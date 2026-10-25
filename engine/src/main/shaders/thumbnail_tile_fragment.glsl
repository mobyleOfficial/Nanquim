#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D atlasPage;

UNIFORMS_BEGIN(ThumbnailTileParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(int, sampleStride)
  UNIFORM(bool, encode)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

// @color_space

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy) - targetOrigin;
  vec4 color = texelFetch(atlasPage, slotOrigin + local * sampleStride, 0);
  fragmentColor = encode ? premultipliedLinearToSrgb(color) : color;
}
