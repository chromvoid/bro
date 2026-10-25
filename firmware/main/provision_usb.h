#pragma once
#include "esp_err.h"
namespace provision {
esp_err_t start();
void open_settings();
bool settings_open();
}
