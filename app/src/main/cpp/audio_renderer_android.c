/* audio_renderer_android.c — 音频渲染器：压缩帧(ct: 2=AAC, 3=AAC-ELD, 0=PCM)经 JNI 推给 Java 解码播放 */
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <jni.h>
#include <android/log.h>

typedef unsigned int guint;
#include "../../../../uxplay/renderers/audio_renderer.h"
#include "jni_bridge.h"

extern JavaVM *g_vm;

static unsigned char compression = 3;  /* 默认 AAC-ELD（镜像） */
static long audio_push_count = 0;

void bridge_push_audio_c(unsigned char *data, int len, unsigned char ct) {
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_push_audio || len <= 0) return;
    if ((++audio_push_count % 500) == 1)
        __android_log_print(ANDROID_LOG_INFO, "AudioBridge", "音频帧推送 #%ld len=%d ct=%d",
                            audio_push_count, len, ct);
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_push_audio, arr, len, (jbyte) ct);
    (*env)->DeleteLocalRef(env, arr);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

bool gstreamer_init() { return true; }

void audio_renderer_init(logger_t *logger, const char *audiosink, const bool *audio_sync,
                         const bool *video_sync, const char *artp_pipeline) {
    (void) logger; (void) audiosink; (void) audio_sync; (void) video_sync; (void) artp_pipeline;
}

void audio_renderer_start(unsigned char *compression_type) {
    if (compression_type) {
        compression = *compression_type;
        __android_log_print(ANDROID_LOG_INFO, "AudioBridge", "audio_renderer_start ct=%d", compression);
    }
}

void audio_renderer_stop() {}

void audio_renderer_render_buffer(unsigned char *data, int *data_len, unsigned short *seqnum, uint64_t *ntp_time) {
    (void) seqnum;
    static int64_t last_log_ns = 0;
    int64_t now_ns = bridge_monotonic_ns();
    if (ntp_time && g_video_arrival_offset_ns != 0 && now_ns - last_log_ns > 5000000000LL) {
        last_log_ns = now_ns;
        int64_t audio_offset_ns = now_ns - (int64_t) *ntp_time;
        /* Positive: audio reaches the TV later than the video it belongs to */
        __android_log_print(ANDROID_LOG_INFO, "AVSync", "audio_late_ms=%lld",
                            (long long) ((audio_offset_ns - g_video_arrival_offset_ns) / 1000000));
    }
    bridge_push_audio_c(data, *data_len, compression);
}

void audio_renderer_set_volume(double volume) {
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_set_volume) return;
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_set_volume, (jfloat) volume);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

void audio_renderer_flush() {}
void audio_renderer_destroy() {}
unsigned int audio_renderer_listen(void *loop, int id) { (void) loop; (void) id; return 0; }
