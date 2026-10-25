#include "stroke/loaded_brush.h"

#include <utility>

#include "stroke/brush_json.h"

namespace nanquim::stroke {

namespace {

std::shared_ptr<const GrayTexture> decode(const std::string& name,
                                          const std::vector<uint8_t>& bytes, int32_t maximumSide,
                                          std::string& error, bool keepColors = false) {
  if (bytes.empty()) {
    error = "missing texture " + name;
    return nullptr;
  }
  std::string decodeError;
  auto texture =
      decodeGrayTexture(bytes.data(), bytes.size(), maximumSide, decodeError, keepColors);
  if (!texture) {
    error = "texture " + name + ": " + decodeError;
    return nullptr;
  }
  return std::make_shared<const GrayTexture>(std::move(*texture));
}

}  // namespace

std::shared_ptr<const LoadedBrush> loadBrush(std::string_view brushJson,
                                             const std::vector<uint8_t>& shapePng,
                                             const std::vector<uint8_t>& grainPng,
                                             std::string& error) {
  auto parameters = parseBrush(brushJson, error);
  if (!parameters) {
    return nullptr;
  }
  auto brush = std::make_shared<LoadedBrush>();
  brush->parameters = std::move(*parameters);
  if (!brush->parameters.shape.texture.empty()) {
    brush->shapeTexture =
        decode(brush->parameters.shape.texture, shapePng, kMaximumShapeTexturePixels, error,
               brush->parameters.stamp.keepColors);
    if (!brush->shapeTexture) {
      return nullptr;
    }
  }
  if (brush->parameters.grain.enabled()) {
    brush->grainTexture =
        decode(brush->parameters.grain.texture, grainPng, kMaximumGrainTexturePixels, error);
    if (!brush->grainTexture) {
      return nullptr;
    }
  }
  return brush;
}

}  // namespace nanquim::stroke
