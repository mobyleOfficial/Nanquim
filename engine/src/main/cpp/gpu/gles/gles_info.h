#pragma once

#include <EGL/egl.h>

#include <string>

namespace nanquim::gpu::gles {

struct GlesVersion {
  int major = 0;
  int minor = 0;
};

GlesVersion queryGlesVersion();

std::string queryGlesInfoJson(EGLDisplay display);

}  // namespace nanquim::gpu::gles
