#pragma once
#include <cmath>
#include <memory>
#include <string>
#include "cJSON.h"

namespace json {
using Document = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
inline Document parse(const char *text) { return Document(cJSON_ParseWithOpts(text, nullptr, true), cJSON_Delete); }
inline Document object() { return Document(cJSON_CreateObject(), cJSON_Delete); }
inline const cJSON *get(const cJSON *root, const char *key) {
    return cJSON_GetObjectItemCaseSensitive(root, key);
}
inline bool text(const cJSON *root, const char *key, std::string &out, size_t max, bool required = true) {
    const auto *value = get(root, key);
    if (!value || cJSON_IsNull(value)) { out.clear(); return !required; }
    if (!cJSON_IsString(value) || !value->valuestring) return false;
    out = value->valuestring;
    return out.size() <= max && (!required || !out.empty());
}
inline double number(const cJSON *root, const char *key, double fallback = 0) {
    const auto *value = get(root, key);
    return cJSON_IsNumber(value) && std::isfinite(value->valuedouble) ? value->valuedouble : fallback;
}
inline std::string dump(const cJSON *root) {
    char *raw = cJSON_PrintUnformatted(root);
    std::string result = raw ? raw : "";
    cJSON_free(raw);
    return result;
}
inline void add(cJSON *root, const char *key, const std::string &value) {
    cJSON_AddStringToObject(root, key, value.c_str());
}
}  // namespace json
