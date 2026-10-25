#pragma once

#include <array>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "canvas/compositor.h"
#include "core/affine_transform.h"
#include "gpu/rhi/rhi.h"

namespace nanquim::canvas {

struct SelectionDisplay {
  const gpu::rhi::Texture* texture = nullptr;
  int32_t reduction = 1;
  int32_t widthTexels = 0;
  int32_t heightTexels = 0;
  bool dimOutside = false;
  float antsPhasePixels = 0;
};

struct ViewportStyle {
  std::array<float, 3> surroundColor{0.16F, 0.16F, 0.16F};
  std::array<float, 4> shadowColor{0.0F, 0.0F, 0.0F, 0.35F};
  float shadowRadiusPixels = 14.0F;
  bool showPixels = false;
};

class ViewportRenderer {
 public:
  bool initialize(gpu::rhi::Device& device, std::string& errorMessage);

  void render(const Compositor& compositor, const core::AffineTransform& canvasToScreen,
              const gpu::rhi::RenderTarget& target, const ViewportStyle& style,
              const gpu::rhi::Texture* dirtyAges, std::optional<int32_t> levelOverride,
              std::optional<std::array<int32_t, 4>> screenRegion = std::nullopt,
              std::optional<SelectionDisplay> selection = std::nullopt);

  void setDisplayLut(const std::vector<uint16_t>& lut);

 private:
  gpu::rhi::Device* device_ = nullptr;
  const gpu::rhi::Pipeline* pipeline_ = nullptr;
  std::unique_ptr<gpu::rhi::Sampler> linearSampler_;
  std::unique_ptr<gpu::rhi::Sampler> nearestSampler_;
  std::unique_ptr<gpu::rhi::Texture> displayLut_;
  bool hasDisplayLut_ = false;
};

}  // namespace nanquim::canvas
