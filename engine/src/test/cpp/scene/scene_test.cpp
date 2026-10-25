#include "scene/scene.h"

#include <gtest/gtest.h>

#include <array>

#include "stroke/random.h"

namespace nanquim::scene {
namespace {

constexpr const char* kScene = R"json({
  "format": 1,
  "documentId": "doc",
  "canvas": { "width": 300, "height": 200, "background": [1, 1, 0.5, 1] },
  "layers": 2,
  "actions": [
    { "type": "stroke", "tool": "paint", "layer": 2, "size": 30, "opacity": 0.5,
      "color": [0.2, 0.4, 0.6, 1], "brush": { "accumulation": "buildUp", "spacing": 0.2 },
      "samples": [[10, 20, 0.5, 0], [30, 40, 1, 8.5]] },
    { "type": "stroke", "tool": "erase", "trace": "t.trace" }
  ]
})json";

std::optional<std::vector<uint8_t>> oneSampleTrace(const std::string& name) {
  if (name != "t.trace") {
    return std::nullopt;
  }
  std::vector<uint8_t> bytes(stroke::kPackedSampleBytes);
  stroke::InputSample sample;
  sample.x = 7;
  stroke::encodePackedSample(sample, bytes.data());
  return bytes;
}

TEST(SceneTest, ParsesCanvasLayersAndStrokes) {
  std::string error;
  const auto parsed = parseScene(kScene, oneSampleTrace, error);
  if (!parsed) {
    FAIL() << error;
    return;
  }
  const Scene* scene = &*parsed;
  EXPECT_EQ(scene->width, 300);
  EXPECT_EQ(scene->height, 200);
  EXPECT_EQ(scene->background[2], 0.5);
  ASSERT_EQ(scene->layers.layers.size(), 2U);
  EXPECT_EQ(scene->layers.layers[1].id, 2U);
  ASSERT_EQ(scene->actions.size(), 2U);

  const StrokeAction& paint = scene->actions[0].stroke;
  EXPECT_EQ(paint.tool, Tool::Paint);
  EXPECT_EQ(paint.layer, 2);
  EXPECT_EQ(paint.settings.sizePixels, 30);
  EXPECT_EQ(paint.settings.opacity, 0.5);
  EXPECT_EQ(paint.settings.color[1], 0.4);
  EXPECT_EQ(paint.brush.render.accumulation, stroke::Accumulation::BuildUp);
  EXPECT_EQ(paint.brush.path.spacing, 0.2);
  ASSERT_EQ(paint.samples.size(), 2U);
  EXPECT_EQ(paint.samples[1].eventTimeNanos, 8500000);
  EXPECT_EQ(paint.settings.seed, stroke::strokeSeed("doc", 0));

  const StrokeAction& erase = scene->actions[1].stroke;
  EXPECT_EQ(erase.tool, Tool::Erase);
  ASSERT_EQ(erase.samples.size(), 1U);
  EXPECT_EQ(erase.samples[0].x, 7);
  EXPECT_EQ(erase.settings.seed, stroke::strokeSeed("doc", 1));
}

TEST(SceneTest, ReportsInvalidScenes) {
  const std::array<const char*, 5> invalid{
      R"({"format": 2, "canvas": {"width": 1, "height": 1}})",
      R"({"format": 1, "canvas": {"width": 0, "height": 1}})",
      R"({"format": 1, "canvas": {"width": 1, "height": 1}, "actions": [{"type": "stroke", "layer": 3, "samples": []}]})",
      R"({"format": 1, "canvas": {"width": 1, "height": 1}, "actions": [{"type": "stroke", "trace": "missing"}]})",
      "not json",
  };
  for (const char* text : invalid) {
    std::string error;
    EXPECT_FALSE(parseScene(text, oneSampleTrace, error)) << text;
    EXPECT_FALSE(error.empty());
  }
}

TEST(SceneTest, ReadsSymmetryCopies) {
  std::string error;
  const auto parsed = parseScene(
      R"({"format": 1, "canvas": {"width": 64, "height": 64}, "actions": [{"type": "stroke",
          "samples": [[1, 2, 1, 0]], "symmetry": [[1, 0, 0, 1, 0, 0], [-1, 0, 0, 1, 64, 0]]}]})",
      oneSampleTrace, error);
  ASSERT_TRUE(parsed) << error;
  const std::vector<core::AffineTransform>& copies = parsed->actions[0].stroke.settings.symmetry;
  ASSERT_EQ(copies.size(), 2U);
  EXPECT_EQ(copies[1].a, -1);
  EXPECT_EQ(copies[1].tx, 64);
  for (const char* stroke : {R"("tool": "smudge", "symmetry": [[1, 0, 0, 1, 0, 0]])",
                             R"("symmetry": [[1, 0, 0, 1, 0]])"}) {
    const std::string text =
        std::string(R"({"format": 1, "canvas": {"width": 64, "height": 64}, "actions": [{"type": )"
                    R"("stroke", "samples": [[1, 2, 1, 0]], )") +
        stroke + "}]}";
    EXPECT_FALSE(parseScene(text, oneSampleTrace, error)) << text;
  }
}

TEST(SceneTest, ReadsAQuickShapeReplacement) {
  std::string error;
  const auto parsed = parseScene(
      R"({"format": 1, "canvas": {"width": 64, "height": 64}, "actions": [{"type": "stroke",
          "brush": {"streamline": 0.5}, "samples": [[1, 2, 1, 0], [9, 3, 1, 8]],
          "replaceWith": [[1, 2, 0.5, 0], [5, 2, 0.6, 4], [9, 2, 0.7, 8]]}]})",
      oneSampleTrace, error);
  ASSERT_TRUE(parsed) << error;
  const StrokeAction& stroke = parsed->actions[0].stroke;
  EXPECT_EQ(stroke.samples.size(), 2U);
  ASSERT_EQ(stroke.replacement.size(), 3U);
  EXPECT_FLOAT_EQ(stroke.replacement[1].pressure, 0.6F);
  EXPECT_TRUE(stroke.settings.smoothPositions);
  const StrokeAction replaced = stroke.replaced();
  EXPECT_EQ(replaced.samples.size(), 3U);
  EXPECT_TRUE(replaced.replacement.empty());
  EXPECT_FALSE(replaced.settings.smoothPositions);
  EXPECT_EQ(replaced.settings.seed, stroke.settings.seed);
  EXPECT_EQ(replaced.brush.path.streamline, 0.5);
  EXPECT_FALSE(parseScene(
      R"({"format": 1, "canvas": {"width": 64, "height": 64}, "actions": [{"type": "stroke",
          "samples": [[1, 2, 1, 0]], "replaceWith": [[1, 2]]}]})",
      oneSampleTrace, error));
}

}  // namespace
}  // namespace nanquim::scene
