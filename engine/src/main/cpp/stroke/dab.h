#pragma once

#include <array>
#include <cstdint>

namespace nanquim::stroke {

struct Dab {
  double x = 0;
  double y = 0;
  double diameter = 0;
  double opacity = 0;
  double flow = 0;
  double angle = 0;
  double roundness = 1;
  std::array<double, 4> color{0, 0, 0, 1};
  bool flipX = false;
  bool flipY = false;
  uint32_t index = 0;
  double bleed = 0;
  double gradation = 0;
  double gradationAngle = 0;
  std::array<double, 2> grainOffset{0, 0};
  double grainDepth = 0;
  double grainPeriod = 256;
  double distance = 0;
  double pressure = 1;
  double timeMilliseconds = 0;
  std::array<double, 2> wetJitter{0, 0};
};

}  // namespace nanquim::stroke
