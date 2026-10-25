#include "brush_pad.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>

#include "reference_renderer.h"

namespace nanquim::reference {
namespace {

std::shared_ptr<const stroke::LoadedBrush> roundBrush() {
  auto brush = std::make_shared<stroke::LoadedBrush>();
  brush->parameters = stroke::roundBrush();
  return brush;
}

std::vector<stroke::InputSample> line(double fromX, double toX, double y, int count) {
  std::vector<stroke::InputSample> samples;
  for (int index = 0; index <= count; ++index) {
    stroke::InputSample sample;
    sample.x = static_cast<float>(fromX + (toX - fromX) * index / count);
    sample.y = static_cast<float>(y);
    sample.pressure = 0.8F;
    sample.eventTimeNanos = static_cast<int64_t>(index) * 4'000'000;
    sample.toolType = stroke::kToolTypeStylus;
    samples.push_back(sample);
  }
  return samples;
}

uint8_t alphaAt(const std::vector<uint8_t>& pixels, int32_t width, int32_t x, int32_t y) {
  return pixels[(static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 4 +
                3];
}

TEST(BrushPadTest, IncrementalStrokeMatchesTheReferenceRenderer) {
  stroke::StrokeSettings settings;
  settings.sizePixels = 16;
  settings.opacity = 0.7;
  settings.color = {0.2, 0.4, 0.6, 1};
  settings.seed = 3;
  const auto samples = line(10, 110, 30, 50);

  BrushPad pad(128, 64);
  pad.beginStroke(roundBrush(), settings, false);
  PixelRect changed;
  for (size_t start = 0; start < samples.size(); start += 7) {
    const std::vector<stroke::InputSample> batch(
        samples.begin() + static_cast<std::ptrdiff_t>(start),
        samples.begin() + static_cast<std::ptrdiff_t>(std::min(samples.size(), start + 7)));
    changed.include(pad.addSamples(batch));
  }
  changed.include(pad.endStroke());
  EXPECT_FALSE(changed.empty());
  std::vector<uint8_t> padPixels(128 * 64 * 4);
  pad.writeRgba8({0, 0, 128, 64}, padPixels.data());

  stroke::DabGenerator generator(stroke::roundBrush(), settings);
  std::vector<stroke::Dab> dabs;
  for (const auto& sample : samples) {
    generator.addPoint(stroke::pathPointFrom(sample), dabs);
  }
  generator.finish(dabs);
  Image buffer(128, 64);
  renderDabs(buffer, dabs, stroke::TipStyle{1.0}, stroke::Accumulation::Wash);
  Image layer(128, 64);
  commitPaint(layer, buffer, settings.opacity);
  for (size_t offset = 0; offset < padPixels.size(); ++offset) {
    ASSERT_NEAR(padPixels[offset], std::lround(layer.rgba[offset] * 255), 1) << offset;
  }
}

TEST(BrushPadTest, EraseAndClear) {
  stroke::StrokeSettings settings;
  settings.sizePixels = 20;
  BrushPad pad(64, 64);
  pad.beginStroke(roundBrush(), settings, false);
  pad.addSamples(line(5, 60, 32, 20));
  pad.endStroke();
  std::vector<uint8_t> pixels(64 * 64 * 4);
  pad.writeRgba8({0, 0, 64, 64}, pixels.data());
  EXPECT_GT(alphaAt(pixels, 64, 32, 32), 200);

  pad.beginStroke(roundBrush(), settings, true);
  pad.addSamples(line(5, 60, 32, 20));
  pad.endStroke();
  pad.writeRgba8({0, 0, 64, 64}, pixels.data());
  EXPECT_LT(alphaAt(pixels, 64, 32, 32), 60);

  pad.clear();
  pad.writeRgba8({0, 0, 64, 64}, pixels.data());
  for (size_t offset = 3; offset < pixels.size(); offset += 4) {
    ASSERT_EQ(pixels[offset], 0);
  }
}

TEST(BrushPadTest, ThumbnailIsAnSCurveOrAStamp) {
  const auto brush = roundBrush();
  const auto stroke = renderBrushThumbnail(*brush, 0.5, false, 256, 64);
  int inked = 0;
  for (size_t offset = 3; offset < stroke.size(); offset += 4) {
    inked += stroke[offset] > 0 ? 1 : 0;
  }
  EXPECT_GT(inked, 1000);
  EXPECT_EQ(alphaAt(stroke, 256, 2, 2), 0);

  const auto stamp = renderBrushThumbnail(*brush, 0.5, true, 256, 64);
  EXPECT_GT(alphaAt(stamp, 256, 128, 32), 0);
  EXPECT_EQ(alphaAt(stamp, 256, 20, 32), 0);
}

TEST(BrushPadTest, ThumbnailFramesGrowIntoTheThumbnail) {
  const auto brush = roundBrush();
  constexpr int32_t kWidth = 256;
  constexpr int32_t kHeight = 64;
  constexpr int32_t kFrames = 6;
  const auto frames = renderBrushThumbnailFrames(*brush, 0.5, false, kWidth, kHeight, kFrames);
  const size_t frameBytes = static_cast<size_t>(kWidth) * kHeight * 4;
  ASSERT_EQ(frames.size(), frameBytes * kFrames);
  int previous = 0;
  for (int32_t frame = 0; frame < kFrames; ++frame) {
    int inked = 0;
    for (size_t offset = 3; offset < frameBytes; offset += 4) {
      inked += frames[frameBytes * static_cast<size_t>(frame) + offset] > 0 ? 1 : 0;
    }
    EXPECT_GT(inked, previous) << "frame " << frame;
    previous = inked;
  }
  const std::vector<uint8_t> first(frames.begin(),
                                   frames.begin() + static_cast<ptrdiff_t>(frameBytes));
  EXPECT_EQ(alphaAt(first, kWidth, kWidth * 3 / 4, kHeight / 2), 0);
  const auto thumbnail = renderBrushThumbnail(*brush, 0.5, false, kWidth, kHeight);
  EXPECT_TRUE(std::equal(thumbnail.begin(), thumbnail.end(),
                         frames.begin() + static_cast<ptrdiff_t>(frameBytes * (kFrames - 1))));
}

}  // namespace
}  // namespace nanquim::reference
