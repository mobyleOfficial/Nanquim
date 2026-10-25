#include "gpu/gles/egl_session.h"

#include <EGL/eglext.h>

#include <cstdio>

namespace nanquim::gpu::gles {

namespace {

bool hasExtension(EGLDisplay display, const char* name) {
  const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
  return extensions != nullptr && std::string(extensions).find(name) != std::string::npos;
}

std::string describeEglError(const char* operation) {
  char message[96];
  std::snprintf(message, sizeof(message), "%s failed (EGL error 0x%04x)", operation, eglGetError());
  return message;
}

}  // namespace

EglSession::~EglSession() { terminate(); }

bool EglSession::initialize(std::string& errorMessage) {
  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display_ == EGL_NO_DISPLAY) {
    errorMessage = describeEglError("eglGetDisplay");
    return false;
  }
  if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
    errorMessage = describeEglError("eglInitialize");
    return false;
  }

  mutableConfig_ = false;
  if (hasExtension(display_, "EGL_KHR_mutable_render_buffer")) {
    // clang-format off
    const EGLint mutableAttributes[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT | EGL_MUTABLE_RENDER_BUFFER_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
        EGL_NONE,
    };
    // clang-format on
    EGLint mutableCount = 0;
    mutableConfig_ =
        eglChooseConfig(display_, mutableAttributes, &config_, 1, &mutableCount) == EGL_TRUE &&
        mutableCount > 0;
  }

  // clang-format off
  const EGLint configAttributes[] = {
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
      EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
      EGL_RED_SIZE, 8,
      EGL_GREEN_SIZE, 8,
      EGL_BLUE_SIZE, 8,
      EGL_ALPHA_SIZE, 8,
      EGL_DEPTH_SIZE, 0,
      EGL_STENCIL_SIZE, 0,
      EGL_NONE,
  };
  // clang-format on
  EGLint configCount = 0;
  if (!mutableConfig_ &&
      (eglChooseConfig(display_, configAttributes, &config_, 1, &configCount) != EGL_TRUE ||
       configCount < 1)) {
    errorMessage = describeEglError("eglChooseConfig (RGBA8888, OpenGL ES 3)");
    return false;
  }

  const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
  if (context_ == EGL_NO_CONTEXT) {
    errorMessage = describeEglError("eglCreateContext (OpenGL ES 3)");
    return false;
  }

  const EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  pbufferSurface_ = eglCreatePbufferSurface(display_, config_, pbufferAttributes);
  if (pbufferSurface_ == EGL_NO_SURFACE) {
    errorMessage = describeEglError("eglCreatePbufferSurface");
    return false;
  }
  if (eglMakeCurrent(display_, pbufferSurface_, pbufferSurface_, context_) != EGL_TRUE) {
    errorMessage = describeEglError("eglMakeCurrent (pbuffer)");
    return false;
  }
  return true;
}

bool EglSession::attachWindow(ANativeWindow* window, std::string& errorMessage) {
  detachWindow();

  EGLint nativeVisualFormat = 0;
  if (eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &nativeVisualFormat) ==
      EGL_TRUE) {
    ANativeWindow_setBuffersGeometry(window, 0, 0, nativeVisualFormat);
  }

  const EGLint displayP3Attributes[] = {EGL_GL_COLORSPACE_KHR,
                                        EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT, EGL_NONE};
  windowSurface_ =
      eglCreateWindowSurface(display_, config_, window, displayP3_ ? displayP3Attributes : nullptr);
  if (windowSurface_ == EGL_NO_SURFACE) {
    errorMessage = describeEglError("eglCreateWindowSurface");
    return false;
  }
  if (eglMakeCurrent(display_, windowSurface_, windowSurface_, context_) != EGL_TRUE) {
    errorMessage = describeEglError("eglMakeCurrent (window)");
    eglDestroySurface(display_, windowSurface_);
    windowSurface_ = EGL_NO_SURFACE;
    return false;
  }
  eglSwapInterval(display_, 1);
  singleBufferSupported_ =
      mutableConfig_ && hasExtension(display_, "EGL_ANDROID_front_buffer_auto_refresh") &&
      eglSurfaceAttrib(display_, windowSurface_, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL_TRUE) ==
          EGL_TRUE;
  frameTimestampsEnabled_ = false;
  const char* extensions = eglQueryString(display_, EGL_EXTENSIONS);
  if (extensions != nullptr &&
      std::string(extensions).find("EGL_ANDROID_get_frame_timestamps") != std::string::npos) {
    getNextFrameId_ = reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC>(
        eglGetProcAddress("eglGetNextFrameIdANDROID"));
    getFrameTimestamps_ = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC>(
        eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    frameTimestampsEnabled_ =
        getNextFrameId_ != nullptr && getFrameTimestamps_ != nullptr &&
        eglSurfaceAttrib(display_, windowSurface_, EGL_TIMESTAMPS_ANDROID, EGL_TRUE) == EGL_TRUE;
  }
  return true;
}

bool EglSession::supportsDisplayP3() const {
  return display_ != EGL_NO_DISPLAY && hasExtension(display_, "EGL_KHR_gl_colorspace") &&
         hasExtension(display_, "EGL_EXT_gl_colorspace_display_p3_passthrough");
}

void EglSession::requestSingleBuffer(bool singleBuffer) {
  if (!singleBufferSupported_ || windowSurface_ == EGL_NO_SURFACE) {
    return;
  }
  eglSurfaceAttrib(display_, windowSurface_, EGL_RENDER_BUFFER,
                   singleBuffer ? EGL_SINGLE_BUFFER : EGL_BACK_BUFFER);
}

bool EglSession::isSingleBuffered() const {
  EGLint renderBuffer = EGL_BACK_BUFFER;
  return windowSurface_ != EGL_NO_SURFACE &&
         eglQuerySurface(display_, windowSurface_, EGL_RENDER_BUFFER, &renderBuffer) == EGL_TRUE &&
         renderBuffer == EGL_SINGLE_BUFFER;
}

std::optional<uint64_t> EglSession::nextFrameId() const {
  EGLuint64KHR frameId = 0;
  if (!frameTimestampsEnabled_ || windowSurface_ == EGL_NO_SURFACE ||
      getNextFrameId_(display_, windowSurface_, &frameId) != EGL_TRUE) {
    return std::nullopt;
  }
  return frameId;
}

EglSession::PresentTime EglSession::presentTime(uint64_t frameId, int64_t& presentNanos) const {
  if (!frameTimestampsEnabled_ || windowSurface_ == EGL_NO_SURFACE) {
    return PresentTime::Unavailable;
  }
  const EGLint timestamps[] = {EGL_DISPLAY_PRESENT_TIME_ANDROID};
  EGLnsecsANDROID value = 0;
  if (getFrameTimestamps_(display_, windowSurface_, frameId, 1, timestamps, &value) != EGL_TRUE ||
      value == EGL_TIMESTAMP_INVALID_ANDROID) {
    return PresentTime::Unavailable;
  }
  if (value == EGL_TIMESTAMP_PENDING_ANDROID) {
    return PresentTime::Pending;
  }
  presentNanos = value;
  return PresentTime::Known;
}

void EglSession::detachWindow() {
  if (windowSurface_ == EGL_NO_SURFACE) {
    return;
  }
  eglMakeCurrent(display_, pbufferSurface_, pbufferSurface_, context_);
  eglDestroySurface(display_, windowSurface_);
  windowSurface_ = EGL_NO_SURFACE;
}

bool EglSession::swapBuffers() {
  return windowSurface_ != EGL_NO_SURFACE && eglSwapBuffers(display_, windowSurface_) == EGL_TRUE;
}

void EglSession::terminate() {
  if (display_ == EGL_NO_DISPLAY) {
    return;
  }
  eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (windowSurface_ != EGL_NO_SURFACE) {
    eglDestroySurface(display_, windowSurface_);
    windowSurface_ = EGL_NO_SURFACE;
  }
  if (pbufferSurface_ != EGL_NO_SURFACE) {
    eglDestroySurface(display_, pbufferSurface_);
    pbufferSurface_ = EGL_NO_SURFACE;
  }
  if (context_ != EGL_NO_CONTEXT) {
    eglDestroyContext(display_, context_);
    context_ = EGL_NO_CONTEXT;
  }
  eglTerminate(display_);
  display_ = EGL_NO_DISPLAY;
}

}  // namespace nanquim::gpu::gles
