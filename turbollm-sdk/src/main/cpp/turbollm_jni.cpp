#include <jni.h>

#include "llama.h"

extern "C" JNIEXPORT void JNICALL
Java_dev_turbollm_sdk_engine_llama_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}
