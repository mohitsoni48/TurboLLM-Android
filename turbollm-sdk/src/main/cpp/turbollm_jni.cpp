// The JNI edge of LlamaNative (architecture §2.6). Every call is wrapped in guarded(): no C++ exception
// ever reaches the JVM. A failure is stored for lastError() and the call returns its sentinel instead.
#include <jni.h>

#include "jni_text.h"
#include "llama.h"
#include "llama_session.h"
#include "session_error.h"
#include "session_registry.h"

#include <exception>
#include <mutex>
#include <string>
#include <utility>

using turbollm::java_from_utf8;
using turbollm::session_failure;
using turbollm::utf8_from_java;

namespace {

turbollm::session_registry & sessions() {
    static turbollm::session_registry registry;
    return registry;
}

// Per thread, and reset by every call but lastError(), so it always describes the latest call.
std::string & last_error() {
    thread_local std::string message;
    return message;
}

void remember(const char * message) noexcept {
    try {
        last_error() = message;
    } catch (const std::bad_alloc &) {
        // Out of memory: the message is lost, but throwing here would cross into the JVM.
    }
}

template <typename Call, typename OnFailure>
auto guarded(Call call, OnFailure on_failure) noexcept -> decltype(call()) {
    last_error().clear();
    try {
        return call();
    } catch (const turbollm::session_error & error) {
        remember(error.what());
        return on_failure(error.failure());
    } catch (const std::exception & error) {
        remember(error.what());
        return on_failure(session_failure::failed);
    } catch (...) {
        remember("An unknown native failure occurred.");
        return on_failure(session_failure::failed);
    }
}

void nothing_more(session_failure) {}

jint failure_code(session_failure failure) {
    return static_cast<jint>(failure);
}

jlong no_session(session_failure) {
    return 0;
}

jbyteArray no_token(session_failure) {
    return nullptr;
}

auto empty_text(JNIEnv * env) {
    return [env](session_failure) { return env->NewStringUTF(""); };
}

turbollm::llama_session & session(jlong handle) {
    return sessions().find(handle);
}

jbyteArray java_bytes(JNIEnv * env, const std::string & bytes) {
    const auto length = static_cast<jsize>(bytes.size());
    jbyteArray array  = env->NewByteArray(length);
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, length, reinterpret_cast<const jbyte *>(bytes.data()));
    }
    return array;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_backendInit(JNIEnv *, jobject) {
    static std::once_flag backend_ready;
    guarded([] { std::call_once(backend_ready, llama_backend_init); }, nothing_more);
}

JNIEXPORT jlong JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_loadModel(
    JNIEnv * env, jobject, jstring path, jint context_size, jint threads) {
    return guarded(
        [&] {
            auto loaded = turbollm::llama_session::load(utf8_from_java(env, path), context_size, threads);
            return static_cast<jlong>(sessions().adopt(std::move(loaded)));
        },
        no_session);
}

JNIEXPORT jstring JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_modelInfo(JNIEnv * env, jobject,
                                                                                  jlong handle) {
    return guarded([&] { return java_from_utf8(env, session(handle).model_info_json()); }, empty_text(env));
}

JNIEXPORT jint JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_prepare(JNIEnv * env, jobject, jlong handle,
                                                                             jstring request_json) {
    return guarded([&] { return static_cast<jint>(session(handle).prepare(utf8_from_java(env, request_json))); },
                   failure_code);
}

JNIEXPORT jint JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_startSampling(
    JNIEnv *, jobject, jlong handle, jfloat temperature, jfloat top_p, jint top_k, jlong seed) {
    return guarded(
        [&] {
            session(handle).start_sampling({ temperature, top_p, top_k, seed });
            return jint{ 0 };
        },
        failure_code);
}

JNIEXPORT jbyteArray JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_nextToken(JNIEnv * env, jobject,
                                                                                     jlong handle) {
    return guarded(
        [&]() -> jbyteArray {
            const auto piece = session(handle).next_token();
            return piece ? java_bytes(env, *piece) : nullptr;
        },
        no_token);
}

JNIEXPORT jstring JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_parseDelta(JNIEnv * env, jobject,
                                                                                   jlong handle, jboolean partial) {
    return guarded([&] { return java_from_utf8(env, session(handle).parse_delta_json(partial == JNI_TRUE)); },
                   empty_text(env));
}

JNIEXPORT void JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_requestAbort(JNIEnv *, jobject,
                                                                                  jlong handle) {
    guarded([&] { sessions().request_abort(handle); }, nothing_more);
}

JNIEXPORT void JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_clearAbort(JNIEnv *, jobject, jlong handle) {
    guarded([&] { session(handle).clear_abort(); }, nothing_more);
}

JNIEXPORT jstring JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_stats(JNIEnv * env, jobject, jlong handle) {
    return guarded([&] { return java_from_utf8(env, session(handle).stats_json()); }, empty_text(env));
}

JNIEXPORT void JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_freeSession(JNIEnv *, jobject, jlong handle) {
    guarded([&] { sessions().release(handle).reset(); }, nothing_more);
}

JNIEXPORT jstring JNICALL Java_dev_turbollm_sdk_engine_llama_LlamaNative_lastError(JNIEnv * env, jobject) {
    try {
        return java_from_utf8(env, last_error());
    } catch (const std::exception &) {
        return env->NewStringUTF("");
    }
}

}  // extern "C"
