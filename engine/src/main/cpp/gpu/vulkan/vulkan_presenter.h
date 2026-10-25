#pragma once

#include "gpu/rhi/rhi.h"
#include "gpu/vulkan/vulkan_device.h"

namespace nanquim::gpu::vulkan {

class VulkanPresenter final : public rhi::Presenter {
 public:
  explicit VulkanPresenter(VulkanDevice& device) : device_(device) {}

  bool attachWindow(ANativeWindow* window, std::string& errorMessage) override {
    return device_.attachWindow(window, displayP3_, errorMessage);
  }
  void detachWindow() override { device_.detachWindow(); }
  bool hasWindow() const override { return device_.hasWindow(); }
  rhi::RenderTarget windowTarget(int32_t widthPixels, int32_t heightPixels) const override {
    return rhi::RenderTarget{nullptr, widthPixels, heightPixels, false};
  }

  bool supportsDisplayP3() const override { return device_.supportsDisplayP3(); }
  void setDisplayP3(bool displayP3) override { displayP3_ = displayP3 && supportsDisplayP3(); }
  bool isDisplayP3() const override { return displayP3_; }

  bool supportsFrontBuffer() const override { return device_.supportsFrontBuffer(); }
  void requestFrontBuffer(bool frontBuffered) override {
    device_.requestFrontBuffer(frontBuffered);
  }
  bool isFrontBuffered() const override { return device_.isFrontBuffered(); }
  bool hasPendingSwitch() const override { return device_.hasPendingSwitch(); }
  void prepareFrame() override { device_.prepareFrame(); }

  bool present() override { return device_.present(); }

  std::optional<uint64_t> nextFrameId() const override { return device_.nextFrameId(); }
  PresentTime presentTime(uint64_t frameId, int64_t& presentNanos) const override {
    return device_.presentTime(frameId, presentNanos);
  }

 private:
  VulkanDevice& device_;
  bool displayP3_ = false;
};

}  // namespace nanquim::gpu::vulkan
