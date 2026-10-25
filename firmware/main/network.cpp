#include "network.h"
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <strings.h>
#include "esp_app_desc.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_timer.h"
#include "esp_wifi.h"

namespace network {
static std::atomic<bool> online{false};
static std::atomic<bool> synchronized{false};
static bool started = false;
static uint32_t revision = 0;
static int64_t next_connect = 0;

static void event(void *, esp_event_base_t base, int32_t id, void *) {
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) { online = true; esp_netif_sntp_start(); }
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) online = false;
}
esp_err_t init() {
    auto result = esp_netif_init(); if (result != ESP_OK) return result;
    result = esp_event_loop_create_default(); if (result != ESP_OK) return result;
    if (!esp_netif_create_default_wifi_sta()) return ESP_ERR_NO_MEM;
    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT();
    result = esp_wifi_init(&config); if (result != ESP_OK) return result;
    result = esp_wifi_set_storage(WIFI_STORAGE_RAM); if (result != ESP_OK) return result;
    result = esp_wifi_set_mode(WIFI_MODE_STA); if (result != ESP_OK) return result;
    result = esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, event, nullptr); if (result != ESP_OK) return result;
    result = esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, event, nullptr); if (result != ESP_OK) return result;
    esp_sntp_config_t time_config = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    time_config.start = false;
    time_config.sync_cb = [](struct timeval *) { synchronized = true; };
    return esp_netif_sntp_init(&time_config);
}
void tick() {
    config::Wifi settings;
    if (config::read_wifi(settings) != ESP_OK || settings.ssid.empty()) return;
    if (settings.revision != revision) {
        if (started) { esp_wifi_stop(); started = false; online = false; }
        wifi_config_t wifi = {};
        memcpy(wifi.sta.ssid, settings.ssid.data(), settings.ssid.size());
        memcpy(wifi.sta.password, settings.password.data(), settings.password.size());
        wifi.sta.threshold.authmode = settings.password.empty() ? WIFI_AUTH_OPEN : WIFI_AUTH_WPA2_PSK;
        if (esp_wifi_set_config(WIFI_IF_STA, &wifi) != ESP_OK || esp_wifi_start() != ESP_OK) return;
        started = true; revision = settings.revision; next_connect = 0;
    }
    const int64_t now = esp_timer_get_time();
    if (!online && now >= next_connect) { esp_wifi_connect(); next_connect = now + 10000000; }
}
bool connected() { return online; }
// Certificates are checked against the clock, so no request goes out before SNTP.
bool time_ready() { return synchronized && time(nullptr) >= 1700000000; }
int rssi() {
    wifi_ap_record_t ap = {};
    return online && esp_wifi_sta_get_ap_info(&ap) == ESP_OK ? ap.rssi : 0;
}

static esp_err_t collect(esp_http_client_event_t *event) {
    auto *into = static_cast<Connection::Collector *>(event->user_data);
    if (event->event_id == HTTP_EVENT_ON_HEADER && event->header_key && event->header_value) {
        if (!strcasecmp(event->header_key, "ETag") && strlen(event->header_value) <= 64) into->etag = event->header_value;
        if (!strcasecmp(event->header_key, "Retry-After")) into->retry_after = atoi(event->header_value);
    } else if (event->event_id == HTTP_EVENT_ON_DATA && event->data_len > 0) {
        if (into->body.size() + event->data_len > into->max) into->overflow = true;
        else into->body.append(static_cast<const char *>(event->data), event->data_len);
    }
    return ESP_OK;
}

void Connection::reset() {
    if (client) { esp_http_client_cleanup(client); client = nullptr; }
    extra.clear();
}

Reply Connection::request(const config::Server &server, esp_http_client_method_t method, const char *path,
                          const Headers &headers, const std::string &body, size_t max_body) {
    Reply result;
    if (!connected() || !time_ready() || !config::valid_server(server)) return result;
    if (client && (base != server.url || ca != server.ca_pem)) reset();
    url = server.url + path;
    if (!client) {
        base = server.url; ca = server.ca_pem;  // the client keeps pointing at these strings
        esp_http_client_config_t options = {};
        options.url = url.c_str();
        options.cert_pem = ca.c_str();
        options.timeout_ms = timeout;
        options.keep_alive_enable = true;
        options.disable_auto_redirect = true;
        options.buffer_size = 2048;
        options.buffer_size_tx = 1024;
        options.event_handler = collect;
        options.user_data = &collector;
        client = esp_http_client_init(&options);
        if (!client) return result;
        const auto agent = std::string("assistant-display/") + esp_app_get_description()->version;
        esp_http_client_set_header(client, "User-Agent", agent.c_str());
    }
    esp_http_client_set_url(client, url.c_str());
    esp_http_client_set_method(client, method);
    for (const auto *name : extra) esp_http_client_delete_header(client, name);
    extra.clear();
    for (const auto &[name, value] : headers) {
        esp_http_client_set_header(client, name, value.c_str());
        extra.push_back(name);
    }
    if (method == HTTP_METHOD_POST) {
        esp_http_client_set_header(client, "Content-Type", "application/json");
        extra.push_back("Content-Type");
        esp_http_client_set_post_field(client, body.data(), body.size());
    } else {
        esp_http_client_set_post_field(client, nullptr, 0);
    }
    collector = Collector{};
    collector.max = max_body;
    collector.body.reserve(max_body);
    const auto status = esp_http_client_perform(client);
    if (status != ESP_OK) {
        reset();  // a broken connection starts over on the next request
        return result;
    }
    result.status = esp_http_client_get_status_code(client);
    result.oversized = collector.overflow;
    result.transport_ok = !collector.overflow && result.status > 0;
    result.etag = std::move(collector.etag);
    result.retry_after = collector.retry_after;
    result.body = std::move(collector.body);
    if (result.oversized) reset();
    return result;
}
}  // namespace network
