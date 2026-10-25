#include "config_store.h"
#include "json_util.h"
#include <cstring>
#include <climits>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs.h"
#include "nvs_flash.h"

namespace config {
static constexpr const char *space = "quota_cfg";
static StaticSemaphore_t mutex_storage;
static SemaphoreHandle_t mutex;
struct Lock { Lock() { xSemaphoreTake(mutex, portMAX_DELAY); } ~Lock() { xSemaphoreGive(mutex); } };

static esp_err_t read_blob(const char *key, std::string &out) {
    nvs_handle_t handle;
    esp_err_t result = nvs_open(space, NVS_READONLY, &handle);
    if (result != ESP_OK) return result;
    size_t size = 0;
    result = nvs_get_blob(handle, key, nullptr, &size);
    if (result == ESP_OK && size > 32768) result = ESP_ERR_INVALID_SIZE;
    if (result == ESP_OK) { out.resize(size); result = nvs_get_blob(handle, key, out.data(), &size); }
    nvs_close(handle);
    return result;
}
static esp_err_t write_blob(const char *key, const std::string &data) {
    if (data.empty() || data.size() > 32768) return ESP_ERR_INVALID_SIZE;
    nvs_handle_t handle;
    esp_err_t result = nvs_open(space, NVS_READWRITE, &handle);
    if (result != ESP_OK) return result;
    result = nvs_set_blob(handle, key, data.data(), data.size());
    if (result == ESP_OK) result = nvs_commit(handle);
    nvs_close(handle);
    return result;
}
static esp_err_t erase(const char *key) {
    nvs_handle_t handle;
    esp_err_t result = nvs_open(space, NVS_READWRITE, &handle);
    if (result != ESP_OK) return result;
    result = nvs_erase_key(handle, key);
    if (result == ESP_ERR_NVS_NOT_FOUND) result = ESP_OK;
    if (result == ESP_OK) result = nvs_commit(handle);
    nvs_close(handle);
    return result;
}
// Next revision of a versioned blob; 0 when the stored one is unreadable.
static uint32_t next_revision(const char *key) {
    std::string raw;
    if (read_blob(key, raw) != ESP_OK) return 1;
    auto previous = json::parse(raw.c_str());
    if (!previous || json::number(previous.get(), "version") != 1) return 0;
    const double revision = json::number(previous.get(), "revision");
    return revision >= UINT32_MAX ? 0 : static_cast<uint32_t>(revision) + 1;
}

esp_err_t init() {
    mutex = xSemaphoreCreateMutexStatic(&mutex_storage);
    auto result = nvs_flash_init();
    if (result != ESP_OK) return result;
    // The voice firmware's relay key and quota cache mean nothing to this server; drop them.
    erase("relay");
    erase("cache");
    return ESP_OK;
}

esp_err_t read_wifi(Wifi &out) {
    Lock lock; out = Wifi{}; std::string raw;
    auto result = read_blob("wifi", raw);
    if (result == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (result != ESP_OK) return result;
    auto root = json::parse(raw.c_str());
    if (!root || json::number(root.get(), "version") != 1 ||
        !json::text(root.get(), "ssid", out.ssid, 32) || !json::text(root.get(), "password", out.password, 63, false)) return ESP_ERR_INVALID_STATE;
    out.revision = json::number(root.get(), "revision"); return ESP_OK;
}
esp_err_t put_wifi(const Wifi &value) {
    if (value.ssid.empty() || value.ssid.size() > 32 || value.password.size() > 63 || (!value.password.empty() && value.password.size() < 8)) return ESP_ERR_INVALID_ARG;
    Lock lock;
    const auto revision = next_revision("wifi");
    if (!revision) return ESP_ERR_INVALID_STATE;
    auto root = json::object(); cJSON_AddNumberToObject(root.get(), "version", 1); cJSON_AddNumberToObject(root.get(), "revision", revision);
    json::add(root.get(), "ssid", value.ssid); json::add(root.get(), "password", value.password);
    return write_blob("wifi", json::dump(root.get()));
}

bool valid_server(const Server &value) {
    const auto &url = value.url;
    if (url.size() < 12 || url.size() > 200 || url.rfind("https://", 0) != 0 || url.back() == '/' ||
        url.find('/', 8) != std::string::npos || value.ca_pem.size() > 4096 ||
        value.ca_pem.rfind("-----BEGIN CERTIFICATE-----", 0) != 0) return false;
    for (unsigned char c : url) if (c <= 32 || c >= 127 || c == '@' || c == '?' || c == '#' || c == '\\') return false;
    return true;
}
esp_err_t read_server(Server &out) {
    Lock lock; out = {}; std::string raw;
    auto result = read_blob("server", raw);
    if (result == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (result != ESP_OK) return result;
    auto root = json::parse(raw.c_str());
    if (!root || json::number(root.get(), "version") != 1 || !json::text(root.get(), "url", out.url, 200) ||
        !json::text(root.get(), "ca_pem", out.ca_pem, 4096) || !valid_server(out)) return ESP_ERR_INVALID_STATE;
    out.revision = json::number(root.get(), "revision"); return ESP_OK;
}
esp_err_t put_server(const Server &value) {
    if (!valid_server(value)) return ESP_ERR_INVALID_ARG;
    Server current;
    const bool moved = read_server(current) != ESP_OK || current.url != value.url;
    {
        Lock lock;
        const auto revision = next_revision("server");
        if (!revision) return ESP_ERR_INVALID_STATE;
        auto root = json::object(); cJSON_AddNumberToObject(root.get(), "version", 1);
        cJSON_AddNumberToObject(root.get(), "revision", revision);
        json::add(root.get(), "url", value.url); json::add(root.get(), "ca_pem", value.ca_pem);
        auto result = write_blob("server", json::dump(root.get()));
        if (result != ESP_OK) return result;
    }
    // A session belongs to one server; another address starts a new pairing.
    return moved ? clear_session() : ESP_OK;
}

esp_err_t read_session(Session &out) {
    Lock lock; out = {}; std::string raw;
    auto result = read_blob("session", raw);
    if (result == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (result != ESP_OK) return result;
    auto root = json::parse(raw.c_str());
    if (!root || json::number(root.get(), "version") != 1 || !json::text(root.get(), "token", out.token, 128) ||
        !json::text(root.get(), "device_id", out.device_id, 64)) { out = {}; return ESP_ERR_INVALID_STATE; }
    return ESP_OK;
}
esp_err_t put_session(const Session &value) {
    if (value.token.empty() || value.token.size() > 128 || value.device_id.size() > 64) return ESP_ERR_INVALID_ARG;
    for (unsigned char c : value.token) if (c < 33 || c > 126) return ESP_ERR_INVALID_ARG;
    Lock lock;
    auto root = json::object(); cJSON_AddNumberToObject(root.get(), "version", 1);
    json::add(root.get(), "token", value.token); json::add(root.get(), "device_id", value.device_id);
    return write_blob("session", json::dump(root.get()));
}
esp_err_t clear_session() { Lock lock; return erase("session"); }

bool complete() {
    Wifi wifi; Server server;
    return read_wifi(wifi) == ESP_OK && !wifi.ssid.empty() && read_server(server) == ESP_OK && valid_server(server);
}
}  // namespace config
