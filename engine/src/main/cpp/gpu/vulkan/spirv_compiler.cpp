#include "gpu/vulkan/spirv_compiler.h"

#include <SPIRV/GlslangToSpv.h>
#include <glslang/Public/ShaderLang.h>
#include <libshaderc_util/resources.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <mutex>

#include "core/log.h"
#include "gpu/embedded_shader_source.h"
#include "gpu/shader_source.h"

namespace nanquim::gpu::vulkan {
namespace {

constexpr const char* kCompilerIdentity = "glslang-11.11|vulkan1.1|spv1.3|1";

uint64_t fnv1a(std::string_view text, uint64_t hash = 0xCBF29CE484222325ULL) {
  for (const char character : text) {
    hash ^= static_cast<uint8_t>(character);
    hash *= 0x100000001B3ULL;
  }
  return hash;
}

EShLanguage language(ShaderStage stage) {
  switch (stage) {
    case ShaderStage::Vertex:
      return EShLangVertex;
    case ShaderStage::Fragment:
      return EShLangFragment;
    case ShaderStage::Compute:
      return EShLangCompute;
  }
  return EShLangFragment;
}

const char* stageName(ShaderStage stage) {
  switch (stage) {
    case ShaderStage::Vertex:
      return "vert";
    case ShaderStage::Fragment:
      return "frag";
    case ShaderStage::Compute:
      return "comp";
  }
  return "frag";
}

std::vector<uint32_t> readModule(const std::string& path) {
  std::ifstream stream(path, std::ios::binary);
  if (!stream) {
    return {};
  }
  const std::vector<char> bytes((std::istreambuf_iterator<char>(stream)),
                                std::istreambuf_iterator<char>());
  if (bytes.size() < 20 || bytes.size() % 4 != 0) {
    return {};
  }
  std::vector<uint32_t> words(bytes.size() / 4);
  std::memcpy(words.data(), bytes.data(), bytes.size());
  return words[0] == 0x07230203 ? words : std::vector<uint32_t>{};
}

void writeModule(const std::string& path, const std::vector<uint32_t>& words) {
  const std::string temporaryPath = path + ".tmp";
  {
    std::ofstream stream(temporaryPath, std::ios::binary | std::ios::trunc);
    if (!stream) {
      NANQUIM_LOG_ERROR("Cannot write SPIR-V module %s", temporaryPath.c_str());
      return;
    }
    stream.write(reinterpret_cast<const char*>(words.data()),
                 static_cast<std::streamsize>(words.size() * sizeof(uint32_t)));
  }
  std::rename(temporaryPath.c_str(), path.c_str());
}

}  // namespace

SpirvCompiler::SpirvCompiler(std::string cacheDirectory)
    : cacheDirectory_(std::move(cacheDirectory)) {
  static std::once_flag initialized;
  std::call_once(initialized, [] { glslang::InitializeProcess(); });
}

std::string SpirvCompiler::vulkanSource(std::string_view writtenSource,
                                        const std::vector<std::string>& defines) {
  return withShaderMacros(withEmbeddedIncludes(withDefines(writtenSource, defines)),
                          ShaderDialect::Vulkan);
}

std::vector<uint32_t> SpirvCompiler::compile(ShaderStage stage, std::string_view writtenSource,
                                             const std::vector<std::string>& defines,
                                             std::string& errorMessage) {
  const std::string text = vulkanSource(writtenSource, defines);
  std::string path;
  if (!cacheDirectory_.empty()) {
    char name[48];
    std::snprintf(name, sizeof name, "%016llx.%s.spv",
                  static_cast<unsigned long long>(fnv1a(text, fnv1a(kCompilerIdentity))),
                  stageName(stage));
    path = cacheDirectory_ + "/" + name;
    std::vector<uint32_t> cached = readModule(path);
    if (!cached.empty()) {
      ++stats_.loadedFromCache;
      return cached;
    }
  }

  const EShLanguage shaderLanguage = language(stage);
  glslang::TShader shader(shaderLanguage);
  const char* source = text.c_str();
  shader.setStrings(&source, 1);
  shader.setEnvInput(glslang::EShSourceGlsl, shaderLanguage, glslang::EShClientVulkan, 100);
  shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
  shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);
  const auto messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
  if (!shader.parse(&shaderc_util::kDefaultTBuiltInResource, 310, false, messages)) {
    errorMessage = std::string(stageName(stage)) + " shader: " + shader.getInfoLog();
    return {};
  }
  glslang::TProgram program;
  program.addShader(&shader);
  if (!program.link(messages)) {
    errorMessage = std::string(stageName(stage)) + " shader link: " + program.getInfoLog();
    return {};
  }
  std::vector<unsigned int> spirv;
  glslang::SpvOptions options;
  options.disableOptimizer = true;
  options.validate = false;
  glslang::GlslangToSpv(*program.getIntermediate(shaderLanguage), spirv, &options);
  std::vector<uint32_t> words(spirv.begin(), spirv.end());
  ++stats_.compiled;
  if (!path.empty() && !words.empty()) {
    writeModule(path, words);
  }
  return words;
}

}  // namespace nanquim::gpu::vulkan
