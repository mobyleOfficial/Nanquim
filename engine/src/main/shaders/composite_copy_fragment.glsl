#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D sourceTexture;

layout(location = 0) out vec4 fragmentColor;

void main() {
  fragmentColor = texelFetch(sourceTexture, ivec2(gl_FragCoord.xy), 0);
}
