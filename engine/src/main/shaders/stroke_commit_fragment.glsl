#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D strokePage;

UNIFORMS_BEGIN(StrokeCommitParameters)
  UNIFORM(ivec2, strokeSlotOrigin)
  UNIFORM(ivec2, targetSlotOrigin)
  UNIFORM(float, opacity)
  UNIFORM(float, maskLuminance)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  vec4 stroke = texelFetch(strokePage, ivec2(gl_FragCoord.xy) - targetSlotOrigin + strokeSlotOrigin, 0);
#if defined(MASK_TARGET)
  float coverage = stroke.a * opacity;
  fragmentColor = vec4(vec3(maskLuminance), 1.0) * coverage;
#elif defined(ERASE)
  fragmentColor = vec4(0.0, 0.0, 0.0, stroke.a * opacity);
#else
  fragmentColor = stroke * opacity;
#endif
}
