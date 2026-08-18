// SPDX-License-Identifier: GPL-3.0-only
//
// The thin layer between de.singular.player.audio.Mp3 and LAME.
//
// Everything that decides anything — bitrate, what to do with a short buffer, when to stop — is on
// the Kotlin side. This holds an encoder, takes 16-bit PCM in and hands MP3 frames back, and that
// is the whole contract. Errors come back as negative returns rather than as exceptions, because a
// JNI throw from inside an encode loop is a second failure path for no benefit.

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "lame/include/lame.h"

// A handle is a pointer widened to jlong. Zero is "nothing here", so a failed init is a plain 0.
static lame_global_flags *flags_of(jlong handle) {
    return (lame_global_flags *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_de_singular_player_audio_Mp3_nativeInit(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels, jint bitrate_kbps) {
    (void) env;
    (void) clazz;
    lame_global_flags *flags = lame_init();
    if (flags == NULL) return 0;

    lame_set_in_samplerate(flags, sample_rate);
    lame_set_num_channels(flags, channels);
    // Out at the rate it came in: resampling a master to fit a bitrate is a decision this app has
    // no business making silently.
    lame_set_out_samplerate(flags, sample_rate);
    lame_set_mode(flags, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(flags, vbr_off);
    lame_set_brate(flags, bitrate_kbps);
    // 2 is LAME's own default: the slower settings buy very little at these bitrates, and this runs
    // on a phone with the screen on.
    lame_set_quality(flags, 2);
    // No Xing/LAME header frame: it is written by lame_get_lametag_frame() into the *start* of the
    // file, and a SAF stream cannot be seeked back to put it there. A CBR file without one is still
    // an ordinary MP3 — players get the duration from the frame count.
    lame_set_bWriteVbrTag(flags, 0);
    // No ID3 from here either. Tags are written afterwards by TagIo, which is what the tag editor
    // uses and the one place in this app that decides what a tag looks like.
    lame_set_write_id3tag_automatic(flags, 0);

    if (lame_init_params(flags) < 0) {
        lame_close(flags);
        return 0;
    }
    return (jlong) (intptr_t) flags;
}

// Interleaved 16-bit PCM in, frames out. Returns the bytes written into mp3, or a negative LAME
// error code. The caller sizes mp3 by Mp3.bufferBytes(), which is LAME's documented worst case.
JNIEXPORT jint JNICALL
Java_de_singular_player_audio_Mp3_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray pcm, jint offset, jint count,
        jbyteArray mp3) {
    (void) clazz;
    lame_global_flags *flags = flags_of(handle);
    if (flags == NULL) return -1;

    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (in == NULL) return -1;
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, mp3, NULL);
    if (out == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
        return -1;
    }

    const int channels = lame_get_num_channels(flags);
    // "Samples" to LAME means frames — one per channel — which is what count/2/channels is.
    const int frames = count / 2 / channels;
    const jsize capacity = (*env)->GetArrayLength(env, mp3);
    int written = lame_encode_buffer_interleaved(
            flags, (short *) (in + offset), frames, (unsigned char *) out, capacity);

    (*env)->ReleasePrimitiveArrayCritical(env, mp3, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return written;
}

// The last partial frame, padded and encoded. Returns bytes written, or negative on failure.
JNIEXPORT jint JNICALL
Java_de_singular_player_audio_Mp3_nativeFlush(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray mp3) {
    (void) clazz;
    lame_global_flags *flags = flags_of(handle);
    if (flags == NULL) return -1;

    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, mp3, NULL);
    if (out == NULL) return -1;
    const jsize capacity = (*env)->GetArrayLength(env, mp3);
    int written = lame_encode_flush(flags, (unsigned char *) out, capacity);
    (*env)->ReleasePrimitiveArrayCritical(env, mp3, out, 0);
    return written;
}

JNIEXPORT void JNICALL
Java_de_singular_player_audio_Mp3_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    lame_global_flags *flags = flags_of(handle);
    if (flags != NULL) lame_close(flags);
}
