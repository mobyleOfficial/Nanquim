UNIFORMS_BEGIN(StrokeDabParameters)
  UNIFORM(vec2, tileOrigin)
  UNIFORM(float, extentScale)
  // @dab_coverage_uniforms
  UNIFORM(ivec2, cloneOffset)
  UNIFORM(ivec2, cloneSize)
  UNIFORM(ivec2, layerSize)
  UNIFORM(float, layerMixAmount)
UNIFORMS_END
