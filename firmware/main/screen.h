#pragma once
#include "esp_err.h"
namespace screen {
esp_err_t init();
// Sends a new picture to the panel only when something shown has changed.
void draw();
}
