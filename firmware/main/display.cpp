#include "display.h"
#include "config_store.h"
#include "frame.h"
#include "json_util.h"
#include "network.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

namespace display {
static const char *TAG = "display";
static constexpr int hold_timeout_ms = 35000;  // the server holds a frame request for 25 s
static constexpr int short_timeout_ms = 10000;

static StaticSemaphore_t mutex_storage;
static SemaphoreHandle_t mutex;
static uint8_t image[frame::image_size];
static View state;
static std::string etag;
static char boot[17];
static QueueHandle_t presses;
static volatile bool unpair_requested = false;
struct Lock { Lock() { xSemaphoreTake(mutex, portMAX_DELAY); } ~Lock() { xSemaphoreGive(mutex); } };

View view() { Lock lock; return state; }
bool copy_image(uint8_t *out) {
    Lock lock;
    if (!state.has_frame) return false;
    memcpy(out, image, sizeof(image));
    return true;
}
const char *boot_id() { return boot; }
void press(bool long_press) {
    const uint8_t event = long_press ? 1 : 0;
    xQueueSend(presses, &event, 0);
}
void forget_session() { unpair_requested = true; }

static void set_phase(Phase phase, const std::string &problem = "", const std::string &code = "") {
    Lock lock;
    if (state.phase == phase && state.problem == problem && state.code == code) return;
    state.phase = phase; state.problem = problem; state.code = code;
    if (phase != Phase::showing && phase != Phase::loading) { state.has_frame = false; etag.clear(); }
    ++state.revision;
}
static void contact() { Lock lock; state.last_contact = esp_timer_get_time(); }

// A verified frame replaces the picture; the same picture again changes nothing.
static bool publish(const network::Reply &reply) {
    frame::Header header;
    const auto *data = reinterpret_cast<const uint8_t *>(reply.body.data());
    const auto result = frame::parse(data, reply.body.size(), header);
    if (result != frame::Result::ok) {
        ESP_LOGW(TAG, "frame rejected: %d, %u bytes", static_cast<int>(result), static_cast<unsigned>(reply.body.size()));
        return false;
    }
    Lock lock;
    state.last_contact = esp_timer_get_time();
    state.stale_after = header.stale_after;
    if (state.has_frame && reply.etag == etag && state.phase == Phase::showing) return true;
    memcpy(image, data + frame::header_size, frame::image_size);
    etag = reply.etag;
    state.has_frame = true;
    state.phase = Phase::showing;
    state.problem.clear();
    ++state.revision;
    return true;
}

static network::Headers report(const config::Session &session) {
    char number[16];
    network::Headers headers{{"Authorization", "Bearer " + session.token},
                             {"Display-Boot", boot},
                             {"Display-Firmware", esp_app_get_description()->version},
                             {"Display-Panel", "400x300x1"}};
    snprintf(number, sizeof(number), "%lld", static_cast<long long>(esp_timer_get_time() / 1000000));
    headers.emplace_back("Display-Uptime", number);
    if (const int level = network::rssi()) {
        snprintf(number, sizeof(number), "%d", level);
        headers.emplace_back("Display-RSSI", number);
    }
    snprintf(number, sizeof(number), "%u", static_cast<unsigned>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL)));
    headers.emplace_back("Display-Heap", number);
    return headers;
}

static void wait_ms(int64_t ms) { vTaskDelay(pdMS_TO_TICKS(std::max<int64_t>(ms, 10))); }
static int64_t backoff_ms(unsigned failures) {
    static const int64_t steps[] = {1000, 2000, 5000, 10000, 30000, 60000};
    return steps[std::min<unsigned>(failures, 5)] + esp_random() % 1000;
}

struct Pairing { std::string device_code, code; int64_t expires = 0, next_poll = 0; int interval = 5; };

static void pair(network::Connection &connection, const config::Server &server, Pairing &pairing, unsigned &failures) {
    const int64_t now = esp_timer_get_time();
    if (pairing.device_code.empty() || now >= pairing.expires) {
        auto reply = connection.request(server, HTTP_METHOD_POST, "/v1/devices/pairings", {},
                                        R"({"kind":"display","name":"Экран"})", 1024);
        auto root = reply.transport_ok && reply.status == 200 ? json::parse(reply.body.c_str()) : json::Document(nullptr, cJSON_Delete);
        Pairing next;
        if (!root || !json::text(root.get(), "device_code", next.device_code, 128) || !json::text(root.get(), "user_code", next.code, 16)) {
            set_phase(Phase::connecting, reply.transport_ok ? "Сервер не выдал код входа" : "Нет связи с сервером");
            wait_ms(reply.status == 429 && reply.retry_after > 0 ? reply.retry_after * 1000LL : backoff_ms(failures++));
            return;
        }
        next.interval = std::clamp(static_cast<int>(json::number(root.get(), "interval", 5)), 2, 60);
        next.expires = now + static_cast<int64_t>(std::clamp(json::number(root.get(), "expires_in", 600.0), 60.0, 3600.0) * 1e6) - 5000000;
        next.next_poll = now + next.interval * 1000000LL;
        pairing = std::move(next);
        failures = 0;
        contact();
        set_phase(Phase::pairing, "", pairing.code);
        return;
    }
    if (now < pairing.next_poll) { wait_ms(std::min<int64_t>((pairing.next_poll - now) / 1000, 250)); return; }
    const std::string body = std::string(R"({"device_code":")") + pairing.device_code + "\"}";
    auto reply = connection.request(server, HTTP_METHOD_POST, "/v1/devices/pairings/token", {}, body, 1024);
    pairing.next_poll = esp_timer_get_time() + pairing.interval * 1000000LL;
    if (!reply.transport_ok) { pairing.next_poll += backoff_ms(failures++) * 1000; return; }
    failures = 0;
    contact();
    if (reply.status == 428) return;  // the owner has not confirmed the code yet
    if (reply.status == 200) {
        auto root = json::parse(reply.body.c_str());
        config::Session session;
        if (root && json::text(root.get(), "token", session.token, 128) && json::text(root.get(), "device_id", session.device_id, 64) &&
            config::put_session(session) == ESP_OK) {
            ESP_LOGI(TAG, "paired as %s", session.device_id.c_str());
            pairing = {};
            set_phase(Phase::loading);
            return;
        }
    }
    pairing = {};  // gone, expired or unusable: ask for a new code
}

static void fetch(network::Connection &connection, const config::Server &server, const config::Session &session, unsigned &failures) {
    auto headers = report(session);
    std::string current;
    { Lock lock; if (state.has_frame) current = etag; }
    if (!current.empty()) headers.emplace_back("If-None-Match", current);
    auto reply = connection.request(server, HTTP_METHOD_GET, "/v1/display/frame", headers, "", frame::total_size);
    if (!reply.transport_ok) {
        { Lock lock; if (!state.has_frame) { state.problem = "Нет связи с сервером"; ++state.revision; } }
        wait_ms(backoff_ms(failures++));
        return;
    }
    switch (reply.status) {
        case 200:
            if (publish(reply)) failures = 0; else wait_ms(backoff_ms(failures++));
            return;
        case 304:
            failures = 0;
            contact();
            return;
        case 401:
            ESP_LOGW(TAG, "session revoked; pairing again");
            config::clear_session();
            set_phase(Phase::pairing);
            return;
        case 429:
            wait_ms(std::max(reply.retry_after, 1) * 1000LL);
            return;
        default:
            ESP_LOGW(TAG, "frame request: HTTP %d", reply.status);
            wait_ms(backoff_ms(failures++));
    }
}

static void frames_task(void *) {
    network::Connection connection(hold_timeout_ms);
    Pairing pairing;
    unsigned failures = 0;
    while (true) {
        network::tick();
        if (unpair_requested) {
            unpair_requested = false;
            config::clear_session();
            connection.reset();
            pairing = {};
        }
        config::Server server;
        if (config::read_server(server) != ESP_OK || !config::valid_server(server)) {
            set_phase(Phase::setup);
            wait_ms(500);
            continue;
        }
        if (!network::connected() || !network::time_ready()) {
            bool shown;
            { Lock lock; shown = state.has_frame; }
            // A picture already on the screen stays; the badge shows how old it is.
            if (!shown) set_phase(Phase::connecting, network::connected() ? "Синхронизация часов" : "Подключение к Wi-Fi");
            wait_ms(250);
            continue;
        }
        config::Session session;
        if (config::read_session(session) != ESP_OK || session.token.empty()) {
            pair(connection, server, pairing, failures);
            continue;
        }
        { Lock lock; if (state.phase != Phase::showing && state.phase != Phase::loading) { state.phase = Phase::loading; ++state.revision; } }
        fetch(connection, server, session, failures);
    }
}

// Presses go on their own connection: the frame connection is held by the long-poll.
static void presses_task(void *) {
    network::Connection connection(short_timeout_ms);
    uint32_t seq = 0;
    while (true) {
        uint8_t event;
        if (xQueueReceive(presses, &event, portMAX_DELAY) != pdTRUE) continue;
        config::Server server; config::Session session;
        if (config::read_server(server) != ESP_OK || config::read_session(session) != ESP_OK || session.token.empty()) continue;
        char body[96];
        snprintf(body, sizeof(body), R"({"boot":"%s","seq":%lu,"event":"%s"})", boot, static_cast<unsigned long>(++seq),
                 event ? "long" : "short");
        auto reply = connection.request(server, HTTP_METHOD_POST, "/v1/display/input", report(session), body, frame::total_size);
        if (reply.transport_ok && reply.status == 200) publish(reply);
        else if (reply.transport_ok && reply.status == 401) config::clear_session();
        else ESP_LOGW(TAG, "press not delivered: HTTP %d", reply.status);
    }
}

void start() {
    mutex = xSemaphoreCreateMutexStatic(&mutex_storage);
    presses = xQueueCreate(8, sizeof(uint8_t));
    snprintf(boot, sizeof(boot), "%08lx%08lx", static_cast<unsigned long>(esp_random()), static_cast<unsigned long>(esp_random()));
    configASSERT(xTaskCreate(frames_task, "display_frames", 12288, nullptr, 4, nullptr) == pdPASS);
    configASSERT(xTaskCreate(presses_task, "display_presses", 8192, nullptr, 5, nullptr) == pdPASS);
}
}  // namespace display
