#include "test_sheet.h"

#include <algorithm>
#include <cmath>
#include <functional>

#include "stroke/random.h"

namespace nanquim::reference {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kSampleIntervalMilliseconds = 4;
constexpr double kSlowSpeed = 0.5;
constexpr double kMediumSpeed = 1.5;
constexpr double kFastSpeed = 4.0;
constexpr double kTiltRadians = kPi / 3;
constexpr double kMaximumSizeInRegion = 400;
constexpr std::array<double, 4> kInk{0, 0, 0, 1};
constexpr std::array<double, 4> kSaturated{0.9, 0.1, 0.1, 1};

struct Region2048 {
  SheetItem item;
  int32_t left, top, right, bottom;
};

constexpr Region2048 kRegions[] = {
    {SheetItem::A, 64, 64, 1984, 288},     {SheetItem::B, 64, 320, 1984, 544},
    {SheetItem::C, 64, 576, 1984, 928},    {SheetItem::D, 64, 960, 1000, 1248},
    {SheetItem::E, 1048, 960, 1984, 1248}, {SheetItem::F, 64, 1280, 1000, 1440},
    {SheetItem::I, 1048, 1280, 1984, 1440}, {SheetItem::G, 64, 1472, 1984, 1984},
};

struct PathSample {
  double x;
  double y;
  double pressure;
};

std::vector<stroke::InputSample> samplePath(const std::function<PathSample(double)>& path,
                                            double speed, double scale, double tilt = 0,
                                            double orientation = 0) {
  constexpr int kSubdivisions = 512;
  double length = 0;
  PathSample previous = path(0);
  for (int index = 1; index <= kSubdivisions; ++index) {
    const PathSample next = path(static_cast<double>(index) / kSubdivisions);
    length += std::hypot(next.x - previous.x, next.y - previous.y);
    previous = next;
  }
  const double duration = std::max(length / speed, kSampleIntervalMilliseconds);
  const auto count = std::max(1, static_cast<int>(std::ceil(duration / kSampleIntervalMilliseconds)));
  std::vector<stroke::InputSample> samples;
  for (int index = 0; index <= count; ++index) {
    const PathSample point = path(static_cast<double>(index) / count);
    stroke::InputSample sample;
    sample.x = static_cast<float>(point.x * scale);
    sample.y = static_cast<float>(point.y * scale);
    sample.pressure = static_cast<float>(std::clamp(point.pressure, 0.0, 1.0));
    sample.tilt = static_cast<float>(tilt);
    sample.orientation = static_cast<float>(orientation);
    sample.eventTimeNanos = static_cast<int64_t>(index * kSampleIntervalMilliseconds * 1e6);
    sample.toolType = stroke::kToolTypeStylus;
    samples.push_back(sample);
  }
  return samples;
}

std::function<PathSample(double)> line(double fromX, double fromY, double toX, double toY,
                                       std::function<double(double)> pressure) {
  return [=](double t) {
    return PathSample{fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, pressure(t)};
  };
}

double constantPressure(double) { return 0.7; }
double rampPressure(double t) { return 0.1 + 0.9 * std::sin(kPi * t); }

}  // namespace

std::vector<SheetRegion> sheetRegions(double scale) {
  std::vector<SheetRegion> regions;
  for (const Region2048& region : kRegions) {
    regions.push_back(SheetRegion{
        region.item, PixelRect{static_cast<int32_t>(std::lround(region.left * scale)),
                               static_cast<int32_t>(std::lround(region.top * scale)),
                               static_cast<int32_t>(std::lround(region.right * scale)),
                               static_cast<int32_t>(std::lround(region.bottom * scale))}});
  }
  return regions;
}

double defaultBrushSize(const stroke::BrushParameters& brush) {
  const double slider = std::clamp(brush.defaults.size, 0.0, 1.0);
  return brush.limits.minSize + (brush.limits.maxSize - brush.limits.minSize) * slider * slider;
}

double defaultBrushOpacity(const stroke::BrushParameters& brush) {
  return brush.limits.minOpacity +
         (brush.limits.maxOpacity - brush.limits.minOpacity) * std::clamp(brush.defaults.opacity, 0.0, 1.0);
}

scene::Scene testSheetScene(const stroke::LoadedBrush& brush, double scale, double sizePixels,
                            double opacity) {
  scene::Scene sheet;
  sheet.documentId = "brush-test-sheet";
  sheet.width = static_cast<int32_t>(std::lround(kSheetSize * scale));
  sheet.height = sheet.width;
  sheet.background = {1, 1, 1, 1};
  const double size = (sizePixels > 0 ? sizePixels : defaultBrushSize(brush.parameters)) * scale;
  const double strokeOpacity = opacity > 0 ? opacity : defaultBrushOpacity(brush.parameters);
  uint64_t strokeIndex = 0;

  auto add = [&](std::vector<stroke::InputSample> samples, double diameter,
                 std::array<double, 4> color) {
    scene::StrokeAction action;
    action.tool = scene::Tool::Paint;
    action.layer = 1;
    action.brush = brush.parameters;
    action.shapeTexture = brush.shapeTexture;
    action.grainTexture = brush.grainTexture;
    action.settings.sizePixels = std::max(diameter, 0.1);
    action.settings.opacity = strokeOpacity;
    action.settings.color = color;
    action.settings.seed = stroke::strokeSeed(sheet.documentId, strokeIndex++);
    action.samples = std::move(samples);
    sheet.actions.push_back(scene::SceneAction::of(std::move(action)));
  };

  add(samplePath(line(160, 176, 1888, 176, rampPressure), kSlowSpeed, scale), size, kInk);
  add(samplePath(line(160, 432, 1888, 432, rampPressure), kFastSpeed, scale), size, kInk);
  add(samplePath(
          [](double t) {
            return PathSample{160 + 1728 * t, 752 + 120 * std::sin(2 * kPi * t), 0.5};
          },
          kMediumSpeed, scale),
      size, kInk);
  add(samplePath(line(160, 1032, 900, 1032, constantPressure), kMediumSpeed, scale), size, kInk);
  for (int pass = 0; pass < 5; ++pass) {
    add(samplePath(line(160, 1176, 900, 1176, constantPressure), kMediumSpeed, scale), size, kInk);
  }
  for (const double y : {1032.0, 1104.0, 1176.0}) {
    add(samplePath(line(1144, y, 1888, y, [](double) { return 0.6; }), kMediumSpeed, scale,
                   kTiltRadians, 0),
        size, kInk);
  }
  for (int tap = 0; tap < 5; ++tap) {
    const double x = 160 + 160 * tap;
    add(samplePath(line(x, 1360, x, 1360, constantPressure), kSlowSpeed, scale), size, kInk);
  }
  for (int stroke = 0; stroke < 10; ++stroke) {
    const double x = 1120 + 84 * stroke;
    add(samplePath(line(x, 1320, x + 40, 1400, constantPressure), kMediumSpeed, scale), size,
        kSaturated);
  }
  const double largest =
      std::min(brush.parameters.limits.maxSize, kMaximumSizeInRegion) * scale;
  add(samplePath(line(300, 1660, 1750, 1660, [](double) { return 1.0; }), kMediumSpeed, scale),
      largest, kInk);
  add(samplePath(line(160, 1930, 1888, 1930, [](double) { return 1.0; }), kMediumSpeed, scale),
      brush.parameters.limits.minSize * scale, kInk);
  return sheet;
}

namespace {

constexpr std::array<std::array<uint8_t, 7>, 8> kGlyphs = {{
    {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11},
    {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E},
    {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E},
    {0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E},
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F},
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10},
    {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F},
    {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E},
}};

}  // namespace

std::vector<uint8_t> renderSheetTemplate(double scale) {
  const auto size = static_cast<int32_t>(std::lround(kSheetSize * scale));
  std::vector<uint8_t> pixels(static_cast<size_t>(size) * static_cast<size_t>(size) * 4, 255);
  auto set = [&](int32_t x, int32_t y, uint8_t gray) {
    if (x < 0 || y < 0 || x >= size || y >= size) {
      return;
    }
    uint8_t* pixel = &pixels[(static_cast<size_t>(y) * static_cast<size_t>(size) + static_cast<size_t>(x)) * 4];
    pixel[0] = pixel[1] = pixel[2] = gray;
  };
  constexpr uint8_t kOutline = 170;
  constexpr uint8_t kLetter = 90;
  for (const SheetRegion& region : sheetRegions(scale)) {
    const PixelRect& rect = region.rect;
    for (int32_t x = rect.left; x < rect.right; ++x) {
      set(x, rect.top, kOutline);
      set(x, rect.bottom - 1, kOutline);
    }
    for (int32_t y = rect.top; y < rect.bottom; ++y) {
      set(rect.left, y, kOutline);
      set(rect.right - 1, y, kOutline);
    }
    const auto& glyph = kGlyphs[static_cast<size_t>(region.item)];
    const int32_t cell = std::max(2, static_cast<int32_t>(std::lround(4 * scale)));
    for (int32_t row = 0; row < 7; ++row) {
      for (int32_t column = 0; column < 5; ++column) {
        if ((glyph[static_cast<size_t>(row)] >> (4 - column) & 1U) == 0) {
          continue;
        }
        for (int32_t dy = 0; dy < cell; ++dy) {
          for (int32_t dx = 0; dx < cell; ++dx) {
            set(rect.left + 8 + column * cell + dx, rect.top + 8 + row * cell + dy, kLetter);
          }
        }
      }
    }
  }
  return pixels;
}

}  // namespace nanquim::reference
