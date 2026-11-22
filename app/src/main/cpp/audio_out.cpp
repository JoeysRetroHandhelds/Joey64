#include "joeylog.h"
#include "audio_out.h"
#include "hitch_monitor.h"
#include <android/log.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <thread>
#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

#define LOGI(...) joeyLog(ANDROID_LOG_INFO, "joey64host", __VA_ARGS__)

namespace {
int64_t nowNs() {
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
}

bool AudioOut::open(int srcRate) {
    std::lock_guard<std::mutex> lk(streamMutex_);
    // A second open (another game in this process) closes the stream it had first.
    if (stream_) { AAudioStream_requestStop(stream_); AAudioStream_close(stream_); stream_ = nullptr; }
    stalled_ = false;
    fed_.store(false, std::memory_order_relaxed);
    srcRate_ = srcRate;
    paused_ = false;
    lost_ = false;
    retryNs_ = 0;
    return openStream();
}

void AudioOut::onError(AAudioStream*, void* user, aaudio_result_t error) {
    LOGE("audio: stream error %d (%s)", error, AAudio_convertResultToText(error));
    if (error == AAUDIO_ERROR_DISCONNECTED) static_cast<AudioOut*>(user)->lost_ = true;
}

void AudioOut::reopenIfLost() {
    if (!lost_ && !retryNs_) return;
    if (retryNs_ && nowNs() < retryNs_) return;
    std::lock_guard<std::mutex> lk(streamMutex_);
    lost_ = false;
    if (stream_) { AAudioStream_requestStop(stream_); AAudioStream_close(stream_); stream_ = nullptr; }
    if (openStream()) {
        retryNs_ = 0;
        LOGI("audio: reopened after the output device changed");
        if (paused_) AAudioStream_requestPause(stream_);
    } else {
        retryNs_ = nowNs() + 1000000000LL;
    }
}

bool AudioOut::openStream() {
    const int srcRate = srcRate_;
    AAudioStreamBuilder* b = nullptr;
    AAudio_createStreamBuilder(&b);
    // No sample rate set: AAudio picks the device's native rate.
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // A game's sound, matching the audio focus MainActivity takes (Settings › Audio, Play alongside other apps).
    const char* usage = getenv("N64_AUDIO_USAGE");
    if (!usage || *usage != '0') {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    }
    // Exclusive = AAudio's MMAP path where the device allows it, else AAudio falls back to shared (Flip).
    // Device latency: Duo 14-16 -> 10 ms, Odin 3 14 -> 9 ms. N64_AUDIO_EXCLUSIVE=0 turns it off.
    const char* excl = getenv("N64_AUDIO_EXCLUSIVE");
    if (!excl || *excl != '0') AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(b, &AudioOut::cb, this);
    AAudioStreamBuilder_setErrorCallback(b, &AudioOut::onError, this);
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &stream_);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) { LOGE("AAudio open failed: %d", res); stream_ = nullptr; return false; }

    int dstRate = AAudioStream_getSampleRate(stream_);
    dstRate_ = dstRate;
    baseStep_ = step_ = (double)srcRate / dstRate;
    drcI_ = 0; frames_ = 0;
    // ~20 ms kept queued (stereo samples) to start; the rest of the ring is slack for slow frames.
    // Settings › Audio's Latency: Low starts at 10 ms and stays, Safe starts at 60.
    const int mode = latencyMode_.load();
    // With run-ahead Auto starts at 40 ms (Joey 2026-11-21): its frames swing 11-22 ms and a 20 ms start crackled until
    // the target had grown (Odin 2 Portal, Smash 2x CRT light, run-ahead 1, capped: 0-6 underruns per 5 s after the
    // first window at 20 ms, none at 40 or 60). Netplay turns run-ahead off, so 20 ms there.
    // N64_AUDIO_START_MS=<ms> (dev): Auto's starting target.
    const char* sm = getenv("N64_AUDIO_START_MS");
    const char* ra = getenv("N64_RUNAHEAD");
    const bool netplay = (getenv("N64_NETPLAY") && *getenv("N64_NETPLAY")) || (getenv("N64_NETPLAY_SERVE") && *getenv("N64_NETPLAY_SERVE"));
    const int autoStart = sm && atoi(sm) > 0 ? std::min(atoi(sm), (int)kMaxTargetMs)
                        : ra && atoi(ra) > 0 && !netplay ? std::min(40, (int)kMaxTargetMs) : kTargetMs;
    const int startMs = mode == 1 ? 10 : mode == 2 ? 60 : autoStart;
    grow_ = mode != 1;
    target_ = (size_t)dstRate * startMs / 1000 * 2;
    startTarget_ = target_; quietWindows_ = 0;
    keep_.store(target_, std::memory_order_relaxed);
    {
        const char* fd = getenv("N64_AUDIO_FADE"); fadeOn_ = fd && *fd == '1';
        const char* sh = getenv("N64_AUDIO_SHRINK"); shrinkOn_ = sh && *sh == '1';
        // N64_AUDIO_GROW_ONCE=<ms> (test only): the target grows by this once, as if underruns had grown it, so the shrink
        // can be measured where a game doesn't underrun by itself.
        const char* go = getenv("N64_AUDIO_GROW_ONCE"); growOnceMs_ = go ? atoi(go) : 0; growOnceDone_ = false;
        const char* fp = getenv("N64_FF_PITCH"); ffPitchOn_ = fp && *fp == '1';
        if (fadeOn_ || shrinkOn_ || ffPitchOn_)
            LOGI("audio: dev switches:%s%s%s", fadeOn_ ? " underrun fade (N64_AUDIO_FADE)" : "",
                 shrinkOn_ ? " target shrinks back (N64_AUDIO_SHRINK)" : "", ffPitchOn_ ? " fast-forward keeps pitch (N64_FF_PITCH)" : "");
    }
    last_[0] = last_[1] = 0; fadeIn_ = 0;
    if (const char* cp = getenv("N64_AUDIO_CAPTURE"); cp && *cp && cap_buf_.empty()) {
        capPath_ = cp;
        cap_buf_.assign((size_t)dstRate * 2 * 30, 0.0f);
        capN_ = 0; capWritten_ = false;
        LOGI("audio: capturing 30 s of output to %s (N64_AUDIO_CAPTURE)", cp);
    }
    ffPos_ = 0; ffLen_ = 0; ffHaveTail_ = false;
    { const char* rd = getenv("N64_RESUME_DROP"); resumeDropOn_ = !(rd && *rd == '0'); }
    { const char* xf = getenv("N64_AUDIO_XFADE"); xfadeOn_ = !(xf && *xf == '0'); }
    resumeDrop_.store(false, std::memory_order_relaxed);
    pos_ = 0.0; prev_[0] = prev_[1] = 0;
    cap_ = (size_t)dstRate * 2 * 2 / 5; // ~400 ms stereo: room for a game's 100 ms lump on top of the wait's ceiling
    ring_.assign(cap_, 0);
    r_ = w_ = 0;
    lastW_ = 0; avgWrite_ = -1; excess_ = 0; peak_ = 0;
    { const char* sm = getenv("N64_AUDIO_SMOOTH"); smoothMode_ = sm && *sm == '1' ? 1 : sm && *sm == '0' ? 0 : -1; }
    smooth_ = smoothMode_ == 1;
    if (smoothMode_ >= 0) LOGI("audio: smoothed audio wait forced %s (N64_AUDIO_SMOOTH)", smooth_ ? "on" : "off");
    // AAudio defaults the buffer to its whole capacity (8 bursts on the Flip and the Portal: 43 and
    // 32 ms of extra latency). Start at 2 bursts; takeStats adds a burst per xrun.
    AAudioStream_setBufferSizeInFrames(stream_, 2 * AAudioStream_getFramesPerBurst(stream_));
    xruns_ = 0;
    LOGI("audio: core %d Hz -> device %d Hz, perf mode %d, sharing %d, burst %d, buffer %d of %d, latency %s (target %d ms)", srcRate, dstRate,
         AAudioStream_getPerformanceMode(stream_), AAudioStream_getSharingMode(stream_), AAudioStream_getFramesPerBurst(stream_),
         AAudioStream_getBufferSizeInFrames(stream_), AAudioStream_getBufferCapacityInFrames(stream_),
         mode == 1 ? "low" : mode == 2 ? "safe" : "auto", startMs);
    AAudioStream_requestStart(stream_);
    return true;
}

void AudioOut::close() {
    std::lock_guard<std::mutex> lk(streamMutex_);
    retryNs_ = 0;
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

void AudioOut::setPaused(bool p) {
    std::lock_guard<std::mutex> lk(streamMutex_);
    paused_ = p;
    if (!stream_) return;
    if (!p && resumeDropOn_) resumeDrop_.store(true, std::memory_order_release);
    p ? AAudioStream_requestPause(stream_) : AAudioStream_requestStart(stream_);
}

void AudioOut::setPanelRefreshNs(uint64_t ns) {
    if (smoothMode_ != -1) return;
    bool on = ns > 0 && ns < 15000000; // faster than ~66 Hz
    if (on == smooth_) return;
    smooth_ = on;
    lastW_ = w_.load(std::memory_order_relaxed); avgWrite_ = -1; excess_ = 0;
    LOGI("audio: smoothed audio wait %s (panel refresh %.2f ms)", on ? "on" : "off", ns / 1e6);
}

void AudioOut::waitForRoom() {
    reopenIfLost();
    if (!stream_) return;
    if (resumeDrop_.load(std::memory_order_acquire)) {
        // Resumed, and the callback hasn't run yet: no wait (see resumeDrop_). Half a second without a callback
        // (a stream that didn't start): back to the normal wait.
        lastW_ = w_.load(std::memory_order_relaxed);
        if (++resumeFrames_ < 30) return;
        resumeDrop_.store(false, std::memory_order_relaxed);
    }
    resumeFrames_ = 0;
    // Waits only above target + one frame of audio: where vsync paces the frames (fill near the
    // target via rate control), audio then never delays a frame past its vsync.
    const size_t frameAudio = (size_t)dstRate_ / 60 * 2;
    size_t ceiling = target_ + frameAudio;
    // Samples written since the last call (one frame's audio).
    size_t w = w_.load(std::memory_order_relaxed);
    double wrote = (double)((w + cap_ - lastW_) % cap_);
    lastW_ = w;
    // A game that hands over its sound in lumps: Pokemon Puzzle League's intro gives 100 ms (4,410 frames) every
    // sixth frame and nothing between. Waiting down to target + a frame after a lump left too little for the empty
    // frames after it (Odin 3 Portal: 174 + 147 underruns in its first 10 s). The ceiling is raised by the largest
    // frame's audio lately above a normal frame's; it fades by 1% a frame (~3 s back to normal once the lumps stop).
    peak_ = std::max(wrote, peak_ * 0.99);
    const size_t lump = peak_ > 2.0 * frameAudio ? (size_t)(peak_ - frameAudio) : 0;
    // With the smoothed wait (fast panels) the fading excess below covers a lump, its waits spread over the empty
    // frames (this rule alone bunched them: one 38-50 ms wait after each lump), now the ring lets it reach 100 ms.
    // The excess fades 3% a frame, so it ends a little below zero by the next lump: two frames more while lumps come
    // (35 short underruns in Puzzle League's intro without them).
    if (!smooth_) ceiling += lump; else if (lump) ceiling += 2 * frameAudio;
    if (smooth_) {
        // Against their running average; a burst raises the ceiling by what it brought above the average, an empty
        // frame lowers it, so frames start evenly.
        if (frames_ > 0) {
            avgWrite_ = avgWrite_ < 0 ? wrote : avgWrite_ + 0.02 * (wrote - avgWrite_);
            double lim = (double)cap_ / 4;
            excess_ = std::max(-lim, std::min(lim, 0.97 * excess_ + (wrote - avgWrite_)));
        }
        ceiling = (size_t)std::max(0.0, (double)ceiling + excess_);
    }
    bool waited = false;
    // Sleep for as long as the excess takes to play, then check again (the callback drains in bursts):
    // a few wake-ups per frame. Polling every 250 us was ~47 per frame, ~3.5% of the Duo's CPU samples.
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(100); // never deadlock
    for (size_t f; (f = fill()) > ceiling && std::chrono::steady_clock::now() < deadline;) {
        long us = (long)((double)(f - ceiling) * 1e6 / (dstRate_ * 2.0));
        std::this_thread::sleep_for(std::chrono::microseconds(std::max(250L, us)));
        waited = true;
    }
    // Fill right before the frame: target + a frame when audio paces the frames, ~target when vsync does.
    size_t f = fill();
    fillSum_ += f; ++fillCount_;
    // Dynamic rate control: resample up to 1% faster/slower (17 cents) to hold the fill at the
    // target when vsync, not audio, paces the frames: the clocks differ slightly and frames that
    // miss a vsync produce no audio (Retroid Flip menus: ~0.8% of frames). The integral part only
    // learns from moderate errors (not slowdowns). While audio paces (it waited), the correction
    // fades and never pulls the fill down, which would only speed the game up.
    ++frames_;
    double err = std::max(-1.0, std::min(1.0, ((double)f - (double)target_) / (double)target_));
    if (err > -0.1 && err < 0.1) err = 0; // within ~4 ms: the wait loop's own granularity
    if (waited) { drcI_ *= 0.95; err = std::min(err, 0.0); }
    else if (err > -0.75 && err < 0.75) drcI_ = std::max(-0.01, std::min(0.01, drcI_ + 0.0001 * err));
    step_ = baseStep_ * (1.0 + std::max(-0.01, std::min(0.01, 0.005 * err + drcI_)));
}

double AudioOut::outputLatencyMs() {
    if (!stream_) return -1;
    int64_t pos = 0, ns = 0;
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &pos, &ns) != AAUDIO_OK) return -1;
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now = ts.tv_sec * 1000000000LL + ts.tv_nsec;
    // The frame written now plays after the ones queued ahead of it.
    int64_t playedNow = pos + (now - ns) * dstRate_ / 1000000000LL;
    return (AAudioStream_getFramesWritten(stream_) - playedNow) * 1000.0 / dstRate_;
}

void AudioOut::takeStats(bool keepingUp, double& fillMs, double& targetMs, int& underruns) {
    if (stream_) {
        int x = AAudioStream_getXRunCount(stream_);
        if (x > xruns_) {
            int32_t size = AAudioStream_getBufferSizeInFrames(stream_) + AAudioStream_getFramesPerBurst(stream_);
            if (size <= AAudioStream_getBufferCapacityInFrames(stream_)) {
                AAudioStream_setBufferSizeInFrames(stream_, size);
                LOGI("audio: %d xruns, buffer now %d frames", x - xruns_, size);
            }
            xruns_ = x;
        }
    }
    auto ms = [this](double samples) { return samples / 2 * 1000.0 / dstRate_; };
    if (!cap_buf_.empty() && !capWritten_ && capN_.load(std::memory_order_acquire) == cap_buf_.size()) {
        if (FILE* fp = fopen(capPath_.c_str(), "wb")) {
            fwrite(cap_buf_.data(), sizeof(float), cap_buf_.size(), fp);
            fclose(fp);
            LOGI("audio: capture written, %d Hz stereo float, %s", dstRate_, capPath_.c_str());
        } else LOGE("audio: can't write the capture to %s", capPath_.c_str());
        capWritten_ = true;
    }
    fillMs = fillCount_ ? ms(fillSum_ / fillCount_) : 0;
    fillSum_ = 0; fillCount_ = 0;
    underruns = underruns_.exchange(0);
    size_t step = (size_t)dstRate_ / 100 * 2; // 10 ms
    if (grow_ && underruns && keepingUp && frames_ > 600 && ms((double)(target_ + step)) <= kMaxTargetMs)
        target_ += step;
    if (growOnceMs_ > 0 && !growOnceDone_ && keepingUp && frames_ > 600) {
        size_t add = (size_t)dstRate_ * growOnceMs_ / 1000 * 2;
        if (ms((double)(target_ + add)) <= kMaxTargetMs) target_ += add;
        growOnceDone_ = true;
        LOGI("audio: target grown to %.0f ms for the test (N64_AUDIO_GROW_ONCE)", ms((double)target_));
    }
    if (shrinkOn_) {
        // N64_AUDIO_SHRINK: two quiet windows (10 s), then 5 ms back a window, never below where it started.
        quietWindows_ = underruns ? 0 : quietWindows_ + 1;
        if (quietWindows_ >= 2 && target_ > startTarget_) {
            size_t down = std::min(step / 4 * 2, target_ - startTarget_); // 5 ms, whole stereo frames
            target_ -= down;
            LOGI("audio: target back down to %.0f ms after %d s without an underrun", ms((double)target_), quietWindows_ * 5);
        }
    }
    keep_.store(target_, std::memory_order_relaxed);
    targetMs = ms((double)target_);
}

// Returns false if the ring stayed full (paused / stalled consumer).

bool AudioOut::push(float s) {
    size_t w = w_.load(std::memory_order_relaxed);
    size_t next = (w + 1) % cap_;
    int spins = 0;
    while (next == r_.load(std::memory_order_acquire)) { // full: wait for consumer
        // Once a wait gave up, drop samples without waiting until the consumer moves again: a stream that stalls
        // without reporting an error cost ~0.5 s on every write, so the game crawled.
        if (lost_ || stalled_ || ++spins > 2000) { stalled_ = true; return false; } // ~give up, avoid deadlock on pause
        hitch::Span span(hitch::AUDIO);
        std::this_thread::sleep_for(std::chrono::microseconds(250));
    }
    stalled_ = false;
    ring_[w] = s;
    w_.store(next, std::memory_order_release);
    fed_.store(true, std::memory_order_relaxed);
    return true;
}

void AudioOut::takeRates(double secs, double& inHz, double& outHz) {
    inHz = secs > 0 ? srcFrames_.exchange(0) / secs : 0;
    outHz = secs > 0 ? outFrames_.exchange(0) / secs : 0;
}

void AudioOut::write(const int16_t* frames, size_t frameCount) {
    if (!stream_) return;
    srcFrames_.fetch_add((long long)frameCount, std::memory_order_relaxed);
    // Linear interpolation between prev_ and each new source frame.
    for (size_t i = 0; i < frameCount; ++i) {
        const int16_t* cur = frames + i * 2;
        while (pos_ < 1.0) {
            for (int ch = 0; ch < 2; ++ch)
                if (!push((prev_[ch] + (cur[ch] - prev_[ch]) * (float)pos_) * (1.0f / 32768))) return;
            pos_ += step_;
        }
        pos_ -= 1.0;
        prev_[0] = cur[0]; prev_[1] = cur[1];
    }
}

void AudioOut::writeFast(const int16_t* frames, size_t frameCount, double speed, float gain) {
    if (!stream_ || !cap_) return;
    // Steer around the target: up to half again faster when the ring fills, slower when it empties.
    const size_t f = fill();
    fillSum_ += f; ++fillCount_; // the stats line's fill, as waitForRoom keeps it outside fast-forward
    double err = std::max(-1.0, std::min(1.0, ((double)f - (double)target_) / (double)target_));
    // Below 1: slow motion (approved 2026-11-08), its sound slower and lower.
    const float k = gain * (1.0f / 32768);
    if (ffPitchOn_ && speed > 1.0) {
        // N64_FF_PITCH: keep B source frames (20 ms) of every block of speed x B, the first C (2 ms) crossfaded from the
        // previous block's frames just after its kept piece; the fill steers the block length (more dropped when the
        // ring fills), never the pitch. The kept frames go through the normal-rate resampler.
        const size_t B = (size_t)srcRate_ / 50, C = std::min<size_t>(96, (size_t)srcRate_ / 500);
        for (size_t i = 0; i < frameCount; ++i) {
            const int16_t* cur = frames + i * 2;
            if (ffPos_ == 0) ffLen_ = std::max((double)(B + C), B * speed * (1.0 + 0.5 * err));
            if (ffPos_ < C) {
                if (ffHaveTail_) {
                    float t = (float)(ffPos_ + 1) / (float)(C + 1);
                    int16_t m[2];
                    for (int ch = 0; ch < 2; ++ch) m[ch] = (int16_t)(ffTail_[ffPos_ * 2 + ch] * (1.0f - t) + cur[ch] * t);
                    ffEmit(m, k);
                } else ffEmit(cur, k);
            } else if (ffPos_ < B) {
                ffEmit(cur, k);
            } else if (ffPos_ < B + C) {
                ffNext_[(ffPos_ - B) * 2] = cur[0]; ffNext_[(ffPos_ - B) * 2 + 1] = cur[1];
            }
            if (++ffPos_ >= (size_t)ffLen_) {
                std::memcpy(ffTail_, ffNext_, sizeof ffTail_);
                ffHaveTail_ = true;
                ffPos_ = 0;
            }
        }
        return;
    }
    ffPos_ = 0; ffHaveTail_ = false;
    const double step = baseStep_ * std::max(0.1, speed) * (1.0 + 0.5 * err);
    for (size_t i = 0; i < frameCount; ++i) {
        const int16_t* cur = frames + i * 2;
        while (pos_ < 1.0) {
            size_t w = w_.load(std::memory_order_relaxed);
            size_t next = (w + 2) % cap_;
            // Full (fewer than 2 free samples): drop the rest of this batch.
            if ((w + 1) % cap_ == r_.load(std::memory_order_acquire) || next == r_.load(std::memory_order_acquire)) {
                prev_[0] = frames[(frameCount - 1) * 2]; prev_[1] = frames[(frameCount - 1) * 2 + 1];
                pos_ = 0; return;
            }
            ring_[w] = (prev_[0] + (cur[0] - prev_[0]) * (float)pos_) * k;
            ring_[(w + 1) % cap_] = (prev_[1] + (cur[1] - prev_[1]) * (float)pos_) * k;
            w_.store(next, std::memory_order_release);
            fed_.store(true, std::memory_order_relaxed);
            pos_ += step;
        }
        pos_ -= 1.0;
        prev_[0] = cur[0]; prev_[1] = cur[1];
    }
}

void AudioOut::ffEmit(const int16_t* cur, float k) {
    // One source frame at the normal rate (step_ = baseStep_ with rate control), dropped when the ring is full.
    while (pos_ < 1.0) {
        size_t w = w_.load(std::memory_order_relaxed);
        size_t next = (w + 2) % cap_;
        if ((w + 1) % cap_ == r_.load(std::memory_order_acquire) || next == r_.load(std::memory_order_acquire)) {
            pos_ += baseStep_;
            continue;
        }
        ring_[w] = (prev_[0] + (cur[0] - prev_[0]) * (float)pos_) * k;
        ring_[(w + 1) % cap_] = (prev_[1] + (cur[1] - prev_[1]) * (float)pos_) * k;
        w_.store(next, std::memory_order_release);
        fed_.store(true, std::memory_order_relaxed);
        pos_ += baseStep_;
    }
    pos_ -= 1.0;
    prev_[0] = cur[0]; prev_[1] = cur[1];
}

void AudioOut::setMix(int volumePct, bool mute, bool mono) {
    float v = std::max(0, std::min(100, volumePct)) / 100.0f;
    gain_ = mute ? 0.0f : v * v;
    mono_ = mono;
    LOGI("audio: volume %d%%%s%s", volumePct, mute ? ", muted" : "", mono ? ", mono" : "");
}

aaudio_data_callback_result_t AudioOut::cb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioOut*>(user);
    auto* out = static_cast<float*>(data);
    size_t samples = (size_t)frames * 2;
    self->outFrames_.fetch_add(frames, std::memory_order_relaxed);
    if (self->resumeDrop_.load(std::memory_order_acquire)) {
        // First run after a resume: drop the oldest samples down to the target (this thread owns r_), in whole
        // stereo frames.
        size_t r = self->r_.load(std::memory_order_relaxed), w = self->w_.load(std::memory_order_acquire);
        size_t f = (w + self->cap_ - r) % self->cap_, keep = self->keep_.load(std::memory_order_relaxed);
        if (f > keep) {
            if (self->xfadeOn_) {
                // What would have played next, from the old read position: faded out under the kept sound.
                int n = (int)std::min<size_t>(kXfadeFrames, f / 2);
                for (int j = 0; j < n * 2; ++j) self->xfade_[j] = self->ring_[(r + j) % self->cap_];
                self->xfadeN_ = n; self->xfadeAt_ = 0;
            }
            self->r_.store((r + ((f - keep) & ~(size_t)1)) % self->cap_, std::memory_order_release);
        }
        self->resumeDrop_.store(false, std::memory_order_release);
    }
    for (size_t i = 0; i < samples; ++i) {
        size_t r = self->r_.load(std::memory_order_relaxed);
        if (r == self->w_.load(std::memory_order_acquire)) { // underrun → silence
            if (self->fed_.load(std::memory_order_relaxed)) {
                self->underruns_.fetch_add(1, std::memory_order_relaxed);
                self->underrunsTotal_.fetch_add(1, std::memory_order_relaxed);
            }
            memset(out + i, 0, (samples - i) * sizeof(float));
            if (self->fadeOn_) {
                // N64_AUDIO_FADE: from the last frame played down to zero over kFadeFrames, then the next sound fades in.
                size_t start = i & ~(size_t)1;
                if (start < i) out[start] = self->last_[0]; // a frame cut between its two samples: finish it
                size_t n = std::min((size_t)kFadeFrames, (samples - start) / 2);
                for (size_t j = 0; j < n; ++j) {
                    float g = 1.0f - (float)(j + 1) / (float)(kFadeFrames + 1);
                    out[start + j * 2] = self->last_[0] * g;
                    out[start + j * 2 + 1] = self->last_[1] * g;
                }
                self->fadeIn_ = kFadeFrames;
                self->last_[0] = self->last_[1] = 0.0f;
            }
            break;
        }
        float s = self->ring_[r];
        if (self->xfadeAt_ < self->xfadeN_) {
            float t = (float)(self->xfadeAt_ + 1) / (float)(self->xfadeN_ + 1);
            s = self->xfade_[self->xfadeAt_ * 2 + (i & 1)] * (1.0f - t) + s * t;
            if (i & 1) ++self->xfadeAt_;
        }
        if (self->fadeIn_ > 0) {
            s *= 1.0f - (float)self->fadeIn_ / (float)(kFadeFrames + 1);
            if (i & 1) --self->fadeIn_;
        }
        out[i] = s;
        if (self->fadeOn_) self->last_[i & 1] = s;
        self->r_.store((r + 1) % self->cap_, std::memory_order_release);
    }
    const float g = self->gain_.load(std::memory_order_relaxed);
    if (self->mono_.load(std::memory_order_relaxed)) {
        for (size_t i = 0; i + 1 < samples; i += 2) { float m = (out[i] + out[i + 1]) * 0.5f * g; out[i] = out[i + 1] = m; }
    } else if (g != 1.0f) {
        for (size_t i = 0; i < samples; ++i) out[i] *= g;
    }
    if (!self->cap_buf_.empty() && self->fed_.load(std::memory_order_relaxed)) {
        size_t n = self->capN_.load(std::memory_order_relaxed), room = self->cap_buf_.size() - n;
        if (room) {
            size_t c = std::min(room, samples);
            memcpy(self->cap_buf_.data() + n, out, c * sizeof(float));
            self->capN_.store(n + c, std::memory_order_release);
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
