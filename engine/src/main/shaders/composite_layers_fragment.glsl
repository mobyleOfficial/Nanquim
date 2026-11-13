#version 310 es
precision highp float;
precision highp int;

// 15, not 16: the array starts at unit 1 (unit 0 is the upload unit), so 16 layers would
// need unit 16 and 16 fragment samplers, the bare ES 3.1 minimum. The Samsung Xclipse GLES
// compiler crashes on that shader; Compositor::kMaxLayersPerPass must match.
const int kMaxLayers = 15;

layout(binding = 1) uniform highp sampler2D atlasPages[kMaxLayers];

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
  if (layerCount > 0) result = over(tinted(texelFetch(atlasPages[0], layerSlotOrigin[0] + local, 0), layerTint[0]) * layerOpacity[0], result);
  if (layerCount > 1) result = over(tinted(texelFetch(atlasPages[1], layerSlotOrigin[1] + local, 0), layerTint[1]) * layerOpacity[1], result);
  if (layerCount > 2) result = over(tinted(texelFetch(atlasPages[2], layerSlotOrigin[2] + local, 0), layerTint[2]) * layerOpacity[2], result);
  if (layerCount > 3) result = over(tinted(texelFetch(atlasPages[3], layerSlotOrigin[3] + local, 0), layerTint[3]) * layerOpacity[3], result);
  if (layerCount > 4) result = over(tinted(texelFetch(atlasPages[4], layerSlotOrigin[4] + local, 0), layerTint[4]) * layerOpacity[4], result);
  if (layerCount > 5) result = over(tinted(texelFetch(atlasPages[5], layerSlotOrigin[5] + local, 0), layerTint[5]) * layerOpacity[5], result);
  if (layerCount > 6) result = over(tinted(texelFetch(atlasPages[6], layerSlotOrigin[6] + local, 0), layerTint[6]) * layerOpacity[6], result);
  if (layerCount > 7) result = over(tinted(texelFetch(atlasPages[7], layerSlotOrigin[7] + local, 0), layerTint[7]) * layerOpacity[7], result);
  if (layerCount > 8) result = over(tinted(texelFetch(atlasPages[8], layerSlotOrigin[8] + local, 0), layerTint[8]) * layerOpacity[8], result);
  if (layerCount > 9) result = over(tinted(texelFetch(atlasPages[9], layerSlotOrigin[9] + local, 0), layerTint[9]) * layerOpacity[9], result);
  if (layerCount > 10) result = over(tinted(texelFetch(atlasPages[10], layerSlotOrigin[10] + local, 0), layerTint[10]) * layerOpacity[10], result);
  if (layerCount > 11) result = over(tinted(texelFetch(atlasPages[11], layerSlotOrigin[11] + local, 0), layerTint[11]) * layerOpacity[11], result);
  if (layerCount > 12) result = over(tinted(texelFetch(atlasPages[12], layerSlotOrigin[12] + local, 0), layerTint[12]) * layerOpacity[12], result);
  if (layerCount > 13) result = over(tinted(texelFetch(atlasPages[13], layerSlotOrigin[13] + local, 0), layerTint[13]) * layerOpacity[13], result);
  if (layerCount > 14) result = over(tinted(texelFetch(atlasPages[14], layerSlotOrigin[14] + local, 0), layerTint[14]) * layerOpacity[14], result);
  fragmentColor = result;
}
