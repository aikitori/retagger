// JNI bridge to LAME for de.aikitori.retagger.convert.Mp3Encoder
#include <jni.h>
#include <stdint.h>
#include "lame.h"

#define FN(name) Java_de_aikitori_retagger_convert_Mp3Encoder_##name

static lame_global_flags *flags(jlong handle) { return (lame_global_flags *) (intptr_t) handle; }

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jobject thiz, jint sampleRate, jint channels, jboolean vbr, jint value) {
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    if (vbr) {
        lame_set_VBR(g, vbr_default);
        lame_set_VBR_quality(g, (float) value);
    } else {
        lame_set_VBR(g, vbr_off);
        lame_set_brate(g, value);
    }
    lame_set_quality(g, 2);
    // Xing/LAME header so players know the exact length of VBR files
    lame_set_bWriteVbrTag(g, 1);
    if (lame_init_params(g) < 0) {
        lame_close(g);
        return 0;
    }
    return (jlong) (intptr_t) g;
}

JNIEXPORT jint JNICALL FN(encode)(JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm, jint frames, jbyteArray out) {
    lame_global_flags *g = flags(handle);
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    jsize size = (*env)->GetArrayLength(env, out);
    int written = lame_get_num_channels(g) == 1
        ? lame_encode_buffer(g, samples, samples, frames, (unsigned char *) buffer, size)
        : lame_encode_buffer_interleaved(g, samples, frames, (unsigned char *) buffer, size);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT jint JNICALL FN(flush)(JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    int written = lame_encode_flush(flags(handle), (unsigned char *) buffer, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return written;
}

JNIEXPORT jint JNICALL FN(lameTag)(JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    jbyte *buffer = (*env)->GetByteArrayElements(env, out, NULL);
    size_t written = lame_get_lametag_frame(flags(handle), (unsigned char *) buffer, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, buffer, 0);
    return (jint) written;
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jobject thiz, jlong handle) {
    lame_close(flags(handle));
}
