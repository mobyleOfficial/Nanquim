#include "adjust/liquify.h"

#include <algorithm>
#include <cmath>

#include "adjust/adjustment.h"

namespace nanquim::adjust {

namespace {

constexpr double kDabSpacing = 0.05;
constexpr double kMomentumMilliseconds = 500;
constexpr double kMomentumStep = 1000.0 / 120;
constexpr double kTwirlRadians = 0.04;
constexpr double kPinch = 0.027;
constexpr double kEdge = 0.04;
constexpr double kCrystals = 0.027;
constexpr double kReconstruct = 0.09;

std::array<double, 4> cubicWeights(double t) {
  const double t2 = t * t;
  const double t3 = t2 * t;
  return {0.5 * (-t3 + 2 * t2 - t), 0.5 * (3 * t3 - 5 * t2 + 2), 0.5 * (-3 * t3 + 4 * t2 + t),
          0.5 * (t3 - t2)};
}

std::array<double, 4> bicubic(const std::vector<double>& rgba, int32_t width, int32_t height,
                              double x, double y) {
  const double left = std::floor(x);
  const double top = std::floor(y);
  const auto weightsX = cubicWeights(x - left);
  const auto weightsY = cubicWeights(y - top);
  std::array<double, 4> result{};
  for (int32_t j = 0; j < 4; ++j) {
    const int32_t row = std::clamp(static_cast<int32_t>(top) - 1 + j, 0, height - 1);
    for (int32_t i = 0; i < 4; ++i) {
      const int32_t column = std::clamp(static_cast<int32_t>(left) - 1 + i, 0, width - 1);
      const double weight = weightsX[static_cast<size_t>(i)] * weightsY[static_cast<size_t>(j)];
      const size_t offset = (static_cast<size_t>(row) * width + column) * 4;
      for (size_t channel = 0; channel < 4; ++channel) {
        result[channel] += rgba[offset + channel] * weight;
      }
    }
  }
  result[3] = std::clamp(result[3], 0.0, 1.0);
  for (size_t channel = 0; channel < 3; ++channel) {
    result[channel] = std::clamp(result[channel], 0.0, result[3]);
  }
  return result;
}

double falloff(double distance, double radius) {
  if (distance >= radius) {
    return 0;
  }
  const double t = distance / radius;
  const double inside = 1 - t * t;
  return inside * inside;
}

}  // namespace

std::string_view liquifyModeName(LiquifyMode mode) {
  switch (mode) {
    case LiquifyMode::Push:
      return "push";
    case LiquifyMode::TwirlRight:
      return "twirlRight";
    case LiquifyMode::TwirlLeft:
      return "twirlLeft";
    case LiquifyMode::Pinch:
      return "pinch";
    case LiquifyMode::Expand:
      return "expand";
    case LiquifyMode::Crystals:
      return "crystals";
    case LiquifyMode::Edge:
      return "edge";
    case LiquifyMode::Reconstruct:
      return "reconstruct";
  }
  return "";
}

std::optional<LiquifyMode> liquifyModeFromName(std::string_view name) {
  for (int32_t value = 0; value <= static_cast<int32_t>(LiquifyMode::Reconstruct); ++value) {
    const auto mode = static_cast<LiquifyMode>(value);
    if (liquifyModeName(mode) == name) {
      return mode;
    }
  }
  return std::nullopt;
}

double liquifyRadius(double size, int32_t width, int32_t height) {
  return std::max(4.0, (4 + 396 * std::clamp(size, 0.0, 1.0)) * std::max(width, height) / 4096.0);
}

void FieldRegion::include(const FieldRegion& other) {
  if (other.isEmpty()) {
    return;
  }
  if (isEmpty()) {
    *this = other;
    return;
  }
  for (size_t axis = 0; axis < 2; ++axis) {
    first[axis] = std::min(first[axis], other.first[axis]);
    end[axis] = std::max(end[axis], other.end[axis]);
  }
}

LiquifyField::LiquifyField(int32_t canvasWidth, int32_t canvasHeight)
    : canvasWidth_(canvasWidth),
      canvasHeight_(canvasHeight),
      width_((canvasWidth - 1) / kLiquifyFieldSpacing + 2),
      height_((canvasHeight - 1) / kLiquifyFieldSpacing + 2),
      values_(static_cast<size_t>(width_) * static_cast<size_t>(height_) * 2, 0.0F) {}

std::array<double, 2> LiquifyField::at(int32_t column, int32_t row) const {
  const size_t index = (static_cast<size_t>(std::clamp(row, 0, height_ - 1)) * width_ +
                        static_cast<size_t>(std::clamp(column, 0, width_ - 1))) *
                       2;
  return {values_[index], values_[index + 1]};
}

std::array<double, 2> LiquifyField::displacement(double x, double y) const {
  const double u = x / kLiquifyFieldSpacing;
  const double v = y / kLiquifyFieldSpacing;
  const double left = std::floor(u);
  const double top = std::floor(v);
  const double fx = u - left;
  const double fy = v - top;
  const auto column = static_cast<int32_t>(left);
  const auto row = static_cast<int32_t>(top);
  const auto a = at(column, row);
  const auto b = at(column + 1, row);
  const auto c = at(column, row + 1);
  const auto d = at(column + 1, row + 1);
  std::array<double, 2> result{};
  for (size_t axis = 0; axis < 2; ++axis) {
    const double upper = a[axis] + (b[axis] - a[axis]) * fx;
    const double lower = c[axis] + (d[axis] - c[axis]) * fx;
    result[axis] = upper + (lower - upper) * fy;
  }
  return result;
}

FieldRegion LiquifyField::takeChanged() {
  const FieldRegion region = changed_;
  changed_ = {};
  return region;
}

void LiquifyField::touched(int32_t column, int32_t row) {
  const FieldRegion point{{column, row}, {column + 1, row + 1}};
  changed_.include(point);
  bounds_.include(point);
}

void LiquifyField::set(int32_t column, int32_t row, std::array<double, 2> value) {
  if (column < 0 || row < 0 || column >= width_ || row >= height_) {
    return;
  }
  const double x = static_cast<double>(column) * kLiquifyFieldSpacing;
  const double y = static_cast<double>(row) * kLiquifyFieldSpacing;
  value[0] = std::clamp(x + value[0], 0.0, canvasWidth_ - 1.0) - x;
  value[1] = std::clamp(y + value[1], 0.0, canvasHeight_ - 1.0) - y;
  const size_t point = static_cast<size_t>(row) * width_ + static_cast<size_t>(column);
  if (recording_ != nullptr && recorded_[point] == 0) {
    recorded_[point] = 1;
    recording_->indices.push_back(static_cast<int32_t>(point));
    recording_->before.push_back(values_[point * 2]);
    recording_->before.push_back(values_[point * 2 + 1]);
  }
  values_[point * 2] = static_cast<float>(value[0]);
  values_[point * 2 + 1] = static_cast<float>(value[1]);
  touched(column, row);
}

void LiquifyField::beginRecording(Change* change) {
  recording_ = change;
  recorded_.assign(static_cast<size_t>(width_) * static_cast<size_t>(height_), 0);
}

void LiquifyField::endRecording() {
  if (recording_ != nullptr) {
    recording_->after.clear();
    for (const int32_t point : recording_->indices) {
      recording_->after.push_back(values_[static_cast<size_t>(point) * 2]);
      recording_->after.push_back(values_[static_cast<size_t>(point) * 2 + 1]);
    }
  }
  recording_ = nullptr;
  recorded_.clear();
  recorded_.shrink_to_fit();
}

void LiquifyField::clear() { scale(0); }

void LiquifyField::scale(double factor) {
  for (int32_t row = 0; row < height_; ++row) {
    for (int32_t column = 0; column < width_; ++column) {
      const auto value = at(column, row);
      if (value[0] != 0 || value[1] != 0) {
        set(column, row, {value[0] * factor, value[1] * factor});
      }
    }
  }
}

LiquifyField::Change LiquifyField::snapshotAll() const {
  Change change;
  for (int32_t point = 0; point < width_ * height_; ++point) {
    change.indices.push_back(point);
    change.before.push_back(values_[static_cast<size_t>(point) * 2]);
    change.before.push_back(values_[static_cast<size_t>(point) * 2 + 1]);
  }
  change.after = change.before;
  return change;
}

void LiquifyField::apply(const Change& change, bool undo) {
  const std::vector<float>& values = undo ? change.before : change.after;
  for (size_t entry = 0; entry < change.indices.size(); ++entry) {
    const auto point = static_cast<size_t>(change.indices[entry]);
    values_[point * 2] = values[entry * 2];
    values_[point * 2 + 1] = values[entry * 2 + 1];
    touched(static_cast<int32_t>(point % static_cast<size_t>(width_)),
            static_cast<int32_t>(point / static_cast<size_t>(width_)));
  }
}

LiquifyStroke::LiquifyStroke(LiquifyField& field, LiquifyBrush brush)
    : field_(field),
      brush_(brush),
      radius_(liquifyRadius(brush.size, field.canvasWidth(), field.canvasHeight())) {
  field_.beginRecording(&change_);
}

LiquifyStroke::~LiquifyStroke() {
  if (!finished_) {
    field_.endRecording();
  }
}

void LiquifyStroke::add(const LiquifySample& sample) {
  if (ended_) {
    return;
  }
  if (!hasPrevious_) {
    hasPrevious_ = true;
    previous_ = sample;
    lastDab_ = {sample.x, sample.y};
    lastDabTime_ = sample.time;
    dab(lastDab_, sample.pressure, {0, 0});
    return;
  }
  const double spacing = std::max(radius_ * kDabSpacing, 1.0);
  const double dx = sample.x - previous_.x;
  const double dy = sample.y - previous_.y;
  const double length = std::hypot(dx, dy);
  double walked = spacing - carried_;
  while (walked <= length) {
    const double t = walked / length;
    const std::array<double, 2> center{previous_.x + dx * t, previous_.y + dy * t};
    dab(center, previous_.pressure + (sample.pressure - previous_.pressure) * t,
        {center[0] - lastDab_[0], center[1] - lastDab_[1]});
    lastDab_ = center;
    lastDabTime_ = previous_.time + (sample.time - previous_.time) * t;
    walked += spacing;
  }
  if (brush_.mode != LiquifyMode::Push) {
    while (sample.time - lastDabTime_ >= kMomentumStep) {
      lastDabTime_ += kMomentumStep;
      dab(lastDab_, sample.pressure, {0, 0});
    }
  }
  carried_ = length - (walked - spacing);
  if (sample.time > previous_.time) {
    velocity_ = {dx / (sample.time - previous_.time), dy / (sample.time - previous_.time)};
  }
  previous_ = sample;
}

void LiquifyStroke::end() {
  if (ended_) {
    return;
  }
  ended_ = true;
  momentumDuration_ = kMomentumMilliseconds * std::clamp(brush_.momentum, 0.0, 1.0);
  momentumPosition_ = {previous_.x, previous_.y};
  if (!hasPrevious_) {
    momentumDuration_ = 0;
  }
}

bool LiquifyStroke::advanceMomentum(double elapsed) {
  if (!ended_) {
    return true;
  }
  const double spacing = std::max(radius_ * kDabSpacing, 1.0);
  while (momentumDone_ + kMomentumStep <= std::min(elapsed, momentumDuration_)) {
    momentumDone_ += kMomentumStep;
    const double decay = 1 - momentumDone_ / momentumDuration_;
    const std::array<double, 2> next{momentumPosition_[0] + velocity_[0] * kMomentumStep * decay,
                                     momentumPosition_[1] + velocity_[1] * kMomentumStep * decay};
    momentumPosition_ = next;
    if (std::hypot(next[0] - lastDab_[0], next[1] - lastDab_[1]) >= spacing) {
      dab(next, previous_.pressure * decay, {next[0] - lastDab_[0], next[1] - lastDab_[1]});
      lastDab_ = next;
    }
  }
  return momentumDone_ + kMomentumStep <= momentumDuration_;
}

LiquifyField::Change LiquifyStroke::finish() {
  if (!finished_) {
    finished_ = true;
    field_.endRecording();
  }
  return change_;
}

void LiquifyStroke::dab(const std::array<double, 2>& center, double pressure,
                        const std::array<double, 2>& delta) {
  const double strength = std::clamp(brush_.pressure, 0.0, 1.0) *
                          (brush_.usePressure ? std::clamp(pressure, 0.0, 1.0) : 1);
  if (strength <= 0) {
    return;
  }
  const double radius = radius_;
  const int32_t spacing = kLiquifyFieldSpacing;
  const int32_t firstColumn =
      std::max(static_cast<int32_t>(std::floor((center[0] - radius) / spacing)), 0);
  const int32_t firstRow =
      std::max(static_cast<int32_t>(std::floor((center[1] - radius) / spacing)), 0);
  const int32_t endColumn =
      std::min(static_cast<int32_t>(std::ceil((center[0] + radius) / spacing)) + 1, field_.width());
  const int32_t endRow = std::min(
      static_cast<int32_t>(std::ceil((center[1] + radius) / spacing)) + 1, field_.height());
  if (endColumn <= firstColumn || endRow <= firstRow) {
    return;
  }
  const double reach = radius * 0.25 + std::hypot(delta[0], delta[1]) + 2 * spacing;
  const int32_t margin = static_cast<int32_t>(std::ceil(reach / spacing));
  const int32_t copyColumn = std::max(firstColumn - margin, 0);
  const int32_t copyRow = std::max(firstRow - margin, 0);
  const int32_t copyWidth = std::min(endColumn + margin, field_.width()) - copyColumn;
  const int32_t copyHeight = std::min(endRow + margin, field_.height()) - copyRow;
  std::vector<std::array<double, 2>> old(static_cast<size_t>(copyWidth) * copyHeight);
  for (int32_t row = 0; row < copyHeight; ++row) {
    for (int32_t column = 0; column < copyWidth; ++column) {
      old[static_cast<size_t>(row) * copyWidth + column] =
          field_.at(copyColumn + column, copyRow + row);
    }
  }
  auto oldAt = [&](int32_t column, int32_t row) {
    const int32_t localColumn = column - copyColumn;
    const int32_t localRow = row - copyRow;
    if (localColumn >= 0 && localRow >= 0 && localColumn < copyWidth && localRow < copyHeight) {
      return old[static_cast<size_t>(localRow) * copyWidth + localColumn];
    }
    return field_.at(column, row);
  };
  auto oldDisplacement = [&](double x, double y) {
    const double u = x / spacing;
    const double v = y / spacing;
    const double left = std::floor(u);
    const double top = std::floor(v);
    const double fx = u - left;
    const double fy = v - top;
    const auto column = static_cast<int32_t>(left);
    const auto row = static_cast<int32_t>(top);
    const auto a = oldAt(column, row);
    const auto b = oldAt(column + 1, row);
    const auto c = oldAt(column, row + 1);
    const auto d = oldAt(column + 1, row + 1);
    std::array<double, 2> result{};
    for (size_t axis = 0; axis < 2; ++axis) {
      const double upper = a[axis] + (b[axis] - a[axis]) * fx;
      const double lower = c[axis] + (d[axis] - c[axis]) * fx;
      result[axis] = upper + (lower - upper) * fy;
    }
    return result;
  };
  for (int32_t row = firstRow; row < endRow; ++row) {
    for (int32_t column = firstColumn; column < endColumn; ++column) {
      const std::array<double, 2> point{static_cast<double>(column) * spacing,
                                        static_cast<double>(row) * spacing};
      const double offsetX = point[0] - center[0];
      const double offsetY = point[1] - center[1];
      const double distance = std::hypot(offsetX, offsetY);
      const double weight = falloff(distance, radius) * strength;
      if (weight <= 0) {
        continue;
      }
      std::array<double, 2> from = point;
      switch (brush_.mode) {
        case LiquifyMode::Push:
          from = {point[0] - delta[0] * weight, point[1] - delta[1] * weight};
          break;
        case LiquifyMode::TwirlRight:
        case LiquifyMode::TwirlLeft: {
          const double angle =
              (brush_.mode == LiquifyMode::TwirlRight ? -1 : 1) * kTwirlRadians * weight;
          const double cosine = std::cos(angle);
          const double sine = std::sin(angle);
          from = {center[0] + cosine * offsetX - sine * offsetY,
                  center[1] + sine * offsetX + cosine * offsetY};
          break;
        }
        case LiquifyMode::Pinch:
          from = {center[0] + offsetX * (1 + kPinch * weight),
                  center[1] + offsetY * (1 + kPinch * weight)};
          break;
        case LiquifyMode::Expand:
          from = {center[0] + offsetX * (1 - kPinch * weight),
                  center[1] + offsetY * (1 - kPinch * weight)};
          break;
        case LiquifyMode::Edge: {
          if (distance > 0) {
            const double ring = radius * 0.6;
            const double scale =
                (distance + (distance - ring) * kEdge * weight * (0.5 + brush_.distortion)) /
                distance;
            from = {center[0] + offsetX * scale, center[1] + offsetY * scale};
          }
          break;
        }
        case LiquifyMode::Crystals: {
          const auto value = oldAt(column, row);
          const double jitter =
              kCrystals * radius * std::clamp(brush_.distortion, 0.0, 1.0) * weight;
          field_.set(column, row,
                     {value[0] + (hashUnit(column, row, brush_.seed * 2) - 0.5) * 2 * jitter,
                      value[1] + (hashUnit(column, row, brush_.seed * 2 + 1) - 0.5) * 2 * jitter});
          continue;
        }
        case LiquifyMode::Reconstruct: {
          const auto value = oldAt(column, row);
          const double keep = 1 - kReconstruct * weight;
          field_.set(column, row, {value[0] * keep, value[1] * keep});
          continue;
        }
      }
      const auto moved = oldDisplacement(from[0], from[1]);
      field_.set(column, row, {from[0] + moved[0] - point[0], from[1] + moved[1] - point[1]});
    }
  }
}

std::vector<double> liquifyImage(const LiquifyField& field, const std::vector<double>& rgba,
                                 int32_t width, int32_t height) {
  std::vector<double> result(rgba.size());
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      const auto offset = field.displacement(x, y);
      const std::array<double, 4> value =
          bicubic(rgba, width, height, x + offset[0], y + offset[1]);
      std::copy(value.begin(), value.end(),
                result.begin() + static_cast<ptrdiff_t>((static_cast<size_t>(y) * width + x) * 4));
    }
  }
  return result;
}

}  // namespace nanquim::adjust
