#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace nanquim::canvas {

struct MeshVertex {
  float x = 0;
  float y = 0;
  float w = 1;
  float u = 0;
  float v = 0;
};

enum class TransformFilter : int32_t { Nearest = 0, Bilinear = 1, Bicubic = 2 };

struct ContentAnalysis {
  std::array<int32_t, 4> bounds{0, 0, 0, 0};
  double centroidX = 0;
  double centroidY = 0;
  double principalAngle = 0;
};

ContentAnalysis analyzeContent(const uint8_t* pixels, int32_t width, int32_t height,
                               int32_t originX, int32_t originY);

std::array<float, 4> meshBounds(const std::vector<MeshVertex>& triangles);

std::array<double, 4> sampleSource(const std::vector<double>& source, int32_t width, int32_t height,
                                   double u, double v, TransformFilter filter);

void rasterizeMesh(const std::vector<MeshVertex>& triangles, const std::vector<double>& source,
                   std::vector<double>& target, int32_t width, int32_t height,
                   TransformFilter filter);

}  // namespace nanquim::canvas
