#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D source;

UNIFORMS_BEGIN(EdgeBlurParameters)
  UNIFORM(ivec2, sourceSize)
  UNIFORM(ivec2, direction)
  UNIFORM(int, radius)
  UNIFORM_ARRAY(float, weights, 13)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 texel = ivec2(gl_FragCoord.xy);
  float sum = weights[0] * texelFetch(source, texel, 0).r;
  for (int tap = 1; tap <= radius; ++tap) {
    ivec2 before = texel - direction * tap;
    ivec2 after = texel + direction * tap;
    if (all(greaterThanEqual(before, ivec2(0)))) {
      sum += weights[tap] * texelFetch(source, before, 0).r;
    }
    if (all(lessThan(after, sourceSize))) {
      sum += weights[tap] * texelFetch(source, after, 0).r;
    }
  }
  fragmentColor = vec4(sum, 0.0, 0.0, 1.0);
}
