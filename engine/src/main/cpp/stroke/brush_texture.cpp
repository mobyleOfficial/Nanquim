#include "stroke/brush_texture.h"

#include <algorithm>
#include <cmath>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include <stb_image.h>

namespace nanquim::stroke {

namespace {

int32_t wrap(int32_t value, int32_t size) {
  const int32_t remainder = value % size;
  return remainder < 0 ? remainder + size : remainder;
}

}  // namespace

std::optional<GrayTexture> GrayTexture::create(int32_t width, int32_t height,
                                               std::vector<uint8_t> pixels) {
  return build(width, height, 1, std::move(pixels));
}

std::optional<GrayTexture> GrayTexture::createColor(int32_t width, int32_t height,
                                                    std::vector<uint8_t> pixels) {
  return build(width, height, 4, std::move(pixels));
}

std::optional<GrayTexture> GrayTexture::build(int32_t width, int32_t height, int32_t channels,
                                              std::vector<uint8_t> pixels) {
  if (width < 1 || height < 1 ||
      pixels.size() != static_cast<size_t>(width) * static_cast<size_t>(height) *
                           static_cast<size_t>(channels)) {
    return std::nullopt;
  }
  GrayTexture texture;
  texture.width_ = width;
  texture.height_ = height;
  texture.channels_ = channels;
  const auto stride = static_cast<size_t>(channels);
  texture.levels_.push_back(std::move(pixels));
  while (texture.levelWidth(texture.levelCount() - 1) > 1 ||
         texture.levelHeight(texture.levelCount() - 1) > 1) {
    const int32_t source = texture.levelCount() - 1;
    const int32_t sourceWidth = texture.levelWidth(source);
    const int32_t sourceHeight = texture.levelHeight(source);
    const int32_t targetWidth = std::max(1, sourceWidth / 2);
    const int32_t targetHeight = std::max(1, sourceHeight / 2);
    const std::vector<uint8_t>& above = texture.levels_.back();
    std::vector<uint8_t> next(static_cast<size_t>(targetWidth) * static_cast<size_t>(targetHeight) *
                              stride);
    for (int32_t y = 0; y < targetHeight; ++y) {
      const int32_t y0 = std::min(2 * y, sourceHeight - 1);
      const int32_t y1 = std::min(2 * y + 1, sourceHeight - 1);
      for (int32_t x = 0; x < targetWidth; ++x) {
        const int32_t x0 = std::min(2 * x, sourceWidth - 1);
        const int32_t x1 = std::min(2 * x + 1, sourceWidth - 1);
        for (size_t channel = 0; channel < stride; ++channel) {
          const auto at = [&](int32_t column, int32_t row) {
            return static_cast<int32_t>(
                above[(static_cast<size_t>(row) * static_cast<size_t>(sourceWidth) +
                       static_cast<size_t>(column)) *
                          stride +
                      channel]);
          };
          next[(static_cast<size_t>(y) * static_cast<size_t>(targetWidth) +
                static_cast<size_t>(x)) *
                   stride +
               channel] =
              static_cast<uint8_t>((at(x0, y0) + at(x1, y0) + at(x0, y1) + at(x1, y1) + 2) / 4);
        }
      }
    }
    texture.levels_.push_back(std::move(next));
  }
  return texture;
}

int32_t GrayTexture::levelWidth(int32_t level) const { return std::max(1, width_ >> level); }

int32_t GrayTexture::levelHeight(int32_t level) const { return std::max(1, height_ >> level); }

double GrayTexture::texel(int32_t level, int32_t x, int32_t y, int32_t channel, bool repeat) const {
  const int32_t width = levelWidth(level);
  const int32_t height = levelHeight(level);
  if (repeat) {
    x = wrap(x, width);
    y = wrap(y, height);
  } else {
    x = std::clamp(x, 0, width - 1);
    y = std::clamp(y, 0, height - 1);
  }
  return levels_[static_cast<size_t>(level)]
                [(static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) *
                     static_cast<size_t>(channels_) +
                 static_cast<size_t>(channel)] /
         255.0;
}

double GrayTexture::bilinear(int32_t level, double u, double v, int32_t channel,
                             bool repeat) const {
  const double x = u * levelWidth(level) - 0.5;
  const double y = v * levelHeight(level) - 0.5;
  const double floorX = std::floor(x);
  const double floorY = std::floor(y);
  const double weightX = x - floorX;
  const double weightY = y - floorY;
  const auto x0 = static_cast<int32_t>(floorX);
  const auto y0 = static_cast<int32_t>(floorY);
  const double top = texel(level, x0, y0, channel, repeat) * (1 - weightX) +
                     texel(level, x0 + 1, y0, channel, repeat) * weightX;
  const double bottom = texel(level, x0, y0 + 1, channel, repeat) * (1 - weightX) +
                        texel(level, x0 + 1, y0 + 1, channel, repeat) * weightX;
  return top * (1 - weightY) + bottom * weightY;
}

double GrayTexture::sample(double u, double v, double lod, TextureFiltering filtering,
                           bool repeat) const {
  return sampleChannel(channels_ - 1, u, v, lod, filtering, repeat);
}

double GrayTexture::sampleChannel(int32_t channel, double u, double v, double lod,
                                  TextureFiltering filtering, bool repeat) const {
  if (filtering == TextureFiltering::None) {
    return texel(0, static_cast<int32_t>(std::floor(u * width_)),
                 static_cast<int32_t>(std::floor(v * height_)), channel, repeat);
  }
  if (!(lod > 0)) {
    return bilinear(0, u, v, channel, repeat);
  }
  const double maximumLevel = levelCount() - 1;
  const double clamped = std::min(lod, maximumLevel);
  const auto lower = static_cast<int32_t>(std::floor(clamped));
  const int32_t upper = std::min(lower + 1, levelCount() - 1);
  const double weight = clamped - lower;
  const double low = bilinear(lower, u, v, channel, repeat);
  if (weight <= 0 || upper == lower) {
    return low;
  }
  return low * (1 - weight) + bilinear(upper, u, v, channel, repeat) * weight;
}

std::optional<GrayTexture> decodeGrayTexture(const uint8_t* data, size_t size, int32_t maximumSide,
                                             std::string& error, bool keepColors) {
  int width = 0;
  int height = 0;
  int channels = 0;
  stbi_uc* pixels = stbi_load_from_memory(data, static_cast<int>(size), &width, &height, &channels,
                                          keepColors ? 4 : 2);
  if (pixels == nullptr) {
    error = stbi_failure_reason() != nullptr ? stbi_failure_reason() : "cannot decode image";
    return std::nullopt;
  }
  if (width > maximumSide || height > maximumSide) {
    stbi_image_free(pixels);
    error = "texture larger than " + std::to_string(maximumSide) + " px";
    return std::nullopt;
  }
  if (keepColors) {
    std::vector<uint8_t> premultiplied(static_cast<size_t>(width) * static_cast<size_t>(height) *
                                       4);
    for (size_t index = 0; index < premultiplied.size(); index += 4) {
      const int32_t alpha = pixels[index + 3];
      for (size_t channel = 0; channel < 3; ++channel) {
        premultiplied[index + channel] =
            static_cast<uint8_t>((pixels[index + channel] * alpha + 127) / 255);
      }
      premultiplied[index + 3] = static_cast<uint8_t>(alpha);
    }
    stbi_image_free(pixels);
    return GrayTexture::createColor(width, height, std::move(premultiplied));
  }
  std::vector<uint8_t> gray(static_cast<size_t>(width) * static_cast<size_t>(height));
  for (size_t index = 0; index < gray.size(); ++index) {
    gray[index] = static_cast<uint8_t>((pixels[index * 2] * pixels[index * 2 + 1] + 127) / 255);
  }
  stbi_image_free(pixels);
  return GrayTexture::create(width, height, std::move(gray));
}

}  // namespace nanquim::stroke
