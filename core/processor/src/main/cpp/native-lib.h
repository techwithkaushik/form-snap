#pragma once

#include <jni.h>

extern "C" {
JNIEXPORT jlong JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeDetect(
    JNIEnv*, jobject, jlong, jint, jdouble, jint, jdouble);
JNIEXPORT jfloatArray JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReadResult(
    JNIEnv*, jobject, jlong);
JNIEXPORT jlong JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReleaseResult(
    JNIEnv*, jobject, jlong);
}
