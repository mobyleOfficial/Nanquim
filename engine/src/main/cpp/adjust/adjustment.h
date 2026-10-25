#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <string_view>
#include <vector>

namespace nanquim::adjust {

enum class Filter : int32_t {
  HueSaturationBrightness = 0,
  ColorBalance = 1,
  Curves = 2,
  GradientMap = 3,
  GaussianBlur = 4,
  MotionBlur = 5,
  PerspectiveBlur = 6,
  Sharpen = 7,
  Noise = 8,
  Bloom = 9,
  Glitch = 10,
  Halftone = 11,
  ChromaticAberration = 12,
  Liquify = 13,
};

inline constexpr size_t kParameterCount = 16;
inline constexpr size_t kTableSize = 1024;

using Table = std::vector<float>;

struct Parameters {
  Filter filter = Filter::HueSaturationBrightness;
  std::array<float, kParameterCount> values{};
  std::shared_ptr<const Table> table;
};

std::vector<std::string_view> parameterNames(Filter filter);

std::string_view filterName(Filter filter);
std::optional<Filter> filterFromName(std::string_view name);

bool usesTable(Filter filter);

std::optional<Table> bakeTable(Filter filter, const std::vector<float>& data);

std::vector<float> defaultTableData(Filter filter);

double monotoneCurve(const std::vector<std::array<double, 2>>& points, double x);

double lookup(const Table& table, size_t channel, double value);

double luminance(const std::array<double, 3>& color);

std::array<double, 3> hueSaturationBrightness(const std::array<double, 3>& color, double hue,
                                              double saturation, double brightness);

std::array<double, 3> colorBalance(const std::array<double, 3>& color,
                                   const std::array<float, kParameterCount>& shifts);

struct PixelPosition {
  double x = 0;
  double y = 0;
  int32_t width = 1;
  int32_t height = 1;
};

std::array<double, 4> filterColor(const Parameters& parameters, const std::array<double, 4>& color,
                                  const PixelPosition& position = {});

std::array<double, 4> applyToPixel(const Parameters& parameters,
                                   const std::array<double, 4>& premultiplied, double weight,
                                   const PixelPosition& position = {});

uint32_t hash32(int32_t x, int32_t y, uint32_t seed);
double hashUnit(int32_t x, int32_t y, uint32_t seed);
double valueNoise(double x, double y, uint32_t seed);
double noiseAt(const Parameters& parameters, const PixelPosition& position);
double overlay(double base, double blend);

std::array<double, 3> hsvFromRgb(const std::array<double, 3>& color);
std::array<double, 3> rgbFromHsv(const std::array<double, 3>& hsv);

}  // namespace nanquim::adjust
