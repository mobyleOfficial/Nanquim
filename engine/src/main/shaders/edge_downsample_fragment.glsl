#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D strokePage;

UNIFORMS_BEGIN(EdgeDownsampleParameters)
  UNIFORM(ivec2, strokeSlotOrigin)
  UNIFORM(ivec2, targetOrigin)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

const int kScale = 4;

void main() {
  ivec2 block = (ivec2(gl_FragCoord.xy) - targetOrigin) * kScale + strokeSlotOrigin;
  float sum = 0.0;
  for (int y = 0; y < kScale; ++y) {
    for (int x = 0; x < kScale; ++x) {
      sum += texelFetch(strokePage, block + ivec2(x, y), 0).a;
    }
  }
  fragmentColor = vec4(sum / float(kScale * kScale), 0.0, 0.0, 1.0);
}
