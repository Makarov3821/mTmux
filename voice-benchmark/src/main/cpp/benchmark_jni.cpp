#include <jni.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <fstream>
#include <sstream>
#include <string>

#include "whisper.h"

namespace {
using Clock = std::chrono::steady_clock;

long elapsed_ms(Clock::time_point start, Clock::time_point end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

long peak_rss_kb() {
    struct rusage usage {};
    return getrusage(RUSAGE_SELF, &usage) == 0 ? usage.ru_maxrss : -1;
}

long current_rss_kb() {
    std::ifstream statm("/proc/self/statm");
    long pages_total = 0, pages_resident = 0;
    if (!(statm >> pages_total >> pages_resident)) return -1;
    return pages_resident * sysconf(_SC_PAGESIZE) / 1024;
}

long file_size(const std::string & path) {
    struct stat info {};
    return stat(path.c_str(), &info) == 0 ? info.st_size : -1;
}

std::string json_escape(const std::string & value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    static const char * hex = "0123456789abcdef";
                    out << "\\u00" << hex[c >> 4] << hex[c & 15];
                } else out << static_cast<char>(c);
        }
    }
    return out.str();
}

void throw_runtime(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type) env->ThrowNew(type, message);
}

std::string from_java(JNIEnv * env, jstring input) {
    const char * chars = env->GetStringUTFChars(input, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(input, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_mtmux_voicebenchmark_NativeWhisper_run(
        JNIEnv * env, jclass, jstring model_path_java, jfloatArray pcm_java,
        jstring language_java, jint threads) {
    if (!model_path_java || !pcm_java || !language_java || threads < 1 || threads > 16) {
        throw_runtime(env, "invalid benchmark arguments");
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(pcm_java);
    if (sample_count < 1600 || sample_count > 16000 * 60) {
        throw_runtime(env, "audio must be 0.1 to 60 seconds at 16 kHz");
        return nullptr;
    }

    const std::string model_path = from_java(env, model_path_java);
    const std::string language = from_java(env, language_java);
    if (model_path.empty() || language.empty()) {
        throw_runtime(env, "model path and language are required");
        return nullptr;
    }

    const auto total_start = Clock::now();
    const long rss_before = current_rss_kb();
    const long peak_before = peak_rss_kb();

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = true;
    whisper_context * context = whisper_init_from_file_with_params(model_path.c_str(), context_params);
    const auto load_end = Clock::now();
    if (!context) {
        throw_runtime(env, "whisper model initialization failed");
        return nullptr;
    }
    const long rss_after_load = current_rss_kb();

    jfloat * pcm = env->GetFloatArrayElements(pcm_java, nullptr);
    if (!pcm) {
        whisper_free(context);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = language.c_str(); // "auto" performs automatic language selection while transcribing.
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.temperature = 0.0f;
    params.greedy.best_of = 1;

    const auto inference_start = Clock::now();
    const int status = whisper_full(context, params, pcm, sample_count);
    const auto inference_end = Clock::now();
    env->ReleaseFloatArrayElements(pcm_java, pcm, JNI_ABORT);
    if (status != 0) {
        whisper_free(context);
        throw_runtime(env, "whisper transcription failed");
        return nullptr;
    }

    std::string transcript;
    const int segments = whisper_full_n_segments(context);
    for (int index = 0; index < segments; ++index) {
        const char * text = whisper_full_get_segment_text(context, index);
        if (text) transcript += text;
    }
    const int language_id = whisper_full_lang_id(context);
    const char * detected = language_id >= 0 ? whisper_lang_str(language_id) : "unknown";
    const char * model_type_text = whisper_model_type_readable(context);
    const std::string model_type = model_type_text ? model_type_text : "unknown";
    const long rss_after_run = current_rss_kb();
    const long peak_after = peak_rss_kb();
    whisper_free(context);
    const auto total_end = Clock::now();

    const long load_ms = elapsed_ms(total_start, load_end);
    const long inference_ms = elapsed_ms(inference_start, inference_end);
    const double audio_ms = sample_count * 1000.0 / 16000.0;
    const double rtf = inference_ms / audio_ms;

    std::ostringstream json;
    json << "{"
         << "\"schema\":1,"
         << "\"whisperVersion\":\"" << json_escape(whisper_version()) << "\","
         << "\"modelType\":\"" << json_escape(model_type) << "\","
         << "\"modelBytes\":" << file_size(model_path) << ","
         << "\"languageRequested\":\"" << json_escape(language) << "\","
         << "\"languageDetected\":\"" << json_escape(detected ? detected : "unknown") << "\","
         << "\"threads\":" << threads << ","
         << "\"samples\":" << sample_count << ","
         << "\"audioMs\":" << static_cast<long>(audio_ms) << ","
         << "\"loadMs\":" << load_ms << ","
         << "\"inferenceMs\":" << inference_ms << ","
         << "\"totalMs\":" << elapsed_ms(total_start, total_end) << ","
         << "\"realTimeFactor\":" << rtf << ","
         << "\"rssBeforeKb\":" << rss_before << ","
         << "\"rssAfterLoadKb\":" << rss_after_load << ","
         << "\"rssAfterRunKb\":" << rss_after_run << ","
         << "\"peakRssBeforeKb\":" << peak_before << ","
         << "\"peakRssAfterKb\":" << peak_after << ","
         << "\"segments\":" << segments << ","
         << "\"systemInfo\":\"" << json_escape(whisper_print_system_info()) << "\","
         << "\"text\":\"" << json_escape(transcript) << "\""
         << "}";
    const std::string output = json.str();
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                        reinterpret_cast<const jbyte *>(output.data()));
    return result;
}
