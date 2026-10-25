#include "adjust/adjustment.h"

#include <algorithm>
#include <cmath>

namespace nanquim::adjust {

namespace {

constexpr size_t kCurveCount = 5;
constexpr size_t kMaximumCurvePoints = 16;
constexpr size_t kMaximumGradientStops = 64;
constexpr double kColorBalanceStrength = 0.5;

double smoothStep(double edge0, double edge1, double value) {
  const double t = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
  return t * t * (3 - 2 * t);
}

std::optional<Table> bakeCurves(const std::vector<float>& data) {
  std::array<std::vector<std::array<double, 2>>, kCurveCount> curves;
  size_t offset = 0;
  for (auto& curve : curves) {
    if (offset >= data.size()) {
      return std::nullopt;
    }
    const auto count = static_cast<size_t>(data[offset++]);
    if (count < 1 || count > kMaximumCurvePoints || offset + count * 2 > data.size()) {
      return std::nullopt;
    }
    for (size_t index = 0; index < count; ++index) {
      const double x = data[offset++];
      const double y = data[offset++];
      if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1) || (!curve.empty() && x <= curve.back()[0])) {
        return std::nullopt;
      }
      curve.push_back({x, y});
    }
  }
  if (offset != data.size()) {
    return std::nullopt;
  }
  Table table(kTableSize * 4);
  for (size_t entry = 0; entry < kTableSize; ++entry) {
    const double t = static_cast<double>(entry) / (kTableSize - 1);
    const double master = monotoneCurve(curves[0], t);
    for (size_t channel = 0; channel < 3; ++channel) {
      table[entry * 4 + channel] = static_cast<float>(monotoneCurve(curves[channel + 1], master));
    }
    table[entry * 4 + 3] = static_cast<float>(monotoneCurve(curves[4], t));
  }
  return table;
}

std::optional<Table> bakeGradient(const std::vector<float>& data) {
  if (data.empty()) {
    return std::nullopt;
  }
  const auto count = static_cast<size_t>(data[0]);
  if (count < 1 || count > kMaximumGradientStops || data.size() != 1 + count * 4) {
    return std::nullopt;
  }
  std::vector<std::array<double, 4>> stops;
  for (size_t index = 0; index < count; ++index) {
    std::array<double, 4> stop{};
    for (size_t component = 0; component < 4; ++component) {
      stop[component] = data[1 + index * 4 + component];
      if (!(stop[component] >= 0 && stop[component] <= 1)) {
        return std::nullopt;
      }
    }
    if (!stops.empty() && stop[0] < stops.back()[0]) {
      return std::nullopt;
    }
    stops.push_back(stop);
  }
  Table table(kTableSize * 4);
  for (size_t entry = 0; entry < kTableSize; ++entry) {
    const double t = static_cast<double>(entry) / (kTableSize - 1);
    size_t after = 0;
    while (after < stops.size() && stops[after][0] <= t) {
      ++after;
    }
    std::array<double, 3> color{};
    if (after == 0) {
      color = {stops.front()[1], stops.front()[2], stops.front()[3]};
    } else if (after == stops.size()) {
      color = {stops.back()[1], stops.back()[2], stops.back()[3]};
    } else {
      const auto& from = stops[after - 1];
      const auto& to = stops[after];
      const double span = to[0] - from[0];
      const double mix = span > 0 ? (t - from[0]) / span : 1;
      for (size_t channel = 0; channel < 3; ++channel) {
        color[channel] = from[channel + 1] + (to[channel + 1] - from[channel + 1]) * mix;
      }
    }
    for (size_t channel = 0; channel < 3; ++channel) {
      table[entry * 4 + channel] = static_cast<float>(color[channel]);
    }
    table[entry * 4 + 3] = 1;
  }
  return table;
}

}  // namespace

std::vector<std::string_view> parameterNames(Filter filter) {
  switch (filter) {
    case Filter::HueSaturationBrightness:
      return {"hue", "saturation", "brightness"};
    case Filter::ColorBalance:
      return {"shadowsCyanRed",    "shadowsMagentaGreen",    "shadowsYellowBlue",
              "midtonesCyanRed",   "midtonesMagentaGreen",   "midtonesYellowBlue",
              "highlightsCyanRed", "highlightsMagentaGreen", "highlightsYellowBlue"};
    case Filter::Curves:
      return {};
    case Filter::GradientMap:
      return {"amount"};
    case Filter::GaussianBlur:
      return {"radius"};
    case Filter::MotionBlur:
      return {"amount", "angle"};
    case Filter::PerspectiveBlur:
      return {"amount", "x", "y", "directional", "angle"};
    case Filter::Sharpen:
      return {"amount"};
    case Filter::Noise:
      return {"amount", "scale", "octaves", "turbulence", "type"};
    case Filter::Bloom:
      return {"size", "transition", "burn"};
    case Filter::Glitch:
      return {"amount", "blockSize", "zoom", "frequency", "type"};
    case Filter::Halftone:
      return {"dotSize", "style"};
    case Filter::ChromaticAberration:
      return {"amount", "x", "y", "displace", "angle", "falloff", "transparency"};
    case Filter::Liquify:
      return {"size", "pressure", "distortion", "momentum", "adjust", "liquifyMode"};
  }
  return {};
}

std::string_view filterName(Filter filter) {
  switch (filter) {
    case Filter::HueSaturationBrightness:
      return "hueSaturationBrightness";
    case Filter::ColorBalance:
      return "colorBalance";
    case Filter::Curves:
      return "curves";
    case Filter::GradientMap:
      return "gradientMap";
    case Filter::GaussianBlur:
      return "gaussianBlur";
    case Filter::MotionBlur:
      return "motionBlur";
    case Filter::PerspectiveBlur:
      return "perspectiveBlur";
    case Filter::Sharpen:
      return "sharpen";
    case Filter::Noise:
      return "noise";
    case Filter::Bloom:
      return "bloom";
    case Filter::Glitch:
      return "glitch";
    case Filter::Halftone:
      return "halftone";
    case Filter::ChromaticAberration:
      return "chromaticAberration";
    case Filter::Liquify:
      return "liquify";
  }
  return "";
}

std::optional<Filter> filterFromName(std::string_view name) {
  for (const Filter filter :
       {Filter::HueSaturationBrightness, Filter::ColorBalance, Filter::Curves, Filter::GradientMap,
        Filter::GaussianBlur, Filter::MotionBlur, Filter::PerspectiveBlur, Filter::Sharpen,
        Filter::Noise, Filter::Bloom, Filter::Glitch, Filter::Halftone, Filter::ChromaticAberration,
        Filter::Liquify}) {
    if (filterName(filter) == name) {
      return filter;
    }
  }
  return std::nullopt;
}

bool usesTable(Filter filter) { return filter == Filter::Curves || filter == Filter::GradientMap; }

std::optional<Table> bakeTable(Filter filter, const std::vector<float>& data) {
  switch (filter) {
    case Filter::Curves:
      return bakeCurves(data);
    case Filter::GradientMap:
      return bakeGradient(data);
    default:
      return std::nullopt;
  }
}

std::vector<float> defaultTableData(Filter filter) {
  switch (filter) {
    case Filter::Curves: {
      std::vector<float> data;
      for (size_t curve = 0; curve < kCurveCount; ++curve) {
        data.insert(data.end(), {2, 0, 0, 1, 1});
      }
      return data;
    }
    case Filter::GradientMap:
      return {2, 0, 0, 0, 0, 1, 1, 1, 1};
    default:
      return {};
  }
}

double monotoneCurve(const std::vector<std::array<double, 2>>& points, double x) {
  if (points.empty()) {
    return std::clamp(x, 0.0, 1.0);
  }
  if (points.size() == 1 || x <= points.front()[0]) {
    return points.front()[1];
  }
  if (x >= points.back()[0]) {
    return points.back()[1];
  }
  const size_t count = points.size();
  std::vector<double> secants(count - 1);
  for (size_t index = 0; index + 1 < count; ++index) {
    secants[index] =
        (points[index + 1][1] - points[index][1]) / (points[index + 1][0] - points[index][0]);
  }
  std::vector<double> tangents(count);
  tangents.front() = secants.front();
  tangents.back() = secants.back();
  for (size_t index = 1; index + 1 < count; ++index) {
    tangents[index] =
        secants[index - 1] * secants[index] <= 0 ? 0 : (secants[index - 1] + secants[index]) / 2;
  }
  for (size_t index = 0; index + 1 < count; ++index) {
    if (secants[index] == 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      continue;
    }
    const double a = tangents[index] / secants[index];
    const double b = tangents[index + 1] / secants[index];
    const double length = a * a + b * b;
    if (length > 9) {
      const double scale = 3 / std::sqrt(length);
      tangents[index] = scale * a * secants[index];
      tangents[index + 1] = scale * b * secants[index];
    }
  }
  size_t segment = 0;
  while (x > points[segment + 1][0]) {
    ++segment;
  }
  const double width = points[segment + 1][0] - points[segment][0];
  const double t = (x - points[segment][0]) / width;
  const double t2 = t * t;
  const double t3 = t2 * t;
  const double value =
      (2 * t3 - 3 * t2 + 1) * points[segment][1] + (t3 - 2 * t2 + t) * width * tangents[segment] +
      (-2 * t3 + 3 * t2) * points[segment + 1][1] + (t3 - t2) * width * tangents[segment + 1];
  return std::clamp(value, 0.0, 1.0);
}

double lookup(const Table& table, size_t channel, double value) {
  const double position = std::clamp(value, 0.0, 1.0) * (kTableSize - 1);
  const auto index = std::min(static_cast<size_t>(position), kTableSize - 2);
  const double fraction = position - static_cast<double>(index);
  return table[index * 4 + channel] * (1 - fraction) + table[(index + 1) * 4 + channel] * fraction;
}

double luminance(const std::array<double, 3>& color) {
  return 0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2];
}

std::array<double, 3> hsvFromRgb(const std::array<double, 3>& color) {
  const double red = color[0];
  const double green = color[1];
  const double blue = color[2];
  const double maximum = std::max({red, green, blue});
  const double minimum = std::min({red, green, blue});
  const double delta = maximum - minimum;
  double hue = 0;
  if (delta > 0) {
    if (maximum == red) {
      hue = (green - blue) / delta;
      if (hue < 0) {
        hue += 6;
      }
    } else if (maximum == green) {
      hue = (blue - red) / delta + 2;
    } else {
      hue = (red - green) / delta + 4;
    }
    hue /= 6;
  }
  const double saturation = maximum > 0 ? delta / maximum : 0;
  return {hue, saturation, maximum};
}

std::array<double, 3> rgbFromHsv(const std::array<double, 3>& hsv) {
  std::array<double, 3> color{};
  const std::array<double, 3> offsets{5, 3, 1};
  for (size_t channel = 0; channel < 3; ++channel) {
    double k = std::fmod(offsets[channel] + hsv[0] * 6, 6.0);
    if (k < 0) {
      k += 6;
    }
    const double ramp = std::clamp(std::min(k, 4 - k), 0.0, 1.0);
    color[channel] = hsv[2] - hsv[2] * hsv[1] * ramp;
  }
  return color;
}

std::array<double, 3> hueSaturationBrightness(const std::array<double, 3>& color, double hue,
                                              double saturation, double brightness) {
  std::array<double, 3> hsv = hsvFromRgb(color);
  hsv[0] += hue * 0.5;
  hsv[0] -= std::floor(hsv[0]);
  hsv[1] = std::clamp(hsv[1] * (1 + saturation), 0.0, 1.0);
  std::array<double, 3> result = rgbFromHsv(hsv);
  for (double& channel : result) {
    channel = brightness >= 0 ? channel + (1 - channel) * brightness : channel * (1 + brightness);
  }
  return result;
}

std::array<double, 3> colorBalance(const std::array<double, 3>& color,
                                   const std::array<float, kParameterCount>& shifts) {
  const double light = luminance(color);
  const double shadows = 1 - smoothStep(0, 0.5, light);
  const double highlights = smoothStep(0.5, 1, light);
  const double midtones = 1 - shadows - highlights;
  std::array<double, 3> result{};
  for (size_t channel = 0; channel < 3; ++channel) {
    const double shift = shadows * shifts[channel] + midtones * shifts[3 + channel] +
                         highlights * shifts[6 + channel];
    result[channel] = std::clamp(color[channel] + shift * kColorBalanceStrength, 0.0, 1.0);
  }
  return result;
}

uint32_t hash32(int32_t x, int32_t y, uint32_t seed) {
  uint32_t value = static_cast<uint32_t>(x) * 0x8da6b343U ^ static_cast<uint32_t>(y) * 0xd8163841U ^
                   seed * 0xcb1ab31fU;
  value ^= value >> 13;
  value *= 0x5bd1e995U;
  value ^= value >> 15;
  return value;
}

double hashUnit(int32_t x, int32_t y, uint32_t seed) {
  return static_cast<double>(hash32(x, y, seed)) / 4294967296.0;
}

double valueNoise(double x, double y, uint32_t seed) {
  const double left = std::floor(x);
  const double top = std::floor(y);
  const auto column = static_cast<int32_t>(left);
  const auto row = static_cast<int32_t>(top);
  auto fade = [](double t) { return t * t * t * (t * (t * 6 - 15) + 10); };
  const double u = fade(x - left);
  const double v = fade(y - top);
  const double upper = hashUnit(column, row, seed) +
                       (hashUnit(column + 1, row, seed) - hashUnit(column, row, seed)) * u;
  const double lower = hashUnit(column, row + 1, seed) +
                       (hashUnit(column + 1, row + 1, seed) - hashUnit(column, row + 1, seed)) * u;
  return upper + (lower - upper) * v;
}

double noiseAt(const Parameters& parameters, const PixelPosition& position) {
  const auto& values = parameters.values;
  const double scale = std::clamp(static_cast<double>(values[1]), 0.0, 1.0);
  const int32_t octaves =
      1 +
      static_cast<int32_t>(std::lround(std::clamp(static_cast<double>(values[2]), 0.0, 1.0) * 7));
  const double turbulence = std::clamp(static_cast<double>(values[3]), 0.0, 1.0);
  const auto type = static_cast<int32_t>(std::lround(values[4]));
  const double wavelength = std::max(
      (8 + 1016 * scale * scale) * std::max(position.width, position.height) / 4096.0, 2.0);
  double x = (position.x + 0.5) / wavelength;
  double y = (position.y + 0.5) / wavelength;
  if (turbulence > 0) {
    const double warpX = valueNoise(x + 5.2, y + 1.3, 101) - 0.5;
    const double warpY = valueNoise(x + 1.7, y + 9.2, 202) - 0.5;
    x += turbulence * 2 * warpX;
    y += turbulence * 2 * warpY;
  }
  double sum = 0;
  double total = 0;
  double weight = 1;
  double frequency = 1;
  for (int32_t octave = 0; octave < octaves; ++octave) {
    double value = valueNoise(x * frequency, y * frequency, static_cast<uint32_t>(octave));
    if (type == 1) {
      value = std::abs(2 * value - 1);
    } else if (type == 2) {
      value = 1 - std::abs(2 * value - 1);
    }
    sum += weight * value;
    total += weight;
    weight *= 0.5;
    frequency *= 2;
  }
  return sum / total;
}

double overlay(double base, double blend) {
  return base < 0.5 ? 2 * base * blend : 1 - 2 * (1 - base) * (1 - blend);
}

std::array<double, 4> filterColor(const Parameters& parameters, const std::array<double, 4>& color,
                                  const PixelPosition& position) {
  const auto& values = parameters.values;
  const std::array<double, 3> rgb{color[0], color[1], color[2]};
  std::array<double, 3> result = rgb;
  double alpha = color[3];
  switch (parameters.filter) {
    case Filter::HueSaturationBrightness:
      result = hueSaturationBrightness(rgb, values[0], values[1], values[2]);
      break;
    case Filter::ColorBalance:
      result = colorBalance(rgb, values);
      break;
    case Filter::Curves:
      if (parameters.table) {
        for (size_t channel = 0; channel < 3; ++channel) {
          result[channel] = lookup(*parameters.table, channel, rgb[channel]);
        }
        alpha = lookup(*parameters.table, 3, alpha);
      }
      break;
    case Filter::GradientMap:
      if (parameters.table) {
        const double light = luminance(rgb);
        for (size_t channel = 0; channel < 3; ++channel) {
          result[channel] =
              rgb[channel] + (lookup(*parameters.table, channel, light) - rgb[channel]) * values[0];
        }
      }
      break;
    case Filter::Noise: {
      const double noise = noiseAt(parameters, position);
      const double amount = std::clamp(static_cast<double>(values[0]), 0.0, 1.0);
      for (size_t channel = 0; channel < 3; ++channel) {
        result[channel] = rgb[channel] + (overlay(rgb[channel], noise) - rgb[channel]) * amount;
      }
      break;
    }
    default:
      break;
  }
  return {result[0], result[1], result[2], alpha};
}

std::array<double, 4> applyToPixel(const Parameters& parameters,
                                   const std::array<double, 4>& premultiplied, double weight,
                                   const PixelPosition& position) {
  const double alpha = premultiplied[3];
  if (alpha <= 0 || weight <= 0) {
    return premultiplied;
  }
  std::array<double, 4> color{0, 0, 0, alpha};
  for (size_t channel = 0; channel < 3; ++channel) {
    color[channel] = std::clamp(premultiplied[channel] / alpha, 0.0, 1.0);
  }
  const std::array<double, 4> filtered = filterColor(parameters, color, position);
  const double filteredAlpha = std::clamp(filtered[3], 0.0, 1.0);
  std::array<double, 4> result = premultiplied;
  for (size_t channel = 0; channel < 4; ++channel) {
    const double value =
        channel == 3 ? filteredAlpha : std::clamp(filtered[channel], 0.0, 1.0) * filteredAlpha;
    result[channel] = premultiplied[channel] + (value - premultiplied[channel]) * weight;
  }
  return result;
}

}  // namespace nanquim::adjust
