#include <jni.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include "whisper.h"

namespace {
using Clock = std::chrono::steady_clock;
std::mutex requests_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> requests;
std::unordered_set<jlong> cancelled_before_registration;

void fail(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type) env->ThrowNew(type, message);
}
std::string from_java(JNIEnv * env, jstring input) {
    const char * chars = env->GetStringUTFChars(input, nullptr);
    if (!chars) return {};
    std::string result(chars); env->ReleaseStringUTFChars(input, chars); return result;
}
std::string escape(const std::string & value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break; case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break; case '\r': out << "\\r"; break; case '\t': out << "\\t"; break;
            default: if (c < 0x20) out << ' '; else out << static_cast<char>(c);
        }
    }
    return out.str();
}
jbyteArray bytes(JNIEnv * env, const std::string & value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
struct RequestGuard {
    jlong id;
    ~RequestGuard() { std::lock_guard<std::mutex> lock(requests_mutex); requests.erase(id); }
};
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_mtmux_voice_NativeVoice_transcribe(JNIEnv * env, jclass, jstring model_java,
        jfloatArray pcm_java, jstring language_java, jint threads, jlong request_id) {
    if (!model_java || !pcm_java || !language_java || threads < 1 || threads > 16 || request_id <= 0) {
        fail(env, "VOICE_INVALID_ARGUMENT"); return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm_java);
    if (sample_count < 1600 || sample_count > 16000 * 60) { fail(env, "VOICE_AUDIO_LENGTH"); return nullptr; }
    const std::string model = from_java(env, model_java);
    const std::string language = from_java(env, language_java);
    if (model.empty() || language.empty()) { fail(env, "VOICE_INVALID_ARGUMENT"); return nullptr; }

    auto cancelled = std::make_shared<std::atomic_bool>(false);
    {
        std::lock_guard<std::mutex> lock(requests_mutex);
        if (requests.count(request_id)) { fail(env, "VOICE_DUPLICATE_REQUEST"); return nullptr; }
        if (cancelled_before_registration.erase(request_id) > 0) cancelled->store(true);
        requests.emplace(request_id, cancelled);
    }
    RequestGuard guard{request_id};
    const auto started = Clock::now();
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false; context_params.flash_attn = true;
    whisper_context * context = whisper_init_from_file_with_params(model.c_str(), context_params);
    if (!context) { fail(env, "VOICE_MODEL_INIT"); return nullptr; }
    if (cancelled->load()) { whisper_free(context); fail(env, "VOICE_CANCELLED"); return nullptr; }

    jfloat * pcm = env->GetFloatArrayElements(pcm_java, nullptr);
    if (!pcm) { whisper_free(context); return nullptr; }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads; params.language = language.c_str(); params.translate = false;
    params.no_context = true; params.no_timestamps = true; params.print_progress = false;
    params.print_realtime = false; params.print_timestamps = false; params.suppress_blank = true;
    params.temperature = 0.0f; params.greedy.best_of = 1;
    params.encoder_begin_callback = [](whisper_context *, whisper_state *, void * data) {
        return !static_cast<std::atomic_bool *>(data)->load();
    };
    params.encoder_begin_callback_user_data = cancelled.get();
    params.abort_callback = [](void * data) { return static_cast<std::atomic_bool *>(data)->load(); };
    params.abort_callback_user_data = cancelled.get();
    const int status = whisper_full(context, params, pcm, sample_count);
    env->ReleaseFloatArrayElements(pcm_java, pcm, JNI_ABORT);
    if (status != 0 || cancelled->load()) {
        whisper_free(context); fail(env, cancelled->load() ? "VOICE_CANCELLED" : "VOICE_TRANSCRIBE"); return nullptr;
    }
    std::string text;
    float max_no_speech = 0.0f;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        if (const char * part = whisper_full_get_segment_text(context, i)) text += part;
        max_no_speech = std::max(max_no_speech, whisper_full_get_segment_no_speech_prob(context, i));
    }
    const int lang_id = whisper_full_lang_id(context);
    const char * detected = lang_id >= 0 ? whisper_lang_str(lang_id) : "unknown";
    if (cancelled->load()) { whisper_free(context); fail(env, "VOICE_CANCELLED"); return nullptr; }
    whisper_free(context);
    const long elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
    std::ostringstream json;
    json << "{\"text\":\"" << escape(text) << "\",\"language\":\"" << escape(detected ? detected : "unknown")
         << "\",\"elapsedMs\":" << elapsed << ",\"segments\":" << segments
         << ",\"noSpeechProbability\":" << max_no_speech << "}";
    return bytes(env, json.str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mtmux_voice_NativeVoice_cancel(JNIEnv *, jclass, jlong request_id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    auto found = requests.find(request_id);
    if (found != requests.end()) { found->second->store(true); return JNI_TRUE; }
    else {
        // Close the Kotlin->native registration race. Keep this bounded for requests whose coroutine
        // is cancelled before it ever reaches native code.
        if (cancelled_before_registration.size() >= 64) cancelled_before_registration.erase(cancelled_before_registration.begin());
        cancelled_before_registration.insert(request_id);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mtmux_voice_NativeVoice_forget(JNIEnv *, jclass, jlong request_id) {
    std::lock_guard<std::mutex> lock(requests_mutex);
    cancelled_before_registration.erase(request_id);
}
