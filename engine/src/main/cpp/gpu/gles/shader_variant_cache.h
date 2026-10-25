#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "gpu/gles/shader_program.h"

namespace nanquim::gpu::gles {

class ShaderVariantCache {
 public:
  explicit ShaderVariantCache(std::string binaryDirectory);

  void initialize();

  void registerPipeline(const std::string& pipeline, const char* vertexSource,
                        const char* fragmentSource);
  void registerComputePipeline(const std::string& pipeline, const char* computeSource);

  ShaderProgram* get(const std::string& pipeline, std::vector<std::string> defines,
                     std::string& errorMessage);

  struct Stats {
    int32_t compiled = 0;
    int32_t loadedFromBinary = 0;
  };
  [[nodiscard]] Stats stats() const { return stats_; }

 private:
  struct Pipeline {
    const char* vertexSource = nullptr;
    const char* fragmentSource = nullptr;
  };

  [[nodiscard]] std::unique_ptr<ShaderProgram> loadBinary(const std::string& path) const;
  void storeBinary(const std::string& path, const ShaderProgram& program) const;

  std::string binaryDirectory_;
  std::string driverIdentity_;
  bool binariesSupported_ = false;
  std::map<std::string, Pipeline> pipelines_;
  std::map<std::string, std::unique_ptr<ShaderProgram>> variants_;
  Stats stats_;
};

}  // namespace nanquim::gpu::gles
