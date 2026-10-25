#include "gpu/vulkan/vulkan_context.h"

#include <algorithm>
#include <cstring>
#include <vector>

#include "core/log.h"

namespace nanquim::gpu::vulkan {
namespace {

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
  return std::any_of(extensions.begin(), extensions.end(),
                     [&](const VkExtensionProperties& extension) {
                       return std::strcmp(extension.extensionName, name) == 0;
                     });
}

bool hasFeatures(VkPhysicalDevice physical, VkFormat format, VkFormatFeatureFlags features) {
  VkFormatProperties properties{};
  vkGetPhysicalDeviceFormatProperties(physical, format, &properties);
  return (properties.optimalTilingFeatures & features) == features;
}

}  // namespace

std::string describeVkResult(const char* what, VkResult result) {
  return std::string(what) + " failed: VkResult " + std::to_string(static_cast<int>(result));
}

std::unique_ptr<VulkanContext> VulkanContext::create(std::string& errorMessage) {
  std::unique_ptr<VulkanContext> context(new VulkanContext());

  uint32_t apiVersion = VK_API_VERSION_1_0;
  vkEnumerateInstanceVersion(&apiVersion);
  if (apiVersion < VK_API_VERSION_1_1) {
    errorMessage = "Vulkan 1.1 required";
    return nullptr;
  }
  uint32_t count = 0;
  vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> available(count);
  vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());
  std::vector<const char*> extensions = {VK_KHR_SURFACE_EXTENSION_NAME,
                                         VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
  context->swapchainColorSpace_ =
      hasExtension(available, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
  if (context->swapchainColorSpace_) {
    extensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
  }
  const bool surfaceCapabilities2 =
      hasExtension(available, VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
  if (surfaceCapabilities2) {
    extensions.push_back(VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME);
  }
  auto application = make<VkApplicationInfo>(VK_STRUCTURE_TYPE_APPLICATION_INFO);
  application.pApplicationName = "Nanquim";
  application.pEngineName = "Nanquim";
  application.apiVersion = VK_API_VERSION_1_1;
  auto instanceInfo = make<VkInstanceCreateInfo>(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
  instanceInfo.pApplicationInfo = &application;
  instanceInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
  instanceInfo.ppEnabledExtensionNames = extensions.data();
  VkResult result = vkCreateInstance(&instanceInfo, nullptr, &context->instance_);
  if (result != VK_SUCCESS) {
    errorMessage = describeVkResult("vkCreateInstance", result);
    return nullptr;
  }

  count = 0;
  vkEnumeratePhysicalDevices(context->instance_, &count, nullptr);
  if (count == 0) {
    errorMessage = "No Vulkan device";
    return nullptr;
  }
  std::vector<VkPhysicalDevice> physicalDevices(count);
  vkEnumeratePhysicalDevices(context->instance_, &count, physicalDevices.data());
  context->physical_ = physicalDevices[0];
  VkPhysicalDevice physical = context->physical_;
  vkGetPhysicalDeviceProperties(physical, &context->properties_);
  vkGetPhysicalDeviceMemoryProperties(physical, &context->memory_);
  if (context->properties_.apiVersion < VK_API_VERSION_1_1) {
    errorMessage = std::string(context->properties_.deviceName) + ": Vulkan 1.1 required";
    return nullptr;
  }
  constexpr VkFormatFeatureFlags kTarget = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
                                           VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT |
                                           VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
  if (!hasFeatures(physical, VK_FORMAT_R8G8B8A8_UNORM, kTarget) ||
      !hasFeatures(physical, VK_FORMAT_R16G16B16A16_SFLOAT, kTarget) ||
      !hasFeatures(physical, VK_FORMAT_R32_UINT,
                   VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
                       VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)) {
    errorMessage = std::string(context->properties_.deviceName) +
                   ": half-float targets with blending or R32_UINT storage images missing";
    return nullptr;
  }

  count = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
  std::vector<VkQueueFamilyProperties> families(count);
  vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, families.data());
  bool found = false;
  for (uint32_t family = 0; family < count && !found; ++family) {
    constexpr VkQueueFlags kNeeded = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
    if ((families[family].queueFlags & kNeeded) == kNeeded) {
      context->queueFamily_ = family;
      context->timestampValidBits_ = families[family].timestampValidBits;
      found = true;
    }
  }
  if (!found) {
    errorMessage = "No graphics and compute queue";
    return nullptr;
  }

  count = 0;
  vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> deviceExtensions(count);
  vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, deviceExtensions.data());
  if (!hasExtension(deviceExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
    errorMessage = "VK_KHR_swapchain missing";
    return nullptr;
  }
  VkPhysicalDeviceFeatures supported{};
  vkGetPhysicalDeviceFeatures(physical, &supported);
  if (supported.shaderSampledImageArrayDynamicIndexing != VK_TRUE) {
    errorMessage =
        std::string(context->properties_.deviceName) + ": no dynamic indexing of sampler arrays";
    return nullptr;
  }
  VkPhysicalDeviceFeatures features{};
  features.shaderSampledImageArrayDynamicIndexing = VK_TRUE;
  std::vector<const char*> enabled = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
  context->sharedPresentableImage_ =
      surfaceCapabilities2 &&
      hasExtension(deviceExtensions, VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME);
  if (context->sharedPresentableImage_) {
    enabled.push_back(VK_KHR_SHARED_PRESENTABLE_IMAGE_EXTENSION_NAME);
  }
  context->displayTiming_ = hasExtension(deviceExtensions, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
  if (context->displayTiming_) {
    enabled.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
  }
  const float priority = 1.0F;
  auto queueInfo = make<VkDeviceQueueCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
  queueInfo.queueFamilyIndex = context->queueFamily_;
  queueInfo.queueCount = 1;
  queueInfo.pQueuePriorities = &priority;
  auto deviceInfo = make<VkDeviceCreateInfo>(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
  deviceInfo.queueCreateInfoCount = 1;
  deviceInfo.pQueueCreateInfos = &queueInfo;
  deviceInfo.pEnabledFeatures = &features;
  deviceInfo.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
  deviceInfo.ppEnabledExtensionNames = enabled.data();
  result = vkCreateDevice(physical, &deviceInfo, nullptr, &context->device_);
  if (result != VK_SUCCESS) {
    errorMessage = describeVkResult("vkCreateDevice", result);
    return nullptr;
  }
  vkGetDeviceQueue(context->device_, context->queueFamily_, 0, &context->queue_);
  return context;
}

VulkanContext::~VulkanContext() {
  if (device_ != VK_NULL_HANDLE) {
    vkDeviceWaitIdle(device_);
    vkDestroyDevice(device_, nullptr);
  }
  if (instance_ != VK_NULL_HANDLE) {
    vkDestroyInstance(instance_, nullptr);
  }
}

int32_t VulkanContext::memoryType(uint32_t typeBits, VkMemoryPropertyFlags wanted) const {
  for (uint32_t type = 0; type < memory_.memoryTypeCount; ++type) {
    if ((typeBits & (1U << type)) != 0 &&
        (memory_.memoryTypes[type].propertyFlags & wanted) == wanted) {
      return static_cast<int32_t>(type);
    }
  }
  return -1;
}

}  // namespace nanquim::gpu::vulkan
