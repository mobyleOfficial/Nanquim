#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::reference {

struct ProceduralTexture {
  int32_t size = 0;
  std::vector<uint8_t> pixels;
};

std::optional<ProceduralTexture> generateTexture(const std::string& kind, int32_t size,
                                                 uint64_t seed, double parameter,
                                                 std::string& error);

}  // namespace nanquim::reference
