#include <android/native_window_jni.h>
#include <jni.h>

#include <string>

#include "gpu/vulkan/vulkan_probe.h"

extern "C" {

JNIEXPORT jstring JNICALL Java_com_mobyle_nanquim_engine_VulkanProbe_nativeRun(JNIEnv* environment, jclass,
                                                                        jobject surface,
                                                                        jint switchCycles) {
  ANativeWindow* window = ANativeWindow_fromSurface(environment, surface);
  if (window == nullptr) {
    return environment->NewStringUTF(R"({"error":"no native window"})");
  }
  const std::string report = nanquim::gpu::vulkan::runProbe(window, switchCycles);
  ANativeWindow_release(window);
  return environment->NewStringUTF(report.c_str());
}

}
