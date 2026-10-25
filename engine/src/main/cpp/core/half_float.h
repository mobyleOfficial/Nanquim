#pragma once

#include <cstdint>
#include <cstring>

namespace nanquim::core {

inline uint16_t floatToHalf(float value) {
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  const auto sign = static_cast<uint16_t>((bits >> 16U) & 0x8000U);
  const uint32_t exponent = (bits >> 23U) & 0xFFU;
  uint32_t mantissa = bits & 0x7FFFFFU;
  if (exponent == 0xFFU) {
    return static_cast<uint16_t>(sign | 0x7C00U | (mantissa != 0 ? 0x200U : 0U));
  }
  const int32_t halfExponent = static_cast<int32_t>(exponent) - 127 + 15;
  if (halfExponent >= 0x1F) {
    return static_cast<uint16_t>(sign | 0x7C00U);
  }
  if (halfExponent <= 0) {
    if (halfExponent < -10) {
      return sign;
    }
    mantissa |= 0x800000U;
    const auto shift = static_cast<uint32_t>(14 - halfExponent);
    uint32_t half = mantissa >> shift;
    const uint32_t remainder = mantissa & ((1U << shift) - 1U);
    const uint32_t halfway = 1U << (shift - 1U);
    if (remainder > halfway || (remainder == halfway && (half & 1U) != 0)) {
      ++half;
    }
    return static_cast<uint16_t>(sign | half);
  }
  auto half = static_cast<uint32_t>((static_cast<uint32_t>(halfExponent) << 10U) | (mantissa >> 13U));
  const uint32_t remainder = mantissa & 0x1FFFU;
  if (remainder > 0x1000U || (remainder == 0x1000U && (half & 1U) != 0)) {
    ++half;
  }
  return static_cast<uint16_t>(sign | half);
}

inline float halfToFloat(uint16_t half) {
  const uint32_t sign = (static_cast<uint32_t>(half) & 0x8000U) << 16U;
  const uint32_t exponent = (half >> 10U) & 0x1FU;
  uint32_t mantissa = half & 0x3FFU;
  uint32_t bits = 0;
  if (exponent == 0) {
    if (mantissa == 0) {
      bits = sign;
    } else {
      int32_t shift = 0;
      while ((mantissa & 0x400U) == 0) {
        mantissa <<= 1U;
        ++shift;
      }
      mantissa &= 0x3FFU;
      bits = sign | (static_cast<uint32_t>(127 - 15 - shift + 1) << 23U) | (mantissa << 13U);
    }
  } else if (exponent == 0x1F) {
    bits = sign | 0x7F800000U | (mantissa << 13U);
  } else {
    bits = sign | ((exponent - 15 + 127) << 23U) | (mantissa << 13U);
  }
  float value = 0;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

}  // namespace nanquim::core
