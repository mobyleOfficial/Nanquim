#pragma once

#include <android/native_window.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace nanquim::gpu::rhi {

enum class Format : uint8_t { Rgba8, Rgba16f, R8, R16f, R32ui, R32f, Rg32f, Rgba32f };
enum class TextureType : uint8_t { Texture2D, Texture3D };
enum class Filter : uint8_t { Nearest, Linear };
enum class AddressMode : uint8_t { ClampToEdge, Repeat };

inline constexpr uint32_t kUsageSampled = 1U << 0U;
inline constexpr uint32_t kUsageRenderTarget = 1U << 1U;
inline constexpr uint32_t kUsageStorage = 1U << 2U;
inline constexpr uint32_t kUsageUpload = 1U << 3U;
inline constexpr uint32_t kUsageReadback = 1U << 4U;

struct TextureDescription {
  TextureType type = TextureType::Texture2D;
  Format format = Format::Rgba8;
  int32_t width = 0;
  int32_t height = 0;
  int32_t depth = 1;
  uint32_t usage = kUsageSampled;
  Filter filter = Filter::Nearest;
  int32_t levels = 1;
};

class Texture {
 public:
  Texture(const Texture&) = delete;
  Texture& operator=(const Texture&) = delete;
  virtual ~Texture() = default;

  [[nodiscard]] const TextureDescription& description() const { return description_; }
  [[nodiscard]] int32_t width() const { return description_.width; }
  [[nodiscard]] int32_t height() const { return description_.height; }

 protected:
  explicit Texture(const TextureDescription& description) : description_(description) {}

 private:
  TextureDescription description_;
};

struct SamplerDescription {
  Filter filter = Filter::Nearest;
  bool mipmaps = false;
  AddressMode address = AddressMode::ClampToEdge;
};

class Sampler {
 public:
  Sampler(const Sampler&) = delete;
  Sampler& operator=(const Sampler&) = delete;
  virtual ~Sampler() = default;

 protected:
  Sampler() = default;
};

struct Rect {
  int32_t x = 0;
  int32_t y = 0;
  int32_t width = 0;
  int32_t height = 0;
};

struct RenderTarget {
  const Texture* texture = nullptr;
  int32_t width = 0;
  int32_t height = 0;
  bool rowsFromBottom = true;

  static RenderTarget of(const Texture& texture) {
    return RenderTarget{&texture, texture.width(), texture.height(), true};
  }
};

enum class BlendFactor : uint8_t {
  Zero,
  One,
  SourceColor,
  OneMinusSourceColor,
  DestinationColor,
  OneMinusDestinationColor,
  SourceAlpha,
  OneMinusSourceAlpha,
  DestinationAlpha,
  OneMinusDestinationAlpha,
};
enum class BlendOperation : uint8_t { Add, Subtract, ReverseSubtract, Min, Max };

enum class Topology : uint8_t { Triangles, TriangleStrip };

struct VertexLayout {
  uint32_t attributes = 0;
  uint32_t stride = 0;
  bool perInstance = true;
  std::vector<uint8_t> components;
};

struct BlendState {
  bool enabled = false;
  BlendFactor sourceColor = BlendFactor::One;
  BlendFactor destinationColor = BlendFactor::Zero;
  BlendFactor sourceAlpha = BlendFactor::One;
  BlendFactor destinationAlpha = BlendFactor::Zero;
  BlendOperation colorOperation = BlendOperation::Add;
  BlendOperation alphaOperation = BlendOperation::Add;
};

struct PipelineDescription {
  std::string program;
  std::vector<std::string> defines;
  BlendState blend;
  Format targetFormat = Format::Rgba8;
  Topology topology = Topology::Triangles;
  std::optional<VertexLayout> vertexLayout;
};

struct ComputePipelineDescription {
  std::string program;
  std::vector<std::string> defines;
};

inline constexpr uint32_t kBufferVertex = 1U << 0U;
inline constexpr uint32_t kBufferStorage = 1U << 1U;

class Buffer {
 public:
  Buffer(const Buffer&) = delete;
  Buffer& operator=(const Buffer&) = delete;
  virtual ~Buffer() = default;

  [[nodiscard]] size_t size() const { return size_; }
  [[nodiscard]] uint32_t usage() const { return usage_; }

 protected:
  Buffer(size_t size, uint32_t usage) : size_(size), usage_(usage) {}
  void resized(size_t size) { size_ = size; }

 private:
  size_t size_;
  uint32_t usage_;
};

enum class StorageAccess : uint8_t { ReadOnly, WriteOnly, ReadWrite };

inline constexpr uint32_t kBarrierStorage = 1U << 0U;
inline constexpr uint32_t kBarrierSampled = 1U << 1U;
inline constexpr uint32_t kBarrierCopy = 1U << 2U;

class Pipeline {
 public:
  Pipeline(const Pipeline&) = delete;
  Pipeline& operator=(const Pipeline&) = delete;
  virtual ~Pipeline() = default;

 protected:
  Pipeline() = default;
};

class Fence {
 public:
  Fence(const Fence&) = delete;
  Fence& operator=(const Fence&) = delete;
  virtual ~Fence() = default;

  [[nodiscard]] virtual bool isSignaled() = 0;
  virtual bool wait(uint64_t timeoutNanoseconds) = 0;

 protected:
  Fence() = default;
};

class Readback {
 public:
  Readback(const Readback&) = delete;
  Readback& operator=(const Readback&) = delete;
  virtual ~Readback() = default;

  [[nodiscard]] virtual bool isReady() = 0;
  virtual bool read(void* out) = 0;
  [[nodiscard]] size_t size() const { return size_; }

 protected:
  explicit Readback(size_t size) : size_(size) {}

 private:
  size_t size_;
};

class TimerQuery {
 public:
  TimerQuery(const TimerQuery&) = delete;
  TimerQuery& operator=(const TimerQuery&) = delete;
  virtual ~TimerQuery() = default;

  [[nodiscard]] virtual std::optional<uint64_t> elapsedNanoseconds() = 0;

 protected:
  TimerQuery() = default;
};

enum class LoadAction : uint8_t { Load, Clear, DontCare };

class CommandContext {
 public:
  CommandContext(const CommandContext&) = delete;
  CommandContext& operator=(const CommandContext&) = delete;
  virtual ~CommandContext() = default;

  virtual void beginPass(const RenderTarget& target, LoadAction load,
                         std::array<float, 4> clearColor = {0, 0, 0, 0}) = 0;
  virtual void endPass() = 0;

  virtual void setViewport(const Rect& rect) = 0;
  virtual void setScissor(std::optional<Rect> rect) = 0;
  virtual void clear(std::array<float, 4> color, std::optional<Rect> rect = std::nullopt) = 0;

  virtual void bindPipeline(const Pipeline& pipeline) = 0;
  virtual void setUniforms(const void* data, size_t size) = 0;
  template <typename T>
  void setUniforms(const T& block) {
    setUniforms(&block, sizeof(T));
  }
  virtual void bindTexture(uint32_t binding, const Texture* texture,
                           const Sampler* sampler = nullptr) = 0;
  virtual void bindVertexBuffer(const Buffer& buffer, size_t offset) = 0;
  virtual void draw(uint32_t vertexCount, uint32_t instanceCount = 1) = 0;

  virtual void beginCompute() = 0;
  virtual void endCompute() = 0;
  virtual void bindComputePipeline(const Pipeline& pipeline) = 0;
  virtual void bindStorageImage(uint32_t binding, const Texture& texture, StorageAccess access) = 0;
  virtual void bindStorageBuffer(uint32_t binding, const Buffer& buffer) = 0;
  virtual void dispatch(uint32_t groupsX, uint32_t groupsY, uint32_t groupsZ) = 0;
  virtual void barrier(uint32_t barrierBits) = 0;

  virtual void copyTexture(const Texture& source, const Rect& sourceRect,
                           const Texture& destination, int32_t destinationX,
                           int32_t destinationY) = 0;
  virtual bool readTexture(const Texture& texture, const Rect& rect, void* out) = 0;
  [[nodiscard]] virtual std::unique_ptr<Readback> readTextureAsync(const Texture& texture,
                                                                   const Rect& rect) = 0;
  virtual void submit() = 0;

  [[nodiscard]] virtual std::unique_ptr<Fence> insertFence() = 0;
  virtual void beginTimer(TimerQuery& query) = 0;
  virtual void endTimer(TimerQuery& query) = 0;

 protected:
  CommandContext() = default;
};

class Device {
 public:
  Device(const Device&) = delete;
  Device& operator=(const Device&) = delete;
  virtual ~Device() = default;

  [[nodiscard]] virtual std::unique_ptr<Texture> createTexture(
      const TextureDescription& description) = 0;
  virtual void uploadTexture(Texture& texture, const void* data) = 0;
  virtual void uploadTextureLevel(Texture& texture, int32_t level, const void* data) = 0;
  virtual void uploadTextureRegion(Texture& texture, const Rect& region, const void* data,
                                   int32_t rowLength) = 0;
  [[nodiscard]] virtual std::unique_ptr<Sampler> createSampler(
      const SamplerDescription& description) = 0;
  [[nodiscard]] std::unique_ptr<Sampler> createSampler(Filter filter) {
    return createSampler(SamplerDescription{filter, false, AddressMode::ClampToEdge});
  }

  [[nodiscard]] virtual std::unique_ptr<Buffer> createBuffer(size_t size, uint32_t usage) = 0;
  virtual void uploadBuffer(Buffer& buffer, const void* data, size_t size) = 0;

  virtual void registerProgram(const std::string& name, std::string vertexSource,
                               std::string fragmentSource) = 0;
  [[nodiscard]] virtual const Pipeline* pipeline(const PipelineDescription& description,
                                                 std::string& errorMessage) = 0;
  virtual void registerComputeProgram(const std::string& name, std::string source) = 0;
  [[nodiscard]] virtual const Pipeline* computePipeline(
      const ComputePipelineDescription& description, std::string& errorMessage) = 0;

  [[nodiscard]] virtual CommandContext& commands() = 0;

  [[nodiscard]] virtual int32_t maxTextureSize() const = 0;
  [[nodiscard]] virtual bool isRenderable(Format format) const = 0;
  [[nodiscard]] virtual bool readsDestination() const = 0;

  [[nodiscard]] virtual bool supportsTimerQueries() const = 0;
  [[nodiscard]] virtual std::unique_ptr<TimerQuery> createTimerQuery() = 0;
  virtual bool timerResultsDisjoint() = 0;

  [[nodiscard]] virtual std::string infoJson() const = 0;

 protected:
  Device() = default;
};

class Presenter {
 public:
  Presenter(const Presenter&) = delete;
  Presenter& operator=(const Presenter&) = delete;
  virtual ~Presenter() = default;

  virtual bool attachWindow(ANativeWindow* window, std::string& errorMessage) = 0;
  virtual void detachWindow() = 0;
  [[nodiscard]] virtual bool hasWindow() const = 0;
  [[nodiscard]] virtual RenderTarget windowTarget(int32_t widthPixels,
                                                  int32_t heightPixels) const = 0;

  [[nodiscard]] virtual bool supportsDisplayP3() const = 0;
  virtual void setDisplayP3(bool displayP3) = 0;
  [[nodiscard]] virtual bool isDisplayP3() const = 0;

  [[nodiscard]] virtual bool supportsFrontBuffer() const = 0;
  virtual void requestFrontBuffer(bool frontBuffered) = 0;
  [[nodiscard]] virtual bool isFrontBuffered() const = 0;
  [[nodiscard]] virtual bool hasPendingSwitch() const { return false; }
  virtual void prepareFrame() {}

  virtual bool present() = 0;

  [[nodiscard]] virtual std::optional<uint64_t> nextFrameId() const = 0;
  enum class PresentTime { Known, Pending, Unavailable };
  virtual PresentTime presentTime(uint64_t frameId, int64_t& presentNanos) const = 0;

 protected:
  Presenter() = default;
};

}  // namespace nanquim::gpu::rhi
