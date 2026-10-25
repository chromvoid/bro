#pragma once
#include <cstdint>
#include <string>
#include "esp_err.h"

// NVS namespace "quota_cfg" is kept from the voice firmware so its Wi-Fi settings survive the update.
namespace config {
struct Wifi { std::string ssid; std::string password; uint32_t revision = 0; };
// The assistant's LAN entrance: https://host:port without a path, and the CA that signs it.
struct Server { std::string url; std::string ca_pem; uint32_t revision = 0; };
// The screen's own bearer session, received after the owner confirmed the pairing code.
struct Session { std::string token; std::string device_id; };
esp_err_t init();
esp_err_t read_wifi(Wifi &out);
esp_err_t put_wifi(const Wifi &value);
esp_err_t read_server(Server &out);
esp_err_t put_server(const Server &value);
bool valid_server(const Server &value);
esp_err_t read_session(Session &out);
esp_err_t put_session(const Session &value);
esp_err_t clear_session();
bool complete();
}
