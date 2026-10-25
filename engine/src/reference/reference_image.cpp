#include "reference_image.h"

#include <algorithm>
#include <cmath>

#include "canvas/color_conversion.h"
#include "core/half_float.h"

namespace nanquim::reference {

namespace {

uint8_t toByte(double value) {
  return static_cast<uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
}

}  // namespace

void quantizeToRgba8(Image& image) {
  for (double& channel : image.rgba) {
    channel = static_cast<double>(toByte(channel)) / 255.0;
  }
}

void quantizeToHalf(Image& image) {
  for (double& channel : image.rgba) {
    channel = core::halfToFloat(core::floatToHalf(static_cast<float>(channel)));
  }
}

void quantize(Image& image, canvas::PixelFormat format) {
  if (canvas::isLinear(format)) {
    quantizeToHalf(image);
  } else {
    quantizeToRgba8(image);
  }
}

namespace {

Image convertColors(const Image& source, double (*transfer)(double)) {
  Image result = source;
  for (size_t offset = 0; offset < result.rgba.size(); offset += 4) {
    const double alpha = result.rgba[offset + 3];
    for (size_t channel = 0; channel < 3; ++channel) {
      result.rgba[offset + channel] =
          alpha > 0 ? transfer(std::clamp(source.rgba[offset + channel] / alpha, 0.0, 1.0)) * alpha
                    : 0.0;
    }
  }
  return result;
}

}  // namespace

Image encodedToSrgb(const Image& linear) {
  return convertColors(linear, canvas::linearToSrgbValue);
}

Image decodedFromSrgb(const Image& encoded) {
  return convertColors(encoded, canvas::srgbToLinearValue);
}

std::vector<uint8_t> toUnpremultipliedRgba8(const Image& image) {
  std::vector<uint8_t> bytes(image.rgba.size());
  for (size_t offset = 0; offset < image.rgba.size(); offset += 4) {
    const double alpha = std::clamp(image.rgba[offset + 3], 0.0, 1.0);
    for (size_t channel = 0; channel < 3; ++channel) {
      bytes[offset + channel] = alpha > 0 ? toByte(image.rgba[offset + channel] / alpha) : 0;
    }
    bytes[offset + 3] = toByte(alpha);
  }
  return bytes;
}

Image fromUnpremultipliedRgba8(const uint8_t* pixels, int32_t width, int32_t height) {
  Image image(width, height);
  for (size_t offset = 0; offset < image.rgba.size(); offset += 4) {
    const double alpha = pixels[offset + 3] / 255.0;
    for (size_t channel = 0; channel < 3; ++channel) {
      image.rgba[offset + channel] = pixels[offset + channel] / 255.0 * alpha;
    }
    image.rgba[offset + 3] = alpha;
  }
  return image;
}

}  // namespace nanquim::reference
