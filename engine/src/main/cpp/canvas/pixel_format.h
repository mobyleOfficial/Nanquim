#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

#include "canvas/tile_coordinate.h"

namespace nanquim::canvas {

enum class PixelFormat : uint8_t {
  Rgba8PremulSrgb,
  Rgba16fPremulLinear,
};

constexpr int32_t bytesPerPixel(PixelFormat format) {
  return format == PixelFormat::Rgba16fPremulLinear ? 8 : 4;
}

constexpr int64_t tileBytes(PixelFormat format) {
  return static_cast<int64_t>(kTileSizePixels) * kTileSizePixels * bytesPerPixel(format);
}

constexpr bool isLinear(PixelFormat format) { return format == PixelFormat::Rgba16fPremulLinear; }

constexpr std::string_view pixelFormatName(PixelFormat format) {
  return format == PixelFormat::Rgba16fPremulLinear ? "rgba16f_premul_linear" : "rgba8_premul_srgb";
}

inline std::optional<PixelFormat> parsePixelFormat(std::string_view name) {
  if (name == "rgba8_premul_srgb") {
    return PixelFormat::Rgba8PremulSrgb;
  }
  if (name == "rgba16f_premul_linear") {
    return PixelFormat::Rgba16fPremulLinear;
  }
  return std::nullopt;
}

}  // namespace nanquim::canvas
