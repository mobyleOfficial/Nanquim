#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "canvas/pixel_format.h"

namespace nanquim::reference {

struct Image {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<double> rgba;

  Image() = default;
  Image(int32_t imageWidth, int32_t imageHeight)
      : width(imageWidth),
        height(imageHeight),
        rgba(static_cast<size_t>(imageWidth) * static_cast<size_t>(imageHeight) * 4, 0.0) {}

  [[nodiscard]] size_t offset(int32_t x, int32_t y) const {
    return (static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 4;
  }
  double* pixel(int32_t x, int32_t y) { return &rgba[offset(x, y)]; }
  [[nodiscard]] const double* pixel(int32_t x, int32_t y) const { return &rgba[offset(x, y)]; }
};

void quantizeToRgba8(Image& image);

void quantizeToHalf(Image& image);

void quantize(Image& image, canvas::PixelFormat format);

Image encodedToSrgb(const Image& linear);
Image decodedFromSrgb(const Image& encoded);

std::vector<uint8_t> toUnpremultipliedRgba8(const Image& image);

Image fromUnpremultipliedRgba8(const uint8_t* pixels, int32_t width, int32_t height);

}  // namespace nanquim::reference
