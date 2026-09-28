#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL
Java_org_techwithkaushik_formsnap_processor_ImageProcessor_processNativeForm(
    JNIEnv* env,
    jobject thiz,
    jbyteArray input,
    jint blockSize,
    jdouble constant
);

#ifdef __cplusplus
}
#endif
