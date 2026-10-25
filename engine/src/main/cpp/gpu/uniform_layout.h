#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace nanquim::gpu {

struct UniformMember {
  enum class Kind : uint8_t { Float, Int, UInt, Bool };

  std::string name;
  Kind kind = Kind::Float;
  int32_t components = 1;
  int32_t count = 0;
  uint32_t offset = 0;
  uint32_t stride = 0;
};

struct UniformLayout {
  std::string blockName;
  std::vector<UniformMember> members;
  uint32_t size = 0;
};

bool parseUniformLayout(std::string_view source, UniformLayout& layout, std::string& errorMessage);
}  // namespace nanquim::gpu
