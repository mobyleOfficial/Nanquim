#version 310 es
precision highp float;
precision highp int;

layout(local_size_x = 16, local_size_y = 16) in;

UNIFORMS_BEGIN(StrokeGlazeParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(vec2, tileOrigin)
  UNIFORM(ivec2, canvasLimit)
  UNIFORM(int, firstDab)
  UNIFORM(int, dabCount)
  UNIFORM(float, extentScale)
  // @dab_coverage_uniforms
UNIFORMS_END

layout(binding = 3) uniform highp sampler2D strokePage;

#ifdef HALF_FLOAT
layout(rgba16f, STORAGE_BINDING(0)) writeonly uniform highp image2D result;
#else
layout(rgba8, STORAGE_BINDING(0)) writeonly uniform highp image2D result;
#endif

struct DabRecord {
  vec4 geometry;
  vec4 shape;
  vec4 color;
  vec4 grain;
  vec4 gradation;
};
layout(std430, STORAGE_BINDING(1)) readonly buffer Dabs {
  DabRecord dabs[];
};

vec4 geometry;
vec4 shape;
vec4 grain;
vec4 gradation;

// @dab_coverage

#if defined(GAMMA) || defined(LINEAR_DOCUMENT)
vec3 srgbToLinear(vec3 color) {
  return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
             greaterThan(color, vec3(0.04045)));
}
#endif

// @stroke_glaze

void main() {
  ivec2 local = ivec2(gl_GlobalInvocationID.xy);
  vec4 value = texelFetch(strokePage, slotOrigin + local, 0);
  if (all(lessThan(local, canvasLimit))) {
    vec2 point = tileOrigin + vec2(local) + 0.5;
    for (int index = firstDab; index < firstDab + dabCount; ++index) {
      geometry = dabs[index].geometry;
      float extent = geometry.z * extentScale + 1.0;
      if (any(lessThan(point, geometry.xy - extent)) ||
          any(greaterThan(point, geometry.xy + extent))) {
        continue;
      }
      shape = dabs[index].shape;
      grain = dabs[index].grain;
      gradation = dabs[index].gradation;
      float coverage = dabTipCoverage(point);
      vec3 baseColor = dabs[index].color.rgb;
#ifdef SHAPE_COLOR
      vec3 tipColor;
      if (coverage > 0.0 && dabTipColor(point, tipColor)) {
#ifdef LINEAR_DOCUMENT
        baseColor = srgbToLinear(tipColor);
#else
        baseColor = tipColor;
#endif
      }
#endif
      value = glaze(value, coverage, baseColor);
    }
  }
  imageStore(result, local, value);
}
