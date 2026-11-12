#pragma once

#include <cstdlib>
#include <optional>

#include "core/log.h"

namespace nanquim::core {

// Reads an optional that the caller's state guarantees is set: a session that is open while its
// commands run, the document while one is loaded. Breaking that guarantee stops with a log line
// naming the caller instead of reading an empty optional, which is undefined behavior.
template <typename T>
T& required(std::optional<T>& value, const char* caller = __builtin_FUNCTION()) {
  if (!value.has_value()) {
    NANQUIM_LOG_ERROR("%s: required value is missing", caller);
    std::abort();
  }
  return *value;
}

template <typename T>
const T& required(const std::optional<T>& value, const char* caller = __builtin_FUNCTION()) {
  if (!value.has_value()) {
    NANQUIM_LOG_ERROR("%s: required value is missing", caller);
    std::abort();
  }
  return *value;
}

}  // namespace nanquim::core
