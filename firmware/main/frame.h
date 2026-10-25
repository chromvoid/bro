#pragma once
// Server-drawn frame (docs/esp-display-spec.md §5.1): 16-byte header, then a packed 1-bit image.
// Portable: no ESP-IDF headers, so firmware/test builds it on the host.
#include <cstddef>
#include <cstdint>

namespace frame {
constexpr uint16_t width = 400;
constexpr uint16_t height = 300;
constexpr size_t header_size = 16;
constexpr size_t image_size = (width + 7) / 8 * height;
constexpr size_t total_size = header_size + image_size;
constexpr uint8_t full_refresh = 1;

struct Header {
    uint8_t flags = 0;
    uint16_t stale_after = 180;  // seconds without contact before the «нет связи» badge
};

enum class Result { ok, too_short, unsupported, corrupted };

uint32_t crc32(const uint8_t *data, size_t length);
// Validates a whole response body; on success the image starts at data + header_size.
Result parse(const uint8_t *data, size_t length, Header &out);
}  // namespace frame
