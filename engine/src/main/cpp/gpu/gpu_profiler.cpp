#include "gpu/gpu_profiler.h"

#include "core/log.h"

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace nanquim::gpu {
namespace {

constexpr int32_t kReportFrames = 120;
constexpr int64_t kPropertyCheckFrames = 60;
constexpr std::array<const char*, 4> kSectionNames = {"strokeDabs", "provisional", "compose",
                                                      "screen"};

}  // namespace

void GpuProfiler::setDevice(rhi::Device* device) {
  pending_.clear();
  idle_.clear();
  active_ = false;
  enabled_ = false;
  device_ = device;
}

void GpuProfiler::refreshEnabled() {
  bool wanted = false;
#if defined(__ANDROID__)
  char value[PROP_VALUE_MAX] = {};
  wanted = __system_property_get("debug.nanquim.gpuprofile", value) > 0 && value[0] == '1';
#endif
  const bool supported = device_ != nullptr && device_->supportsTimerQueries();
  if (wanted && !supported && !enabled_) {
    NANQUIM_LOG_INFO("GPU profile: timer queries are not supported");
  }
  enabled_ = wanted && supported;
}

void GpuProfiler::begin(Section section) {
  if (!enabled_ || active_) {
    return;
  }
  std::unique_ptr<rhi::TimerQuery> query;
  if (idle_.empty()) {
    query = device_->createTimerQuery();
  } else {
    query = std::move(idle_.back());
    idle_.pop_back();
  }
  device_->commands().beginTimer(*query);
  pending_.push_back(Pending{std::move(query), section});
  active_ = true;
}

void GpuProfiler::end() {
  if (!active_) {
    return;
  }
  device_->commands().endTimer(*pending_.back().query);
  active_ = false;
}

void GpuProfiler::endFrame() {
  if (frame_++ % kPropertyCheckFrames == 0) {
    refreshEnabled();
  }
  if ((pending_.empty() && !enabled_) || device_ == nullptr) {
    return;
  }
  disjoint_ = device_->timerResultsDisjoint() || disjoint_;
  while (!pending_.empty() && !(active_ && pending_.size() == 1)) {
    Pending& oldest = pending_.front();
    const std::optional<uint64_t> nanoseconds = oldest.query->elapsedNanoseconds();
    if (!nanoseconds) {
      break;
    }
    const auto index = static_cast<size_t>(oldest.section);
    totalMilliseconds_[index] += static_cast<double>(*nanoseconds) / 1e6;
    ++counts_[index];
    idle_.push_back(std::move(oldest.query));
    pending_.pop_front();
  }
  if (!enabled_) {
    return;
  }
  if (++framesCounted_ < kReportFrames) {
    return;
  }
  double total = 0;
  for (const double milliseconds : totalMilliseconds_) {
    total += milliseconds;
  }
  NANQUIM_LOG_INFO(
      "GPU profile over %d frames (ms per frame): %s %.2f (%d), %s %.2f (%d), %s %.2f (%d), "
      "%s %.2f (%d), total %.2f%s",
      framesCounted_, kSectionNames[0], totalMilliseconds_[0] / framesCounted_, counts_[0],
      kSectionNames[1], totalMilliseconds_[1] / framesCounted_, counts_[1], kSectionNames[2],
      totalMilliseconds_[2] / framesCounted_, counts_[2], kSectionNames[3],
      totalMilliseconds_[3] / framesCounted_, counts_[3], total / framesCounted_,
      disjoint_ ? " (disjoint: unreliable)" : "");
  framesCounted_ = 0;
  totalMilliseconds_ = {};
  counts_ = {};
  disjoint_ = false;
}

}  // namespace nanquim::gpu
