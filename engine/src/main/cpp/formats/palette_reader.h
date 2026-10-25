#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "color/color_profile.h"

namespace nanquim::formats {

struct PaletteColor {
  float red = 0;
  float green = 0;
  float blue = 0;
  float alpha = 1;
  std::string name;
};

struct PaletteWarning {
  std::string code;
  std::string detail;
};

struct ImportedPalette {
  std::string format;
  std::string name;
  int columns = 0;
  std::vector<PaletteColor> colors;
  std::vector<PaletteWarning> warnings;
};

inline constexpr size_t kMaximumPaletteSwatches = 300;

std::optional<ImportedPalette> readPalette(const std::vector<uint8_t>& bytes, std::string& error,
                                           const color::CmykColorConverter* cmyk = nullptr);

std::optional<std::string> paletteJson(const std::vector<uint8_t>& bytes, std::string& error,
                                       const color::CmykColorConverter* cmyk = nullptr);

void labToSrgb(double lightness, double a, double b, float& red, float& green, float& blue);

}  // namespace nanquim::formats
