/*
 * JNI wrapper for hev-socks5-tunnel
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>

#include "../hev-socks5-tunnel-src/include/hev-socks5-tunnel.h"

#define LOG_TAG "HevTunnel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pthread_t tunnel_thread;
static pthread_mutex_t tunnel_mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile int tunnel_running = 0;
static char *config_content = NULL;
static int tun_fd_global = -1;
static char crash_log_path[512];
static volatile sig_atomic_t signal_handlers_installed = 0;

static void append_crash_log_line(const char *line) {
    if (!crash_log_path[0]) {
        return;
    }

    int fd = open(crash_log_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) {
        return;
    }

    write(fd, line, strlen(line));
    close(fd);
}

static void native_signal_handler(int sig, siginfo_t *info, void *ctx) {
    (void)ctx;
    char line[192];
    int len = snprintf(
        line,
        sizeof(line),
        "native crash: signal=%d fault_addr=%p component=hev-socks5-tunnel\n",
        sig,
        info ? info->si_addr : NULL
    );
    if (len > 0) {
        append_crash_log_line(line);
    }

    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_signal_handlers(void) {
    if (signal_handlers_installed) {
        return;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    signal_handlers_installed = 1;
}

static void *tunnel_thread_func(void *arg) {
    LOGI("Tunnel thread started");

    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *)config_content,
        strlen(config_content),
        tun_fd_global
    );

    LOGI("Tunnel thread exited with result: %d", result);
    tunnel_running = 0;

    return NULL;
}

JNIEXPORT jint JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeStart(
    JNIEnv *env,
    jclass clazz,
    jstring config,
    jint tun_fd
) {
    pthread_mutex_lock(&tunnel_mutex);

    if (tunnel_running) {
        LOGE("Tunnel already running");
        pthread_mutex_unlock(&tunnel_mutex);
        return -1;
    }

    install_signal_handlers();

    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    if (!config_str) {
        LOGE("Failed to get config string");
        pthread_mutex_unlock(&tunnel_mutex);
        return -1;
    }

    // Free old config if exists
    if (config_content) {
        free(config_content);
    }

    // Copy config
    config_content = strdup(config_str);
    (*env)->ReleaseStringUTFChars(env, config, config_str);

    if (!config_content) {
        LOGE("Failed to allocate config memory");
        pthread_mutex_unlock(&tunnel_mutex);
        return -1;
    }

    tun_fd_global = tun_fd;
    tunnel_running = 1;

    LOGI("Starting tunnel with fd=%d", tun_fd);
    LOGI("Config:\n%s", config_content);

    int ret = pthread_create(&tunnel_thread, NULL, tunnel_thread_func, NULL);
    if (ret != 0) {
        LOGE("Failed to create tunnel thread: %d", ret);
        tunnel_running = 0;
        free(config_content);
        config_content = NULL;
        pthread_mutex_unlock(&tunnel_mutex);
        return -1;
    }

    LOGI("Tunnel started successfully");
    pthread_mutex_unlock(&tunnel_mutex);
    return 0;
}

JNIEXPORT void JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeSetCrashLogPath(
    JNIEnv *env,
    jclass clazz,
    jstring path
) {
    (void)clazz;

    pthread_mutex_lock(&tunnel_mutex);

    if (!path) {
        crash_log_path[0] = '\0';
        pthread_mutex_unlock(&tunnel_mutex);
        return;
    }

    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str) {
        crash_log_path[0] = '\0';
        pthread_mutex_unlock(&tunnel_mutex);
        return;
    }

    strncpy(crash_log_path, path_str, sizeof(crash_log_path) - 1);
    crash_log_path[sizeof(crash_log_path) - 1] = '\0';
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    pthread_mutex_unlock(&tunnel_mutex);
}

JNIEXPORT void JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeStop(
    JNIEnv *env,
    jclass clazz
) {
    pthread_mutex_lock(&tunnel_mutex);

    if (!tunnel_running) {
        LOGI("Tunnel not running");
        pthread_mutex_unlock(&tunnel_mutex);
        return;
    }

    LOGI("Stopping tunnel...");
    hev_socks5_tunnel_quit();

    // Wait for thread to finish
    pthread_join(tunnel_thread, NULL);

    if (config_content) {
        free(config_content);
        config_content = NULL;
    }

    tunnel_running = 0;
    tun_fd_global = -1;
    LOGI("Tunnel stopped");
    pthread_mutex_unlock(&tunnel_mutex);
}

JNIEXPORT void JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeSetRejectQuic(
    JNIEnv *env,
    jclass clazz,
    jboolean enabled
) {
    hev_socks5_tunnel_set_reject_quic(enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeSetRejectNonDnsUdp(
    JNIEnv *env,
    jclass clazz,
    jboolean enabled
) {
    hev_socks5_tunnel_set_reject_non_dns_udp(enabled ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeIsRunning(
    JNIEnv *env,
    jclass clazz
) {
    return tunnel_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_app_slipnet_tunnel_HevSocks5Tunnel_nativeGetStats(
    JNIEnv *env,
    jclass clazz
) {
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    size_t quic_rejected = 0, udp_rejected = 0;

    if (tunnel_running) {
        hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
        quic_rejected = hev_socks5_tunnel_stats_quic_rejected();
        udp_rejected = hev_socks5_tunnel_stats_udp_rejected();
    }

    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result) {
        jlong stats[6] = {
            (jlong)tx_packets,
            (jlong)tx_bytes,
            (jlong)rx_packets,
            (jlong)rx_bytes,
            (jlong)quic_rejected,
            (jlong)udp_rejected
        };
        (*env)->SetLongArrayRegion(env, result, 0, 6, stats);
    }

    return result;
}
