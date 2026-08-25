#include <jni.h>

JNIEXPORT jlong JNICALL
Java_org_autojs_fixture_r8compiler_g7_G7NativeBridge_nativeRoundTrip(
        JNIEnv *environment,
        jclass type,
        jlong value) {
    (void) environment;
    (void) type;
    return value + 7;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_fixture_r8compiler_g7_G7NativeBridge_nativeStaticMessage(
        JNIEnv *environment,
        jclass type) {
    (void) type;
    return (*environment)->NewStringUTF(environment, "g7-jni-static");
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *virtualMachine, void *reserved) {
    (void) virtualMachine;
    (void) reserved;
    return JNI_VERSION_1_6;
}
