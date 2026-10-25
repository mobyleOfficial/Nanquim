#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace nanquim::gpu {

struct SpirvResource {
  enum class Kind : uint8_t { UniformBuffer, SampledImage, StorageImage, StorageBuffer };
  enum class Dimension : uint8_t { Texture2D, Texture3D, Other };
  enum class SampledType : uint8_t { Float, UInt, Int };

  Kind kind = Kind::UniformBuffer;
  uint32_t set = 0;
  uint32_t binding = 0;
  uint32_t count = 1;
  Dimension dimension = Dimension::Texture2D;
  SampledType sampledType = SampledType::Float;
  std::string name;

  bool operator==(const SpirvResource& other) const {
    return kind == other.kind && set == other.set && binding == other.binding &&
           count == other.count && dimension == other.dimension && sampledType == other.sampledType;
  }
};

bool reflectSpirv(const std::vector<uint32_t>& words, std::vector<SpirvResource>& resources,
                  std::string& errorMessage);

}  // namespace nanquim::gpu
