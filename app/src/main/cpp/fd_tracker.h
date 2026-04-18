/**
 * fd_tracker.h — File descriptor → sensitive-file-type tracking + helpers.
 */

#pragma once

#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "HOMVPN_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

enum class SensitiveFile : uint8_t {
    NONE              = 0,
    PROC_MAPS         = 1,
    PROC_NET_TCP      = 2,
    PROC_STATUS       = 3,
    PROC_MOUNTS       = 4,
    PROC_NET_DEV      = 5,
    PROC_NET_IF_INET6 = 6,
    PROC_NET_ROUTE    = 7,
};

// ── VPN interface name filter ───────────────────────────────────────────────

static inline bool is_vpn_interface(const char* name) {
    if (!name) return false;
    return strncmp(name, "tun",   3) == 0 ||
           strncmp(name, "tap",   3) == 0 ||
           strncmp(name, "ppp",   3) == 0 ||
           strncmp(name, "wg",    2) == 0 ||
           strncmp(name, "ipsec", 5) == 0;
}

// ── Netlink socket tracking ─────────────────────────────────────────────────

static uint8_t g_nl_socket_table[65536] = {0};

static inline void track_nl_socket(int fd) {
    if (fd >= 0 && fd < 65536) g_nl_socket_table[fd] = 1;
}

static inline void untrack_nl_socket(int fd) {
    if (fd >= 0 && fd < 65536) g_nl_socket_table[fd] = 0;
}

static inline bool is_nl_socket(int fd) {
    return fd >= 0 && fd < 65536 && g_nl_socket_table[fd] != 0;
}

// Interface index → VPN flag (populated from RTM_NEWLINK), used to filter
// RTM_NEWADDR messages for the same (now-hidden) interface index.
static uint8_t g_vpn_iface_idx[512] = {0};

static inline void mark_vpn_iface_idx(unsigned int idx) {
    if (idx > 0 && idx < 512) g_vpn_iface_idx[idx] = 1;
}

static inline bool is_vpn_iface_idx(unsigned int idx) {
    return idx > 0 && idx < 512 && g_vpn_iface_idx[idx] != 0;
}

// ── FD tracking table ────────────────────────────────────────────────────────

static uint8_t g_fd_table[65536] = {0};

static inline SensitiveFile classify_path(const char* path) {
    if (!path) return SensitiveFile::NONE;

    if (strstr(path, "/proc/") && strstr(path, "/maps"))
        return SensitiveFile::PROC_MAPS;

    if (strstr(path, "/proc/net/tcp") || strstr(path, "/proc/self/net/tcp"))
        return SensitiveFile::PROC_NET_TCP;

    if (strstr(path, "/proc/") && strstr(path, "/status"))
        return SensitiveFile::PROC_STATUS;

    if (strstr(path, "/proc/") && (strstr(path, "/mountinfo") || strstr(path, "/mounts")))
        return SensitiveFile::PROC_MOUNTS;

    if (strstr(path, "/proc/net/if_inet6") || strstr(path, "/proc/self/net/if_inet6"))
        return SensitiveFile::PROC_NET_IF_INET6;

    if (strstr(path, "/proc/net/dev") || strstr(path, "/proc/self/net/dev"))
        return SensitiveFile::PROC_NET_DEV;

    if (strstr(path, "/proc/net/route") || strstr(path, "/proc/self/net/route"))
        return SensitiveFile::PROC_NET_ROUTE;

    return SensitiveFile::NONE;
}

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
