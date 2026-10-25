#pragma once

#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <string_view>

#include "stroke/brush_parameters.h"

namespace nanquim::stroke {

std::optional<BrushParameters> parseBrush(const nlohmann::json& document, std::string& error);
std::optional<BrushParameters> parseBrush(std::string_view jsonText, std::string& error);

}  // namespace nanquim::stroke
