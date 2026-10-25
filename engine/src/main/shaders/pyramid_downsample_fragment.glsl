#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D finerLevel;

layout(location = 0) out vec4 fragmentColor;

vec4 fetchOrTransparent(ivec2 texel, ivec2 finerSize) {
  if (texel.x >= finerSize.x || texel.y >= finerSize.y) {
    return vec4(0.0);
  }
  return texelFetch(finerLevel, texel, 0);
}

void main() {
  ivec2 finerSize = textureSize(finerLevel, 0);
  ivec2 source = ivec2(gl_FragCoord.xy) * 2;
  fragmentColor = 0.25 * (fetchOrTransparent(source, finerSize) +
                          fetchOrTransparent(source + ivec2(1, 0), finerSize) +
                          fetchOrTransparent(source + ivec2(0, 1), finerSize) +
                          fetchOrTransparent(source + ivec2(1, 1), finerSize));
}
