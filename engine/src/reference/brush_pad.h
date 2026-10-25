#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

#include "reference_image.h"
#include "stroke/dab_generator.h"
#include "stroke/input_sample.h"
#include "stroke/loaded_brush.h"
#include "stroke/paint_mixing.h"

namespace nanquim::reference {

struct PixelRect {
  int32_t left = 0;
  int32_t top = 0;
  int32_t right = 0;
  int32_t bottom = 0;

  [[nodiscard]] bool empty() const { return right <= left || bottom <= top; }
  void include(const PixelRect& other);
};

class BrushPad {
 public:
  BrushPad(int32_t width, int32_t height);

  [[nodiscard]] int32_t width() const { return layer_.width; }
  [[nodiscard]] int32_t height() const { return layer_.height; }

  void clear();
  void beginStroke(std::shared_ptr<const stroke::LoadedBrush> brush,
                   const stroke::StrokeSettings& settings, bool erase);
  PixelRect addSamples(const std::vector<stroke::InputSample>& samples);
  PixelRect endStroke();

  void writeRgba8(const PixelRect& rect, uint8_t* output) const;

 private:
  PixelRect renderDabs(const std::vector<stroke::Dab>& dabs);
  [[nodiscard]] PixelRect canvasRect() const { return {0, 0, width(), height()}; }

  Image layer_;
  Image strokeBuffer_;
  PixelRect strokeRect_;
  std::shared_ptr<const stroke::LoadedBrush> brush_;
  std::optional<stroke::DabGenerator> generator_;
  double strokeOpacity_ = 1;
  bool erase_ = false;
  stroke::RenderParameters render_;
  stroke::PaintMixing mixing_ = stroke::PaintMixing::None;
  stroke::MixingState mixingState_;
  double strokeSizePixels_ = 0;
  std::vector<stroke::Dab> scratch_;
};

std::vector<uint8_t> renderBrushThumbnail(const stroke::LoadedBrush& brush, double previewSize,
                                          bool stampPreview, int32_t width, int32_t height);

std::vector<uint8_t> renderBrushThumbnailFrames(const stroke::LoadedBrush& brush,
                                                double previewSize, bool stampPreview,
                                                int32_t width, int32_t height, int32_t frameCount);

}  // namespace nanquim::reference
