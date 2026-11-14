#pragma once

#include <jni.h>

#include <string>

namespace turbollm {

// Java strings are UTF-16 and the session speaks UTF-8. JNI's own *StringUTF calls use modified UTF-8,
// which mangles every character outside the Basic Multilingual Plane (emoji) and aborts under CheckJNI,
// so text crosses the boundary through these two instead. Malformed input becomes U+FFFD: one per bad
// byte or unpaired surrogate, so nothing is silently dropped.
std::string utf8_from_java(JNIEnv * env, jstring text);
jstring     java_from_utf8(JNIEnv * env, const std::string & text);

}  // namespace turbollm
