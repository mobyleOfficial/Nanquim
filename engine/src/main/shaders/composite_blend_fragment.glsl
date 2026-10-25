#version 310 es
precision highp float;
precision highp int;

layout(binding = 1) uniform highp sampler2D backdropTexture;
layout(binding = 2) uniform highp sampler2D sourceTexture;
layout(binding = 3) uniform highp sampler2D maskTexture;
UNIFORMS_BEGIN(CompositeBlendParameters)
  UNIFORM(ivec2, targetOrigin)
  UNIFORM(ivec2, backdropOrigin)
  UNIFORM(bool, hasSource)
  UNIFORM(ivec2, sourceOrigin)
  UNIFORM(int, sourceStride)
  UNIFORM(int, maskMode)
  UNIFORM(bool, maskInverted)
  UNIFORM(ivec2, maskOrigin)
  UNIFORM(int, maskStride)
  UNIFORM(float, opacity)
  UNIFORM(int, blendMode)
  UNIFORM(bool, clipToBackdrop)
  UNIFORM(vec4, sourceTint)
UNIFORMS_END

layout(location = 0) out vec4 fragmentColor;

// @blend_functions

void main() {
  ivec2 local = ivec2(gl_FragCoord.xy) - targetOrigin;
  vec4 backdrop = texelFetch(backdropTexture, backdropOrigin + local, 0);
  vec4 source = hasSource ? texelFetch(sourceTexture, sourceOrigin + local * sourceStride, 0)
                          : vec4(0.0);
  if (maskMode != 0) {
    float value = maskMode == 1 ? texelFetch(maskTexture, maskOrigin + local * maskStride, 0).r : 1.0;
    source *= maskInverted ? 1.0 - value : value;
  }
  source = vec4(mix(source.rgb, sourceTint.rgb * source.a, sourceTint.a), source.a);
  source *= opacity;
  fragmentColor = clipToBackdrop ? blendClip(blendMode, backdrop, source)
                                 : blendComposite(blendMode, backdrop, source);
}
