#pragma once
#include "esp_err.h"
#include "esp_http_client.h"
#include "config_store.h"
#include <string>
#include <utility>
#include <vector>

namespace network {
esp_err_t init();
void tick();
bool connected();
bool time_ready();
int rssi();  // dBm of the current access point, 0 when unknown

using Headers = std::vector<std::pair<const char *, std::string>>;
struct Reply {
    bool transport_ok = false;  // a complete HTTP response arrived
    bool oversized = false;
    int status = 0;
    int retry_after = 0;
    std::string etag;
    std::string body;
};

// One kept-alive HTTPS connection to the server, used by a single task.
class Connection {
public:
    explicit Connection(int timeout_ms) : timeout(timeout_ms) {}
    ~Connection() { reset(); }
    Reply request(const config::Server &server, esp_http_client_method_t method, const char *path,
                  const Headers &headers, const std::string &body, size_t max_body);
    void reset();

    struct Collector { std::string body; size_t max = 0; bool overflow = false; std::string etag; int retry_after = 0; };

private:
    int timeout;
    esp_http_client_handle_t client = nullptr;
    std::string base, ca, url;
    std::vector<const char *> extra;
    Collector collector;
};
}  // namespace network
