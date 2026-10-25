#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "stroke/input_sample.h"

namespace nanquim::stroke::testing {

inline constexpr double kReferencePixelsPerMeter = 9584;
inline constexpr double kTwoMetersPerSecondPixels = 2 * kReferencePixelsPerMeter;

inline std::vector<InputSample> wavyStroke(double speedPixelsPerSecond, double sampleRateHertz,
                                           double lengthPixels, double amplitudePixels = 120) {
  constexpr double kPi = 3.14159265358979323846;
  std::vector<InputSample> samples;
  const double durationSeconds = lengthPixels / speedPixelsPerSecond;
  const auto count = static_cast<int32_t>(std::ceil(durationSeconds * sampleRateHertz)) + 1;
  for (int32_t index = 0; index < count; ++index) {
    const double seconds = std::min(index / sampleRateHertz, durationSeconds);
    const double progress = seconds / durationSeconds;
    InputSample sample;
    sample.x = static_cast<float>(100 + progress * lengthPixels);
    sample.y = static_cast<float>(400 + amplitudePixels * std::sin(progress * 4 * kPi));
    sample.pressure = static_cast<float>(0.2 + 0.8 * std::sin(progress * kPi));
    sample.eventTimeNanos = static_cast<int64_t>(seconds * 1e9);
    sample.toolType = kToolTypeStylus;
    samples.push_back(sample);
  }
  return samples;
}

}  // namespace nanquim::stroke::testing
