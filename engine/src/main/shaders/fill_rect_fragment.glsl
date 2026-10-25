#version 310 es
precision highp float;
precision highp int;

UNIFORMS_BEGIN(FillRectParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(ivec2, tileCanvasOrigin)
  UNIFORM(vec4, rect)
  UNIFORM(vec4, color)
  UNIFORM(vec4, toColor)
  UNIFORM(bool, gradient)
  UNIFORM(bool, vertical)
  UNIFORM(bool, maskTarget)
  UNIFORM(bool, linearDocument)
UNIFORMS_END

// @color_space

layout(location = 0) out vec4 fragmentColor;

void main() {
  vec2 center = vec2(tileCanvasOrigin + ivec2(gl_FragCoord.xy) - slotOrigin) + 0.5;
  float position = vertical ? (center.y - rect.y) / rect.w : (center.x - rect.x) / rect.z;
  vec4 value = gradient ? mix(color, toColor, clamp(position, 0.0, 1.0)) : color;
  if (maskTarget) {
    float luminance = 0.3 * value.r + 0.59 * value.g + 0.11 * value.b;
    fragmentColor = vec4(vec3(luminance), 1.0);
  } else {
    vec3 rgb = linearDocument ? srgbToLinear(value.rgb) : value.rgb;
    fragmentColor = vec4(rgb * value.a, value.a);
  }
}
