#version 310 es
precision highp float;
precision highp int;

UNIFORMS_BEGIN(SelectionReduceParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(ivec2, strokeSlotOrigin)
  UNIFORM(ivec2, regionOrigin)
  UNIFORM(float, strokeOpacity)
  UNIFORM(float, strokeLuminance)
  UNIFORM(int, reduction)
  UNIFORM(bool, hasTile)
  UNIFORM(bool, hasStrokeTile)
UNIFORMS_END

layout(binding = 1) uniform highp sampler2D tilePage;
layout(binding = 2) uniform highp sampler2D strokePage;

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 first = (ivec2(gl_FragCoord.xy) - regionOrigin) * reduction;
  float sum = 0.0;
  for (int y = 0; y < reduction; ++y) {
    for (int x = 0; x < reduction; ++x) {
      ivec2 local = first + ivec2(x, y);
      float value = hasTile ? texelFetch(tilePage, slotOrigin + local, 0).r : 0.0;
      if (hasStrokeTile) {
        float coverage = texelFetch(strokePage, strokeSlotOrigin + local, 0).a * strokeOpacity;
        value = mix(value, strokeLuminance, coverage);
      }
      sum += value;
    }
  }
  fragmentColor = vec4(sum / float(reduction * reduction), 0.0, 0.0, 1.0);
}
