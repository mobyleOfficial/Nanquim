#pragma once

#include "gpu/gles/egl_session.h"
#include "gpu/rhi/rhi.h"

namespace nanquim::gpu::gles {

class GlesPresenter final : public rhi::Presenter {
 public:
  bool initialize(std::string& errorMessage) { return session_.initialize(errorMessage); }
  [[nodiscard]] EGLDisplay display() const { return session_.display(); }
  void terminate() { session_.terminate(); }

  bool attachWindow(ANativeWindow* window, std::string& errorMessage) override {
    return session_.attachWindow(window, errorMessage);
  }
  void detachWindow() override { session_.detachWindow(); }
  bool hasWindow() const override { return session_.hasWindow(); }
  rhi::RenderTarget windowTarget(int32_t widthPixels, int32_t heightPixels) const override {
    return rhi::RenderTarget{nullptr, widthPixels, heightPixels, true};
  }

  bool supportsDisplayP3() const override { return session_.supportsDisplayP3(); }
  void setDisplayP3(bool displayP3) override { session_.setDisplayP3(displayP3); }
  bool isDisplayP3() const override { return session_.isDisplayP3(); }

  bool supportsFrontBuffer() const override { return session_.supportsSingleBuffer(); }
  void requestFrontBuffer(bool frontBuffered) override {
    session_.requestSingleBuffer(frontBuffered);
  }
  bool isFrontBuffered() const override { return session_.isSingleBuffered(); }

  bool present() override { return session_.swapBuffers(); }

  std::optional<uint64_t> nextFrameId() const override { return session_.nextFrameId(); }
  PresentTime presentTime(uint64_t frameId, int64_t& presentNanos) const override {
    switch (session_.presentTime(frameId, presentNanos)) {
      case EglSession::PresentTime::Known:
        return PresentTime::Known;
      case EglSession::PresentTime::Pending:
        return PresentTime::Pending;
      case EglSession::PresentTime::Unavailable:
        break;
    }
    return PresentTime::Unavailable;
  }

 private:
  EglSession session_;
};

}  // namespace nanquim::gpu::gles
