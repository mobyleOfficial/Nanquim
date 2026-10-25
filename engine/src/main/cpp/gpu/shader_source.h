#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace nanquim::gpu {

using ShaderLookup = std::function<std::optional<std::string_view>(std::string_view name)>;
std::string withIncludes(std::string_view source, const ShaderLookup& lookup,
                         std::string& errorMessage);

std::string withDefines(std::string_view source, const std::vector<std::string>& defines);

enum class ShaderDialect : uint8_t { Gles, Vulkan };

std::string shaderMacros(ShaderDialect dialect);

std::string withShaderMacros(std::string_view source, ShaderDialect dialect);

}  // namespace nanquim::gpu
