#include "gpu/embedded_shader_source.h"

#include <optional>

#include "core/log.h"
#include "embedded_shaders.h"
#include "gpu/shader_source.h"

namespace nanquim::gpu {

std::string withEmbeddedIncludes(std::string_view source) {
  std::string error;
  std::string result = withIncludes(
      source,
      [](std::string_view name) -> std::optional<std::string_view> {
        for (const shaders::EmbeddedShader& shader : shaders::kEmbeddedShaders) {
          if (name == shader.name) {
            return std::string_view(shader.source);
          }
        }
        return std::nullopt;
      },
      error);
  if (!error.empty()) {
    NANQUIM_LOG_ERROR("Shader source: %s", error.c_str());
  }
  return result;
}

}  // namespace nanquim::gpu
