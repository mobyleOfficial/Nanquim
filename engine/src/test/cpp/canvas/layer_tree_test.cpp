#include "canvas/layer_tree.h"

#include <gtest/gtest.h>

namespace nanquim::canvas {
namespace {

constexpr const char* kTree = R"({
  "background": [1, 0.5, 0, 1], "backgroundHidden": false, "activeLayer": 3, "solo": null,
  "layers": [
    { "type": "raster", "id": 1, "opacity": 0.5, "blend": "multiply", "alphaLock": true },
    { "type": "group", "id": 2, "blend": "screen", "hidden": true, "children": [
      { "type": "raster", "id": 3, "clipping": true,
        "mask": { "id": 4, "enabled": false, "inverted": true } } ] } ] })";

TEST(LayerTreeTest, ParsesNodesAndProperties) {
  std::string error;
  const auto tree = parseLayerTree(kTree, error);
  ASSERT_TRUE(tree) << error;
  EXPECT_EQ(tree->background[1], 0.5F);
  EXPECT_EQ(tree->activeLayer, 3U);
  EXPECT_FALSE(tree->solo);
  ASSERT_EQ(tree->layers.size(), 2U);
  const LayerTreeNode& first = tree->layers[0];
  EXPECT_EQ(first.blend, BlendMode::Multiply);
  EXPECT_EQ(first.opacity, 0.5F);
  EXPECT_TRUE(first.alphaLock);
  const LayerTreeNode& group = tree->layers[1];
  EXPECT_TRUE(group.isGroup());
  EXPECT_FALSE(group.isPassThrough());
  EXPECT_TRUE(group.hidden);
  const LayerTreeNode* nested = tree->find(3);
  ASSERT_NE(nested, nullptr);
  EXPECT_TRUE(nested->clipping);
  ASSERT_TRUE(nested->mask);
  EXPECT_EQ(nested->mask->id, 4U);
  EXPECT_FALSE(nested->mask->enabled);
  EXPECT_TRUE(nested->mask->inverted);
  EXPECT_EQ(tree->surfaceIds(), (std::vector<LayerId>{1, 3, 4}));
  ASSERT_EQ(tree->ancestors(3).size(), 1U);
  EXPECT_EQ(tree->ancestors(3)[0]->id, 2U);
  EXPECT_TRUE(tree->ancestors(1).empty());
  EXPECT_EQ(tree->maskOwner(4), nested);
  EXPECT_EQ(tree->rasterLayers().size(), 2U);
}

TEST(LayerTreeTest, GroupsDefaultToPassThrough) {
  std::string error;
  const auto tree = parseLayerTree(R"({ "layers": [ { "type": "group", "id": 7 } ] })", error);
  ASSERT_TRUE(tree) << error;
  EXPECT_TRUE(tree->layers[0].isPassThrough());
}

TEST(LayerTreeTest, RejectsInvalidTrees) {
  const char* invalid[] = {
      R"({ "layers": [ { "id": 1 }, { "id": 1 } ] })",
      R"({ "layers": [ { "id": 1, "mask": { "id": 1 } } ] })",
      R"({ "layers": [ { "id": 1, "blend": "passThrough" } ] })",
      R"({ "layers": [ { "id": 1, "blend": "behind" } ] })",
      R"({ "layers": [ { "id": 1, "blend": "sparkle" } ] })",
      R"({ "layers": [ { "id": 0 } ] })",
      R"({ "layers": [ { "id": 1, "opacity": 2 } ] })",
      R"({ "layers": [ { "type": "group", "id": 1 } ], "solo": 1 })",
      R"({ "layers": [ { "id": 1 } ], "solo": 9 })",
      R"({ "layers": [ { "id": 1, "tint": [1, 0, 0] } ] })",
      R"({ "layers": [ { "id": 1, "tint": [1, 0, 0, "x"] } ] })",
  };
  for (const char* json : invalid) {
    std::string error;
    EXPECT_FALSE(parseLayerTree(json, error)) << json;
    EXPECT_FALSE(error.empty());
  }
}

TEST(LayerTreeTest, ReadsOnionTints) {
  std::string error;
  const auto tree = parseLayerTree(
      R"({ "layers": [ { "id": 1, "tint": [1, 0.5, 2, 1] }, { "id": 2 } ] })", error);
  ASSERT_TRUE(tree) << error;
  ASSERT_TRUE(tree->layers[0].tint);
  EXPECT_EQ(*tree->layers[0].tint, (std::array<float, 4>{1, 0.5F, 1, 1}));
  EXPECT_FALSE(tree->layers[1].tint);
}

TEST(LayerTreeTest, BlendModeIdsRoundTrip) {
  for (const BlendModeName& entry : kBlendModeNames) {
    EXPECT_EQ(blendModeFromId(entry.id), entry.mode);
    EXPECT_EQ(blendModeId(entry.mode), entry.id);
  }
  EXPECT_TRUE(isLayerBlendMode(BlendMode::LighterColor));
  EXPECT_FALSE(isLayerBlendMode(BlendMode::PassThrough));
}

}  // namespace
}  // namespace nanquim::canvas
