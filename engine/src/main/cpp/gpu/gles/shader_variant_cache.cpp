#include "gpu/gles/shader_variant_cache.h"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <utility>

#include "core/log.h"
#include "gpu/shader_source.h"

namespace nanquim::gpu::gles {

namespace {

uint64_t fnv1a(const std::string& text, uint64_t hash = 0xCBF29CE484222325ULL) {
  for (const char character : text) {
    hash ^= static_cast<uint8_t>(character);
    hash *= 0x100000001B3ULL;
  }
  return hash;
}

std::string glString(GLenum name) {
  const auto* text = reinterpret_cast<const char*>(glGetString(name));
  return text != nullptr ? text : "";
}

}  // namespace

ShaderVariantCache::ShaderVariantCache(std::string binaryDirectory)
    : binaryDirectory_(std::move(binaryDirectory)) {}

void ShaderVariantCache::initialize() {
  driverIdentity_ = glString(GL_RENDERER) + "|" + glString(GL_VERSION);
  GLint formatCount = 0;
  glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &formatCount);
  binariesSupported_ = formatCount > 0 && !binaryDirectory_.empty();
}

void ShaderVariantCache::registerPipeline(const std::string& pipeline, const char* vertexSource,
                                          const char* fragmentSource) {
  pipelines_[pipeline] = Pipeline{vertexSource, fragmentSource};
}

void ShaderVariantCache::registerComputePipeline(const std::string& pipeline,
                                                 const char* computeSource) {
  pipelines_[pipeline] = Pipeline{nullptr, computeSource};
}

ShaderProgram* ShaderVariantCache::get(const std::string& pipeline,
                                       std::vector<std::string> defines,
                                       std::string& errorMessage) {
  std::sort(defines.begin(), defines.end());
  defines.erase(std::unique(defines.begin(), defines.end()), defines.end());
  std::string key = pipeline;
  for (const std::string& define : defines) {
    key += "+" + define;
  }
  auto existing = variants_.find(key);
  if (existing != variants_.end()) {
    return existing->second.get();
  }
  auto found = pipelines_.find(pipeline);
  if (found == pipelines_.end()) {
    errorMessage = "Unknown shader pipeline " + pipeline;
    return nullptr;
  }
  const bool compute = found->second.vertexSource == nullptr;
  const std::string vertex = compute ? std::string() : withDefines(found->second.vertexSource, defines);
  const std::string fragment = withDefines(found->second.fragmentSource, defines);

  std::string binaryPath;
  std::unique_ptr<ShaderProgram> program;
  if (binariesSupported_) {
    const std::string compiledVertex = compute ? std::string() : glesShaderSource(vertex);
    const std::string compiledFragment = glesShaderSource(fragment);
    char name[32];
    std::snprintf(name, sizeof name, "%016llx.bin",
                  static_cast<unsigned long long>(
                      fnv1a(compiledFragment, fnv1a(compiledVertex, fnv1a(driverIdentity_)))));
    binaryPath = binaryDirectory_ + "/" + name;
    program = loadBinary(binaryPath);
    if (program) {
      ++stats_.loadedFromBinary;
    }
  }
  if (!program) {
    program = compute ? ShaderProgram::createCompute(fragment.c_str(), errorMessage,
                                                     binariesSupported_)
                      : ShaderProgram::create(vertex.c_str(), fragment.c_str(), errorMessage,
                                              binariesSupported_);
    if (!program) {
      errorMessage = key + ": " + errorMessage;
      return nullptr;
    }
    ++stats_.compiled;
    if (binariesSupported_) {
      storeBinary(binaryPath, *program);
    }
  }
  ShaderProgram* result = program.get();
  variants_.emplace(std::move(key), std::move(program));
  return result;
}

std::unique_ptr<ShaderProgram> ShaderVariantCache::loadBinary(const std::string& path) const {
  std::ifstream stream(path, std::ios::binary);
  if (!stream) {
    return nullptr;
  }
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(stream)),
                             std::istreambuf_iterator<char>());
  if (bytes.size() <= sizeof(uint32_t)) {
    return nullptr;
  }
  uint32_t format = 0;
  for (size_t byte = 0; byte < sizeof format; ++byte) {
    format |= static_cast<uint32_t>(bytes[byte]) << (byte * 8);
  }
  bytes.erase(bytes.begin(), bytes.begin() + sizeof format);
  return ShaderProgram::createFromBinary(format, bytes);
}

void ShaderVariantCache::storeBinary(const std::string& path, const ShaderProgram& program) const {
  GLenum format = 0;
  const std::vector<uint8_t> binary = program.binary(format);
  if (binary.empty()) {
    return;
  }
  const std::string temporaryPath = path + ".tmp";
  {
    std::ofstream stream(temporaryPath, std::ios::binary | std::ios::trunc);
    if (!stream) {
      NANQUIM_LOG_ERROR("Cannot write shader binary %s", temporaryPath.c_str());
      return;
    }
    for (size_t byte = 0; byte < sizeof(uint32_t); ++byte) {
      stream.put(static_cast<char>((format >> (byte * 8)) & 0xFFU));
    }
    stream.write(reinterpret_cast<const char*>(binary.data()),
                 static_cast<std::streamsize>(binary.size()));
  }
  std::rename(temporaryPath.c_str(), path.c_str());
}

}  // namespace nanquim::gpu::gles
