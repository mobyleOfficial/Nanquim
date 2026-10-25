#include "formats/color_quantizer.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <unordered_set>

namespace nanquim::formats {

namespace {

constexpr int32_t kMeansPasses = 3;
constexpr int32_t kCacheBits = 6;

const std::array<float, 256>& linearTable() {
  static const std::array<float, 256> table = [] {
    std::array<float, 256> values{};
    for (size_t index = 0; index < values.size(); ++index) {
      const double srgb = static_cast<double>(index) / 255.0;
      values[index] = static_cast<float>(srgb <= 0.04045 ? srgb / 12.92
                                                         : std::pow((srgb + 0.055) / 1.055, 2.4));
    }
    return values;
  }();
  return table;
}

float distance(const std::array<float, 3>& first, const std::array<float, 3>& second) {
  const float lightness = first[0] - second[0];
  const float green = first[1] - second[1];
  const float blue = first[2] - second[2];
  return lightness * lightness + green * green + blue * blue;
}

struct Sample {
  std::array<float, 3> lab;
  Rgb8 color;
};

struct Box {
  size_t begin = 0;
  size_t end = 0;
  int32_t axis = 0;
  float range = 0;
};

Box measure(const std::vector<Sample>& samples, size_t begin, size_t end) {
  Box box{begin, end, 0, 0};
  std::array<float, 3> low{std::numeric_limits<float>::max(), std::numeric_limits<float>::max(),
                           std::numeric_limits<float>::max()};
  std::array<float, 3> high{-low[0], -low[1], -low[2]};
  for (size_t index = begin; index < end; ++index) {
    for (size_t axis = 0; axis < 3; ++axis) {
      low[axis] = std::min(low[axis], samples[index].lab[axis]);
      high[axis] = std::max(high[axis], samples[index].lab[axis]);
    }
  }
  for (int32_t axis = 0; axis < 3; ++axis) {
    const float range = high[static_cast<size_t>(axis)] - low[static_cast<size_t>(axis)];
    if (range > box.range) {
      box.range = range;
      box.axis = axis;
    }
  }
  return box;
}

}  // namespace

std::array<float, 3> oklabOf(Rgb8 color) {
  const auto& linear = linearTable();
  const float red = linear[color[0]];
  const float green = linear[color[1]];
  const float blue = linear[color[2]];
  const float l = std::cbrt(0.4122214708F * red + 0.5363325363F * green + 0.0514459929F * blue);
  const float m = std::cbrt(0.2119034982F * red + 0.6806995451F * green + 0.1073969566F * blue);
  const float s = std::cbrt(0.0883024619F * red + 0.2817188376F * green + 0.6299787005F * blue);
  return {0.2104542553F * l + 0.7936177850F * m - 0.0040720468F * s,
          1.9779984951F * l - 2.4285922050F * m + 0.4505937099F * s,
          0.0259040371F * l + 0.7827717662F * m - 0.8086757660F * s};
}

std::vector<Rgb8> choosePalette(const uint8_t* rgba, size_t pixelCount, size_t maximumColors,
                                size_t maximumSamples, bool* exact) {
  if (exact != nullptr) {
    *exact = false;
  }
  if (maximumColors == 0) {
    return {};
  }
  size_t opaque = 0;
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    opaque += rgba[pixel * 4 + 3] >= 128 ? 1 : 0;
  }
  if (opaque == 0) {
    return {};
  }
  {
    std::unordered_set<uint32_t> distinct;
    for (size_t pixel = 0; pixel < pixelCount && distinct.size() <= maximumColors; ++pixel) {
      const uint8_t* value = rgba + pixel * 4;
      if (value[3] >= 128) {
        distinct.insert((uint32_t{value[0]} << 16U) | (uint32_t{value[1]} << 8U) | value[2]);
      }
    }
    if (distinct.size() <= maximumColors) {
      std::vector<Rgb8> palette;
      palette.reserve(distinct.size());
      for (const uint32_t color : distinct) {
        palette.push_back({static_cast<uint8_t>(color >> 16U), static_cast<uint8_t>(color >> 8U),
                           static_cast<uint8_t>(color)});
      }
      std::sort(palette.begin(), palette.end());
      if (exact != nullptr) {
        *exact = true;
      }
      return palette;
    }
  }
  const size_t stride = std::max<size_t>(1, (opaque + maximumSamples - 1) / maximumSamples);
  std::vector<Sample> samples;
  samples.reserve(opaque / stride + 1);
  size_t seen = 0;
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    const uint8_t* value = rgba + pixel * 4;
    if (value[3] < 128) {
      continue;
    }
    if (seen++ % stride == 0) {
      const Rgb8 color{value[0], value[1], value[2]};
      samples.push_back({oklabOf(color), color});
    }
  }

  std::vector<Box> boxes{measure(samples, 0, samples.size())};
  while (boxes.size() < maximumColors) {
    auto widest = std::max_element(boxes.begin(), boxes.end(), [](const Box& a, const Box& b) {
      return a.range < b.range;
    });
    if (widest->range <= 0 || widest->end - widest->begin < 2) {
      break;
    }
    const Box box = *widest;
    const auto axis = static_cast<size_t>(box.axis);
    const auto first = samples.begin() + static_cast<std::ptrdiff_t>(box.begin);
    const auto last = samples.begin() + static_cast<std::ptrdiff_t>(box.end);
    std::sort(first, last, [axis](const Sample& a, const Sample& b) { return a.lab[axis] < b.lab[axis]; });
    size_t middle = box.begin + (box.end - box.begin) / 2;
    const float value = samples[middle].lab[axis];
    size_t split = middle;
    while (split > box.begin && samples[split - 1].lab[axis] == value) {
      --split;
    }
    if (split == box.begin) {
      split = middle;
      while (split < box.end && samples[split].lab[axis] == value) {
        ++split;
      }
    }
    *widest = measure(samples, box.begin, split);
    boxes.push_back(measure(samples, split, box.end));
  }

  std::vector<std::array<float, 3>> centers(boxes.size());
  for (size_t index = 0; index < boxes.size(); ++index) {
    std::array<double, 3> sum{};
    for (size_t sample = boxes[index].begin; sample < boxes[index].end; ++sample) {
      for (size_t axis = 0; axis < 3; ++axis) {
        sum[axis] += samples[sample].lab[axis];
      }
    }
    const auto count = static_cast<double>(boxes[index].end - boxes[index].begin);
    centers[index] = {static_cast<float>(sum[0] / count), static_cast<float>(sum[1] / count),
                      static_cast<float>(sum[2] / count)};
  }
  std::vector<uint32_t> owner(samples.size());
  std::vector<std::array<double, 6>> sums(centers.size());
  std::vector<size_t> counts(centers.size());
  for (int32_t pass = 0; pass <= kMeansPasses; ++pass) {
    std::fill(sums.begin(), sums.end(), std::array<double, 6>{});
    std::fill(counts.begin(), counts.end(), 0);
    for (size_t sample = 0; sample < samples.size(); ++sample) {
      uint32_t best = 0;
      float bestDistance = std::numeric_limits<float>::max();
      for (size_t center = 0; center < centers.size(); ++center) {
        const float value = distance(samples[sample].lab, centers[center]);
        if (value < bestDistance) {
          bestDistance = value;
          best = static_cast<uint32_t>(center);
        }
      }
      owner[sample] = best;
      auto& sum = sums[best];
      for (size_t axis = 0; axis < 3; ++axis) {
        sum[axis] += samples[sample].lab[axis];
        sum[3 + axis] += samples[sample].color[axis];
      }
      ++counts[best];
    }
    if (pass == kMeansPasses) {
      break;
    }
    for (size_t center = 0; center < centers.size(); ++center) {
      if (counts[center] > 0) {
        const auto count = static_cast<double>(counts[center]);
        centers[center] = {static_cast<float>(sums[center][0] / count),
                           static_cast<float>(sums[center][1] / count),
                           static_cast<float>(sums[center][2] / count)};
      }
    }
  }
  std::vector<Rgb8> palette;
  palette.reserve(centers.size());
  for (size_t center = 0; center < centers.size(); ++center) {
    if (counts[center] == 0) {
      continue;
    }
    const auto count = static_cast<double>(counts[center]);
    palette.push_back({static_cast<uint8_t>(std::lround(sums[center][3] / count)),
                       static_cast<uint8_t>(std::lround(sums[center][4] / count)),
                       static_cast<uint8_t>(std::lround(sums[center][5] / count))});
  }
  std::sort(palette.begin(), palette.end());
  palette.erase(std::unique(palette.begin(), palette.end()), palette.end());
  return palette;
}

namespace {

size_t cellOf(int32_t red, int32_t green, int32_t blue) {
  constexpr int32_t kShift = 8 - kCacheBits;
  return static_cast<size_t>(((red >> kShift) << (2 * kCacheBits)) |
                             ((green >> kShift) << kCacheBits) | (blue >> kShift));
}

}  // namespace

PaletteMapper::PaletteMapper(const std::vector<Rgb8>& palette)
    : palette_(palette),
      cache_(size_t{1} << (3 * kCacheBits), -1),
      cellFirst_(size_t{1} << (3 * kCacheBits), -1),
      cellNext_(palette.size(), -1) {
  labs_.reserve(palette_.size());
  for (size_t index = 0; index < palette_.size(); ++index) {
    const Rgb8& color = palette_[index];
    labs_.push_back(oklabOf(color));
    int16_t& first = cellFirst_[cellOf(color[0], color[1], color[2])];
    cellNext_[index] = first;
    first = static_cast<int16_t>(index);
  }
}

uint8_t PaletteMapper::nearest(int32_t red, int32_t green, int32_t blue) {
  red = std::clamp(red, 0, 255);
  green = std::clamp(green, 0, 255);
  blue = std::clamp(blue, 0, 255);
  constexpr int32_t kShift = 8 - kCacheBits;
  const size_t key = cellOf(red, green, blue);
  for (int16_t member = cellFirst_[key]; member >= 0; member = cellNext_[static_cast<size_t>(member)]) {
    const Rgb8& color = palette_[static_cast<size_t>(member)];
    if (color[0] == red && color[1] == green && color[2] == blue) {
      return static_cast<uint8_t>(member);
    }
  }
  int16_t& cached = cache_[key];
  if (cached >= 0) {
    return static_cast<uint8_t>(cached);
  }
  constexpr int32_t kHalf = 1 << (kShift - 1);
  const Rgb8 center{static_cast<uint8_t>(((red >> kShift) << kShift) + kHalf),
                    static_cast<uint8_t>(((green >> kShift) << kShift) + kHalf),
                    static_cast<uint8_t>(((blue >> kShift) << kShift) + kHalf)};
  const auto lab = oklabOf(center);
  size_t best = 0;
  float bestDistance = std::numeric_limits<float>::max();
  for (size_t index = 0; index < labs_.size(); ++index) {
    const float value = distance(lab, labs_[index]);
    if (value < bestDistance) {
      bestDistance = value;
      best = index;
    }
  }
  cached = static_cast<int16_t>(best);
  return static_cast<uint8_t>(best);
}

}  // namespace nanquim::formats
