#pragma once

#include <android/native_window.h>

#include <string>

namespace nanquim::gpu::gles {

std::string runSingleBufferSwitchProbe(ANativeWindow* window, int switchCycles);

}  // namespace nanquim::gpu::gles
