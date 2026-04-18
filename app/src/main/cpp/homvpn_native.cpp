/**
 * homvpn_native.cpp — Native payload for HandsOffMyVPN.
 *
 * Loaded into the target app's process by NativeLoader.kt via System.load().
 * JNI_OnLoad installs Dobby inline hooks on libc functions to sanitize reads
 * from sensitive /proc pseudo-files and filter native network interface
 * enumeration to hide VPN tunnel interfaces.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  open / openat / fopen                                                 │
 * │    └─► classify_path → track_fd(fd, type)                             │
 * │                                                                        │
 * │  read / pread64                                                        │
 * │    ├─► get_fd_type(fd) → sanitize /proc buffer                        │
 * │    └─► is_nl_socket(fd) → sanitize_netlink()                          │
 * │                                                                        │
 * │  close → untrack_fd + untrack_nl_socket                               │
 * │                                                                        │
 * │  getifaddrs → unlink VPN entries from the returned linked list        │
 * │  if_nametoindex → return 0 for VPN interface names                   │
 * │  ioctl(SIOCGIFCONF) → compact out VPN entries                        │
 * │                                                                        │
 * │  socket(AF_NETLINK, …, NETLINK_ROUTE) → track_nl_socket(fd)          │
 * │  recvmsg / recv / recvfrom on netlink fd → sanitize_netlink()         │
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
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>

#include "dobby.h"
#include "fd_tracker.h"
#include "sanitizer.h"

static char g_self_path[512] = {0};

// ─── Original function pointers ─────────────────────────────────────────────

static int     (*orig_open)(const char*, int, ...)        = nullptr;
static int     (*orig_openat)(int, const char*, int, ...) = nullptr;
static FILE*   (*orig_fopen)(const char*, const char*)    = nullptr;
static ssize_t (*orig_read)(int, void*, size_t)           = nullptr;
static ssize_t (*orig_pread64)(int, void*, size_t, off64_t) = nullptr;
static int     (*orig_close)(int)                         = nullptr;

static int          (*orig_getifaddrs)(struct ifaddrs**)         = nullptr;
static unsigned int (*orig_if_nametoindex)(const char*)          = nullptr;
static int          (*orig_ioctl)(int, unsigned long, ...)        = nullptr;

static int     (*orig_socket)(int, int, int)                     = nullptr;
static ssize_t (*orig_recvmsg)(int, struct msghdr*, int)         = nullptr;
static ssize_t (*orig_recv)(int, void*, size_t, int)             = nullptr;
static ssize_t (*orig_recvfrom)(int, void*, size_t, int,
                                struct sockaddr*, socklen_t*)     = nullptr;

// ─── Hook implementations ───────────────────────────────────────────────────

static int hook_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags);
        mode = (mode_t)va_arg(args, int); va_end(args);
    }
    int fd = orig_open(path, flags, mode);
    if (fd >= 0) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) track_fd(fd, type);
    }
    return fd;
}

static int hook_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags);
        mode = (mode_t)va_arg(args, int); va_end(args);
    }
    int fd = orig_openat(dirfd, path, flags, mode);
    if (fd >= 0) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) track_fd(fd, type);
    }
    return fd;
}

static FILE* hook_fopen(const char* path, const char* mode) {
    FILE* fp = orig_fopen(path, mode);
    if (fp) {
        SensitiveFile type = classify_path(path);
        if (type != SensitiveFile::NONE) track_fd(fileno(fp), type);
    }
    return fp;
}

static ssize_t hook_read(int fd, void* buf, size_t count) {
    ssize_t bytes_read = orig_read(fd, buf, count);
    if (bytes_read <= 0) return bytes_read;

    SensitiveFile type = get_fd_type(fd);
    if (type != SensitiveFile::NONE) {
        char* cbuf = static_cast<char*>(buf);
        switch (type) {
            case SensitiveFile::PROC_MAPS:
                sanitize_maps(cbuf, &bytes_read, g_self_path); break;
            case SensitiveFile::PROC_NET_TCP:
                sanitize_tcp(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_STATUS:
                sanitize_status(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_MOUNTS:
                sanitize_mounts(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_DEV:
                sanitize_net_dev(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_IF_INET6:
                sanitize_if_inet6(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_ROUTE:
                sanitize_route(cbuf, &bytes_read); break;
            default: break;
        }
    }

    if (is_nl_socket(fd)) sanitize_netlink(buf, bytes_read);

    return bytes_read;
}

static ssize_t hook_pread64(int fd, void* buf, size_t count, off64_t offset) {
    ssize_t bytes_read = orig_pread64(fd, buf, count, offset);
    if (bytes_read <= 0) return bytes_read;

    SensitiveFile type = get_fd_type(fd);
    if (type != SensitiveFile::NONE) {
        char* cbuf = static_cast<char*>(buf);
        switch (type) {
            case SensitiveFile::PROC_MAPS:
                sanitize_maps(cbuf, &bytes_read, g_self_path); break;
            case SensitiveFile::PROC_NET_TCP:
                sanitize_tcp(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_STATUS:
                sanitize_status(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_MOUNTS:
                sanitize_mounts(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_DEV:
                sanitize_net_dev(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_IF_INET6:
                sanitize_if_inet6(cbuf, &bytes_read); break;
            case SensitiveFile::PROC_NET_ROUTE:
                sanitize_route(cbuf, &bytes_read); break;
            default: break;
        }
    }

    return bytes_read;
}

static int hook_close(int fd) {
    untrack_fd(fd);
    untrack_nl_socket(fd);
    return orig_close(fd);
}

static int hook_getifaddrs(struct ifaddrs** ifap) {
    int ret = orig_getifaddrs(ifap);
    if (ret != 0 || !ifap || !*ifap) return ret;

    struct ifaddrs* prev = nullptr;
    struct ifaddrs* curr = *ifap;
    while (curr) {
        if (is_vpn_interface(curr->ifa_name)) {
            if (prev) prev->ifa_next = curr->ifa_next;
            else      *ifap = curr->ifa_next;
            curr = curr->ifa_next;
        } else {
            prev = curr;
            curr = curr->ifa_next;
        }
    }
    return ret;
}

static unsigned int hook_if_nametoindex(const char* ifname) {
    if (is_vpn_interface(ifname)) return 0;
    return orig_if_nametoindex(ifname);
}

static int hook_ioctl(int fd, unsigned long request, ...) {
    va_list args; va_start(args, request);
    void* argp = va_arg(args, void*); va_end(args);

    int ret = orig_ioctl(fd, request, argp);
    if (ret == 0 && request == SIOCGIFCONF && argp) {
        struct ifconf* ifc = static_cast<struct ifconf*>(argp);
        if (ifc->ifc_req) {
            struct ifreq* ifr = ifc->ifc_req;
            int n = ifc->ifc_len / (int)sizeof(struct ifreq);
            int valid = 0;
            for (int i = 0; i < n; ++i) {
                if (!is_vpn_interface(ifr[i].ifr_name)) {
                    if (valid != i) ifr[valid] = ifr[i];
                    valid++;
                }
            }
            ifc->ifc_len = valid * (int)sizeof(struct ifreq);
        }
    }
    return ret;
}

static int hook_socket(int domain, int type, int protocol) {
    int fd = orig_socket(domain, type, protocol);
    if (fd >= 0 && domain == AF_NETLINK && protocol == NETLINK_ROUTE)
        track_nl_socket(fd);
    return fd;
}

static ssize_t hook_recvmsg(int fd, struct msghdr* msg, int flags) {
    ssize_t ret = orig_recvmsg(fd, msg, flags);
    if (ret > 0 && is_nl_socket(fd) &&
        msg && msg->msg_iovlen >= 1 &&
        msg->msg_iov && msg->msg_iov[0].iov_base) {
        sanitize_netlink(msg->msg_iov[0].iov_base, ret);
    }
    return ret;
}

static ssize_t hook_recv(int fd, void* buf, size_t len, int flags) {
    ssize_t ret = orig_recv(fd, buf, len, flags);
    if (ret > 0 && is_nl_socket(fd)) sanitize_netlink(buf, ret);
    return ret;
}

static ssize_t hook_recvfrom(int fd, void* buf, size_t len, int flags,
                              struct sockaddr* src_addr, socklen_t* addrlen) {
    ssize_t ret = orig_recvfrom(fd, buf, len, flags, src_addr, addrlen);
    if (ret > 0 && is_nl_socket(fd)) sanitize_netlink(buf, ret);
    return ret;
}

// ─── Hook installation ─────────────────────────────────────────────────────

static bool install_hook(const char* symbol, void* replacement, void** original) {
    void* addr = DobbySymbolResolver("libc.so", symbol);
    if (!addr) {
        LOGE("install_hook: failed to resolve '%s'", symbol);
        return false;
    }
    int ret = DobbyHook(addr, (dobby_dummy_func_t)replacement,
                        (dobby_dummy_func_t*)original);
    if (ret == 0) {
        LOGD("install_hook: hooked %s @ %p", symbol, addr);
        return true;
    }
    LOGE("install_hook: DobbyHook failed for %s (ret=%d)", symbol, ret);
    return false;
}

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

    resolve_self_path();

    // /proc file interception
    install_hook("open",    (void*)hook_open,    (void**)&orig_open);
    install_hook("openat",  (void*)hook_openat,  (void**)&orig_openat);
    install_hook("fopen",   (void*)hook_fopen,   (void**)&orig_fopen);
    install_hook("read",    (void*)hook_read,    (void**)&orig_read);
    install_hook("pread64", (void*)hook_pread64, (void**)&orig_pread64);
    install_hook("close",   (void*)hook_close,   (void**)&orig_close);

    // Native interface enumeration
    install_hook("getifaddrs",     (void*)hook_getifaddrs,     (void**)&orig_getifaddrs);
    install_hook("if_nametoindex", (void*)hook_if_nametoindex, (void**)&orig_if_nametoindex);
    install_hook("ioctl",          (void*)hook_ioctl,          (void**)&orig_ioctl);

    // Netlink socket filtering
    install_hook("socket",   (void*)hook_socket,   (void**)&orig_socket);
    install_hook("recvmsg",  (void*)hook_recvmsg,  (void**)&orig_recvmsg);
    install_hook("recv",     (void*)hook_recv,      (void**)&orig_recv);
    install_hook("recvfrom", (void*)hook_recvfrom,  (void**)&orig_recvfrom);

    LOGD("JNI_OnLoad: all hooks installed");
    return JNI_VERSION_1_6;
}
