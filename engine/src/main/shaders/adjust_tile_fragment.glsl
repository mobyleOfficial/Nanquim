#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D sourcePage;
layout(binding = 2) uniform highp sampler2D selectionPage;
layout(binding = 3) uniform highp sampler2D lookupTable;
layout(binding = 4) uniform highp sampler2D maskPage;
layout(binding = 5) uniform highp sampler2D strokePage;
layout(binding = 6) uniform highp sampler2D filteredImage;
layout(binding = 7) uniform highp sampler2D fieldTexture;

UNIFORMS_BEGIN(AdjustTileParameters)
  UNIFORM(ivec2, fieldSize)
  UNIFORM(float, fieldScale)
  UNIFORM(int, liquifyCubic)
  UNIFORM(ivec2, sourceSlotOrigin)
  UNIFORM(ivec2, selectionSlotOrigin)
  UNIFORM(ivec2, maskSlotOrigin)
  UNIFORM(ivec2, strokeSlotOrigin)
  UNIFORM(int, maskMode)
  UNIFORM(int, hasStroke)
  UNIFORM(float, strokeOpacity)
  UNIFORM(float, strokeLuminance)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(int, sampleStride)
  UNIFORM(int, hasSelection)
  UNIFORM(float, amount)
  UNIFORM(int, filterKind)
  UNIFORM_ARRAY(float, parameters, 16)
  UNIFORM(ivec2, canvasSize)
  UNIFORM(int, hasSource)
  UNIFORM(bool, encodeSource)
  UNIFORM(bool, decodeResult)
  UNIFORM(int, spatialMode)
  UNIFORM(float, sharpenStrength)
  UNIFORM(ivec2, canvasOrigin)
  UNIFORM(int, canvasStride)
  UNIFORM(float, filteredScale)
  UNIFORM(float, filteredOffset)
  UNIFORM(ivec2, filteredRectOrigin)
  UNIFORM(ivec2, filteredRectSize)
  UNIFORM(ivec2, filteredLevelSize)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

const int kHueSaturationBrightness = 0;
const int kColorBalance = 1;
const int kCurves = 2;
const int kGradientMap = 3;
const int kNoise = 8;
const int kTableSize = 1024;
const float kColorBalanceStrength = 0.5;

// @color_space

uint hash32(int x, int y, uint seed) {
  uint value = uint(x) * 0x8da6b343u ^ uint(y) * 0xd8163841u ^ seed * 0xcb1ab31fu;
  value ^= value >> 13u;
  value *= 0x5bd1e995u;
  value ^= value >> 15u;
  return value;
}

float hashUnit(int x, int y, uint seed) { return float(hash32(x, y, seed)) / 4294967296.0; }

float valueNoise(vec2 position, uint seed) {
  vec2 corner = floor(position);
  ivec2 index = ivec2(corner);
  vec2 t = position - corner;
  vec2 fade = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
  float a = hashUnit(index.x, index.y, seed);
  float b = hashUnit(index.x + 1, index.y, seed);
  float c = hashUnit(index.x, index.y + 1, seed);
  float d = hashUnit(index.x + 1, index.y + 1, seed);
  float upper = a + (b - a) * fade.x;
  float lower = c + (d - c) * fade.x;
  return upper + (lower - upper) * fade.y;
}

float noiseAt(vec2 pixel) {
  float scale = clamp(parameters[1], 0.0, 1.0);
  int octaves = 1 + int(floor(clamp(parameters[2], 0.0, 1.0) * 7.0 + 0.5));
  float turbulence = clamp(parameters[3], 0.0, 1.0);
  int type = int(floor(parameters[4] + 0.5));
  float wavelength =
      max((8.0 + 1016.0 * scale * scale) * float(max(canvasSize.x, canvasSize.y)) / 4096.0, 2.0);
  vec2 position = (pixel + 0.5) / wavelength;
  if (turbulence > 0.0) {
    vec2 warp = vec2(valueNoise(position + vec2(5.2, 1.3), 101u),
                     valueNoise(position + vec2(1.7, 9.2), 202u)) - 0.5;
    position += turbulence * 2.0 * warp;
  }
  float sum = 0.0;
  float total = 0.0;
  float weight = 1.0;
  float frequency = 1.0;
  for (int octave = 0; octave < octaves; ++octave) {
    float value = valueNoise(position * frequency, uint(octave));
    if (type == 1) value = abs(2.0 * value - 1.0);
    if (type == 2) value = 1.0 - abs(2.0 * value - 1.0);
    sum += weight * value;
    total += weight;
    weight *= 0.5;
    frequency *= 2.0;
  }
  return sum / total;
}

vec3 overlay(vec3 base, float blend) {
  return mix(2.0 * base * blend, 1.0 - 2.0 * (1.0 - base) * (1.0 - blend), step(0.5, base));
}

vec2 currentPixel = vec2(0.0);

vec3 hsvFromRgb(vec3 color) {
  float maximum = max(color.r, max(color.g, color.b));
  float minimum = min(color.r, min(color.g, color.b));
  float delta = maximum - minimum;
  float hue = 0.0;
  if (delta > 0.0) {
    if (maximum == color.r) {
      hue = (color.g - color.b) / delta;
      if (hue < 0.0) hue += 6.0;
    } else if (maximum == color.g) {
      hue = (color.b - color.r) / delta + 2.0;
    } else {
      hue = (color.r - color.g) / delta + 4.0;
    }
    hue /= 6.0;
  }
  return vec3(hue, maximum > 0.0 ? delta / maximum : 0.0, maximum);
}

vec3 rgbFromHsv(vec3 hsv) {
  vec3 k = mod(vec3(5.0, 3.0, 1.0) + hsv.x * 6.0, 6.0);
  vec3 ramp = clamp(min(k, 4.0 - k), 0.0, 1.0);
  return hsv.z - hsv.z * hsv.y * ramp;
}

float luminance(vec3 color) {
  return 0.3 * color.r + 0.59 * color.g + 0.11 * color.b;
}

vec4 lookupEntry(float value) {
  float position = clamp(value, 0.0, 1.0) * float(kTableSize - 1);
  int index = min(int(position), kTableSize - 2);
  float fraction = position - float(index);
  return mix(texelFetch(lookupTable, ivec2(index, 0), 0), texelFetch(lookupTable, ivec2(index + 1, 0), 0),
             fraction);
}

vec3 hueSaturationBrightness(vec3 color) {
  vec3 hsv = hsvFromRgb(color);
  hsv.x = fract(hsv.x + parameters[0] * 0.5);
  hsv.y = clamp(hsv.y * (1.0 + parameters[1]), 0.0, 1.0);
  vec3 result = rgbFromHsv(hsv);
  float brightness = parameters[2];
  return brightness >= 0.0 ? result + (1.0 - result) * brightness : result * (1.0 + brightness);
}

vec3 colorBalance(vec3 color) {
  float light = luminance(color);
  float shadows = 1.0 - smoothstep(0.0, 0.5, light);
  float highlights = smoothstep(0.5, 1.0, light);
  float midtones = 1.0 - shadows - highlights;
  vec3 shift = shadows * vec3(parameters[0], parameters[1], parameters[2]) +
               midtones * vec3(parameters[3], parameters[4], parameters[5]) +
               highlights * vec3(parameters[6], parameters[7], parameters[8]);
  return clamp(color + shift * kColorBalanceStrength, 0.0, 1.0);
}

vec4 filterColor(vec4 color) {
  if (filterKind == kHueSaturationBrightness) return vec4(hueSaturationBrightness(color.rgb), color.a);
  if (filterKind == kColorBalance) return vec4(colorBalance(color.rgb), color.a);
  if (filterKind == kCurves) {
    return vec4(lookupEntry(color.r).r, lookupEntry(color.g).g, lookupEntry(color.b).b,
                lookupEntry(color.a).a);
  }
  if (filterKind == kGradientMap) {
    vec3 mapped = lookupEntry(luminance(color.rgb)).rgb;
    return vec4(color.rgb + (mapped - color.rgb) * parameters[0], color.a);
  }
  if (filterKind == kNoise) {
    float noise = noiseAt(currentPixel);
    return vec4(mix(color.rgb, overlay(color.rgb, noise), clamp(parameters[0], 0.0, 1.0)), color.a);
  }
  return color;
}

vec4 filteredTexel(ivec2 index) {
  ivec2 local = clamp(index, ivec2(0), filteredLevelSize - 1) - filteredRectOrigin;
  if (any(lessThan(local, ivec2(0))) || any(greaterThanEqual(local, filteredRectSize))) {
    return vec4(0.0);
  }
  return texelFetch(filteredImage, local, 0);
}

vec4 filteredBilinear(vec2 position) {
  vec2 corner = floor(position);
  vec2 fraction = position - corner;
  ivec2 index = ivec2(corner);
  vec4 upper = mix(filteredTexel(index), filteredTexel(index + ivec2(1, 0)), fraction.x);
  vec4 lower = mix(filteredTexel(index + ivec2(0, 1)), filteredTexel(index + ivec2(1, 1)), fraction.x);
  return mix(upper, lower, fraction.y);
}

vec4 filteredAt(ivec2 pixel) {
  return filteredBilinear((vec2(pixel) - filteredOffset) / filteredScale);
}

vec2 displacementAt(vec2 pixel) {
  vec2 position = pixel / 4.0;
  vec2 corner = floor(position);
  vec2 fraction = position - corner;
  ivec2 index = ivec2(corner);
  ivec2 last = fieldSize - 1;
  vec2 a = texelFetch(fieldTexture, clamp(index, ivec2(0), last), 0).rg;
  vec2 b = texelFetch(fieldTexture, clamp(index + ivec2(1, 0), ivec2(0), last), 0).rg;
  vec2 c = texelFetch(fieldTexture, clamp(index + ivec2(0, 1), ivec2(0), last), 0).rg;
  vec2 d = texelFetch(fieldTexture, clamp(index + ivec2(1, 1), ivec2(0), last), 0).rg;
  return mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
}

vec4 cubicWeights(float t) {
  float t2 = t * t;
  float t3 = t2 * t;
  return 0.5 * vec4(-t3 + 2.0 * t2 - t, 3.0 * t3 - 5.0 * t2 + 2.0, -3.0 * t3 + 4.0 * t2 + t,
                    t3 - t2);
}

vec4 filteredBicubic(vec2 position) {
  vec2 base = floor(position);
  vec2 fraction = position - base;
  vec4 wx = cubicWeights(fraction.x);
  vec4 wy = cubicWeights(fraction.y);
  vec4 sum = vec4(0.0);
  for (int j = 0; j < 4; ++j) {
    vec4 row = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
      row += wx[i] * filteredTexel(ivec2(base) + ivec2(i - 1, j - 1));
    }
    sum += wy[j] * row;
  }
  return sum;
}

void main() {
  ivec2 local = (ivec2(gl_FragCoord.xy) - targetOrigin) * sampleStride;
  vec4 stored = hasSource != 0 ? texelFetch(sourcePage, sourceSlotOrigin + local, 0) : vec4(0.0);
  vec4 original = encodeSource ? premultipliedLinearToSrgb(stored) : stored;
  float weight = amount;
  if (hasSelection != 0) weight *= texelFetch(selectionPage, selectionSlotOrigin + local, 0).r;
  if (maskMode != 0) {
    float painted = maskMode == 1 ? texelFetch(maskPage, maskSlotOrigin + local, 0).r : 0.0;
    if (hasStroke != 0) {
      float coverage = texelFetch(strokePage, strokeSlotOrigin + local, 0).a * strokeOpacity;
      painted = mix(painted, strokeLuminance, coverage);
    }
    weight *= painted;
  }
  vec4 result = original;
  ivec2 pixel = canvasOrigin + (ivec2(gl_FragCoord.xy) - targetOrigin) * canvasStride;
#if defined(ADJUST_SPATIAL) || defined(ADJUST_LIQUIFY)
  if (weight > 0.0) {
#if defined(ADJUST_LIQUIFY)
    vec2 source = vec2(pixel) + displacementAt(vec2(pixel)) * fieldScale;
    vec4 filtered = liquifyCubic != 0 ? filteredBicubic(source)
                                      : filteredBilinear((source - filteredOffset) / filteredScale);
    vec4 value = filtered;
#else
    vec4 filtered = filteredAt(pixel);
    vec4 value = filtered;
    if (spatialMode == 2) value = original + sharpenStrength * (original - filtered);
    if (spatialMode == 3) {
      value.rgb = original.rgb + sharpenStrength * filtered.rgb;
      value.a = original.a + sharpenStrength * filtered.a * (1.0 - original.a);
    }
#endif
    value.a = clamp(value.a, 0.0, 1.0);
    value.rgb = clamp(value.rgb, vec3(0.0), vec3(value.a));
    result = original + (value - original) * weight;
  }
#else
  currentPixel = vec2(pixel);
  if (original.a > 0.0 && weight > 0.0) {
    vec4 color = vec4(clamp(original.rgb / original.a, 0.0, 1.0), original.a);
    vec4 filtered = filterColor(color);
    float alpha = clamp(filtered.a, 0.0, 1.0);
    vec4 premultiplied = vec4(clamp(filtered.rgb, 0.0, 1.0) * alpha, alpha);
    result = original + (premultiplied - original) * weight;
  }
#endif
  if (!decodeResult) {
    fragmentColor = result;
  } else if (weight > 0.0) {
    fragmentColor = premultipliedSrgbToLinear(result);
  } else {
    fragmentColor = encodeSource ? stored : premultipliedSrgbToLinear(result);
  }
}
