#pragma once

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include <memory>
#include <string>

namespace nanquim::gpu::vulkan {

template <typename T>
T make(VkStructureType type) {
  T value{};
  value.sType = type;
  return value;
}

class VulkanContext {
 public:
  static std::unique_ptr<VulkanContext> create(std::string& errorMessage);
  VulkanContext(const VulkanContext&) = delete;
  VulkanContext& operator=(const VulkanContext&) = delete;
  ~VulkanContext();

  [[nodiscard]] VkInstance instance() const { return instance_; }
  [[nodiscard]] VkPhysicalDevice physicalDevice() const { return physical_; }
  [[nodiscard]] VkDevice device() const { return device_; }
  [[nodiscard]] VkQueue queue() const { return queue_; }
  [[nodiscard]] uint32_t queueFamily() const { return queueFamily_; }
  [[nodiscard]] const VkPhysicalDeviceProperties& properties() const { return properties_; }
  [[nodiscard]] const VkPhysicalDeviceMemoryProperties& memory() const { return memory_; }
  [[nodiscard]] uint32_t timestampValidBits() const { return timestampValidBits_; }
  [[nodiscard]] bool hasSwapchainColorSpace() const { return swapchainColorSpace_; }
  [[nodiscard]] bool hasSharedPresentableImage() const { return sharedPresentableImage_; }
  [[nodiscard]] bool hasDisplayTiming() const { return displayTiming_; }

  [[nodiscard]] int32_t memoryType(uint32_t typeBits, VkMemoryPropertyFlags wanted) const;
  [[nodiscard]] bool isHostCoherent(uint32_t memoryType) const {
    return (memory_.memoryTypes[memoryType].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) !=
           0;
  }

 private:
  VulkanContext() = default;

  VkInstance instance_ = VK_NULL_HANDLE;
  VkPhysicalDevice physical_ = VK_NULL_HANDLE;
  VkDevice device_ = VK_NULL_HANDLE;
  VkQueue queue_ = VK_NULL_HANDLE;
  uint32_t queueFamily_ = 0;
  VkPhysicalDeviceProperties properties_{};
  VkPhysicalDeviceMemoryProperties memory_{};
  uint32_t timestampValidBits_ = 0;
  bool swapchainColorSpace_ = false;
  bool sharedPresentableImage_ = false;
  bool displayTiming_ = false;
};

std::string describeVkResult(const char* what, VkResult result);

}  // namespace nanquim::gpu::vulkan
