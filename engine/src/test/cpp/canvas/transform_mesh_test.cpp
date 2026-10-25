#include "canvas/transform_mesh.h"

#include <gtest/gtest.h>

#include <cmath>

namespace nanquim::canvas {
namespace {

std::vector<MeshVertex> quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1,
                             float v1) {
  return {{x0, y0, 1, u0, v0}, {x1, y0, 1, u1, v0}, {x1, y1, 1, u1, v1},
          {x0, y0, 1, u0, v0}, {x1, y1, 1, u1, v1}, {x0, y1, 1, u0, v1}};
}

TEST(TransformMesh, AnalysisFindsBoundsAndAxis) {
  std::vector<uint8_t> pixels(64 * 32 * 4, 0);
  for (int32_t step = 0; step <= 20; ++step) {
    pixels[(static_cast<size_t>(5 + step) * 64 + 10 + step) * 4 + 3] = 255;
  }
  const ContentAnalysis analysis = analyzeContent(pixels.data(), 64, 32, 100, 200);
  EXPECT_EQ(analysis.bounds, (std::array<int32_t, 4>{110, 205, 21, 21}));
  EXPECT_NEAR(analysis.centroidX, 120.5, 1e-9);
  EXPECT_NEAR(analysis.principalAngle, std::atan(1.0), 1e-9);
  const std::vector<uint8_t> empty(16, 0);
  EXPECT_EQ(analyzeContent(empty.data(), 2, 2, 0, 0).bounds[2], 0);
}

TEST(TransformMesh, IdentityAndTranslationAreExact) {
  const int32_t size = 16;
  std::vector<double> source(size * size * 4, 0);
  for (int32_t y = 4; y < 8; ++y) {
    for (int32_t x = 4; x < 8; ++x) {
      double* pixel = &source[(static_cast<size_t>(y) * size + x) * 4];
      pixel[0] = 0.5;
      pixel[3] = 1;
    }
  }
  for (const TransformFilter filter :
       {TransformFilter::Nearest, TransformFilter::Bilinear, TransformFilter::Bicubic}) {
    std::vector<double> target(source.size(), 0);
    rasterizeMesh(quad(3, 2, 19, 18, 0, 0, 16, 16), source, target, size, size, filter);
    EXPECT_NEAR(target[(static_cast<size_t>(6) * size + 7) * 4 + 3], 1.0, 1e-9);
    EXPECT_NEAR(target[(static_cast<size_t>(6) * size + 7) * 4], 0.5, 1e-9);
    EXPECT_NEAR(target[(static_cast<size_t>(5) * size + 6) * 4 + 3], 0.0, 1e-9);
  }
}

TEST(TransformMesh, BilinearHalfPixelShiftAverages) {
  std::vector<double> source(4 * 1 * 4, 0);
  source[1 * 4 + 3] = 1;
  std::vector<double> target(source.size(), 0);
  rasterizeMesh(quad(0.5F, 0, 4.5F, 1, 0, 0, 4, 1), source, target, 4, 1,
                TransformFilter::Bilinear);
  EXPECT_NEAR(target[1 * 4 + 3], 0.5, 1e-9);
  EXPECT_NEAR(target[2 * 4 + 3], 0.5, 1e-9);
}

TEST(TransformMesh, PerspectiveWeightsInterpolateTheSource) {
  const auto sample = [](float weight) {
    std::vector<MeshVertex> mesh = quad(0, 0, 8, 8, 0, 0, 8, 8);
    for (MeshVertex& vertex : mesh) {
      vertex.w = weight;
    }
    std::vector<double> source(8 * 8 * 4, 0);
    for (size_t index = 3; index < source.size(); index += 4) {
      source[index] = static_cast<double>(index) / static_cast<double>(source.size());
    }
    std::vector<double> target(source.size(), 0);
    rasterizeMesh(mesh, source, target, 8, 8, TransformFilter::Nearest);
    return target;
  };
  EXPECT_EQ(sample(1), sample(2));
}

}  // namespace
}  // namespace nanquim::canvas
