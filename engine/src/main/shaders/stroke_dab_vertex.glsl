#version 310 es
precision highp float;

layout(location = 0) in vec4 dabGeometry;
layout(location = 1) in vec4 dabShape;
layout(location = 2) in vec4 dabColor;
layout(location = 3) in vec4 dabGrain;
layout(location = 4) in vec4 dabGradation;

// @stroke_dab_uniforms

layout(location = 0) out vec2 canvasPosition;
layout(location = 1) flat out vec4 geometry;
layout(location = 2) flat out vec4 shape;
layout(location = 3) flat out vec4 color;
layout(location = 4) flat out vec4 grain;
layout(location = 5) flat out vec4 gradation;

const float kTileSize = 256.0;

void main() {
  vec2 corner = vec2(float(VERTEX_INDEX & 1), float((VERTEX_INDEX >> 1) & 1)) * 2.0 - 1.0;
  float extent = dabGeometry.z * extentScale + 1.0;
  canvasPosition = dabGeometry.xy + corner * extent;
  vec2 local = (canvasPosition - tileOrigin) / kTileSize;
  gl_Position = vec4(local * 2.0 - 1.0, 0.0, 1.0);
  geometry = dabGeometry;
  shape = dabShape;
  color = dabColor;
  grain = dabGrain;
  gradation = dabGradation;
}
