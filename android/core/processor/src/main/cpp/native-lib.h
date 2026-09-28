#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeDetect(
    JNIEnv* env,
    jobject thiz,
    jlong bgrMatAddr,
    jint kind,
    jdouble bias,
    jint blockSize,
    jdouble localC);

JNIEXPORT jfloatArray JNICALL
Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReadResult(
    JNIEnv* env,
    jobject thiz,
    jlong resultAddr);

JNIEXPORT jlong JNICALL
Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReleaseResult(
    JNIEnv* env,
    jobject thiz,
    jlong resultAddr);

}
