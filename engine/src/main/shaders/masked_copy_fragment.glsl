#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D sourcePage;
layout(binding = 2) uniform highp sampler2D selectionPage;
UNIFORMS_BEGIN(MaskedCopyParameters)
  UNIFORM(ivec2, sourceSlotOrigin)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(ivec2, selectionSlotOrigin)
  UNIFORM(int, selectionMode)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy) - targetOrigin;
  vec4 color = texelFetch(sourcePage, sourceSlotOrigin + local, 0);
  float selected = selectionMode == 0 ? 1.0 : texelFetch(selectionPage, selectionSlotOrigin + local, 0).r;
  fragmentColor = color * (selectionMode == 2 ? 1.0 - selected : selected);
}
