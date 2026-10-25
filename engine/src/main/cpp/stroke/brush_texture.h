#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "stroke/brush_parameters.h"

namespace nanquim::stroke {

class GrayTexture {
 public:
  static std::optional<GrayTexture> create(int32_t width, int32_t height,
                                           std::vector<uint8_t> pixels);
  static std::optional<GrayTexture> createColor(int32_t width, int32_t height,
                                                std::vector<uint8_t> pixels);

  [[nodiscard]] int32_t width() const { return width_; }
  [[nodiscard]] int32_t height() const { return height_; }
  [[nodiscard]] int32_t levelCount() const { return static_cast<int32_t>(levels_.size()); }
  [[nodiscard]] int32_t channels() const { return channels_; }
  [[nodiscard]] bool hasColor() const { return channels_ == 4; }
  [[nodiscard]] int32_t levelWidth(int32_t level) const;
  [[nodiscard]] int32_t levelHeight(int32_t level) const;
  [[nodiscard]] const std::vector<uint8_t>& level(int32_t level) const {
    return levels_[static_cast<size_t>(level)];
  }

  [[nodiscard]] double sample(double u, double v, double lod, TextureFiltering filtering,
                              bool repeat) const;
  [[nodiscard]] double sampleChannel(int32_t channel, double u, double v, double lod,
                                     TextureFiltering filtering, bool repeat) const;

 private:
  static std::optional<GrayTexture> build(int32_t width, int32_t height, int32_t channels,
                                          std::vector<uint8_t> pixels);
  [[nodiscard]] double texel(int32_t level, int32_t x, int32_t y, int32_t channel,
                             bool repeat) const;
  [[nodiscard]] double bilinear(int32_t level, double u, double v, int32_t channel,
                                bool repeat) const;

  int32_t width_ = 0;
  int32_t height_ = 0;
  int32_t channels_ = 1;
  std::vector<std::vector<uint8_t>> levels_;
};

inline constexpr int32_t kMaximumShapeTexturePixels = 1024;
inline constexpr int32_t kMaximumGrainTexturePixels = 2048;

std::optional<GrayTexture> decodeGrayTexture(const uint8_t* data, size_t size, int32_t maximumSide,
                                             std::string& error, bool keepColors = false);

}  // namespace nanquim::stroke
