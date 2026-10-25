#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D tilePage;

UNIFORMS_BEGIN(InvertTileParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(bool, hasTile)
  UNIFORM(int, mode)
  UNIFORM(bool, linearDocument)
UNIFORMS_END

// @color_space

layout(location = 0) out vec4 fragmentColor;

void main() {
  vec4 color = hasTile ? texelFetch(tilePage, slotOrigin + ivec2(gl_FragCoord.xy), 0)
                       : (mode == 1 ? vec4(1.0) : vec4(0.0, 0.0, 0.0, mode == 2 ? 1.0 : 0.0));
  if (mode == 0) {
    vec4 encoded = linearDocument ? premultipliedLinearToSrgb(color) : color;
    vec4 inverted = vec4(vec3(encoded.a) - encoded.rgb, encoded.a);
    fragmentColor = linearDocument ? premultipliedSrgbToLinear(inverted) : inverted;
  } else if (mode == 3) {
    fragmentColor = vec4(vec3(color.a), 1.0);
  } else {
    fragmentColor = vec4(vec3(1.0) - color.rgb, 1.0);
  }
}
