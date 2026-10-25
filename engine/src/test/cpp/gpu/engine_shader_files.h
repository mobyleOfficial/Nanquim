#pragma once

#include <filesystem>
#include <fstream>
#include <map>
#include <sstream>
#include <string>

#include "gpu/shader_source.h"

namespace nanquim::gpu {

struct EngineShaderFiles {
  std::map<std::string, std::string, std::less<>> texts;

  [[nodiscard]] ShaderLookup lookup() const {
    return [this](std::string_view name) -> std::optional<std::string_view> {
      const auto found = texts.find(name);
      if (found == texts.end()) {
        return std::nullopt;
      }
      return std::string_view(found->second);
    };
  }
};

inline EngineShaderFiles readEngineShaderFiles() {
  EngineShaderFiles files;
  for (const auto& entry : std::filesystem::directory_iterator(NANQUIM_SHADERS_DIRECTORY)) {
    if (entry.path().extension() != ".glsl") {
      continue;
    }
    std::ifstream stream(entry.path(), std::ios::binary);
    std::stringstream text;
    text << stream.rdbuf();
    files.texts[entry.path().stem().string()] = text.str();
  }
  return files;
}

}  // namespace nanquim::gpu
