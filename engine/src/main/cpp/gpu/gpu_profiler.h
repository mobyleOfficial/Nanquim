#pragma once

#include <array>
#include <cstdint>
#include <deque>
#include <memory>
#include <vector>

#include "gpu/rhi/rhi.h"

namespace nanquim::gpu {

class GpuProfiler {
 public:
  enum class Section : uint8_t { StrokeDabs, Provisional, Compose, Screen, Count };

  GpuProfiler() = default;
  GpuProfiler(const GpuProfiler&) = delete;
  GpuProfiler& operator=(const GpuProfiler&) = delete;

  void setDevice(rhi::Device* device);

  void begin(Section section);
  void end();
  void endFrame();

 private:
  struct Pending {
    std::unique_ptr<rhi::TimerQuery> query;
    Section section = Section::StrokeDabs;
  };

  void refreshEnabled();

  rhi::Device* device_ = nullptr;
  bool enabled_ = false;
  bool active_ = false;
  int64_t frame_ = 0;
  int32_t framesCounted_ = 0;
  std::vector<std::unique_ptr<rhi::TimerQuery>> idle_;
  std::deque<Pending> pending_;
  std::array<double, static_cast<size_t>(Section::Count)> totalMilliseconds_{};
  std::array<int32_t, static_cast<size_t>(Section::Count)> counts_{};
  bool disjoint_ = false;
};

class GpuProfileScope {
 public:
  GpuProfileScope(GpuProfiler& profiler, GpuProfiler::Section section) : profiler_(profiler) {
    profiler_.begin(section);
  }
  GpuProfileScope(const GpuProfileScope&) = delete;
  GpuProfileScope& operator=(const GpuProfileScope&) = delete;
  ~GpuProfileScope() { profiler_.end(); }

 private:
  GpuProfiler& profiler_;
};

}  // namespace nanquim::gpu
