#version 310 es
precision highp float;
precision highp int;

// One sampler per layer, not an array: the Samsung Xclipse GLES compiler (ANGLE on Vulkan)
// crashes on any sampler2D array. Units 1-15 (unit 0 is the upload unit), so
// Compositor::kMaxLayersPerPass must match the count here.
layout(binding = 1) uniform highp sampler2D atlasPage0;
layout(binding = 2) uniform highp sampler2D atlasPage1;
layout(binding = 3) uniform highp sampler2D atlasPage2;
layout(binding = 4) uniform highp sampler2D atlasPage3;
layout(binding = 5) uniform highp sampler2D atlasPage4;
layout(binding = 6) uniform highp sampler2D atlasPage5;
layout(binding = 7) uniform highp sampler2D atlasPage6;
layout(binding = 8) uniform highp sampler2D atlasPage7;
layout(binding = 9) uniform highp sampler2D atlasPage8;
layout(binding = 10) uniform highp sampler2D atlasPage9;
layout(binding = 11) uniform highp sampler2D atlasPage10;
layout(binding = 12) uniform highp sampler2D atlasPage11;
layout(binding = 13) uniform highp sampler2D atlasPage12;
layout(binding = 14) uniform highp sampler2D atlasPage13;
layout(binding = 15) uniform highp sampler2D atlasPage14;

UNIFORMS_BEGIN(CompositeLayersParameters)
  UNIFORM(int, layerCount)
  UNIFORM_ARRAY(ivec2, layerSlotOrigin, 15)
  UNIFORM_ARRAY(float, layerOpacity, 15)
  UNIFORM_ARRAY(vec4, layerTint, 15)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(int, sampleStride)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

vec4 tinted(vec4 color, vec4 tint) {
  return vec4(mix(color.rgb, tint.rgb * color.a, tint.a), color.a);
}

vec4 over(vec4 source, vec4 destination) {
  return source + destination * (1.0 - source.a);
}

void main() {
  ivec2 local = (ivec2(gl_FragCoord.xy) - targetOrigin) * sampleStride;
  vec4 result = vec4(0.0);
  if (layerCount > 0) result = over(tinted(texelFetch(atlasPage0, layerSlotOrigin[0] + local, 0), layerTint[0]) * layerOpacity[0], result);
  if (layerCount > 1) result = over(tinted(texelFetch(atlasPage1, layerSlotOrigin[1] + local, 0), layerTint[1]) * layerOpacity[1], result);
  if (layerCount > 2) result = over(tinted(texelFetch(atlasPage2, layerSlotOrigin[2] + local, 0), layerTint[2]) * layerOpacity[2], result);
  if (layerCount > 3) result = over(tinted(texelFetch(atlasPage3, layerSlotOrigin[3] + local, 0), layerTint[3]) * layerOpacity[3], result);
  if (layerCount > 4) result = over(tinted(texelFetch(atlasPage4, layerSlotOrigin[4] + local, 0), layerTint[4]) * layerOpacity[4], result);
  if (layerCount > 5) result = over(tinted(texelFetch(atlasPage5, layerSlotOrigin[5] + local, 0), layerTint[5]) * layerOpacity[5], result);
  if (layerCount > 6) result = over(tinted(texelFetch(atlasPage6, layerSlotOrigin[6] + local, 0), layerTint[6]) * layerOpacity[6], result);
  if (layerCount > 7) result = over(tinted(texelFetch(atlasPage7, layerSlotOrigin[7] + local, 0), layerTint[7]) * layerOpacity[7], result);
  if (layerCount > 8) result = over(tinted(texelFetch(atlasPage8, layerSlotOrigin[8] + local, 0), layerTint[8]) * layerOpacity[8], result);
  if (layerCount > 9) result = over(tinted(texelFetch(atlasPage9, layerSlotOrigin[9] + local, 0), layerTint[9]) * layerOpacity[9], result);
  if (layerCount > 10) result = over(tinted(texelFetch(atlasPage10, layerSlotOrigin[10] + local, 0), layerTint[10]) * layerOpacity[10], result);
  if (layerCount > 11) result = over(tinted(texelFetch(atlasPage11, layerSlotOrigin[11] + local, 0), layerTint[11]) * layerOpacity[11], result);
  if (layerCount > 12) result = over(tinted(texelFetch(atlasPage12, layerSlotOrigin[12] + local, 0), layerTint[12]) * layerOpacity[12], result);
  if (layerCount > 13) result = over(tinted(texelFetch(atlasPage13, layerSlotOrigin[13] + local, 0), layerTint[13]) * layerOpacity[13], result);
  if (layerCount > 14) result = over(tinted(texelFetch(atlasPage14, layerSlotOrigin[14] + local, 0), layerTint[14]) * layerOpacity[14], result);
  fragmentColor = result;
}
