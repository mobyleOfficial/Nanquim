#version 310 es
precision highp float;
precision highp int;

UNIFORMS_BEGIN(ViewportParameters)
  UNIFORM(vec4, screenToCanvasLinear)
  UNIFORM(vec4, shadowColor)
  UNIFORM(vec3, surroundColor)
  UNIFORM(float, fineLevelScale)
  UNIFORM(vec2, fineLevelSize)
  UNIFORM(vec2, coarseLevelSize)
  UNIFORM(vec2, screenToCanvasOffset)
  UNIFORM(vec2, canvasSize)
  UNIFORM(vec2, selectionSize)
  UNIFORM(float, coarseLevelScale)
  UNIFORM(float, coarseWeight)
  UNIFORM(float, surfaceHeight)
  UNIFORM(float, zoom)
  UNIFORM(float, shadowRadiusPixels)
  UNIFORM(float, selectionReduction)
  UNIFORM(float, antsPhase)
  UNIFORM(bool, showCheckerboard)
  UNIFORM(bool, linearComposite)
  UNIFORM(bool, useDisplayLut)
  UNIFORM(bool, showDirtyOverlay)
  UNIFORM(bool, showSelection)
  UNIFORM(bool, dimOutsideSelection)
  UNIFORM(bool, rowsFromBottom)
UNIFORMS_END

layout(binding = 1) uniform highp sampler2D fineLevel;
layout(binding = 2) uniform highp sampler2D coarseLevel;
layout(binding = 3) uniform highp sampler2D dirtyTileAges;
layout(binding = 4) uniform highp sampler2D selectionMask;
layout(binding = 5) uniform highp sampler3D displayLut;

layout(location = 0) out vec4 fragmentColor;

// @color_space

const float kDisplayLutSize = 33.0;
const float kTileSize = 256.0;
const float kCheckerCellPixels = 12.0;
const float kOutsideSelectionDim = 0.3;
const float kAntsDashPixels = 6.0;

void main() {
  vec2 screen = vec2(gl_FragCoord.x, rowsFromBottom ? surfaceHeight - gl_FragCoord.y : gl_FragCoord.y);
  vec2 canvas = mat2(screenToCanvasLinear.xy, screenToCanvasLinear.zw) * screen + screenToCanvasOffset;
  float selected = 1.0;
  float selectedChange = 0.0;
  if (showSelection) {
    selected = texture(selectionMask, canvas / selectionReduction / selectionSize).r;
    selectedChange = fwidth(selected);
  }

  vec2 outsideBy = max(max(-canvas, canvas - canvasSize), vec2(0.0));
  if (outsideBy.x > 0.0 || outsideBy.y > 0.0) {
    float distancePixels = length(outsideBy) * zoom;
    float shadow = shadowColor.a * (1.0 - smoothstep(0.0, shadowRadiusPixels, distancePixels));
    fragmentColor = vec4(mix(surroundColor, shadowColor.rgb, shadow), 1.0);
    return;
  }

  vec4 color = texture(fineLevel, canvas / fineLevelScale / fineLevelSize);
  if (coarseWeight > 0.0) {
    vec4 coarse = texture(coarseLevel, canvas / coarseLevelScale / coarseLevelSize);
    color = mix(color, coarse, coarseWeight);
  }

  vec3 base = surroundColor;
  if (showCheckerboard) {
    vec2 cell = floor(screen / kCheckerCellPixels);
    base = mod(cell.x + cell.y, 2.0) < 1.0 ? vec3(0.92) : vec3(0.78);
  }
  vec3 result = linearComposite
                    ? linearToSrgb(color.rgb + (1.0 - color.a) * srgbToLinear(base))
                    : color.rgb + (1.0 - color.a) * base;
  if (useDisplayLut) {
    vec3 lutCoordinate = clamp(result, 0.0, 1.0) * ((kDisplayLutSize - 1.0) / kDisplayLutSize) +
                         0.5 / kDisplayLutSize;
    result = texture(displayLut, lutCoordinate).rgb;
  }

  if (showDirtyOverlay) {
    ivec2 tile = ivec2(canvas / kTileSize);
    float age = texelFetch(dirtyTileAges, tile, 0).r;
    vec2 inTile = mod(canvas, kTileSize) * zoom;
    float tileOnScreen = kTileSize * zoom;
    float edgeDistance = min(min(inTile.x, inTile.y), min(tileOnScreen - inTile.x, tileOnScreen - inTile.y));
    if (age > 0.0 && edgeDistance < 2.0) {
      result = mix(result, vec3(1.0, 0.1, 0.6), age);
    }
  }
  if (showSelection) {
    if (dimOutsideSelection) {
      result *= 1.0 - kOutsideSelectionDim * (1.0 - selected);
    }
    if (abs(selected - 0.5) < 0.75 * max(selectedChange, 1e-4)) {
      float dash = mod(screen.x + screen.y + antsPhase, 2.0 * kAntsDashPixels);
      result = vec3(dash < kAntsDashPixels ? 0.0 : 1.0);
    }
  }
  fragmentColor = vec4(result, 1.0);
}
