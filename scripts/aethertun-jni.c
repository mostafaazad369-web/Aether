/*
 * Aether JNI bridge for the pinned HEV core.
 *
 * The event loop runs on a native pthread (hev-task-system switches stacks;
 * running it on an ART-managed Java thread is unsafe).
 */
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

extern int hev_socks5_tunnel_main_with_ready_callback(
    const char *config_path, int tun_fd, uint64_t generation,
    void (*ready_callback)(void *), void *user_data);
extern void hev_socks5_tunnel_quit_generation(uint64_t generation);
extern void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                                    size_t *rx_packets, size_t *rx_bytes);

#define TAG "aethertun"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    LOGI("libaethertun generation-aware bridge loaded");
    return JNI_VERSION_1_6;
}

typedef enum {
    TUNNEL_IDLE = 0,
    TUNNEL_STARTING,
    TUNNEL_RUNNING,
    TUNNEL_STOP_REQUESTED,
    TUNNEL_EXITED
} TunnelState;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static TunnelState state = TUNNEL_IDLE;
static bool stop_dispatched;
static uint64_t generation_counter;
static uint64_t active_generation;

typedef struct {
    char *config_path;
    int tun_fd;
    uint64_t generation;
} StartArgs;

/* Called by the patched HEV main path only after tunnel init succeeded. */
static void
hev_ready_callback(void *user_data)
{
    StartArgs *args = (StartArgs *)user_data;
    bool dispatch_stop = false;

    pthread_mutex_lock(&lock);
    if (active_generation != args->generation) {
        pthread_mutex_unlock(&lock);
        return;
    }
    if (state == TUNNEL_STARTING) {
        state = TUNNEL_RUNNING;
    } else if (state == TUNNEL_STOP_REQUESTED && !stop_dispatched) {
        stop_dispatched = true;
        dispatch_stop = true;
    }
    pthread_mutex_unlock(&lock);

    if (dispatch_stop)
        hev_socks5_tunnel_quit_generation(args->generation);
}

static void *
tunnel_thread(void *data)
{
    StartArgs *args = (StartArgs *)data;
    int res;

    LOGI("HEV worker starting (fd=%d)", args->tun_fd);
    res = hev_socks5_tunnel_main_with_ready_callback(
        args->config_path, args->tun_fd, args->generation,
        hev_ready_callback, args);
    LOGI("HEV worker returned (res=%d)", res);

    if (args->tun_fd >= 0)
        close(args->tun_fd);
    free(args->config_path);
    free(args);

    /* HEV's own lifecycle gate is already inactive when main returns. */
    pthread_mutex_lock(&lock);
    state = TUNNEL_EXITED;
    active_generation = 0;
    stop_dispatched = false;
    pthread_mutex_unlock(&lock);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyStartService(
    JNIEnv *env, jclass clazz, jstring config_path, jint tun_fd)
{
    const char *path;
    StartArgs *args;
    pthread_attr_t attr;
    pthread_t thread;
    int rc;

    (void)clazz;
    if (config_path == NULL)
        return JNI_FALSE;

    path = (*env)->GetStringUTFChars(env, config_path, NULL);
    if (path == NULL)
        return JNI_FALSE;

    args = (StartArgs *)calloc(1, sizeof(*args));
    if (args != NULL)
        args->config_path = strdup(path);
    if (args != NULL)
        args->tun_fd = dup((int)tun_fd);
    (*env)->ReleaseStringUTFChars(env, config_path, path);

    if (args == NULL || args->config_path == NULL || args->tun_fd < 0) {
        if (args != NULL) {
            if (args->tun_fd >= 0)
                close(args->tun_fd);
            free(args->config_path);
            free(args);
        }
        return JNI_FALSE;
    }

    pthread_mutex_lock(&lock);
    if (state == TUNNEL_STARTING || state == TUNNEL_RUNNING) {
        pthread_mutex_unlock(&lock);
        close(args->tun_fd);
        free(args->config_path);
        free(args);
        return JNI_TRUE; /* this generation has already been accepted */
    }
    if (state == TUNNEL_STOP_REQUESTED) {
        pthread_mutex_unlock(&lock);
        close(args->tun_fd);
        free(args->config_path);
        free(args);
        return JNI_FALSE; /* never report success for a generation draining */
    }

    if (++generation_counter == 0)
        ++generation_counter;
    args->generation = generation_counter;
    active_generation = args->generation;
    state = TUNNEL_STARTING;
    config_ready = false;
    stop_dispatched = false;

    rc = pthread_attr_init(&attr);
    if (rc == 0) {
        rc = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (rc == 0)
            rc = pthread_create(&thread, &attr, tunnel_thread, args);
        pthread_attr_destroy(&attr);
    }
    if (rc != 0) {
        state = TUNNEL_IDLE;
        active_generation = 0;
        config_ready = false;
        stop_dispatched = false;
    }
    pthread_mutex_unlock(&lock);

    if (rc != 0) {
        LOGE("could not create HEV worker: %d", rc);
        close(args->tun_fd);
        free(args->config_path);
        free(args);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyStopService(
    JNIEnv *env, jclass clazz)
{
    uint64_t stop_generation = 0;
    bool dispatch_stop = false;
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&lock);
    if (state == TUNNEL_STARTING) {
        /* Defer quit until HEV confirms successful tunnel initialization. */
        state = TUNNEL_STOP_REQUESTED;
    } else if (state == TUNNEL_RUNNING) {
        state = TUNNEL_STOP_REQUESTED;
        stop_dispatched = true;
        stop_generation = active_generation;
        dispatch_stop = true;
    }

    /*
     * Keep the bridge lock through dispatch. This prevents the worker from
     * publishing EXITED and a new generation from starting before this stop
     * has crossed into HEV. HEV also validates the generation token itself.
     */
    if (dispatch_stop)
        hev_socks5_tunnel_quit_generation(stop_generation);
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyIsActive(
    JNIEnv *env, jclass clazz)
{
    jboolean active;
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&lock);
    active = (state == TUNNEL_STARTING ||
              state == TUNNEL_RUNNING ||
              state == TUNNEL_STOP_REQUESTED) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&lock);
    return active;
}

JNIEXPORT jlongArray JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyGetStats(
    JNIEnv *env, jclass clazz)
{
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    jlong values[4];
    jlongArray result;
    (void)clazz;

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    values[0] = (jlong)tx_packets;
    values[1] = (jlong)tx_bytes;
    values[2] = (jlong)rx_packets;
    values[3] = (jlong)rx_bytes;

    result = (*env)->NewLongArray(env, 4);
    if (result == NULL)
        return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
