/**
 * native-lib.cpp — Beirus Sampler audio engine
 *
 * Features
 * ─────────
 * • Google Oboe low-latency playback (exclusive, float32, mono)
 * • Per-pad slice playback with start/end boundaries
 * • Smart-Chop: energy-flux onset detection → 16 pads
 * • 1-pole lo-fi filter  (cutoff 0..1)
 * • Variable playback speed / pitch  (phaseInc 0.25..4.0)
 * • Optional audio recording of the output stream
 */

#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

// ─────────────────────────────────────────────────────────────────────────────
// SamplerEngine
// ─────────────────────────────────────────────────────────────────────────────
class SamplerEngine : public oboe::AudioStreamDataCallback {
public:
    // ── Shared audio buffer (written once from JNI, read from callback) ──
    std::mutex            bufMtx;
    std::vector<float>    sampleData;
    std::vector<int>      chopPoints;   // start frames, sorted ascending

    // ── Playback state (lock-free atomics) ───────────────────────────────
    std::atomic<float>    playHead {0.0f};
    std::atomic<float>    playEnd  {0.0f};   // 0 = play to buffer end
    std::atomic<float>    phaseInc {1.0f};
    std::atomic<float>    cutoff   {1.0f};
    std::atomic<bool>     isPlaying{false};

    // ── Recording ────────────────────────────────────────────────────────
    std::vector<float>    recBuf;
    std::atomic<bool>     isRecording{false};

    // ── Oboe stream ──────────────────────────────────────────────────────
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex            streamMtx;

private:
    float prevOut = 0.0f;   // filter memory (callback thread only)

public:
    // ─────────────────────────────────────────────────────────────────────
    // Audio callback — called by Oboe on a high-priority thread
    // ─────────────────────────────────────────────────────────────────────
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* /*stream*/,
            void*              audioData,
            int32_t            numFrames) override
    {
        auto* out = static_cast<float*>(audioData);

        // Snapshot atomics once per callback (avoid repeated atomic loads)
        const float head    = playHead.load(std::memory_order_relaxed);
        const float end     = playEnd .load(std::memory_order_relaxed);
        const float inc     = phaseInc.load(std::memory_order_relaxed);
        const float c       = std::clamp(cutoff.load(std::memory_order_relaxed), 0.0f, 1.0f);
        const bool  playing = isPlaying.load(std::memory_order_relaxed);
        const bool  rec     = isRecording.load(std::memory_order_relaxed);

        // We need to read sampleData; use try_lock to avoid blocking the
        // audio thread — if we can't get the lock just output silence.
        const float* src     = nullptr;
        int          srcSize = 0;
        int          limit   = 0;
        bool         haveLock = false;

        if (bufMtx.try_lock()) {
            haveLock = true;
            if (!sampleData.empty()) {
                src     = sampleData.data();
                srcSize = static_cast<int>(sampleData.size());
                limit   = (end > 0.0f && static_cast<int>(end) <= srcSize)
                          ? static_cast<int>(end) : srcSize;
            }
        }

        float localHead = head;

        for (int i = 0; i < numFrames; ++i) {
            float raw = 0.0f;

            if (playing && src && localHead < static_cast<float>(limit)) {
                int idx = static_cast<int>(localHead);
                if (idx >= 0 && idx < srcSize) raw = src[idx];
                localHead += inc;
                if (static_cast<int>(localHead) >= limit) {
                    isPlaying.store(false, std::memory_order_relaxed);
                }
            }

            // 1-pole IIR low-pass:  y[n] = c*x[n] + (1-c)*y[n-1]
            prevOut = c * raw + (1.0f - c) * prevOut;
            out[i]  = prevOut;

            if (rec) recBuf.push_back(prevOut);
        }

        playHead.store(localHead, std::memory_order_relaxed);

        if (haveLock) bufMtx.unlock();

        return oboe::DataCallbackResult::Continue;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stream management
    // ─────────────────────────────────────────────────────────────────────
    bool start() {
        std::lock_guard<std::mutex> lock(streamMtx);
        if (stream) return true;

        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
         .setSharingMode(oboe::SharingMode::Exclusive)
         .setPerformanceMode(oboe::PerformanceMode::LowLatency)
         .setChannelCount(1)
         .setFormat(oboe::AudioFormat::Float)
         .setDataCallback(this);

        oboe::Result r = b.openStream(stream);
        if (r != oboe::Result::OK || !stream) return false;
        return stream->requestStart() == oboe::Result::OK;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(streamMtx);
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trigger a pad
    // ─────────────────────────────────────────────────────────────────────
    void trigger(int startFrame, int endFrame) {
        std::lock_guard<std::mutex> lock(bufMtx);
        if (sampleData.empty()) return;
        const int total = static_cast<int>(sampleData.size());
        startFrame = std::clamp(startFrame, 0, total - 1);
        if (endFrame <= 0 || endFrame > total) endFrame = 0;
        playEnd .store(static_cast<float>(endFrame),  std::memory_order_relaxed);
        playHead.store(static_cast<float>(startFrame),std::memory_order_relaxed);
        isPlaying.store(true, std::memory_order_relaxed);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Smart-Chop — energy-flux onset detection
    //
    // 1. Compute short-time energy in overlapping windows.
    // 2. Half-wave-rectified flux = max(0, E[n] - E[n-1]).
    // 3. Threshold at mean + kSigma * stddev.
    // 4. Enforce minGap between onsets.
    // 5. Pad to exactly targetSlices by bisecting the largest gap.
    // ─────────────────────────────────────────────────────────────────────
    void smartChop(int targetSlices) {
        std::lock_guard<std::mutex> lock(bufMtx);
        chopPoints.clear();
        if (sampleData.empty() || targetSlices <= 0) return;

        const int   N        = static_cast<int>(sampleData.size());
        const int   winSz    = 1024;
        const int   hopSz    = 512;
        const float kSigma   = 1.4f;
        const int   minGap   = static_cast<int>(44100 * 0.08f);  // 80 ms

        // Short-time energy
        std::vector<float> energy;
        energy.reserve(N / hopSz + 1);
        for (int pos = 0; pos + winSz <= N; pos += hopSz) {
            float e = 0.0f;
            for (int j = pos; j < pos + winSz; ++j)
                e += sampleData[j] * sampleData[j];
            energy.push_back(e / static_cast<float>(winSz));
        }

        if (energy.size() < 2) {
            // Buffer too short — equal spacing fallback
            int step = N / targetSlices;
            for (int i = 0; i < targetSlices; ++i)
                chopPoints.push_back(i * step);
            return;
        }

        // Flux
        std::vector<float> flux(energy.size(), 0.0f);
        for (size_t i = 1; i < energy.size(); ++i) {
            float d = energy[i] - energy[i - 1];
            flux[i] = d > 0.0f ? d : 0.0f;
        }

        // Stats
        float mean = 0.0f;
        for (float v : flux) mean += v;
        mean /= static_cast<float>(flux.size());

        float var = 0.0f;
        for (float v : flux) var += (v - mean) * (v - mean);
        float sigma = std::sqrt(var / static_cast<float>(flux.size()));

        float thresh = mean + kSigma * sigma;

        // Pick onsets
        chopPoints.push_back(0);
        int lastOnset = 0;
        for (size_t i = 1; i < flux.size() && static_cast<int>(chopPoints.size()) < targetSlices; ++i) {
            if (flux[i] > thresh) {
                int sp = static_cast<int>(i) * hopSz;
                if (sp - lastOnset >= minGap && sp < N) {
                    chopPoints.push_back(sp);
                    lastOnset = sp;
                }
            }
        }

        // Pad by bisecting the largest gap
        while (static_cast<int>(chopPoints.size()) < targetSlices) {
            std::sort(chopPoints.begin(), chopPoints.end());
            int bestStart = 0, bestSize = 0;
            for (int k = 0; k < static_cast<int>(chopPoints.size()); ++k) {
                int gEnd = (k + 1 < static_cast<int>(chopPoints.size()))
                           ? chopPoints[k + 1] : N;
                int gSz  = gEnd - chopPoints[k];
                if (gSz > bestSize) { bestSize = gSz; bestStart = chopPoints[k]; }
            }
            chopPoints.push_back(bestStart + bestSize / 2);
        }

        std::sort(chopPoints.begin(), chopPoints.end());
        if (static_cast<int>(chopPoints.size()) > targetSlices)
            chopPoints.resize(static_cast<size_t>(targetSlices));
    }
};

// ── Singleton ────────────────────────────────────────────────────────────────
static SamplerEngine gEngine;

// ─────────────────────────────────────────────────────────────────────────────
// JNI bridge
// ─────────────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_beirus_MainActivity_nativeStartEngine(JNIEnv*, jobject) {
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeStopEngine(JNIEnv*, jobject) {
    gEngine.stop();
}

// Called from Kotlin after WAV decode — hands float PCM to the engine.
JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeLoadSample(
        JNIEnv* env, jobject, jfloatArray data)
{
    jsize   len  = env->GetArrayLength(data);
    jfloat* body = env->GetFloatArrayElements(data, nullptr);
    {
        std::lock_guard<std::mutex> lock(gEngine.bufMtx);
        gEngine.sampleData.assign(body, body + len);
        gEngine.isPlaying.store(false, std::memory_order_relaxed);
        gEngine.playHead .store(0.0f,  std::memory_order_relaxed);
        gEngine.playEnd  .store(0.0f,  std::memory_order_relaxed);
    }
    env->ReleaseFloatArrayElements(data, body, 0);
}

// Trigger pad by absolute start frame; engine derives end from chopPoints.
JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeTrigger(
        JNIEnv*, jobject, jint startFrame)
{
    int endFrame = 0;
    {
        std::lock_guard<std::mutex> lock(gEngine.bufMtx);
        const auto& cp = gEngine.chopPoints;
        for (int i = 0; i < static_cast<int>(cp.size()); ++i) {
            if (cp[i] == startFrame && i + 1 < static_cast<int>(cp.size())) {
                endFrame = cp[i + 1];
                break;
            }
        }
    }
    gEngine.trigger(startFrame, endFrame);
}

JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeAutoChop(JNIEnv*, jobject, jint num) {
    gEngine.smartChop(num);
}

JNIEXPORT jintArray JNICALL
Java_com_example_beirus_MainActivity_nativeGetChops(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gEngine.bufMtx);
    jintArray res = env->NewIntArray(static_cast<jsize>(gEngine.chopPoints.size()));
    if (!gEngine.chopPoints.empty()) {
        env->SetIntArrayRegion(res, 0,
            static_cast<jsize>(gEngine.chopPoints.size()),
            gEngine.chopPoints.data());
    }
    return res;
}

JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeSetPitch(JNIEnv*, jobject, jfloat inc) {
    gEngine.phaseInc.store(std::clamp(inc, 0.25f, 4.0f), std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeSetCutoff(JNIEnv*, jobject, jfloat c) {
    gEngine.cutoff.store(std::clamp(c, 0.0f, 1.0f), std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_example_beirus_MainActivity_nativeToggleRecord(JNIEnv*, jobject, jboolean rec) {
    if (rec) gEngine.recBuf.clear();
    gEngine.isRecording.store(rec == JNI_TRUE, std::memory_order_relaxed);
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_beirus_MainActivity_nativeGetRecordedData(JNIEnv* env, jobject) {
    jfloatArray result = env->NewFloatArray(
        static_cast<jsize>(gEngine.recBuf.size()));
    if (!gEngine.recBuf.empty()) {
        env->SetFloatArrayRegion(result, 0,
            static_cast<jsize>(gEngine.recBuf.size()),
            gEngine.recBuf.data());
    }
    return result;
}

} // extern "C"
