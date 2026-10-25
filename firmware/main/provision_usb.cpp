#include "provision_usb.h"
#include "config_store.h"
#include "display.h"
#include "json_util.h"
#include "network.h"
#include <atomic>
#include <cctype>
#include "driver/usb_serial_jtag.h"
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// Line-delimited JSON over the USB serial port (tools/display_usb.py). Nothing secret is ever
// sent back: status reports whether Wi-Fi and a session exist, not their values.
namespace provision {
static std::atomic<int64_t> open_until{0};
static std::atomic<bool> initial_setup{true};
void open_settings() { open_until = esp_timer_get_time() + 300000000; }
bool settings_open() { return initial_setup || esp_timer_get_time() < open_until; }

static void send(const cJSON *response) {
    const auto raw = json::dump(response) + "\n";
    usb_serial_jtag_write_bytes(raw.data(), raw.size(), pdMS_TO_TICKS(1000));
}

static const char *phase_name(display::Phase phase) {
    switch (phase) {
        case display::Phase::setup: return "setup";
        case display::Phase::connecting: return "connecting";
        case display::Phase::pairing: return "pairing";
        case display::Phase::loading: return "loading";
        case display::Phase::showing: return "showing";
    }
    return "unknown";
}

static void status(cJSON *response) {
    cJSON_AddBoolToObject(response, "ok", true);
    auto *status = cJSON_AddObjectToObject(response, "status");
    json::add(status, "mode", "display");
    json::add(status, "firmware", esp_app_get_description()->version);
    cJSON_AddBoolToObject(status, "settings_open", settings_open());
    config::Wifi wifi; config::Server server; config::Session session;
    cJSON_AddBoolToObject(status, "wifi_configured", config::read_wifi(wifi) == ESP_OK && !wifi.ssid.empty());
    cJSON_AddBoolToObject(status, "wifi_connected", network::connected());
    cJSON_AddBoolToObject(status, "time_ready", network::time_ready());
    json::add(status, "server", config::read_server(server) == ESP_OK ? server.url : "");
    const bool paired = config::read_session(session) == ESP_OK && !session.token.empty();
    cJSON_AddBoolToObject(status, "paired", paired);
    json::add(status, "device_id", paired ? session.device_id : "");
    const auto view = display::view();
    json::add(status, "phase", phase_name(view.phase));
    json::add(status, "pairing_code", view.code);
    json::add(status, "boot", display::boot_id());
    cJSON_AddNumberToObject(status, "uptime_seconds", esp_timer_get_time() / 1000000);
    cJSON_AddNumberToObject(status, "rssi", network::rssi());
    cJSON_AddNumberToObject(status, "free_internal_bytes", heap_caps_get_free_size(MALLOC_CAP_INTERNAL));
    cJSON_AddNumberToObject(status, "free_psram_bytes", heap_caps_get_free_size(MALLOC_CAP_SPIRAM));
}

static void process(const char *line) {
    auto request = json::parse(line);
    auto response = json::object();
    cJSON_AddNumberToObject(response.get(), "version", 1);
    std::string id, op;
    bool valid_id = request && json::text(request.get(), "request_id", id, 40);
    for (unsigned char c : id) if (!isalnum(c) && c != '-' && c != '_' && c != '.') valid_id = false;
    json::add(response.get(), "request_id", valid_id ? id : "");
    const char *error = nullptr;
    if (!request || !valid_id || json::number(request.get(), "version") != 1 || !json::text(request.get(), "op", op, 32)) error = "invalid_request";
    else if (op == "status") { status(response.get()); send(response.get()); return; }
    else if (!settings_open()) error = "settings_closed";
    else {
        esp_err_t result = ESP_ERR_INVALID_ARG;
        if (op == "set_wifi") {
            config::Wifi wifi;
            if (json::text(request.get(), "ssid", wifi.ssid, 32) && json::text(request.get(), "password", wifi.password, 63, false)) result = config::put_wifi(wifi);
        } else if (op == "set_server") {
            config::Server server;
            if (json::text(request.get(), "url", server.url, 200) && json::text(request.get(), "ca_pem", server.ca_pem, 4096)) result = config::put_server(server);
        } else if (op == "unpair") {
            display::forget_session();
            result = ESP_OK;
        }
        if (result != ESP_OK) error = result == ESP_ERR_INVALID_ARG ? "invalid_settings" : "storage_error";
        else {
            cJSON_AddBoolToObject(response.get(), "ok", true);
            if (config::complete()) initial_setup = false;
        }
    }
    if (error) { cJSON_AddBoolToObject(response.get(), "ok", false); json::add(response.get(), "error", error); }
    send(response.get());
}

static void task(void *) {
    std::string line; line.reserve(8192);
    bool discarded = false; int64_t last_byte = 0;
    while (true) {
        char input[256];
        const int count = usb_serial_jtag_read_bytes(input, sizeof(input), pdMS_TO_TICKS(100));
        const auto now = esp_timer_get_time();
        if ((!line.empty() || discarded) && last_byte && now - last_byte > 5000000) { line.clear(); discarded = false; }
        for (int i = 0; i < count; ++i) {
            last_byte = now;
            if (input[i] == '\n') {
                if (discarded) {
                    auto response = json::object(); cJSON_AddNumberToObject(response.get(), "version", 1); json::add(response.get(), "request_id", "");
                    cJSON_AddBoolToObject(response.get(), "ok", false); json::add(response.get(), "error", "message_too_large"); send(response.get());
                } else if (!line.empty()) process(line.c_str());
                line.clear(); discarded = false;
            } else if (input[i] != '\r' && !discarded) {
                if (input[i] == 0 || line.size() >= 8192) { discarded = true; line.clear(); }
                else line += input[i];
            }
        }
    }
}

esp_err_t start() {
    initial_setup = !config::complete();
    usb_serial_jtag_driver_config_t config = {}; config.rx_buffer_size = 4096; config.tx_buffer_size = 4096;
    auto result = usb_serial_jtag_driver_install(&config); if (result != ESP_OK) return result;
    return xTaskCreate(task, "display_usb", 8192, nullptr, 3, nullptr) == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}
}  // namespace provision
