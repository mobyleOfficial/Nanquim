#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "stroke/brush_parameters.h"
#include "stroke/brush_texture.h"

namespace nanquim::stroke {

struct LoadedBrush {
  BrushParameters parameters;
  std::shared_ptr<const GrayTexture> shapeTexture;
  std::shared_ptr<const GrayTexture> grainTexture;
  std::shared_ptr<const LoadedBrush> dual;
};

std::shared_ptr<const LoadedBrush> loadBrush(std::string_view brushJson,
                                             const std::vector<uint8_t>& shapePng,
                                             const std::vector<uint8_t>& grainPng,
                                             std::string& error);

}  // namespace nanquim::stroke
