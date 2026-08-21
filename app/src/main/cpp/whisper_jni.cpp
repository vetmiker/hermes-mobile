#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Session {
    whisper_context * whisper = nullptr;
    whisper_vad_context * vad = nullptr;
    std::atomic<bool> cancelled = false;

    ~Session() {
        if (vad != nullptr) whisper_vad_free(vad);
        if (whisper != nullptr) whisper_free(whisper);
    }
};

std::mutex sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_handle = 1;

void throw_illegal_argument(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    env->ThrowNew(type, message);
}

void throw_illegal_state(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

std::shared_ptr<Session> lookup(JNIEnv * env, jlong handle) {
    std::lock_guard lock(sessions_mutex);
    const auto found = sessions.find(handle);
    if (found == sessions.end()) {
        throw_illegal_state(env, "Whisper session is closed");
        return nullptr;
    }
    return found->second;
}

std::vector<float> copy_samples(JNIEnv * env, jfloatArray samples) {
    if (samples == nullptr) {
        throw_illegal_argument(env, "PCM samples are required");
        return {};
    }
    const auto length = env->GetArrayLength(samples);
    if (length <= 0) {
        throw_illegal_argument(env, "PCM samples must not be empty");
        return {};
    }
    std::vector<float> result(static_cast<size_t>(length));
    env->GetFloatArrayRegion(samples, 0, length, result.data());
    return result;
}

jstring make_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeVersion(JNIEnv * env, jobject) {
    return make_string(env, whisper_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeOpen(
        JNIEnv * env, jobject, jstring whisper_path, jstring vad_path, jint threads) {
    if (whisper_path == nullptr || vad_path == nullptr || threads < 1 || threads > 8) {
        throw_illegal_argument(env, "Valid model paths and 1-8 threads are required");
        return 0;
    }
    const char * asr_chars = env->GetStringUTFChars(whisper_path, nullptr);
    const char * vad_chars = env->GetStringUTFChars(vad_path, nullptr);
    const std::string asr(asr_chars);
    const std::string vad(vad_chars);
    env->ReleaseStringUTFChars(whisper_path, asr_chars);
    env->ReleaseStringUTFChars(vad_path, vad_chars);

    auto session = std::make_shared<Session>();
    auto whisper_params = whisper_context_default_params();
    whisper_params.use_gpu = false;
    session->whisper = whisper_init_from_file_with_params(asr.c_str(), whisper_params);
    auto vad_params = whisper_vad_default_context_params();
    vad_params.n_threads = threads;
    vad_params.use_gpu = false;
    session->vad = whisper_vad_init_from_file_with_params(vad.c_str(), vad_params);
    if (session->whisper == nullptr || session->vad == nullptr) {
        throw_illegal_state(env, "Could not load verified Whisper model contexts");
        return 0;
    }
    const jlong handle = next_handle.fetch_add(1);
    std::lock_guard lock(sessions_mutex);
    sessions.emplace(handle, std::move(session));
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    std::lock_guard lock(sessions_mutex);
    sessions.erase(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeCancel(JNIEnv * env, jobject, jlong handle) {
    if (const auto session = lookup(env, handle)) session->cancelled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeResetVad(JNIEnv * env, jobject, jlong handle) {
    if (const auto session = lookup(env, handle)) {
        whisper_vad_reset_state(session->vad);
        session->cancelled.store(false, std::memory_order_release);
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeVadProbability(
        JNIEnv * env, jobject, jlong handle, jfloatArray samples) {
    const auto session = lookup(env, handle);
    if (!session) return nullptr;
    const auto input = copy_samples(env, samples);
    if (env->ExceptionCheck()) return nullptr;
    const bool processed = !session->cancelled.load(std::memory_order_acquire) &&
        whisper_vad_detect_speech_no_reset(session->vad, input.data(), static_cast<int>(input.size()));
    float probability = 0.0f;
    if (processed) {
        const int count = whisper_vad_n_probs(session->vad);
        float * probabilities = whisper_vad_probs(session->vad);
        if (count > 0 && probabilities != nullptr) probability = probabilities[count - 1];
    }
    jfloat values[2] = { processed ? 1.0f : 0.0f, probability };
    jfloatArray result = env->NewFloatArray(2);
    env->SetFloatArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_m57_hermescontrol_glasses_speech_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray samples, jint threads) {
    const auto session = lookup(env, handle);
    if (!session) return nullptr;
    const auto input = copy_samples(env, samples);
    if (env->ExceptionCheck()) return nullptr;
    if (session->cancelled.load(std::memory_order_acquire)) return make_string(env, "");

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = std::clamp(static_cast<int>(threads), 1, 8);
    params.abort_callback = [](void * user_data) {
        return static_cast<Session *>(user_data)->cancelled.load(std::memory_order_acquire);
    };
    params.abort_callback_user_data = session.get();
    if (whisper_full(session->whisper, params, input.data(), static_cast<int>(input.size())) != 0 ||
        session->cancelled.load(std::memory_order_acquire)) {
        return make_string(env, "");
    }
    std::string text;
    const int segments = whisper_full_n_segments(session->whisper);
    for (int index = 0; index < segments; ++index) text += whisper_full_get_segment_text(session->whisper, index);
    return make_string(env, text);
}
