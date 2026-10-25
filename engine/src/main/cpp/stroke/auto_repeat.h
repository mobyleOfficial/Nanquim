#pragma once

#include <cstdint>
#include <functional>
#include <vector>

namespace nanquim::stroke {

struct AutoRepeatParameters {
  int32_t outputSize = 0;
  double patchScale = 0.25;
  double seamOverlap = 0.25;
  bool mirroring = false;
  bool ignoreLowBands = false;
  double maskHardness = 0.5;
  bool pyramidBlending = false;
  uint64_t seed = 1;
};

struct AutoRepeatResult {
  int32_t side = 0;
  std::vector<uint8_t> pixels;
};

AutoRepeatResult autoRepeat(const std::vector<uint8_t>& pixels, int32_t width, int32_t height,
                            const AutoRepeatParameters& parameters,
                            const std::function<bool(double)>& progress = {});

}  // namespace nanquim::stroke
