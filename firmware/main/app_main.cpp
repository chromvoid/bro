#include "board.h"
#include "config_store.h"
#include "display.h"
#include "network.h"
#include "provision_usb.h"
#include "screen.h"
#include <cstdlib>
#include <ctime>
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// Display-only client (docs/esp-display-spec.md). The key: a short press shows the next page,
// 1–3 s returns to the first one, holding 3 s opens USB settings for five minutes.
extern "C" void app_main() {
    setenv("TZ", "UTC0", 1); tzset();
    ESP_ERROR_CHECK(config::init());
    ESP_ERROR_CHECK(board::init_key());
    ESP_ERROR_CHECK(screen::init());
    ESP_ERROR_CHECK(network::init());
    display::start();
    ESP_ERROR_CHECK(provision::start());
    ESP_LOGI("display", "ready: firmware %s, panel 400x300x1", esp_app_get_description()->version);
    bool candidate = board::key_pressed(), stable = candidate, held = false;
    int64_t changed_at = esp_timer_get_time(), pressed_at = changed_at, last_frame = 0;
    while (true) {
        const auto now = esp_timer_get_time();
        const bool pressed = board::key_pressed();
        if (pressed != candidate) { candidate = pressed; changed_at = now; }
        if (candidate != stable && now - changed_at >= 30000) {
            stable = candidate;
            if (stable) { pressed_at = now; held = false; }
            else if (!held) display::press(now - pressed_at >= 1000000);
        }
        if (stable && !held && now - pressed_at >= 3000000) {
            provision::open_settings(); held = true; last_frame = 0;
        }
        if (now - last_frame >= 250000) { screen::draw(); last_frame = now; }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}
