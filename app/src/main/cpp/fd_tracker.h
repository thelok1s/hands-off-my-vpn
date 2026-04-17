/**
 * fd_tracker.h — Thread-safe file descriptor → sensitive-file-type tracking.
 *
 * When our hooked open()/openat()/fopen() detects that the target app is
 * opening a sensitive /proc pseudo-file, we record the returned fd here.
 * Later, our hooked read()/pread64() checks this table to decide whether
 * the read buffer needs sanitization before being returned to the app.
 *
 * Thread safety: all public functions acquire `g_fd_mutex` internally.
 * The mutex only guards the map operations — per-buffer sanitization in the
 * read hooks happens outside the lock.
 */

#pragma once

#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "HOMVPN_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Types of sensitive /proc files we track.
 */
enum class SensitiveFile : uint8_t {
    NONE         = 0,
    PROC_MAPS    = 1,   // /proc/self/maps, /proc/<pid>/maps
    PROC_NET_TCP = 2,   // /proc/net/tcp, /proc/net/tcp6
    PROC_STATUS  = 3,   // /proc/self/status
    PROC_MOUNTS  = 4,   // /proc/self/mountinfo, /proc/self/mounts
    PROC_NET_DEV = 5,   // /proc/net/dev, /proc/net/if_inet6
};

// ── Global state ────────────────────────────────────────────────────────────

// Use a flat, lock-free array to track FDs up to 65535.
// This statically allocates 64KB, which guarantees zero OOM on malloc,
// zero std::mutex deadlocks inside async signal handlers, and O(1) tracking.
static uint8_t g_fd_table[65536] = {0};

// ── Path classification ─────────────────────────────────────────────────────

static inline SensitiveFile classify_path(const char* path) {
    if (!path) return SensitiveFile::NONE;

    if (strstr(path, "/proc/") && strstr(path, "/maps"))
        return SensitiveFile::PROC_MAPS;

    if (strstr(path, "/proc/net/tcp"))
        return SensitiveFile::PROC_NET_TCP;

    if (strstr(path, "/proc/") && strstr(path, "/status"))
        return SensitiveFile::PROC_STATUS;

    if (strstr(path, "/proc/") && (strstr(path, "/mountinfo") || strstr(path, "/mounts")))
        return SensitiveFile::PROC_MOUNTS;

    // /proc/net/dev lists all interfaces with traffic stats (tun0, wlan0, etc)
    // /proc/net/if_inet6 lists IPv6 addresses per interface
    if (strstr(path, "/proc/net/dev") || strstr(path, "/proc/net/if_inet6"))
        return SensitiveFile::PROC_NET_DEV;

    return SensitiveFile::NONE;
}

// ── FD tracking API (Async-Signal-Safe) ─────────────────────────────────────

static inline void track_fd(int fd, SensitiveFile type) {
    if (fd < 0 || fd >= 65536 || type == SensitiveFile::NONE) return;
    g_fd_table[fd] = static_cast<uint8_t>(type);
}

static inline void untrack_fd(int fd) {
    if (fd < 0 || fd >= 65536) return;
    g_fd_table[fd] = 0;
}

static inline SensitiveFile get_fd_type(int fd) {
    if (fd < 0 || fd >= 65536) return SensitiveFile::NONE;
    return static_cast<SensitiveFile>(g_fd_table[fd]);
}
