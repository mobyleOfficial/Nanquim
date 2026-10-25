#include "reference_compositor.h"

#include <gtest/gtest.h>

#include <map>

namespace nanquim::reference {
namespace {

using canvas::BlendMode;
using canvas::LayerTree;
using canvas::LayerTreeNode;

constexpr double kTolerance = 1e-12;

Image solid(std::array<double, 4> left, std::array<double, 4> right) {
  Image image(2, 1);
  for (size_t channel = 0; channel < 3; ++channel) {
    image.rgba[channel] = left[channel] * left[3];
    image.rgba[4 + channel] = right[channel] * right[3];
  }
  image.rgba[3] = left[3];
  image.rgba[7] = right[3];
  return image;
}

Image gray(double left, double right) { return solid({left, left, left, 1}, {right, right, right, 1}); }

LayerTreeNode raster(canvas::LayerId id, BlendMode blend = BlendMode::Normal, float opacity = 1) {
  LayerTreeNode node;
  node.id = id;
  node.blend = blend;
  node.opacity = opacity;
  return node;
}

LayerTreeNode group(canvas::LayerId id, std::vector<LayerTreeNode> children,
                    BlendMode blend = BlendMode::PassThrough, float opacity = 1) {
  LayerTreeNode node;
  node.type = LayerTreeNode::Type::Group;
  node.id = id;
  node.blend = blend;
  node.opacity = opacity;
  node.children = std::move(children);
  return node;
}

class Composer {
 public:
  std::map<canvas::LayerId, Image> surfaces;

  Image compose(LayerTree tree, std::array<float, 4> background = {1, 1, 1, 1},
                bool backgroundHidden = false) {
    tree.background = background;
    tree.backgroundHidden = backgroundHidden;
    return composeLayerTree(tree, 2, 1, [this](canvas::LayerId id) -> const Image* {
      auto found = surfaces.find(id);
      return found == surfaces.end() ? nullptr : &found->second;
    });
  }
};

LayerTree treeOf(std::vector<LayerTreeNode> layers) {
  LayerTree tree;
  tree.layers = std::move(layers);
  return tree;
}

TEST(ReferenceCompositorTest, MultiplyOverTheBackground) {
  Composer composer;
  composer.surfaces[1] = gray(0.5, 0.5);
  composer.surfaces[2] = solid({1, 0, 0, 1}, {1, 0, 0, 0.5});
  const Image result = composer.compose(treeOf({raster(1), raster(2, BlendMode::Multiply)}));
  EXPECT_NEAR(result.rgba[0], 0.5, kTolerance);
  EXPECT_NEAR(result.rgba[1], 0.0, kTolerance);
  EXPECT_NEAR(result.rgba[5], 0.25, kTolerance);
  EXPECT_NEAR(result.rgba[7], 1, kTolerance);
}

TEST(ReferenceCompositorTest, PassThroughGroupOpacityMultipliesChildren) {
  Composer composer;
  composer.surfaces[1] = solid({1, 0, 0, 1}, {0, 0, 1, 1});
  composer.surfaces[2] = solid({0, 1, 0, 0.5}, {0, 1, 0, 1});
  const Image throughGroup = composer.compose(
      treeOf({group(9, {raster(1), raster(2)}, BlendMode::PassThrough, 0.5F)}));
  const Image direct = composer.compose(treeOf({raster(1, BlendMode::Normal, 0.5F),
                                                raster(2, BlendMode::Normal, 0.5F)}));
  for (size_t index = 0; index < direct.rgba.size(); ++index) {
    EXPECT_NEAR(throughGroup.rgba[index], direct.rgba[index], kTolerance) << index;
  }
  const Image isolated =
      composer.compose(treeOf({group(9, {raster(1), raster(2)}, BlendMode::Normal, 0.5F)}));
  EXPECT_NEAR(direct.rgba[1], 0.625, kTolerance);
  EXPECT_NEAR(isolated.rgba[1], 0.75, kTolerance);
}

TEST(ReferenceCompositorTest, IsolatedGroupBlendsItsResult) {
  Composer composer;
  composer.surfaces[1] = gray(0.5, 0.5);
  composer.surfaces[2] = gray(0.5, 1.0);
  const Image result = composer.compose(
      treeOf({group(9, {raster(2, BlendMode::Multiply)}, BlendMode::Screen)}), {0.5, 0.5, 0.5, 1});
  EXPECT_NEAR(result.rgba[0], 0.75, kTolerance);
  EXPECT_NEAR(result.rgba[4], 1.0, kTolerance);
}

TEST(ReferenceCompositorTest, ClippingLayersStayInsideTheBase) {
  Composer composer;
  composer.surfaces[1] = solid({0, 0, 0, 1}, {0, 0, 0, 0});
  composer.surfaces[2] = gray(1, 1);
  LayerTreeNode clipped = raster(2);
  clipped.clipping = true;
  const Image result = composer.compose(treeOf({raster(1), clipped}), {0, 0, 1, 1});
  EXPECT_NEAR(result.rgba[0], 1, kTolerance);
  EXPECT_NEAR(result.rgba[4], 0, kTolerance);
  EXPECT_NEAR(result.rgba[6], 1, kTolerance);

  LayerTreeNode hiddenBase = raster(1);
  hiddenBase.hidden = true;
  const Image hidden = composer.compose(treeOf({hiddenBase, clipped}), {0, 0, 1, 1});
  EXPECT_NEAR(hidden.rgba[0], 0, kTolerance);
  EXPECT_NEAR(hidden.rgba[2], 1, kTolerance);

  const Image alone = composer.compose(treeOf({clipped}), {0, 0, 1, 1});
  EXPECT_NEAR(alone.rgba[4], 1, kTolerance);
}

TEST(ReferenceCompositorTest, MasksScaleTheLayer) {
  Composer composer;
  composer.surfaces[1] = gray(0, 0);
  composer.surfaces[5] = gray(0.25, 1);
  LayerTreeNode layer = raster(1);
  layer.mask = canvas::LayerMaskInfo{5, true, false};
  const Image masked = composer.compose(treeOf({layer}));
  EXPECT_NEAR(masked.rgba[0], 0.75, kTolerance);
  EXPECT_NEAR(masked.rgba[4], 0, kTolerance);

  layer.mask->inverted = true;
  const Image inverted = composer.compose(treeOf({layer}));
  EXPECT_NEAR(inverted.rgba[0], 0.25, kTolerance);
  EXPECT_NEAR(inverted.rgba[4], 1, kTolerance);

  layer.mask->enabled = false;
  EXPECT_NEAR(composer.compose(treeOf({layer})).rgba[0], 0, kTolerance);

  composer.surfaces.erase(5);
  layer.mask = canvas::LayerMaskInfo{5, true, false};
  EXPECT_NEAR(composer.compose(treeOf({layer})).rgba[0], 0, kTolerance);
}

TEST(ReferenceCompositorTest, SoloShowsOneLayerAlone) {
  Composer composer;
  composer.surfaces[1] = gray(0, 0);
  composer.surfaces[2] = solid({1, 0, 0, 0.5}, {1, 0, 0, 0.5});
  LayerTreeNode hiddenTop = raster(2);
  hiddenTop.hidden = true;
  LayerTree tree = treeOf({raster(1), hiddenTop});
  tree.solo = 2;
  const Image result = composer.compose(tree);
  EXPECT_NEAR(result.rgba[0], 1, kTolerance);
  EXPECT_NEAR(result.rgba[1], 0.5, kTolerance);
}

TEST(ReferenceCompositorTest, HiddenBackgroundIsTransparent) {
  Composer composer;
  const Image result = composer.compose(treeOf({}), {1, 1, 1, 1}, true);
  EXPECT_EQ(result.rgba[3], 0);
}

TEST(ReferenceCompositorTest, TintReplacesColorAndKeepsAlpha) {
  Composer composer;
  composer.surfaces[1] = solid({1, 1, 1, 1}, {0, 0, 0, 0.5});
  LayerTree tree;
  LayerTreeNode onion = raster(1, BlendMode::Normal, 0.5F);
  onion.tint = std::array<float, 4>{1, 0, 0, 1};
  tree.layers = {onion};
  const Image result = composer.compose(tree, {0, 0, 1, 1});
  EXPECT_NEAR(result.rgba[0], 0.5, kTolerance);
  EXPECT_NEAR(result.rgba[1], 0, kTolerance);
  EXPECT_NEAR(result.rgba[2], 0.5, kTolerance);
  EXPECT_NEAR(result.rgba[4], 0.25, kTolerance);
  EXPECT_NEAR(result.rgba[6], 0.75, kTolerance);
  EXPECT_NEAR(result.rgba[7], 1, kTolerance);
}

}  // namespace
}  // namespace nanquim::reference
