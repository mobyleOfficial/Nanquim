#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace nanquim::formats {

using Rgb8 = std::array<uint8_t, 3>;

std::array<float, 3> oklabOf(Rgb8 color);

std::vector<Rgb8> choosePalette(const uint8_t* rgba, size_t pixelCount, size_t maximumColors,
                                size_t maximumSamples = 32768, bool* exact = nullptr);

class PaletteMapper {
 public:
  explicit PaletteMapper(const std::vector<Rgb8>& palette);
  [[nodiscard]] uint8_t nearest(int32_t red, int32_t green, int32_t blue);
  [[nodiscard]] const std::vector<Rgb8>& palette() const { return palette_; }

 private:
  std::vector<Rgb8> palette_;
  std::vector<std::array<float, 3>> labs_;
  std::vector<int16_t> cache_;
  std::vector<int16_t> cellFirst_;
  std::vector<int16_t> cellNext_;
};

}  // namespace nanquim::formats
