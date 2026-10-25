#version 310 es
precision highp float;
precision highp int;

UNIFORMS_BEGIN(TestPatternParameters)
  UNIFORM(ivec2, slotOrigin)
  UNIFORM(ivec2, tileCanvasOrigin)
  UNIFORM(vec3, baseColor)
  UNIFORM(float, seed)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

void main() {
  vec2 local = vec2(ivec2(gl_FragCoord.xy) - slotOrigin) + 0.5;
  vec2 canvas = vec2(tileCanvasOrigin) + local;
  float waves = 0.5 + 0.25 * sin(canvas.x * 0.031 + seed) + 0.25 * sin(canvas.y * 0.023 + seed * 1.7);
  float disc = 1.0 - smoothstep(60.0, 120.0, length(local - 128.0));
  float alpha = clamp(max(waves * 0.6, disc), 0.0, 1.0);
  fragmentColor = vec4(baseColor * alpha, alpha);
}
