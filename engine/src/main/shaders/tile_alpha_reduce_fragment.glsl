#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D source;

UNIFORMS_BEGIN(TileAlphaReduceParameters)
  UNIFORM(ivec2, sourceOrigin)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(bool, alphaChannel)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

const int kBlock = 4;

void main() {
  ivec2 first = sourceOrigin + (ivec2(gl_FragCoord.xy) - targetOrigin) * kBlock;
  float maximum = 0.0;
  for (int y = 0; y < kBlock; ++y) {
    for (int x = 0; x < kBlock; ++x) {
      vec4 texel = texelFetch(source, first + ivec2(x, y), 0);
      maximum = max(maximum, alphaChannel ? texel.a : texel.r);
    }
  }
  fragmentColor = vec4(maximum, 0.0, 0.0, 1.0);
}
