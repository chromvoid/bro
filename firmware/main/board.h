#pragma once

#include "esp_err.h"
#include "u8g2_st7305.h"

namespace board {
constexpr int width = 400;
constexpr int height = 300;
esp_err_t init_display(u8g2_st7305_t *display);
esp_err_t init_key();
bool key_pressed();
}  // namespace board
