#include "frame.h"

namespace frame {
static uint16_t u16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }
static uint32_t u32(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 | static_cast<uint32_t>(p[2]) << 16 |
           static_cast<uint32_t>(p[3]) << 24;
}

// CRC-32/ISO-HDLC, the one zlib.crc32 computes on the server.
uint32_t crc32(const uint8_t *data, size_t length) {
    static uint32_t table[256];
    static bool ready = false;
    if (!ready) {
        for (uint32_t n = 0; n < 256; ++n) {
            uint32_t c = n;
            for (int k = 0; k < 8; ++k) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[n] = c;
        }
        ready = true;
    }
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < length; ++i) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

Result parse(const uint8_t *data, size_t length, Header &out) {
    if (!data || length < header_size) return Result::too_short;
    if (data[0] != 'A' || data[1] != 'D' || data[2] != 'F' || data[3] != '1' || u16(data + 4) != width ||
        u16(data + 6) != height || data[8] != 1 || length != total_size)
        return Result::unsupported;
    if (crc32(data + header_size, image_size) != u32(data + 12)) return Result::corrupted;
    out.flags = data[9];
    out.stale_after = u16(data + 10);
    return Result::ok;
}
}  // namespace frame
