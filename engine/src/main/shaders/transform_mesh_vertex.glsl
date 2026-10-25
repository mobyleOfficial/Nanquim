#version 310 es
precision highp float;

layout(location = 0) in vec2 position;
layout(location = 1) in float weight;
layout(location = 2) in vec2 source;

// @transform_mesh_uniforms

layout(location = 0) out vec2 texel;

void main() {
  vec2 clip = (position - tileOrigin) / 128.0 - 1.0;
  gl_Position = vec4(clip * weight, 0.0, weight);
  texel = source - floatingOrigin;
}
