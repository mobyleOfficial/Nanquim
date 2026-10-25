#version 310 es
precision highp float;
precision highp int;

// @transform_mesh_uniforms

layout(binding = 1) uniform highp sampler2D floating;

layout(location = 0) in vec2 texel;
layout(location = 0) out vec4 fragmentColor;

vec4 fetchTexel(ivec2 position) {
  return texelFetch(floating, clamp(position, ivec2(0), ivec2(floatingSize) - 1), 0);
}

vec4 cubicWeights(float t) {
  float t2 = t * t;
  float t3 = t2 * t;
  return 0.5 * vec4(-t3 + 2.0 * t2 - t, 3.0 * t3 - 5.0 * t2 + 2.0, -3.0 * t3 + 4.0 * t2 + t,
                    t3 - t2);
}

void main() {
  if (filterMode == 0) {
    fragmentColor = fetchTexel(ivec2(floor(texel)));
  } else if (filterMode == 1) {
    fragmentColor = texture(floating, texel / floatingSize);
  } else {
    vec2 t = texel - 0.5;
    vec2 base = floor(t);
    vec2 f = t - base;
    vec4 wx = cubicWeights(f.x);
    vec4 wy = cubicWeights(f.y);
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; ++j) {
      vec4 row = vec4(0.0);
      for (int i = 0; i < 4; ++i) {
        row += wx[i] * fetchTexel(ivec2(base) + ivec2(i - 1, j - 1));
      }
      sum += wy[j] * row;
    }
    float alpha = clamp(sum.a, 0.0, 1.0);
    fragmentColor = vec4(clamp(sum.rgb, vec3(0.0), vec3(alpha)), alpha);
  }
}
