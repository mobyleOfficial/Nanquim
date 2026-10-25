#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace nanquim::gpu::vulkan {

enum class ShaderStage : uint8_t { Vertex, Fragment, Compute };

class SpirvCompiler {
 public:
  explicit SpirvCompiler(std::string cacheDirectory);

  std::vector<uint32_t> compile(ShaderStage stage, std::string_view writtenSource,
                                const std::vector<std::string>& defines, std::string& errorMessage);

  struct Stats {
    int32_t compiled = 0;
    int32_t loadedFromCache = 0;
  };
  [[nodiscard]] Stats stats() const { return stats_; }

  static std::string vulkanSource(std::string_view writtenSource,
                                  const std::vector<std::string>& defines);

 private:
  std::string cacheDirectory_;
  Stats stats_;
};

}  // namespace nanquim::gpu::vulkan
