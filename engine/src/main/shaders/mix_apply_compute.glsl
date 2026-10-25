#version 310 es
precision highp float;
precision highp int;

layout(local_size_x = 8, local_size_y = 8) in;

UNIFORMS_BEGIN(MixApplyParameters)
  UNIFORM(vec4, dabGeometry)
  UNIFORM(vec4, dabShape)
  UNIFORM(vec4, dabGrain)
  UNIFORM(vec4, dabGradation)
  UNIFORM(ivec2, boxOrigin)
  UNIFORM(ivec2, boxSize)
  UNIFORM(int, mixingMode)
  UNIFORM(float, amount)
  UNIFORM(float, blur)
  // @dab_coverage_uniforms
UNIFORMS_END

layout(r32ui, STORAGE_BINDING(0)) uniform highp uimage2D working;

vec4 loadWorking(ivec2 pixel) {
#ifdef WORKING_HALF
  return vec4(unpackHalf2x16(imageLoad(working, ivec2(pixel.x * 2, pixel.y)).r),
              unpackHalf2x16(imageLoad(working, ivec2(pixel.x * 2 + 1, pixel.y)).r));
#else
  return unpackUnorm4x8(imageLoad(working, pixel).r);
#endif
}

void storeWorking(ivec2 pixel, vec4 color) {
#ifdef WORKING_HALF
  imageStore(working, ivec2(pixel.x * 2, pixel.y), uvec4(packHalf2x16(color.rg), 0u, 0u, 0u));
  imageStore(working, ivec2(pixel.x * 2 + 1, pixel.y), uvec4(packHalf2x16(color.ba), 0u, 0u, 0u));
#else
  imageStore(working, pixel, uvec4(packUnorm4x8(color), 0u, 0u, 0u));
#endif
}

layout(std430, STORAGE_BINDING(1)) readonly buffer MixingState {
  vec4 carried;
  vec4 paint;
  vec4 sampled;
  int started;
  int apply;
} state;

vec4 geometry;
vec4 shape;
vec4 grain;
vec4 gradation;

// @dab_coverage

void main() {
  geometry = dabGeometry;
  shape = dabShape;
  grain = dabGrain;
  gradation = dabGradation;
  ivec2 local = ivec2(gl_GlobalInvocationID.xy);
  if (state.apply == 0 || any(greaterThanEqual(local, boxSize))) {
    return;
  }
  ivec2 pixel = boxOrigin + local;
  float tip = dabTipCoverage(vec2(pixel) + 0.5);
  if (tip <= 0.0) {
    return;
  }
  vec4 color = loadWorking(pixel);
  if (mixingMode == 1) {
    color = mix(color, state.paint, clamp(tip * amount, 0.0, 1.0));
  } else {
    vec4 softened = mix(color, state.sampled, clamp(tip * blur, 0.0, 1.0));
    float deposit = clamp(tip * amount, 0.0, 1.0);
    color = state.paint * deposit + softened * (1.0 - state.paint.a * deposit);
  }
  storeWorking(pixel, color);
}
