#include "gpu/vulkan/vulkan_probe.h"

#define VK_USE_PLATFORM_ANDROID_KHR
#include <time.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <nlohmann/json.hpp>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "core/log.h"
#include "gpu/gles/egl_switch_probe.h"

namespace nanquim::gpu::vulkan {
namespace {

using Clock = std::chrono::steady_clock;
using nlohmann::json;

class ProbeError : public std::runtime_error {
 public:
  using std::runtime_error::runtime_error;
};

template <typename T>
T make(VkStructureType type) {
  T value{};
  value.sType = type;
  return value;
}

void check(VkResult result, const char* what) {
  if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
    throw ProbeError(std::string(what) + " failed: VkResult " + std::to_string(result));
  }
}

double millisecondsSince(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

int64_t monotonicNanos() {
  timespec now{};
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<int64_t>(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

json summary(std::vector<double> values) {
  if (values.empty()) {
    return json{{"count", 0}};
  }
  std::sort(values.begin(), values.end());
  const auto at = [&](double fraction) {
    return values[std::min(values.size() - 1, static_cast<size_t>(fraction * values.size()))];
  };
  return json{{"count", values.size()},
              {"min", values.front()},
              {"median", at(0.5)},
              {"p90", at(0.9)},
              {"max", values.back()}};
}

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
  return std::any_of(extensions.begin(), extensions.end(), [&](const VkExtensionProperties& e) {
    return std::strcmp(e.extensionName, name) == 0;
  });
}

const char* presentModeName(VkPresentModeKHR mode) {
  switch (mode) {
    case VK_PRESENT_MODE_IMMEDIATE_KHR:
      return "immediate";
    case VK_PRESENT_MODE_MAILBOX_KHR:
      return "mailbox";
    case VK_PRESENT_MODE_FIFO_KHR:
      return "fifo";
    case VK_PRESENT_MODE_FIFO_RELAXED_KHR:
      return "fifoRelaxed";
    case VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR:
      return "sharedDemandRefresh";
    case VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR:
      return "sharedContinuousRefresh";
    default:
      return "other";
  }
}

class Probe {
 public:
  explicit Probe(ANativeWindow* window) : window_(window) {}
  Probe(const Probe&) = delete;
  Probe& operator=(const Probe&) = delete;
  ~Probe() { destroy(); }

  json run(int switchCycles) {
    json report;
    auto start = Clock::now();
    createInstance(report);
    pickDevice(report);
    createSurface();
    createDevice(report);
    report["initializeMs"] = millisecondsSince(start);
    describeSurface(report);
    report["doubleBuffered"] = runDoubleBuffered();
    report["frontBufferSwitch"] = runSwitchCycles(switchCycles);
    report["frontBufferLeave"] = runLeaveVariants(switchCycles);
    report["frontBufferHold"] = runHoldWithoutPresent();
    report["displayP3"] = runDisplayP3();
    report["readback"] = runReadbacks();
    destroy();
    return report;
  }

  void destroy() {
    if (device_ != VK_NULL_HANDLE) {
      vkDeviceWaitIdle(device_);
      destroySwapchain(swapchain_);
      destroySwapchain(retired_);
      if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
      if (acquired_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, acquired_, nullptr);
      if (rendered_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, rendered_, nullptr);
      if (pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, pool_, nullptr);
      for (VkRenderPass pass : renderPasses_) vkDestroyRenderPass(device_, pass, nullptr);
      renderPasses_.clear();
      vkDestroyDevice(device_, nullptr);
      device_ = VK_NULL_HANDLE;
    }
    if (surface_ != VK_NULL_HANDLE) {
      vkDestroySurfaceKHR(instance_, surface_, nullptr);
      surface_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
      vkDestroyInstance(instance_, nullptr);
      instance_ = VK_NULL_HANDLE;
    }
  }

 private:
  struct Swapchain {
    VkSwapchainKHR handle = VK_NULL_HANDLE;
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};
    VkRenderPass renderPass = VK_NULL_HANDLE;
    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> framebuffers;
    uint32_t sharedImage = 0;
    uint32_t nextPresentId = 1;
    [[nodiscard]] bool shared() const {
      return mode == VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR ||
             mode == VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR;
    }
  };

  void createInstance(json& report) {
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());
    std::vector<const char*> extensions = {VK_KHR_SURFACE_EXTENSION_NAME,
                                           VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    for (const char* optional : {VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME,
                                 VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME}) {
      if (hasExtension(available, optional)) extensions.push_back(optional);
    }
    surfaceCapabilities2_ =
        hasExtension(available, VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
    colorSpaceExtension_ = hasExtension(available, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    report["instance"] = {{"getSurfaceCapabilities2", surfaceCapabilities2_},
                          {"swapchainColorSpace", colorSpaceExtension_}};

    auto application = make<VkApplicationInfo>(VK_STRUCTURE_TYPE_APPLICATION_INFO);
    application.pApplicationName = "Nanquim Vulkan probe";
    application.apiVersion = VK_API_VERSION_1_1;
    auto info = make<VkInstanceCreateInfo>(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
    info.pApplicationInfo = &application;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    check(vkCreateInstance(&info, nullptr, &instance_), "vkCreateInstance");
  }

  void pickDevice(json& report) {
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(instance_, &count, nullptr), "vkEnumeratePhysicalDevices");
    if (count == 0) throw ProbeError("no Vulkan physical device");
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());
    physical_ = devices[0];

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_, &properties);
    json device;
    device["name"] = properties.deviceName;
    device["apiVersion"] = std::to_string(VK_VERSION_MAJOR(properties.apiVersion)) + "." +
                           std::to_string(VK_VERSION_MINOR(properties.apiVersion)) + "." +
                           std::to_string(VK_VERSION_PATCH(properties.apiVersion));
    device["driverVersion"] = properties.driverVersion;
    device["limits"] = {
        {"maxImageDimension2D", properties.limits.maxImageDimension2D},
        {"maxImageDimension3D", properties.limits.maxImageDimension3D},
        {"maxMemoryAllocationCount", properties.limits.maxMemoryAllocationCount},
        {"maxComputeWorkGroupInvocations", properties.limits.maxComputeWorkGroupInvocations},
        {"maxUniformBufferRange", properties.limits.maxUniformBufferRange},
        {"maxPushConstantsSize", properties.limits.maxPushConstantsSize},
        {"maxBoundDescriptorSets", properties.limits.maxBoundDescriptorSets},
        {"maxPerStageDescriptorSamplers", properties.limits.maxPerStageDescriptorSamplers},
        {"minUniformBufferOffsetAlignment", properties.limits.minUniformBufferOffsetAlignment},
        {"timestampPeriod", properties.limits.timestampPeriod},
        {"timestampComputeAndGraphics", properties.limits.timestampComputeAndGraphics == VK_TRUE}};

    VkPhysicalDeviceFeatures features{};
    vkGetPhysicalDeviceFeatures(physical_, &features);
    device["features"] = {
        {"fragmentStoresAndAtomics", features.fragmentStoresAndAtomics == VK_TRUE},
        {"shaderStorageImageWriteWithoutFormat",
         features.shaderStorageImageWriteWithoutFormat == VK_TRUE},
        {"independentBlend", features.independentBlend == VK_TRUE},
        {"samplerAnisotropy", features.samplerAnisotropy == VK_TRUE}};

    uint32_t extensionCount = 0;
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extensionCount, nullptr);
    deviceExtensions_.resize(extensionCount);
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &extensionCount,
                                         deviceExtensions_.data());
    json extensions;
    for (const char* name :
         {VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME,
          VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME, VK_KHR_INCREMENTAL_PRESENT_EXTENSION_NAME,
          VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,
          VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME,
          VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
          VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME, VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME}) {
      extensions[name] = hasExtension(deviceExtensions_, name);
    }
    device["extensions"] = extensions;

    json formats;
    const auto describeFormat = [&](const char* name, VkFormat format) {
      VkFormatProperties formatProperties{};
      vkGetPhysicalDeviceFormatProperties(physical_, format, &formatProperties);
      const VkFormatFeatureFlags optimal = formatProperties.optimalTilingFeatures;
      formats[name] = {
          {"sampled", (optimal & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0},
          {"linearFilter", (optimal & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0},
          {"colorAttachment", (optimal & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0},
          {"blend", (optimal & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0},
          {"storage", (optimal & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0},
          {"blitSource", (optimal & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0},
          {"blitDestination", (optimal & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0}};
    };
    describeFormat("rgba8", VK_FORMAT_R8G8B8A8_UNORM);
    describeFormat("rgba16f", VK_FORMAT_R16G16B16A16_SFLOAT);
    describeFormat("r8", VK_FORMAT_R8_UNORM);
    describeFormat("r32ui", VK_FORMAT_R32_UINT);
    describeFormat("r32f", VK_FORMAT_R32_SFLOAT);
    describeFormat("rg32f", VK_FORMAT_R32G32_SFLOAT);
    VkImageFormatProperties lut{};
    device["rgba16f3dLut"] =
        vkGetPhysicalDeviceImageFormatProperties(
            physical_, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_TYPE_3D, VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 0, &lut) == VK_SUCCESS &&
        lut.maxExtent.depth >= 33;
    device["formats"] = formats;

    VkPhysicalDeviceMemoryProperties memory{};
    vkGetPhysicalDeviceMemoryProperties(physical_, &memory);
    memory_ = memory;
    json heaps = json::array();
    for (uint32_t i = 0; i < memory.memoryHeapCount; ++i) {
      heaps.push_back(
          {{"megabytes", memory.memoryHeaps[i].size / (1024 * 1024)},
           {"deviceLocal", (memory.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0}});
    }
    device["memoryHeaps"] = heaps;
    report["device"] = device;
  }

  void createSurface() {
    auto info =
        make<VkAndroidSurfaceCreateInfoKHR>(VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR);
    info.window = window_;
    check(vkCreateAndroidSurfaceKHR(instance_, &info, nullptr, &surface_),
          "vkCreateAndroidSurfaceKHR");
  }

  void createDevice(json& report) {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &count, families.data());
    bool found = false;
    for (uint32_t i = 0; i < count && !found; ++i) {
      VkBool32 present = VK_FALSE;
      vkGetPhysicalDeviceSurfaceSupportKHR(physical_, i, surface_, &present);
      const VkQueueFlags needed = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
      if ((families[i].queueFlags & needed) == needed && present == VK_TRUE) {
        family_ = i;
        found = true;
        report["queue"] = {{"family", i},
                           {"count", families[i].queueCount},
                           {"timestampValidBits", families[i].timestampValidBits}};
      }
    }
    if (!found) throw ProbeError("no graphics + compute queue that presents to the window");

    std::vector<const char*> extensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    sharedPresentable_ =
        surfaceCapabilities2_ &&
        hasExtension(deviceExtensions_, VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME);
    if (sharedPresentable_) extensions.push_back(VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME);
    displayTiming_ = hasExtension(deviceExtensions_, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
    if (displayTiming_) extensions.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);

    const float priority = 1.0F;
    auto queue = make<VkDeviceQueueCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
    queue.queueFamilyIndex = family_;
    queue.queueCount = 1;
    queue.pQueuePriorities = &priority;
    auto info = make<VkDeviceCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queue;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    check(vkCreateDevice(physical_, &info, nullptr, &device_), "vkCreateDevice");
    vkGetDeviceQueue(device_, family_, 0, &queue_);

    if (sharedPresentable_) {
      getSwapchainStatus_ = reinterpret_cast<PFN_vkGetSwapchainStatusKHR>(
          vkGetDeviceProcAddr(device_, "vkGetSwapchainStatusKHR"));
      getSurfaceCapabilities2_ = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR>(
          vkGetInstanceProcAddr(instance_, "vkGetPhysicalDeviceSurfaceCapabilities2KHR"));
    }
    if (displayTiming_) {
      getPastPresentationTiming_ = reinterpret_cast<PFN_vkGetPastPresentationTimingGOOGLE>(
          vkGetDeviceProcAddr(device_, "vkGetPastPresentationTimingGOOGLE"));
      getRefreshCycleDuration_ = reinterpret_cast<PFN_vkGetRefreshCycleDurationGOOGLE>(
          vkGetDeviceProcAddr(device_, "vkGetRefreshCycleDurationGOOGLE"));
    }

    auto poolInfo = make<VkCommandPoolCreateInfo>(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = family_;
    check(vkCreateCommandPool(device_, &poolInfo, nullptr, &pool_), "vkCreateCommandPool");
    auto allocate =
        make<VkCommandBufferAllocateInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
    allocate.commandPool = pool_;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    check(vkAllocateCommandBuffers(device_, &allocate, &commands_), "vkAllocateCommandBuffers");
    auto fenceInfo = make<VkFenceCreateInfo>(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
    check(vkCreateFence(device_, &fenceInfo, nullptr, &fence_), "vkCreateFence");
    auto semaphoreInfo = make<VkSemaphoreCreateInfo>(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
    check(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &acquired_), "vkCreateSemaphore");
    check(vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &rendered_), "vkCreateSemaphore");
  }

  void describeSurface(json& report) {
    json surface;
    VkSurfaceCapabilitiesKHR capabilities{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_, &capabilities);
    surface["extent"] = {capabilities.currentExtent.width, capabilities.currentExtent.height};
    surface["imageCount"] = {capabilities.minImageCount, capabilities.maxImageCount};
    surface["usage"] = capabilities.supportedUsageFlags;
    surface["transform"] = capabilities.currentTransform;

    uint32_t count = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &count, nullptr);
    std::vector<VkPresentModeKHR> modes(count);
    vkGetPhysicalDeviceSurfacePresentModesKHR(physical_, surface_, &count, modes.data());
    json modeNames = json::array();
    for (VkPresentModeKHR mode : modes) {
      modeNames.push_back(presentModeName(mode));
      if (mode == VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR) continuousRefresh_ = true;
    }
    surface["presentModes"] = modeNames;

    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &count, nullptr);
    surfaceFormats_.resize(count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &count, surfaceFormats_.data());
    json formats = json::array();
    for (const VkSurfaceFormatKHR& format : surfaceFormats_) {
      formats.push_back({{"format", format.format}, {"colorSpace", format.colorSpace}});
    }
    surface["formats"] = formats;

    if (sharedPresentable_ && getSurfaceCapabilities2_ != nullptr) {
      auto shared = make<VkSharedPresentSurfaceCapabilitiesKHR>(
          VK_STRUCTURE_TYPE_SHARED_PRESENT_SURFACE_CAPABILITIES_KHR);
      auto capabilities2 =
          make<VkSurfaceCapabilities2KHR>(VK_STRUCTURE_TYPE_SURFACE_CAPABILITIES_2_KHR);
      capabilities2.pNext = &shared;
      auto info = make<VkPhysicalDeviceSurfaceInfo2KHR>(
          VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SURFACE_INFO_2_KHR);
      info.surface = surface_;
      if (getSurfaceCapabilities2_(physical_, &info, &capabilities2) == VK_SUCCESS) {
        sharedUsage_ = shared.sharedPresentSupportedUsageFlags;
        surface["sharedPresentUsage"] = sharedUsage_;
      }
    }
    minImageCount_ = std::max<uint32_t>(capabilities.minImageCount, 2);
    if (capabilities.maxImageCount != 0) {
      minImageCount_ = std::min(minImageCount_ + 1, capabilities.maxImageCount);
    } else {
      minImageCount_ += 1;
    }
    transform_ = capabilities.currentTransform;
    extent_ = capabilities.currentExtent;
    report["surface"] = surface;
  }

  VkRenderPass renderPass(VkFormat format, VkImageLayout finalLayout) {
    VkAttachmentDescription attachment{};
    attachment.format = format;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.finalLayout = finalLayout;
    VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &reference;
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    auto info = make<VkRenderPassCreateInfo>(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
    info.attachmentCount = 1;
    info.pAttachments = &attachment;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 1;
    info.pDependencies = &dependency;
    VkRenderPass pass = VK_NULL_HANDLE;
    check(vkCreateRenderPass(device_, &info, nullptr, &pass), "vkCreateRenderPass");
    renderPasses_.push_back(pass);
    return pass;
  }

  VkSwapchainKHR createHandle(VkPresentModeKHR mode, VkFormat format, VkColorSpaceKHR colorSpace,
                              uint32_t imageCount) const {
    const bool shared = mode == VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR ||
                        mode == VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR;
    auto info = make<VkSwapchainCreateInfoKHR>(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
    info.surface = surface_;
    info.minImageCount = shared ? 1 : (imageCount != 0 ? imageCount : minImageCount_);
    info.imageFormat = format;
    info.imageColorSpace = colorSpace;
    info.imageExtent = extent_;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = transform_;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    info.presentMode = mode;
    info.clipped = VK_TRUE;
    info.oldSwapchain = swapchain_.handle;
    VkSwapchainKHR handle = VK_NULL_HANDLE;
    check(vkCreateSwapchainKHR(device_, &info, nullptr, &handle), "vkCreateSwapchainKHR");
    return handle;
  }

  json createSwapchain(VkPresentModeKHR mode, VkFormat format, VkColorSpaceKHR colorSpace,
                       uint32_t imageCount = 0, VkSwapchainKHR prebuilt = VK_NULL_HANDLE) {
    json timing;
    auto start = Clock::now();
    Swapchain next;
    next.mode = mode;
    next.format = format;
    next.extent = extent_;
    next.handle =
        prebuilt != VK_NULL_HANDLE ? prebuilt : createHandle(mode, format, colorSpace, imageCount);
    timing["createMs"] = millisecondsSince(start);

    uint32_t count = 0;
    vkGetSwapchainImagesKHR(device_, next.handle, &count, nullptr);
    next.images.resize(count);
    vkGetSwapchainImagesKHR(device_, next.handle, &count, next.images.data());
    next.renderPass = renderPass(format, next.shared() ? VK_IMAGE_LAYOUT_SHARED_PRESENT_KHR
                                                       : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    for (VkImage image : next.images) {
      auto viewInfo = make<VkImageViewCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
      viewInfo.image = image;
      viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = format;
      viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      VkImageView view = VK_NULL_HANDLE;
      check(vkCreateImageView(device_, &viewInfo, nullptr, &view), "vkCreateImageView");
      next.views.push_back(view);
      auto framebufferInfo =
          make<VkFramebufferCreateInfo>(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
      framebufferInfo.renderPass = next.renderPass;
      framebufferInfo.attachmentCount = 1;
      framebufferInfo.pAttachments = &view;
      framebufferInfo.width = extent_.width;
      framebufferInfo.height = extent_.height;
      framebufferInfo.layers = 1;
      VkFramebuffer framebuffer = VK_NULL_HANDLE;
      check(vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffer),
            "vkCreateFramebuffer");
      next.framebuffers.push_back(framebuffer);
    }
    timing["images"] = count;
    destroySwapchain(retired_);
    retired_ = swapchain_;
    swapchain_ = next;
    timing["setupMs"] = millisecondsSince(start);
    return timing;
  }

  void destroySwapchain(Swapchain& swapchain) {
    if (swapchain.handle == VK_NULL_HANDLE) return;
    for (VkFramebuffer framebuffer : swapchain.framebuffers) {
      vkDestroyFramebuffer(device_, framebuffer, nullptr);
    }
    for (VkImageView view : swapchain.views) vkDestroyImageView(device_, view, nullptr);
    vkDestroySwapchainKHR(device_, swapchain.handle, nullptr);
    swapchain = Swapchain{};
  }

  double destroyRetired() {
    auto start = Clock::now();
    destroySwapchain(retired_);
    return millisecondsSince(start);
  }

  void record(uint32_t image, float red, float green, float blue) {
    vkResetCommandBuffer(commands_, 0);
    auto begin = make<VkCommandBufferBeginInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commands_, &begin);
    VkClearValue clear{};
    clear.color = {{red, green, blue, 1.0F}};
    auto pass = make<VkRenderPassBeginInfo>(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
    pass.renderPass = swapchain_.renderPass;
    pass.framebuffer = swapchain_.framebuffers[image];
    pass.renderArea = {{0, 0}, swapchain_.extent};
    pass.clearValueCount = 1;
    pass.pClearValues = &clear;
    vkCmdBeginRenderPass(commands_, &pass, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdEndRenderPass(commands_);
    vkEndCommandBuffer(commands_);
  }

  struct FrameTiming {
    double acquireMs = 0;
    double gpuMs = 0;
    double presentMs = 0;
    uint32_t presentId = 0;
    int64_t submitNanos = 0;
  };

  FrameTiming frame(float red, float green, float blue, bool present = true) {
    FrameTiming timing;
    auto start = Clock::now();
    uint32_t image = swapchain_.sharedImage;
    bool waitAcquire = false;
    if (!swapchain_.shared() || !sharedAcquired_) {
      check(vkAcquireNextImageKHR(device_, swapchain_.handle, UINT64_MAX, acquired_, VK_NULL_HANDLE,
                                  &image),
            "vkAcquireNextImageKHR");
      waitAcquire = true;
      if (swapchain_.shared()) {
        swapchain_.sharedImage = image;
        sharedAcquired_ = true;
      }
    }
    timing.acquireMs = millisecondsSince(start);
    record(image, red, green, blue);
    const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    auto submit = make<VkSubmitInfo>(VK_STRUCTURE_TYPE_SUBMIT_INFO);
    submit.waitSemaphoreCount = waitAcquire ? 1 : 0;
    submit.pWaitSemaphores = &acquired_;
    submit.pWaitDstStageMask = &stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commands_;
    submit.signalSemaphoreCount = present ? 1 : 0;
    submit.pSignalSemaphores = &rendered_;
    auto submitted = Clock::now();
    timing.submitNanos = monotonicNanos();
    check(vkQueueSubmit(queue_, 1, &submit, fence_), "vkQueueSubmit");
    check(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX), "vkWaitForFences");
    vkResetFences(device_, 1, &fence_);
    timing.gpuMs = millisecondsSince(submitted);
    if (present) {
      auto presentStart = Clock::now();
      auto info = make<VkPresentInfoKHR>(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
      info.waitSemaphoreCount = 1;
      info.pWaitSemaphores = &rendered_;
      info.swapchainCount = 1;
      info.pSwapchains = &swapchain_.handle;
      info.pImageIndices = &image;
      VkPresentTimeGOOGLE time{swapchain_.nextPresentId, 0};
      auto times = make<VkPresentTimesInfoGOOGLE>(VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE);
      times.swapchainCount = 1;
      times.pTimes = &time;
      if (displayTiming_ && !swapchain_.shared()) {
        info.pNext = &times;
        timing.presentId = swapchain_.nextPresentId++;
      }
      check(vkQueuePresentKHR(queue_, &info), "vkQueuePresentKHR");
      timing.presentMs = millisecondsSince(presentStart);
    }
    return timing;
  }

  std::vector<VkPastPresentationTimingGOOGLE> pastTimings() {
    std::vector<VkPastPresentationTimingGOOGLE> timings;
    if (getPastPresentationTiming_ == nullptr || swapchain_.shared()) return timings;
    uint32_t count = 0;
    getPastPresentationTiming_(device_, swapchain_.handle, &count, nullptr);
    timings.resize(count);
    getPastPresentationTiming_(device_, swapchain_.handle, &count, timings.data());
    timings.resize(count);
    return timings;
  }

  json runDoubleBuffered() {
    json result = createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                                  VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    if (getRefreshCycleDuration_ != nullptr) {
      VkRefreshCycleDurationGOOGLE refresh{};
      if (getRefreshCycleDuration_(device_, swapchain_.handle, &refresh) == VK_SUCCESS) {
        result["refreshCycleMs"] = static_cast<double>(refresh.refreshDuration) / 1e6;
      }
    }
    std::vector<double> gpu;
    std::vector<double> acquire;
    for (int i = 0; i < 90; ++i) {
      const float value = static_cast<float>(i % 30) / 30.0F;
      FrameTiming timing = frame(value, 0.2F, 0.4F);
      gpu.push_back(timing.gpuMs);
      acquire.push_back(timing.acquireMs);
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    std::vector<double> intervals;
    std::vector<VkPastPresentationTimingGOOGLE> timings = pastTimings();
    for (size_t i = 1; i < timings.size(); ++i) {
      if (timings[i].presentID == timings[i - 1].presentID + 1) {
        intervals.push_back(
            static_cast<double>(timings[i].actualPresentTime - timings[i - 1].actualPresentTime) /
            1e6);
      }
    }
    result["gpuMs"] = summary(gpu);
    result["acquireMs"] = summary(acquire);
    result["presentIntervalMs"] = summary(intervals);
    return result;
  }

  json runSwitchCycles(int cycles) {
    json result;
    if (!sharedPresentable_ || !continuousRefresh_) {
      result["supported"] = false;
      return result;
    }
    if ((sharedUsage_ & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
      result["supported"] = false;
      result["reason"] = "shared images cannot be color attachments";
      return result;
    }
    result["supported"] = true;
    std::vector<double> enterCreate;
    std::vector<double> enterTotal;
    std::vector<double> enterFirstFrame;
    std::vector<double> frontGpu;
    std::vector<double> frontPresent;
    std::vector<double> leaveCreate;
    std::vector<double> leaveTotal;
    std::vector<double> leaveVisible;
    std::vector<double> retire;
    json failures = json::array();
    for (int cycle = 0; cycle < cycles; ++cycle) {
      try {
        for (int i = 0; i < 20; ++i) frame(0.1F, 0.1F, 0.1F + 0.02F * static_cast<float>(i));

        auto enterStart = Clock::now();
        json created = createSwapchain(VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR,
                                       VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        retire.push_back(destroyRetired());
        sharedAcquired_ = false;
        auto firstFrameStart = Clock::now();
        frame(0.9F, 0.1F, 0.1F);
        enterFirstFrame.push_back(millisecondsSince(firstFrameStart));
        enterCreate.push_back(created["createMs"].get<double>());
        enterTotal.push_back(millisecondsSince(enterStart));

        for (int i = 0; i < 30; ++i) {
          FrameTiming timing = frame(0.9F, 0.1F + 0.02F * static_cast<float>(i), 0.1F);
          frontGpu.push_back(timing.gpuMs);
          frontPresent.push_back(timing.presentMs);
          std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }

        auto leaveStart = Clock::now();
        const int64_t leaveNanos = monotonicNanos();
        json back = createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                                    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
        retire.push_back(destroyRetired());
        sharedAcquired_ = false;
        FrameTiming first = frame(0.1F, 0.6F, 0.1F);
        leaveCreate.push_back(back["createMs"].get<double>());
        leaveTotal.push_back(millisecondsSince(leaveStart));
        for (int i = 0; i < 10; ++i) frame(0.1F, 0.6F, 0.1F);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        for (const VkPastPresentationTimingGOOGLE& timing : pastTimings()) {
          if (timing.presentID == first.presentId) {
            leaveVisible.push_back(
                static_cast<double>(static_cast<int64_t>(timing.actualPresentTime) - leaveNanos) /
                1e6);
          }
        }
      } catch (const ProbeError& error) {
        failures.push_back({{"cycle", cycle}, {"error", error.what()}});
        break;
      }
    }
    result["enterCreateMs"] = summary(enterCreate);
    result["enterFirstFrameMs"] = summary(enterFirstFrame);
    result["enterTotalMs"] = summary(enterTotal);
    result["frontGpuMs"] = summary(frontGpu);
    result["frontPresentMs"] = summary(frontPresent);
    result["leaveCreateMs"] = summary(leaveCreate);
    result["leaveTotalMs"] = summary(leaveTotal);
    result["leaveVisibleMs"] = summary(leaveVisible);
    result["retireMs"] = summary(retire);
    result["failures"] = failures;
    return result;
  }

  json runLeaveVariants(int cycles) {
    json result;
    if (!sharedPresentable_ || !continuousRefresh_ ||
        (sharedUsage_ & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
      result["supported"] = false;
      return result;
    }
    for (const char* variant : {"twoImages", "background"}) {
      const bool background = std::strcmp(variant, "background") == 0;
      std::vector<double> renderThreadHeld;
      std::vector<double> untilDoubleBuffered;
      std::vector<double> drawsMeanwhile;
      std::vector<double> drawGpuMeanwhile;
      json failures = json::array();
      for (int cycle = 0; cycle < cycles; ++cycle) {
        try {
          for (int i = 0; i < 10; ++i) frame(0.1F, 0.1F, 0.2F);
          createSwapchain(VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                          VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
          destroyRetired();
          sharedAcquired_ = false;
          for (int i = 0; i < 20; ++i) {
            frame(0.9F, 0.1F + 0.02F * static_cast<float>(i), 0.1F);
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
          }
          auto leaveStart = Clock::now();
          if (background) {
            std::atomic<bool> done{false};
            VkSwapchainKHR built = VK_NULL_HANDLE;
            std::string error;
            std::thread worker([&] {
              try {
                built = createHandle(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                                     VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, 0);
              } catch (const ProbeError& e) {
                error = e.what();
              }
              done = true;
            });
            int draws = 0;
            double heldMax = 0;
            while (!done) {
              FrameTiming timing = frame(0.9F, 0.5F, 0.1F, false);
              drawGpuMeanwhile.push_back(timing.gpuMs);
              heldMax = std::max(heldMax, timing.gpuMs);
              ++draws;
              std::this_thread::sleep_for(std::chrono::milliseconds(4));
            }
            worker.join();
            if (!error.empty()) throw ProbeError(error);
            drawsMeanwhile.push_back(draws);
            auto adoptStart = Clock::now();
            createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                            VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, 0, built);
            destroyRetired();
            sharedAcquired_ = false;
            frame(0.1F, 0.6F, 0.1F);
            renderThreadHeld.push_back(std::max(heldMax, millisecondsSince(adoptStart)));
          } else {
            createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                            VK_COLOR_SPACE_SRGB_NONLINEAR_KHR, 2);
            destroyRetired();
            sharedAcquired_ = false;
            frame(0.1F, 0.6F, 0.1F);
            renderThreadHeld.push_back(millisecondsSince(leaveStart));
          }
          untilDoubleBuffered.push_back(millisecondsSince(leaveStart));
          std::vector<double> gpu;
          for (int i = 0; i < 30; ++i) gpu.push_back(frame(0.1F, 0.6F, 0.1F).gpuMs);
          if (!background) {
            result[variant]["afterGpuMsCycle" + std::to_string(cycle)] = summary(gpu)["max"];
          }
        } catch (const ProbeError& error) {
          failures.push_back({{"cycle", cycle}, {"error", error.what()}});
          break;
        }
      }
      result[variant]["renderThreadHeldMs"] = summary(renderThreadHeld);
      result[variant]["untilDoubleBufferedMs"] = summary(untilDoubleBuffered);
      if (background) {
        result[variant]["drawsMeanwhile"] = summary(drawsMeanwhile);
        result[variant]["drawGpuMeanwhileMs"] = summary(drawGpuMeanwhile);
      }
      result[variant]["failures"] = failures;
    }
    createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    destroyRetired();
    sharedAcquired_ = false;
    frame(0.1F, 0.1F, 0.1F);
    return result;
  }

  json runHoldWithoutPresent() {
    json result;
    if (!sharedPresentable_ || !continuousRefresh_ ||
        (sharedUsage_ & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
      result["supported"] = false;
      return result;
    }
    createSwapchain(VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    destroyRetired();
    sharedAcquired_ = false;
    frame(1.0F, 0.0F, 0.0F);
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    frame(0.0F, 1.0F, 0.0F, false);
    NANQUIM_LOG_INFO("VulkanProbe hold: green drawn without present, holding 2 s");
    std::this_thread::sleep_for(std::chrono::milliseconds(2000));
    NANQUIM_LOG_INFO("VulkanProbe hold end");
    if (getSwapchainStatus_ != nullptr) {
      result["swapchainStatus"] = getSwapchainStatus_(device_, swapchain_.handle);
    }
    result["supported"] = true;
    createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
    destroyRetired();
    sharedAcquired_ = false;
    frame(0.1F, 0.1F, 0.1F);
    return result;
  }

  json runDisplayP3() {
    json result;
    const auto p3 = std::find_if(
        surfaceFormats_.begin(), surfaceFormats_.end(), [](const VkSurfaceFormatKHR& format) {
          return format.colorSpace == VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT;
        });
    result["offered"] = p3 != surfaceFormats_.end();
    if (p3 == surfaceFormats_.end()) return result;
    result["format"] = p3->format;
    try {
      createSwapchain(VK_PRESENT_MODE_FIFO_KHR, p3->format, p3->colorSpace);
      destroyRetired();
      sharedAcquired_ = false;
      for (int i = 0; i < 20; ++i) frame(1.0F, 0.0F, 0.0F);
      result["doubleBuffered"] = true;
      if (sharedPresentable_ && continuousRefresh_) {
        createSwapchain(VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR, p3->format, p3->colorSpace);
        destroyRetired();
        sharedAcquired_ = false;
        for (int i = 0; i < 10; ++i) frame(1.0F, 0.0F, 0.0F);
        result["frontBuffered"] = true;
      }
      NANQUIM_LOG_INFO("VulkanProbe p3: red in Display P3, holding 1.5 s");
      std::this_thread::sleep_for(std::chrono::milliseconds(1500));
      createSwapchain(VK_PRESENT_MODE_FIFO_KHR, VK_FORMAT_R8G8B8A8_UNORM,
                      VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
      destroyRetired();
      sharedAcquired_ = false;
      frame(0.1F, 0.1F, 0.1F);
    } catch (const ProbeError& error) {
      result["error"] = error.what();
    }
    return result;
  }

  uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags wanted) const {
    for (uint32_t i = 0; i < memory_.memoryTypeCount; ++i) {
      if ((bits & (1U << i)) != 0 && (memory_.memoryTypes[i].propertyFlags & wanted) == wanted) {
        return i;
      }
    }
    throw ProbeError("no memory type");
  }

  json runReadback(VkFormat format, uint32_t bytesPerPixel, const VkClearColorValue& color) {
    constexpr uint32_t kSize = 2048;
    json result;
    auto imageInfo = make<VkImageCreateInfo>(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {kSize, kSize, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkImage image = VK_NULL_HANDLE;
    check(vkCreateImage(device_, &imageInfo, nullptr, &image), "vkCreateImage");
    VkMemoryRequirements imageRequirements{};
    vkGetImageMemoryRequirements(device_, image, &imageRequirements);
    auto imageAllocate = make<VkMemoryAllocateInfo>(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
    imageAllocate.allocationSize = imageRequirements.size;
    imageAllocate.memoryTypeIndex =
        memoryType(imageRequirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkDeviceMemory imageMemory = VK_NULL_HANDLE;
    check(vkAllocateMemory(device_, &imageAllocate, nullptr, &imageMemory), "vkAllocateMemory");
    vkBindImageMemory(device_, image, imageMemory, 0);

    const VkDeviceSize bytes = static_cast<VkDeviceSize>(kSize) * kSize * bytesPerPixel;
    auto bufferInfo = make<VkBufferCreateInfo>(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
    bufferInfo.size = bytes;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    VkBuffer buffer = VK_NULL_HANDLE;
    check(vkCreateBuffer(device_, &bufferInfo, nullptr, &buffer), "vkCreateBuffer");
    VkMemoryRequirements bufferRequirements{};
    vkGetBufferMemoryRequirements(device_, buffer, &bufferRequirements);
    auto bufferAllocate = make<VkMemoryAllocateInfo>(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
    bufferAllocate.allocationSize = bufferRequirements.size;
    VkMemoryPropertyFlags hostFlags =
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
    bool cached = true;
    try {
      bufferAllocate.memoryTypeIndex = memoryType(bufferRequirements.memoryTypeBits, hostFlags);
    } catch (const ProbeError&) {
      cached = false;
      hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
      bufferAllocate.memoryTypeIndex = memoryType(bufferRequirements.memoryTypeBits, hostFlags);
    }
    result["hostCached"] = cached;
    const bool coherent = (memory_.memoryTypes[bufferAllocate.memoryTypeIndex].propertyFlags &
                           VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    VkDeviceMemory bufferMemory = VK_NULL_HANDLE;
    check(vkAllocateMemory(device_, &bufferAllocate, nullptr, &bufferMemory), "vkAllocateMemory");
    vkBindBufferMemory(device_, buffer, bufferMemory, 0);
    void* mapped = nullptr;
    check(vkMapMemory(device_, bufferMemory, 0, VK_WHOLE_SIZE, 0, &mapped), "vkMapMemory");

    std::vector<double> gpu;
    std::vector<double> read;
    std::vector<uint8_t> copy(bytes);
    bool correct = true;
    for (int i = 0; i < 6; ++i) {
      vkResetCommandBuffer(commands_, 0);
      auto begin = make<VkCommandBufferBeginInfo>(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
      begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
      vkBeginCommandBuffer(commands_, &begin);
      auto toTransfer = make<VkImageMemoryBarrier>(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
      toTransfer.srcAccessMask = 0;
      toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
      toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      toTransfer.image = image;
      toTransfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      vkCmdPipelineBarrier(commands_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                           &toTransfer);
      const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      vkCmdClearColorImage(commands_, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1,
                           &range);
      VkImageMemoryBarrier toSource = toTransfer;
      toSource.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      toSource.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
      toSource.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
      toSource.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
      vkCmdPipelineBarrier(commands_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toSource);
      VkBufferImageCopy region{};
      region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      region.imageExtent = {kSize, kSize, 1};
      vkCmdCopyImageToBuffer(commands_, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                             &region);
      auto toHost = make<VkBufferMemoryBarrier>(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER);
      toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
      toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      toHost.buffer = buffer;
      toHost.size = VK_WHOLE_SIZE;
      vkCmdPipelineBarrier(commands_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                           0, nullptr, 1, &toHost, 0, nullptr);
      vkEndCommandBuffer(commands_);
      auto submit = make<VkSubmitInfo>(VK_STRUCTURE_TYPE_SUBMIT_INFO);
      submit.commandBufferCount = 1;
      submit.pCommandBuffers = &commands_;
      auto start = Clock::now();
      check(vkQueueSubmit(queue_, 1, &submit, fence_), "vkQueueSubmit");
      check(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX), "vkWaitForFences");
      vkResetFences(device_, 1, &fence_);
      gpu.push_back(millisecondsSince(start));
      auto readStart = Clock::now();
      if (!coherent) {
        auto mappedRange = make<VkMappedMemoryRange>(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE);
        mappedRange.memory = bufferMemory;
        mappedRange.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(device_, 1, &mappedRange);
      }
      std::memcpy(copy.data(), mapped, bytes);
      read.push_back(millisecondsSince(readStart));
    }
    const uint8_t* last = copy.data() + bytes - bytesPerPixel;
    if (format == VK_FORMAT_R8G8B8A8_UNORM) {
      result["lastPixel"] = {last[0], last[1], last[2], last[3]};
      correct = last[0] == 255 && last[1] == 128 && last[2] == 0 && last[3] == 255;
    } else {
      uint16_t halves[4];
      std::memcpy(halves, last, sizeof(halves));
      result["lastPixelHalfBits"] = {halves[0], halves[1], halves[2], halves[3]};
      correct =
          halves[0] == 0x3C00 && halves[1] == 0x3800 && halves[2] == 0x3400 && halves[3] == 0x3C00;
    }
    result["correct"] = correct;
    result["megabytes"] = static_cast<double>(bytes) / (1024.0 * 1024.0);
    result["gpuMs"] = summary(gpu);
    result["memcpyMs"] = summary(read);

    vkUnmapMemory(device_, bufferMemory);
    vkDestroyBuffer(device_, buffer, nullptr);
    vkFreeMemory(device_, bufferMemory, nullptr);
    vkDestroyImage(device_, image, nullptr);
    vkFreeMemory(device_, imageMemory, nullptr);
    return result;
  }

  json runReadbacks() {
    json result;
    VkClearColorValue eight{};
    eight.float32[0] = 1.0F;
    eight.float32[1] = 128.0F / 255.0F;
    eight.float32[2] = 0.0F;
    eight.float32[3] = 1.0F;
    result["rgba8"] = runReadback(VK_FORMAT_R8G8B8A8_UNORM, 4, eight);
    VkClearColorValue half{};
    half.float32[0] = 1.0F;
    half.float32[1] = 0.5F;
    half.float32[2] = 0.25F;
    half.float32[3] = 1.0F;
    result["rgba16f"] = runReadback(VK_FORMAT_R16G16B16A16_SFLOAT, 8, half);
    return result;
  }

  ANativeWindow* window_;
  VkInstance instance_ = VK_NULL_HANDLE;
  VkPhysicalDevice physical_ = VK_NULL_HANDLE;
  VkPhysicalDeviceMemoryProperties memory_{};
  std::vector<VkExtensionProperties> deviceExtensions_;
  VkSurfaceKHR surface_ = VK_NULL_HANDLE;
  VkDevice device_ = VK_NULL_HANDLE;
  VkQueue queue_ = VK_NULL_HANDLE;
  uint32_t family_ = 0;
  VkCommandPool pool_ = VK_NULL_HANDLE;
  VkCommandBuffer commands_ = VK_NULL_HANDLE;
  VkFence fence_ = VK_NULL_HANDLE;
  VkSemaphore acquired_ = VK_NULL_HANDLE;
  VkSemaphore rendered_ = VK_NULL_HANDLE;
  std::vector<VkRenderPass> renderPasses_;
  Swapchain swapchain_;
  Swapchain retired_;
  bool sharedAcquired_ = false;
  std::vector<VkSurfaceFormatKHR> surfaceFormats_;
  VkSurfaceTransformFlagBitsKHR transform_ = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
  VkExtent2D extent_{};
  uint32_t minImageCount_ = 3;
  VkImageUsageFlags sharedUsage_ = 0;
  bool surfaceCapabilities2_ = false;
  bool colorSpaceExtension_ = false;
  bool sharedPresentable_ = false;
  bool continuousRefresh_ = false;
  bool displayTiming_ = false;
  PFN_vkGetSwapchainStatusKHR getSwapchainStatus_ = nullptr;
  PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR getSurfaceCapabilities2_ = nullptr;
  PFN_vkGetPastPresentationTimingGOOGLE getPastPresentationTiming_ = nullptr;
  PFN_vkGetRefreshCycleDurationGOOGLE getRefreshCycleDuration_ = nullptr;
};

}  // namespace

std::string runProbe(ANativeWindow* window, int switchCycles) {
  json report;
  try {
    Probe probe(window);
    report["vulkan"] = probe.run(switchCycles);
  } catch (const ProbeError& error) {
    report["vulkan"] = {{"error", error.what()}};
  }
  report["egl"] = nlohmann::json::parse(gles::runSingleBufferSwitchProbe(window, switchCycles));
  return report.dump(2);
}

}  // namespace nanquim::gpu::vulkan
