#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D tilePage;
layout(binding = 2) uniform highp sampler2D coverage;
layout(binding = 3) uniform highp sampler2D selectionPage;
UNIFORMS_BEGIN(CoverageFillParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(bool, hasTile)
  UNIFORM(bool, hasSelection)
  UNIFORM(ivec2, selectionSlotOrigin)
  UNIFORM(vec4, color)
  UNIFORM(bool, maskTarget)
  UNIFORM(int, operation)
  UNIFORM(bool, linearDocument)
UNIFORMS_END

// @color_space

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy);
  vec4 existing = hasTile ? texelFetch(tilePage, slotOrigin + local, 0)
                          : (maskTarget ? vec4(1.0) : vec4(0.0));
  float amount = texelFetch(coverage, local, 0).r;
  if (hasSelection) {
    amount *= texelFetch(selectionPage, selectionSlotOrigin + local, 0).r;
  }
  if (operation == 1) {
    fragmentColor = maskTarget ? vec4(vec3(mix(existing.r, 1.0, amount)), 1.0)
                               : existing * (1.0 - amount);
  } else if (operation == 2) {
    vec4 encoded = linearDocument && !maskTarget ? premultipliedLinearToSrgb(existing) : existing;
    vec4 inverted = maskTarget ? vec4(vec3(1.0 - existing.r), 1.0)
                               : vec4(vec3(encoded.a) - encoded.rgb, encoded.a);
    if (linearDocument && !maskTarget) {
      inverted = premultipliedSrgbToLinear(inverted);
    }
    fragmentColor = mix(existing, inverted, amount);
  } else if (maskTarget) {
    float luminance = 0.3 * color.r + 0.59 * color.g + 0.11 * color.b;
    fragmentColor = vec4(vec3(mix(existing.r, luminance, amount)), 1.0);
  } else {
    vec4 source = vec4(color.rgb * color.a, color.a) * amount;
    fragmentColor = source + existing * (1.0 - source.a);
  }
}
