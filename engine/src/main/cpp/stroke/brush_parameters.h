#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "canvas/blend_mode.h"
#include "core/affine_transform.h"
#include "stroke/curve.h"

namespace nanquim::stroke {

enum class RotationMode : int32_t { Fixed = 0, FollowStroke = 1, PenAzimuth = 2, Random = 3 };

enum class Accumulation : int32_t {
  BuildUp = 0,
  Wash = 1,
  GlazeLight = 2,
  GlazeUniform = 3,
  GlazeIntense = 4,
  GlazeHeavy = 5,
  BlendUniform = 6,
  BlendIntense = 7,
};

enum class TextureFiltering : int32_t { None = 0, Classic = 1, Improved = 2 };

enum class GrainBlend : int32_t {
  Multiply = 0,
  Subtract = 1,
  LinearBurn = 2,
  ColorBurn = 3,
  Overlay = 4,
  Height = 5,
};

struct Dynamics {
  double minimum = 0;
  std::optional<Curve> pressure;
  double pressureAmount = 1;
  std::optional<Curve> tilt;
  double tiltAmount = 0;
  double speed = 0;
  double jitter = 0;
};

struct PathParameters {
  double spacing = 0.1;
  double jitterLateral = 0;
  double streamline = 0;
  double stabilization = 0;
  double jitterFilter = 0;
  double pressureSmoothing = 0;
  double falloff = 0;
  double jitterFilterExpression = 0;
};

struct ShapeParameters {
  std::string texture;
  double hardness = 1;
  RotationMode rotationMode = RotationMode::FollowStroke;
  double rotation = 0;
  double rotationJitter = 0;
  double roundness = 1;
  std::optional<Curve> pressureRoundness;
  double pressureRoundnessAmount = 0;
  double tiltRoundnessAmount = 0;
  double scatter = 0;
  int32_t count = 1;
  double countJitter = 0;
  double flipXJitter = 0;
  double flipYJitter = 0;
  bool invert = false;
  TextureFiltering filtering = TextureFiltering::Improved;
  bool randomized = false;
};

struct GrainParameters {
  std::string texture;
  double movement = 1;
  double zoom = 0;
  double scale = 1;
  double rotation = 0;
  bool rotateWithDab = false;
  bool offsetJitter = true;
  double depth = 1;
  double depthMinimum = 0;
  double depthJitter = 0;
  std::optional<Curve> pressureDepth;
  double pressureDepthAmount = 0;
  double brightness = 0;
  double contrast = 0;
  bool invert = false;
  GrainBlend blend = GrainBlend::Multiply;
  bool perStroke = false;
  TextureFiltering filtering = TextureFiltering::Improved;

  [[nodiscard]] bool enabled() const { return !texture.empty(); }
  [[nodiscard]] bool moving() const { return movement >= 1.0; }
};

struct DynamicsParameters {
  Dynamics size;
  Dynamics opacity;
  Dynamics flow;
  Dynamics bleed{0, std::nullopt, 0, std::nullopt, 0, 0, 0};
  double tiltSizeCompression = 0;
  double tiltGradation = 0;
  bool fingerPressureFromSpeed = false;
};

struct TaperSettings {
  double start = 0;
  double end = 0;
  bool relative = false;
  double minSize = 0;
  double minOpacity = 1;
  bool pressureMode = true;
  double shape = 0;
  double tip = 1;
  bool tipAnimation = false;
};

struct TaperParameters {
  TaperSettings stylus;
  TaperSettings finger;
  bool classic = false;
};

struct RenderParameters {
  Accumulation accumulation = Accumulation::GlazeLight;
  double flow = 1;
  canvas::BlendMode blend = canvas::BlendMode::Normal;
  double wetEdges = 0;
  double burntEdges = 0;
  canvas::BlendMode burntEdgesBlend = canvas::BlendMode::Multiply;
  bool gammaCorrect = false;
  bool luminanceBlending = false;

  [[nodiscard]] bool hasEdges() const { return wetEdges > 0 || burntEdges > 0; }
};

struct ColorDynamicsParameters {
  double hueJitter = 0;
  double saturationJitter = 0;
  double brightnessJitter = 0;
  double darknessJitter = 0;
  double strokeHueJitter = 0;
  double strokeSaturationJitter = 0;
  double strokeBrightnessJitter = 0;
  double strokeDarknessJitter = 0;
  double pressureHue = 0;
  double pressureSaturation = 0;
  double pressureBrightness = 0;
  double tiltHue = 0;
  double tiltSaturation = 0;
  double tiltBrightness = 0;
  double secondaryColorByPressure = 0;
  double tiltSecondaryColor = 0;
  double secondaryColorJitter = 0;
  double strokeSecondaryColorJitter = 0;
  std::optional<Curve> pressureCurve;

  [[nodiscard]] bool changesHsb() const {
    return hueJitter != 0 || saturationJitter != 0 || brightnessJitter != 0 ||
           darknessJitter != 0 || strokeHueJitter != 0 || strokeSaturationJitter != 0 ||
           strokeBrightnessJitter != 0 || strokeDarknessJitter != 0 || pressureHue != 0 || pressureSaturation != 0 ||
           pressureBrightness != 0 || tiltHue != 0 || tiltSaturation != 0 || tiltBrightness != 0;
  }
};

enum class DualBlend : int32_t {
  Multiply = 0,
  Subtract = 1,
  Darken = 2,
  Lighten = 3,
  Overlay = 4,
  Height = 5,
};

struct DualParameters {
  bool enabled = true;
  std::string brushId;
  DualBlend blend = DualBlend::Multiply;

  [[nodiscard]] bool active() const { return enabled && !brushId.empty(); }
};

enum class ParticleDirection : int32_t { FollowStroke = 0, Random = 1, Outward = 2 };

struct ParticleParameters {
  double rate = 0;
  double lifetimeMs = 600;
  double initialSpeed = 150;
  double speedJitter = 0;
  ParticleDirection direction = ParticleDirection::Random;
  double gravity = 0;
  double drag = 0;
  double sizeStart = 0.3;
  double sizeEnd = 0.1;
  double opacityStart = 1;
  double opacityEnd = 0;
  double rotationSpeed = 0;
  bool useShape = true;

  [[nodiscard]] bool active() const { return rate > 0; }
};

struct RollParameters {
  bool enabled = false;
  double amount = 1;
};

struct StampParameters {
  bool enabled = false;
  bool placeOnRelease = false;
  bool keepColors = false;
};

struct SmudgeParameters {
  double strength = 0.8;
  double pickUp = 0.5;
  double length = 0.5;
  double useBrushColor = 0;
};

struct WetMixParameters {
  bool enabled = false;
  double dilution = 0;
  double charge = 1;
  double attack = 1;
  double pull = 0;
  double grade = 0;
  double blur = 0;
  double blurJitter = 0;
  double wetnessJitter = 0;
};

struct LimitParameters {
  double minSize = 1;
  double maxSize = 500;
  double minOpacity = 0;
  double maxOpacity = 1;
};

struct DefaultParameters {
  double size = 0.3;
  double opacity = 1;
};

struct BrushParameters {
  PathParameters path;
  ShapeParameters shape;
  GrainParameters grain;
  DynamicsParameters dynamics;
  TaperParameters taper;
  RenderParameters render;
  ColorDynamicsParameters colorDynamics;
  SmudgeParameters smudge;
  WetMixParameters wetMix;
  DualParameters dual;
  ParticleParameters particles;
  StampParameters stamp;
  RollParameters roll;
  LimitParameters limits;
  DefaultParameters defaults;
  bool orientToScreen = false;
};

inline BrushParameters roundBrush() {
  BrushParameters brush;
  brush.path.spacing = 0.05;
  brush.shape.hardness = 1;
  brush.dynamics.size.minimum = 0.15;
  brush.dynamics.size.pressure = Curve::linear();
  brush.dynamics.opacity.minimum = 0.4;
  brush.dynamics.opacity.pressure = Curve::soft();
  brush.render.accumulation = Accumulation::Wash;
  return brush;
}

inline bool isGlaze(Accumulation accumulation) {
  return accumulation == Accumulation::GlazeLight || accumulation == Accumulation::GlazeUniform ||
         accumulation == Accumulation::GlazeIntense || accumulation == Accumulation::GlazeHeavy;
}

constexpr double kLayerMixAmount = 0.5;

struct AccumulationPrimitive {
  Accumulation primitive = Accumulation::BuildUp;
  double flowScale = 1;
};

inline AccumulationPrimitive primitiveFor(Accumulation accumulation) {
  switch (accumulation) {
    case Accumulation::Wash:
      return {Accumulation::Wash, 1};
    case Accumulation::GlazeLight:
      return {Accumulation::BuildUp, 0.5};
    case Accumulation::BuildUp:
    case Accumulation::GlazeUniform:
    case Accumulation::GlazeIntense:
    case Accumulation::GlazeHeavy:
    case Accumulation::BlendUniform:
    case Accumulation::BlendIntense:
      return {Accumulation::BuildUp, 1};
  }
  return {};
}

struct StrokeSettings {
  double sizePixels = 20;
  double opacity = 1;
  std::array<double, 4> color{0, 0, 0, 1};
  std::array<double, 3> secondaryColor{1, 1, 1};
  uint64_t seed = 0;
  bool streamlineCatchUp = true;
  std::optional<Curve> pressureCurve;
  double globalSmoothing = 0;
  double viewRotation = 0;
  bool rollFromOrientation = false;
  std::vector<core::AffineTransform> symmetry;
  bool smoothPositions = true;
};

}  // namespace nanquim::stroke
