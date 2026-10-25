vec4 glaze(vec4 destination, float coverage, vec3 baseColor) {
  float cap = clamp(gradation.z, 0.0, 1.0);
#ifdef GAMMA
  vec3 glazeColor = srgbToLinear(baseColor);
#else
  vec3 glazeColor = baseColor;
#endif
#ifdef GLAZE_FLOW
  float rate = clamp(coverage * gradation.w, 0.0, 1.0);
#else
  float rate = clamp(coverage, 0.0, 1.0);
#endif
  if (rate > 0.0 && cap > destination.a) {
#ifdef GLAZE_HEAVY
    float alpha = destination.a + (cap - destination.a) * rate;
    return vec4(glazeColor * alpha, alpha);
#else
    return destination + (vec4(glazeColor * cap, cap) - destination) * rate;
#endif
  }
  return destination;
}
