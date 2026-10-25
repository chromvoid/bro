#ifndef HERZRING_ABI_1_H
#define HERZRING_ABI_1_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Status: 0 OK, 1 invalid argument/handle/token, 2 failed, 3 contained panic.
 * Input byte pointers must be valid and readable for len bytes. Output structs
 * must be writable and aligned. Never pass or dereference a Rust object pointer.
 * Returned data stays valid until buffer_free(token); copy it before freeing.
 * Handles and tokens are checked registry IDs. Double free/close is rejected.
 * dispatch output is UTF-8 JSON effect array, including ordinary error effects.
 */
typedef struct { const uint8_t *data; size_t len; uint64_t token; } HerzRingBuffer;
uint32_t herzring_api_version(void);
int32_t herzring_create(const uint8_t *input, size_t len, uint64_t *out_handle);
int32_t herzring_dispatch(uint64_t handle, const uint8_t *input, size_t len, uint64_t now_ms, HerzRingBuffer *out);
int32_t herzring_destroy(uint64_t handle);
int32_t herzring_buffer_free(uint64_t token);
#ifdef __cplusplus
}
#endif
#endif
