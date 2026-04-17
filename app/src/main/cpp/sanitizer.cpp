/**
 * sanitizer.cpp — In-place buffer sanitization for /proc pseudo-files.
 *
 * All functions use the same pattern:
 *   1. Walk the buffer looking for '\n' line boundaries
 *   2. For each line, check if it matches a blocklist
 *   3. If dirty: memmove() everything after the line left to overwrite it
 *   4. Adjust *bytes_read
 *
 * This is zero-allocation — no malloc/new, no string objects.  The buffer is
 * always shrunk, never grown, so no risk of overrun.
 */

#include "sanitizer.h"
#include "fd_tracker.h"   // LOG macros

#include <string.h>
#include <ctype.h>
#include <stdlib.h>

// ─── Helpers ────────────────────────────────────────────────────────────────

/**
 * Case-insensitive substring search within a bounded region.
 * We can't use strstr() directly because the buffer may not be null-terminated
 * at the line boundary.
 */
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

/**
 * Remove a line from `buf` at [line_start, line_end).
 * Everything from line_end to buf_end is shifted left.
 * Returns the new buf_end after the shift.
 */
static char* remove_line(char* buf, char* line_start, char* line_end, char* buf_end) {
    size_t tail = buf_end - line_end;
    if (tail > 0) {
        memmove(line_start, line_end, tail);
    }
    return line_start + tail;
}

// ─── /proc/self/maps ────────────────────────────────────────────────────────

// Strings that indicate Xposed/Magisk/Frida/root in a maps line.
// All matched case-insensitively.
static const char* MAPS_BLOCKLIST[] = {
    "frida",
    "lsposed",
    "edxposed",
    "xposed",
    "magisk",
    "riru",
    "zygisk",
    "dobby",
    "libhomvpn",
    "substrate",
    "cydia",
    "/data/adb/modules",
    "libgadget",
};
static const int MAPS_BLOCKLIST_COUNT = sizeof(MAPS_BLOCKLIST) / sizeof(MAPS_BLOCKLIST[0]);

void sanitize_maps(char* buf, ssize_t* bytes_read, const char* self_path) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;
    int removed   = 0;

    while (pos < buf_end) {
        // Find end of current line
        char* eol = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;  // include the newline
        size_t line_len = line_end - pos;

        bool dirty = false;

        // Check against blocklist
        for (int i = 0; i < MAPS_BLOCKLIST_COUNT; ++i) {
            if (line_contains_ci(pos, line_len, MAPS_BLOCKLIST[i])) {
                dirty = true;
                break;
            }
        }

        // Check against our own .so path (self-redaction)
        if (!dirty && self_path && self_path[0] != '\0') {
            if (line_contains_ci(pos, line_len, self_path)) {
                dirty = true;
            }
        }

        if (dirty) {
            buf_end = remove_line(buf, pos, line_end, buf_end);
            removed++;
            // Don't advance pos — the next line was shifted into current position
        } else {
            pos = line_end;
        }
    }

    *bytes_read = buf_end - buf;
    *bytes_read = buf_end - buf;
}

// ─── /proc/net/tcp ──────────────────────────────────────────────────────────

// Frida default ports in hex (as they appear in /proc/net/tcp column 2)
// 27042 = 0x69B2, 27043 = 0x69B3
static const char* FRIDA_PORTS_HEX[] = {
    ":69B2",   // 27042
    ":69b2",
    ":69B3",   // 27043
    ":69b3",
};
static const int FRIDA_PORTS_COUNT = sizeof(FRIDA_PORTS_HEX) / sizeof(FRIDA_PORTS_HEX[0]);

void sanitize_tcp(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;
    int removed   = 0;
    bool first_line = true;  // preserve the header line

    while (pos < buf_end) {
        char* eol = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;
        size_t line_len = line_end - pos;

        // Always keep the header line (first line, starts with whitespace + "sl")
        if (first_line) {
            first_line = false;
            pos = line_end;
            continue;
        }

        bool dirty = false;
        for (int i = 0; i < FRIDA_PORTS_COUNT; ++i) {
            // /proc/net/tcp format: "  sl  local_address  rem_address ..."
            // local_address is "HEX_IP:HEX_PORT" — we search for ":69B2"
            if (line_contains_ci(pos, line_len, FRIDA_PORTS_HEX[i])) {
                dirty = true;
                break;
            }
        }

        if (dirty) {
            buf_end = remove_line(buf, pos, line_end, buf_end);
            removed++;
        } else {
            pos = line_end;
        }
    }

    *bytes_read = buf_end - buf;
    *bytes_read = buf_end - buf;
}

// ─── /proc/self/status ──────────────────────────────────────────────────────

void sanitize_status(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    // Look for "TracerPid:\t" followed by a non-zero value
    const char* needle = "TracerPid:\t";
    size_t needle_len = strlen(needle);

    char* match = (char*)memmem(buf, *bytes_read, needle, needle_len);
    if (!match) return;

    char* val_start = match + needle_len;
    char* buf_end   = buf + *bytes_read;
    if (val_start >= buf_end) return;

    // Find end of the value (newline or end of buffer)
    char* val_end = (char*)memchr(val_start, '\n', buf_end - val_start);
    if (!val_end) val_end = buf_end;

    // Check if the current value is "0"
    size_t val_len = val_end - val_start;
    if (val_len == 1 && val_start[0] == '0') return;  // already clean

    // Overwrite the PID value with "0" and pad with spaces to keep alignment
    val_start[0] = '0';
    for (char* p = val_start + 1; p < val_end; ++p) {
        *p = ' ';  // pad remainder with spaces (preserves line length)
    }
}

// ─── /proc/self/mountinfo & /proc/self/mounts ───────────────────────────────

static const char* MOUNTS_BLOCKLIST[] = {
    "magisk",
    "/sbin/.magisk",
    "/data/adb",
    "/system/xbin/su",
    "/system/bin/su",
    "supolicy",
    "supersu",
    "ksu",
};
static const int MOUNTS_BLOCKLIST_COUNT = sizeof(MOUNTS_BLOCKLIST) / sizeof(MOUNTS_BLOCKLIST[0]);

void sanitize_mounts(char* buf, ssize_t* bytes_read) {
    if (!buf || !bytes_read || *bytes_read <= 0) return;

    char* pos     = buf;
    char* buf_end = buf + *bytes_read;
    int removed   = 0;

    while (pos < buf_end) {
        char* eol = (char*)memchr(pos, '\n', buf_end - pos);
        char* line_end = eol ? eol + 1 : buf_end;
        size_t line_len = line_end - pos;

        bool dirty = false;
        for (int i = 0; i < MOUNTS_BLOCKLIST_COUNT; ++i) {
            if (line_contains_ci(pos, line_len, MOUNTS_BLOCKLIST[i])) {
                dirty = true;
                break;
            }
        }

        if (dirty) {
            buf_end = remove_line(buf, pos, line_end, buf_end);
            removed++;
        } else {
            pos = line_end;
        }
    }

    *bytes_read = buf_end - buf;
    *bytes_read = buf_end - buf;
}
