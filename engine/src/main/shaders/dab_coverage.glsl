const int kFilteringNone = 0;
const int kFilteringClassic = 1;
const int kFilteringImproved = 2;

#ifdef SHAPE_TEXTURE
layout(binding = 1) uniform highp sampler2D shapeTexture;
#endif

#ifdef GRAIN
layout(binding = 2) uniform highp sampler2D grainTexture;
#endif

float roundCoverage(float diameter, float along, float across) {
  float radius = diameter * 0.5;
  float distanceFromCenter = sqrt(along * along + across * across);
  float edge = clamp(radius - distanceFromCenter + 0.5, 0.0, 1.0);
  float blur = shape.w * diameter * 0.25;
  float inner = max(0.0, radius * clamp(hardness, 0.0, 1.0) - blur);
  if (distanceFromCenter <= inner || inner >= radius) {
    return edge;
  }
  float t = clamp((distanceFromCenter - inner) / (radius - inner), 0.0, 1.0);
  return edge * (1.0 - t * t * (3.0 - 2.0 * t));
}

#ifdef SHAPE_TEXTURE
bool tipTexel(float diameter, float along, float across, out vec2 uv, out float lod) {
  float u = along / diameter + 0.5;
  float v = across / diameter + 0.5;
  if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
    return false;
  }
  int flips = int(shape.z + 0.5);
  if ((flips & 1) != 0) {
    u = 1.0 - u;
  }
  if ((flips & 2) != 0) {
    v = 1.0 - v;
  }
  float bleedLod = log2(1.0 + shape.w * 0.25 * shapeSize);
  lod = 0.0;
  if (shapeFiltering == kFilteringImproved) {
    lod = max(log2(shapeSize / (diameter * max(shape.x, 0.05))), bleedLod);
  } else if (shapeFiltering == kFilteringClassic) {
    lod = bleedLod;
  }
  uv = vec2(u, v);
  return true;
}

float textureCoverage(float diameter, float along, float across) {
  vec2 uv;
  float lod;
  if (!tipTexel(diameter, along, across, uv, lod)) {
    return 0.0;
  }
#ifdef SHAPE_COLOR
  float value = textureLod(shapeTexture, uv, lod).a;
#else
  float value = textureLod(shapeTexture, uv, lod).r;
#endif
  return shapeInvert ? 1.0 - value : value;
}
#endif

#ifdef GRAIN
float adjustGrain(float value) {
  value = grainInvert ? 1.0 - value : value;
  value += grainBrightness;
  float factor = grainContrast >= 0.0 ? 1.0 / (1.0 - 0.99 * grainContrast) : 1.0 + grainContrast;
  return clamp((value - 0.5) * factor + 0.5, 0.0, 1.0);
}

float grainSample(float diameter, vec2 offset, vec2 point) {
  vec2 uv;
  float texelsPerPixel;
#ifdef GRAIN_TEXTURIZED
  float cosine = cos(grainRotation);
  float sine = sin(grainRotation);
  vec2 position = point - grain.xy;
  uv = vec2(position.x * cosine + position.y * sine, -position.x * sine + position.y * cosine) /
       grain.w;
  texelsPerPixel = grainSize / grain.w;
#else
  float angle = grainRotation + (grainRotateWithDab ? geometry.w : 0.0);
  float cosine = cos(angle);
  float sine = sin(angle);
  float scale = grainScale / diameter;
  uv = vec2(offset.x * cosine + offset.y * sine, -offset.x * sine + offset.y * cosine) * scale +
       grain.xy;
  texelsPerPixel = grainSize * scale;
#endif
  float lod = grainImproved ? log2(texelsPerPixel) : 0.0;
  return adjustGrain(textureLod(grainTexture, uv, lod).r);
}

float grainBlend(float c, float g, float d) {
  d = clamp(d, 0.0, 1.0);
#if defined(GRAIN_BLEND_1)
  return max(0.0, c - (1.0 - g) * d);
#elif defined(GRAIN_BLEND_2)
  return mix(c, max(0.0, c + g - 1.0), d);
#elif defined(GRAIN_BLEND_3)
  return mix(c, g > 0.0 ? clamp(1.0 - (1.0 - c) / g, 0.0, 1.0) : 0.0, d);
#elif defined(GRAIN_BLEND_4)
  return mix(c, c < 0.5 ? 2.0 * c * g : 1.0 - 2.0 * (1.0 - c) * (1.0 - g), d);
#elif defined(GRAIN_BLEND_5)
  return clamp((c - (1.0 - g) * d) / (1.0 - 0.99 * d), 0.0, 1.0);
#else
  return c * mix(1.0, g, d);
#endif
}
#endif

vec2 tipFrame(vec2 point) {
  float cosine = cos(geometry.w);
  float sine = sin(geometry.w);
  vec2 offset = point - geometry.xy;
  return vec2(offset.x * cosine + offset.y * sine,
              (-offset.x * sine + offset.y * cosine) / max(shape.x, 0.05));
}

#ifdef SHAPE_COLOR
bool dabTipColor(vec2 point, out vec3 color) {
  vec2 frame = tipFrame(point);
  vec2 uv;
  float lod;
  color = vec3(0.0);
  if (!tipTexel(geometry.z * 2.0, frame.x, frame.y, uv, lod)) {
    return false;
  }
  vec4 texel = textureLod(shapeTexture, uv, lod);
  if (texel.a <= 0.0) {
    return false;
  }
  color = min(texel.rgb / texel.a, vec3(1.0));
  return true;
}
#endif

float dabTipCoverage(vec2 point) {
  float diameter = geometry.z * 2.0;
  vec2 offset = point - geometry.xy;
  vec2 frame = tipFrame(point);
  float along = frame.x;
  float across = frame.y;
#ifdef SHAPE_TEXTURE
  float coverage = textureCoverage(diameter, along, across);
#else
  float coverage = roundCoverage(diameter, along, across);
#endif
  if (coverage > 0.0) {
    if (gradation.x > 0.0) {
      float projection =
          (offset.x * cos(gradation.y) + offset.y * sin(gradation.y)) / (diameter * 0.5);
      coverage *= 1.0 - gradation.x * (clamp(projection, -1.0, 1.0) + 1.0) * 0.5;
    }
#ifdef GRAIN
    coverage = grainBlend(coverage, grainSample(diameter, offset, point), grain.z);
#endif
  } else {
    coverage = 0.0;
  }
  return coverage;
}
