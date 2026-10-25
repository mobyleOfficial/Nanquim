#include "canvas/composite_plan.h"

#include <gtest/gtest.h>

#include <map>
#include <random>

#include "blend_reference.h"
#include "reference_compositor.h"

namespace nanquim::canvas {
namespace {

using reference::Image;
using reference::Rgba;

LayerTreeNode raster(LayerId id, BlendMode blend = BlendMode::Normal, float opacity = 1) {
  LayerTreeNode node;
  node.id = id;
  node.blend = blend;
  node.opacity = opacity;
  return node;
}

LayerTreeNode group(LayerId id, std::vector<LayerTreeNode> children,
                    BlendMode blend = BlendMode::PassThrough, float opacity = 1) {
  LayerTreeNode node;
  node.type = LayerTreeNode::Type::Group;
  node.id = id;
  node.blend = blend;
  node.opacity = opacity;
  node.children = std::move(children);
  return node;
}

Image evaluatePlan(const CompositePlan& plan, const LayerTree& tree, int32_t width, int32_t height,
                   const std::map<LayerId, Image>& surfaces) {
  std::vector<Image> levels(static_cast<size_t>(plan.levels), Image(width, height));
  if (!tree.backgroundHidden) {
    const double alpha = tree.background[3];
    for (size_t offset = 0; offset < levels[0].rgba.size(); offset += 4) {
      for (size_t channel = 0; channel < 3; ++channel) {
        levels[0].rgba[offset + channel] = tree.background[channel] * alpha;
      }
      levels[0].rgba[offset + 3] = alpha;
    }
  }
  auto blend = [](Image& backdrop, const Image& source, BlendMode mode, double opacity, bool clip,
                  const Image* mask, bool inverted, bool hasMask) {
    for (size_t offset = 0; offset < backdrop.rgba.size(); offset += 4) {
      double scale = opacity;
      if (hasMask) {
        double value = mask != nullptr ? mask->rgba[offset] : 1.0;
        scale *= inverted ? 1 - value : value;
      }
      const Rgba sourceColor{source.rgba[offset] * scale, source.rgba[offset + 1] * scale,
                             source.rgba[offset + 2] * scale, source.rgba[offset + 3] * scale};
      const Rgba backdropColor{backdrop.rgba[offset], backdrop.rgba[offset + 1],
                               backdrop.rgba[offset + 2], backdrop.rgba[offset + 3]};
      const Rgba result = clip ? reference::clipCompositePixel(mode, backdropColor, sourceColor)
                               : reference::compositePixel(mode, backdropColor, sourceColor);
      backdrop.rgba[offset] = result.red;
      backdrop.rgba[offset + 1] = result.green;
      backdrop.rgba[offset + 2] = result.blue;
      backdrop.rgba[offset + 3] = result.alpha;
    }
  };
  const Image empty(width, height);
  for (const PlanOperation& operation : plan.operations) {
    Image& level = levels[static_cast<size_t>(operation.level)];
    switch (operation.kind) {
      case PlanOperation::Kind::Push:
        level = Image(width, height);
        break;
      case PlanOperation::Kind::Draw: {
        auto found = surfaces.find(operation.layer);
        const Image& source = found == surfaces.end() ? empty : found->second;
        const Image* mask = nullptr;
        if (operation.mask) {
          auto maskFound = surfaces.find(operation.mask->id);
          mask = maskFound == surfaces.end() ? nullptr : &maskFound->second;
        }
        blend(level, source, operation.mode, operation.opacity, operation.clip, mask,
              operation.mask && operation.mask->inverted, operation.mask.has_value());
        break;
      }
      case PlanOperation::Kind::Pop:
        blend(level, levels[static_cast<size_t>(operation.level) + 1], operation.mode,
              operation.opacity, false, nullptr, false, false);
        break;
    }
  }
  return levels[0];
}

TEST(CompositePlanTest, FlatNormalLayersArePlainOverWithCaches) {
  LayerTree tree;
  tree.layers = {raster(1), raster(2), raster(3)};
  const CompositePlan plan = buildCompositePlan(tree, LayerId{2});
  EXPECT_TRUE(plan.allPlainOver);
  EXPECT_EQ(plan.levels, 1);
  EXPECT_EQ(plan.unitCount, 3);
  ASSERT_TRUE(plan.activeOperation);
  EXPECT_EQ(*plan.activeOperation, 1U);
  EXPECT_TRUE(plan.aboveIsOver);
}

TEST(CompositePlanTest, NonNormalLayerAboveInvalidatesTheAboveCache) {
  LayerTree tree;
  tree.layers = {raster(1), raster(2), raster(3, BlendMode::Multiply)};
  const CompositePlan plan = buildCompositePlan(tree, LayerId{1});
  EXPECT_FALSE(plan.allPlainOver);
  ASSERT_TRUE(plan.activeOperation);
  EXPECT_FALSE(plan.aboveIsOver);
  tree.layers = {raster(1), group(9, {raster(2, BlendMode::Screen)}, BlendMode::Normal)};
  EXPECT_TRUE(buildCompositePlan(tree, LayerId{1}).aboveIsOver);
}

TEST(CompositePlanTest, LayersInsideIsolatedGroupsOrClippingRunsHaveNoCaches) {
  LayerTree tree;
  LayerTreeNode clipped = raster(3);
  clipped.clipping = true;
  tree.layers = {group(9, {raster(1)}, BlendMode::Normal), raster(2), clipped};
  EXPECT_FALSE(buildCompositePlan(tree, LayerId{1}).activeOperation);
  EXPECT_FALSE(buildCompositePlan(tree, LayerId{2}).activeOperation);
  EXPECT_FALSE(buildCompositePlan(tree, LayerId{3}).activeOperation);
  tree.layers = {group(9, {raster(1), raster(2)}, BlendMode::PassThrough, 0.5F)};
  const CompositePlan plan = buildCompositePlan(tree, LayerId{2});
  ASSERT_TRUE(plan.activeOperation);
  EXPECT_FLOAT_EQ(plan.operations[*plan.activeOperation].opacity, 0.5F);
  EXPECT_EQ(plan.unitOfSurface.at(1), 0);
  EXPECT_EQ(plan.unitOfSurface.at(2), 1);
}

TEST(CompositePlanTest, NestedGroupsShareTheirRootUnit) {
  LayerTree tree;
  tree.layers = {raster(1),
                 group(9, {raster(2), group(8, {raster(3)}, BlendMode::Screen)}, BlendMode::Multiply),
                 raster(4)};
  const CompositePlan plan = buildCompositePlan(tree, std::nullopt);
  EXPECT_EQ(plan.levels, 3);
  EXPECT_EQ(plan.unitCount, 3);
  EXPECT_EQ(plan.unitOfSurface.at(2), 1);
  EXPECT_EQ(plan.unitOfSurface.at(3), 1);
  EXPECT_EQ(plan.unitOfSurface.at(4), 2);
  EXPECT_EQ(plan.surfaces, (std::vector<LayerId>{1, 2, 3, 4}));
}

TEST(CompositePlanTest, RandomTreesMatchTheReferenceCompositor) {
  std::mt19937 random(20261014);
  std::uniform_real_distribution<double> unit(0.0, 1.0);
  constexpr int32_t kWidth = 3;
  constexpr int32_t kHeight = 2;
  for (int iteration = 0; iteration < 200; ++iteration) {
    LayerId nextId = 1;
    std::map<LayerId, Image> surfaces;
    std::function<std::vector<LayerTreeNode>(int)> makeNodes = [&](int depth) {
      std::vector<LayerTreeNode> nodes;
      const int count = 1 + static_cast<int>(random() % 4);
      for (int index = 0; index < count; ++index) {
        if (depth < 3 && random() % 4 == 0) {
          const auto mode = random() % 3 == 0
                                ? BlendMode::PassThrough
                                : static_cast<BlendMode>(random() % kLayerBlendModeCount);
          LayerTreeNode node = group(nextId++, makeNodes(depth + 1), mode,
                                     static_cast<float>(0.3 + 0.7 * unit(random)));
          node.hidden = random() % 8 == 0;
          nodes.push_back(std::move(node));
          continue;
        }
        LayerTreeNode layer = raster(nextId++, static_cast<BlendMode>(random() % kLayerBlendModeCount),
                                     static_cast<float>(0.2 + 0.8 * unit(random)));
        layer.clipping = random() % 3 == 0;
        layer.hidden = random() % 10 == 0;
        Image pixels(kWidth, kHeight);
        for (size_t offset = 0; offset < pixels.rgba.size(); offset += 4) {
          const double alpha = random() % 4 == 0 ? 0.0 : unit(random);
          for (size_t channel = 0; channel < 3; ++channel) {
            pixels.rgba[offset + channel] = unit(random) * alpha;
          }
          pixels.rgba[offset + 3] = alpha;
        }
        surfaces[layer.id] = pixels;
        if (random() % 3 == 0) {
          layer.mask = LayerMaskInfo{nextId++, random() % 4 != 0, random() % 2 == 0};
          if (random() % 2 == 0) {
            Image mask(kWidth, kHeight);
            for (size_t offset = 0; offset < mask.rgba.size(); offset += 4) {
              const double value = unit(random);
              mask.rgba[offset] = mask.rgba[offset + 1] = mask.rgba[offset + 2] = value;
              mask.rgba[offset + 3] = 1;
            }
            surfaces[layer.mask->id] = mask;
          }
        }
        nodes.push_back(std::move(layer));
      }
      return nodes;
    };
    LayerTree tree;
    tree.layers = makeNodes(0);
    tree.background = {0.9F, 0.8F, 0.7F, 1};
    tree.backgroundHidden = random() % 4 == 0;
    const CompositePlan plan = buildCompositePlan(tree, std::nullopt);
    const Image expected = reference::composeLayerTree(
        tree, kWidth, kHeight, [&surfaces](LayerId id) -> const Image* {
          auto found = surfaces.find(id);
          return found == surfaces.end() ? nullptr : &found->second;
        });
    const Image actual = evaluatePlan(plan, tree, kWidth, kHeight, surfaces);
    for (size_t index = 0; index < expected.rgba.size(); ++index) {
      ASSERT_NEAR(actual.rgba[index], expected.rgba[index], 1e-6) << "iteration " << iteration;
    }
  }
}

TEST(CompositePlanTest, DrawsCarryTheLayerTint) {
  LayerTree tree;
  LayerTreeNode onion = raster(1);
  onion.tint = std::array<float, 4>{0.2F, 0.6F, 1, 1};
  tree.layers = {onion, raster(2)};
  const CompositePlan plan = buildCompositePlan(tree, 2);
  ASSERT_EQ(plan.operations.size(), 2U);
  EXPECT_EQ(plan.operations[0].tint, (std::array<float, 4>{0.2F, 0.6F, 1, 1}));
  EXPECT_EQ(plan.operations[1].tint[3], 0);
  EXPECT_TRUE(plan.allPlainOver);
}

}  // namespace
}  // namespace nanquim::canvas
