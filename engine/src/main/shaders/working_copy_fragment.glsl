#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D sourcePage;

UNIFORMS_BEGIN(WorkingCopyParameters)
  UNIFORM(ivec2, sourceSlotOrigin)
  UNIFORM(ivec2, targetOrigin)
UNIFORMS_END

layout(location = 0) out highp uvec4 fragmentColor;

void main() {
#ifdef WORKING_HALF
  ivec2 texel = ivec2(gl_FragCoord.xy) - ivec2(targetOrigin.x * 2, targetOrigin.y);
  vec4 color = texelFetch(sourcePage, sourceSlotOrigin + ivec2(texel.x / 2, texel.y), 0);
  fragmentColor = uvec4((texel.x & 1) == 0 ? packHalf2x16(color.rg) : packHalf2x16(color.ba), 0u, 0u, 0u);
#else
  ivec2 local = ivec2(gl_FragCoord.xy) - targetOrigin;
  fragmentColor = uvec4(packUnorm4x8(texelFetch(sourcePage, sourceSlotOrigin + local, 0)), 0u, 0u, 0u);
#endif
}
