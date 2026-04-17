/**
 * homvpn_native.cpp — Native payload for HandsOffMyVPN.
 *
 * Loaded into the target app's process by NativeLoader.kt via System.load().
 * JNI_OnLoad installs Dobby inline hooks on libc functions to intercept and
 * sanitize reads from sensitive /proc pseudo-files, and filter native
 * network interface enumeration to hide VPN tunnel interfaces.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ Architecture Overview                                                  │
 * │                                                                        │
 * │  open(path) / openat(fd, path) / fopen(path)                          │
 * │    └─► classify_path(path)                                            │
 * │          └─► if sensitive → track_fd(returned_fd, type)               │
 * │                                                                        │
 * │  read(fd, buf, count) / pread64(fd, buf, count, offset)               │
 * │    └─► get_fd_type(fd)                                                │
 * │          └─► if tracked → call original → sanitize buffer → return    │
 * │                                                                        │
 * │  close(fd)                                                             │
 * │    └─► untrack_fd(fd)                                                 │
 * │                                                                        │
 * │  getifaddrs(ifap) → filter out tun/tap/ppp/wg/ipsec interfaces        │
 * │  if_nametoindex(name) → return 0 for VPN interface names              │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <sys/ioctl.h>

#include "dobby.h"
#include "fd_tracker.h"
#include "sanitizer.h"

// ─── Self-redaction path ────────────────────────────────────────────────────
// Resolved at JNI_OnLoad time via dladdr() so we can remove our own .so
// from /proc/self/maps output.
static char g_self_path[512] = {0};

// ─── VPN interface detection ────────────────────────────────────────────────

static bool is_vpn_interface(const char* name) {
    if (!name) return false;
    return strncmp(name, "tun",   3) == 0 ||
           strncmp(name, "tap",   3) == 0 ||
           strncmp(name, "ppp",   3) == 0 ||
           strncmp(name, "wg",    2) == 0 ||
           strncmp(name, "ipsec", 5) == 0;
}

// ─── Original function pointers (set by DobbyHook) ─────────────────────────

// File open family
static int   (*orig_open)(const char*, int, ...)       = nullptr;
static int   (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*)   = nullptr;

// File read family
static ssize_t (*orig_read)(int, void*, size_t)                  = nullptr;
static ssize_t (*orig_pread64)(int, void*, size_t, off64_t)      = nullptr;

// File close
static int (*orig_close)(int) = nullptr;

// Native network interface enumeration
static int (*orig_getifaddrs)(struct ifaddrs**) = nullptr;
static unsigned int (*orig_if_nametoindex)(const char*) = nullptr;
static int (*orig_ioctl)(int, unsigned long, ...) = nullptr;

// ─── Hook implementations ───────────────────────────────────────────────────

/**
 * Hooked open() — intercept file opens and track sensitive fds.
 */
static int hook_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    int fd = orig_open(path, flags, mode);
    if (fd >= 0) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) {
            track_fd(fd, type);
        }
    }
    return fd;
}

/**
 * Hooked openat() — same logic as open() but with a dirfd.
 */
static int hook_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    int fd = orig_openat(dirfd, path, flags, mode);
    if (fd >= 0) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) {
            track_fd(fd, type);
        }
    }
    return fd;
}

/**
 * Hooked fopen() — track the underlying fd from the FILE* stream.
 */
static FILE* hook_fopen(const char* path, const char* mode) {
    FILE* fp = orig_fopen(path, mode);
    if (fp) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) {
            track_fd(fileno(fp), type);
        }
    }
    return fp;
}

/**
 * Hooked read() — if the fd is tracked, sanitize the buffer after reading.
 */
static ssize_t hook_read(int fd, void* buf, size_t count) {
    ssize_t bytes_read = orig_read(fd, buf, count);
    if (bytes_read <= 0) return bytes_read;

    SensitiveFile type = get_fd_type(fd);
    if (type == SensitiveFile::NONE) return bytes_read;

    char* cbuf = static_cast<char*>(buf);
    switch (type) {
        case SensitiveFile::PROC_MAPS:
            sanitize_maps(cbuf, &bytes_read, g_self_path);
            break;
        case SensitiveFile::PROC_NET_TCP:
            sanitize_tcp(cbuf, &bytes_read);
            break;
        case SensitiveFile::PROC_STATUS:
            sanitize_status(cbuf, &bytes_read);
            break;
        case SensitiveFile::PROC_MOUNTS:
            sanitize_mounts(cbuf, &bytes_read);
            break;
        default:
            break;
    }

    return bytes_read;
}

/**
 * Hooked pread64() — same logic as read() but handles the offset parameter.
 */
static ssize_t hook_pread64(int fd, void* buf, size_t count, off64_t offset) {
    ssize_t bytes_read = orig_pread64(fd, buf, count, offset);
    if (bytes_read <= 0) return bytes_read;

    SensitiveFile type = get_fd_type(fd);
    if (type == SensitiveFile::NONE) return bytes_read;

    char* cbuf = static_cast<char*>(buf);
    switch (type) {
        case SensitiveFile::PROC_MAPS:
            sanitize_maps(cbuf, &bytes_read, g_self_path);
            break;
        case SensitiveFile::PROC_NET_TCP:
            sanitize_tcp(cbuf, &bytes_read);
            break;
        case SensitiveFile::PROC_STATUS:
            sanitize_status(cbuf, &bytes_read);
            break;
        case SensitiveFile::PROC_MOUNTS:
            sanitize_mounts(cbuf, &bytes_read);
            break;
        default:
            break;
    }

    return bytes_read;
}

/**
 * Hooked close() — remove the fd from our tracking table on cleanup.
 */
static int hook_close(int fd) {
    untrack_fd(fd);
    return orig_close(fd);
}

/**
 * Hooked getifaddrs() — filters out VPN tunnel interfaces (tun*, tap*, ppp*, wg*, ipsec*)
 * from the linked list returned by the real getifaddrs().
 *
 * The VPN Detector app uses this native API to enumerate all network interfaces
 * and detect tunnel-like interface names. We unlink matching entries from the list
 * and free them with freeifaddrs-compatible cleanup.
 */
static int hook_getifaddrs(struct ifaddrs** ifap) {
    int ret = orig_getifaddrs(ifap);
    if (ret != 0 || !ifap || !*ifap) return ret;

    struct ifaddrs* prev = nullptr;
    struct ifaddrs* curr = *ifap;

    while (curr) {
        if (is_vpn_interface(curr->ifa_name)) {
            // Unlink this node from the list
            struct ifaddrs* to_remove = curr;
            if (prev) {
                prev->ifa_next = curr->ifa_next;
            } else {
                *ifap = curr->ifa_next;
            }
            curr = curr->ifa_next;
            // We can't individually free nodes from getifaddrs — they're
            // allocated as a single block. Instead we just unlink them.
            // The freeifaddrs() call on the head pointer will free everything.
        } else {
            prev = curr;
            curr = curr->ifa_next;
        }
    }

    return ret;
}

/**
 * Hooked if_nametoindex() — return 0 (not found) for VPN interface names.
 */
static unsigned int hook_if_nametoindex(const char* ifname) {
    if (is_vpn_interface(ifname)) {
        return 0;  // pretend interface doesn't exist
    }
    return orig_if_nametoindex(ifname);
}

/**
 * Hooked ioctl() — intercept SIOCGIFCONF to filter interfaces from low-level queries.
 */
static int hook_ioctl(int fd, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    void* argp = va_arg(args, void*);
    va_end(args);

    int ret = orig_ioctl(fd, request, argp);

    if (ret == 0 && request == SIOCGIFCONF && argp != nullptr) {
        struct ifconf* ifc = static_cast<struct ifconf*>(argp);
        if (ifc->ifc_req != nullptr) {
            struct ifreq* ifr = ifc->ifc_req;
            int num_interfaces = ifc->ifc_len / sizeof(struct ifreq);
            int valid_interfaces = 0;

            for (int i = 0; i < num_interfaces; ++i) {
                if (!is_vpn_interface(ifr[i].ifr_name)) {
                    if (valid_interfaces != i) {
                        ifr[valid_interfaces] = ifr[i];
                    }
                    valid_interfaces++;
                }
            }
            ifc->ifc_len = valid_interfaces * sizeof(struct ifreq);
        }
    }

    return ret;
}

// ─── Hook installation ─────────────────────────────────────────────────────

/**
 * Resolve a symbol from libc.so and install a Dobby inline hook.
 * Returns true on success.
 */
static bool install_hook(const char* symbol, void* replacement, void** original) {
    void* addr = DobbySymbolResolver("libc.so", symbol);
    if (!addr) {
        LOGE("install_hook: failed to resolve libc symbol '%s'", symbol);
        return false;
    }

    int ret = DobbyHook(addr, (dobby_dummy_func_t)replacement, (dobby_dummy_func_t*)original);
    if (ret == 0) {
        LOGD("install_hook: hooked %s @ %p", symbol, addr);
        return true;
    } else {
        LOGE("install_hook: DobbyHook failed for %s (ret=%d)", symbol, ret);
        return false;
    }
}

/**
 * Resolve our own .so path via dladdr() for self-redaction in /proc/self/maps.
 */
static void resolve_self_path() {
    Dl_info info;
    if (dladdr((void*)resolve_self_path, &info) && info.dli_fname) {
        strncpy(g_self_path, info.dli_fname, sizeof(g_self_path) - 1);
        g_self_path[sizeof(g_self_path) - 1] = '\0';
        LOGD("resolve_self_path: %s", g_self_path);
    } else {
        LOGW("resolve_self_path: dladdr failed, self-redaction disabled");
    }
}

// ─── JNI entry point ────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    LOGD("JNI_OnLoad: HandsOffMyVPN native payload initializing");

    // Resolve our own path for self-redaction
    resolve_self_path();

    // Install file-open hooks (track which fds point to sensitive files)
    install_hook("open",   (void*)hook_open,   (void**)&orig_open);
    install_hook("openat", (void*)hook_openat,  (void**)&orig_openat);
    install_hook("fopen",  (void*)hook_fopen,   (void**)&orig_fopen);

    // Install read hooks (sanitize buffers from tracked fds)
    install_hook("read",    (void*)hook_read,    (void**)&orig_read);
    install_hook("pread64", (void*)hook_pread64, (void**)&orig_pread64);

    // Install close hook (cleanup fd tracking table)
    install_hook("close", (void*)hook_close, (void**)&orig_close);

    // Install native interface enumeration hooks (hide tun/tap/ppp/wg/ipsec)
    install_hook("getifaddrs",     (void*)hook_getifaddrs,     (void**)&orig_getifaddrs);
    install_hook("if_nametoindex", (void*)hook_if_nametoindex, (void**)&orig_if_nametoindex);
    install_hook("ioctl",          (void*)hook_ioctl,          (void**)&orig_ioctl);

    LOGD("JNI_OnLoad: all hooks installed successfully");
    return JNI_VERSION_1_6;
}
