#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace nanquim::formats {

std::vector<uint8_t> srgbIccProfile();

std::array<std::array<double, 3>, 3> srgbD50Colorants();

}  // namespace nanquim::formats
