#include "brush_pad.h"

#include <algorithm>
#include <cmath>
#include <utility>

#include "reference_renderer.h"
#include "stroke/stroke_effects.h"
#include "stroke/tip_coverage.h"

namespace nanquim::reference {

namespace {

constexpr double kPi = 3.14159265358979323846;

uint8_t toByte(double value) {
  return static_cast<uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
}

stroke::TipStyle tipStyleOf(const stroke::LoadedBrush& brush) {
  const stroke::BrushParameters& parameters = brush.parameters;
  return stroke::TipStyle{parameters.shape.hardness, brush.shapeTexture.get(),
                          parameters.shape.invert,   parameters.shape.filtering,
                          &parameters.grain,         brush.grainTexture.get()};
}

void clearRect(Image& image, const PixelRect& rect) {
  for (int32_t y = rect.top; y < rect.bottom; ++y) {
    std::fill_n(image.pixel(rect.left, y), static_cast<size_t>(rect.right - rect.left) * 4, 0.0);
  }
}

}  // namespace

void PixelRect::include(const PixelRect& other) {
  if (other.empty()) {
    return;
  }
  if (empty()) {
    *this = other;
    return;
  }
  left = std::min(left, other.left);
  top = std::min(top, other.top);
  right = std::max(right, other.right);
  bottom = std::max(bottom, other.bottom);
}

BrushPad::BrushPad(int32_t width, int32_t height)
    : layer_(width, height), strokeBuffer_(width, height) {}

void BrushPad::clear() {
  std::fill(layer_.rgba.begin(), layer_.rgba.end(), 0.0);
  std::fill(strokeBuffer_.rgba.begin(), strokeBuffer_.rgba.end(), 0.0);
  strokeRect_ = {};
  generator_.reset();
}

void BrushPad::beginStroke(std::shared_ptr<const stroke::LoadedBrush> brush,
                           const stroke::StrokeSettings& settings, bool erase) {
  if (generator_) {
    endStroke();
  }
  brush_ = std::move(brush);
  generator_.emplace(brush_->parameters, settings);
  strokeOpacity_ = std::clamp(settings.opacity, 0.0, 1.0);
  erase_ = erase;
  render_ = stroke::effectiveRender(brush_->parameters.render, !erase, false, false);
  mixing_ = !erase && brush_->parameters.wetMix.enabled ? stroke::PaintMixing::WetMix
                                                        : stroke::PaintMixing::None;
  mixingState_ = {};
  strokeSizePixels_ = settings.sizePixels;
}

PixelRect BrushPad::addSamples(const std::vector<stroke::InputSample>& samples) {
  if (!generator_) {
    return {};
  }
  scratch_.clear();
  for (const stroke::InputSample& sample : samples) {
    if (stroke::isPathSample(sample)) {
      generator_->addPoint(stroke::pathPointFrom(sample), scratch_);
    }
  }
  return renderDabs(scratch_);
}

PixelRect BrushPad::endStroke() {
  if (!generator_) {
    return {};
  }
  scratch_.clear();
  generator_->finish(scratch_);
  PixelRect changed = renderDabs(scratch_);
  changed.include(strokeRect_);
  if (!strokeRect_.empty() && mixing_ == stroke::PaintMixing::None) {
    applyStrokeEffects(strokeBuffer_, render_, strokeSizePixels_);
    for (int32_t y = strokeRect_.top; y < strokeRect_.bottom; ++y) {
      for (int32_t x = strokeRect_.left; x < strokeRect_.right; ++x) {
        double* destination = layer_.pixel(x, y);
        const double* source = strokeBuffer_.pixel(x, y);
        if (erase_) {
          const double keep = 1 - source[3] * strokeOpacity_;
          for (size_t channel = 0; channel < 4; ++channel) {
            destination[channel] *= keep;
          }
        } else {
          const double alpha = source[3] * strokeOpacity_;
          for (size_t channel = 0; channel < 4; ++channel) {
            destination[channel] =
                source[channel] * strokeOpacity_ + destination[channel] * (1 - alpha);
          }
        }
      }
    }
    clearRect(strokeBuffer_, strokeRect_);
  }
  strokeRect_ = {};
  generator_.reset();
  return changed;
}

PixelRect BrushPad::renderDabs(const std::vector<stroke::Dab>& dabs) {
  if (dabs.empty() || !brush_) {
    return {};
  }
  const stroke::TipStyle style = tipStyleOf(*brush_);
  if (mixing_ != stroke::PaintMixing::None) {
    reference::renderMixingDabs(layer_, dabs, style, mixing_, brush_->parameters, strokeOpacity_,
                                mixingState_);
  } else {
    reference::renderDabs(strokeBuffer_, dabs, style, brush_->parameters.render.accumulation,
                          erase_ ? nullptr : &layer_, render_.gammaCorrect);
  }
  PixelRect changed;
  for (const stroke::Dab& dab : dabs) {
    const double extent = stroke::dabBoundingRadius(dab, style.textured());
    PixelRect bounds{static_cast<int32_t>(std::floor(dab.x - extent)),
                     static_cast<int32_t>(std::floor(dab.y - extent)),
                     static_cast<int32_t>(std::ceil(dab.x + extent)) + 1,
                     static_cast<int32_t>(std::ceil(dab.y + extent)) + 1};
    bounds.left = std::clamp(bounds.left, 0, width());
    bounds.top = std::clamp(bounds.top, 0, height());
    bounds.right = std::clamp(bounds.right, 0, width());
    bounds.bottom = std::clamp(bounds.bottom, 0, height());
    changed.include(bounds);
  }
  strokeRect_.include(changed);
  return changed;
}

void BrushPad::writeRgba8(const PixelRect& rect, uint8_t* output) const {
  const bool stroking = generator_.has_value();
  for (int32_t y = std::max(rect.top, 0); y < std::min(rect.bottom, height()); ++y) {
    for (int32_t x = std::max(rect.left, 0); x < std::min(rect.right, width()); ++x) {
      const double* layer = layer_.pixel(x, y);
      double pixel[4] = {layer[0], layer[1], layer[2], layer[3]};
      if (stroking) {
        const double* buffer = strokeBuffer_.pixel(x, y);
        double stroke[4] = {buffer[0], buffer[1], buffer[2], buffer[3]};
        if (render_.gammaCorrect && stroke[3] > 0) {
          for (size_t channel = 0; channel < 3; ++channel) {
            stroke[channel] = stroke::linearToSrgb(stroke[channel] / stroke[3]) * stroke[3];
          }
        }
        if (erase_) {
          const double keep = 1 - stroke[3] * strokeOpacity_;
          for (double& channel : pixel) {
            channel *= keep;
          }
        } else {
          const double alpha = stroke[3] * strokeOpacity_;
          for (size_t channel = 0; channel < 4; ++channel) {
            pixel[channel] = stroke[channel] * strokeOpacity_ + pixel[channel] * (1 - alpha);
          }
        }
      }
      uint8_t* target =
          output +
          (static_cast<size_t>(y) * static_cast<size_t>(width()) + static_cast<size_t>(x)) * 4;
      for (size_t channel = 0; channel < 4; ++channel) {
        target[channel] = toByte(pixel[channel]);
      }
    }
  }
}

std::vector<uint8_t> renderBrushThumbnail(const stroke::LoadedBrush& brush, double previewSize,
                                          bool stampPreview, int32_t width, int32_t height) {
  return renderBrushThumbnailFrames(brush, previewSize, stampPreview, width, height, 1);
}

std::vector<uint8_t> renderBrushThumbnailFrames(const stroke::LoadedBrush& brush,
                                                double previewSize, bool stampPreview,
                                                int32_t width, int32_t height, int32_t frameCount) {
  frameCount = std::max(frameCount, 1);
  auto shared = std::make_shared<const stroke::LoadedBrush>(brush);
  BrushPad pad(width, height);
  stroke::StrokeSettings settings;
  settings.sizePixels =
      std::clamp(std::clamp(previewSize, 0.05, 1.0) * 0.8 * height, brush.parameters.limits.minSize,
                 brush.parameters.limits.maxSize);
  settings.color = {0, 0, 0, 1};
  settings.seed = 0x5EED;
  pad.beginStroke(shared, settings, false);
  std::vector<stroke::InputSample> samples;
  if (stampPreview) {
    stroke::InputSample sample;
    sample.x = static_cast<float>(width / 2.0);
    sample.y = static_cast<float>(height / 2.0);
    sample.pressure = 1;
    sample.toolType = stroke::kToolTypeStylus;
    samples.push_back(sample);
  } else {
    constexpr int kSampleCount = 240;
    const double margin = settings.sizePixels / 2 + 4;
    const double amplitude = std::max(0.0, height / 2.0 - margin) * 0.8;
    for (int index = 0; index <= kSampleCount; ++index) {
      const double t = static_cast<double>(index) / kSampleCount;
      stroke::InputSample sample;
      sample.x = static_cast<float>(margin + (width - 2 * margin) * t);
      sample.y = static_cast<float>(height / 2.0 - amplitude * std::sin(2 * kPi * t));
      sample.pressure = static_cast<float>(0.2 + 0.8 * std::sin(kPi * t));
      sample.eventTimeNanos = static_cast<int64_t>(index) * 4'000'000;
      sample.toolType = stroke::kToolTypeStylus;
      samples.push_back(sample);
    }
  }
  const size_t frameBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
  std::vector<uint8_t> frames(frameBytes * static_cast<size_t>(frameCount));
  size_t added = 0;
  for (int32_t frame = 1; frame <= frameCount; ++frame) {
    const size_t until =
        samples.size() * static_cast<size_t>(frame) / static_cast<size_t>(frameCount);
    if (until > added) {
      pad.addSamples(
          std::vector<stroke::InputSample>(samples.begin() + static_cast<ptrdiff_t>(added),
                                           samples.begin() + static_cast<ptrdiff_t>(until)));
      added = until;
    }
    if (frame == frameCount) pad.endStroke();
    pad.writeRgba8(PixelRect{0, 0, width, height},
                   frames.data() + frameBytes * static_cast<size_t>(frame - 1));
  }
  return frames;
}

}  // namespace nanquim::reference
