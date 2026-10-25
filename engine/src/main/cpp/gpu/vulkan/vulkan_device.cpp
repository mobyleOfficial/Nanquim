#include "gpu/vulkan/vulkan_device.h"

#include <android/trace.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <new>
#include <stdexcept>
#include <thread>
#include <tuple>

#include "core/json_writer.h"
#include "core/log.h"
#include "gpu/embedded_shader_source.h"

namespace nanquim::gpu::vulkan {
namespace {

constexpr VkDeviceSize kArenaChunkBytes = 4ULL * 1024 * 1024;
constexpr size_t kMaxIdleArenaChunks = 8;
constexpr VkDeviceSize kMaxIdleReadBufferBytes = 256ULL * 1024 * 1024;
constexpr size_t kMaxBatchesInFlight = 8;
constexpr VkDeviceSize kMaxBatchCreatedBytes = 64ULL * 1024 * 1024;
constexpr uint32_t kMaxBatchCreatedAllocations = 128;
constexpr int32_t kPipelinesPerCacheSave = 16;
constexpr uint64_t kLostGpuNanoseconds = 5'000'000'000;

constexpr uint32_t kDescriptorSetsPerPool = 256;

VkFormat vkFormat(rhi::Format format) {
  switch (format) {
    case rhi::Format::Rgba8:
      return VK_FORMAT_R8G8B8A8_UNORM;
    case rhi::Format::Rgba16f:
      return VK_FORMAT_R16G16B16A16_SFLOAT;
    case rhi::Format::R8:
      return VK_FORMAT_R8_UNORM;
    case rhi::Format::R16f:
      return VK_FORMAT_R16_SFLOAT;
    case rhi::Format::R32ui:
      return VK_FORMAT_R32_UINT;
    case rhi::Format::R32f:
      return VK_FORMAT_R32_SFLOAT;
    case rhi::Format::Rg32f:
      return VK_FORMAT_R32G32_SFLOAT;
    case rhi::Format::Rgba32f:
      return VK_FORMAT_R32G32B32A32_SFLOAT;
  }
  return VK_FORMAT_R8G8B8A8_UNORM;
}

uint32_t bytesPerTexel(rhi::Format format) {
  switch (format) {
    case rhi::Format::Rgba8:
      return 4;
    case rhi::Format::Rgba16f:
      return 8;
    case rhi::Format::R8:
      return 1;
    case rhi::Format::R16f:
      return 2;
    case rhi::Format::R32ui:
    case rhi::Format::R32f:
      return 4;
    case rhi::Format::Rg32f:
      return 8;
    case rhi::Format::Rgba32f:
      return 16;
  }
  return 4;
}

VkBlendFactor vkBlendFactor(rhi::BlendFactor factor) {
  switch (factor) {
    case rhi::BlendFactor::Zero:
      return VK_BLEND_FACTOR_ZERO;
    case rhi::BlendFactor::One:
      return VK_BLEND_FACTOR_ONE;
    case rhi::BlendFactor::SourceColor:
      return VK_BLEND_FACTOR_SRC_COLOR;
    case rhi::BlendFactor::OneMinusSourceColor:
      return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
    case rhi::BlendFactor::DestinationColor:
      return VK_BLEND_FACTOR_DST_COLOR;
    case rhi::BlendFactor::OneMinusDestinationColor:
      return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
    case rhi::BlendFactor::SourceAlpha:
      return VK_BLEND_FACTOR_SRC_ALPHA;
    case rhi::BlendFactor::OneMinusSourceAlpha:
      return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    case rhi::BlendFactor::DestinationAlpha:
      return VK_BLEND_FACTOR_DST_ALPHA;
    case rhi::BlendFactor::OneMinusDestinationAlpha:
      return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
  }
  return VK_BLEND_FACTOR_ONE;
}

VkBlendOp vkBlendOperation(rhi::BlendOperation operation) {
  switch (operation) {
    case rhi::BlendOperation::Add:
      return VK_BLEND_OP_ADD;
    case rhi::BlendOperation::Subtract:
      return VK_BLEND_OP_SUBTRACT;
    case rhi::BlendOperation::ReverseSubtract:
      return VK_BLEND_OP_REVERSE_SUBTRACT;
    case rhi::BlendOperation::Min:
      return VK_BLEND_OP_MIN;
    case rhi::BlendOperation::Max:
      return VK_BLEND_OP_MAX;
  }
  return VK_BLEND_OP_ADD;
}

VkAttachmentLoadOp vkLoadOperation(rhi::LoadAction load) {
  switch (load) {
    case rhi::LoadAction::Load:
      return VK_ATTACHMENT_LOAD_OP_LOAD;
    case rhi::LoadAction::Clear:
      return VK_ATTACHMENT_LOAD_OP_CLEAR;
    case rhi::LoadAction::DontCare:
      return VK_ATTACHMENT_LOAD_OP_DONT_CARE;
  }
  return VK_ATTACHMENT_LOAD_OP_LOAD;
}

VkClearColorValue clearValue(VkFormat format, const std::array<float, 4>& color) {
  VkClearColorValue value{};
  if (format == VK_FORMAT_R32_UINT) {
    for (size_t channel = 0; channel < 4; ++channel) {
      value.uint32[channel] = static_cast<uint32_t>(color[channel]);
    }
  } else {
    for (size_t channel = 0; channel < 4; ++channel) {
      value.float32[channel] = color[channel];
    }
  }
  return value;
}

VkDeviceSize alignUp(VkDeviceSize value, VkDeviceSize alignment) {
  return (value + alignment - 1) / alignment * alignment;
}

VkRect2D clampedRect(const rhi::Rect& rect, int32_t width, int32_t height) {
  const int32_t left = std::clamp(rect.x, 0, width);
  const int32_t top = std::clamp(rect.y, 0, height);
  const int32_t right = std::clamp(rect.x + rect.width, left, width);
  const int32_t bottom = std::clamp(rect.y + rect.height, top, height);
  return VkRect2D{{left, top},
                  {static_cast<uint32_t>(right - left), static_cast<uint32_t>(bottom - top)}};
}

std::string pipelineKey(const rhi::PipelineDescription& description,
                        const std::vector<std::string>& defines) {
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

std::vector<std::string> sortedDefines(std::vector<std::string> defines) {
  std::sort(defines.begin(), defines.end());
  defines.erase(std::unique(defines.begin(), defines.end()), defines.end());
  return defines;
}

VkShaderModule createModule(VkDevice device, const std::vector<uint32_t>& words) {
  auto info = make<VkShaderModuleCreateInfo>(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
  info.codeSize = words.size() * sizeof(uint32_t);
  info.pCode = words.data();
  VkShaderModule module = VK_NULL_HANDLE;
  if (vkCreateShaderModule(device, &info, nullptr, &module) != VK_SUCCESS) {
    return VK_NULL_HANDLE;
  }
  return module;
}

uint32_t samplerKey(const rhi::SamplerDescription& description) {
  return static_cast<uint32_t>(description.filter) |
         (static_cast<uint32_t>(description.mipmaps ? 1 : 0) << 2U) |
         (static_cast<uint32_t>(description.address) << 3U);
}

class VulkanSampler final : public rhi::Sampler {
 public:
  explicit VulkanSampler(const rhi::SamplerDescription& description) : description_(description) {}
  [[nodiscard]] const rhi::SamplerDescription& description() const { return description_; }

 private:
  rhi::SamplerDescription description_;
};

class VulkanFence final : public rhi::Fence {
 public:
  VulkanFence(VulkanDevice& device, uint64_t serial) : device_(device), serial_(serial) {}
  bool isSignaled() override { return device_.isComplete(serial_); }
  bool wait(uint64_t timeoutNanoseconds) override {
    return device_.waitForSerial(serial_, timeoutNanoseconds);
  }

 private:
  VulkanDevice& device_;
  uint64_t serial_;
};

class VulkanReadback final : public rhi::Readback {
 public:
  VulkanReadback(VulkanDevice& device, VulkanDevice::HostBuffer buffer, uint64_t serial,
                 size_t bytes)
      : rhi::Readback(bytes), device_(device), buffer_(buffer), serial_(serial) {}
  ~VulkanReadback() override { device_.returnReadBuffer(buffer_); }

  bool isReady() override { return device_.isComplete(serial_); }
  bool read(void* out) override {
    if (!device_.waitForSerial(serial_, kLostGpuNanoseconds)) {
      return false;
    }
    if (!buffer_.coherent) {
      auto range = make<VkMappedMemoryRange>(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE);
      range.memory = buffer_.memory;
      range.size = VK_WHOLE_SIZE;
      vkInvalidateMappedMemoryRanges(device_.context().device(), 1, &range);
    }
    std::memcpy(out, buffer_.mapped, size());
    return true;
  }

 private:
  VulkanDevice& device_;
  VulkanDevice::HostBuffer buffer_;
  uint64_t serial_;
};

class VulkanTimerQuery final : public rhi::TimerQuery {
 public:
  VulkanTimerQuery(VulkanDevice& device, VkQueryPool pool) : device_(device), pool_(pool) {}
  ~VulkanTimerQuery() override {
    VulkanDevice::Retired retired;
    retired.queryPool = pool_;
    device_.retire(retired);
  }

  std::optional<uint64_t> elapsedNanoseconds() override {
    if (!ended_ || !device_.isComplete(serial_)) {
      return std::nullopt;
    }
    std::array<uint64_t, 4> results{};
    const VkResult result = vkGetQueryPoolResults(
        device_.context().device(), pool_, 0, 2, sizeof results, results.data(),
        2 * sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
    if (result != VK_SUCCESS || results[1] == 0 || results[3] == 0) {
      return std::nullopt;
    }
    const uint32_t validBits = device_.context().timestampValidBits();
    const uint64_t mask = validBits >= 64 ? ~0ULL : (1ULL << validBits) - 1;
    const uint64_t ticks = (results[2] - results[0]) & mask;
    return static_cast<uint64_t>(static_cast<double>(ticks) *
                                 device_.context().properties().limits.timestampPeriod);
  }

  [[nodiscard]] VkQueryPool pool() const { return pool_; }
  void ended(uint64_t serial) {
    ended_ = true;
    serial_ = serial;
  }

 private:
  VulkanDevice& device_;
  VkQueryPool pool_;
  bool ended_ = false;
  uint64_t serial_ = 0;
};

}  // namespace

VulkanTexture::VulkanTexture(const rhi::TextureDescription& description, VulkanDevice& device,
                             VkImage image, VkDeviceMemory memory, VkImageView view,
                             VkFormat format)
    : rhi::Texture(description),
      device_(device),
      image_(image),
      memory_(memory),
      view_(view),
      format_(format) {}

VulkanTexture::~VulkanTexture() {
  if (memory_ == VK_NULL_HANDLE) {
    const VkDevice device = device_.context().device();
    vkDestroyFramebuffer(device, framebuffer_, nullptr);
    vkDestroyImageView(device, view_, nullptr);
    return;
  }
  VulkanDevice::Retired retired;
  retired.framebuffer = framebuffer_;
  retired.view = view_;
  retired.image = image_;
  retired.memory = memory_;
  device_.retire(retired);
  if (attachmentView_ != VK_NULL_HANDLE) {
    VulkanDevice::Retired attachment;
    attachment.view = attachmentView_;
    device_.retire(attachment);
  }
}

struct VulkanDevice::Batch {
  VkCommandBuffer commands = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  uint64_t serial = 0;
  bool begun = false;
  std::vector<VkDescriptorPool> pools;
  size_t poolInUse = 0;
  std::vector<HostBuffer> chunks;
  VkDeviceSize chunkUsed = 0;
  std::vector<VkSemaphore> semaphores;
};

struct VulkanDevice::ProgramSources {
  std::string vertex;
  std::string fragment;
  UniformLayout uniforms;
  std::string error;
  bool compute = false;
};

class VulkanDevice::Pipeline final : public rhi::Pipeline {
 public:
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkDescriptorSetLayout set0 = VK_NULL_HANDLE;
  VkDescriptorSetLayout set1 = VK_NULL_HANDLE;
  std::vector<SpirvResource> resources;
  uint32_t uniformSize = 0;
  bool compute = false;
  bool vertexInput = false;
  std::string label;
  mutable bool reportedSizeMismatch = false;
};

class VulkanDevice::Buffer final : public rhi::Buffer {
 public:
  Buffer(VulkanDevice& device, HostBuffer backing, uint32_t usage)
      : rhi::Buffer(static_cast<size_t>(backing.size), usage), device_(device), backing_(backing) {}
  ~Buffer() override { retireBacking(); }

  [[nodiscard]] const HostBuffer& backing() const { return backing_; }
  void used(uint64_t serial) const { lastUse_ = std::max(lastUse_, serial); }

  bool upload(const void* data, size_t size) {
    const bool idle = lastUse_ == 0 || device_.isComplete(lastUse_);
    if (!idle || size > backing_.size) {
      HostBuffer next =
          device_.createHostBuffer(std::max<VkDeviceSize>(size, 16), usageFlags(), false);
      if (next.buffer == VK_NULL_HANDLE) {
        return false;
      }
      retireBacking();
      backing_ = next;
      lastUse_ = 0;
    }
    std::memcpy(backing_.mapped, data, size);
    resized(size);
    return true;
  }

  static VkBufferUsageFlags usageFlags() {
    return VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  }

 private:
  void retireBacking() {
    Retired retired;
    retired.buffer = backing_.buffer;
    retired.memory = backing_.memory;
    device_.retire(retired);
  }

  VulkanDevice& device_;
  HostBuffer backing_;
  mutable uint64_t lastUse_ = 0;
};

struct VulkanDevice::Swapchain {
  VkSwapchainKHR handle = VK_NULL_HANDLE;
  VkExtent2D extent{};
  std::vector<std::unique_ptr<VulkanTexture>> images;
  std::vector<VkSemaphore> rendered;
  bool outdated = false;
  bool shared = false;
  bool presented = false;
};

VulkanDevice::VulkanDevice(std::shared_ptr<VulkanContext> context,
                           const std::string& shaderCacheDirectory)
    : context_(std::move(context)),
      device_(context_->device()),
      compiler_(shaderCacheDirectory.empty() ? std::string() : shaderCacheDirectory + "/spirv") {
  if (!shaderCacheDirectory.empty()) {
    mkdir((shaderCacheDirectory + "/spirv").c_str(), 0700);
    pipelineCachePath_ = shaderCacheDirectory + "/vulkan_pipelines.bin";
  }
  const VkPhysicalDeviceLimits& limits = context_->properties().limits;
  uniformAlignment_ = std::max<VkDeviceSize>(limits.minUniformBufferOffsetAlignment, 16);
  timerQueriesSupported_ =
      limits.timestampComputeAndGraphics == VK_TRUE && context_->timestampValidBits() > 0;
  for (size_t format = 0; format < linearFilterable_.size(); ++format) {
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(context_->physicalDevice(),
                                        vkFormat(static_cast<rhi::Format>(format)), &properties);
    linearFilterable_[format] =
        (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
  }
  if (context_->hasSharedPresentableImage()) {
    getSurfaceCapabilities2_ = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR>(
        vkGetInstanceProcAddr(context_->instance(), "vkGetPhysicalDeviceSurfaceCapabilities2KHR"));
  }
  if (context_->hasDisplayTiming()) {
    getPastPresentationTiming_ = reinterpret_cast<PFN_vkGetPastPresentationTimingGOOGLE>(
        vkGetDeviceProcAddr(device_, "vkGetPastPresentationTimingGOOGLE"));
  }
  createSharedObjects();
}

void VulkanDevice::createSharedObjects() {
  auto poolInfo = make<VkCommandPoolCreateInfo>(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
  poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  poolInfo.queueFamilyIndex = context_->queueFamily();
  if (vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
    setupError_ = "vkCreateCommandPool failed";
    return;
  }

  std::vector<char> cacheData;
  if (!pipelineCachePath_.empty()) {
    std::ifstream stream(pipelineCachePath_, std::ios::binary);
    cacheData.assign(std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>());
  }
  auto cacheInfo = make<VkPipelineCacheCreateInfo>(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
  cacheInfo.initialDataSize = cacheData.size();
  cacheInfo.pInitialData = cacheData.empty() ? nullptr : cacheData.data();
  if (vkCreatePipelineCache(device_, &cacheInfo, nullptr, &pipelineCache_) != VK_SUCCESS) {
    cacheInfo.initialDataSize = 0;
    cacheInfo.pInitialData = nullptr;
    vkCreatePipelineCache(device_, &cacheInfo, nullptr, &pipelineCache_);
  }

  auto layoutInfo =
      make<VkDescriptorSetLayoutCreateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
  vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &emptySetLayout_);

  recording_ = std::make_unique<Batch>();
  recording_->serial = recordingSerial_;

  const auto makePlaceholder = [&](rhi::TextureType type, rhi::Format format) {
    rhi::TextureDescription description;
    description.type = type;
    description.format = format;
    description.width = 1;
    description.height = 1;
    std::unique_ptr<rhi::Texture> texture = createTexture(description);
    if (!texture) {
      setupError_ = "Placeholder texture failed";
      return std::unique_ptr<VulkanTexture>();
    }
    const std::array<uint8_t, 4> zero{};
    uploadTexture(*texture, zero.data());
    return std::unique_ptr<VulkanTexture>(static_cast<VulkanTexture*>(texture.release()));
  };
  placeholder2D_ = makePlaceholder(rhi::TextureType::Texture2D, rhi::Format::Rgba8);
  placeholder3D_ = makePlaceholder(rhi::TextureType::Texture3D, rhi::Format::Rgba8);
  placeholderUInt_ = makePlaceholder(rhi::TextureType::Texture2D, rhi::Format::R32ui);
}

VulkanDevice::~VulkanDevice() {
  try {
    shutdown();
  } catch (const std::exception& error) {
    NANQUIM_LOG_ERROR("Vulkan device shutdown: %s", error.what());
  }
}

void VulkanDevice::shutdown() {
  detachWindow();
  if (recording_ && recording_->begun) {
    submitBatch();
  }
  vkDeviceWaitIdle(device_);
  collect();
  placeholder2D_.reset();
  placeholder3D_.reset();
  placeholderUInt_.reset();
  savePipelineCache();
  for (auto& [key, pipeline] : pipelines_) {
    vkDestroyPipeline(device_, pipeline->pipeline, nullptr);
  }
  pipelines_.clear();
  completedSerial_ = ~0ULL;
  collect();
  for (auto& [key, buffer] : idleReadBuffers_) {
    vkDestroyBuffer(device_, buffer.buffer, nullptr);
    vkFreeMemory(device_, buffer.memory, nullptr);
  }
  for (HostBuffer& chunk : idleArenaChunks_) {
    vkDestroyBuffer(device_, chunk.buffer, nullptr);
    vkFreeMemory(device_, chunk.memory, nullptr);
  }
  const auto destroyBatch = [&](Batch& batch) {
    for (VkDescriptorPool pool : batch.pools) {
      vkDestroyDescriptorPool(device_, pool, nullptr);
    }
    for (HostBuffer& chunk : batch.chunks) {
      vkDestroyBuffer(device_, chunk.buffer, nullptr);
      vkFreeMemory(device_, chunk.memory, nullptr);
    }
    for (VkSemaphore semaphore : batch.semaphores) {
      vkDestroySemaphore(device_, semaphore, nullptr);
    }
    if (batch.fence != VK_NULL_HANDLE) {
      vkDestroyFence(device_, batch.fence, nullptr);
    }
  };
  if (recording_) {
    destroyBatch(*recording_);
  }
  for (auto& batch : idleBatches_) {
    destroyBatch(*batch);
  }
  for (VkSemaphore semaphore : idleSemaphores_) {
    vkDestroySemaphore(device_, semaphore, nullptr);
  }
  for (VkSemaphore semaphore : waitSemaphores_) {
    vkDestroySemaphore(device_, semaphore, nullptr);
  }
  for (auto& [key, pipelineLayout] : pipelineLayouts_) {
    vkDestroyPipelineLayout(device_, pipelineLayout, nullptr);
  }
  for (auto& [key, setLayout] : setLayouts_) {
    vkDestroyDescriptorSetLayout(device_, setLayout, nullptr);
  }
  vkDestroyDescriptorSetLayout(device_, emptySetLayout_, nullptr);
  for (auto& [key, pass] : renderPasses_) {
    vkDestroyRenderPass(device_, pass, nullptr);
  }
  for (auto& [key, handle] : samplers_) {
    vkDestroySampler(device_, handle, nullptr);
  }
  if (pipelineCache_ != VK_NULL_HANDLE) {
    vkDestroyPipelineCache(device_, pipelineCache_, nullptr);
  }
  if (commandPool_ != VK_NULL_HANDLE) {
    vkDestroyCommandPool(device_, commandPool_, nullptr);
  }
}

void VulkanDevice::savePipelineCache() {
  if (pipelineCachePath_.empty() || pipelineCache_ == VK_NULL_HANDLE) {
    return;
  }
  size_t size = 0;
  if (vkGetPipelineCacheData(device_, pipelineCache_, &size, nullptr) != VK_SUCCESS || size == 0) {
    return;
  }
  std::vector<char> data(size);
  if (vkGetPipelineCacheData(device_, pipelineCache_, &size, data.data()) != VK_SUCCESS) {
    return;
  }
  const std::string temporaryPath = pipelineCachePath_ + ".tmp";
  {
    std::ofstream stream(temporaryPath, std::ios::binary | std::ios::trunc);
    stream.write(data.data(), static_cast<std::streamsize>(size));
  }
  std::rename(temporaryPath.c_str(), pipelineCachePath_.c_str());
}

std::string VulkanDevice::infoJson() const {
  const VkPhysicalDeviceProperties& properties = context_->properties();
  uint32_t count = 0;
  vkEnumerateDeviceExtensionProperties(context_->physicalDevice(), nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> extensions(count);
  vkEnumerateDeviceExtensionProperties(context_->physicalDevice(), nullptr, &count,
                                       extensions.data());
  const auto version = [](uint32_t value) {
    return std::to_string(VK_VERSION_MAJOR(value)) + "." + std::to_string(VK_VERSION_MINOR(value)) +
           "." + std::to_string(VK_VERSION_PATCH(value));
  };
  char vendor[16];
  std::snprintf(vendor, sizeof vendor, "0x%04x", properties.vendorID);
  core::JsonWriter writer;
  writer.beginObject();
  writer.key("api");
  writer.stringValue("vulkan");
  writer.key("vendor");
  writer.stringValue(vendor);
  writer.key("renderer");
  writer.stringValue(properties.deviceName);
  writer.key("version");
  writer.stringValue("Vulkan " + version(properties.apiVersion) + " (driver " +
                     std::to_string(properties.driverVersion) + ")");
  writer.key("versionMajor");
  writer.integerValue(static_cast<int64_t>(VK_VERSION_MAJOR(properties.apiVersion)));
  writer.key("versionMinor");
  writer.integerValue(static_cast<int64_t>(VK_VERSION_MINOR(properties.apiVersion)));
  writer.key("shadingLanguageVersion");
  writer.stringValue("SPIR-V 1.3 (glslang)");
  writer.key("maxTextureSize");
  writer.integerValue(static_cast<int64_t>(properties.limits.maxImageDimension2D));
  writer.key("maxRenderbufferSize");
  writer.integerValue(static_cast<int64_t>(properties.limits.maxFramebufferWidth));
  writer.key("supportsColorBufferHalfFloat");
  writer.booleanValue(true);
  writer.key("programBinaryFormats");
  writer.beginArray();
  writer.endArray();
  writer.key("extensions");
  writer.beginArray();
  for (const VkExtensionProperties& extension : extensions) {
    writer.stringValue(extension.extensionName);
  }
  writer.endArray();
  writer.key("eglVersion");
  writer.stringValue("");
  writer.key("eglVendor");
  writer.stringValue("");
  writer.key("eglExtensions");
  writer.beginArray();
  writer.endArray();
  writer.endObject();
  return writer.text();
}

bool VulkanDevice::isRenderable(rhi::Format format) const {
  switch (format) {
    case rhi::Format::Rg32f:
    case rhi::Format::R32f:
    case rhi::Format::Rgba32f:
      return false;
    default:
      return true;
  }
}

VkCommandBuffer VulkanDevice::recording() {
  Batch& batch = *recording_;
  if (batch.commands == VK_NULL_HANDLE) {
    auto allocate =
        make<VkCommandBufferAllocateInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
    allocate.commandPool = commandPool_;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    vkAllocateCommandBuffers(device_, &allocate, &batch.commands);
    auto fenceInfo = make<VkFenceCreateInfo>(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
    vkCreateFence(device_, &fenceInfo, nullptr, &batch.fence);
  }
  if (!batch.begun) {
    auto begin = make<VkCommandBufferBeginInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(batch.commands, &begin);
    batch.begun = true;
  }
  return batch.commands;
}

void VulkanDevice::submitBatch(VkSemaphore signal) {
  const VkCommandBuffer commands = recording();
  if (renderPassActive_) {
    vkCmdEndRenderPass(commands);
    renderPassActive_ = false;
    pendingLoad_ = rhi::LoadAction::Load;
  }
  vkEndCommandBuffer(commands);
  Batch& batch = *recording_;
  const std::vector<VkPipelineStageFlags> waitStages(waitSemaphores_.size(),
                                                     VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
  auto info = make<VkSubmitInfo>(VK_STRUCTURE_TYPE_SUBMIT_INFO);
  info.waitSemaphoreCount = static_cast<uint32_t>(waitSemaphores_.size());
  info.pWaitSemaphores = waitSemaphores_.data();
  info.pWaitDstStageMask = waitStages.data();
  info.commandBufferCount = 1;
  info.pCommandBuffers = &commands;
  info.signalSemaphoreCount = signal != VK_NULL_HANDLE ? 1 : 0;
  info.pSignalSemaphores = &signal;
  const VkResult result = vkQueueSubmit(context_->queue(), 1, &info, batch.fence);
  if (result != VK_SUCCESS) {
    NANQUIM_LOG_ERROR("%s", describeVkResult("vkQueueSubmit", result).c_str());
  }
  batch.semaphores.insert(batch.semaphores.end(), waitSemaphores_.begin(), waitSemaphores_.end());
  waitSemaphores_.clear();
  inFlight_.push_back(std::move(recording_));

  ++recordingSerial_;
  batchCreatedBytes_ = 0;
  batchCreatedAllocations_ = 0;
  if (!idleBatches_.empty()) {
    recording_ = std::move(idleBatches_.back());
    idleBatches_.pop_back();
  } else {
    recording_ = std::make_unique<Batch>();
  }
  recording_->serial = recordingSerial_;
  pipelineDirty_ = true;
  dynamicStateDirty_ = true;
  texturesDirty_ = true;
  storageDirty_ = true;
  set0_ = VK_NULL_HANDLE;
  set1_ = VK_NULL_HANDLE;

  collect();
  if (inFlight_.size() > kMaxBatchesInFlight) {
    vkWaitForFences(device_, 1, &inFlight_.front()->fence, VK_TRUE, kLostGpuNanoseconds);
    collect();
  }
}

void VulkanDevice::collect() {
  while (!inFlight_.empty()) {
    Batch& batch = *inFlight_.front();
    if (completedSerial_ < batch.serial && vkGetFenceStatus(device_, batch.fence) != VK_SUCCESS) {
      break;
    }
    completedSerial_ = std::max(completedSerial_, batch.serial);
    vkResetFences(device_, 1, &batch.fence);
    vkResetCommandBuffer(batch.commands, 0);
    batch.begun = false;
    for (VkDescriptorPool pool : batch.pools) {
      vkResetDescriptorPool(device_, pool, 0);
    }
    batch.poolInUse = 0;
    for (HostBuffer& chunk : batch.chunks) {
      if (chunk.size == kArenaChunkBytes && idleArenaChunks_.size() < kMaxIdleArenaChunks) {
        idleArenaChunks_.push_back(chunk);
      } else {
        vkDestroyBuffer(device_, chunk.buffer, nullptr);
        vkFreeMemory(device_, chunk.memory, nullptr);
      }
    }
    batch.chunks.clear();
    batch.chunkUsed = 0;
    idleSemaphores_.insert(idleSemaphores_.end(), batch.semaphores.begin(), batch.semaphores.end());
    batch.semaphores.clear();
    idleBatches_.push_back(std::move(inFlight_.front()));
    inFlight_.pop_front();
  }
  while (!retired_.empty() && retired_.front().first <= completedSerial_) {
    destroyRetired(retired_.front().second);
    retired_.pop_front();
  }
  while (!retiredSwapchains_.empty() && retiredSwapchains_.front().first <= completedSerial_) {
    releaseSwapchain(*retiredSwapchains_.front().second);
    retiredSwapchains_.pop_front();
  }
}

void VulkanDevice::destroyRetired(const Retired& objects) noexcept {
  if (objects.framebuffer != VK_NULL_HANDLE) {
    vkDestroyFramebuffer(device_, objects.framebuffer, nullptr);
  }
  if (objects.view != VK_NULL_HANDLE) {
    vkDestroyImageView(device_, objects.view, nullptr);
  }
  if (objects.image != VK_NULL_HANDLE) {
    vkDestroyImage(device_, objects.image, nullptr);
  }
  if (objects.buffer != VK_NULL_HANDLE) {
    vkDestroyBuffer(device_, objects.buffer, nullptr);
  }
  if (objects.memory != VK_NULL_HANDLE) {
    vkFreeMemory(device_, objects.memory, nullptr);
  }
  if (objects.queryPool != VK_NULL_HANDLE) {
    vkDestroyQueryPool(device_, objects.queryPool, nullptr);
  }
}

void VulkanDevice::retire(const Retired& objects) noexcept {
  try {
    retired_.emplace_back(recordingSerial_, objects);
  } catch (const std::bad_alloc&) {
    vkDeviceWaitIdle(device_);
    destroyRetired(objects);
  }
}

bool VulkanDevice::isComplete(uint64_t serial) {
  if (serial > completedSerial_) {
    collect();
  }
  return serial <= completedSerial_;
}

bool VulkanDevice::waitForSerial(uint64_t serial, uint64_t timeoutNanoseconds) {
  if (isComplete(serial)) {
    return true;
  }
  if (serial >= recordingSerial_) {
    submitBatch();
  }
  for (const auto& batch : inFlight_) {
    if (batch->serial >= serial) {
      const VkResult result =
          vkWaitForFences(device_, 1, &batch->fence, VK_TRUE, timeoutNanoseconds);
      if (result != VK_SUCCESS) {
        return false;
      }
      break;
    }
  }
  return isComplete(serial);
}

void VulkanDevice::submit() {
  if (recording_->begun) {
    submitBatch();
  }
}

void VulkanDevice::submitIfHeavy() {
  if (batchCreatedBytes_ > kMaxBatchCreatedBytes ||
      batchCreatedAllocations_ > kMaxBatchCreatedAllocations) {
    submit();
  }
}

std::unique_ptr<rhi::Fence> VulkanDevice::insertFence() {
  return std::make_unique<VulkanFence>(*this, recordingSerial_);
}

VulkanDevice::HostBuffer VulkanDevice::createHostBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                                        bool cached) {
  HostBuffer result;
  auto info = make<VkBufferCreateInfo>(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
  info.size = size;
  info.usage = usage;
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  if (vkCreateBuffer(device_, &info, nullptr, &result.buffer) != VK_SUCCESS) {
    return HostBuffer{};
  }
  VkMemoryRequirements requirements{};
  vkGetBufferMemoryRequirements(device_, result.buffer, &requirements);
  int32_t type = -1;
  if (cached) {
    type =
        context_->memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                              VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
  }
  if (type < 0) {
    type =
        context_->memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  }
  auto allocate = make<VkMemoryAllocateInfo>(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
  allocate.allocationSize = requirements.size;
  allocate.memoryTypeIndex = static_cast<uint32_t>(type);
  if (type < 0 || vkAllocateMemory(device_, &allocate, nullptr, &result.memory) != VK_SUCCESS) {
    NANQUIM_LOG_ERROR("Host buffer of %llu bytes: out of memory",
                      static_cast<unsigned long long>(size));
    vkDestroyBuffer(device_, result.buffer, nullptr);
    return HostBuffer{};
  }
  vkBindBufferMemory(device_, result.buffer, result.memory, 0);
  vkMapMemory(device_, result.memory, 0, VK_WHOLE_SIZE, 0, &result.mapped);
  batchCreatedBytes_ += size;
  ++batchCreatedAllocations_;
  result.size = size;
  result.coherent = context_->isHostCoherent(static_cast<uint32_t>(type));
  return result;
}

void VulkanDevice::returnReadBuffer(HostBuffer buffer) noexcept {
  if (buffer.buffer == VK_NULL_HANDLE) {
    return;
  }
  if (idleReadBufferBytes_ + buffer.size <= kMaxIdleReadBufferBytes) {
    try {
      idleReadBuffers_.emplace(buffer.size, buffer);
      idleReadBufferBytes_ += buffer.size;
      return;
    } catch (const std::bad_alloc&) {
      NANQUIM_LOG_ERROR("Readback buffer not kept for reuse: out of memory");
    }
  }
  Retired retired;
  retired.buffer = buffer.buffer;
  retired.memory = buffer.memory;
  retire(retired);
}

VulkanDevice::Allocation VulkanDevice::allocateUpload(VkDeviceSize size, VkDeviceSize alignment) {
  Batch& batch = *recording_;
  if (!batch.chunks.empty()) {
    const VkDeviceSize offset = alignUp(batch.chunkUsed, alignment);
    const HostBuffer& chunk = batch.chunks.back();
    if (offset + size <= chunk.size) {
      batch.chunkUsed = offset + size;
      return Allocation{chunk.buffer, offset, static_cast<uint8_t*>(chunk.mapped) + offset};
    }
  }
  HostBuffer chunk;
  if (size <= kArenaChunkBytes && !idleArenaChunks_.empty()) {
    chunk = idleArenaChunks_.back();
    idleArenaChunks_.pop_back();
  } else {
    chunk = createHostBuffer(std::max(size, kArenaChunkBytes),
                             VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                             false);
    if (chunk.buffer == VK_NULL_HANDLE) {
      return Allocation{};
    }
  }
  batch.chunks.push_back(chunk);
  batch.chunkUsed = size;
  return Allocation{chunk.buffer, 0, static_cast<uint8_t*>(chunk.mapped)};
}

bool VulkanDevice::needsBarrier(const ImageState& state, VkImageLayout layout, bool write) {
  return state.layout != layout || state.written || write;
}

void VulkanDevice::useImage(const VulkanTexture& texture, VkImageLayout layout,
                            VkPipelineStageFlags stages, VkAccessFlags access, bool write) {
  ImageState& state = texture.state();
  if (!needsBarrier(state, layout, write)) {
    state.stages |= stages;
    state.access |= access;
    return;
  }
  auto barrier = make<VkImageMemoryBarrier>(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
  barrier.srcAccessMask = state.written ? state.access : 0;
  barrier.dstAccessMask = access;
  barrier.oldLayout = state.layout;
  barrier.newLayout = layout;
  barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.image = texture.image();
  barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_REMAINING_MIP_LEVELS, 0, 1};
  pendingBarriers_.push_back(barrier);
  pendingSourceStages_ |= state.stages;
  pendingDestinationStages_ |= stages;
  state = ImageState{layout, stages, access, write};
}

void VulkanDevice::flushBarriers() {
  if (pendingBarriers_.empty()) {
    return;
  }
  vkCmdPipelineBarrier(
      recording(),
      pendingSourceStages_ != 0 ? pendingSourceStages_ : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      pendingDestinationStages_, 0, 0, nullptr, 0, nullptr,
      static_cast<uint32_t>(pendingBarriers_.size()), pendingBarriers_.data());
  pendingBarriers_.clear();
  pendingSourceStages_ = 0;
  pendingDestinationStages_ = 0;
}

void VulkanDevice::barrier(uint32_t barrierBits) {
  if ((barrierBits & rhi::kBarrierStorage) == 0) {
    return;
  }
  suspendRenderPass();
  auto memory = make<VkMemoryBarrier>(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
  memory.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  memory.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(recording(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT |
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                       0, 1, &memory, 0, nullptr, 0, nullptr);
}

std::unique_ptr<rhi::Texture> VulkanDevice::createTexture(
    const rhi::TextureDescription& description) {
  const VkFormat format = vkFormat(description.format);
  const bool is3D = description.type == rhi::TextureType::Texture3D;
  const int32_t levels = is3D ? 1 : std::max(description.levels, 1);
  auto info = make<VkImageCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
  info.imageType = is3D ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D;
  info.format = format;
  info.extent = {static_cast<uint32_t>(description.width),
                 static_cast<uint32_t>(description.height),
                 static_cast<uint32_t>(is3D ? description.depth : 1)};
  info.mipLevels = static_cast<uint32_t>(levels);
  info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
               VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  if (!is3D && isRenderable(description.format)) {
    info.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
  }
  if ((description.usage & rhi::kUsageStorage) != 0) {
    info.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
  }
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  VkImage image = VK_NULL_HANDLE;
  VkResult result = vkCreateImage(device_, &info, nullptr, &image);
  if (result != VK_SUCCESS) {
    NANQUIM_LOG_ERROR("Texture of %d×%d×%d: %s", description.width, description.height,
                      description.depth, describeVkResult("vkCreateImage", result).c_str());
    return nullptr;
  }
  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(device_, image, &requirements);
  const int32_t type =
      context_->memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  auto allocate = make<VkMemoryAllocateInfo>(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
  allocate.allocationSize = requirements.size;
  allocate.memoryTypeIndex = static_cast<uint32_t>(std::max(type, 0));
  VkDeviceMemory memory = VK_NULL_HANDLE;
  result = type < 0 ? VK_ERROR_OUT_OF_DEVICE_MEMORY
                    : vkAllocateMemory(device_, &allocate, nullptr, &memory);
  if (result != VK_SUCCESS) {
    NANQUIM_LOG_ERROR("Texture of %d×%d×%d: out of GPU memory", description.width,
                      description.height, description.depth);
    vkDestroyImage(device_, image, nullptr);
    return nullptr;
  }
  vkBindImageMemory(device_, image, memory, 0);
  auto viewInfo = make<VkImageViewCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
  viewInfo.image = image;
  viewInfo.viewType = is3D ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D;
  viewInfo.format = format;
  viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, static_cast<uint32_t>(levels), 0, 1};
  VkImageView view = VK_NULL_HANDLE;
  vkCreateImageView(device_, &viewInfo, nullptr, &view);
  rhi::TextureDescription stored = description;
  stored.levels = levels;
  return std::make_unique<VulkanTexture>(stored, *this, image, memory, view, format);
}

void VulkanDevice::uploadTexture(rhi::Texture& texture, const void* data) {
  const rhi::TextureDescription& description = texture.description();
  uploadToImage(static_cast<const VulkanTexture&>(texture), 0,
                rhi::Rect{0, 0, description.width, description.height}, data, description.width);
}

void VulkanDevice::uploadTextureLevel(rhi::Texture& texture, int32_t level, const void* data) {
  const rhi::TextureDescription& description = texture.description();
  const int32_t width = std::max(description.width >> level, 1);
  const int32_t height = std::max(description.height >> level, 1);
  uploadToImage(static_cast<const VulkanTexture&>(texture), level, rhi::Rect{0, 0, width, height},
                data, width);
}

void VulkanDevice::uploadTextureRegion(rhi::Texture& texture, const rhi::Rect& region,
                                       const void* data, int32_t rowLength) {
  uploadToImage(static_cast<const VulkanTexture&>(texture), 0, region, data, rowLength);
}

void VulkanDevice::uploadToImage(const VulkanTexture& texture, int32_t level,
                                 const rhi::Rect& region, const void* data, int32_t rowLength) {
  if (region.width <= 0 || region.height <= 0) {
    return;
  }
  const rhi::TextureDescription& description = texture.description();
  const bool is3D = description.type == rhi::TextureType::Texture3D;
  const int32_t depth = is3D ? description.depth : 1;
  const VkDeviceSize texelBytes = bytesPerTexel(description.format);
  const VkDeviceSize rowBytes = static_cast<VkDeviceSize>(rowLength) * texelBytes;
  const VkDeviceSize bytes =
      (static_cast<VkDeviceSize>(region.height) * static_cast<VkDeviceSize>(depth) - 1) * rowBytes +
      static_cast<VkDeviceSize>(region.width) * texelBytes;
  const Allocation staging = allocateUpload(bytes, 16);
  if (staging.buffer == VK_NULL_HANDLE) {
    return;
  }
  std::memcpy(staging.mapped, data, bytes);
  suspendRenderPass();
  useImage(texture, layoutFor(texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
           VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, true);
  flushBarriers();
  VkBufferImageCopy copy{};
  copy.bufferOffset = staging.offset;
  copy.bufferRowLength = static_cast<uint32_t>(rowLength);
  copy.bufferImageHeight = static_cast<uint32_t>(region.height);
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, static_cast<uint32_t>(level), 0, 1};
  copy.imageOffset = {region.x, region.y, 0};
  copy.imageExtent = {static_cast<uint32_t>(region.width), static_cast<uint32_t>(region.height),
                      static_cast<uint32_t>(depth)};
  vkCmdCopyBufferToImage(recording(), staging.buffer, texture.image(), texture.state().layout, 1,
                         &copy);
}

std::unique_ptr<rhi::Sampler> VulkanDevice::createSampler(
    const rhi::SamplerDescription& description) {
  return std::make_unique<VulkanSampler>(description);
}

VkSampler VulkanDevice::sampler(const rhi::SamplerDescription& description) {
  const uint32_t key = samplerKey(description);
  const auto existing = samplers_.find(key);
  if (existing != samplers_.end()) {
    return existing->second;
  }
  const bool linear = description.filter == rhi::Filter::Linear;
  const VkSamplerAddressMode address = description.address == rhi::AddressMode::Repeat
                                           ? VK_SAMPLER_ADDRESS_MODE_REPEAT
                                           : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  auto info = make<VkSamplerCreateInfo>(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
  info.magFilter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  info.minFilter = info.magFilter;
  info.mipmapMode = linear && description.mipmaps ? VK_SAMPLER_MIPMAP_MODE_LINEAR
                                                  : VK_SAMPLER_MIPMAP_MODE_NEAREST;
  info.addressModeU = address;
  info.addressModeV = address;
  info.addressModeW = address;
  info.minLod = 0;
  info.maxLod = description.mipmaps ? VK_LOD_CLAMP_NONE : 0.0F;
  info.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
  VkSampler handle = VK_NULL_HANDLE;
  vkCreateSampler(device_, &info, nullptr, &handle);
  samplers_.emplace(key, handle);
  return handle;
}

std::unique_ptr<rhi::Buffer> VulkanDevice::createBuffer(size_t size, uint32_t usage) {
  const HostBuffer backing =
      createHostBuffer(std::max<VkDeviceSize>(size, 16), Buffer::usageFlags(), false);
  if (backing.buffer == VK_NULL_HANDLE) {
    return nullptr;
  }
  return std::make_unique<Buffer>(*this, backing, usage);
}

void VulkanDevice::uploadBuffer(rhi::Buffer& buffer, const void* data, size_t size) {
  static_cast<Buffer&>(buffer).upload(data, size);
}

std::unique_ptr<rhi::TimerQuery> VulkanDevice::createTimerQuery() {
  auto info = make<VkQueryPoolCreateInfo>(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
  info.queryType = VK_QUERY_TYPE_TIMESTAMP;
  info.queryCount = 2;
  VkQueryPool pool = VK_NULL_HANDLE;
  if (vkCreateQueryPool(device_, &info, nullptr, &pool) != VK_SUCCESS) {
    return nullptr;
  }
  return std::make_unique<VulkanTimerQuery>(*this, pool);
}

void VulkanDevice::beginTimer(rhi::TimerQuery& query) {
  auto& timer = static_cast<VulkanTimerQuery&>(query);
  suspendRenderPass();
  const VkCommandBuffer commands = recording();
  vkCmdResetQueryPool(commands, timer.pool(), 0, 2);
  vkCmdWriteTimestamp(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timer.pool(), 0);
}

void VulkanDevice::endTimer(rhi::TimerQuery& query) {
  auto& timer = static_cast<VulkanTimerQuery&>(query);
  vkCmdWriteTimestamp(recording(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timer.pool(), 1);
  timer.ended(recordingSerial_);
}

void VulkanDevice::registerProgram(const std::string& name, std::string vertexSource,
                                   std::string fragmentSource) {
  auto sources = std::make_unique<ProgramSources>();
  sources->vertex = std::move(vertexSource);
  sources->fragment = std::move(fragmentSource);
  UniformLayout vertexUniforms;
  UniformLayout fragmentUniforms;
  std::string error;
  if (!parseUniformLayout(withEmbeddedIncludes(sources->vertex), vertexUniforms, error) ||
      !parseUniformLayout(withEmbeddedIncludes(sources->fragment), fragmentUniforms, error)) {
    sources->error = name + ": " + error;
  } else if (!vertexUniforms.blockName.empty() && !fragmentUniforms.blockName.empty() &&
             (vertexUniforms.size != fragmentUniforms.size ||
              vertexUniforms.members.size() != fragmentUniforms.members.size())) {
    sources->error = name + ": the stages declare different uniforms";
  }
  sources->uniforms =
      vertexUniforms.blockName.empty() ? std::move(fragmentUniforms) : std::move(vertexUniforms);
  programs_[name] = std::move(sources);
}

void VulkanDevice::registerComputeProgram(const std::string& name, std::string source) {
  auto sources = std::make_unique<ProgramSources>();
  sources->compute = true;
  sources->fragment = std::move(source);
  std::string error;
  if (!parseUniformLayout(withEmbeddedIncludes(sources->fragment), sources->uniforms, error)) {
    sources->error = name + ": " + error;
  }
  programs_[name] = std::move(sources);
}

const rhi::Pipeline* VulkanDevice::pipeline(const rhi::PipelineDescription& description,
                                            std::string& errorMessage) {
  const std::string key = pipelineKey(description, sortedDefines(description.defines));
  const auto existing = pipelines_.find(key);
  if (existing != pipelines_.end()) {
    return existing->second.get();
  }
  return createPipeline(key, &description, nullptr, errorMessage);
}

const rhi::Pipeline* VulkanDevice::computePipeline(
    const rhi::ComputePipelineDescription& description, std::string& errorMessage) {
  std::string key = "compute:" + description.program;
  for (const std::string& define : sortedDefines(description.defines)) {
    key += "+" + define;
  }
  const auto existing = pipelines_.find(key);
  if (existing != pipelines_.end()) {
    return existing->second.get();
  }
  return createPipeline(key, nullptr, &description, errorMessage);
}

VkDescriptorSetLayout VulkanDevice::setLayout(const std::vector<SpirvResource>& resources,
                                              uint32_t set) {
  std::string signature;
  std::vector<VkDescriptorSetLayoutBinding> bindings;
  for (const SpirvResource& resource : resources) {
    if (resource.set != set) {
      continue;
    }
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = resource.binding;
    binding.descriptorCount = resource.count;
    binding.stageFlags = VK_SHADER_STAGE_ALL;
    switch (resource.kind) {
      case SpirvResource::Kind::UniformBuffer:
        binding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
        break;
      case SpirvResource::Kind::SampledImage:
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        break;
      case SpirvResource::Kind::StorageImage:
        binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        break;
      case SpirvResource::Kind::StorageBuffer:
        binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        break;
    }
    bindings.push_back(binding);
    signature += std::to_string(binding.binding) + ":" +
                 std::to_string(static_cast<int>(binding.descriptorType)) + "x" +
                 std::to_string(binding.descriptorCount) + ",";
  }
  if (bindings.empty()) {
    return emptySetLayout_;
  }
  const auto existing = setLayouts_.find(signature);
  if (existing != setLayouts_.end()) {
    return existing->second;
  }
  auto info =
      make<VkDescriptorSetLayoutCreateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
  info.bindingCount = static_cast<uint32_t>(bindings.size());
  info.pBindings = bindings.data();
  VkDescriptorSetLayout layout = VK_NULL_HANDLE;
  vkCreateDescriptorSetLayout(device_, &info, nullptr, &layout);
  setLayouts_.emplace(signature, layout);
  return layout;
}

const rhi::Pipeline* VulkanDevice::createPipeline(const std::string& key,
                                                  const rhi::PipelineDescription* graphics,
                                                  const rhi::ComputePipelineDescription* compute,
                                                  std::string& errorMessage) {
  const std::string& programName = graphics != nullptr ? graphics->program : compute->program;
  const auto found = programs_.find(programName);
  if (found == programs_.end() || found->second->compute != (compute != nullptr)) {
    errorMessage = "Unknown program " + programName;
    return nullptr;
  }
  const ProgramSources& sources = *found->second;
  if (!sources.error.empty()) {
    errorMessage = sources.error;
    return nullptr;
  }
  const std::vector<std::string> defines =
      sortedDefines(graphics != nullptr ? graphics->defines : compute->defines);

  struct Module {
    ShaderStage stage;
    std::vector<uint32_t> words;
  };
  std::vector<Module> modules;
  if (graphics != nullptr) {
    modules.push_back({ShaderStage::Vertex, {}});
    modules.push_back({ShaderStage::Fragment, {}});
  } else {
    modules.push_back({ShaderStage::Compute, {}});
  }
  auto pipeline = std::make_unique<Pipeline>();
  pipeline->compute = compute != nullptr;
  pipeline->label = key;
  for (Module& module : modules) {
    const std::string& source =
        module.stage == ShaderStage::Vertex ? sources.vertex : sources.fragment;
    std::string error;
    module.words = compiler_.compile(module.stage, source, defines, error);
    if (module.words.empty()) {
      errorMessage = key + ": " + error;
      return nullptr;
    }
    std::vector<SpirvResource> stageResources;
    if (!reflectSpirv(module.words, stageResources, error)) {
      errorMessage = key + ": " + error;
      return nullptr;
    }
    for (const SpirvResource& resource : stageResources) {
      const auto same = std::find_if(
          pipeline->resources.begin(), pipeline->resources.end(), [&](const SpirvResource& other) {
            return other.set == resource.set && other.binding == resource.binding;
          });
      if (same == pipeline->resources.end()) {
        pipeline->resources.push_back(resource);
      } else if (!(*same == resource)) {
        errorMessage = key + ": set " + std::to_string(resource.set) + ", binding " +
                       std::to_string(resource.binding) + " differs between the stages";
        return nullptr;
      }
    }
  }
  std::sort(pipeline->resources.begin(), pipeline->resources.end(),
            [](const SpirvResource& a, const SpirvResource& b) {
              return a.set != b.set ? a.set < b.set : a.binding < b.binding;
            });
  for (const SpirvResource& resource : pipeline->resources) {
    const bool storage = resource.kind == SpirvResource::Kind::StorageImage ||
                         resource.kind == SpirvResource::Kind::StorageBuffer;
    if (resource.set != (storage ? 1U : 0U) || resource.binding + resource.count > kMaxBindings ||
        (storage && resource.count != 1) ||
        (resource.kind == SpirvResource::Kind::UniformBuffer && resource.binding != 0)) {
      errorMessage = key + ": unexpected descriptor at set " + std::to_string(resource.set) +
                     ", binding " + std::to_string(resource.binding);
      return nullptr;
    }
    if (resource.kind == SpirvResource::Kind::UniformBuffer) {
      pipeline->uniformSize = sources.uniforms.size;
    }
  }
  if (pipeline->uniformSize == 0 &&
      std::any_of(pipeline->resources.begin(), pipeline->resources.end(),
                  [](const SpirvResource& resource) {
                    return resource.kind == SpirvResource::Kind::UniformBuffer;
                  })) {
    errorMessage = key + ": a uniform block without a declaration";
    return nullptr;
  }
  pipeline->set0 = setLayout(pipeline->resources, 0);
  const VkDescriptorSetLayout set1 = setLayout(pipeline->resources, 1);
  pipeline->set1 = set1 != emptySetLayout_ ? set1 : VK_NULL_HANDLE;
  const auto layoutKey = std::make_pair(pipeline->set0, pipeline->set1);
  auto layout = pipelineLayouts_.find(layoutKey);
  if (layout == pipelineLayouts_.end()) {
    const std::array<VkDescriptorSetLayout, 2> setLayouts{pipeline->set0, pipeline->set1};
    auto info = make<VkPipelineLayoutCreateInfo>(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
    info.setLayoutCount = pipeline->set1 != VK_NULL_HANDLE ? 2 : 1;
    info.pSetLayouts = setLayouts.data();
    VkPipelineLayout created = VK_NULL_HANDLE;
    vkCreatePipelineLayout(device_, &info, nullptr, &created);
    layout = pipelineLayouts_.emplace(layoutKey, created).first;
  }
  pipeline->layout = layout->second;

  std::vector<VkPipelineShaderStageCreateInfo> stages;
  for (const Module& module : modules) {
    auto stage =
        make<VkPipelineShaderStageCreateInfo>(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
    stage.stage = module.stage == ShaderStage::Vertex     ? VK_SHADER_STAGE_VERTEX_BIT
                  : module.stage == ShaderStage::Fragment ? VK_SHADER_STAGE_FRAGMENT_BIT
                                                          : VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = createModule(device_, module.words);
    stage.pName = "main";
    stages.push_back(stage);
  }
  const auto destroyModules = [&] {
    for (const VkPipelineShaderStageCreateInfo& stage : stages) {
      if (stage.module != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device_, stage.module, nullptr);
      }
    }
  };
  if (std::any_of(stages.begin(), stages.end(), [](const VkPipelineShaderStageCreateInfo& stage) {
        return stage.module == VK_NULL_HANDLE;
      })) {
    destroyModules();
    errorMessage = key + ": vkCreateShaderModule failed";
    return nullptr;
  }

  VkResult result = VK_SUCCESS;
  if (compute != nullptr) {
    auto info = make<VkComputePipelineCreateInfo>(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
    info.stage = stages[0];
    info.layout = pipeline->layout;
    result =
        vkCreateComputePipelines(device_, pipelineCache_, 1, &info, nullptr, &pipeline->pipeline);
  } else {
    VkVertexInputBindingDescription binding{};
    std::vector<VkVertexInputAttributeDescription> attributes;
    auto vertexInput = make<VkPipelineVertexInputStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
    if (graphics->vertexLayout) {
      const rhi::VertexLayout& vertexLayout = *graphics->vertexLayout;
      pipeline->vertexInput = true;
      binding.binding = 0;
      binding.stride = vertexLayout.stride;
      binding.inputRate =
          vertexLayout.perInstance ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX;
      uint32_t offset = 0;
      for (uint32_t location = 0; location < vertexLayout.attributes; ++location) {
        const uint32_t components =
            location < vertexLayout.components.size() ? vertexLayout.components[location] : 4;
        static constexpr std::array<VkFormat, 4> kFormats{
            VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT,
            VK_FORMAT_R32G32B32A32_SFLOAT};
        attributes.push_back(
            VkVertexInputAttributeDescription{location, 0, kFormats[components - 1], offset});
        offset += components * static_cast<uint32_t>(sizeof(float));
      }
      vertexInput.vertexBindingDescriptionCount = 1;
      vertexInput.pVertexBindingDescriptions = &binding;
      vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributes.size());
      vertexInput.pVertexAttributeDescriptions = attributes.data();
    }
    auto inputAssembly = make<VkPipelineInputAssemblyStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
    inputAssembly.topology = graphics->topology == rhi::Topology::TriangleStrip
                                 ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
                                 : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    auto viewport = make<VkPipelineViewportStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    auto rasterization = make<VkPipelineRasterizationStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
    rasterization.polygonMode = VK_POLYGON_MODE_FILL;
    rasterization.cullMode = VK_CULL_MODE_NONE;
    rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterization.lineWidth = 1.0F;
    auto multisample = make<VkPipelineMultisampleStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    const rhi::BlendState& blend = graphics->blend;
    VkPipelineColorBlendAttachmentState attachment{};
    attachment.blendEnable = blend.enabled ? VK_TRUE : VK_FALSE;
    attachment.srcColorBlendFactor = vkBlendFactor(blend.sourceColor);
    attachment.dstColorBlendFactor = vkBlendFactor(blend.destinationColor);
    attachment.colorBlendOp = vkBlendOperation(blend.colorOperation);
    attachment.srcAlphaBlendFactor = vkBlendFactor(blend.sourceAlpha);
    attachment.dstAlphaBlendFactor = vkBlendFactor(blend.destinationAlpha);
    attachment.alphaBlendOp = vkBlendOperation(blend.alphaOperation);
    attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    auto colorBlend = make<VkPipelineColorBlendStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
    colorBlend.attachmentCount = 1;
    colorBlend.pAttachments = &attachment;
    const std::array<VkDynamicState, 2> dynamicStates{VK_DYNAMIC_STATE_VIEWPORT,
                                                      VK_DYNAMIC_STATE_SCISSOR};
    auto dynamic = make<VkPipelineDynamicStateCreateInfo>(
        VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
    dynamic.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
    dynamic.pDynamicStates = dynamicStates.data();
    auto info = make<VkGraphicsPipelineCreateInfo>(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
    info.stageCount = static_cast<uint32_t>(stages.size());
    info.pStages = stages.data();
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &inputAssembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &rasterization;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &colorBlend;
    info.pDynamicState = &dynamic;
    info.layout = pipeline->layout;
    info.renderPass = renderPass(vkFormat(graphics->targetFormat), rhi::LoadAction::Load,
                                 VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    result =
        vkCreateGraphicsPipelines(device_, pipelineCache_, 1, &info, nullptr, &pipeline->pipeline);
  }
  destroyModules();
  if (result != VK_SUCCESS) {
    errorMessage = key + ": " + describeVkResult("vkCreate*Pipelines", result);
    return nullptr;
  }
  if (++pipelinesSinceCacheSave_ >= kPipelinesPerCacheSave) {
    pipelinesSinceCacheSave_ = 0;
    savePipelineCache();
  }
  const rhi::Pipeline* created = pipeline.get();
  pipelines_.emplace(key, std::move(pipeline));
  return created;
}

VkDescriptorSet VulkanDevice::allocateSet(VkDescriptorSetLayout layout) {
  Batch& batch = *recording_;
  for (int32_t attempt = 0; attempt < 2; ++attempt) {
    if (batch.poolInUse >= batch.pools.size()) {
      const std::array<VkDescriptorPoolSize, 4> sizes{
          VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, kDescriptorSetsPerPool},
          VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                               kDescriptorSetsPerPool * 16},
          VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kDescriptorSetsPerPool / 4},
          VkDescriptorPoolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kDescriptorSetsPerPool / 4}};
      auto info = make<VkDescriptorPoolCreateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
      info.maxSets = kDescriptorSetsPerPool;
      info.poolSizeCount = static_cast<uint32_t>(sizes.size());
      info.pPoolSizes = sizes.data();
      VkDescriptorPool pool = VK_NULL_HANDLE;
      if (vkCreateDescriptorPool(device_, &info, nullptr, &pool) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
      }
      batch.pools.push_back(pool);
    }
    auto allocate =
        make<VkDescriptorSetAllocateInfo>(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
    allocate.descriptorPool = batch.pools[batch.poolInUse];
    allocate.descriptorSetCount = 1;
    allocate.pSetLayouts = &layout;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(device_, &allocate, &set) == VK_SUCCESS) {
      return set;
    }
    ++batch.poolInUse;
  }
  NANQUIM_LOG_ERROR("Descriptor set allocation failed");
  return VK_NULL_HANDLE;
}

const VulkanTexture& VulkanDevice::placeholder(const SpirvResource& resource) {
  if (resource.dimension == SpirvResource::Dimension::Texture3D) {
    return *placeholder3D_;
  }
  return resource.sampledType == SpirvResource::SampledType::Float ? *placeholder2D_
                                                                   : *placeholderUInt_;
}

bool VulkanDevice::prepareResources(bool compute) {
  const Pipeline& pipeline = *boundPipeline_;
  struct Use {
    const VulkanTexture* texture;
    VkImageLayout layout;
    VkAccessFlags access;
    bool write;
  };
  std::array<Use, static_cast<size_t>(kMaxBindings) * 2> uses{};
  size_t useCount = 0;
  bool barrierNeeded = false;
  for (const SpirvResource& resource : pipeline.resources) {
    Use use{};
    if (resource.kind == SpirvResource::Kind::SampledImage) {
      for (uint32_t element = 0; element < resource.count; ++element) {
        use.texture = textures_[resource.binding + element].texture;
        if (use.texture == nullptr) {
          use.texture = &placeholder(resource);
        }
        use.layout = layoutFor(*use.texture, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        use.access = VK_ACCESS_SHADER_READ_BIT;
        barrierNeeded = barrierNeeded || needsBarrier(use.texture->state(), use.layout, use.write);
        uses[useCount++] = use;
      }
      continue;
    }
    if (resource.kind == SpirvResource::Kind::StorageImage) {
      const BoundStorage& bound = storage_[resource.binding];
      if (bound.image == nullptr) {
        NANQUIM_LOG_ERROR("%s: no storage image at binding %u", pipeline.label.c_str(),
                          resource.binding);
        return false;
      }
      use.texture = bound.image;
      use.layout = VK_IMAGE_LAYOUT_GENERAL;
      use.write = bound.access != rhi::StorageAccess::ReadOnly;
      use.access =
          (bound.access != rhi::StorageAccess::WriteOnly ? VK_ACCESS_SHADER_READ_BIT : 0U) |
          (use.write ? VK_ACCESS_SHADER_WRITE_BIT : 0U);
    } else if (resource.kind == SpirvResource::Kind::StorageBuffer) {
      if (storage_[resource.binding].buffer == nullptr) {
        NANQUIM_LOG_ERROR("%s: no storage buffer at binding %u", pipeline.label.c_str(),
                          resource.binding);
        return false;
      }
      continue;
    } else {
      continue;
    }
    barrierNeeded = barrierNeeded || needsBarrier(use.texture->state(), use.layout, use.write);
    uses[useCount++] = use;
  }
  if (barrierNeeded) {
    suspendRenderPass();
  }
  const VkPipelineStageFlags stages =
      compute ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
              : VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
  for (size_t index = 0; index < useCount; ++index) {
    const Use& use = uses[index];
    useImage(*use.texture, use.layout, stages, use.access, use.write);
  }
  flushBarriers();
  return true;
}

bool VulkanDevice::bindDescriptors(bool compute) {
  const Pipeline& pipeline = *boundPipeline_;
  const VkCommandBuffer commands = recording();
  const VkPipelineBindPoint bindPoint =
      compute ? VK_PIPELINE_BIND_POINT_COMPUTE : VK_PIPELINE_BIND_POINT_GRAPHICS;
  uint32_t dynamicOffset = 0;
  Allocation uniform;
  if (pipeline.uniformSize > 0) {
    uniform = allocateUpload(pipeline.uniformSize, uniformAlignment_);
    if (uniform.buffer == VK_NULL_HANDLE) {
      return false;
    }
    if (uniforms_.size() != pipeline.uniformSize && !pipeline.reportedSizeMismatch) {
      pipeline.reportedSizeMismatch = true;
      NANQUIM_LOG_ERROR("Uniforms of %s: %zu bytes given, the layout has %u",
                        pipeline.label.c_str(), uniforms_.size(), pipeline.uniformSize);
    }
    const size_t copied = std::min<size_t>(uniforms_.size(), pipeline.uniformSize);
    std::memcpy(uniform.mapped, uniforms_.data(), copied);
    std::memset(uniform.mapped + copied, 0, pipeline.uniformSize - copied);
    dynamicOffset = static_cast<uint32_t>(uniform.offset);
    if (uniform.buffer != set0UniformBuffer_) {
      texturesDirty_ = true;
    }
  }
  if (pipeline.set0 != set0Layout_ || pipeline.uniformSize != set0UniformSize_) {
    texturesDirty_ = true;
  }

  if (pipeline.set0 != emptySetLayout_) {
    if (texturesDirty_ || set0_ == VK_NULL_HANDLE) {
      set0_ = allocateSet(pipeline.set0);
      if (set0_ == VK_NULL_HANDLE) {
        return false;
      }
      std::array<VkWriteDescriptorSet, kMaxBindings> writes{};
      std::array<VkDescriptorImageInfo, kMaxBindings> images{};
      VkDescriptorBufferInfo uniformInfo{uniform.buffer, 0, pipeline.uniformSize};
      uint32_t writeCount = 0;
      for (const SpirvResource& resource : pipeline.resources) {
        if (resource.set != 0) {
          continue;
        }
        VkWriteDescriptorSet& write = writes[writeCount];
        write = make<VkWriteDescriptorSet>(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet = set0_;
        write.dstBinding = resource.binding;
        write.descriptorCount = 1;
        if (resource.kind == SpirvResource::Kind::UniformBuffer) {
          write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
          write.pBufferInfo = &uniformInfo;
        } else {
          for (uint32_t element = 0; element < resource.count; ++element) {
            const BoundTexture& bound = textures_[resource.binding + element];
            const VulkanTexture& texture =
                bound.texture != nullptr ? *bound.texture : placeholder(resource);
            rhi::SamplerDescription description{texture.description().filter, false,
                                                rhi::AddressMode::ClampToEdge};
            if (bound.texture != nullptr && bound.sampler != nullptr) {
              description = static_cast<const VulkanSampler*>(bound.sampler)->description();
            }
            if (!linearFilterable_[static_cast<size_t>(texture.description().format)]) {
              description.filter = rhi::Filter::Nearest;
            }
            VkDescriptorImageInfo& image = images[resource.binding + element];
            image.sampler = sampler(description);
            image.imageView = texture.view();
            image.imageLayout = texture.state().layout;
          }
          write.descriptorCount = resource.count;
          write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
          write.pImageInfo = &images[resource.binding];
        }
        ++writeCount;
      }
      vkUpdateDescriptorSets(device_, writeCount, writes.data(), 0, nullptr);
      set0Layout_ = pipeline.set0;
      set0UniformSize_ = pipeline.uniformSize;
      set0UniformBuffer_ = uniform.buffer;
      texturesDirty_ = false;
    }
    vkCmdBindDescriptorSets(commands, bindPoint, pipeline.layout, 0, 1, &set0_,
                            pipeline.uniformSize > 0 ? 1 : 0, &dynamicOffset);
  }

  if (pipeline.set1 != VK_NULL_HANDLE) {
    set1_ = allocateSet(pipeline.set1);
    if (set1_ == VK_NULL_HANDLE) {
      return false;
    }
    std::array<VkWriteDescriptorSet, kMaxBindings> writes{};
    std::array<VkDescriptorImageInfo, kMaxBindings> images{};
    std::array<VkDescriptorBufferInfo, kMaxBindings> buffers{};
    uint32_t writeCount = 0;
    for (const SpirvResource& resource : pipeline.resources) {
      if (resource.set != 1) {
        continue;
      }
      const BoundStorage& bound = storage_[resource.binding];
      VkWriteDescriptorSet& write = writes[writeCount];
      write = make<VkWriteDescriptorSet>(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
      write.dstSet = set1_;
      write.dstBinding = resource.binding;
      write.descriptorCount = 1;
      if (resource.kind == SpirvResource::Kind::StorageImage) {
        images[writeCount] =
            VkDescriptorImageInfo{VK_NULL_HANDLE, bound.image->view(), VK_IMAGE_LAYOUT_GENERAL};
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        write.pImageInfo = &images[writeCount];
      } else {
        bound.buffer->used(recordingSerial_);
        buffers[writeCount] =
            VkDescriptorBufferInfo{bound.buffer->backing().buffer, 0, VK_WHOLE_SIZE};
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &buffers[writeCount];
      }
      ++writeCount;
    }
    vkUpdateDescriptorSets(device_, writeCount, writes.data(), 0, nullptr);
    vkCmdBindDescriptorSets(commands, bindPoint, pipeline.layout, 1, 1, &set1_, 0, nullptr);
  }
  return true;
}

VkRenderPass VulkanDevice::renderPass(VkFormat format, rhi::LoadAction load, VkImageLayout layout) {
  const auto key = std::make_tuple(format, load, layout);
  const auto existing = renderPasses_.find(key);
  if (existing != renderPasses_.end()) {
    return existing->second;
  }
  VkAttachmentDescription attachment{};
  attachment.format = format;
  attachment.samples = VK_SAMPLE_COUNT_1_BIT;
  attachment.loadOp = vkLoadOperation(load);
  attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
  attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
  attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
  attachment.initialLayout = layout;
  attachment.finalLayout = layout;
  const VkAttachmentReference reference{0, layout};
  VkSubpassDescription subpass{};
  subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  subpass.colorAttachmentCount = 1;
  subpass.pColorAttachments = &reference;
  auto info = make<VkRenderPassCreateInfo>(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
  info.attachmentCount = 1;
  info.pAttachments = &attachment;
  info.subpassCount = 1;
  info.pSubpasses = &subpass;
  VkRenderPass pass = VK_NULL_HANDLE;
  vkCreateRenderPass(device_, &info, nullptr, &pass);
  renderPasses_.emplace(key, pass);
  return pass;
}

void VulkanDevice::beginPass(const rhi::RenderTarget& target, rhi::LoadAction load,
                             std::array<float, 4> clearColor) {
  suspendRenderPass();
  submitIfHeavy();
  passOpen_ = true;
  passTexture_ = nullptr;
  if (target.texture != nullptr) {
    passTexture_ = static_cast<const VulkanTexture*>(target.texture);
  } else if (const std::optional<uint32_t> image =
                 acquireWindowImage() ? acquiredImage_ : std::nullopt) {
    passTexture_ = swapchain_->images[*image].get();
  }
  passWidth_ = target.width;
  passHeight_ = target.height;
  pendingLoad_ = load;
  pendingClear_ = clearColor;
  viewport_ = rhi::Rect{0, 0, target.width, target.height};
  scissor_.reset();
  dynamicStateDirty_ = true;
}

void VulkanDevice::endPass() {
  if (!passOpen_) {
    return;
  }
  if (!renderPassActive_ && pendingLoad_ == rhi::LoadAction::Clear) {
    ensureRenderPass();
  }
  suspendRenderPass();
  passOpen_ = false;
  passTexture_ = nullptr;
  textures_ = {};
  texturesDirty_ = true;
  boundPipeline_ = nullptr;
  vertexBuffer_ = nullptr;
}

void VulkanDevice::suspendRenderPass() {
  if (!renderPassActive_) {
    return;
  }
  vkCmdEndRenderPass(recording());
  renderPassActive_ = false;
  pendingLoad_ = rhi::LoadAction::Load;
}

void VulkanDevice::ensureRenderPass() {
  if (renderPassActive_ || !passOpen_ || passTexture_ == nullptr) {
    return;
  }
  const VulkanTexture& target = *passTexture_;
  const VkImageLayout layout = layoutFor(target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  useImage(target, layout, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
           VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, true);
  flushBarriers();
  if (target.framebuffer() == VK_NULL_HANDLE) {
    VkImageView attachment = target.view();
    if (target.description().levels > 1) {
      auto viewInfo = make<VkImageViewCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
      viewInfo.image = target.image();
      viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = target.format();
      viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      vkCreateImageView(device_, &viewInfo, nullptr, &target.attachmentView());
      attachment = target.attachmentView();
    }
    auto info = make<VkFramebufferCreateInfo>(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
    info.renderPass = renderPass(target.format(), rhi::LoadAction::Load, layout);
    info.attachmentCount = 1;
    info.pAttachments = &attachment;
    info.width = static_cast<uint32_t>(target.width());
    info.height = static_cast<uint32_t>(target.height());
    info.layers = 1;
    vkCreateFramebuffer(device_, &info, nullptr, &target.framebuffer());
  }
  VkClearValue clear{};
  clear.color = clearValue(target.format(), pendingClear_);
  auto begin = make<VkRenderPassBeginInfo>(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
  begin.renderPass = renderPass(target.format(), pendingLoad_, layout);
  begin.framebuffer = target.framebuffer();
  begin.renderArea = {
      {0, 0}, {static_cast<uint32_t>(target.width()), static_cast<uint32_t>(target.height())}};
  begin.clearValueCount = 1;
  begin.pClearValues = &clear;
  vkCmdBeginRenderPass(recording(), &begin, VK_SUBPASS_CONTENTS_INLINE);
  renderPassActive_ = true;
  pendingLoad_ = rhi::LoadAction::Load;
  dynamicStateDirty_ = true;
}

void VulkanDevice::applyDynamicState() {
  if (!dynamicStateDirty_ || passTexture_ == nullptr) {
    return;
  }
  const VkCommandBuffer commands = recording();
  const VkViewport viewport{static_cast<float>(viewport_.x),
                            static_cast<float>(viewport_.y),
                            static_cast<float>(std::max(viewport_.width, 1)),
                            static_cast<float>(std::max(viewport_.height, 1)),
                            0.0F,
                            1.0F};
  vkCmdSetViewport(commands, 0, 1, &viewport);
  const int32_t width = passTexture_->width();
  const int32_t height = passTexture_->height();
  const VkRect2D scissor =
      clampedRect(scissor_.value_or(rhi::Rect{0, 0, width, height}), width, height);
  vkCmdSetScissor(commands, 0, 1, &scissor);
  dynamicStateDirty_ = false;
}

void VulkanDevice::setViewport(const rhi::Rect& rect) {
  viewport_ = rect;
  dynamicStateDirty_ = true;
}

void VulkanDevice::setScissor(std::optional<rhi::Rect> rect) {
  scissor_ = rect;
  dynamicStateDirty_ = true;
}

void VulkanDevice::clear(std::array<float, 4> color, std::optional<rhi::Rect> rect) {
  if (!passOpen_ || passTexture_ == nullptr) {
    return;
  }
  const int32_t width = passTexture_->width();
  const int32_t height = passTexture_->height();
  const VkRect2D area = clampedRect(rect.value_or(rhi::Rect{0, 0, width, height}), width, height);
  if (area.extent.width == 0 || area.extent.height == 0) {
    return;
  }
  const bool whole = area.extent.width == static_cast<uint32_t>(width) &&
                     area.extent.height == static_cast<uint32_t>(height);
  if (!renderPassActive_ && whole) {
    pendingLoad_ = rhi::LoadAction::Clear;
    pendingClear_ = color;
    return;
  }
  ensureRenderPass();
  VkClearAttachment attachment{};
  attachment.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  attachment.colorAttachment = 0;
  attachment.clearValue.color = clearValue(passTexture_->format(), color);
  const VkClearRect clearRect{area, 0, 1};
  vkCmdClearAttachments(recording(), 1, &attachment, 1, &clearRect);
}

void VulkanDevice::bindPipeline(const rhi::Pipeline& pipeline) {
  const auto* vulkan = static_cast<const Pipeline*>(&pipeline);
  if (vulkan != boundPipeline_) {
    boundPipeline_ = vulkan;
    pipelineDirty_ = true;
  }
}

void VulkanDevice::setUniforms(const void* data, size_t size) {
  uniforms_.assign(static_cast<const uint8_t*>(data), static_cast<const uint8_t*>(data) + size);
}

void VulkanDevice::bindTexture(uint32_t binding, const rhi::Texture* texture,
                               const rhi::Sampler* sampler) {
  if (binding >= kMaxBindings) {
    return;
  }
  textures_[binding] = BoundTexture{static_cast<const VulkanTexture*>(texture), sampler};
  texturesDirty_ = true;
}

void VulkanDevice::bindVertexBuffer(const rhi::Buffer& buffer, size_t offset) {
  vertexBuffer_ = static_cast<const Buffer*>(&buffer);
  vertexBufferOffset_ = offset;
}

void VulkanDevice::draw(uint32_t vertexCount, uint32_t instanceCount) {
  if (!passOpen_ || passTexture_ == nullptr || boundPipeline_ == nullptr ||
      boundPipeline_->compute || (boundPipeline_->vertexInput && vertexBuffer_ == nullptr)) {
    return;
  }
  if (!prepareResources(false)) {
    return;
  }
  ensureRenderPass();
  const VkCommandBuffer commands = recording();
  if (pipelineDirty_) {
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, boundPipeline_->pipeline);
    pipelineDirty_ = false;
  }
  applyDynamicState();
  if (!bindDescriptors(false)) {
    return;
  }
  if (boundPipeline_->vertexInput) {
    vertexBuffer_->used(recordingSerial_);
    const VkBuffer buffer = vertexBuffer_->backing().buffer;
    vkCmdBindVertexBuffers(commands, 0, 1, &buffer, &vertexBufferOffset_);
  }
  vkCmdDraw(commands, vertexCount, instanceCount, 0, 0);
}

void VulkanDevice::beginCompute() {
  suspendRenderPass();
  submitIfHeavy();
}

void VulkanDevice::endCompute() {
  storage_ = {};
  textures_ = {};
  texturesDirty_ = true;
  storageDirty_ = true;
  boundPipeline_ = nullptr;
}

void VulkanDevice::bindComputePipeline(const rhi::Pipeline& pipeline) { bindPipeline(pipeline); }

void VulkanDevice::bindStorageImage(uint32_t binding, const rhi::Texture& texture,
                                    rhi::StorageAccess access) {
  if (binding >= kMaxBindings) {
    return;
  }
  storage_[binding].image = static_cast<const VulkanTexture*>(&texture);
  storage_[binding].access = access;
  storageDirty_ = true;
}

void VulkanDevice::bindStorageBuffer(uint32_t binding, const rhi::Buffer& buffer) {
  if (binding >= kMaxBindings) {
    return;
  }
  storage_[binding].buffer = static_cast<const Buffer*>(&buffer);
  storageDirty_ = true;
}

void VulkanDevice::dispatch(uint32_t groupsX, uint32_t groupsY, uint32_t groupsZ) {
  if (boundPipeline_ == nullptr || !boundPipeline_->compute) {
    return;
  }
  suspendRenderPass();
  if (!prepareResources(true)) {
    return;
  }
  const VkCommandBuffer commands = recording();
  if (pipelineDirty_) {
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, boundPipeline_->pipeline);
    pipelineDirty_ = false;
  }
  if (!bindDescriptors(true)) {
    return;
  }
  vkCmdDispatch(commands, groupsX, groupsY, groupsZ);
}

void VulkanDevice::copyTexture(const rhi::Texture& source, const rhi::Rect& sourceRect,
                               const rhi::Texture& destination, int32_t destinationX,
                               int32_t destinationY) {
  if (sourceRect.width <= 0 || sourceRect.height <= 0) {
    return;
  }
  const auto& from = static_cast<const VulkanTexture&>(source);
  const auto& to = static_cast<const VulkanTexture&>(destination);
  suspendRenderPass();
  if (&from == &to) {
    useImage(from, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_TRANSFER_BIT,
             VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, true);
  } else {
    useImage(from, layoutFor(from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT, false);
    useImage(to, layoutFor(to, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, true);
  }
  flushBarriers();
  VkImageCopy region{};
  region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  region.srcOffset = {sourceRect.x, sourceRect.y, 0};
  region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  region.dstOffset = {destinationX, destinationY, 0};
  region.extent = {static_cast<uint32_t>(sourceRect.width),
                   static_cast<uint32_t>(sourceRect.height), 1};
  vkCmdCopyImage(recording(), from.image(), from.state().layout, to.image(), to.state().layout, 1,
                 &region);
}

std::unique_ptr<rhi::Readback> VulkanDevice::readTextureAsync(const rhi::Texture& texture,
                                                              const rhi::Rect& rect) {
  if (rect.width <= 0 || rect.height <= 0) {
    return nullptr;
  }
  const auto& image = static_cast<const VulkanTexture&>(texture);
  const VkDeviceSize bytes = static_cast<VkDeviceSize>(rect.width) *
                             static_cast<VkDeviceSize>(rect.height) *
                             bytesPerTexel(texture.description().format);
  HostBuffer buffer;
  const auto idle = idleReadBuffers_.find(bytes);
  if (idle != idleReadBuffers_.end()) {
    buffer = idle->second;
    idleReadBuffers_.erase(idle);
    idleReadBufferBytes_ -= bytes;
  } else {
    buffer = createHostBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
    if (buffer.buffer == VK_NULL_HANDLE) {
      return nullptr;
    }
  }
  suspendRenderPass();
  useImage(image, layoutFor(image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
           VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT, false);
  flushBarriers();
  const VkCommandBuffer commands = recording();
  VkBufferImageCopy copy{};
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  copy.imageOffset = {rect.x, rect.y, 0};
  copy.imageExtent = {static_cast<uint32_t>(rect.width), static_cast<uint32_t>(rect.height), 1};
  vkCmdCopyImageToBuffer(commands, image.image(), image.state().layout, buffer.buffer, 1, &copy);
  auto toHost = make<VkBufferMemoryBarrier>(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER);
  toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  toHost.buffer = buffer.buffer;
  toHost.size = VK_WHOLE_SIZE;
  vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0,
                       nullptr, 1, &toHost, 0, nullptr);
  return std::make_unique<VulkanReadback>(*this, buffer, recordingSerial_,
                                          static_cast<size_t>(bytes));
}

bool VulkanDevice::readTexture(const rhi::Texture& texture, const rhi::Rect& rect, void* out) {
  const std::unique_ptr<rhi::Readback> readback = readTextureAsync(texture, rect);
  return readback != nullptr && readback->read(out);
}

struct VulkanDevice::Leave {
  std::thread worker;
  std::atomic<bool> done{false};
  VkSwapchainCreateInfoKHR info{};
  VkSwapchainKHR handle = VK_NULL_HANDLE;
  VkResult result = VK_SUCCESS;
};

bool VulkanDevice::attachWindow(ANativeWindow* window, bool displayP3, std::string& errorMessage) {
  detachWindow();
  auto info =
      make<VkAndroidSurfaceCreateInfoKHR>(VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR);
  info.window = window;
  VkResult result = vkCreateAndroidSurfaceKHR(context_->instance(), &info, nullptr, &surface_);
  if (result != VK_SUCCESS) {
    errorMessage = describeVkResult("vkCreateAndroidSurfaceKHR", result);
    surface_ = VK_NULL_HANDLE;
    return false;
  }
  VkBool32 presents = VK_FALSE;
  vkGetPhysicalDeviceSurfaceSupportKHR(context_->physicalDevice(), context_->queueFamily(),
                                       surface_, &presents);
  window_ = window;
  displayP3_ = displayP3;
  if (presents != VK_TRUE) {
    errorMessage = "The queue does not present to the window";
  } else if (createSwapchain(false, errorMessage)) {
    frontBufferOffered_ = false;
    if (context_->hasSharedPresentableImage() && getSurfaceCapabilities2_ != nullptr) {
      uint32_t count = 0;
      vkGetPhysicalDeviceSurfacePresentModesKHR(context_->physicalDevice(), surface_, &count,
                                                nullptr);
      std::vector<VkPresentModeKHR> modes(count);
      vkGetPhysicalDeviceSurfacePresentModesKHR(context_->physicalDevice(), surface_, &count,
                                                modes.data());
      auto shared = make<VkSharedPresentSurfaceCapabilitiesKHR>(
          VK_STRUCTURE_TYPE_SHARED_PRESENT_SURFACE_CAPABILITIES_KHR);
      auto capabilities =
          make<VkSurfaceCapabilities2KHR>(VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_2_KHR);
      capabilities.pNext = &shared;
      auto surfaceInfo = make<VkPhysicalDeviceSurfaceInfo2KHR>(
          VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SURFACE_INFO_2_KHR);
      surfaceInfo.surface = surface_;
      frontBufferOffered_ =
          std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR) !=
              modes.end() &&
          getSurfaceCapabilities2_(context_->physicalDevice(), &surfaceInfo, &capabilities) ==
              VK_SUCCESS &&
          (shared.sharedPresentSupportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0;
    }
    return true;
  }
  detachWindow();
  return false;
}

void VulkanDevice::detachWindow() {
  if (surface_ == VK_NULL_HANDLE) {
    return;
  }
  finishLeave(true);
  if (recording_->begun || !waitSemaphores_.empty()) {
    submitBatch();
  }
  vkDeviceWaitIdle(device_);
  collect();
  destroySwapchain();
  vkDestroySurfaceKHR(context_->instance(), surface_, nullptr);
  surface_ = VK_NULL_HANDLE;
  window_ = nullptr;
  acquiredImage_.reset();
  presentTimes_.clear();
}

bool VulkanDevice::supportsDisplayP3() const {
  return context_->hasSwapchainColorSpace() && displayP3Offered_;
}

bool VulkanDevice::supportsFrontBuffer() const {
  return surface_ != VK_NULL_HANDLE && frontBufferOffered_;
}

bool VulkanDevice::describeSwapchain(bool shared, VkSwapchainCreateInfoKHR& info,
                                     std::string& errorMessage) {
  const VkPhysicalDevice physical = context_->physicalDevice();
  VkSurfaceCapabilitiesKHR capabilities{};
  VkResult result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface_, &capabilities);
  if (result != VK_SUCCESS) {
    errorMessage = describeVkResult("vkGetPhysicalDeviceSurfaceCapabilitiesKHR", result);
    return false;
  }
  uint32_t count = 0;
  vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface_, &count, nullptr);
  std::vector<VkSurfaceFormatKHR> formats(count);
  vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface_, &count, formats.data());
  const auto offered = [&](VkColorSpaceKHR colorSpace) {
    return std::any_of(formats.begin(), formats.end(), [&](const VkSurfaceFormatKHR& format) {
      return format.format == VK_FORMAT_R8G8B8A8_UNORM && format.colorSpace == colorSpace;
    });
  };
  VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
  displayP3Offered_ = offered(VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT);
  if (displayP3_ && context_->hasSwapchainColorSpace() && displayP3Offered_) {
    colorSpace = VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT;
  } else if (!offered(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)) {
    errorMessage = "The window offers no RGBA8 sRGB format";
    return false;
  }
  const bool identity =
      (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0;
  const VkSurfaceTransformFlagBitsKHR transform =
      identity ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : capabilities.currentTransform;
  VkExtent2D extent = capabilities.currentExtent;
  if (extent.width == 0xFFFFFFFFU || transform != capabilities.currentTransform) {
    extent = {static_cast<uint32_t>(ANativeWindow_getWidth(window_)),
              static_cast<uint32_t>(ANativeWindow_getHeight(window_))};
  }
  extent.width = std::clamp(extent.width, capabilities.minImageExtent.width,
                            capabilities.maxImageExtent.width);
  extent.height = std::clamp(extent.height, capabilities.minImageExtent.height,
                             capabilities.maxImageExtent.height);
  uint32_t imageCount = std::max<uint32_t>(capabilities.minImageCount, 3);
  if (capabilities.maxImageCount != 0) {
    imageCount = std::min(imageCount, capabilities.maxImageCount);
  }
  VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
  for (const VkCompositeAlphaFlagBitsKHR candidate :
       {VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR, VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR}) {
    if ((capabilities.supportedCompositeAlpha & candidate) != 0) {
      compositeAlpha = candidate;
      break;
    }
  }
  info = make<VkSwapchainCreateInfoKHR>(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
  info.surface = surface_;
  info.minImageCount = shared ? 1 : imageCount;
  info.imageFormat = VK_FORMAT_R8G8B8A8_UNORM;
  info.imageColorSpace = colorSpace;
  info.imageExtent = extent;
  info.imageArrayLayers = 1;
  info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                    (shared ? 0
                            : capabilities.supportedUsageFlags & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT));
  info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
  info.preTransform = transform;
  info.compositeAlpha = compositeAlpha;
  info.presentMode =
      shared ? VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR : VK_PRESENT_MODE_FIFO_KHR;
  info.clipped = VK_TRUE;
  info.oldSwapchain = swapchain_ ? swapchain_->handle : VK_NULL_HANDLE;
  return true;
}

bool VulkanDevice::createSwapchain(bool shared, std::string& errorMessage) {
  VkSwapchainCreateInfoKHR info{};
  if (!describeSwapchain(shared, info, errorMessage)) {
    return false;
  }
  VkSwapchainKHR handle = VK_NULL_HANDLE;
  const VkResult result = vkCreateSwapchainKHR(device_, &info, nullptr, &handle);
  if (result != VK_SUCCESS) {
    destroySwapchain();
    errorMessage = describeVkResult("vkCreateSwapchainKHR", result);
    return false;
  }
  adoptSwapchain(handle, info);
  return true;
}

void VulkanDevice::adoptSwapchain(VkSwapchainKHR handle, const VkSwapchainCreateInfoKHR& info) {
  collectPresentTimes();
  if (swapchain_) {
    if (recording_->begun || !waitSemaphores_.empty()) {
      submitBatch();
    }
    retiredSwapchains_.emplace_back(recordingSerial_, std::move(swapchain_));
    acquiredImage_.reset();
  }
  const bool shared = info.presentMode == VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR;
  swapchain_ = std::make_unique<Swapchain>();
  swapchain_->handle = handle;
  swapchain_->extent = info.imageExtent;
  swapchain_->shared = shared;
  swapchainFirstPresentId_ = nextPresentId_;
  displayP3Active_ = info.imageColorSpace == VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT;
  uint32_t count = 0;
  vkGetSwapchainImagesKHR(device_, handle, &count, nullptr);
  std::vector<VkImage> images(count);
  vkGetSwapchainImagesKHR(device_, handle, &count, images.data());
  rhi::TextureDescription description;
  description.width = static_cast<int32_t>(info.imageExtent.width);
  description.height = static_cast<int32_t>(info.imageExtent.height);
  description.usage = rhi::kUsageRenderTarget;
  for (const VkImage image : images) {
    auto viewInfo = make<VkImageViewCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    vkCreateImageView(device_, &viewInfo, nullptr, &view);
    swapchain_->images.push_back(std::make_unique<VulkanTexture>(
        description, *this, image, VK_NULL_HANDLE, view, VK_FORMAT_R8G8B8A8_UNORM));
    if (shared) {
      swapchain_->images.back()->setSharedPresent();
    }
    auto semaphoreInfo = make<VkSemaphoreCreateInfo>(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
    VkSemaphore rendered = VK_NULL_HANDLE;
    vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &rendered);
    swapchain_->rendered.push_back(rendered);
  }
  NANQUIM_LOG_INFO("Vulkan swapchain %u×%u, %zu images, %s%s", info.imageExtent.width,
                   info.imageExtent.height, images.size(), displayP3Active_ ? "Display P3" : "sRGB",
                   shared ? ", front-buffered" : "");
}

void VulkanDevice::destroySwapchain() {
  if (!swapchain_ && retiredSwapchains_.empty()) {
    return;
  }
  if (recording_->begun || !waitSemaphores_.empty()) {
    submitBatch();
  }
  vkDeviceWaitIdle(device_);
  collect();
  for (auto& [serial, retired] : retiredSwapchains_) {
    releaseSwapchain(*retired);
  }
  retiredSwapchains_.clear();
  if (swapchain_) {
    releaseSwapchain(*swapchain_);
  }
  swapchain_.reset();
  acquiredImage_.reset();
}

void VulkanDevice::releaseSwapchain(Swapchain& swapchain) {
  swapchain.images.clear();
  for (VkSemaphore semaphore : swapchain.rendered) {
    vkDestroySemaphore(device_, semaphore, nullptr);
  }
  vkDestroySwapchainKHR(device_, swapchain.handle, nullptr);
}

void VulkanDevice::beginLeave() {
  auto leave = std::make_unique<Leave>();
  std::string errorMessage;
  if (!describeSwapchain(false, leave->info, errorMessage)) {
    NANQUIM_LOG_ERROR("Swapchain: %s", errorMessage.c_str());
    swapchain_->outdated = true;
    return;
  }
  ATrace_beginSection("NanquimVulkanLeaveFrontBuffer");
  ATrace_endSection();
  Leave* state = leave.get();
  const VkDevice device = device_;
  leave->worker = std::thread([state, device] {
    state->result = vkCreateSwapchainKHR(device, &state->info, nullptr, &state->handle);
    state->done = true;
  });
  leave_ = std::move(leave);
}

void VulkanDevice::finishLeave(bool wait) {
  if (!leave_ || (!wait && !leave_->done)) {
    return;
  }
  ATrace_beginSection("NanquimVulkanAdoptSwapchain");
  leave_->worker.join();
  const std::unique_ptr<Leave> leave = std::move(leave_);
  if (leave->result == VK_SUCCESS) {
    adoptSwapchain(leave->handle, leave->info);
  } else {
    NANQUIM_LOG_ERROR("%s", describeVkResult("vkCreateSwapchainKHR", leave->result).c_str());
    destroySwapchain();
    std::string errorMessage;
    if (!createSwapchain(false, errorMessage)) {
      NANQUIM_LOG_ERROR("Swapchain: %s", errorMessage.c_str());
    }
  }
  ATrace_endSection();
}

void VulkanDevice::prepareFrame() {
  finishLeave(false);
  if (!frontBufferWanted_ || !swapchain_ || swapchain_->shared || leave_ ||
      !supportsFrontBuffer()) {
    return;
  }
  ATrace_beginSection("NanquimVulkanEnterFrontBuffer");
  std::string errorMessage;
  if (!createSwapchain(true, errorMessage)) {
    NANQUIM_LOG_ERROR("Front-buffered swapchain: %s", errorMessage.c_str());
    frontBufferOffered_ = false;
    if (!swapchain_ && !createSwapchain(false, errorMessage)) {
      NANQUIM_LOG_ERROR("Swapchain: %s", errorMessage.c_str());
    }
  }
  ATrace_endSection();
}

bool VulkanDevice::isFrontBuffered() const { return swapchain_ && swapchain_->shared; }

bool VulkanDevice::acquireWindowImage() {
  if (acquiredImage_) {
    return true;
  }
  finishLeave(true);
  if (!swapchain_ && surface_ != VK_NULL_HANDLE) {
    std::string errorMessage;
    if (!createSwapchain(false, errorMessage)) {
      NANQUIM_LOG_ERROR("Swapchain: %s", errorMessage.c_str());
    }
  }
  if (!swapchain_) {
    return false;
  }
  for (int32_t attempt = 0; attempt < 2; ++attempt) {
    const bool resized =
        swapchain_->extent.width != static_cast<uint32_t>(ANativeWindow_getWidth(window_)) ||
        swapchain_->extent.height != static_cast<uint32_t>(ANativeWindow_getHeight(window_));
    if (swapchain_->outdated || resized) {
      std::string errorMessage;
      if (!createSwapchain(swapchain_->shared, errorMessage)) {
        NANQUIM_LOG_ERROR("Swapchain: %s", errorMessage.c_str());
        return false;
      }
    }
    VkSemaphore acquired = VK_NULL_HANDLE;
    if (!idleSemaphores_.empty()) {
      acquired = idleSemaphores_.back();
      idleSemaphores_.pop_back();
    } else {
      auto semaphoreInfo = make<VkSemaphoreCreateInfo>(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
      vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &acquired);
    }
    uint32_t index = 0;
    const VkResult result = vkAcquireNextImageKHR(device_, swapchain_->handle, kLostGpuNanoseconds,
                                                  acquired, VK_NULL_HANDLE, &index);
    if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
      swapchain_->outdated = result == VK_SUBOPTIMAL_KHR;
      waitSemaphores_.push_back(acquired);
      acquiredImage_ = index;
      swapchain_->images[index]->state() = ImageState{
          VK_IMAGE_LAYOUT_UNDEFINED, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, false};
      return true;
    }
    idleSemaphores_.push_back(acquired);
    if (result != VK_ERROR_OUT_OF_DATE_KHR) {
      NANQUIM_LOG_ERROR("%s", describeVkResult("vkAcquireNextImageKHR", result).c_str());
      return false;
    }
    swapchain_->outdated = true;
  }
  return false;
}

bool VulkanDevice::present() {
  bool presented = true;
  if (swapchain_ && acquiredImage_) {
    const uint32_t index = *acquiredImage_;
    const bool shared = swapchain_->shared;
    suspendRenderPass();
    useImage(*swapchain_->images[index],
             layoutFor(*swapchain_->images[index], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR),
             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, false);
    flushBarriers();
    if (shared && (swapchain_->presented || leave_)) {
      submitBatch();
    } else {
      if (!shared) {
        acquiredImage_.reset();
      }
      const VkSemaphore rendered = swapchain_->rendered[index];
      submitBatch(rendered);
      auto info = make<VkPresentInfoKHR>(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
      info.waitSemaphoreCount = 1;
      info.pWaitSemaphores = &rendered;
      info.swapchainCount = 1;
      info.pSwapchains = &swapchain_->handle;
      info.pImageIndices = &index;
      VkPresentTimeGOOGLE time{nextPresentId_, 0};
      auto times = make<VkPresentTimesInfoGOOGLE>(VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE);
      times.swapchainCount = 1;
      times.pTimes = &time;
      if (getPastPresentationTiming_ != nullptr && !shared) {
        info.pNext = &times;
        ++nextPresentId_;
      }
      const VkResult result = vkQueuePresentKHR(context_->queue(), &info);
      swapchain_->presented = true;
      lastPresentTime_ = std::chrono::steady_clock::now();
      if (result == VK_SUBOPTIMAL_KHR || result == VK_ERROR_OUT_OF_DATE_KHR) {
        swapchain_->outdated = true;
      }
      presented = result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
    }
  }
  if (swapchain_ && swapchain_->shared && !frontBufferWanted_ && !leave_) {
    beginLeave();
  }
  return presented;
}

void VulkanDevice::collectPresentTimes() {
  if (getPastPresentationTiming_ == nullptr || !swapchain_ || swapchain_->shared) {
    return;
  }
  uint32_t count = 0;
  if (getPastPresentationTiming_(device_, swapchain_->handle, &count, nullptr) != VK_SUCCESS ||
      count == 0) {
    return;
  }
  std::vector<VkPastPresentationTimingGOOGLE> timings(count);
  getPastPresentationTiming_(device_, swapchain_->handle, &count, timings.data());
  for (uint32_t index = 0; index < count; ++index) {
    presentTimes_[timings[index].presentID] =
        static_cast<int64_t>(timings[index].actualPresentTime);
    newestReportedPresentId_ = std::max(newestReportedPresentId_, timings[index].presentID);
  }
  constexpr size_t kKeptTimes = 256;
  while (presentTimes_.size() > kKeptTimes) {
    presentTimes_.erase(presentTimes_.begin());
  }
}

std::optional<uint64_t> VulkanDevice::nextFrameId() const {
  if (getPastPresentationTiming_ == nullptr || !swapchain_ || swapchain_->shared) {
    return std::nullopt;
  }
  return nextPresentId_;
}

rhi::Presenter::PresentTime VulkanDevice::presentTime(uint64_t frameId, int64_t& presentNanos) {
  using PresentTime = rhi::Presenter::PresentTime;
  if (getPastPresentationTiming_ == nullptr || frameId >= nextPresentId_) {
    return PresentTime::Unavailable;
  }
  const auto found = presentTimes_.find(static_cast<uint32_t>(frameId));
  if (found != presentTimes_.end()) {
    presentNanos = found->second;
    return PresentTime::Known;
  }
  collectPresentTimes();
  const auto reported = presentTimes_.find(static_cast<uint32_t>(frameId));
  if (reported != presentTimes_.end()) {
    presentNanos = reported->second;
    return PresentTime::Known;
  }
  constexpr auto kReportWindow = std::chrono::milliseconds(40);
  if (frameId < newestReportedPresentId_ || frameId < swapchainFirstPresentId_ ||
      std::chrono::steady_clock::now() - lastPresentTime_ > kReportWindow) {
    return PresentTime::Unavailable;
  }
  return PresentTime::Pending;
}

}  // namespace nanquim::gpu::vulkan
