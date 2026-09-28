#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_org_techwithkaushik_formsnap_processor_ImageProcessor_processNativeForm(
    JNIEnv* env,
    jobject thiz,
    jbyteArray input,
    jint blockSize,
    jdouble constant
);

}
