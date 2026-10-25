#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <vector>

namespace nanquim::reference {

struct ImageComparison {
  size_t differingPixels = 0;
  size_t totalPixels = 0;
  int32_t maximumChannelDifference = 0;
  bool sameSize = true;
  bool matches = false;
};

inline ImageComparison compareRgba8(const std::vector<uint8_t>& actual, int32_t actualWidth,
                                    int32_t actualHeight, const std::vector<uint8_t>& expected,
                                    int32_t expectedWidth, int32_t expectedHeight,
                                    int32_t channelTolerance = 2,
                                    double maximumDifferingRatio = 0.001) {
  ImageComparison comparison;
  if (actualWidth != expectedWidth || actualHeight != expectedHeight ||
      actual.size() != expected.size()) {
    comparison.sameSize = false;
    return comparison;
  }
  comparison.totalPixels = actual.size() / 4;
  for (size_t offset = 0; offset < actual.size(); offset += 4) {
    int32_t pixelDifference = 0;
    for (size_t channel = 0; channel < 4; ++channel) {
      const int32_t difference =
          std::abs(static_cast<int32_t>(actual[offset + channel]) - expected[offset + channel]);
      pixelDifference = difference > pixelDifference ? difference : pixelDifference;
    }
    comparison.maximumChannelDifference = pixelDifference > comparison.maximumChannelDifference
                                              ? pixelDifference
                                              : comparison.maximumChannelDifference;
    if (pixelDifference > channelTolerance) {
      ++comparison.differingPixels;
    }
  }
  comparison.matches = static_cast<double>(comparison.differingPixels) <=
                       maximumDifferingRatio * static_cast<double>(comparison.totalPixels);
  return comparison;
}

}  // namespace nanquim::reference
