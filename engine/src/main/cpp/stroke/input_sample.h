#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace nanquim::stroke {

inline constexpr int32_t kSampleHistorical = 1 << 0;
inline constexpr int32_t kSamplePredicted = 1 << 1;
inline constexpr int32_t kSampleHover = 1 << 2;
inline constexpr int32_t kSampleCanceled = 1 << 3;

inline constexpr int32_t kToolTypeFinger = 1;
inline constexpr int32_t kToolTypeStylus = 2;
inline constexpr int32_t kToolTypeMouse = 3;
inline constexpr int32_t kToolTypeEraser = 4;

struct InputSample {
  float x = 0;
  float y = 0;
  float pressure = 0;
  float tilt = 0;
  float orientation = 0;
  float distance = 0;
  int64_t eventTimeNanos = 0;
  int32_t toolType = 0;
  int32_t buttonState = 0;
  int32_t flags = 0;
  int32_t pointerId = 0;
};

inline constexpr size_t kPackedSampleBytes = 48;

void encodePackedSample(const InputSample& sample, uint8_t* destination);
InputSample decodePackedSample(const uint8_t* source);

bool decodePackedSamples(const uint8_t* data, size_t byteCount, std::vector<InputSample>& samples);

inline bool isPathSample(const InputSample& sample) {
  return (sample.flags & (kSamplePredicted | kSampleHover)) == 0;
}

inline bool isCanceledSample(const InputSample& sample) {
  return (sample.flags & kSampleCanceled) != 0;
}

}  // namespace nanquim::stroke
