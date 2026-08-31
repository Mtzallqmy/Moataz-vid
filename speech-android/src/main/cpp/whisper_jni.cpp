#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

namespace { thread_local std::string last_error; }

extern "C" JNIEXPORT jlong JNICALL
Java_com_moatazvid_speech_android_WhisperCppBridge_nativeLoadModel(JNIEnv *env, jobject, jstring path, jint) {
    const char *raw = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(raw, params);
    env->ReleaseStringUTFChars(path, raw);
    if (!ctx) { last_error = "whisper.cpp could not load the verified model"; return 0; }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moatazvid_speech_android_WhisperCppBridge_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray input, jstring language, jboolean) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) { last_error = "Invalid model handle"; return nullptr; }
    const jsize count = env->GetArrayLength(input);
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(input, 0, count, samples.data());
    const char *lang = env->GetStringUTFChars(language, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = std::string(lang) == "auto" ? nullptr : lang;
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
    const int result = whisper_full(ctx, params, samples.data(), samples.size());
    env->ReleaseStringUTFChars(language, lang);
    if (result != 0) { last_error = "whisper_full failed"; return nullptr; }
    const int n = whisper_full_n_segments(ctx);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray rows = env->NewObjectArray(n, string_class, nullptr);
    for (int i = 0; i < n; ++i) {
        const int64_t start_us = whisper_full_get_segment_t0(ctx, i) * 10000;
        const int64_t end_us = whisper_full_get_segment_t1(ctx, i) * 10000;
        std::string row = std::to_string(start_us) + "\x1f" + std::to_string(end_us) + "\x1f" + whisper_full_get_segment_text(ctx, i);
        env->SetObjectArrayElement(rows, i, env->NewStringUTF(row.c_str()));
    }
    return rows;
}

extern "C" JNIEXPORT void JNICALL
Java_com_moatazvid_speech_android_WhisperCppBridge_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_moatazvid_speech_android_WhisperCppBridge_nativeLastError(JNIEnv *env, jobject) { return env->NewStringUTF(last_error.c_str()); }
