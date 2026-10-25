#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D inputImage;

UNIFORMS_BEGIN(AdjustPassParameters)
  UNIFORM(int, passKind)
  UNIFORM(ivec2, outputOrigin)
  UNIFORM(ivec2, rectOrigin)
  UNIFORM(ivec2, rectSize)
  UNIFORM(ivec2, levelSize)
  UNIFORM(ivec2, inputTexelOrigin)
  UNIFORM(ivec2, inputBase)
  UNIFORM(ivec2, inputLimit)
  UNIFORM(int, boxFactor)
  UNIFORM(bool, encodeInput)
  UNIFORM_ARRAY(float, weights, 9)
  UNIFORM(int, radius)
  UNIFORM(int, taps)
  UNIFORM(vec2, tapStep)
  UNIFORM(vec2, center)
  UNIFORM(float, spacing)
  UNIFORM(vec4, values)
  UNIFORM(int, variant)
  UNIFORM(float, mappingOffset)
  UNIFORM(float, mappingScale)
  UNIFORM(int, exactReads)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

const int kBox = 0;
const int kGaussianHorizontal = 1;
const int kGaussianVertical = 2;
const int kLine = 3;
const int kZoom = 4;
const int kDirectional = 5;
const int kThreshold = 6;
const int kHalftone = 7;
const int kGlitch = 8;
const int kChromatic = 9;

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

float luminance(vec3 color) { return 0.3 * color.r + 0.59 * color.g + 0.11 * color.b; }

vec3 unpremultiplied(vec4 value) {
  return value.a > 0.0 ? clamp(value.rgb / value.a, 0.0, 1.0) : vec3(0.0);
}

vec4 fetchTexel(ivec2 index) {
  ivec2 local = clamp(index, ivec2(0), levelSize - 1) - rectOrigin;
  if (any(lessThan(local, ivec2(0))) || any(greaterThanEqual(local, rectSize))) return vec4(0.0);
  return texelFetch(inputImage, local, 0);
}

vec4 sampleAt(vec2 position) {
  vec2 clamped = clamp(position, vec2(0.0), vec2(levelSize - 1));
  return texture(inputImage, (clamped - vec2(rectOrigin) + 0.5) / vec2(rectSize));
}

vec4 readCanvas(vec2 pixel) { return sampleAt((pixel - mappingOffset) / mappingScale); }

vec4 readCanvasExact(vec2 pixel) {
  vec2 position = (pixel - mappingOffset) / mappingScale;
  vec2 corner = floor(position);
  vec2 fraction = position - corner;
  ivec2 index = ivec2(corner);
  vec4 upper = mix(fetchTexel(index), fetchTexel(index + ivec2(1, 0)), fraction.x);
  vec4 lower = mix(fetchTexel(index + ivec2(0, 1)), fetchTexel(index + ivec2(1, 1)), fraction.x);
  return mix(upper, lower, fraction.y);
}

float inkOf(vec3 color, int ink) {
  if (ink == 3) return 1.0 - max(color.r, max(color.g, color.b));
  if (ink == 4) return 1.0 - luminance(color);
  if (variant == 1) return color[ink];
  float black = 1.0 - max(color.r, max(color.g, color.b));
  return black >= 1.0 ? 0.0 : (1.0 - color[ink] - black) / (1.0 - black);
}

float coverage(vec2 pixel, float degrees, int ink) {
  float cell = values.x;
  float cosine = 1.0;
  float sine = 0.0;
  if (degrees == 15.0) {
    cosine = 0.96592582628906829;
    sine = 0.25881904510252076;
  } else if (degrees == 75.0) {
    cosine = 0.25881904510252076;
    sine = 0.96592582628906829;
  } else if (degrees == 45.0) {
    cosine = 0.70710678118654752;
    sine = 0.70710678118654752;
  }
  float u = cosine * (pixel.x + 0.5) + sine * (pixel.y + 0.5);
  float v = -sine * (pixel.x + 0.5) + cosine * (pixel.y + 0.5);
  vec2 cellCenter = (floor(vec2(u, v) / cell) + 0.5) * cell;
  vec2 cellPixel = vec2(cosine * cellCenter.x - sine * cellCenter.y,
                        sine * cellCenter.x + cosine * cellCenter.y) - 0.5;
  vec4 cellColor = exactReads != 0 ? readCanvasExact(cellPixel) : readCanvas(cellPixel);
  float amount = clamp(inkOf(unpremultiplied(cellColor), ink), 0.0, 1.0) * cellColor.a;
  float radius = cell * 0.70710678 * sqrt(amount);
  float distance = length(vec2(u, v) - cellCenter);
  return amount <= 0.0 ? 0.0 : clamp((radius - distance) / mappingScale + 0.5, 0.0, 1.0);
}

vec4 pixelPass(ivec2 texel) {
  vec2 pixel = mappingOffset + vec2(texel) * mappingScale;
  if (passKind == kThreshold) {
    vec4 value = fetchTexel(texel);
    return value * smoothstep(values.x - 0.1, values.x + 0.1, luminance(unpremultiplied(value)));
  }
  if (passKind == kHalftone) {
    float alpha = fetchTexel(texel).a;
    vec3 rgb;
    if (variant == 0) {
      float key = coverage(pixel, 45.0, 3);
      rgb = (1.0 - vec3(coverage(pixel, 15.0, 0), coverage(pixel, 75.0, 1), coverage(pixel, 0.0, 2))) *
            (1.0 - key);
    } else if (variant == 1) {
      rgb = vec3(coverage(pixel, 15.0, 0), coverage(pixel, 75.0, 1), coverage(pixel, 0.0, 2));
    } else {
      rgb = vec3(1.0 - coverage(pixel, 45.0, 4));
    }
    return vec4(rgb * alpha, alpha);
  }
  if (passKind == kGlitch) {
    float shift = values.x;
    float block = values.y;
    float frequency = values.z;
    ivec2 cell = ivec2(floor((pixel + 0.5) / block));
    vec2 red = pixel;
    vec2 green = pixel;
    vec2 blue = pixel;
    if (variant == 0) {
      if (hashUnit(cell.x, cell.y, 11u) < frequency) {
        vec2 moved = pixel + vec2((hashUnit(cell.x, cell.y, 12u) - 0.5) * 2.0 * shift,
                                  (hashUnit(cell.x, cell.y, 13u) - 0.5) * 0.5 * shift);
        red = moved;
        green = moved;
        blue = moved;
      }
    } else if (variant == 1) {
      float sway =
          (valueNoise(vec2(pixel.y / block * (0.5 + 2.0 * frequency), 0.5), 14u) - 0.5) * 2.0 * shift;
      red = pixel + vec2(sway, 0.0);
      green = red;
      blue = red;
    } else if (variant == 2) {
      int band = int(floor((pixel.y + 0.5) / (block * 0.25)));
      float split =
          hashUnit(0, band, 15u) < frequency ? (hashUnit(1, band, 16u) - 0.5) * 2.0 * shift : 0.0;
      red = pixel + vec2(split, 0.0);
      blue = pixel - vec2(split, 0.0);
    } else {
      float split = shift * (hashUnit(cell.x, cell.y, 17u) < frequency
                                 ? 0.25 + 0.75 * hashUnit(cell.x, cell.y, 18u)
                                 : 0.25);
      red = pixel + vec2(split, 0.0);
      blue = pixel - vec2(split, 0.0);
    }
    vec4 r = readCanvas(red);
    vec4 g = readCanvas(green);
    vec4 b = readCanvas(blue);
    return vec4(r.r, g.g, b.b, max(r.a, max(g.a, b.a)));
  }
  float edge = clamp(length(pixel - center) / values.w, 0.0, 1.0);
  float strength = values.x * (1.0 + (edge * edge - 1.0) * values.y);
  vec2 red;
  vec2 blue;
  if (variant == 0) {
    red = center + (pixel - center) * (1.0 + strength);
    blue = center + (pixel - center) * (1.0 - strength);
  } else {
    red = pixel + tapStep * strength;
    blue = pixel - tapStep * strength;
  }
  vec4 r = readCanvas(red);
  vec4 g = fetchTexel(texel);
  vec4 b = readCanvas(blue);
  float widest = max(r.a, max(g.a, b.a));
  float alpha = widest + (g.a - widest) * values.z;
  return vec4(min(r.r, alpha), min(g.g, alpha), min(b.b, alpha), alpha);
}

void main() {
  ivec2 texel = ivec2(gl_FragCoord.xy) + outputOrigin;
#if defined(PASS_TEXELS)
  fragmentColor = pixelPass(texel);
#elif defined(PASS_RAYS)
  vec2 position = vec2(texel);
  vec4 sum = vec4(0.0);
  float middle = float(taps - 1) * 0.5;
  float distance = length(position - center) * spacing;
  for (int tap = 0; tap < taps; ++tap) {
    float t = float(tap) - middle;
    if (passKind == kLine) {
      sum += sampleAt(position + tapStep * t);
    } else if (passKind == kZoom) {
      sum += sampleAt(center + (position - center) * exp(spacing * t));
    } else {
      sum += sampleAt(position + tapStep * (distance * t));
    }
  }
  fragmentColor = sum / float(taps);
#else
  vec4 sum = vec4(0.0);
  if (passKind == kBox) {
    ivec2 first = max(texel * boxFactor, inputBase);
    ivec2 end = min((texel + 1) * boxFactor, inputLimit);
    int count = 0;
    for (int y = first.y; y < end.y; ++y) {
      for (int x = first.x; x < end.x; ++x) {
        vec4 texelValue = texelFetch(inputImage, inputTexelOrigin + ivec2(x, y) - inputBase, 0);
        sum += encodeInput ? premultipliedLinearToSrgb(texelValue) : texelValue;
        ++count;
      }
    }
    fragmentColor = count > 0 ? sum / float(count) : vec4(0.0);
    return;
  }
  if (passKind == kGaussianHorizontal || passKind == kGaussianVertical) {
    ivec2 direction = passKind == kGaussianHorizontal ? ivec2(1, 0) : ivec2(0, 1);
    sum = fetchTexel(texel) * weights[0];
    for (int offset = 1; offset <= radius; ++offset) {
      sum += (fetchTexel(texel + direction * offset) + fetchTexel(texel - direction * offset)) *
             weights[offset];
    }
    fragmentColor = sum;
    return;
  }
  fragmentColor = vec4(0.0);
#endif
}
