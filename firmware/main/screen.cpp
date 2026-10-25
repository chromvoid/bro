#include "screen.h"
#include "board.h"
#include "display.h"
#include "frame.h"
#include "network.h"
#include "provision_usb.h"
#include <climits>
#include <string>
#include "esp_app_desc.h"
#include "esp_timer.h"

// Everything on a normal screen comes from the server. The firmware draws only what it must
// know without the server: setup, connection, the pairing code and the «нет связи» badge.
namespace screen {
static u8g2_st7305_t display;
static uint8_t pixels[frame::image_size];
static uint32_t drawn_revision = UINT32_MAX;
static int drawn_badge = INT_MIN;
static bool drawn_settings = false;

static u8g2_t *canvas() { return u8g2_st7305_get_u8g2(&display); }
static const uint8_t *small = u8g2_font_6x13_t_cyrillic;
static const uint8_t *title = u8g2_font_10x20_t_cyrillic;

static void centered(int baseline, const std::string &text, const uint8_t *font) {
    u8g2_SetFont(canvas(), font);
    const int width = u8g2_GetUTF8Width(canvas(), text.c_str());
    u8g2_DrawUTF8(canvas(), (board::width - width) / 2, baseline, text.c_str());
}

static void service(const display::View &view) {
    const auto version = std::string("Прошивка ") + esp_app_get_description()->version;
    switch (view.phase) {
        case display::Phase::setup:
            centered(70, "Экран ассистента", title);
            centered(120, "Нужна настройка по USB:", small);
            centered(140, "адрес сервера и сеть Wi-Fi.", small);
            centered(175, "Подключите экран к компьютеру", small);
            centered(195, "и запустите tools/display_usb.py", small);
            break;
        case display::Phase::connecting:
            centered(120, "Подключение", title);
            centered(160, view.problem.empty() ? "Подождите..." : view.problem, small);
            break;
        case display::Phase::pairing:
            centered(60, "Подключите экран", title);
            if (view.code.empty()) centered(130, "Получаем код...", small);
            else centered(140, view.code, u8g2_font_logisoso32_tr);
            centered(185, "В приложении откройте", small);
            centered(205, "Настройки > Устройства > Добавить экран", small);
            centered(225, "и введите этот код.", small);
            break;
        case display::Phase::loading:
        case display::Phase::showing:
            centered(130, "Загрузка...", title);
            if (!view.problem.empty()) centered(165, view.problem, small);
            break;
    }
    u8g2_SetFont(canvas(), small);
    u8g2_DrawUTF8(canvas(), 8, board::height - 8, version.c_str());
}

// Minutes since the server last answered, once that is longer than the frame allows; -1 otherwise.
static int stale_minutes(const display::View &view) {
    if (view.phase != display::Phase::showing || !view.has_frame || !view.last_contact) return -1;
    const int64_t seconds = (esp_timer_get_time() - view.last_contact) / 1000000;
    return seconds > view.stale_after ? static_cast<int>(seconds / 60) : -1;
}

static void badge(int minutes) {
    const auto text = minutes > 0 ? "нет связи " + std::to_string(minutes) + " мин" : std::string("нет связи");
    auto *u = canvas();
    u8g2_SetFont(u, small);
    const int width = u8g2_GetUTF8Width(u, text.c_str()) + 10;
    u8g2_SetDrawColor(u, 1);
    u8g2_DrawBox(u, board::width - width - 4, board::height - 22, width, 18);
    u8g2_SetDrawColor(u, 0);
    u8g2_DrawUTF8(u, board::width - width + 1, board::height - 9, text.c_str());
    u8g2_SetDrawColor(u, 1);
}

static void settings_strip() {
    auto *u = canvas();
    u8g2_SetDrawColor(u, 1);
    u8g2_DrawBox(u, 0, 0, board::width, 18);
    u8g2_SetDrawColor(u, 0);
    centered(13, "USB: настройка открыта", small);
    u8g2_SetDrawColor(u, 1);
}

esp_err_t init() {
    const auto result = board::init_display(&display);
    if (result == ESP_OK) u8g2_SetFontMode(canvas(), 1);
    return result;
}

void draw() {
    const auto view = display::view();
    const int minutes = stale_minutes(view);
    const bool settings = provision::settings_open();
    if (view.revision == drawn_revision && minutes == drawn_badge && settings == drawn_settings) return;
    auto *u = canvas();
    u8g2_ClearBuffer(u);
    u8g2_SetDrawColor(u, 1);
    if (view.phase == display::Phase::showing && display::copy_image(pixels)) {
        u8g2_DrawBitmap(u, 0, 0, (board::width + 7) / 8, board::height, pixels);
        if (minutes >= 0) badge(minutes);
    } else {
        service(view);
    }
    if (settings) settings_strip();
    u8g2_SendBuffer(u);
    drawn_revision = view.revision; drawn_badge = minutes; drawn_settings = settings;
}
}  // namespace screen
