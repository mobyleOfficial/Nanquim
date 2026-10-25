#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "adjust/adjustment.h"

namespace nanquim::adjust {

inline constexpr double kMaximumBlurPixels = 250;
inline constexpr double kMaximumLevelSigma = 2.5;
inline constexpr int32_t kMaximumLineTaps = 16;
inline constexpr int32_t kMaximumRayTaps = 32;
inline constexpr int32_t kMaximumRaySamples = 1024;
inline constexpr int32_t kMaximumGaussianRadius = 8;

struct LevelMapping {
  int32_t level = 0;
  double offset = 0;
  [[nodiscard]] double scale() const { return static_cast<double>(1 << level); }
  [[nodiscard]] double toTexel(double pixel) const { return (pixel - offset) / scale(); }
};

LevelMapping boxMapping(int32_t level);

struct SpatialPass {
  enum class Kind : int32_t {
    GaussianHorizontal = 1,
    GaussianVertical = 2,
    Line = 3,
    Zoom = 4,
    Directional = 5,
    Threshold = 6,
    Halftone = 7,
    Glitch = 8,
    Chromatic = 9,
  };
  Kind kind = Kind::GaussianHorizontal;
  std::vector<double> weights;
  int32_t taps = 1;
  std::array<double, 2> step{};
  std::array<double, 2> center{};
  double spacing = 0;
  std::array<double, 4> values{};
  int32_t variant = 0;
};

struct SpatialPlan {
  int32_t level = 0;
  bool pyramid = false;
  std::vector<SpatialPass> passes;
  bool unsharp = false;
  double sharpen = 0;
  bool additive = false;
  double gain = 0;
  int32_t reach = 0;
};

bool isSpatial(Filter filter);

SpatialPlan spatialPlan(const Parameters& parameters, int32_t width, int32_t height,
                        int32_t minimumLevel);

struct WorkImage {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<double> rgba;
  [[nodiscard]] std::array<double, 4> texel(int32_t x, int32_t y) const;
  [[nodiscard]] std::array<double, 4> sample(double x, double y) const;
};

WorkImage boxDownsample(const std::vector<double>& rgba, int32_t width, int32_t height,
                        int32_t level);

WorkImage runPass(const SpatialPass& pass, const WorkImage& image, const LevelMapping& mapping);

std::array<double, 4> pixelPass(const SpatialPass& pass, const WorkImage& image,
                                const LevelMapping& mapping, int32_t x, int32_t y);

std::array<double, 4> resultAt(const WorkImage& result, const LevelMapping& mapping, int32_t x,
                               int32_t y);

std::array<double, 4> combineSpatial(const SpatialPlan& plan, const std::array<double, 4>& original,
                                     const std::array<double, 4>& filtered, double weight);

std::vector<double> filterImage(const Parameters& parameters, const std::vector<double>& rgba,
                                int32_t width, int32_t height);

}  // namespace nanquim::adjust
