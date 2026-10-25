#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D tilePage;
layout(binding = 2) uniform highp sampler2D coverage;

UNIFORMS_BEGIN(SelectionCombineParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(bool, hasTile)
  UNIFORM(int, mode)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy);
  float existing = hasTile ? texelFetch(tilePage, slotOrigin + local, 0).r : 0.0;
  float shape = texelFetch(coverage, local, 0).r;
  float result = shape;
  if (mode == 1) {
    result = max(existing, shape);
  } else if (mode == 2) {
    result = existing * (1.0 - shape);
  } else if (mode == 3) {
    result = min(existing, shape);
  }
  fragmentColor = vec4(vec3(result), 1.0);
}
