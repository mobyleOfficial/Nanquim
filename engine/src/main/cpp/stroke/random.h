#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace nanquim::stroke {

class Random {
 public:
  using State = std::array<uint32_t, 4>;

  explicit Random(uint64_t seed) {
    for (size_t index = 0; index < state_.size(); index += 2) {
      const uint64_t value = splitMix64(seed);
      state_[index] = static_cast<uint32_t>(value);
      state_[index + 1] = static_cast<uint32_t>(value >> 32U);
    }
  }

  [[nodiscard]] const State& state() const { return state_; }
  void restore(const State& state) { state_ = state; }

  uint32_t nextUint32() {
    const uint32_t result = rotateLeft(state_[1] * 5U, 7) * 9U;
    const uint32_t shifted = state_[1] << 9U;
    state_[2] ^= state_[0];
    state_[3] ^= state_[1];
    state_[1] ^= state_[2];
    state_[0] ^= state_[3];
    state_[2] ^= shifted;
    state_[3] = rotateLeft(state_[3], 11);
    return result;
  }

  double nextUnit() { return static_cast<double>(nextUint32() >> 8U) * (1.0 / 16777216.0); }

  double uniform(double minimum, double maximum) {
    return minimum + (maximum - minimum) * nextUnit();
  }

  static uint64_t splitMix64(uint64_t& state) {
    state += 0x9E3779B97F4A7C15ULL;
    uint64_t value = state;
    value = (value ^ (value >> 30U)) * 0xBF58476D1CE4E5B9ULL;
    value = (value ^ (value >> 27U)) * 0x94D049BB133111EBULL;
    return value ^ (value >> 31U);
  }

 private:
  static uint32_t rotateLeft(uint32_t value, int bits) {
    return (value << static_cast<uint32_t>(bits)) | (value >> static_cast<uint32_t>(32 - bits));
  }

  State state_{};
};

inline uint64_t strokeSeed(std::string_view documentId, uint64_t strokeIndex) {
  uint64_t hash = 0xCBF29CE484222325ULL;
  for (const char character : documentId) {
    hash ^= static_cast<uint8_t>(character);
    hash *= 0x100000001B3ULL;
  }
  uint64_t state = hash ^ (strokeIndex * 0x9E3779B97F4A7C15ULL);
  return Random::splitMix64(state);
}

}  // namespace nanquim::stroke
