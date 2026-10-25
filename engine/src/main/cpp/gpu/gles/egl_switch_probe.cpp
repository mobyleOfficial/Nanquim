#include "gpu/gles/egl_switch_probe.h"

#include <GLES3/gl3.h>
#include <time.h>

#include <algorithm>
#include <chrono>
#include <nlohmann/json.hpp>
#include <optional>
#include <thread>
#include <vector>

#include "core/log.h"
#include "gpu/gles/egl_session.h"

namespace nanquim::gpu::gles {
namespace {

using Clock = std::chrono::steady_clock;
using nlohmann::json;

double millisecondsSince(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

int64_t monotonicNanos() {
  timespec now{};
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<int64_t>(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

json summary(std::vector<double> values) {
  if (values.empty()) {
    return json{{"count", 0}};
  }
  std::sort(values.begin(), values.end());
  const auto at = [&](double fraction) {
    return values[std::min(values.size() - 1, static_cast<size_t>(fraction * values.size()))];
  };
  return json{{"count", values.size()},
              {"min", values.front()},
              {"median", at(0.5)},
              {"p90", at(0.9)},
              {"max", values.back()}};
}

void clear(float red, float green, float blue) {
  glClearColor(red, green, blue, 1.0F);
  glClear(GL_COLOR_BUFFER_BIT);
}

double swapAndWaitForDisplay(EglSession& session, int64_t startNanos) {
  const std::optional<uint64_t> frameId = session.nextFrameId();
  session.swapBuffers();
  if (!frameId) return -1.0;
  const auto deadline = Clock::now() + std::chrono::milliseconds(200);
  while (Clock::now() < deadline) {
    int64_t presentNanos = 0;
    switch (session.presentTime(*frameId, presentNanos)) {
      case EglSession::PresentTime::Known:
        return static_cast<double>(presentNanos - startNanos) / 1e6;
      case EglSession::PresentTime::Unavailable:
        return -1.0;
      case EglSession::PresentTime::Pending:
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        break;
    }
  }
  return -1.0;
}

}  // namespace

std::string runSingleBufferSwitchProbe(ANativeWindow* window, int switchCycles) {
  json result;
  EglSession session;
  std::string error;
  if (!session.initialize(error) || !session.attachWindow(window, error)) {
    result["error"] = error;
    return result.dump();
  }
  result["singleBufferSupported"] = session.supportsSingleBuffer();
  const int32_t width = ANativeWindow_getWidth(window);
  const int32_t height = ANativeWindow_getHeight(window);
  glViewport(0, 0, width, height);

  std::vector<double> swap;
  for (int i = 0; i < 90; ++i) {
    clear(static_cast<float>(i % 30) / 30.0F, 0.2F, 0.4F);
    auto start = Clock::now();
    session.swapBuffers();
    swap.push_back(millisecondsSince(start));
  }
  result["doubleBuffered"] = {{"swapMs", summary(swap)}};

  if (session.supportsSingleBuffer()) {
    std::vector<double> enterSwap;
    std::vector<double> enterVisible;
    std::vector<double> frontGpu;
    std::vector<double> frontSwap;
    std::vector<double> leaveSwap;
    std::vector<double> leaveVisible;
    int notSingle = 0;
    for (int cycle = 0; cycle < switchCycles; ++cycle) {
      for (int i = 0; i < 20; ++i) {
        clear(0.1F, 0.1F, 0.1F + 0.02F * static_cast<float>(i));
        session.swapBuffers();
      }
      int64_t enterNanos = monotonicNanos();
      auto enterStart = Clock::now();
      session.requestSingleBuffer(true);
      clear(0.9F, 0.1F, 0.1F);
      const double visible = swapAndWaitForDisplay(session, enterNanos);
      enterSwap.push_back(millisecondsSince(enterStart));
      if (visible >= 0) enterVisible.push_back(visible);
      if (!session.isSingleBuffered()) ++notSingle;

      for (int i = 0; i < 30; ++i) {
        clear(0.9F, 0.1F + 0.02F * static_cast<float>(i), 0.1F);
        auto drawStart = Clock::now();
        glFinish();
        frontGpu.push_back(millisecondsSince(drawStart));
        auto swapStart = Clock::now();
        session.swapBuffers();
        frontSwap.push_back(millisecondsSince(swapStart));
        std::this_thread::sleep_for(std::chrono::milliseconds(4));
      }

      const int64_t leaveNanos = monotonicNanos();
      auto leaveStart = Clock::now();
      session.requestSingleBuffer(false);
      clear(0.1F, 0.6F, 0.1F);
      const std::optional<uint64_t> frameId = session.nextFrameId();
      session.swapBuffers();
      leaveSwap.push_back(millisecondsSince(leaveStart));
      for (int i = 0; i < 10; ++i) {
        clear(0.1F, 0.6F, 0.1F);
        session.swapBuffers();
      }
      if (frameId) {
        int64_t presentNanos = 0;
        if (session.presentTime(*frameId, presentNanos) == EglSession::PresentTime::Known) {
          leaveVisible.push_back(static_cast<double>(presentNanos - leaveNanos) / 1e6);
        }
      }
    }
    result["frontBufferSwitch"] = {{"enterSwapAndWaitMs", summary(enterSwap)},
                                   {"enterVisibleMs", summary(enterVisible)},
                                   {"frontGpuMs", summary(frontGpu)},
                                   {"frontSwapMs", summary(frontSwap)},
                                   {"leaveSwapMs", summary(leaveSwap)},
                                   {"leaveVisibleMs", summary(leaveVisible)},
                                   {"notSingleBuffered", notSingle}};

    session.requestSingleBuffer(true);
    clear(1.0F, 0.0F, 0.0F);
    swapAndWaitForDisplay(session, monotonicNanos());
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    clear(0.0F, 1.0F, 0.0F);
    glFinish();
    NANQUIM_LOG_INFO("VulkanProbe egl hold: green drawn without swap, holding 2 s");
    std::this_thread::sleep_for(std::chrono::milliseconds(2000));
    NANQUIM_LOG_INFO("VulkanProbe egl hold end");
    session.requestSingleBuffer(false);
    clear(0.1F, 0.1F, 0.1F);
    session.swapBuffers();
  }
  session.terminate();
  return result.dump();
}

}  // namespace nanquim::gpu::gles
