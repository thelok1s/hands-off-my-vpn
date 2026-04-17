/**
 * sanitizer.h — Public API for in-place buffer sanitization.
 *
 * Each function accepts a raw char* buffer and a pointer to the byte count.
 * It scans the buffer line-by-line, removes "dirty" lines by memmove-ing the
 * remaining data left, and adjusts *bytes_read accordingly.
 *
 * All operations are zero-allocation (no heap), operating entirely on the
 * caller-provided buffer.  This is safe because we only ever shrink the data.
 */

#pragma once

#include <sys/types.h>  // ssize_t

/**
 * /proc/self/maps — remove lines containing Xposed/Magisk/Frida/module artifacts.
 * @param buf          The read buffer (mutable, will be modified in-place).
 * @param bytes_read   Pointer to bytes read; will be decreased for removed lines.
 * @param self_path    Absolute path to our own .so (for self-redaction). May be null.
 */
void sanitize_maps(char* buf, ssize_t* bytes_read, const char* self_path);

/**
 * /proc/net/tcp (or tcp6) — remove lines with Frida default port 27042/27043.
 * @param buf          The read buffer.
 * @param bytes_read   Pointer to bytes read; adjusted in-place.
 */
void sanitize_tcp(char* buf, ssize_t* bytes_read);

/**
 * /proc/self/status — rewrite TracerPid to 0 (hide debugger attachment).
 * @param buf          The read buffer.
 * @param bytes_read   Pointer to bytes read; may change if padding is needed.
 */
void sanitize_status(char* buf, ssize_t* bytes_read);

/**
 * /proc/self/mountinfo or /proc/self/mounts — remove Magisk/root mount entries.
 * @param buf          The read buffer.
 * @param bytes_read   Pointer to bytes read; adjusted in-place.
 */
void sanitize_mounts(char* buf, ssize_t* bytes_read);

/**
 * /proc/net/dev or /proc/net/if_inet6 — remove lines with VPN interface names.
 * @param buf          The read buffer.
 * @param bytes_read   Pointer to bytes read; adjusted in-place.
 */
void sanitize_net_dev(char* buf, ssize_t* bytes_read);
