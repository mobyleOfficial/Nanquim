#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>

namespace nanquim::canvas {

enum class BlendMode : int32_t {
  Normal = 0,
  Multiply = 1,
  Screen = 2,
  Overlay = 3,
  Darken = 4,
  Lighten = 5,
  ColorDodge = 6,
  ColorBurn = 7,
  HardLight = 8,
  SoftLight = 9,
  Difference = 10,
  Exclusion = 11,
  Add = 12,
  Subtract = 13,
  Divide = 14,
  LinearBurn = 15,
  VividLight = 16,
  LinearLight = 17,
  PinLight = 18,
  HardMix = 19,
  Hue = 20,
  Saturation = 21,
  Color = 22,
  Luminosity = 23,
  DarkerColor = 24,
  LighterColor = 25,
  PassThrough = 26,
  Behind = 27,
};

inline constexpr int32_t kLayerBlendModeCount = 26;

struct BlendModeName {
  BlendMode mode;
  std::string_view id;
};

inline constexpr std::array<BlendModeName, 28> kBlendModeNames{{
    {BlendMode::Normal, "normal"},
    {BlendMode::Multiply, "multiply"},
    {BlendMode::Screen, "screen"},
    {BlendMode::Overlay, "overlay"},
    {BlendMode::Darken, "darken"},
    {BlendMode::Lighten, "lighten"},
    {BlendMode::ColorDodge, "colorDodge"},
    {BlendMode::ColorBurn, "colorBurn"},
    {BlendMode::HardLight, "hardLight"},
    {BlendMode::SoftLight, "softLight"},
    {BlendMode::Difference, "difference"},
    {BlendMode::Exclusion, "exclusion"},
    {BlendMode::Add, "add"},
    {BlendMode::Subtract, "subtract"},
    {BlendMode::Divide, "divide"},
    {BlendMode::LinearBurn, "linearBurn"},
    {BlendMode::VividLight, "vividLight"},
    {BlendMode::LinearLight, "linearLight"},
    {BlendMode::PinLight, "pinLight"},
    {BlendMode::HardMix, "hardMix"},
    {BlendMode::Hue, "hue"},
    {BlendMode::Saturation, "saturation"},
    {BlendMode::Color, "color"},
    {BlendMode::Luminosity, "luminosity"},
    {BlendMode::DarkerColor, "darkerColor"},
    {BlendMode::LighterColor, "lighterColor"},
    {BlendMode::PassThrough, "passThrough"},
    {BlendMode::Behind, "behind"},
}};

inline std::optional<BlendMode> blendModeFromId(std::string_view id) {
  for (const BlendModeName& entry : kBlendModeNames) {
    if (entry.id == id) {
      return entry.mode;
    }
  }
  return std::nullopt;
}

inline std::string_view blendModeId(BlendMode mode) {
  for (const BlendModeName& entry : kBlendModeNames) {
    if (entry.mode == mode) {
      return entry.id;
    }
  }
  return "normal";
}

inline bool isLayerBlendMode(BlendMode mode) {
  return static_cast<int32_t>(mode) >= 0 &&
         static_cast<int32_t>(mode) < kLayerBlendModeCount;
}

}  // namespace nanquim::canvas
