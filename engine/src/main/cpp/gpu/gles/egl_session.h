#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window.h>

#include <cstdint>
#include <optional>
#include <string>

namespace nanquim::gpu::gles {

class EglSession {
 public:
  EglSession() = default;
  EglSession(const EglSession&) = delete;
  EglSession& operator=(const EglSession&) = delete;
  ~EglSession();

  bool initialize(std::string& errorMessage);

  bool attachWindow(ANativeWindow* window, std::string& errorMessage);

  void detachWindow();

  [[nodiscard]] bool hasWindow() const { return windowSurface_ != EGL_NO_SURFACE; }

  [[nodiscard]] bool supportsDisplayP3() const;
  void setDisplayP3(bool displayP3) { displayP3_ = displayP3 && supportsDisplayP3(); }
  [[nodiscard]] bool isDisplayP3() const { return displayP3_; }

  [[nodiscard]] bool supportsSingleBuffer() const { return singleBufferSupported_; }
  void requestSingleBuffer(bool singleBuffer);
  [[nodiscard]] bool isSingleBuffered() const;

  bool swapBuffers();

  [[nodiscard]] std::optional<uint64_t> nextFrameId() const;

  enum class PresentTime { Known, Pending, Unavailable };
  PresentTime presentTime(uint64_t frameId, int64_t& presentNanos) const;

  [[nodiscard]] EGLDisplay display() const { return display_; }

  void terminate();

 private:
  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLConfig config_ = nullptr;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLSurface pbufferSurface_ = EGL_NO_SURFACE;
  EGLSurface windowSurface_ = EGL_NO_SURFACE;
  PFNEGLGETNEXTFRAMEIDANDROIDPROC getNextFrameId_ = nullptr;
  PFNEGLGETFRAMETIMESTAMPSANDROIDPROC getFrameTimestamps_ = nullptr;
  bool frameTimestampsEnabled_ = false;
  bool mutableConfig_ = false;
  bool singleBufferSupported_ = false;
  bool displayP3_ = false;
};

}  // namespace nanquim::gpu::gles
