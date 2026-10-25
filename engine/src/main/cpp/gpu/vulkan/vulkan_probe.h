#pragma once

#include <android/native_window.h>

#include <string>

namespace nanquim::gpu::vulkan {

std::string runProbe(ANativeWindow* window, int switchCycles);

}  // namespace nanquim::gpu::vulkan
