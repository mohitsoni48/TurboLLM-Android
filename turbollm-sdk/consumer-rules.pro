# Rules an app that shrinks its code (R8) applies to the TurboLLM SDK.

# The JNI symbols are Java_dev_turbollm_sdk_engine_llama_LlamaNative_<method>: renaming the class or
# one of its external methods breaks the native lookup at runtime.
-keep class dev.turbollm.sdk.engine.llama.LlamaNative {
    public static final dev.turbollm.sdk.engine.llama.LlamaNative INSTANCE;
    native <methods>;
}

# Requests, responses and the download manifest are (de)serialized by kotlinx.serialization through
# their generated serializers, which are looked up by the class's companion.
-keep @kotlinx.serialization.Serializable class dev.turbollm.sdk.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class dev.turbollm.sdk.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.turbollm.sdk.**$$serializer { *; }
