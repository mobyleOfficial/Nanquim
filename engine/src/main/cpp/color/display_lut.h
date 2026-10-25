#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace nanquim::color {

enum class DisplaySpace { Srgb, DisplayP3 };

inline constexpr int32_t kDisplayLutSize = 33;

[[nodiscard]] inline bool needsDisplayLut(const std::vector<uint8_t>& documentProfile,
                                          const std::vector<uint8_t>& proofProfile,
                                          DisplaySpace display) {
  return !documentProfile.empty() || !proofProfile.empty() || display != DisplaySpace::Srgb;
}

std::vector<uint16_t> buildDisplayLut(const std::vector<uint8_t>& documentProfile,
                                      const std::vector<uint8_t>& proofProfile,
                                      DisplaySpace display, std::string& error);

}  // namespace nanquim::color
