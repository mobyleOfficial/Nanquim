#include "gpu/gles/gles_device.h"

#include <algorithm>
#include <cstring>

#include "core/log.h"
#include "gpu/embedded_shader_source.h"
#include "gpu/gles/gles_info.h"

namespace nanquim::gpu::gles {
namespace {

constexpr GLsizeiptr kMaxIdleReadBufferBytes = 256LL * 1024 * 1024;

constexpr GLenum kTimeElapsed = 0x88BF;
constexpr GLenum kGpuDisjoint = 0x8FBB;

struct GlFormat {
  GLenum internalFormat;
  GLenum format;
  GLenum type;
  int32_t bytesPerPixel;
};

GlFormat glFormat(rhi::Format format) {
  switch (format) {
    case rhi::Format::Rgba8:
      return {GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 4};
    case rhi::Format::Rgba16f:
      return {GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, 8};
    case rhi::Format::R8:
      return {GL_R8, GL_RED, GL_UNSIGNED_BYTE, 1};
    case rhi::Format::R16f:
      return {GL_R16F, GL_RED, GL_HALF_FLOAT, 2};
    case rhi::Format::R32ui:
      return {GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, 4};
    case rhi::Format::R32f:
      return {GL_R32F, GL_RED, GL_FLOAT, 4};
    case rhi::Format::Rg32f:
      return {GL_RG32F, GL_RG, GL_FLOAT, 8};
    case rhi::Format::Rgba32f:
      return {GL_RGBA32F, GL_RGBA, GL_FLOAT, 16};
  }
  return {GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 4};
}

GLenum glBlendFactor(rhi::BlendFactor factor) {
  switch (factor) {
    case rhi::BlendFactor::Zero:
      return GL_ZERO;
    case rhi::BlendFactor::One:
      return GL_ONE;
    case rhi::BlendFactor::SourceColor:
      return GL_SRC_COLOR;
    case rhi::BlendFactor::OneMinusSourceColor:
      return GL_ONE_MINUS_SRC_COLOR;
    case rhi::BlendFactor::DestinationColor:
      return GL_DST_COLOR;
    case rhi::BlendFactor::OneMinusDestinationColor:
      return GL_ONE_MINUS_DST_COLOR;
    case rhi::BlendFactor::SourceAlpha:
      return GL_SRC_ALPHA;
    case rhi::BlendFactor::OneMinusSourceAlpha:
      return GL_ONE_MINUS_SRC_ALPHA;
    case rhi::BlendFactor::DestinationAlpha:
      return GL_DST_ALPHA;
    case rhi::BlendFactor::OneMinusDestinationAlpha:
      return GL_ONE_MINUS_DST_ALPHA;
  }
  return GL_ONE;
}

GLenum glBlendOperation(rhi::BlendOperation operation) {
  switch (operation) {
    case rhi::BlendOperation::Add:
      return GL_FUNC_ADD;
    case rhi::BlendOperation::Subtract:
      return GL_FUNC_SUBTRACT;
    case rhi::BlendOperation::ReverseSubtract:
      return GL_FUNC_REVERSE_SUBTRACT;
    case rhi::BlendOperation::Min:
      return GL_MIN;
    case rhi::BlendOperation::Max:
      return GL_MAX;
  }
  return GL_FUNC_ADD;
}

class GlesSampler final : public rhi::Sampler {
 public:
  explicit GlesSampler(const rhi::SamplerDescription& description) {
    const bool linear = description.filter == rhi::Filter::Linear;
    GLint minification = linear ? GL_LINEAR : GL_NEAREST;
    if (description.mipmaps) {
      minification = linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST;
    }
    const GLint wrap =
        description.address == rhi::AddressMode::Repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
    GLuint name = 0;
    glGenSamplers(1, &name);
    glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, minification);
    glSamplerParameteri(name, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
    glSamplerParameteri(name, GL_TEXTURE_WRAP_S, wrap);
    glSamplerParameteri(name, GL_TEXTURE_WRAP_T, wrap);
    sampler_ = gles::Sampler(name);
  }
  [[nodiscard]] GLuint name() const { return sampler_.name(); }

 private:
  gles::Sampler sampler_;
};

class GlesBuffer final : public rhi::Buffer {
 public:
  GlesBuffer(size_t size, uint32_t usage) : rhi::Buffer(size, usage), buffer_(createBuffer()) {
    glBindBuffer(target(), buffer_.name());
    glBufferData(target(), static_cast<GLsizeiptr>(size), nullptr, glUsage());
    glBindBuffer(target(), 0);
  }

  [[nodiscard]] GLuint name() const { return buffer_.name(); }
  [[nodiscard]] GLenum target() const {
    return (usage() & rhi::kBufferStorage) != 0 ? GL_SHADER_STORAGE_BUFFER : GL_ARRAY_BUFFER;
  }
  [[nodiscard]] GLenum glUsage() const {
    return (usage() & rhi::kBufferStorage) != 0 ? GL_DYNAMIC_COPY : GL_STREAM_DRAW;
  }

  void upload(const void* data, size_t size) {
    glBindBuffer(target(), buffer_.name());
    glBufferData(target(), static_cast<GLsizeiptr>(size), data, glUsage());
    glBindBuffer(target(), 0);
    resized(size);
  }

 private:
  gles::Buffer buffer_;
};

}  // namespace

class GlesPipeline final : public rhi::Pipeline {
 public:
  GlesPipeline(ShaderProgram& program, const rhi::BlendState& blend, rhi::Topology topology,
               std::optional<rhi::VertexLayout> layout, GLuint emptyVertexArray)
      : program_(program),
        blend_(blend),
        topology_(topology),
        layout_(layout),
        emptyVertexArray_(emptyVertexArray) {
    if (layout_) {
      vertexArray_ = createVertexArray();
      glBindVertexArray(vertexArray_.name());
      for (GLuint location = 0; location < layout_->attributes; ++location) {
        glEnableVertexAttribArray(location);
        glVertexAttribDivisor(location, layout_->perInstance ? 1 : 0);
      }
      glBindVertexArray(0);
    }
  }
  explicit GlesPipeline(ShaderProgram& program)
      : program_(program), topology_(rhi::Topology::Triangles), emptyVertexArray_(0) {}

  [[nodiscard]] const ShaderProgram& program() const { return program_; }
  [[nodiscard]] GLenum mode() const {
    return topology_ == rhi::Topology::TriangleStrip ? GL_TRIANGLE_STRIP : GL_TRIANGLES;
  }
  [[nodiscard]] const std::optional<rhi::VertexLayout>& layout() const { return layout_; }

  void bind() const {
    program_.use();
    glBindVertexArray(layout_ ? vertexArray_.name() : emptyVertexArray_);
    if (!blend_.enabled) {
      glDisable(GL_BLEND);
      return;
    }
    glEnable(GL_BLEND);
    glBlendFuncSeparate(glBlendFactor(blend_.sourceColor), glBlendFactor(blend_.destinationColor),
                        glBlendFactor(blend_.sourceAlpha), glBlendFactor(blend_.destinationAlpha));
    glBlendEquationSeparate(glBlendOperation(blend_.colorOperation),
                            glBlendOperation(blend_.alphaOperation));
  }

  void setVertexBuffer(GLuint buffer, size_t offset) const {
    glBindBuffer(GL_ARRAY_BUFFER, buffer);
    size_t attributeOffset = offset;
    for (GLuint location = 0; location < layout_->attributes; ++location) {
      const GLint components =
          location < layout_->components.size() ? layout_->components[location] : 4;
      glVertexAttribPointer(location, components, GL_FLOAT, GL_FALSE,
                            static_cast<GLsizei>(layout_->stride),
                            reinterpret_cast<const void*>(attributeOffset));
      attributeOffset += static_cast<size_t>(components) * sizeof(float);
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
  }

 private:
  ShaderProgram& program_;
  rhi::BlendState blend_;
  rhi::Topology topology_;
  std::optional<rhi::VertexLayout> layout_;
  GLuint emptyVertexArray_;
  VertexArray vertexArray_;
};

namespace {

class GlesFence final : public rhi::Fence {
 public:
  GlesFence() : sync_(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)) {}
  ~GlesFence() override { glDeleteSync(sync_); }

  bool isSignaled() override {
    const GLenum status = glClientWaitSync(sync_, 0, 0);
    return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED;
  }

  bool wait(uint64_t timeoutNanoseconds) override {
    const GLenum status = glClientWaitSync(sync_, GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanoseconds);
    return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED;
  }

 private:
  GLsync sync_;
};

class GlesTimerQuery final : public rhi::TimerQuery {
 public:
  GlesTimerQuery() { glGenQueries(1, &query_); }
  ~GlesTimerQuery() override { glDeleteQueries(1, &query_); }

  std::optional<uint64_t> elapsedNanoseconds() override {
    GLuint available = 0;
    glGetQueryObjectuiv(query_, GL_QUERY_RESULT_AVAILABLE, &available);
    if (available == 0) {
      return std::nullopt;
    }
    GLuint nanoseconds = 0;
    glGetQueryObjectuiv(query_, GL_QUERY_RESULT, &nanoseconds);
    return nanoseconds;
  }

  [[nodiscard]] GLuint name() const { return query_; }

 private:
  GLuint query_ = 0;
};

bool isSamplerType(GLenum type) {
  switch (type) {
    case GL_SAMPLER_2D:
    case GL_SAMPLER_3D:
    case GL_SAMPLER_CUBE:
    case GL_SAMPLER_2D_SHADOW:
    case GL_SAMPLER_2D_ARRAY:
    case GL_SAMPLER_2D_ARRAY_SHADOW:
    case GL_SAMPLER_CUBE_SHADOW:
    case GL_SAMPLER_2D_MULTISAMPLE:
    case GL_INT_SAMPLER_2D:
    case GL_INT_SAMPLER_3D:
    case GL_INT_SAMPLER_CUBE:
    case GL_INT_SAMPLER_2D_ARRAY:
    case GL_INT_SAMPLER_2D_MULTISAMPLE:
    case GL_UNSIGNED_INT_SAMPLER_2D:
    case GL_UNSIGNED_INT_SAMPLER_3D:
    case GL_UNSIGNED_INT_SAMPLER_CUBE:
    case GL_UNSIGNED_INT_SAMPLER_2D_ARRAY:
    case GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE:
      return true;
    default:
      return false;
  }
}

uint32_t samplerUnits(GLuint program) {
  GLint count = 0;
  GLint maxLength = 0;
  glGetProgramiv(program, GL_ACTIVE_UNIFORMS, &count);
  glGetProgramiv(program, GL_ACTIVE_UNIFORM_MAX_LENGTH, &maxLength);
  std::vector<char> name(static_cast<size_t>(std::max(maxLength, 1)));
  uint32_t units = 0;
  for (GLint index = 0; index < count; ++index) {
    GLsizei length = 0;
    GLint size = 0;
    GLenum type = 0;
    glGetActiveUniform(program, static_cast<GLuint>(index), static_cast<GLsizei>(name.size()),
                       &length, &size, &type, name.data());
    if (!isSamplerType(type)) {
      continue;
    }
    std::string base(name.data(), static_cast<size_t>(length));
    if (const size_t bracket = base.find('['); bracket != std::string::npos) {
      base.resize(bracket);
    }
    for (GLint element = 0; element < size; ++element) {
      const std::string elementName = size > 1 ? base + "[" + std::to_string(element) + "]" : base;
      const GLint location = glGetUniformLocation(program, elementName.c_str());
      GLint unit = -1;
      if (location >= 0) {
        glGetUniformiv(program, location, &unit);
      }
      if (unit >= 0 && unit < 32) {
        units |= 1U << static_cast<uint32_t>(unit);
      }
    }
  }
  return units;
}

bool isSignaled(GLsync sync, GLbitfield flags, GLuint64 timeoutNanoseconds) {
  const GLenum status = glClientWaitSync(sync, flags, timeoutNanoseconds);
  return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED;
}

}  // namespace

class GlesReadback final : public rhi::Readback {
 public:
  GlesReadback(GlesDevice& device, Buffer buffer, GLsizeiptr bufferBytes, ReadLayout layout,
               size_t pixelCount, size_t outBytes)
      : rhi::Readback(outBytes),
        device_(device),
        buffer_(std::move(buffer)),
        bufferBytes_(bufferBytes),
        layout_(layout),
        pixelCount_(pixelCount),
        fence_(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)) {}
  ~GlesReadback() override {
    glDeleteSync(fence_);
    device_.returnReadBuffer(bufferBytes_, std::move(buffer_));
  }

  bool isReady() override { return isSignaled(fence_, 0, 0); }

  bool read(void* out) override {
    constexpr GLuint64 kWaitNanoseconds = 5000000000;
    if (!isSignaled(fence_, GL_SYNC_FLUSH_COMMANDS_BIT, kWaitNanoseconds)) {
      return false;
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer_.name());
    const void* mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, bufferBytes_, GL_MAP_READ_BIT);
    if (mapped != nullptr) {
      readToFormat(mapped, layout_, static_cast<uint8_t*>(out), pixelCount_);
      glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    return mapped != nullptr;
  }

 private:
  GlesDevice& device_;
  Buffer buffer_;
  GLsizeiptr bufferBytes_;
  ReadLayout layout_;
  size_t pixelCount_;
  GLsync fence_;
};

namespace {

std::string pipelineKey(const rhi::PipelineDescription& description,
                        std::vector<std::string>& defines) {
  std::sort(defines.begin(), defines.end());
  defines.erase(std::unique(defines.begin(), defines.end()), defines.end());
  std::string key = description.program;
  for (const std::string& define : defines) {
    key += "+" + define;
  }
  const rhi::BlendState& blend = description.blend;
  key += "|" + std::to_string(blend.enabled ? 1 : 0);
  for (const auto value :
       {static_cast<int>(blend.sourceColor), static_cast<int>(blend.destinationColor),
        static_cast<int>(blend.sourceAlpha), static_cast<int>(blend.destinationAlpha),
        static_cast<int>(blend.colorOperation), static_cast<int>(blend.alphaOperation),
        static_cast<int>(description.targetFormat), static_cast<int>(description.topology)}) {
    key += "," + std::to_string(value);
  }
  if (description.vertexLayout) {
    const rhi::VertexLayout& layout = *description.vertexLayout;
    key += "|v" + std::to_string(layout.attributes) + "," + std::to_string(layout.stride) + "," +
           std::to_string(layout.perInstance ? 1 : 0);
    for (const uint8_t components : layout.components) {
      key += "," + std::to_string(components);
    }
  }
  return key;
}

bool hasExtension(const char* name) {
  GLint count = 0;
  glGetIntegerv(GL_NUM_EXTENSIONS, &count);
  for (GLint index = 0; index < count; ++index) {
    const auto* extension =
        reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(index)));
    if (extension != nullptr && std::strcmp(extension, name) == 0) {
      return true;
    }
  }
  return false;
}

}  // namespace

GlesDevice::GlesDevice(EGLDisplay display, ShaderVariantCache& shaderCache)
    : display_(display), shaderCache_(shaderCache) {
  framebuffer_ = createFramebuffer();
  readFramebuffer_ = createFramebuffer();
  copyFramebuffer_ = createFramebuffer();
  emptyVertexArray_ = createVertexArray();
  glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize_);
  destinationRead_ = hasExtension("GL_EXT_shader_framebuffer_fetch");
  const auto* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
  timerQueriesSupported_ =
      extensions != nullptr && std::strstr(extensions, "GL_EXT_disjoint_timer_query") != nullptr;
}

GlesDevice::~GlesDevice() = default;

std::unique_ptr<rhi::Texture> GlesDevice::createTexture(
    const rhi::TextureDescription& description) {
  const GlFormat format = glFormat(description.format);
  GLuint name = 0;
  glGenTextures(1, &name);
  const GLenum target =
      description.type == rhi::TextureType::Texture3D ? GL_TEXTURE_3D : GL_TEXTURE_2D;
  glBindTexture(target, name);
  if (target == GL_TEXTURE_3D) {
    glTexStorage3D(target, 1, format.internalFormat, description.width, description.height,
                   description.depth);
    glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
  } else {
    glTexStorage2D(target, std::max(description.levels, 1), format.internalFormat,
                   description.width, description.height);
  }
  const GLint filter = description.filter == rhi::Filter::Linear ? GL_LINEAR : GL_NEAREST;
  glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);
  glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter);
  glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindTexture(target, 0);
  gles::Texture texture(name);
  if (glGetError() == GL_OUT_OF_MEMORY) {
    NANQUIM_LOG_ERROR("Texture of %d×%d×%d: GL_OUT_OF_MEMORY", description.width,
                      description.height, description.depth);
    return nullptr;
  }
  return std::make_unique<GlesTexture>(description, std::move(texture));
}

bool GlesDevice::isRenderable(rhi::Format format) const {
  switch (format) {
    case rhi::Format::Rgba16f:
      return halfFloatRenderable();
    case rhi::Format::Rg32f:
    case rhi::Format::R32f:
    case rhi::Format::Rgba32f:
      return false;
    default:
      return true;
  }
}

void GlesDevice::uploadTexture(rhi::Texture& texture, const void* data) {
  const auto& gles = static_cast<const GlesTexture&>(texture);
  const rhi::TextureDescription& description = texture.description();
  const GlFormat format = glFormat(description.format);
  GLint alignment = 4;
  glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glBindTexture(gles.target(), gles.name());
  if (gles.target() == GL_TEXTURE_3D) {
    glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, description.width, description.height,
                    description.depth, format.format, format.type, data);
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, description.width, description.height, format.format,
                    format.type, data);
  }
  glBindTexture(gles.target(), 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
}

void GlesDevice::uploadTextureLevel(rhi::Texture& texture, int32_t level, const void* data) {
  const auto& gles = static_cast<const GlesTexture&>(texture);
  const rhi::TextureDescription& description = texture.description();
  const GlFormat format = glFormat(description.format);
  GLint alignment = 4;
  glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glBindTexture(GL_TEXTURE_2D, gles.name());
  glTexSubImage2D(GL_TEXTURE_2D, level, 0, 0, std::max(description.width >> level, 1),
                  std::max(description.height >> level, 1), format.format, format.type, data);
  glBindTexture(GL_TEXTURE_2D, 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
}

void GlesDevice::uploadTextureRegion(rhi::Texture& texture, const rhi::Rect& region,
                                     const void* data, int32_t rowLength) {
  const auto& gles = static_cast<const GlesTexture&>(texture);
  const GlFormat format = glFormat(texture.description().format);
  GLint alignment = 4;
  glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
  glBindTexture(GL_TEXTURE_2D, gles.name());
  glTexSubImage2D(GL_TEXTURE_2D, 0, region.x, region.y, region.width, region.height, format.format,
                  format.type, data);
  glBindTexture(GL_TEXTURE_2D, 0);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
}

std::unique_ptr<rhi::Sampler> GlesDevice::createSampler(
    const rhi::SamplerDescription& description) {
  return std::make_unique<GlesSampler>(description);
}

std::unique_ptr<rhi::Buffer> GlesDevice::createBuffer(size_t size, uint32_t usage) {
  auto buffer = std::make_unique<GlesBuffer>(size, usage);
  if (glGetError() == GL_OUT_OF_MEMORY) {
    NANQUIM_LOG_ERROR("Buffer of %zu bytes: GL_OUT_OF_MEMORY", size);
    return nullptr;
  }
  return buffer;
}

void GlesDevice::uploadBuffer(rhi::Buffer& buffer, const void* data, size_t size) {
  static_cast<GlesBuffer&>(buffer).upload(data, size);
}

void GlesDevice::registerProgram(const std::string& name, std::string vertexSource,
                                 std::string fragmentSource) {
  ProgramSources& sources = programs_[name];
  sources.error.clear();
  sources.vertex = std::move(vertexSource);
  sources.fragment = std::move(fragmentSource);
  UniformLayout vertexUniforms;
  UniformLayout fragmentUniforms;
  std::string error;
  if (!parseUniformLayout(withEmbeddedIncludes(sources.vertex), vertexUniforms, error) ||
      !parseUniformLayout(withEmbeddedIncludes(sources.fragment), fragmentUniforms, error)) {
    sources.error = name + ": " + error;
  } else if (!vertexUniforms.blockName.empty() && !fragmentUniforms.blockName.empty() &&
             (vertexUniforms.size != fragmentUniforms.size ||
              vertexUniforms.members.size() != fragmentUniforms.members.size())) {
    sources.error = name + ": the stages declare different uniforms";
  }
  sources.uniforms =
      vertexUniforms.blockName.empty() ? std::move(fragmentUniforms) : std::move(vertexUniforms);
  shaderCache_.registerPipeline(name, sources.vertex.c_str(), sources.fragment.c_str());
}

const rhi::Pipeline* GlesDevice::pipeline(const rhi::PipelineDescription& description,
                                          std::string& errorMessage) {
  std::vector<std::string> defines = description.defines;
  std::string key = pipelineKey(description, defines);
  auto existing = pipelines_.find(key);
  if (existing != pipelines_.end()) {
    return existing->second.get();
  }
  const auto sources = programs_.find(description.program);
  if (sources == programs_.end()) {
    errorMessage = "Unknown program " + description.program;
    return nullptr;
  }
  if (!sources->second.error.empty()) {
    errorMessage = sources->second.error;
    return nullptr;
  }
  ShaderProgram* program = shaderCache_.get(description.program, defines, errorMessage);
  if (program == nullptr) {
    return nullptr;
  }
  trackProgram(*program, sources->second.uniforms);
  auto created = std::make_unique<GlesPipeline>(*program, description.blend, description.topology,
                                                description.vertexLayout, emptyVertexArray_.name());
  const rhi::Pipeline* result = created.get();
  pipelines_.emplace(std::move(key), std::move(created));
  return result;
}

void GlesDevice::trackProgram(ShaderProgram& program, const UniformLayout& layout) {
  if (programUniforms_.count(&program) != 0) {
    return;
  }
  ProgramUniforms& uniforms = programUniforms_[&program];
  uniforms.layout = &layout;
  for (const UniformMember& member : layout.members) {
    uniforms.locations.push_back(program.uniform(member.name.c_str()));
  }
  uniforms.samplerUnits = samplerUnits(program.name());
}

void GlesDevice::registerComputeProgram(const std::string& name, std::string source) {
  ProgramSources& sources = programs_[name];
  sources.error.clear();
  sources.vertex.clear();
  sources.fragment = std::move(source);
  std::string error;
  if (!parseUniformLayout(withEmbeddedIncludes(sources.fragment), sources.uniforms, error)) {
    sources.error = name + ": " + error;
  }
  shaderCache_.registerComputePipeline(name, sources.fragment.c_str());
}

const rhi::Pipeline* GlesDevice::computePipeline(const rhi::ComputePipelineDescription& description,
                                                 std::string& errorMessage) {
  std::vector<std::string> defines = description.defines;
  std::sort(defines.begin(), defines.end());
  defines.erase(std::unique(defines.begin(), defines.end()), defines.end());
  std::string key = "compute:" + description.program;
  for (const std::string& define : defines) {
    key += "+" + define;
  }
  auto existing = pipelines_.find(key);
  if (existing != pipelines_.end()) {
    return existing->second.get();
  }
  const auto sources = programs_.find(description.program);
  if (sources == programs_.end()) {
    errorMessage = "Unknown program " + description.program;
    return nullptr;
  }
  if (!sources->second.error.empty()) {
    errorMessage = sources->second.error;
    return nullptr;
  }
  ShaderProgram* program = shaderCache_.get(description.program, defines, errorMessage);
  if (program == nullptr) {
    return nullptr;
  }
  trackProgram(*program, sources->second.uniforms);
  auto created = std::make_unique<GlesPipeline>(*program);
  const rhi::Pipeline* result = created.get();
  pipelines_.emplace(std::move(key), std::move(created));
  return result;
}

std::unique_ptr<rhi::TimerQuery> GlesDevice::createTimerQuery() {
  return std::make_unique<GlesTimerQuery>();
}

bool GlesDevice::timerResultsDisjoint() {
  GLint disjoint = 0;
  glGetIntegerv(kGpuDisjoint, &disjoint);
  return disjoint != 0;
}

std::string GlesDevice::infoJson() const { return queryGlesInfoJson(display_); }

void GlesDevice::beginPass(const rhi::RenderTarget& target, rhi::LoadAction load,
                           std::array<float, 4> clearColor) {
  targetFormat_ =
      target.texture != nullptr ? target.texture->description().format : rhi::Format::Rgba8;
  if (target.texture == nullptr) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
  } else {
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_.name());
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           static_cast<const GlesTexture*>(target.texture)->name(), 0);
  }
  glViewport(0, 0, target.width, target.height);
  scissor_.reset();
  glDisable(GL_SCISSOR_TEST);
  glBindVertexArray(emptyVertexArray_.name());
  if (load == rhi::LoadAction::Clear) {
    clear(clearColor, std::nullopt);
  }
}

void GlesDevice::unbindResources() {
  for (GLuint unit = 0; boundUnits_ != 0; ++unit, boundUnits_ >>= 1U) {
    if ((boundUnits_ & 1U) != 0) {
      glActiveTexture(GL_TEXTURE0 + unit);
      glBindTexture(GL_TEXTURE_2D, 0);
      glBindTexture(GL_TEXTURE_3D, 0);
      glBindSampler(unit, 0);
    }
  }
  glActiveTexture(GL_TEXTURE0);
  for (GLuint unit = 0; boundImages_ != 0; ++unit, boundImages_ >>= 1U) {
    if ((boundImages_ & 1U) != 0) {
      glBindImageTexture(unit, 0, 0, GL_FALSE, 0, GL_READ_ONLY, GL_R32UI);
    }
  }
  for (GLuint binding = 0; boundStorageBuffers_ != 0; ++binding, boundStorageBuffers_ >>= 1U) {
    if ((boundStorageBuffers_ & 1U) != 0) {
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, 0);
    }
  }
  boundPipeline_ = nullptr;
  boundUniforms_ = nullptr;
}

void GlesDevice::endPass() {
  glDisable(GL_SCISSOR_TEST);
  glDisable(GL_BLEND);
  unbindResources();
  glBindVertexArray(0);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  scissor_.reset();
}

void GlesDevice::beginCompute() {}

void GlesDevice::endCompute() { unbindResources(); }

void GlesDevice::bindComputePipeline(const rhi::Pipeline& pipeline) {
  const auto& gles = static_cast<const GlesPipeline&>(pipeline);
  gles.program().use();
  boundPipeline_ = &gles;
  boundUniforms_ = &programUniforms_.at(&gles.program());
}

void GlesDevice::bindStorageImage(uint32_t binding, const rhi::Texture& texture,
                                  rhi::StorageAccess access) {
  GLenum glAccess = GL_READ_WRITE;
  if (access == rhi::StorageAccess::ReadOnly) {
    glAccess = GL_READ_ONLY;
  } else if (access == rhi::StorageAccess::WriteOnly) {
    glAccess = GL_WRITE_ONLY;
  }
  glBindImageTexture(binding, textureName(texture), 0, GL_FALSE, 0, glAccess,
                     glFormat(texture.description().format).internalFormat);
  boundImages_ |= 1U << binding;
}

void GlesDevice::bindStorageBuffer(uint32_t binding, const rhi::Buffer& buffer) {
  glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding,
                   static_cast<const GlesBuffer&>(buffer).name());
  boundStorageBuffers_ |= 1U << binding;
}

void GlesDevice::dispatch(uint32_t groupsX, uint32_t groupsY, uint32_t groupsZ) {
  uploadPendingUniforms();
  glDispatchCompute(groupsX, groupsY, groupsZ);
}

void GlesDevice::barrier(uint32_t barrierBits) {
  GLbitfield bits = 0;
  if ((barrierBits & rhi::kBarrierStorage) != 0) {
    bits |= GL_SHADER_STORAGE_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
  }
  if ((barrierBits & rhi::kBarrierSampled) != 0) {
    bits |= GL_TEXTURE_FETCH_BARRIER_BIT;
  }
  if ((barrierBits & rhi::kBarrierCopy) != 0) {
    bits |=
        GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT;
  }
  if (bits != 0) {
    glMemoryBarrier(bits);
  }
}

void GlesDevice::setViewport(const rhi::Rect& rect) {
  glViewport(rect.x, rect.y, rect.width, rect.height);
}

void GlesDevice::setScissor(std::optional<rhi::Rect> rect) {
  scissor_ = rect;
  if (rect) {
    glEnable(GL_SCISSOR_TEST);
    glScissor(rect->x, rect->y, rect->width, rect->height);
  } else {
    glDisable(GL_SCISSOR_TEST);
  }
}

void GlesDevice::clear(std::array<float, 4> color, std::optional<rhi::Rect> rect) {
  if (rect) {
    glEnable(GL_SCISSOR_TEST);
    glScissor(rect->x, rect->y, rect->width, rect->height);
  } else {
    glDisable(GL_SCISSOR_TEST);
  }
  if (targetFormat_ == rhi::Format::R32ui) {
    const std::array<GLuint, 4> values{static_cast<GLuint>(color[0]), static_cast<GLuint>(color[1]),
                                       static_cast<GLuint>(color[2]),
                                       static_cast<GLuint>(color[3])};
    glClearBufferuiv(GL_COLOR, 0, values.data());
  } else {
    glClearColor(color[0], color[1], color[2], color[3]);
    glClear(GL_COLOR_BUFFER_BIT);
  }
  setScissor(scissor_);
}

void GlesDevice::bindPipeline(const rhi::Pipeline& pipeline) {
  const auto& gles = static_cast<const GlesPipeline&>(pipeline);
  gles.bind();
  boundPipeline_ = &gles;
  boundUniforms_ = &programUniforms_.at(&gles.program());
}

void GlesDevice::setUniforms(const void* data, size_t size) {
  pendingUniforms_.assign(static_cast<const uint8_t*>(data),
                          static_cast<const uint8_t*>(data) + size);
}

void GlesDevice::uploadPendingUniforms() {
  if (boundUniforms_ == nullptr || boundUniforms_->layout->members.empty()) {
    return;
  }
  ProgramUniforms& uniforms = *boundUniforms_;
  const UniformLayout& layout = *uniforms.layout;
  if (pendingUniforms_.size() != layout.size) {
    if (!uniforms.reportedSizeMismatch) {
      uniforms.reportedSizeMismatch = true;
      NANQUIM_LOG_ERROR("Uniforms of %s: %zu bytes given, the layout has %u",
                        layout.blockName.c_str(), pendingUniforms_.size(), layout.size);
    }
    return;
  }
  const bool first = uniforms.uploaded.size() != pendingUniforms_.size();
  std::vector<uint8_t> packed;
  for (size_t index = 0; index < layout.members.size(); ++index) {
    const UniformMember& member = layout.members[index];
    const GLint location = uniforms.locations[index];
    const uint32_t bytes = member.count == 0 ? 4U * static_cast<uint32_t>(member.components)
                                             : member.stride * static_cast<uint32_t>(member.count);
    const uint8_t* value = pendingUniforms_.data() + member.offset;
    if (location < 0 ||
        (!first && std::memcmp(uniforms.uploaded.data() + member.offset, value, bytes) == 0)) {
      continue;
    }
    const GLsizei count = member.count == 0 ? 1 : member.count;
    const auto elementBytes = static_cast<size_t>(4 * member.components);
    if (member.count != 0) {
      packed.resize(elementBytes * static_cast<size_t>(count));
      for (GLsizei element = 0; element < count; ++element) {
        std::memcpy(packed.data() + elementBytes * static_cast<size_t>(element),
                    value + member.stride * static_cast<uint32_t>(element), elementBytes);
      }
      value = packed.data();
    }
    const auto* floats = reinterpret_cast<const GLfloat*>(value);
    const auto* ints = reinterpret_cast<const GLint*>(value);
    const auto* uints = reinterpret_cast<const GLuint*>(value);
    switch (member.kind) {
      case UniformMember::Kind::Float:
        switch (member.components) {
          case 1:
            glUniform1fv(location, count, floats);
            break;
          case 2:
            glUniform2fv(location, count, floats);
            break;
          case 3:
            glUniform3fv(location, count, floats);
            break;
          default:
            glUniform4fv(location, count, floats);
            break;
        }
        break;
      case UniformMember::Kind::Int:
      case UniformMember::Kind::Bool:
        switch (member.components) {
          case 1:
            glUniform1iv(location, count, ints);
            break;
          case 2:
            glUniform2iv(location, count, ints);
            break;
          case 3:
            glUniform3iv(location, count, ints);
            break;
          default:
            glUniform4iv(location, count, ints);
            break;
        }
        break;
      case UniformMember::Kind::UInt:
        switch (member.components) {
          case 1:
            glUniform1uiv(location, count, uints);
            break;
          case 2:
            glUniform2uiv(location, count, uints);
            break;
          case 3:
            glUniform3uiv(location, count, uints);
            break;
          default:
            glUniform4uiv(location, count, uints);
            break;
        }
        break;
    }
  }
  uniforms.uploaded = pendingUniforms_;
}

void GlesDevice::bindTexture(uint32_t binding, const rhi::Texture* texture,
                             const rhi::Sampler* sampler) {
  glActiveTexture(GL_TEXTURE0 + binding);
  if (texture != nullptr) {
    const auto* gles = static_cast<const GlesTexture*>(texture);
    glBindTexture(gles->target(), gles->name());
  } else {
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindTexture(GL_TEXTURE_3D, 0);
  }
  glBindSampler(binding, sampler != nullptr ? static_cast<const GlesSampler*>(sampler)->name() : 0);
  boundUnits_ |= 1U << binding;
}

void GlesDevice::bindVertexBuffer(const rhi::Buffer& buffer, size_t offset) {
  vertexBuffer_ = static_cast<const GlesBuffer&>(buffer).name();
  vertexBufferOffset_ = offset;
}

void GlesDevice::draw(uint32_t vertexCount, uint32_t instanceCount) {
  uploadPendingUniforms();
  if (boundPipeline_ != nullptr && boundPipeline_->layout()) {
    boundPipeline_->setVertexBuffer(vertexBuffer_, vertexBufferOffset_);
  }
  const uint32_t unbound =
      boundUniforms_ != nullptr ? boundUniforms_->samplerUnits & ~boundUnits_ : 0U;
  if (unbound != 0) {
    for (GLuint unit = 0; unit < 32; ++unit) {
      if ((unbound & (1U << unit)) != 0) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindTexture(GL_TEXTURE_3D, 0);
        glBindSampler(unit, 0);
      }
    }
    boundUnits_ |= unbound;
  }
  const GLenum mode = boundPipeline_ != nullptr ? boundPipeline_->mode() : GL_TRIANGLES;
  const bool instanced =
      instanceCount != 1 || (boundPipeline_ != nullptr && boundPipeline_->layout() &&
                             boundPipeline_->layout()->perInstance);
  if (!instanced) {
    glDrawArrays(mode, 0, static_cast<GLsizei>(vertexCount));
  } else {
    glDrawArraysInstanced(mode, 0, static_cast<GLsizei>(vertexCount),
                          static_cast<GLsizei>(instanceCount));
  }
}

void GlesDevice::copyTexture(const rhi::Texture& source, const rhi::Rect& sourceRect,
                             const rhi::Texture& destination, int32_t destinationX,
                             int32_t destinationY) {
  glDisable(GL_SCISSOR_TEST);
  attachColorTexture(GL_READ_FRAMEBUFFER, readFramebuffer_, textureName(source));
  attachColorTexture(GL_DRAW_FRAMEBUFFER, copyFramebuffer_, textureName(destination));
  glBlitFramebuffer(sourceRect.x, sourceRect.y, sourceRect.x + sourceRect.width,
                    sourceRect.y + sourceRect.height, destinationX, destinationY,
                    destinationX + sourceRect.width, destinationY + sourceRect.height,
                    GL_COLOR_BUFFER_BIT, GL_NEAREST);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
}

std::optional<ReadLayout> GlesDevice::bindForRead(const rhi::Texture& texture) {
  const rhi::Format format = texture.description().format;
  if (format != rhi::Format::Rgba8 && format != rhi::Format::Rgba16f) {
    NANQUIM_LOG_ERROR("Readback of format %d is not supported", static_cast<int>(format));
    return std::nullopt;
  }
  attachColorTexture(GL_READ_FRAMEBUFFER, readFramebuffer_, textureName(texture));
  std::optional<ReadLayout>& layout = readLayouts_[format == rhi::Format::Rgba8 ? 0 : 1];
  if (!layout) {
    layout =
        queryReadLayout(format == rhi::Format::Rgba8 ? canvas::PixelFormat::Rgba8PremulSrgb
                                                     : canvas::PixelFormat::Rgba16fPremulLinear);
  }
  return layout;
}

namespace {

size_t outputBytesPerPixel(const ReadLayout& layout) {
  return layout.type == GL_FLOAT ? 8 : static_cast<size_t>(layout.bytesPerPixel);
}

}  // namespace

bool GlesDevice::readTexture(const rhi::Texture& texture, const rhi::Rect& rect, void* out) {
  const std::optional<ReadLayout> layout = bindForRead(texture);
  if (!layout) {
    return false;
  }
  const auto pixelCount = static_cast<size_t>(rect.width) * static_cast<size_t>(rect.height);
  std::vector<uint8_t> floats(
      layout->type == GL_FLOAT ? pixelCount * static_cast<size_t>(layout->bytesPerPixel) : 0);
  GLint alignment = 4;
  glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
  glPixelStorei(GL_PACK_ALIGNMENT, 1);
  glReadPixels(rect.x, rect.y, rect.width, rect.height, GL_RGBA, layout->type,
               floats.empty() ? out : floats.data());
  glPixelStorei(GL_PACK_ALIGNMENT, alignment);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  if (!floats.empty()) {
    readToFormat(floats.data(), *layout, static_cast<uint8_t*>(out), pixelCount);
  }
  return true;
}

std::unique_ptr<rhi::Readback> GlesDevice::readTextureAsync(const rhi::Texture& texture,
                                                            const rhi::Rect& rect) {
  const std::optional<ReadLayout> layout = bindForRead(texture);
  if (!layout) {
    return nullptr;
  }
  const auto pixelCount = static_cast<size_t>(rect.width) * static_cast<size_t>(rect.height);
  const auto bytes =
      static_cast<GLsizeiptr>(pixelCount * static_cast<size_t>(layout->bytesPerPixel));
  Buffer buffer = takeReadBuffer(bytes);
  glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer.name());
  GLint alignment = 4;
  glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
  glPixelStorei(GL_PACK_ALIGNMENT, 1);
  glReadPixels(rect.x, rect.y, rect.width, rect.height, GL_RGBA, layout->type, nullptr);
  glPixelStorei(GL_PACK_ALIGNMENT, alignment);
  auto readback =
      std::make_unique<GlesReadback>(*this, std::move(buffer), bytes, *layout, pixelCount,
                                     pixelCount * outputBytesPerPixel(*layout));
  glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  return readback;
}

Buffer GlesDevice::takeReadBuffer(GLsizeiptr bytes) {
  const auto idle = idleReadBuffers_.find(bytes);
  if (idle != idleReadBuffers_.end()) {
    Buffer buffer = std::move(idle->second);
    idleReadBuffers_.erase(idle);
    idleReadBufferBytes_ -= bytes;
    return buffer;
  }
  Buffer buffer = gles::createBuffer();
  glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer.name());
  glBufferData(GL_PIXEL_PACK_BUFFER, bytes, nullptr, GL_STREAM_READ);
  glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  return buffer;
}

void GlesDevice::returnReadBuffer(GLsizeiptr bytes, Buffer buffer) {
  if (idleReadBufferBytes_ + bytes <= kMaxIdleReadBufferBytes) {
    idleReadBuffers_.emplace(bytes, std::move(buffer));
    idleReadBufferBytes_ += bytes;
  }
}

void GlesDevice::submit() { glFlush(); }

std::unique_ptr<rhi::Fence> GlesDevice::insertFence() { return std::make_unique<GlesFence>(); }

void GlesDevice::beginTimer(rhi::TimerQuery& query) {
  glBeginQuery(kTimeElapsed, static_cast<GlesTimerQuery&>(query).name());
}

void GlesDevice::endTimer(rhi::TimerQuery&) { glEndQuery(kTimeElapsed); }

}  // namespace nanquim::gpu::gles
