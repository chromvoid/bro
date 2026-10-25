// Host check of firmware/main/frame.cpp: reads frame files, prints one verdict per file.
// Built and run by tests/test_firmware_frame.py against frames the server encoded.
#include "frame.h"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

int main(int argc, char **argv) {
    for (int i = 1; i < argc; ++i) {
        std::ifstream file(argv[i], std::ios::binary);
        std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        frame::Header header;
        switch (frame::parse(data.data(), data.size(), header)) {
            case frame::Result::ok: std::printf("ok %u %u\n", header.flags, header.stale_after); break;
            case frame::Result::too_short: std::printf("too_short\n"); break;
            case frame::Result::unsupported: std::printf("unsupported\n"); break;
            case frame::Result::corrupted: std::printf("corrupted\n"); break;
        }
    }
    return 0;
}
