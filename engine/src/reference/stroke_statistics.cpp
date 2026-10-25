#include "stroke_statistics.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <map>

#include "test_sheet.h"

namespace nanquim::reference {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kProfilePoints = 16;
constexpr double kLineStart = 160;
constexpr double kLineEnd = 1888;
constexpr double kNoInk = 0.02;

class InkMap {
 public:
  explicit InkMap(const Rgba8Image& image)
      : width_(image.width), height_(image.height), scale_(image.width / static_cast<double>(kSheetSize)) {
    ink_.resize(static_cast<size_t>(width_) * static_cast<size_t>(height_));
    rgb_.resize(ink_.size() * 3);
    for (size_t index = 0; index < ink_.size(); ++index) {
      const uint8_t* pixel = &image.pixels[index * 4];
      const double alpha = pixel[3] / 255.0;
      double color[3];
      for (size_t channel = 0; channel < 3; ++channel) {
        color[channel] = pixel[channel] / 255.0 * alpha + (1 - alpha);
        rgb_[index * 3 + channel] = color[channel];
      }
      const double luminance = 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2];
      ink_[index] = std::clamp(1 - luminance, 0.0, 1.0);
    }
  }

  [[nodiscard]] double scale() const { return scale_; }
  [[nodiscard]] int32_t width() const { return width_; }
  [[nodiscard]] int32_t height() const { return height_; }

  [[nodiscard]] double at(double sheetX, double sheetY) const {
    return pixel(static_cast<int32_t>(std::floor(sheetX * scale_)),
                 static_cast<int32_t>(std::floor(sheetY * scale_)));
  }

  [[nodiscard]] double pixel(int32_t x, int32_t y) const {
    if (x < 0 || y < 0 || x >= width_ || y >= height_) {
      return 0;
    }
    return ink_[static_cast<size_t>(y) * static_cast<size_t>(width_) + static_cast<size_t>(x)];
  }

  [[nodiscard]] const double* rgb(int32_t x, int32_t y) const {
    return &rgb_[(static_cast<size_t>(y) * static_cast<size_t>(width_) + static_cast<size_t>(x)) * 3];
  }

  [[nodiscard]] std::vector<double> column(double sheetX, double y0, double y1) const {
    const auto x = static_cast<int32_t>(std::floor(sheetX * scale_));
    std::vector<double> values;
    for (auto y = static_cast<int32_t>(std::floor(y0 * scale_)); y < static_cast<int32_t>(std::ceil(y1 * scale_)); ++y) {
      values.push_back(pixel(x, y));
    }
    return values;
  }

 private:
  int32_t width_;
  int32_t height_;
  double scale_;
  std::vector<double> ink_;
  std::vector<double> rgb_;
};

double maximumOf(const std::vector<double>& values) {
  return values.empty() ? 0.0 : *std::max_element(values.begin(), values.end());
}

double median(std::vector<double> values) {
  if (values.empty()) {
    return 0;
  }
  std::sort(values.begin(), values.end());
  return values[values.size() / 2];
}

double halfMaximumWidth(const std::vector<double>& profile) {
  const double peak = maximumOf(profile);
  if (peak < kNoInk) {
    return 0;
  }
  return static_cast<double>(std::count_if(profile.begin(), profile.end(),
                                           [peak](double value) { return value >= peak / 2; }));
}

double edgeFalloff(const std::vector<double>& profile) {
  const double peak = maximumOf(profile);
  if (peak < kNoInk) {
    return 0;
  }
  const auto low = std::find_if(profile.begin(), profile.end(), [peak](double v) { return v >= 0.1 * peak; });
  const auto high = std::find_if(profile.begin(), profile.end(), [peak](double v) { return v >= 0.9 * peak; });
  return static_cast<double>(std::distance(low, high));
}

std::pair<double, double> widthAndPeak(const InkMap& ink, double sheetX, double y0, double y1) {
  constexpr int kColumns = 5;
  double width = 0;
  double peak = 0;
  for (int offset = -kColumns / 2; offset <= kColumns / 2; ++offset) {
    const auto profile = ink.column(sheetX + offset * 2.0, y0, y1);
    width += halfMaximumWidth(profile);
    peak += maximumOf(profile);
  }
  return {width / kColumns / ink.scale(), peak / kColumns};
}

void lineProfile(const InkMap& ink, const char* item, double y0, double y1,
                 std::vector<StrokeStatistic>& statistics) {
  for (int point = 0; point < kProfilePoints; ++point) {
    const double x = kLineStart + (kLineEnd - kLineStart) * (point + 0.5) / kProfilePoints;
    const auto [width, peak] = widthAndPeak(ink, x, y0, y1);
    char name[32];
    std::snprintf(name, sizeof name, "%s.width.%02d", item, point);
    statistics.push_back({name, width, std::max(1.5, 0.08 * width)});
    std::snprintf(name, sizeof name, "%s.opacity.%02d", item, point);
    statistics.push_back({name, peak, 0.06});
  }
}

void fft(std::vector<std::complex<double>>& values) {
  const size_t count = values.size();
  for (size_t index = 1, reversed = 0; index < count; ++index) {
    size_t bit = count >> 1U;
    for (; (reversed & bit) != 0; bit >>= 1U) {
      reversed ^= bit;
    }
    reversed ^= bit;
    if (index < reversed) {
      std::swap(values[index], values[reversed]);
    }
  }
  for (size_t length = 2; length <= count; length <<= 1U) {
    const double angle = -2 * kPi / static_cast<double>(length);
    const std::complex<double> step(std::cos(angle), std::sin(angle));
    for (size_t start = 0; start < count; start += length) {
      std::complex<double> twiddle(1, 0);
      for (size_t offset = 0; offset < length / 2; ++offset) {
        const auto even = values[start + offset];
        const auto odd = values[start + offset + length / 2] * twiddle;
        values[start + offset] = even + odd;
        values[start + offset + length / 2] = even - odd;
        twiddle *= step;
      }
    }
  }
}

std::pair<double, double> spectrum(const InkMap& ink, int32_t centerX, int32_t centerY, int32_t size) {
  std::vector<std::complex<double>> grid(static_cast<size_t>(size) * static_cast<size_t>(size));
  double mean = 0;
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      mean += ink.pixel(centerX - size / 2 + x, centerY - size / 2 + y);
    }
  }
  mean /= static_cast<double>(grid.size());
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const double window = (0.5 - 0.5 * std::cos(2 * kPi * x / (size - 1))) *
                            (0.5 - 0.5 * std::cos(2 * kPi * y / (size - 1)));
      grid[static_cast<size_t>(y * size + x)] =
          (ink.pixel(centerX - size / 2 + x, centerY - size / 2 + y) - mean) * window;
    }
  }
  std::vector<std::complex<double>> line(static_cast<size_t>(size));
  for (int32_t y = 0; y < size; ++y) {
    std::copy_n(grid.begin() + y * size, size, line.begin());
    fft(line);
    std::copy_n(line.begin(), size, grid.begin() + y * size);
  }
  for (int32_t x = 0; x < size; ++x) {
    for (int32_t y = 0; y < size; ++y) {
      line[static_cast<size_t>(y)] = grid[static_cast<size_t>(y * size + x)];
    }
    fft(line);
    for (int32_t y = 0; y < size; ++y) {
      grid[static_cast<size_t>(y * size + x)] = line[static_cast<size_t>(y)];
    }
  }
  std::vector<double> radial(static_cast<size_t>(size / 2 + 1), 0);
  double horizontal = 0;
  double vertical = 0;
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const int32_t fx = x <= size / 2 ? x : x - size;
      const int32_t fy = y <= size / 2 ? y : y - size;
      const double power = std::norm(grid[static_cast<size_t>(y * size + x)]);
      const auto radius = static_cast<size_t>(std::lround(std::hypot(fx, fy)));
      if (radius < 2 || radius >= radial.size()) {
        continue;
      }
      radial[radius] += power;
      if (std::abs(fx) > std::abs(fy)) {
        horizontal += power;
      } else if (std::abs(fy) > std::abs(fx)) {
        vertical += power;
      }
    }
  }
  const auto peak = std::max_element(radial.begin(), radial.end());
  const double total = horizontal + vertical;
  return {*peak > 0 ? static_cast<double>(peak - radial.begin()) / size : 0.0,
          total > 0 ? (horizontal - vertical) / total : 0.0};
}

double hueOf(const double* rgb) {
  const double maximum = std::max({rgb[0], rgb[1], rgb[2]});
  const double minimum = std::min({rgb[0], rgb[1], rgb[2]});
  const double chroma = maximum - minimum;
  if (chroma <= 0) {
    return 0;
  }
  double hue = 0;
  if (maximum == rgb[0]) {
    hue = std::fmod((rgb[1] - rgb[2]) / chroma + 6, 6);
  } else if (maximum == rgb[1]) {
    hue = (rgb[2] - rgb[0]) / chroma + 2;
  } else {
    hue = (rgb[0] - rgb[1]) / chroma + 4;
  }
  return hue / 6;
}

}  // namespace

std::vector<StrokeStatistic> measureSheet(const Rgba8Image& image) {
  const InkMap ink(image);
  const double scale = ink.scale();
  std::vector<StrokeStatistic> statistics;

  lineProfile(ink, "A", 64, 288, statistics);
  lineProfile(ink, "B", 320, 544, statistics);
  {
    std::vector<double> edges;
    for (int point = 4; point < 12; ++point) {
      const double x = kLineStart + (kLineEnd - kLineStart) * (point + 0.5) / kProfilePoints;
      edges.push_back(edgeFalloff(ink.column(x, 64, 288)) / scale);
    }
    const double edge = median(edges);
    statistics.push_back({"A.edge", edge, std::max(1.0, 0.15 * edge)});
  }
  {
    double sum = 0;
    double squares = 0;
    int64_t count = 0;
    for (auto y = static_cast<int32_t>(576 * scale); y < static_cast<int32_t>(928 * scale); ++y) {
      for (auto x = static_cast<int32_t>(64 * scale); x < static_cast<int32_t>(1984 * scale); ++x) {
        const double value = ink.pixel(x, y);
        if (value > 0.05) {
          sum += value;
          squares += value * value;
          ++count;
        }
      }
    }
    const double mean = count > 0 ? sum / static_cast<double>(count) : 0;
    const double variance = count > 0 ? squares / static_cast<double>(count) - mean * mean : 0;
    statistics.push_back({"C.opacityMean", mean, 0.05});
    statistics.push_back({"C.opacityVariance", variance, 0.01});
  }
  {
    std::vector<double> single;
    std::vector<double> stacked;
    for (double x = 300; x <= 760; x += 20) {
      single.push_back(maximumOf(ink.column(x, 960, 1104)));
      stacked.push_back(maximumOf(ink.column(x, 1104, 1248)));
    }
    const double one = median(single);
    statistics.push_back({"D.stacking", one > kNoInk ? median(stacked) / one : 0.0, 0.08});
  }
  {
    std::vector<double> widths;
    std::vector<double> peaks;
    for (double x = 1300; x <= 1700; x += 50) {
      const auto [width, peak] = widthAndPeak(ink, x, 1068, 1140);
      widths.push_back(width);
      peaks.push_back(peak);
    }
    const double width = median(widths);
    statistics.push_back({"E.width", width, std::max(1.5, 0.1 * width)});
    statistics.push_back({"E.opacity", median(peaks), 0.06});
  }
  {
    double diameter = 0;
    double peak = 0;
    for (int tap = 0; tap < 5; ++tap) {
      const double centerX = 160 + 160 * tap;
      double tapPeak = 0;
      std::vector<double> values;
      for (auto y = static_cast<int32_t>((1360 - 78) * scale); y < static_cast<int32_t>((1360 + 78) * scale); ++y) {
        for (auto x = static_cast<int32_t>((centerX - 78) * scale); x < static_cast<int32_t>((centerX + 78) * scale); ++x) {
          values.push_back(ink.pixel(x, y));
          tapPeak = std::max(tapPeak, ink.pixel(x, y));
        }
      }
      const auto area = static_cast<double>(std::count_if(values.begin(), values.end(),
                                                          [tapPeak](double v) { return tapPeak >= kNoInk && v >= tapPeak / 2; }));
      diameter += 2 * std::sqrt(area / kPi) / scale;
      peak += tapPeak;
    }
    statistics.push_back({"F.tapDiameter", diameter / 5, std::max(1.5, 0.1 * diameter / 5)});
    statistics.push_back({"F.tapOpacity", peak / 5, 0.06});
  }
  {
    const int32_t window = scale >= 0.5 ? 128 : 64;
    const auto [frequency, anisotropy] =
        spectrum(ink, static_cast<int32_t>(1025 * scale), static_cast<int32_t>(1660 * scale), window);
    statistics.push_back({"G.textureFrequency", frequency * scale, 0.01});
    statistics.push_back({"G.textureAnisotropy", anisotropy, 0.15});
    std::vector<double> widths;
    for (double x = 400; x <= 1600; x += 100) {
      widths.push_back(widthAndPeak(ink, x, 1890, 1975).first);
    }
    const double width = median(widths);
    statistics.push_back({"G.minimumWidth", width, std::max(1.0, 0.15 * width)});
  }
  {
    std::vector<double> hues;
    double saturation = 0;
    for (auto y = static_cast<int32_t>(1280 * scale); y < static_cast<int32_t>(1440 * scale); ++y) {
      for (auto x = static_cast<int32_t>(1048 * scale); x < static_cast<int32_t>(1984 * scale); ++x) {
        if (ink.pixel(x, y) < 0.2) {
          continue;
        }
        const double* rgb = ink.rgb(x, y);
        const double maximum = std::max({rgb[0], rgb[1], rgb[2]});
        const double minimum = std::min({rgb[0], rgb[1], rgb[2]});
        saturation += maximum > 0 ? (maximum - minimum) / maximum : 0;
        hues.push_back(hueOf(rgb));
      }
    }
    double sine = 0;
    double cosine = 0;
    for (const double hue : hues) {
      sine += std::sin(2 * kPi * hue);
      cosine += std::cos(2 * kPi * hue);
    }
    const double length = hues.empty() ? 1 : std::hypot(sine, cosine) / static_cast<double>(hues.size());
    const double deviation = std::sqrt(std::max(0.0, -2 * std::log(std::max(length, 1e-12)))) / (2 * kPi);
    statistics.push_back({"I.hueDeviation", deviation, 0.02});
    statistics.push_back({"I.saturation", hues.empty() ? 0 : saturation / static_cast<double>(hues.size()), 0.05});
  }
  return statistics;
}

StatisticsComparison compareStatistics(const std::vector<StrokeStatistic>& target,
                                       const std::vector<StrokeStatistic>& actual) {
  std::map<std::string, double> actualByName;
  for (const StrokeStatistic& statistic : actual) {
    actualByName[statistic.name] = statistic.value;
  }
  StatisticsComparison comparison;
  double squares = 0;
  for (const StrokeStatistic& statistic : target) {
    const auto found = actualByName.find(statistic.name);
    if (found == actualByName.end()) {
      continue;
    }
    const double difference = (found->second - statistic.value) / statistic.tolerance;
    comparison.entries.push_back({statistic.name, statistic.value, found->second, difference});
    squares += difference * difference;
  }
  comparison.distance = comparison.entries.empty()
                            ? 0
                            : std::sqrt(squares / static_cast<double>(comparison.entries.size()));
  return comparison;
}

}  // namespace nanquim::reference
