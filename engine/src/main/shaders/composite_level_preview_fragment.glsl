#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D belowCache;
layout(binding = 2) uniform highp sampler2D aboveCache;
layout(binding = 3) uniform highp sampler2D activeLayer;

UNIFORMS_BEGIN(CompositeLevelPreviewParameters)
  UNIFORM(int, sampleStride)
  UNIFORM(float, layerOpacity)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 levelPixel = ivec2(gl_FragCoord.xy);
  ivec2 canvasPixel = levelPixel * sampleStride;
  vec4 below = texelFetch(belowCache, canvasPixel, 0);
  vec4 layer = texelFetch(activeLayer, levelPixel, 0) * layerOpacity;
  vec4 result = layer + below * (1.0 - layer.a);
  vec4 above = texelFetch(aboveCache, canvasPixel, 0);
  fragmentColor = above + result * (1.0 - above.a);
}
