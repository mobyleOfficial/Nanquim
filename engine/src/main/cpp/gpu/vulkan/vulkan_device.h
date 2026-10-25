#pragma once

#include <android/native_window.h>

#include <array>
#include <chrono>
#include <deque>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <tuple>
#include <vector>

#include "gpu/rhi/rhi.h"
#include "gpu/spirv_reflection.h"
#include "gpu/uniform_layout.h"
#include "gpu/vulkan/spirv_compiler.h"
#include "gpu/vulkan/vulkan_context.h"

namespace nanquim::gpu::vulkan {

class VulkanDevice;

struct ImageState {
  VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
  VkPipelineStageFlags stages = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
  VkAccessFlags access = 0;
  bool written = false;
};

class VulkanTexture final : public rhi::Texture {
 public:
  VulkanTexture(const rhi::TextureDescription& description, VulkanDevice& device, VkImage image,
                VkDeviceMemory memory, VkImageView view, VkFormat format);
  ~VulkanTexture() override;

  [[nodiscard]] VkImage image() const { return image_; }
  [[nodiscard]] VkImageView view() const { return view_; }
  [[nodiscard]] VkFormat format() const { return format_; }
  [[nodiscard]] bool storage() const { return (description().usage & rhi::kUsageStorage) != 0; }
  [[nodiscard]] bool sharedPresent() const { return sharedPresent_; }
  void setSharedPresent() { sharedPresent_ = true; }

  ImageState& state() const { return state_; }
  VkFramebuffer& framebuffer() const { return framebuffer_; }
  VkImageView& attachmentView() const { return attachmentView_; }

 private:
  VulkanDevice& device_;
  VkImage image_;
  VkDeviceMemory memory_;
  VkImageView view_;
  VkFormat format_;
  bool sharedPresent_ = false;
  mutable ImageState state_;
  mutable VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
  mutable VkImageView attachmentView_ = VK_NULL_HANDLE;
};

class VulkanDevice final : public rhi::Device, private rhi::CommandContext {
 public:
  VulkanDevice(std::shared_ptr<VulkanContext> context, const std::string& shaderCacheDirectory);
  ~VulkanDevice() override;

  [[nodiscard]] bool isReady(std::string& errorMessage) const {
    errorMessage = setupError_;
    return setupError_.empty();
  }
  [[nodiscard]] VulkanContext& context() const { return *context_; }

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
  rhi::CommandContext& commands() override { return *this; }
  [[nodiscard]] int32_t maxTextureSize() const override {
    return static_cast<int32_t>(context_->properties().limits.maxImageDimension2D);
  }
  [[nodiscard]] bool isRenderable(rhi::Format format) const override;
  [[nodiscard]] bool readsDestination() const override { return false; }
  [[nodiscard]] bool supportsTimerQueries() const override { return timerQueriesSupported_; }
  std::unique_ptr<rhi::TimerQuery> createTimerQuery() override;
  bool timerResultsDisjoint() override { return false; }
  [[nodiscard]] std::string infoJson() const override;

  bool attachWindow(ANativeWindow* window, bool displayP3, std::string& errorMessage);
  void detachWindow();
  [[nodiscard]] bool hasWindow() const { return surface_ != VK_NULL_HANDLE; }
  [[nodiscard]] bool supportsDisplayP3() const;
  [[nodiscard]] bool isDisplayP3() const { return displayP3Active_; }
  bool present();
  [[nodiscard]] bool supportsFrontBuffer() const;
  void requestFrontBuffer(bool frontBuffered) { frontBufferWanted_ = frontBuffered; }
  [[nodiscard]] bool isFrontBuffered() const;
  [[nodiscard]] bool hasPendingSwitch() const { return leave_ != nullptr; }
  void prepareFrame();
  [[nodiscard]] std::optional<uint64_t> nextFrameId() const;
  rhi::Presenter::PresentTime presentTime(uint64_t frameId, int64_t& presentNanos);

  [[nodiscard]] uint64_t recordingSerial() const { return recordingSerial_; }
  [[nodiscard]] bool isComplete(uint64_t serial);
  bool waitForSerial(uint64_t serial, uint64_t timeoutNanoseconds);

  struct Retired {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
  };
  void retire(const Retired& objects) noexcept;

  struct HostBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
    void* mapped = nullptr;
    bool coherent = true;
  };
  [[nodiscard]] HostBuffer createHostBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                                            bool cached);
  void returnReadBuffer(HostBuffer buffer) noexcept;

 private:
  struct Batch;
  struct ProgramSources;
  class Pipeline;
  class Buffer;
  struct Swapchain;
  struct Leave;

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

  void createSharedObjects();
  void shutdown();
  void destroyRetired(const Retired& objects) noexcept;
  VkCommandBuffer recording();
  void submitBatch(VkSemaphore signal = VK_NULL_HANDLE);
  void submitIfHeavy();
  void collect();

  void useImage(const VulkanTexture& texture, VkImageLayout layout, VkPipelineStageFlags stages,
                VkAccessFlags access, bool write);
  [[nodiscard]] static bool needsBarrier(const ImageState& state, VkImageLayout layout, bool write);
  void flushBarriers();
  [[nodiscard]] static VkImageLayout layoutFor(const VulkanTexture& texture, VkImageLayout wanted) {
    return texture.storage()         ? VK_IMAGE_LAYOUT_GENERAL
           : texture.sharedPresent() ? VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR
                                     : wanted;
  }

  VkRenderPass renderPass(VkFormat format, rhi::LoadAction load, VkImageLayout layout);
  void ensureRenderPass();
  void suspendRenderPass();
  void applyDynamicState();

  struct Allocation {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceSize offset = 0;
    uint8_t* mapped = nullptr;
  };
  Allocation allocateUpload(VkDeviceSize size, VkDeviceSize alignment);
  void uploadToImage(const VulkanTexture& texture, int32_t level, const rhi::Rect& region,
                     const void* data, int32_t rowLength);

  bool prepareResources(bool compute);
  bool bindDescriptors(bool compute);
  VkDescriptorSet allocateSet(VkDescriptorSetLayout layout);
  VkDescriptorSetLayout setLayout(const std::vector<SpirvResource>& resources, uint32_t set);
  const VulkanTexture& placeholder(const SpirvResource& resource);
  VkSampler sampler(const rhi::SamplerDescription& description);

  const rhi::Pipeline* createPipeline(const std::string& key,
                                      const rhi::PipelineDescription* graphics,
                                      const rhi::ComputePipelineDescription* compute,
                                      std::string& errorMessage);
  void savePipelineCache();

  bool acquireWindowImage();
  bool createSwapchain(bool shared, std::string& errorMessage);
  bool describeSwapchain(bool shared, VkSwapchainCreateInfoKHR& info, std::string& errorMessage);
  void adoptSwapchain(VkSwapchainKHR handle, const VkSwapchainCreateInfoKHR& info);
  void destroySwapchain();
  void releaseSwapchain(Swapchain& swapchain);
  void beginLeave();
  void finishLeave(bool wait);
  void collectPresentTimes();

  std::shared_ptr<VulkanContext> context_;
  VkDevice device_;
  std::string setupError_;
  SpirvCompiler compiler_;
  std::string pipelineCachePath_;
  VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
  VkCommandPool commandPool_ = VK_NULL_HANDLE;
  bool timerQueriesSupported_ = false;
  VkDeviceSize uniformAlignment_ = 256;
  std::array<bool, 8> linearFilterable_{};
  int32_t pipelinesSinceCacheSave_ = 0;

  std::unique_ptr<Batch> recording_;
  std::deque<std::unique_ptr<Batch>> inFlight_;
  std::vector<std::unique_ptr<Batch>> idleBatches_;
  uint64_t recordingSerial_ = 1;
  VkDeviceSize batchCreatedBytes_ = 0;
  uint32_t batchCreatedAllocations_ = 0;
  uint64_t completedSerial_ = 0;
  std::deque<std::pair<uint64_t, Retired>> retired_;
  std::vector<VkSemaphore> waitSemaphores_;
  std::vector<VkSemaphore> idleSemaphores_;
  std::vector<HostBuffer> idleArenaChunks_;
  std::multimap<VkDeviceSize, HostBuffer> idleReadBuffers_;
  VkDeviceSize idleReadBufferBytes_ = 0;

  std::vector<VkImageMemoryBarrier> pendingBarriers_;
  VkPipelineStageFlags pendingSourceStages_ = 0;
  VkPipelineStageFlags pendingDestinationStages_ = 0;

  std::map<std::string, std::unique_ptr<ProgramSources>> programs_;
  std::map<std::string, std::unique_ptr<Pipeline>> pipelines_;
  std::map<std::string, VkDescriptorSetLayout> setLayouts_;
  std::map<std::pair<VkDescriptorSetLayout, VkDescriptorSetLayout>, VkPipelineLayout>
      pipelineLayouts_;
  std::map<std::tuple<VkFormat, rhi::LoadAction, VkImageLayout>, VkRenderPass> renderPasses_;
  std::map<uint32_t, VkSampler> samplers_;
  VkDescriptorSetLayout emptySetLayout_ = VK_NULL_HANDLE;
  std::unique_ptr<VulkanTexture> placeholder2D_;
  std::unique_ptr<VulkanTexture> placeholder3D_;
  std::unique_ptr<VulkanTexture> placeholderUInt_;

  struct BoundTexture {
    const VulkanTexture* texture = nullptr;
    const rhi::Sampler* sampler = nullptr;
  };
  struct BoundStorage {
    const VulkanTexture* image = nullptr;
    rhi::StorageAccess access = rhi::StorageAccess::ReadWrite;
    const Buffer* buffer = nullptr;
  };
  static constexpr uint32_t kMaxBindings = 32;
  bool passOpen_ = false;
  bool renderPassActive_ = false;
  const VulkanTexture* passTexture_ = nullptr;
  int32_t passWidth_ = 0;
  int32_t passHeight_ = 0;
  rhi::LoadAction pendingLoad_ = rhi::LoadAction::Load;
  std::array<float, 4> pendingClear_{};
  rhi::Rect viewport_;
  std::optional<rhi::Rect> scissor_;
  bool dynamicStateDirty_ = true;
  const Pipeline* boundPipeline_ = nullptr;
  bool pipelineDirty_ = true;
  std::array<BoundTexture, kMaxBindings> textures_{};
  std::array<BoundStorage, kMaxBindings> storage_{};
  bool texturesDirty_ = true;
  bool storageDirty_ = true;
  std::vector<uint8_t> uniforms_;
  VkDescriptorSet set0_ = VK_NULL_HANDLE;
  VkDescriptorSet set1_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout set0Layout_ = VK_NULL_HANDLE;
  uint32_t set0UniformSize_ = 0;
  VkBuffer set0UniformBuffer_ = VK_NULL_HANDLE;
  const Buffer* vertexBuffer_ = nullptr;
  VkDeviceSize vertexBufferOffset_ = 0;

  ANativeWindow* window_ = nullptr;
  VkSurfaceKHR surface_ = VK_NULL_HANDLE;
  bool displayP3_ = false;
  bool displayP3Active_ = false;
  bool displayP3Offered_ = true;
  std::unique_ptr<Swapchain> swapchain_;
  std::deque<std::pair<uint64_t, std::unique_ptr<Swapchain>>> retiredSwapchains_;
  std::optional<uint32_t> acquiredImage_;
  bool frontBufferWanted_ = false;
  bool frontBufferOffered_ = false;
  std::unique_ptr<Leave> leave_;
  PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR getSurfaceCapabilities2_ = nullptr;
  PFN_vkGetPastPresentationTimingGOOGLE getPastPresentationTiming_ = nullptr;
  uint32_t nextPresentId_ = 1;
  uint32_t swapchainFirstPresentId_ = 1;
  uint32_t newestReportedPresentId_ = 0;
  std::chrono::steady_clock::time_point lastPresentTime_;
  std::map<uint32_t, int64_t> presentTimes_;
};

}  // namespace nanquim::gpu::vulkan
