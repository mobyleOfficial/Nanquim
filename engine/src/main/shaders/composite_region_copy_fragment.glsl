#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D sourceTexture;

UNIFORMS_BEGIN(CompositeRegionCopyParameters)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(ivec2, sourceOrigin)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  fragmentColor = texelFetch(sourceTexture, sourceOrigin + ivec2(gl_FragCoord.xy) - targetOrigin, 0);
}
