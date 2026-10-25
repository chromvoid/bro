#pragma once
// The screen as a client of the assistant (docs/esp-display-spec.md §4–5): sign-in by a shown
// code, frames by long-poll with ETag, button presses answered with the next frame.
#include <cstdint>
#include <string>

namespace display {
enum class Phase { setup, connecting, pairing, loading, showing };

struct View {
    Phase phase = Phase::setup;
    std::string code;          // pairing code to show, «4K7Q-2MXA»
    std::string problem;       // short reason while there is no frame
    bool has_frame = false;
    uint32_t revision = 0;     // grows whenever anything shown changes
    int64_t last_contact = 0;  // esp_timer time of the last answer from the server
    uint16_t stale_after = 180;
};

void start();
View view();
// Copies the current image (frame::image_size bytes); false when there is none.
bool copy_image(uint8_t *out);
void press(bool long_press);
void forget_session();  // USB «unpair»: sign in again with a new code
const char *boot_id();
}  // namespace display
