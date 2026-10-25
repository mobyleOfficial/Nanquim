#version 310 es
void main() {
  vec2 corner = vec2(float((VERTEX_INDEX << 1) & 2), float(VERTEX_INDEX & 2));
  gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
