#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace nanquim::adjust {

inline constexpr int32_t kLiquifyFieldSpacing = 4;

enum class LiquifyMode : int32_t {
  Push = 0,
  TwirlRight = 1,
  TwirlLeft = 2,
  Pinch = 3,
  Expand = 4,
  Crystals = 5,
  Edge = 6,
  Reconstruct = 7,
};

std::string_view liquifyModeName(LiquifyMode mode);
std::optional<LiquifyMode> liquifyModeFromName(std::string_view name);

struct LiquifyBrush {
  LiquifyMode mode = LiquifyMode::Push;
  double size = 0.3;
  double pressure = 1;
  double distortion = 0.5;
  double momentum = 0;
  bool usePressure = true;
  uint32_t seed = 0;
};

double liquifyRadius(double size, int32_t width, int32_t height);

struct LiquifySample {
  double x = 0;
  double y = 0;
  double pressure = 1;
  double time = 0;
};

struct FieldRegion {
  std::array<int32_t, 2> first{0, 0};
  std::array<int32_t, 2> end{0, 0};
  [[nodiscard]] bool isEmpty() const { return end[0] <= first[0] || end[1] <= first[1]; }
  void include(const FieldRegion& other);
};

class LiquifyField {
 public:
  LiquifyField(int32_t canvasWidth, int32_t canvasHeight);

  [[nodiscard]] int32_t width() const { return width_; }
  [[nodiscard]] int32_t height() const { return height_; }
  [[nodiscard]] int32_t canvasWidth() const { return canvasWidth_; }
  [[nodiscard]] int32_t canvasHeight() const { return canvasHeight_; }
  [[nodiscard]] const std::vector<float>& data() const { return values_; }
  [[nodiscard]] std::array<double, 2> at(int32_t column, int32_t row) const;
  [[nodiscard]] std::array<double, 2> displacement(double x, double y) const;

  FieldRegion takeChanged();
  [[nodiscard]] const FieldRegion& bounds() const { return bounds_; }

  void clear();
  void scale(double factor);

  struct Change {
    std::vector<int32_t> indices;
    std::vector<float> before;
    std::vector<float> after;
  };
  Change snapshotAll() const;
  void apply(const Change& change, bool undo);

  void set(int32_t column, int32_t row, std::array<double, 2> value);
  void beginRecording(Change* change);
  void endRecording();

 private:
  void touched(int32_t column, int32_t row);

  int32_t canvasWidth_ = 0;
  int32_t canvasHeight_ = 0;
  int32_t width_ = 0;
  int32_t height_ = 0;
  std::vector<float> values_;
  FieldRegion changed_;
  FieldRegion bounds_;
  Change* recording_ = nullptr;
  std::vector<uint8_t> recorded_;
};

class LiquifyStroke {
 public:
  LiquifyStroke(LiquifyField& field, LiquifyBrush brush);
  LiquifyStroke(const LiquifyStroke&) = delete;
  LiquifyStroke& operator=(const LiquifyStroke&) = delete;
  ~LiquifyStroke();

  void add(const LiquifySample& sample);
  void end();
  bool advanceMomentum(double elapsed);
  void finishMomentum() { advanceMomentum(1e9); }
  [[nodiscard]] bool ended() const { return ended_; }
  LiquifyField::Change finish();

 private:
  void dab(const std::array<double, 2>& center, double pressure,
           const std::array<double, 2>& delta);

  LiquifyField& field_;
  LiquifyBrush brush_;
  double radius_ = 4;
  LiquifyField::Change change_;
  bool hasPrevious_ = false;
  LiquifySample previous_;
  std::array<double, 2> lastDab_{};
  double lastDabTime_ = 0;
  double carried_ = 0;
  bool ended_ = false;
  std::array<double, 2> velocity_{};
  double momentumDone_ = 0;
  double momentumDuration_ = 0;
  std::array<double, 2> momentumPosition_{};
  bool finished_ = false;
};

std::vector<double> liquifyImage(const LiquifyField& field, const std::vector<double>& rgba,
                                 int32_t width, int32_t height);

}  // namespace nanquim::adjust
