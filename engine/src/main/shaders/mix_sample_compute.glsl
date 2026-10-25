#version 310 es
precision highp float;
precision highp int;

layout(local_size_x = 16, local_size_y = 16) in;

UNIFORMS_BEGIN(MixSampleParameters)
  UNIFORM(ivec2, workingSize)
  UNIFORM(vec4, dabGeometry)
  UNIFORM(vec4, dabShape)
  UNIFORM(vec4, dabGrain)
  UNIFORM(vec4, dabGradation)
  UNIFORM(float, boundingRadius)
  UNIFORM(int, mixingMode)
  UNIFORM(vec4, brushPaint)
  UNIFORM(float, pickUp)
  UNIFORM(float, useBrushColor)
  UNIFORM(float, pull)
  // @dab_coverage_uniforms
UNIFORMS_END

layout(r32ui, STORAGE_BINDING(0)) readonly uniform highp uimage2D working;

vec4 loadWorking(ivec2 pixel) {
#ifdef WORKING_HALF
  return vec4(unpackHalf2x16(imageLoad(working, ivec2(pixel.x * 2, pixel.y)).r),
              unpackHalf2x16(imageLoad(working, ivec2(pixel.x * 2 + 1, pixel.y)).r));
#else
  return unpackUnorm4x8(imageLoad(working, pixel).r);
#endif
}

layout(std430, STORAGE_BINDING(1)) buffer MixingState {
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

const int kGrid = 16;
shared vec4 sums[kGrid * kGrid];
shared float weights[kGrid * kGrid];

void main() {
  geometry = dabGeometry;
  shape = dabShape;
  grain = dabGrain;
  gradation = dabGradation;
  ivec2 cell = ivec2(gl_LocalInvocationID.xy);
  vec2 point = geometry.xy + ((vec2(cell) + 0.5) / float(kGrid) * 2.0 - 1.0) * boundingRadius;
  ivec2 pixel = ivec2(floor(point));
  vec4 sum = vec4(0.0);
  float weight = 0.0;
  if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, workingSize))) {
    weight = max(dabTipCoverage(point), 0.0);
    sum = loadWorking(pixel) * weight;
  }
  uint index = gl_LocalInvocationIndex;
  sums[index] = sum;
  weights[index] = weight;
  memoryBarrierShared();
  barrier();
  for (uint stride = uint(kGrid * kGrid) / 2u; stride > 0u; stride /= 2u) {
    if (index < stride) {
      sums[index] += sums[index + stride];
      weights[index] += weights[index + stride];
    }
    memoryBarrierShared();
    barrier();
  }
  if (index != 0u) {
    return;
  }
  float total = weights[0];
  if (total <= 0.0) {
    state.apply = 0;
    return;
  }
  vec4 sampleColor = sums[0] / total;
  state.sampled = sampleColor;
  state.apply = 1;
  if (mixingMode == 1) {
    state.carried = state.started != 0 ? mix(state.carried, sampleColor, pickUp) : sampleColor;
    state.started = 1;
    state.paint = mix(state.carried, brushPaint, useBrushColor);
  } else {
    state.paint = mix(brushPaint, sampleColor, pull);
  }
}
