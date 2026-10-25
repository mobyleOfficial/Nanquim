#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D surfacePage;
layout(binding = 2) uniform highp sampler2D strokePage;
UNIFORMS_BEGIN(CompositeActiveStrokeParameters)
  UNIFORM(bool, hasSurfaceTile)
  UNIFORM(ivec2, surfaceSlotOrigin)
  UNIFORM(ivec2, strokeSlotOrigin)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(float, strokeOpacity)
  UNIFORM(float, layerOpacity)
  UNIFORM(int, brushBlendMode)
  UNIFORM(bool, alphaLock)
  UNIFORM(float, maskLuminance)
  UNIFORM(bool, hasSelectionTile)
  UNIFORM(ivec2, selectionSlotOrigin)
  UNIFORM(bool, hasDualTile)
  UNIFORM(ivec2, dualSlotOrigin)
  UNIFORM(int, dualBlend)
  UNIFORM(vec3, dualColor)
  UNIFORM(bool, hasParticleTile)
  UNIFORM(ivec2, particleSlotOrigin)
  UNIFORM(ivec2, workingOrigin)
  UNIFORM(ivec2, edgeMapSize)
  UNIFORM(ivec2, tileCanvasOrigin)
  UNIFORM(float, wetEdges)
  UNIFORM(float, burntEdges)
  UNIFORM(int, burntBlendMode)
UNIFORMS_END

#ifdef SELECTION
layout(binding = 3) uniform highp sampler2D selectionPage;
#endif
#ifdef DUAL
layout(binding = 6) uniform highp sampler2D dualPage;
#endif
#ifdef PARTICLES
layout(binding = 7) uniform highp sampler2D particlePage;
#endif
#ifdef WORKING
layout(binding = 5) uniform highp usampler2D workingCopy;
#endif
#ifdef STROKE_EDGES
layout(binding = 4) uniform highp sampler2D edgeMap;
#endif

layout(location = 0) out vec4 fragmentColor;

// @blend_functions

#ifdef STROKE_GAMMA
vec3 linearToSrgb(vec3 color) {
  return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055,
             greaterThan(color, vec3(0.0031308)));
}
#endif

#ifdef DUAL
float dualCoverage(int blend, float c, float g) {
  c = clamp(c, 0.0, 1.0);
  g = clamp(g, 0.0, 1.0);
  if (blend == 1) return max(0.0, c - (1.0 - g));
  if (blend == 2) return min(c, g);
  if (blend == 3) return max(c, g);
  if (blend == 4) return c < 0.5 ? 2.0 * c * g : 1.0 - 2.0 * (1.0 - c) * (1.0 - g);
  if (blend == 5) return clamp((c - (1.0 - g)) / 0.01, 0.0, 1.0);
  return c * g;
}
#endif

#ifdef STROKE_EDGES
float blurredCoverage(ivec2 canvasPixel) {
  vec2 position = (vec2(canvasPixel) + 0.5) / 4.0 - 0.5;
  vec2 base = floor(position);
  vec2 fraction = position - base;
  ivec2 first = clamp(ivec2(base), ivec2(0), edgeMapSize - 1);
  ivec2 second = clamp(ivec2(base) + 1, ivec2(0), edgeMapSize - 1);
  float top = mix(texelFetch(edgeMap, ivec2(first.x, first.y), 0).r,
                  texelFetch(edgeMap, ivec2(second.x, first.y), 0).r, fraction.x);
  float bottom = mix(texelFetch(edgeMap, ivec2(first.x, second.y), 0).r,
                     texelFetch(edgeMap, ivec2(second.x, second.y), 0).r, fraction.x);
  return mix(top, bottom, fraction.y);
}
#endif

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy) - targetOrigin;
#if defined(SELECTION_TARGET)
  vec4 surface = hasSurfaceTile ? texelFetch(surfacePage, surfaceSlotOrigin + local, 0)
                                : vec4(0.0, 0.0, 0.0, 1.0);
#elif defined(MASK_TARGET)
  vec4 surface = hasSurfaceTile ? texelFetch(surfacePage, surfaceSlotOrigin + local, 0) : vec4(1.0);
#else
  vec4 surface = hasSurfaceTile ? texelFetch(surfacePage, surfaceSlotOrigin + local, 0) : vec4(0.0);
#endif
#ifdef WORKING
#ifdef WORKING_HALF
  ivec2 workingPixel = workingOrigin + local;
  vec4 working = vec4(
      unpackHalf2x16(texelFetch(workingCopy, ivec2(workingPixel.x * 2, workingPixel.y), 0).r),
      unpackHalf2x16(texelFetch(workingCopy, ivec2(workingPixel.x * 2 + 1, workingPixel.y), 0).r));
#else
  vec4 working = unpackUnorm4x8(texelFetch(workingCopy, workingOrigin + local, 0).r);
#endif
  float selected = 1.0;
#ifdef SELECTION
  selected = hasSelectionTile ? texelFetch(selectionPage, selectionSlotOrigin + local, 0).r : 0.0;
#endif
  vec4 replaced = mix(surface, working, selected);
  if (alphaLock) {
    replaced = replaced.a > 0.0 ? replaced * (surface.a / replaced.a) : vec4(0.0);
  }
  fragmentColor = replaced * layerOpacity;
  return;
#endif
  vec4 stroke = texelFetch(strokePage, strokeSlotOrigin + local, 0);
#ifdef STROKE_GAMMA
  if (stroke.a > 0.0) {
    stroke.rgb = linearToSrgb(stroke.rgb / stroke.a) * stroke.a;
  }
#endif
#ifdef DUAL
  float secondary = hasDualTile ? texelFetch(dualPage, dualSlotOrigin + local, 0).a : 0.0;
  float combined = dualCoverage(dualBlend, stroke.a, secondary);
  stroke = stroke.a > 0.0 ? stroke * (combined / stroke.a) : vec4(dualColor * combined, combined);
#endif
#ifdef PARTICLES
  if (hasParticleTile) {
    vec4 particle = texelFetch(particlePage, particleSlotOrigin + local, 0);
    stroke = particle + stroke * (1.0 - particle.a);
  }
#endif
#ifdef STROKE_EDGES
  if (stroke.a > 0.0) {
    float edge = clamp(1.0 - blurredCoverage(tileCanvasOrigin + local) / stroke.a, 0.0, 1.0);
    vec3 color = stroke.rgb / stroke.a;
    if (burntEdges > 0.0) {
      color = mix(color, blendColor(burntBlendMode, color, color * 0.5), burntEdges * edge);
    }
    float alpha = stroke.a * mix(1.0, 0.6 + 0.4 * edge, wetEdges);
    stroke = vec4(color * alpha, alpha);
  }
#endif
#ifdef SELECTION
  stroke *= hasSelectionTile ? texelFetch(selectionPage, selectionSlotOrigin + local, 0).r : 0.0;
#endif
  float coverage = stroke.a * strokeOpacity;
#if defined(MASK_TARGET)
  vec4 result = vec4(mix(surface.rgb, vec3(maskLuminance), coverage), 1.0);
#elif defined(ERASE)
  vec4 result = alphaLock ? surface : surface * (1.0 - coverage);
#else
  vec4 result = blendComposite(brushBlendMode, surface, stroke * strokeOpacity);
#ifdef LUMINANCE_BLEND
  if (surface.a > 0.0 && result.a > 0.0) {
    result.rgb =
        blendColor(kBlendLuminosity, surface.rgb / surface.a, result.rgb / result.a) * result.a;
  }
#endif
  if (alphaLock) {
    result = result.a > 0.0 ? result * (surface.a / result.a) : vec4(0.0);
  }
#endif
  fragmentColor = result * layerOpacity;
}
