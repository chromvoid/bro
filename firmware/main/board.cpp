#include "board.h"
#include "driver/gpio.h"

namespace board {
// Waveshare 10_FactoryProgram/button_bsp.c: programmable GP18 button, active low.
// GPIO0 remains the ROM boot button; power controls are left to the board.
static constexpr gpio_num_t key_pin = GPIO_NUM_18;

esp_err_t init_display(u8g2_st7305_t *display) {
    auto config = u8g2_st7305_default_config();
    config.mosi_io = GPIO_NUM_12;
    config.sclk_io = GPIO_NUM_11;
    config.dc_io = GPIO_NUM_5;
    config.cs_io = GPIO_NUM_40;
    config.reset_io = GPIO_NUM_41;
    config.rotation = U8G2_R1;
    config.prefer_psram = true;
    return u8g2_st7305_init(display, &config);
}

esp_err_t init_key() {
    gpio_config_t config = {};
    config.pin_bit_mask = 1ULL << key_pin;
    config.mode = GPIO_MODE_INPUT;
    config.pull_up_en = GPIO_PULLUP_ENABLE;
    config.pull_down_en = GPIO_PULLDOWN_DISABLE;
    config.intr_type = GPIO_INTR_DISABLE;
    return gpio_config(&config);
}

bool key_pressed() { return gpio_get_level(key_pin) == 0; }
}  // namespace board
