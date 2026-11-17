// Stutter and hang monitor: see hitch_monitor.h. Ported from hakuX's hitch.c (GPL-3.0-or-later): activity spans,
// frame windows closed at each emulated frame, the display path keyed by present id, the cadence median, the 40% rule,
// the watchdog that samples an overdue frame. Joey64's own: the emulation thread is the one thread watched (it runs
// the core, acquires, presents and paces), the core's smart-sync GPU-wait counter, and the present timings come from
// VK_GOOGLE_display_timing instead of hakuX's swap chain stages.
#include "hitch_monitor.h"
#include "joeylog.h"
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/ucontext.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)


namespace hitch {

Thr g_emu;
thread_local bool t_emu = false;
std::atomic<bool> g_on{false};

namespace {

const char* const kActName[ACT_N] = {"run", "acquire", "fence", "present", "audio", "pace", "save", "state"};

double g_nsPerTick = 1.0;
double tMs(int64_t t) { return (double)t * g_nsPerTick / 1e6; }
int64_t monoNs() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1000000000ll + ts.tv_nsec; }

pid_t g_tid = 0;
pthread_t g_pth;
clockid_t g_cpuClock;
bool g_haveCpuClock = false;
uint64_t (*g_rdpNs)() = nullptr;
unsigned (*g_rdpN)() = nullptr;

std::atomic<double> g_frameMs{1000.0 / 60.0};
std::atomic<double> g_refreshMs{1000.0 / 60.0};
double g_stutterSet = 0, g_hangMs = 500;
double stutterMs() { return g_stutterSet > 0 ? g_stutterSet : std::max(2.0 * g_frameMs.load(), 50.0); }

// One reading of the emulation thread, at a window's edge.
struct Snap {
    int64_t t = 0;
    int64_t acc[ACT_N] = {};
    uint32_t n[ACT_N] = {};
    int64_t cpuNs = 0;
    uint64_t rdpNs = 0;
    unsigned rdpN = 0;
    long minflt = 0, majflt = 0, nvcsw = 0, nivcsw = 0;
};

Snap take() {
    Snap s;
    s.t = ticks();
    for (int i = 0; i < ACT_N; ++i) { s.acc[i] = g_emu.acc[i]; s.n[i] = g_emu.n[i]; }
    s.acc[g_emu.act.load(std::memory_order_relaxed)] += s.t - g_emu.since.load(std::memory_order_relaxed);
    if (g_haveCpuClock) { timespec ts; clock_gettime(g_cpuClock, &ts); s.cpuNs = ts.tv_sec * 1000000000ll + ts.tv_nsec; }
    if (g_rdpNs) s.rdpNs = g_rdpNs();
    if (g_rdpN) s.rdpN = g_rdpN();
    rusage ru{};
    if (getrusage(RUSAGE_THREAD, &ru) == 0) { s.minflt = ru.ru_minflt; s.majflt = ru.ru_majflt; s.nvcsw = ru.ru_nvcsw; s.nivcsw = ru.ru_nivcsw; }
    return s;
}

// What the watchdog saw while a window was overdue: each place the thread was, counted.
struct Seen { char key[128]; int n; };
struct Samples {
    Seen top[3] = {};
    int nTop = 0, total = 0, asleep = 0;
};

// A closed window: where its time went.
struct Win {
    uint64_t id = ~0ull;
    double ms = 0, act[ACT_N] = {}, rdpMs = 0, cpuMs = 0;
    unsigned rdpN = 0;
    uint32_t n[ACT_N] = {};
    long minflt = 0, majflt = 0, nvcsw = 0, nivcsw = 0;
    Samples smp;
};
Win g_wins[8];

// The open window, shared with the watchdog.
std::atomic<uint64_t> g_winId{0};
std::atomic<int64_t> g_winStart{0}, g_winStartCpu{0};
std::atomic<bool> g_winOpen{false}, g_winSkip{true}, g_hangOpen{false};
Snap g_prev;
bool g_havePrev = false;

// The watchdog's samples of the open window.
std::mutex g_sm;
uint64_t g_smpId = ~0ull;
Seen g_seen[24];
int g_nSeen = 0, g_smpTotal = 0, g_smpAsleep = 0;

void addSeen(uint64_t id, const char* key, bool asleep) {
    std::lock_guard<std::mutex> lk(g_sm);
    if (id != g_smpId) { g_smpId = id; g_nSeen = 0; g_smpTotal = 0; g_smpAsleep = 0; }
    ++g_smpTotal;
    if (asleep) ++g_smpAsleep;
    for (int i = 0; i < g_nSeen; ++i)
        if (!strcmp(g_seen[i].key, key)) { ++g_seen[i].n; return; }
    if (g_nSeen < 24) { snprintf(g_seen[g_nSeen].key, sizeof g_seen[0].key, "%s", key); g_seen[g_nSeen++].n = 1; }
}

Samples samplesOf(uint64_t id, bool clear) {
    std::lock_guard<std::mutex> lk(g_sm);
    Samples s;
    if (id != g_smpId) return s;
    s.total = g_smpTotal; s.asleep = g_smpAsleep;
    Seen sorted[24];
    std::copy(g_seen, g_seen + g_nSeen, sorted);
    std::sort(sorted, sorted + g_nSeen, [](const Seen& a, const Seen& b) { return a.n > b.n; });
    s.nTop = std::min(g_nSeen, 3);
    std::copy(sorted, sorted + s.nTop, s.top);
    if (clear) { g_nSeen = 0; g_smpTotal = 0; g_smpAsleep = 0; g_smpId = ~0ull; }
    return s;
}

// Counts and the last reasons, for logStatus and overlay.
std::atomic<unsigned> g_stutters{0}, g_hangs{0}, g_dropped{0}, g_slips{0}, g_gameRate{0};
// Windows not measured at the start: boot, a dev state load (3 s at 60 fps).
uint64_t g_frames = 0;
const uint64_t kWarmFrames = 180;
std::mutex g_lm;
char g_lastStutter[256] = "", g_lastHang[256] = "";
double g_lastStutterMs = 0, g_lastHangMs = 0;
int64_t g_lastStutterAt = 0, g_lastHangAt = 0;
double g_worstMs = 0;

// At most 12 stutter lines a 5 s window, then counted.
int64_t g_rlSlot = 0;
int g_rlLines = 0, g_rlHeld = 0;
bool allowLine() {
    const int64_t slot = monoNs() / 5000000000ll;
    if (slot != g_rlSlot) {
        if (g_rlHeld) LOGI("stutter: %d more not logged in the last window", g_rlHeld);
        g_rlSlot = slot; g_rlLines = 0; g_rlHeld = 0;
    }
    if (g_rlLines < 12) { ++g_rlLines; return true; }
    ++g_rlHeld;
    return false;
}

int cpuOfThread(pid_t tid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    char buf[1024];
    const size_t n = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[n] = 0;
    const char* p = strrchr(buf, ')');
    if (!p) return -1;
    // fields after the comm: state is field 3; processor is field 39
    int field = 2;
    for (; *p && field < 39; ++p) if (*p == ' ') ++field;
    return *p ? atoi(p) : -1;
}

std::string details(const Win& w) {
    char out[640];
    int k = snprintf(out, sizeof out, "run %.1f, acquire %.1f, fence %.1f, present %.1f, audio %.1f, pace %.1f, save %.1f, state %.1f ms",
                     w.act[RUN], w.act[ACQUIRE], w.act[FENCE], w.act[PRESENT], w.act[AUDIO], w.act[PACE], w.act[SAVE], w.act[STATE]);
    if (g_rdpNs) k += snprintf(out + k, sizeof out - k, "; GPU waits %.1f ms (%u)", w.rdpMs, w.rdpN);
    if (g_haveCpuClock) k += snprintf(out + k, sizeof out - k, "; CPU %.1f ms (%d%%)", w.cpuMs, w.ms > 0 ? (int)(100 * w.cpuMs / w.ms) : 0);
    k += snprintf(out + k, sizeof out - k, "; faults %ld+%ld major; switches %ld, %ld preempted", w.minflt, w.majflt, w.nvcsw, w.nivcsw);
    const int cpu = cpuOfThread(g_tid);
    if (cpu >= 0) {
        char path[96];
        long khz = 0;
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", cpu);
        if (FILE* f = fopen(path, "r")) { if (fscanf(f, "%ld", &khz) != 1) khz = 0; fclose(f); }
        k += snprintf(out + k, sizeof out - k, "; cpu%d %ld MHz", cpu, khz / 1000);
    }
    static bool gpuReadable = true; // Adreno's kgsl clock; elsewhere (or denied) left out
    if (gpuReadable) {
        long hz = 0;
        if (FILE* f = fopen("/sys/class/kgsl/kgsl-3d0/gpuclk", "r")) { if (fscanf(f, "%ld", &hz) != 1) hz = 0; fclose(f); }
        if (hz > 0) k += snprintf(out + k, sizeof out - k, "; GPU %ld MHz", hz / 1000000);
        else gpuReadable = false;
    }
    if (w.smp.total) {
        k += snprintf(out + k, sizeof out - k, "; %d samples, %d asleep:", w.smp.total, w.smp.asleep);
        for (int i = 0; i < w.smp.nTop && k < (int)sizeof out; ++i)
            k += snprintf(out + k, sizeof out - k, "%s %s (%d)", i ? "," : "", w.smp.top[i].key, w.smp.top[i].n);
    }
    return out;
}

// hakuX's explain(): the first rule that takes 40% of the window names it.
std::string explain(const Win& w) {
    const double ms = std::max(w.ms, 0.001), part = 0.4 * ms;
    char out[256];
    // rdpN counts smart sync's waits only; the rest of rdpMs is the core's other GPU waits (SyncFull, scanout, the
    // frame-context fence wait, which is the Flip's Mali one: no timeline semaphores there).
    if (w.rdpMs >= part) {
        if (w.rdpN) snprintf(out, sizeof out, "waiting for the GPU (smart sync, %u waits, %.1f ms)", w.rdpN, w.rdpMs);
        else snprintf(out, sizeof out, "waiting for the GPU (the core's own waits, %.1f ms)", w.rdpMs);
        return out;
    }
    if (w.act[FENCE] >= part) { snprintf(out, sizeof out, "waiting on an earlier frame's GPU work (frame fence %.1f ms)", w.act[FENCE]); return out; }
    if (w.act[ACQUIRE] >= part) { snprintf(out, sizeof out, "no free swapchain image (acquire %.1f ms: the compositor is behind)", w.act[ACQUIRE]); return out; }
    if (w.act[PRESENT] >= part) { snprintf(out, sizeof out, "queue submit / present (%.1f ms)", w.act[PRESENT]); return out; }
    if (w.act[AUDIO] >= part) { snprintf(out, sizeof out, "audio ring full (%.1f ms)", w.act[AUDIO]); return out; }
    if (w.act[SAVE] >= part) { snprintf(out, sizeof out, "writing save RAM (%.1f ms)", w.act[SAVE]); return out; }
    if (w.act[STATE] >= part) { snprintf(out, sizeof out, "save state work (%.1f ms)", w.act[STATE]); return out; }
    if (w.act[PACE] >= part) { snprintf(out, sizeof out, "pacing (audio sync / speed cap %.1f ms)", w.act[PACE]); return out; }
    if (w.majflt >= 4) { snprintf(out, sizeof out, "loading: %ld major page faults", w.majflt); return out; }
    if (w.smp.total >= 3 && w.smp.asleep * 10 >= w.smp.total * 4) {
        snprintf(out, sizeof out, "asleep %d%% of the samples: %s", 100 * w.smp.asleep / w.smp.total, w.smp.nTop ? w.smp.top[0].key : "?");
        return out;
    }
    if (g_haveCpuClock && w.cpuMs >= 0.6 * ms) {
        snprintf(out, sizeof out, "emulation busy (CPU %.0f%%)%s%s", 100 * w.cpuMs / ms, w.smp.nTop ? ": " : "", w.smp.nTop ? w.smp.top[0].key : "");
        return out;
    }
    if (g_haveCpuClock && w.cpuMs < 0.5 * ms && w.nivcsw > 0) {
        snprintf(out, sizeof out, "starved: ran %.0f%% of the time, preempted %ld times", 100 * w.cpuMs / ms, w.nivcsw);
        return out;
    }
    if (g_haveCpuClock && w.cpuMs < 0.5 * ms) { snprintf(out, sizeof out, "asleep %.1f ms outside the marked waits", ms - w.cpuMs); return out; }
    return "spread over several things";
}

void noteStutter(double ms, const std::string& reason) {
    ++g_stutters;
    std::lock_guard<std::mutex> lk(g_lm);
    snprintf(g_lastStutter, sizeof g_lastStutter, "%s", reason.c_str());
    g_lastStutterMs = ms;
    g_lastStutterAt = monoNs();
    g_worstMs = std::max(g_worstMs, ms);
}

// The display path: the present records, keyed by present id.
struct Rec {
    uint32_t id = 0;
    bool used = false, newImage = false, skip = false, shown = false;
    uint64_t win = 0;
    int64_t queuedNs = 0, desiredNs = 0, actualNs = 0, earliestNs = 0;
};
Rec g_recs[256];
std::atomic<int64_t> g_lastShownAt{0};
uint32_t g_lastShownId = 0;
Rec g_lastNew;
bool g_haveLastNew = false;
int g_cad[16], g_nCad = 0, g_cadAt = 0, g_lastCadence = 0;

bool displayActive() { return monoNs() - g_lastShownAt.load(std::memory_order_relaxed) < 2000000000ll; }

int cadence() {
    if (g_nCad < 8) return 0;
    int c[16];
    std::copy(g_cad, g_cad + g_nCad, c);
    std::nth_element(c, c + g_nCad / 2, c + g_nCad);
    return c[g_nCad / 2];
}

// The watchdog: samples the emulation thread once a window is overdue, reports hangs.
volatile uint64_t g_sigPc = 0, g_sigLr = 0;
std::atomic<uint32_t> g_sigSeq{0};
bool g_sigOk = false;

void onProf(int, siginfo_t*, void* ucv) {
    if (gettid() != g_tid) return;
    const ucontext_t* uc = (const ucontext_t*)ucv;
#if defined(__aarch64__)
    g_sigPc = uc->uc_mcontext.pc;
    g_sigLr = uc->uc_mcontext.regs[30];
#endif
    g_sigSeq.fetch_add(1, std::memory_order_release);
}

void where(uint64_t pc, char* out, size_t n) {
    Dl_info di{};
    if (pc && dladdr((void*)pc, &di) && di.dli_fname) {
        const char* base = strrchr(di.dli_fname, '/');
        base = base ? base + 1 : di.dli_fname;
        if (di.dli_sname) snprintf(out, n, "%s %s+0x%llx", base, di.dli_sname, (unsigned long long)(pc - (uint64_t)di.dli_saddr));
        else snprintf(out, n, "%s+0x%llx", base, (unsigned long long)(pc - (uint64_t)di.dli_fbase));
    } else snprintf(out, n, "dynarec code");
}

const char* syscallName(long nr) {
    switch (nr) {
        case 98: return "futex"; case 73: return "ppoll"; case 101: return "nanosleep"; case 115: return "clock_nanosleep";
        case 29: return "ioctl"; case 63: return "read"; case 64: return "write"; case 67: return "pread"; case 68: return "pwrite";
        case 222: return "mmap"; case 215: return "munmap"; case 226: return "mprotect"; case 233: return "madvise";
        case 22: return "epoll_pwait"; case 82: return "fsync"; case 57: return "close"; case 56: return "openat";
        case 124: return "sched_yield"; case 216: return "mremap";
        default: return nullptr;
    }
}

bool readFile(const char* path, char* buf, size_t n) {
    FILE* f = fopen(path, "r");
    if (!f) return false;
    const size_t k = fread(buf, 1, n - 1, f);
    fclose(f);
    buf[k] = 0;
    for (size_t len = k; len && buf[len - 1] == '\n'; --len) buf[len - 1] = 0;
    return k > 0;
}

void sampleOnce(uint64_t id) {
    char path[64], stat[512], key[128];
    snprintf(path, sizeof path, "/proc/self/task/%d/stat", g_tid);
    if (!readFile(path, stat, sizeof stat)) return;
    const char* p = strrchr(stat, ')');
    const char state = p && p[1] && p[2] ? p[2] : '?';
    const char* act = kActName[std::min<uint32_t>(g_emu.act.load(std::memory_order_relaxed), ACT_N - 1)];
    if (state == 'R') {
        const uint32_t seq = g_sigSeq.load(std::memory_order_acquire);
        bool got = false;
        if (g_sigOk && pthread_kill(g_pth, SIGPROF) == 0)
            for (int i = 0; i < 200 && !got; ++i) { if (g_sigSeq.load(std::memory_order_acquire) != seq) got = true; else usleep(10); }
        if (!got) { snprintf(key, sizeof key, "[%s] running", act); addSeen(id, key, false); return; }
        char a[64], b[64];
        where(g_sigPc, a, sizeof a);
        where(g_sigLr, b, sizeof b);
        if (!strcmp(a, b)) snprintf(key, sizeof key, "[%s] running in %s", act, a);
        else snprintf(key, sizeof key, "[%s] running in %s from %s", act, a, b);
        addSeen(id, key, false);
        return;
    }
    char sc[256] = "", wchan[64] = "";
    snprintf(path, sizeof path, "/proc/self/task/%d/syscall", g_tid);
    readFile(path, sc, sizeof sc);
    snprintf(path, sizeof path, "/proc/self/task/%d/wchan", g_tid);
    readFile(path, wchan, sizeof wchan);
    const bool haveWchan = wchan[0] && strcmp(wchan, "0");
    long nr = -2;
    unsigned long long a[6] = {}, sp = 0, pc = 0;
    if (sc[0] && strcmp(sc, "running")) {
        if (sscanf(sc, "%ld 0x%llx 0x%llx 0x%llx 0x%llx 0x%llx 0x%llx 0x%llx 0x%llx", &nr, &a[0], &a[1], &a[2], &a[3], &a[4], &a[5], &sp, &pc) < 9)
            sscanf(sc, "%ld 0x%llx 0x%llx", &nr, &sp, &pc);
    }
    char from[64];
    where(pc, from, sizeof from);
    if (nr == -1) snprintf(key, sizeof key, "[%s] %s, not in a system call (a page fault?) at %s%s%s", act, state == 'D' ? "blocked" : "asleep", from,
                           haveWchan ? " in " : "", haveWchan ? wchan : "");
    else if (nr >= 0) {
        const char* name = syscallName(nr);
        char num[16];
        if (!name) { snprintf(num, sizeof num, "syscall %ld", nr); name = num; }
        snprintf(key, sizeof key, "[%s] %s in %s%s%s from %s", act, state == 'D' ? "blocked" : "asleep", name,
                 haveWchan ? " / " : "", haveWchan ? wchan : "", from);
    } else snprintf(key, sizeof key, "[%s] %s%s%s", act, state == 'D' ? "blocked" : "asleep", haveWchan ? " in " : "", haveWchan ? wchan : "");
    addSeen(id, key, true);
}

void watchdog() {
    pthread_setname_np(pthread_self(), "joey64.hitch");
    uint64_t hangId = ~0ull;
    int64_t hangLogAt = 0;
    for (;;) {
        if (!g_winOpen.load(std::memory_order_acquire) || g_winSkip.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        const uint64_t id = g_winId.load(std::memory_order_acquire);
        const double el = tMs(ticks() - g_winStart.load(std::memory_order_acquire));
        static const double slowMs = getenv("N64_HITCH_SLOW_MS") ? atof(getenv("N64_HITCH_SLOW_MS")) : 0;
        double at = displayActive() ? 1.5 * g_frameMs.load() : 0.8 * stutterMs();
        if (slowMs > 0) at = std::min(at, 0.8 * slowMs);
        if (el < at) {
            std::this_thread::sleep_for(std::chrono::microseconds((long long)std::clamp((at - el) * 1000.0, 1000.0, 10000.0)));
            continue;
        }
        sampleOnce(id);
        if (el >= g_hangMs && id == g_winId.load(std::memory_order_acquire)) {
            const int64_t now = monoNs();
            if (id != hangId || now - hangLogAt >= 1000000000ll) {
                const bool first = id != hangId;
                hangId = id; hangLogAt = now;
                double cpuMs = -1;
                if (g_haveCpuClock) { timespec ts; clock_gettime(g_cpuClock, &ts); cpuMs = (ts.tv_sec * 1000000000ll + ts.tv_nsec - g_winStartCpu.load()) / 1e6; }
                const Samples s = samplesOf(id, false);
                char top[420] = "";
                int k = 0;
                for (int i = 0; i < s.nTop; ++i) k += snprintf(top + k, sizeof top - k, "%s %s (%d)", i ? "," : "", s.top[i].key, s.top[i].n);
                if (first) {
                    ++g_hangs;
                    g_hangOpen = true;
                    std::lock_guard<std::mutex> lk(g_lm);
                    snprintf(g_lastHang, sizeof g_lastHang, "%s", s.nTop ? s.top[0].key : kActName[std::min<uint32_t>(g_emu.act.load(), ACT_N - 1)]);
                    g_lastHangAt = now;
                }
                LOGI("HANG %s: frame open %.0f ms, in %s, CPU %.0f ms of it; %d samples, %d asleep:%s", first ? "started" : "ongoing", el,
                     kActName[std::min<uint32_t>(g_emu.act.load(), ACT_N - 1)], cpuMs, s.total, s.asleep, top);
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

void closeWindow(uint64_t id, const Snap& a, const Snap& b, const Samples& smp) {
    Win& w = g_wins[id & 7];
    w = Win{};
    w.id = id;
    w.ms = tMs(b.t - a.t);
    for (int i = 0; i < ACT_N; ++i) { w.act[i] = tMs(b.acc[i] - a.acc[i]); w.n[i] = b.n[i] - a.n[i]; }
    w.rdpMs = (b.rdpNs - a.rdpNs) / 1e6;
    w.rdpN = b.rdpN - a.rdpN;
    w.cpuMs = (b.cpuNs - a.cpuNs) / 1e6;
    w.minflt = b.minflt - a.minflt; w.majflt = b.majflt - a.majflt; w.nvcsw = b.nvcsw - a.nvcsw; w.nivcsw = b.nivcsw - a.nivcsw;
    w.smp = smp;
    if (w.ms >= g_hangMs) {
        if (!g_hangOpen.exchange(false)) ++g_hangs;
        const std::string why = explain(w);
        {
            std::lock_guard<std::mutex> lk(g_lm);
            snprintf(g_lastHang, sizeof g_lastHang, "%s", why.c_str());
            g_lastHangMs = w.ms;
            g_lastHangAt = monoNs();
        }
        LOGI("HANG over: frame %.0f ms: %s | %s", w.ms, why.c_str(), details(w).c_str());
        return;
    }
    g_hangOpen = false;
    // Dev: N64_HITCH_SLOW_MS=<ms> also logs every frame whose work (the window less its pacing) passes that, as
    // "slow frame" lines, not counted as stutters: what makes a frame go over its budget without missing a picture.
    static const double slowMs = getenv("N64_HITCH_SLOW_MS") ? atof(getenv("N64_HITCH_SLOW_MS")) : 0;
    if (slowMs > 0 && w.ms - w.act[PACE] >= slowMs && allowLine())
        LOGI("slow frame: %.1f ms of work (window %.1f): %s | %s", w.ms - w.act[PACE], w.ms, explain(w).c_str(), details(w).c_str());
    if (!displayActive() && w.ms >= stutterMs()) {
        const std::string why = explain(w);
        noteStutter(w.ms, why);
        if (allowLine()) LOGI("stutter #%u: frame %.1f ms (over %.0f): %s | %s", g_stutters.load(), w.ms, stutterMs(), why.c_str(), details(w).c_str());
    }
}

} // namespace

void emuThread(uint64_t (*rdpWaitNs)(), unsigned (*rdpWaits)()) {
    const char* off = getenv("N64_HITCH");
    if (off && *off == '0') { g_on = false; LOGI("monitor: off (N64_HITCH=0)"); return; }
#if defined(__aarch64__)
    uint64_t f;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
    g_nsPerTick = f ? 1e9 / (double)f : 1.0;
#endif
    if (const char* s = getenv("N64_HITCH_MS")) g_stutterSet = atof(s);
    if (const char* s = getenv("N64_HANG_MS")) g_hangMs = std::max(50.0, atof(s));
    t_emu = true;
    g_tid = gettid();
    g_pth = pthread_self();
    g_haveCpuClock = pthread_getcpuclockid(g_pth, &g_cpuClock) == 0;
    g_rdpNs = rdpWaitNs;
    g_rdpN = rdpWaits;
    g_emu.since.store(ticks());
    static bool started = false;
    if (!started) {
        started = true;
        struct sigaction old{};
        if (sigaction(SIGPROF, nullptr, &old) == 0 && old.sa_handler == SIG_DFL && !(old.sa_flags & SA_SIGINFO)) {
            struct sigaction sa{};
            sa.sa_sigaction = onProf;
            sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
            sigemptyset(&sa.sa_mask);
            g_sigOk = sigaction(SIGPROF, &sa, nullptr) == 0;
        }
        std::thread(watchdog).detach();
    }
    g_on = true;
    LOGI("monitor: on (stutter: a picture a whole frame late, or a frame of %.0f ms without present timings; hang %.0f ms; PC samples %s)",
         stutterMs(), g_hangMs, g_sigOk ? "on" : "off");
}

void rates(double gameFps) {
    if (gameFps > 1) g_frameMs = 1000.0 / gameFps;
}

void frame(bool skip) {
    if (!t_emu || !g_on.load(std::memory_order_relaxed)) return;
    const Snap s = take();
    if (++g_frames <= kWarmFrames) skip = true;
    const uint64_t id = g_winId.load(std::memory_order_relaxed);
    const Samples smp = samplesOf(id, true);
    if (g_havePrev && !g_winSkip.load(std::memory_order_relaxed)) closeWindow(id, g_prev, s, smp);
    else g_hangOpen = false;
    g_prev = s;
    g_havePrev = true;
    g_winStart.store(s.t, std::memory_order_relaxed);
    g_winStartCpu.store(s.cpuNs, std::memory_order_relaxed);
    g_winSkip.store(skip, std::memory_order_relaxed);
    g_winId.store(id + 1, std::memory_order_release);
    g_winOpen.store(true, std::memory_order_release);
}

void unmeasured() {
    if (t_emu) g_winSkip.store(true, std::memory_order_release);
}

void presented(uint32_t presentId, bool newImage, int64_t queuedNs, int64_t desiredNs) {
    if (!t_emu || !g_on.load(std::memory_order_relaxed)) return;
    Rec& r = g_recs[presentId & 255];
    r = Rec{};
    r.id = presentId;
    r.used = true;
    r.newImage = newImage;
    r.skip = g_winSkip.load(std::memory_order_relaxed);
    r.win = g_winId.load(std::memory_order_relaxed);
    r.queuedNs = queuedNs;
    r.desiredNs = desiredNs;
}

void shown(uint32_t presentId, int64_t actualNs, int64_t earliestNs, uint64_t refreshNs) {
    if (!t_emu || !g_on.load(std::memory_order_relaxed) || !refreshNs) return;
    const double refresh = (double)refreshNs;
    g_refreshMs = refresh / 1e6;
    g_lastShownAt.store(monoNs(), std::memory_order_relaxed);
    Rec& r = g_recs[presentId & 255];
    if (!r.used || r.id != presentId) return;
    // New pictures queued before this one and never reported shown were dropped.
    if (g_lastShownId && presentId > g_lastShownId + 1 && presentId - g_lastShownId < 256) {
        unsigned lost = 0;
        for (uint32_t k = g_lastShownId + 1; k < presentId; ++k) {
            const Rec& m = g_recs[k & 255];
            if (m.used && m.id == k && m.newImage && !m.shown && !m.skip) ++lost;
        }
        if (lost) {
            g_dropped += lost;
            if (allowLine()) LOGI("stutter: %u new picture%s never shown (presents %u-%u)", lost, lost > 1 ? "s" : "", g_lastShownId + 1, presentId - 1);
        }
    }
    g_lastShownId = presentId;
    r.shown = true;
    r.actualNs = actualNs;
    r.earliestNs = earliestNs;
    if (!r.newImage) return;
    if (r.skip || !g_haveLastNew) { g_lastNew = r; g_haveLastNew = !r.skip; return; }
    const Rec prev = g_lastNew;
    g_lastNew = r;
    const int iv = (int)std::lround((double)(actualNs - prev.actualNs) / refresh);
    if (iv <= 0 || iv > 600) return;
    g_cad[g_cadAt] = iv; g_cadAt = (g_cadAt + 1) & 15; g_nCad = std::min(g_nCad + 1, 16);
    const int cad = cadence();
    if (!cad) return;
    if (g_lastCadence && cad != g_lastCadence) { g_lastCadence = cad; return; } // a cadence change is forgiven once
    g_lastCadence = cad;
    if (iv <= cad) return;
    // The emulated frames between the two pictures put the next one at n frame periods: a game that draws a picture
    // every 2 or 3 frames (Perfect Dark, 20-30 fps) is on time at either. Later than that by a whole frame period is a
    // stutter; by less, a slip (counted).
    const double perFrame = std::max(1.0, g_frameMs.load() / g_refreshMs.load());
    const int nWin = (int)std::min<uint64_t>(r.win - prev.win, 600);
    const double due = std::max<double>(cad, nWin * perFrame);
    if (iv <= std::lround(due)) { ++g_gameRate; return; }
    if (iv < due + perFrame - 0.01) { ++g_slips; return; }
    // A whole frame late: whose fault. The longest emulated frame since the last picture first.
    const Win* worst = nullptr;
    for (const Win& w : g_wins)
        if (w.id != ~0ull && w.id > prev.win && w.id <= r.win && (!worst || w.ms > worst->ms)) worst = &w;
    const double ivMs = (actualNs - prev.actualNs) / 1e6;
    char why[320];
    if (worst && worst->ms >= 1.5 * g_frameMs.load() && worst->act[PACE] < 0.4 * worst->ms) snprintf(why, sizeof why, "a slow frame (%.1f ms): %s", worst->ms, explain(*worst).c_str());
    else if (earliestNs && earliestNs - prev.actualNs > (cad + 0.5) * refresh)
        snprintf(why, sizeof why, "the picture was ready late (GPU or queue): could show %.1f ms after the last", (earliestNs - prev.actualNs) / 1e6);
    else if (earliestNs && actualNs - earliestNs > 0.5 * refresh)
        snprintf(why, sizeof why, "shown %.1f ms after it could have been (compositor or pacing)", (actualNs - earliestNs) / 1e6);
    else snprintf(why, sizeof why, "queued late: %.1f ms after the last picture was queued", (r.queuedNs - prev.queuedNs) / 1e6);
    noteStutter(ivMs, why);
    if (allowLine())
        LOGI("stutter #%u: %.1f ms between pictures (%d refreshes, cadence %d): %s%s%s", g_stutters.load(), ivMs, iv, cad, why,
             worst ? " | " : "", worst ? details(*worst).c_str() : "");
}

bool overlay(char* buf, size_t n) {
    if (!g_on.load() || !n) return false;
    const int64_t now = monoNs();
    std::lock_guard<std::mutex> lk(g_lm);
    int k = snprintf(buf, n, "stutters %u | hangs %u | dropped %u", g_stutters.load(), g_hangs.load(), g_dropped.load());
    if (g_hangOpen.load()) k += snprintf(buf + k, n - std::min<size_t>(n, k), "\nHANG NOW: %s", g_lastHang);
    else if (g_lastHangAt) k += snprintf(buf + k, n - std::min<size_t>(n, k), "\nlast hang %.0f ms, %.0f s ago: %s", g_lastHangMs, (now - g_lastHangAt) / 1e9, g_lastHang);
    if (g_lastStutterAt)
        snprintf(buf + k, n - std::min<size_t>(n, k), "\nlast stutter %.0f ms, %.0f s ago: %s", g_lastStutterMs, (now - g_lastStutterAt) / 1e9, g_lastStutter);
    return true;
}

void logStatus() {
    if (!g_on.load()) return;
    static unsigned s0 = 0, h0 = 0, d0 = 0, l0 = 0, g0 = 0;
    const unsigned s = g_stutters, h = g_hangs, d = g_dropped, l = g_slips, g = g_gameRate;
    if (s == s0 && h == h0 && d == d0 && l == l0) { g0 = g; return; }
    double worst;
    { std::lock_guard<std::mutex> lk(g_lm); worst = g_worstMs; g_worstMs = 0; }
    LOGI("monitor: %u stutters (%u new, worst %.1f ms), %u hangs, %u pictures dropped, %u shown a refresh late (%u new), "
         "%u slower pictures from the game's own frame rate (%u new)", s, s - s0, worst, h, d, l, l - l0, g, g - g0);
    s0 = s; h0 = h; d0 = d; l0 = l; g0 = g;
}

} // namespace hitch
