/**
 * sanitizer.cpp — In-place buffer sanitization for /proc pseudo-files and netlink messages.
 *
 * All line-based functions: walk the buffer, check each line, memmove dirty lines out.
 * Zero-allocation — no malloc/new. Buffer only shrinks, never grows.
 */

#include "sanitizer.h"
#include "fd_tracker.h"

#include <string.h>
#include <ctype.h>
#include <stdlib.h>
#include <net/if.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>

// ─── Helpers ────────────────────────────────────────────────────────────────

static bool line_contains_ci(const char* line, size_t len, const char* needle) {
    size_t nlen = strlen(needle);
    if (nlen > len) return false;
    for (size_t i = 0; i <= len - nlen; ++i) {
        bool match = true;
        for (size_t j = 0; j < nlen; ++j) {
            if (tolower((unsigned char)line[i + j]) != tolower((unsigned char)needle[j])) {
                match = false;
                break;
            }
        }
        if (match) return true;
    }
    return false;
}

static char* remove_line(char* buf, char* line_start, char* line_end, char* buf_end) {
    size_t tail = buf_end - line_end;
    if (tail > 0) memmove(line_start, line_end, tail);
    return line_start + tail;
}

// ─── /proc/self/maps ────────────────────────────────────────────────────────

static const char* MAPS_BLOCKLIST[] = {
    "frida", "lsposed", "edxposed", "xposed", "magisk",
    "riru", "zygisk", "dobby", "libhomvpn", "substrate",
    "cydia", "/data/adb/modules", "libgadget",
};
static const int MAPS_BLOCKLIST_COUNT = (int)(sizeof(MAPS_BLOCKLIST) / sizeof(MAPS_BLOCKLIST[0]));

void sanitize_maps(char* buf, ssize_t* bytes_read, const char* self_path) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;
        size_t line_len = line_end - pos;
        bool dirty = false;

        for (int i = 0; i < MAPS_BLOCKLIST_COUNT && !dirty; ++i)
            dirty = line_contains_ci(pos, line_len, MAPS_BLOCKLIST[i]);

        if (!dirty && self_path && self_path[0])
            dirty = line_contains_ci(pos, line_len, self_path);

        if (dirty)
            buf_end = remove_line(buf, pos, line_end, buf_end);
        else
            pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── /proc/net/tcp ──────────────────────────────────────────────────────────

static const char* FRIDA_PORTS_HEX[] = { ":69B2", ":69b2", ":69B3", ":69b3" };
static const int FRIDA_PORTS_COUNT = (int)(sizeof(FRIDA_PORTS_HEX) / sizeof(FRIDA_PORTS_HEX[0]));

void sanitize_tcp(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos      = buf;
    char* buf_end  = buf + *bytes_read;
    bool first = true;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;
        size_t line_len = line_end - pos;

        if (first) { first = false; pos = line_end; continue; }

        bool dirty = false;
        for (int i = 0; i < FRIDA_PORTS_COUNT && !dirty; ++i)
            dirty = line_contains_ci(pos, line_len, FRIDA_PORTS_HEX[i]);

        if (dirty)
            buf_end = remove_line(buf, pos, line_end, buf_end);
        else
            pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── /proc/self/status ──────────────────────────────────────────────────────

void sanitize_status(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    const char* needle = "TracerPid:\t";
    size_t needle_len  = strlen(needle);
    char* match = (char*)memmem(buf, *bytes_read, needle, needle_len);
    if (!match) return;

    char* val_start = match + needle_len;
    char* buf_end   = buf + *bytes_read;
    if (val_start >= buf_end) return;

    char* val_end = (char*)memchr(val_start, '\n', buf_end - val_start);
    if (!val_end) val_end = buf_end;

    if ((size_t)(val_end - val_start) == 1 && val_start[0] == '0') return;

    val_start[0] = '0';
    for (char* p = val_start + 1; p < val_end; ++p) *p = ' ';
}

// ─── /proc/self/mounts ──────────────────────────────────────────────────────

static const char* MOUNTS_BLOCKLIST[] = {
    "magisk", "/sbin/.magisk", "/data/adb",
    "/system/xbin/su", "/system/bin/su",
    "supolicy", "supersu", "ksu",
};
static const int MOUNTS_BLOCKLIST_COUNT = (int)(sizeof(MOUNTS_BLOCKLIST) / sizeof(MOUNTS_BLOCKLIST[0]));

void sanitize_mounts(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;
        size_t line_len = line_end - pos;
        bool dirty = false;

        for (int i = 0; i < MOUNTS_BLOCKLIST_COUNT && !dirty; ++i)
            dirty = line_contains_ci(pos, line_len, MOUNTS_BLOCKLIST[i]);

        if (dirty)
            buf_end = remove_line(buf, pos, line_end, buf_end);
        else
            pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── /proc/net/dev ──────────────────────────────────────────────────────────

void sanitize_net_dev(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos      = buf;
    char* buf_end  = buf + *bytes_read;
    int line_num   = 0;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;

        if (line_num++ < 2) { pos = line_end; continue; }  // skip 2-line header

        // Format: "  iface_name: rx_bytes ..."
        const char* p = pos;
        while (p < line_end && *p == ' ') p++;
        const char* colon = (const char*)memchr(p, ':', line_end - p);
        if (colon) {
            const char* name_end = colon;
            while (name_end > p && *(name_end - 1) == ' ') name_end--;
            size_t nlen = (size_t)(name_end - p);
            if (nlen > 0 && nlen < IFNAMSIZ) {
                char name[IFNAMSIZ] = {};
                memcpy(name, p, nlen);
                if (is_vpn_interface(name)) {
                    buf_end = remove_line(buf, pos, line_end, buf_end);
                    continue;
                }
            }
        }
        pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── /proc/net/if_inet6 ─────────────────────────────────────────────────────

void sanitize_if_inet6(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;

        // Format: "addr_hex iface_idx prefix scope flags iface_name"
        // Interface name is the last whitespace-delimited field.
        const char* end = line_end;
        while (end > pos && (*(end-1) == '\n' || *(end-1) == '\r' || *(end-1) == ' ')) end--;
        const char* p = end;
        while (p > pos && *(p-1) != ' ') p--;

        size_t nlen = (size_t)(end - p);
        if (nlen > 0 && nlen < IFNAMSIZ) {
            char name[IFNAMSIZ] = {};
            memcpy(name, p, nlen);
            if (is_vpn_interface(name)) {
                buf_end = remove_line(buf, pos, line_end, buf_end);
                continue;
            }
        }
        pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── /proc/net/route ────────────────────────────────────────────────────────

void sanitize_route(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;
    bool first    = true;

    while (pos < buf_end) {
        char* eol      = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;

        if (first) { first = false; pos = line_end; continue; }  // skip header

        // Format: "Iface\tDestination\t..."  — first field is tab-terminated
        const char* tab = (const char*)memchr(pos, '\t', line_end - pos);
        if (tab) {
            size_t nlen = (size_t)(tab - pos);
            if (nlen > 0 && nlen < IFNAMSIZ) {
                char name[IFNAMSIZ] = {};
                memcpy(name, pos, nlen);
                if (is_vpn_interface(name)) {
                    buf_end = remove_line(buf, pos, line_end, buf_end);
                    continue;
                }
            }
        }
        pos = line_end;
    }

    *bytes_read = buf_end - buf;
}

// ─── Netlink NETLINK_ROUTE message filtering ────────────────────────────────

void sanitize_netlink(void* buf, ssize_t len) {
    if (!buf || len <= 0) return;

    struct nlmsghdr* nlh = (struct nlmsghdr*)buf;
    int remaining = (int)len;

    for (; NLMSG_OK(nlh, remaining); nlh = NLMSG_NEXT(nlh, remaining)) {

        if (nlh->nlmsg_type == RTM_NEWLINK) {
            if ((int)nlh->nlmsg_len < (int)NLMSG_SPACE(sizeof(struct ifinfomsg))) continue;
            struct ifinfomsg* ifi = (struct ifinfomsg*)NLMSG_DATA(nlh);
            int rta_rem = (int)IFLA_PAYLOAD(nlh);
            struct rtattr* rta = IFLA_RTA(ifi);
            for (; RTA_OK(rta, rta_rem); rta = RTA_NEXT(rta, rta_rem)) {
                if (rta->rta_type == IFLA_IFNAME) {
                    const char* name = (const char*)RTA_DATA(rta);
                    if (is_vpn_interface(name)) {
                        mark_vpn_iface_idx((unsigned int)ifi->ifi_index);
                        nlh->nlmsg_type = NLMSG_NOOP;
                    }
                    break;
                }
            }

        } else if (nlh->nlmsg_type == RTM_NEWADDR) {
            if ((int)nlh->nlmsg_len < (int)NLMSG_SPACE(sizeof(struct ifaddrmsg))) continue;
            struct ifaddrmsg* ifa = (struct ifaddrmsg*)NLMSG_DATA(nlh);

            if (is_vpn_iface_idx(ifa->ifa_index)) {
                nlh->nlmsg_type = NLMSG_NOOP;
                continue;
            }

            int rta_rem = (int)IFA_PAYLOAD(nlh);
            struct rtattr* rta = IFA_RTA(ifa);
            for (; RTA_OK(rta, rta_rem); rta = RTA_NEXT(rta, rta_rem)) {
                if (rta->rta_type == IFA_LABEL) {
                    const char* name = (const char*)RTA_DATA(rta);
                    if (is_vpn_interface(name)) {
                        mark_vpn_iface_idx(ifa->ifa_index);
                        nlh->nlmsg_type = NLMSG_NOOP;
                    }
                    break;
                }
            }
        }
    }
}
