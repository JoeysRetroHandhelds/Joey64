#pragma once
#include <aaudio/AAudio.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

// AAudio low-latency output fed by a lock-free SPSC ring buffer of interleaved stereo float
// (float matches the device mixer, which the fast path needs).
// The stream runs at the device's native rate (so Android grants the fast path); write()
// linearly resamples from the core's rate.
class AudioOut {
public:
    bool open(int srcRate);
    void close();
    // Called from the emulation thread. Only blocks if the ring is completely full.
    void write(const int16_t* frames, size_t frameCount);
    // Fast-forward sound (Settings › Audio): never blocks. Resamples [speed] times faster (sped up, higher
    // pitch), steered by the ring's fill so a game running below or above that speed neither runs dry nor
    // piles up; what doesn't fit is dropped. [gain] scales it (Quieter).
    void writeFast(const int16_t* frames, size_t frameCount, double speed, float gain);
    // Settings › Audio, any thread, applied in the callback (heard at once): volume 0-100 (a loudness
    // curve: gain = (v/100)^2), mute, mono (L+R averaged).
    void setMix(int volumePct, bool mute, bool mono);
    // Latency (Settings › Audio), read when the stream opens: 0 Auto (20 ms, grows to 150 on
    // underruns at full speed, as before), 1 Low (10 ms, never grows), 2 Safe (60 ms, grows to 150).
    void setLatencyMode(int mode) { latencyMode_ = mode; }
    void setPaused(bool p);
    // Milliseconds of sound queued in the ring now (the resume log line).
    double fillMs() const { return dstRate_ ? fill() / 2 * 1000.0 / dstRate_ : 0; }
    // Audio sync, called before each emulated frame: blocks until the ring holds at most the target
    // plus one frame, so the wait lands before the frame polls input rather than in the middle of it.
    // Also reopens the stream when the output device went away (see onError).
    void waitForRoom();
    // The panel's refresh period (0 = unknown), each frame before waitForRoom: smoothing is automatic on panels
    // faster than 60 Hz (see smooth_).
    void setPanelRefreshNs(uint64_t ns);
    // Device-side output latency in ms (AAudio timestamps: frames written but not yet played),
    // -1 if unknown. AAudio's buffer starts at 2 bursts and grows a burst per xrun.
    double outputLatencyMs();
    // Stats since the last call: mean ring fill and target in ms, underrun callbacks. keepingUp =
    // the emulation ran at full speed meanwhile: underruns then raise the target by 10 ms (devices
    // whose frames sometimes miss a vsync need more queued), up to kMaxTargetMs.
    void takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns);
    // Underruns since the last takeStats (N64_FRAME_TRACE).
    int underrunsNow() const { return underruns_.load(std::memory_order_relaxed); }
    // Audio frames per second since the last call: from the game (write) and taken by the device's callbacks. The
    // T820 report (2026-11-08) drained the ring at 61 emulated fps in windows where half the frames repeat; these
    // tell a game that brings less audio from a device that plays faster than its stated rate.
    void takeRates(double secs, double& inHz, double& outHz);
    // The ring's fill as a percent of the target (100 = at the target or above), for the core's audio buffer status
    // callback (its automatic frame skip, N64_FRAMESKIP=2, treats 33 or less as falling behind).
    unsigned occupancyPct() const { return target_ ? (unsigned)std::min<size_t>(100, fill() * 100 / target_) : 100; }
    // Whether the ring ran dry since the last call: a game below full speed drains it within each slow frame, so the
    // fill seen before a frame can still look healthy (Duo at 70% speed: 15 of 20 ms on average, 824 underruns in 5 s).
    bool ranDrySinceLastAsked() { unsigned n = underrunsTotal_.load(std::memory_order_relaxed); bool dry = n != dryAsked_; dryAsked_ = n; return dry; }

private:
    static constexpr int kTargetMs = 20, kMaxTargetMs = 150;
    static aaudio_data_callback_result_t cb(AAudioStream*, void* user, void* data, int32_t frames);
    // A headset or Bluetooth device coming or going, or another route change, disconnects the
    // stream: its callbacks stop, so the ring would fill and every write stall. The error callback
    // only flags it (a stream can't be closed from its own callbacks); the emulation thread reopens
    // on the default device at the next frame, and retries once a second if that fails.
    static void onError(AAudioStream*, void* user, aaudio_result_t error);
    bool openStream();
    void reopenIfLost();
    std::mutex streamMutex_;  // stream_ lifetime vs setPaused (UI thread)
    std::atomic<bool> lost_{false};
    bool stalled_ = false;    // push gave up on a full ring; the emulation thread's own (push, open)
    bool paused_ = false;
    int srcRate_ = 0;
    int64_t retryNs_ = 0;     // when to try opening again after a failed reopen (0 = not failed)
    AAudioStream* stream_ = nullptr;
    std::vector<float> ring_;
    size_t cap_ = 0; // in samples
    std::atomic<size_t> r_{0}, w_{0};
    bool push(float s);
    std::atomic<float> gain_{1.0f};
    std::atomic<bool> mono_{false};
    std::atomic<int> latencyMode_{0};
    bool grow_ = true;       // underruns may raise the target (not on Low)
    size_t fill() const { return (w_.load(std::memory_order_acquire) + cap_ - r_.load(std::memory_order_acquire)) % cap_; }
    int dstRate_ = 48000;
    size_t target_ = 0;      // samples the ring is kept near (audio latency)
    std::atomic<int> underruns_{0};
    std::atomic<unsigned> underrunsTotal_{0}; // never reset (ranDrySinceLastAsked)
    unsigned dryAsked_ = 0;
    // The game has put sound in the ring since open(). Before that the stream plays silence that isn't an
    // underrun (Paper Mario on the Portal 2026-11-14: 80-210 counted in every start's first 5 s).
    std::atomic<bool> fed_{false};
    // Resume (queue item "Resume wait"): the ring still holds the sound queued before the pause, and the stream
    // starts some ms after requestStart, so the first frame's audio wait slept until it had played (Odin 2 Portal
    // OoT: ~74 ms, the first picture ~190 ms after the last). setPaused(false) sets resumeDrop_; while it's set
    // waitForRoom doesn't wait (the speed cap paces), and the callback's first run drops the oldest samples down
    // to keep_ (the target, kept by the game thread) and clears it. N64_RESUME_DROP=0: the old wait.
    std::atomic<bool> resumeDrop_{false};
    std::atomic<size_t> keep_{0};
    bool resumeDropOn_ = true;
    int resumeFrames_ = 0;   // frames waitForRoom let through since the resume
    std::atomic<long long> srcFrames_{0}, outFrames_{0}; // takeRates
    double fillSum_ = 0; int fillCount_ = 0;
    double baseStep_ = 1.0;  // nominal source frames per output frame
    double drcI_ = 0;        // integral part of the rate correction
    int frames_ = 0;         // waitForRoom calls since open (startup frames don't adapt the target)
    int xruns_ = 0;          // AAudio xrun count at the last check
    // Smoothed audio wait: games that hand over audio unevenly between frames (Perfect Dark: 0, then two frames'
    // worth, every other frame) made audio sync start frames ~5 then ~28 ms apart (judder on 120 Hz). The wait then
    // compares the fill minus excess_, a fading sum of each frame's audio above the running average. On by default
    // on panels faster than 60 Hz (Duo/Odin 3: Perfect Dark uneven 244-246 -> 28-39 per 5 s, lag 1.6-2.6 ms lower,
    // other games unchanged); off at 60 Hz, where vsync paces and it gained nothing (Flip). N64_AUDIO_SMOOTH=1/0
    // forces it on/off. smoothMode_: -1 auto, 0 off, 1 on.
    int smoothMode_ = -1;
    bool smooth_ = false;
    size_t lastW_ = 0;       // write index at the last waitForRoom
    double avgWrite_ = -1;   // running average of samples written per frame (-1 = none yet)
    double excess_ = 0;      // fading sum of (written - average), samples
    double peak_ = 0;        // the most written in one frame lately, fading (~1%/frame), samples: see waitForRoom
    double step_ = 1.0;      // source frames per output frame
    double pos_ = 0.0;       // position between prev_ and the next source frame
    int16_t prev_[2] = {0, 0};
    // Dev switches (2026-11-18, morning report item 7, ideas from altru's design notes, our own code), all off unless set:
    // N64_AUDIO_FADE=1: an underrun fades the last sample out over kFadeFrames instead of a step to zero, and the sound
    // after it fades back in (a click each way without).
    bool fadeOn_ = false;
    static constexpr int kFadeFrames = 64;
    float last_[2] = {0, 0};           // the callback's last output frame (before gain)
    // The resume drop's crossfade (PPSSPP a071d82e02, 2026-11-21): the frames that would have played next, faded out
    // over the first kXfadeFrames after the jump while the kept sound fades in. N64_AUDIO_XFADE=0: a plain jump.
    static constexpr int kXfadeFrames = 128;
    bool xfadeOn_ = true;
    float xfade_[kXfadeFrames * 2] = {};
    int xfadeN_ = 0, xfadeAt_ = 0;     // frames in xfade_, and how many of them are played (callback thread)
    int fadeIn_ = 0;                   // frames of fade-in left after an underrun (callback thread)
    // N64_AUDIO_SHRINK=1: after 10 s (two stats windows) without an underrun, the grown target comes down 5 ms a window,
    // to where it started, so one bad scene doesn't keep its extra latency for the whole play.
    bool shrinkOn_ = false;
    int growOnceMs_ = 0;               // N64_AUDIO_GROW_ONCE (test only)
    bool growOnceDone_ = false;
    size_t startTarget_ = 0;
    int quietWindows_ = 0;
    // N64_FF_PITCH=1: fast-forward (speed above 1) keeps normal pitch: of every speed x 20 ms of the game's sound, 20 ms
    // is kept, each kept piece crossfaded over 2 ms into the one before; the fill steers how much is dropped, not the pitch.
    bool ffPitchOn_ = false;
    size_t ffPos_ = 0;                 // source frames into the current block
    double ffLen_ = 0;                 // this block's length in source frames
    int16_t ffTail_[2 * 96] = {};      // the previous block's frames just after its kept piece (for the crossfade)
    int16_t ffNext_[2 * 96] = {};
    bool ffHaveTail_ = false;
    void ffEmit(const int16_t* f, float k); // one source frame through the normal-rate resampler into the ring
    // N64_AUDIO_CAPTURE=<file> (dev, 2026-11-18): the callback's output (after gain) for the first 30 s after the game's
    // first sound, written as raw float stereo at the device rate (a .f32 file) once full, from takeStats.
    std::vector<float> cap_buf_;
    std::atomic<size_t> capN_{0};
    bool capWritten_ = false;
    std::string capPath_;
};
