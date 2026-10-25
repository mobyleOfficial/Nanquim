#pragma once

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "core/half_float.h"

namespace nanquim::canvas {

inline double srgbToLinearValue(double value) {
  return value <= 0.04045 ? value / 12.92 : std::pow((value + 0.055) / 1.055, 2.4);
}

inline double linearToSrgbValue(double value) {
  return value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
}

inline float documentChannel(double srgb, bool linear) {
  return static_cast<float>(linear ? srgbToLinearValue(srgb) : srgb);
}

inline uint16_t premultipliedSrgb8ChannelToLinearHalf(uint8_t value, uint8_t alphaByte) {
  const double alpha = alphaByte / 255.0;
  const double encoded = alpha > 0 ? std::fmin(1.0, value / 255.0 / alpha) : 0.0;
  return core::floatToHalf(static_cast<float>(srgbToLinearValue(encoded) * alpha));
}

inline const std::array<uint16_t, 65536>& srgb8ToLinearHalfTable() {
  static const std::array<uint16_t, 65536> table = [] {
    std::array<uint16_t, 65536> values{};
    for (size_t index = 0; index < values.size(); ++index) {
      values[index] = premultipliedSrgb8ChannelToLinearHalf(static_cast<uint8_t>(index & 0xFFU),
                                                            static_cast<uint8_t>(index >> 8U));
    }
    return values;
  }();
  return table;
}

inline void premultipliedSrgb8ToLinearHalf(const uint8_t* source, uint8_t* destination,
                                           size_t pixelCount) {
  const auto& table = srgb8ToLinearHalfTable();
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    const uint8_t* in = source + pixel * 4;
    const size_t row = static_cast<size_t>(in[3]) << 8U;
    const std::array<uint16_t, 4> out{table[row + in[0]], table[row + in[1]], table[row + in[2]],
                                      core::floatToHalf(static_cast<float>(in[3] / 255.0))};
    std::memcpy(destination + pixel * 8, out.data(), 8);
  }
}

inline void premultipliedLinearToSrgb8(const float* linear, uint8_t* encoded) {
  const double alpha = std::fmin(std::fmax(static_cast<double>(linear[3]), 0.0), 1.0);
  for (size_t channel = 0; channel < 3; ++channel) {
    const double color =
        alpha > 0 ? std::fmin(std::fmax(linear[channel] / alpha, 0.0), 1.0) : 0.0;
    encoded[channel] = static_cast<uint8_t>(std::lround(linearToSrgbValue(color) * alpha * 255.0));
  }
  encoded[3] = static_cast<uint8_t>(std::lround(alpha * 255.0));
}

inline void premultipliedLinearHalfToSrgb8(const uint8_t* source, uint8_t* destination,
                                           size_t pixelCount) {
  static const std::array<uint8_t, 65536> opaque = [] {
    std::array<uint8_t, 65536> values{};
    for (size_t bits = 0; bits < values.size(); ++bits) {
      const std::array<float, 4> linear{core::halfToFloat(static_cast<uint16_t>(bits)), 0, 0, 1};
      std::array<uint8_t, 4> encoded{};
      premultipliedLinearToSrgb8(linear.data(), encoded.data());
      values[bits] = encoded[0];
    }
    return values;
  }();
  constexpr uint16_t kHalfOne = 0x3C00;
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    std::array<uint16_t, 4> in{};
    std::memcpy(in.data(), source + pixel * 8, 8);
    uint8_t* out = destination + pixel * 4;
    if ((in[3] & 0x7FFFU) == 0 || (in[3] & 0x8000U) != 0) {
      std::memset(out, 0, 4);
    } else if (in[3] == kHalfOne) {
      out[0] = opaque[in[0]];
      out[1] = opaque[in[1]];
      out[2] = opaque[in[2]];
      out[3] = 255;
    } else {
      const std::array<float, 4> linear{core::halfToFloat(in[0]), core::halfToFloat(in[1]),
                                        core::halfToFloat(in[2]), core::halfToFloat(in[3])};
      premultipliedLinearToSrgb8(linear.data(), out);
    }
  }
}

inline void premultipliedLinearHalfToStraightSrgb16(const uint8_t* source, uint16_t* destination,
                                                    size_t pixelCount) {
  const auto encode = [](double linear) {
    const double clamped = std::isnan(linear) ? 0.0 : std::fmin(std::fmax(linear, 0.0), 1.0);
    return static_cast<uint16_t>(std::lround(linearToSrgbValue(clamped) * 65535.0));
  };
  static const std::array<uint16_t, 65536> opaque = [&encode] {
    std::array<uint16_t, 65536> values{};
    for (size_t bits = 0; bits < values.size(); ++bits) {
      values[bits] = encode(core::halfToFloat(static_cast<uint16_t>(bits)));
    }
    return values;
  }();
  constexpr uint16_t kHalfOne = 0x3C00;
  for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
    std::array<uint16_t, 4> in{};
    std::memcpy(in.data(), source + pixel * 8, 8);
    uint16_t* out = destination + pixel * 4;
    const double alpha = core::halfToFloat(in[3]);
    if (!(alpha > 0.0)) {
      out[0] = out[1] = out[2] = out[3] = 0;
    } else if (in[3] == kHalfOne) {
      out[0] = opaque[in[0]];
      out[1] = opaque[in[1]];
      out[2] = opaque[in[2]];
      out[3] = 65535;
    } else {
      const double clampedAlpha = std::fmin(alpha, 1.0);
      for (size_t channel = 0; channel < 3; ++channel) {
        out[channel] = encode(core::halfToFloat(in[channel]) / clampedAlpha);
      }
      out[3] = static_cast<uint16_t>(std::lround(clampedAlpha * 65535.0));
    }
  }
}

inline void rgba8ToHalf(const uint8_t* source, uint8_t* destination, size_t pixelCount) {
  for (size_t index = 0; index < pixelCount * 4; ++index) {
    const uint16_t half = core::floatToHalf(static_cast<float>(source[index]) / 255.0F);
    std::memcpy(destination + index * 2, &half, 2);
  }
}

}  // namespace nanquim::canvas
