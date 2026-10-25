#pragma once

#include <EGL/egl.h>
#include <GLES3/gl31.h>

#include <array>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "gpu/gles/gl_objects.h"
#include "gpu/gles/pixel_format_gl.h"
#include "gpu/gles/shader_variant_cache.h"
#include "gpu/rhi/rhi.h"
#include "gpu/uniform_layout.h"

namespace nanquim::gpu::gles {

class GlesTexture final : public rhi::Texture {
 public:
  GlesTexture(const rhi::TextureDescription& description, gles::Texture texture)
      : rhi::Texture(description), texture_(std::move(texture)) {}

  [[nodiscard]] GLuint name() const { return texture_.name(); }
  [[nodiscard]] GLenum target() const {
    return description().type == rhi::TextureType::Texture3D ? GL_TEXTURE_3D : GL_TEXTURE_2D;
  }

 private:
  gles::Texture texture_;
};

inline GLuint textureName(const rhi::Texture& texture) {
  return static_cast<const GlesTexture&>(texture).name();
}

class GlesDevice final : public rhi::Device, private rhi::CommandContext {
 public:
  GlesDevice(EGLDisplay display, ShaderVariantCache& shaderCache);
  ~GlesDevice() override;

  std::unique_ptr<rhi::Texture> createTexture(const rhi::TextureDescription& description) override;
  void uploadTexture(rhi::Texture& texture, const void* data) override;
  void uploadTextureLevel(rhi::Texture& texture, int32_t level, const void* data) override;
  void uploadTextureRegion(rhi::Texture& texture, const rhi::Rect& region, const void* data,
                           int32_t rowLength) override;
  using rhi::Device::createSampler;
  std::unique_ptr<rhi::Sampler> createSampler(const rhi::SamplerDescription& description) override;
  std::unique_ptr<rhi::Buffer> createBuffer(size_t size, uint32_t usage) override;
  void uploadBuffer(rhi::Buffer& buffer, const void* data, size_t size) override;
  void registerProgram(const std::string& name, std::string vertexSource,
                       std::string fragmentSource) override;
  const rhi::Pipeline* pipeline(const rhi::PipelineDescription& description,
                                std::string& errorMessage) override;
  void registerComputeProgram(const std::string& name, std::string source) override;
  const rhi::Pipeline* computePipeline(const rhi::ComputePipelineDescription& description,
                                       std::string& errorMessage) override;
  bool readsDestination() const override { return destinationRead_; }
  rhi::CommandContext& commands() override { return *this; }
  int32_t maxTextureSize() const override { return maxTextureSize_; }
  bool isRenderable(rhi::Format format) const override;
  bool supportsTimerQueries() const override { return timerQueriesSupported_; }
  std::unique_ptr<rhi::TimerQuery> createTimerQuery() override;
  bool timerResultsDisjoint() override;
  std::string infoJson() const override;

 private:
  void beginPass(const rhi::RenderTarget& target, rhi::LoadAction load,
                 std::array<float, 4> clearColor) override;
  void endPass() override;
  void setViewport(const rhi::Rect& rect) override;
  void setScissor(std::optional<rhi::Rect> rect) override;
  void clear(std::array<float, 4> color, std::optional<rhi::Rect> rect) override;
  void bindPipeline(const rhi::Pipeline& pipeline) override;
  void setUniforms(const void* data, size_t size) override;
  void bindTexture(uint32_t binding, const rhi::Texture* texture,
                   const rhi::Sampler* sampler) override;
  void bindVertexBuffer(const rhi::Buffer& buffer, size_t offset) override;
  void draw(uint32_t vertexCount, uint32_t instanceCount) override;
  void beginCompute() override;
  void endCompute() override;
  void bindComputePipeline(const rhi::Pipeline& pipeline) override;
  void bindStorageImage(uint32_t binding, const rhi::Texture& texture,
                        rhi::StorageAccess access) override;
  void bindStorageBuffer(uint32_t binding, const rhi::Buffer& buffer) override;
  void dispatch(uint32_t groupsX, uint32_t groupsY, uint32_t groupsZ) override;
  void barrier(uint32_t barrierBits) override;
  void copyTexture(const rhi::Texture& source, const rhi::Rect& sourceRect,
                   const rhi::Texture& destination, int32_t destinationX,
                   int32_t destinationY) override;
  bool readTexture(const rhi::Texture& texture, const rhi::Rect& rect, void* out) override;
  std::unique_ptr<rhi::Readback> readTextureAsync(const rhi::Texture& texture,
                                                  const rhi::Rect& rect) override;
  void submit() override;
  std::unique_ptr<rhi::Fence> insertFence() override;
  void beginTimer(rhi::TimerQuery& query) override;
  void endTimer(rhi::TimerQuery& query) override;

  void uploadPendingUniforms();
  void trackProgram(ShaderProgram& program, const UniformLayout& layout);
  void unbindResources();
  std::optional<ReadLayout> bindForRead(const rhi::Texture& texture);

  friend class GlesReadback;
  [[nodiscard]] Buffer takeReadBuffer(GLsizeiptr bytes);
  void returnReadBuffer(GLsizeiptr bytes, Buffer buffer);

  struct ProgramSources {
    std::string vertex;
    std::string fragment;
    UniformLayout uniforms;
    std::string error;
  };
  struct ProgramUniforms {
    const UniformLayout* layout = nullptr;
    std::vector<GLint> locations;
    std::vector<uint8_t> uploaded;
    bool reportedSizeMismatch = false;
    uint32_t samplerUnits = 0;
  };

  EGLDisplay display_;
  ShaderVariantCache& shaderCache_;
  std::map<std::string, ProgramSources> programs_;
  std::map<std::string, std::unique_ptr<rhi::Pipeline>> pipelines_;
  std::map<const ShaderProgram*, ProgramUniforms> programUniforms_;
  ProgramUniforms* boundUniforms_ = nullptr;
  const class GlesPipeline* boundPipeline_ = nullptr;
  GLuint vertexBuffer_ = 0;
  size_t vertexBufferOffset_ = 0;
  rhi::Format targetFormat_ = rhi::Format::Rgba8;
  uint32_t boundImages_ = 0;
  uint32_t boundStorageBuffers_ = 0;
  bool destinationRead_ = false;
  Framebuffer framebuffer_;
  Framebuffer readFramebuffer_;
  Framebuffer copyFramebuffer_;
  VertexArray emptyVertexArray_;
  std::array<std::optional<ReadLayout>, 2> readLayouts_;
  std::multimap<GLsizeiptr, Buffer> idleReadBuffers_;
  GLsizeiptr idleReadBufferBytes_ = 0;
  int32_t maxTextureSize_ = 0;
  std::vector<uint8_t> pendingUniforms_;
  std::optional<rhi::Rect> scissor_;
  uint32_t boundUnits_ = 0;
  bool timerQueriesSupported_ = false;
};

}  // namespace nanquim::gpu::gles
