#pragma once
// Stutter and hang monitor, ported from hakuX's (hw/xbox/hle/hitch.c, hitch.h; hakuX is GPL-3.0-or-later, as Joey64 is).
//
// What the screen shows decides a stutter: with present timings (VK_GOOGLE_display_timing), each new picture's actual
// display time against the game's cadence (the median of the recent front intervals in whole refreshes); a picture shown
// more than half a refresh after its cadence, or new pictures never shown, is a stutter, put on the stage that made it
// late. Without present timings, an emulated-frame window of two frame periods (at least 50 ms) is one. A window of
// 500 ms or more is a hang, reported once a second while it lasts.
//
// Where the time went: the emulation thread marks activity spans at its rare wait points (acquire, the frame fence,
// present, the audio ring, pacing, save writes, save states), never per draw; one counter read each (cntvct_el0). The
// core's smart-sync GPU waits come from its own counter (joey64_rdp_wait_ns). Each window also has the thread's CPU
// time, page faults and context switches. A watchdog thread wakes every 10 ms; once a window is overdue it samples the
// emulation thread every millisecond: asleep, the kernel's wait channel and the system call (or the fault) with the
// user address it came from; running, its PC and return address (SIGPROF). The first rule taking 40% of the window
// names the reason. Lines go to the session log (tag joey64host, "stutter #" / "HANG"); N64_HITCH=0 turns it off,
// N64_HITCH_MS=<ms> sets the stutter threshold, N64_HANG_MS=<ms> the hang one.
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace hitch {

enum Act : uint32_t {
    RUN,        // emulating (retro_run: the core's CPU, RSP and RDP command work)
    ACQUIRE,    // vkAcquireNextImageKHR: no free swapchain image
    FENCE,      // waiting on an earlier frame's GPU work (VkContext::waitFrameFence, the core's wait_sync_index)
    PRESENT,    // queue submit and present
    AUDIO,      // the audio ring full (AudioOut::push sleeping)
    PACE,       // pacing: the audio-sync wait, the speed cap, timed pacing's repeats
    SAVE,       // writing save RAM
    STATE,      // save states: requests, rewind, the state ring, run-ahead
    ACT_N
};

struct Thr {
    std::atomic<uint32_t> act{RUN};
    std::atomic<int64_t> since{0};
    int64_t acc[ACT_N] = {};
    uint32_t n[ACT_N] = {};
};

extern Thr g_emu;
extern thread_local bool t_emu;
extern std::atomic<bool> g_on;

inline int64_t ticks() {
#if defined(__aarch64__)
    uint64_t t;
    __asm__ volatile("mrs %0, cntvct_el0" : "=r"(t));
    return (int64_t)t;
#else
    return 0;
#endif
}

inline void set(Thr& h, uint32_t a) {
    const int64_t t = ticks();
    h.acc[h.act.load(std::memory_order_relaxed)] += t - h.since.load(std::memory_order_relaxed);
    h.since.store(t, std::memory_order_relaxed);
    h.act.store(a, std::memory_order_release);
}

// Enter an activity on the emulation thread; returns what to restore. One other than RUN in progress is kept (the
// more specific: a present inside pacing's repeats stays pacing).
inline uint32_t enter(uint32_t a) {
    if (!t_emu || !g_on.load(std::memory_order_relaxed)) return ACT_N;
    const uint32_t prev = g_emu.act.load(std::memory_order_relaxed);
    if (prev != RUN && a != RUN) return ACT_N;
    g_emu.n[a]++;
    set(g_emu, a);
    return prev;
}

inline void leave(uint32_t prev) {
    if (prev < ACT_N && t_emu) set(g_emu, prev);
}

struct Span {
    uint32_t prev;
    explicit Span(uint32_t a) : prev(enter(a)) {}
    ~Span() { leave(prev); }
    Span(const Span&) = delete;
    Span& operator=(const Span&) = delete;
};

// The emulation thread, at its start. The core's GPU-wait counters (null on a core without them).
void emuThread(uint64_t (*rdpWaitNs)(), unsigned (*rdpWaits)());
// The game's frame rate (retro_get_system_av_info), for the thresholds.
void rates(double gameFps);
// The open window isn't measured after all (the swapchain was rebuilt in it, or the game is paused).
void unmeasured();
// The emulation thread is ending: no window stays open and the watchdog never signals the thread again (release audit
// 2026-11-17: a pause or a finished thread read as a hang, and a sample could pthread_kill a thread that was gone).
void threadEnd();
// The game was paused and goes on now: the next 30 frames aren't measured (the first frame after a resume waits for
// the audio and its picture is timed against the pause: two "stutters" each resume, Odin 2 Portal 2026-11-17).
void resumed();
// Each emulated frame starts (closes the window the last one opened). skip: this window isn't measured (paused,
// frame advance, fast-forward, slow motion, rewind, a benchmark, a rebuilt swapchain).
void frame(bool skip);
// A present was queued (VkContext::present): its id, whether it carries a new picture (not a repeat), the time it was
// queued and the display time it asked for (0 = none). CLOCK_MONOTONIC ns.
void presented(uint32_t presentId, bool newImage, int64_t queuedNs, int64_t desiredNs);
// Its present timing came back (VkContext::collectTiming): when it was shown, the earliest it could have been.
void shown(uint32_t presentId, int64_t actualNs, int64_t earliestNs, uint64_t refreshNs);
// The counts and the last stutter and hang with their reasons, for the stats log and an overlay; false when off.
bool overlay(char* buf, size_t n);
// A "monitor:" log line when the counts changed since the last call (the stats line's cadence).
void logStatus();

} // namespace hitch
