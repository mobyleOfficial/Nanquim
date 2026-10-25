#version 310 es
#ifdef GLAZE
#extension GL_EXT_shader_framebuffer_fetch : require
#endif
precision highp float;
precision highp int;

// @stroke_dab_uniforms

layout(location = 0) in vec2 canvasPosition;
layout(location = 1) flat in vec4 geometry;
layout(location = 2) flat in vec4 shape;
layout(location = 3) flat in vec4 color;
layout(location = 4) flat in vec4 grain;
layout(location = 5) flat in vec4 gradation;

// @dab_coverage

#ifdef CLONE
layout(binding = 3) uniform highp sampler2D cloneSource;
#endif

#ifdef LAYER_MIX
layout(binding = 4) uniform highp sampler2D layerSource;
#endif

#ifdef GLAZE
inout highp vec4 fragmentColor;
#else
layout(location = 0) out vec4 fragmentColor;
#endif

#if defined(GAMMA) || defined(LINEAR_DOCUMENT)
vec3 srgbToLinear(vec3 color) {
  return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
             greaterThan(color, vec3(0.04045)));
}
#endif

#ifdef GLAZE
// @stroke_glaze
#endif

void main() {
  float coverage = dabTipCoverage(canvasPosition);
  vec3 baseColor = color.rgb;
#ifdef SHAPE_COLOR
  vec3 tipColor;
  if (coverage > 0.0 && dabTipColor(canvasPosition, tipColor)) {
#ifdef LINEAR_DOCUMENT
    baseColor = srgbToLinear(tipColor);
#else
    baseColor = tipColor;
#endif
  }
#endif
#ifdef GLAZE
  fragmentColor = glaze(fragmentColor, coverage, baseColor);
  return;
#endif
  coverage *= clamp(shape.y, 0.0, 1.0);
#ifdef CLONE
  ivec2 source = ivec2(floor(canvasPosition)) + cloneOffset;
  vec4 sampled = any(lessThan(source, ivec2(0))) || any(greaterThanEqual(source, cloneSize))
                     ? vec4(0.0)
                     : texelFetch(cloneSource, source, 0);
  fragmentColor = sampled * coverage;
#else
  vec3 dabColor = baseColor;
#ifdef GAMMA
  dabColor = srgbToLinear(dabColor);
#endif
#ifdef LAYER_MIX
  ivec2 pixel = ivec2(floor(canvasPosition));
  if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, layerSize))) {
    vec4 under = texelFetch(layerSource, pixel, 0);
    if (under.a > 0.0) {
      vec3 underColor = under.rgb / under.a;
#ifdef GAMMA
      underColor = srgbToLinear(underColor);
#endif
      dabColor = mix(dabColor, underColor, layerMixAmount * under.a);
    }
  }
#endif
  fragmentColor = vec4(dabColor * coverage, coverage);
#endif
}
