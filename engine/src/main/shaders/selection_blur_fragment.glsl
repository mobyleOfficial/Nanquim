#version 310 es
precision highp float;
precision highp int;

const int kMaximumHalfWidth = 100;

layout(binding = 1) uniform highp sampler2D pages[3];

UNIFORMS_BEGIN(SelectionBlurParameters)
  UNIFORM_ARRAY(ivec2, slotOrigins, 3)
  UNIFORM_ARRAY(bool, hasTiles, 3)
  UNIFORM(bool, horizontal)
  UNIFORM(ivec2, tileOrigin)
  UNIFORM(ivec2, canvasSize)
  UNIFORM(int, halfWidth)
  UNIFORM_ARRAY(float, weights, 101)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

float valueAt(int position, ivec2 local) {
  int tile = position < 0 ? 0 : (position >= 256 ? 2 : 1);
  int inTile = position - (tile - 1) * 256;
  ivec2 texel = horizontal ? ivec2(inTile, local.y) : ivec2(local.x, inTile);
  ivec2 canvasPixel = tileOrigin + (horizontal ? ivec2(position, local.y) : ivec2(local.x, position));
  if (any(lessThan(canvasPixel, ivec2(0))) || any(greaterThanEqual(canvasPixel, canvasSize))) {
    return 0.0;
  }
  if (tile == 0) {
    return hasTiles[0] ? texelFetch(pages[0], slotOrigins[0] + texel, 0).r : 0.0;
  }
  if (tile == 1) {
    return hasTiles[1] ? texelFetch(pages[1], slotOrigins[1] + texel, 0).r : 0.0;
  }
  return hasTiles[2] ? texelFetch(pages[2], slotOrigins[2] + texel, 0).r : 0.0;
}

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy);
  int center = horizontal ? local.x : local.y;
  float sum = weights[0] * valueAt(center, local);
  for (int offset = 1; offset <= halfWidth; ++offset) {
    sum += weights[offset] * (valueAt(center - offset, local) + valueAt(center + offset, local));
  }
  fragmentColor = vec4(vec3(sum), 1.0);
}
