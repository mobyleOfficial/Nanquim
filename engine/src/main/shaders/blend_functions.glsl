const int kBlendNormal = 0;
const int kBlendMultiply = 1;
const int kBlendScreen = 2;
const int kBlendOverlay = 3;
const int kBlendDarken = 4;
const int kBlendLighten = 5;
const int kBlendColorDodge = 6;
const int kBlendColorBurn = 7;
const int kBlendHardLight = 8;
const int kBlendSoftLight = 9;
const int kBlendDifference = 10;
const int kBlendExclusion = 11;
const int kBlendAdd = 12;
const int kBlendSubtract = 13;
const int kBlendDivide = 14;
const int kBlendLinearBurn = 15;
const int kBlendVividLight = 16;
const int kBlendLinearLight = 17;
const int kBlendPinLight = 18;
const int kBlendHardMix = 19;
const int kBlendHue = 20;
const int kBlendSaturation = 21;
const int kBlendColor = 22;
const int kBlendLuminosity = 23;
const int kBlendDarkerColor = 24;
const int kBlendLighterColor = 25;
const int kBlendPassThrough = 26;
const int kBlendBehind = 27;

const float kHardMixThreshold = 1.0 - 1.0 / 1024.0;

float screenChannel(float backdrop, float source) {
  return backdrop + source - backdrop * source;
}

float hardLightChannel(float backdrop, float source) {
  return source <= 0.5 ? backdrop * (2.0 * source) : screenChannel(backdrop, 2.0 * source - 1.0);
}

float colorDodgeChannel(float backdrop, float source) {
  if (backdrop == 0.0) return 0.0;
  if (source == 1.0) return 1.0;
  return min(1.0, backdrop / (1.0 - source));
}

float colorBurnChannel(float backdrop, float source) {
  if (backdrop == 1.0) return 1.0;
  if (source == 0.0) return 0.0;
  return 1.0 - min(1.0, (1.0 - backdrop) / source);
}

float softLightChannel(float backdrop, float source) {
  if (source <= 0.5) return backdrop - (1.0 - 2.0 * source) * backdrop * (1.0 - backdrop);
  float d = backdrop <= 0.25 ? ((16.0 * backdrop - 12.0) * backdrop + 4.0) * backdrop : sqrt(backdrop);
  return backdrop + (2.0 * source - 1.0) * (d - backdrop);
}

float separableChannel(int mode, float backdrop, float source) {
  switch (mode) {
    case kBlendMultiply: return backdrop * source;
    case kBlendScreen: return screenChannel(backdrop, source);
    case kBlendOverlay: return hardLightChannel(source, backdrop);
    case kBlendDarken: return min(backdrop, source);
    case kBlendLighten: return max(backdrop, source);
    case kBlendColorDodge: return colorDodgeChannel(backdrop, source);
    case kBlendColorBurn: return colorBurnChannel(backdrop, source);
    case kBlendHardLight: return hardLightChannel(backdrop, source);
    case kBlendSoftLight: return softLightChannel(backdrop, source);
    case kBlendDifference: return abs(backdrop - source);
    case kBlendExclusion: return backdrop + source - 2.0 * backdrop * source;
    case kBlendAdd: return min(1.0, backdrop + source);
    case kBlendSubtract: return max(0.0, backdrop - source);
    case kBlendDivide:
      if (source == 0.0) return backdrop == 0.0 ? 0.0 : 1.0;
      return min(1.0, backdrop / source);
    case kBlendLinearBurn: return max(0.0, backdrop + source - 1.0);
    case kBlendVividLight:
      return source <= 0.5 ? colorBurnChannel(backdrop, 2.0 * source)
                           : colorDodgeChannel(backdrop, 2.0 * source - 1.0);
    case kBlendLinearLight: return clamp(backdrop + 2.0 * source - 1.0, 0.0, 1.0);
    case kBlendPinLight:
      return source <= 0.5 ? min(backdrop, 2.0 * source) : max(backdrop, 2.0 * source - 1.0);
    case kBlendHardMix: return backdrop + source >= kHardMixThreshold ? 1.0 : 0.0;
    default: return source;
  }
}

float luminosityOf(vec3 color) {
  return 0.3 * color.r + 0.59 * color.g + 0.11 * color.b;
}

vec3 clipColor(vec3 color) {
  float lum = luminosityOf(color);
  float lowest = min(min(color.r, color.g), color.b);
  float highest = max(max(color.r, color.g), color.b);
  if (lowest < 0.0) color = lum <= 0.0 ? vec3(0.0) : lum + (color - lum) * lum / (lum - lowest);
  if (highest > 1.0) color = lum >= 1.0 ? vec3(1.0) : lum + (color - lum) * (1.0 - lum) / (highest - lum);
  return color;
}

vec3 setLuminosity(vec3 color, float lum) {
  return clipColor(color + (lum - luminosityOf(color)));
}

float saturationOf(vec3 color) {
  return max(max(color.r, color.g), color.b) - min(min(color.r, color.g), color.b);
}

vec3 setSaturation(vec3 color, float saturation) {
  float highest = max(max(color.r, color.g), color.b);
  float lowest = min(min(color.r, color.g), color.b);
  if (highest <= lowest) return vec3(0.0);
  return (color - lowest) * saturation / (highest - lowest);
}

vec3 blendColor(int mode, vec3 backdrop, vec3 source) {
  switch (mode) {
    case kBlendHue:
      return setLuminosity(setSaturation(source, saturationOf(backdrop)), luminosityOf(backdrop));
    case kBlendSaturation:
      return setLuminosity(setSaturation(backdrop, saturationOf(source)), luminosityOf(backdrop));
    case kBlendColor: return setLuminosity(source, luminosityOf(backdrop));
    case kBlendLuminosity: return setLuminosity(backdrop, luminosityOf(source));
    case kBlendDarkerColor: return luminosityOf(source) < luminosityOf(backdrop) ? source : backdrop;
    case kBlendLighterColor: return luminosityOf(source) > luminosityOf(backdrop) ? source : backdrop;
    default:
      return vec3(separableChannel(mode, backdrop.r, source.r),
                  separableChannel(mode, backdrop.g, source.g),
                  separableChannel(mode, backdrop.b, source.b));
  }
}

vec3 unpremultiplied(vec4 color) {
  return color.a > 0.0 ? color.rgb / color.a : vec3(0.0);
}

vec4 blendComposite(int mode, vec4 backdrop, vec4 source) {
  if (mode == kBlendBehind) return backdrop + source * (1.0 - backdrop.a);
  if (source.a <= 0.0) return backdrop;
  if (backdrop.a <= 0.0) return source;
  if (mode == kBlendNormal || mode == kBlendPassThrough) return source + backdrop * (1.0 - source.a);
  vec3 cs = unpremultiplied(source);
  vec3 cb = unpremultiplied(backdrop);
  vec3 blended = blendColor(mode, cb, cs);
  vec3 color = source.a * (1.0 - backdrop.a) * cs + source.a * backdrop.a * blended +
               (1.0 - source.a) * backdrop.a * cb;
  return vec4(color, source.a + backdrop.a * (1.0 - source.a));
}

vec4 blendClip(int mode, vec4 backdrop, vec4 source) {
  if (source.a <= 0.0 || backdrop.a <= 0.0) return backdrop;
  vec3 cs = unpremultiplied(source);
  vec3 cb = unpremultiplied(backdrop);
  vec3 blended = blendColor(mode, cb, cs);
  vec3 color = source.a * backdrop.a * blended + (1.0 - source.a) * backdrop.a * cb;
  return vec4(color, backdrop.a);
}
