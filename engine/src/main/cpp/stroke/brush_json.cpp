#include "stroke/brush_json.h"

#include <algorithm>
#include <map>
#include <stdexcept>
#include <utility>
#include <vector>

namespace nanquim::stroke {

namespace {

using Json = nlohmann::json;

constexpr int kBrushFormat = 1;
constexpr std::string_view kCurvePrefix = "curve:";

class Section {
 public:
  Section(const Json* object, std::string path) : object_(object), path_(std::move(path)) {
    if (object_ != nullptr && !object_->is_object()) {
      throw std::invalid_argument(path_ + " must be an object");
    }
  }

  [[nodiscard]] Section child(const char* name) const {
    const Json* value = field(name);
    return Section(value, path_ + "." + name);
  }

  [[nodiscard]] double number(const char* name, double fallback, double minimum,
                              double maximum) const {
    const Json* value = field(name);
    if (value == nullptr) {
      return fallback;
    }
    if (!value->is_number()) {
      throw std::invalid_argument(path_ + "." + name + " must be a number");
    }
    return std::clamp(value->get<double>(), minimum, maximum);
  }

  [[nodiscard]] bool boolean(const char* name, bool fallback) const {
    const Json* value = field(name);
    if (value == nullptr) {
      return fallback;
    }
    if (!value->is_boolean()) {
      throw std::invalid_argument(path_ + "." + name + " must be true or false");
    }
    return value->get<bool>();
  }

  [[nodiscard]] std::string text(const char* name, const std::string& fallback) const {
    if (object_ == nullptr || !object_->contains(name)) {
      return fallback;
    }
    const Json& value = object_->at(name);
    if (value.is_null()) {
      return {};
    }
    if (!value.is_string()) {
      throw std::invalid_argument(path_ + "." + name + " must be a string");
    }
    return value.get<std::string>();
  }

  [[nodiscard]] bool has(const char* name) const { return field(name) != nullptr; }
  [[nodiscard]] const std::string& path() const { return path_; }

 private:
  [[nodiscard]] const Json* field(const char* name) const {
    if (object_ == nullptr) {
      return nullptr;
    }
    const auto found = object_->find(name);
    if (found == object_->end() || found->is_null()) {
      return nullptr;
    }
    return &*found;
  }

  const Json* object_;
  std::string path_;
};

class CurveTable {
 public:
  explicit CurveTable(const Json& document) {
    const auto found = document.find("curves");
    if (found == document.end() || found->is_null()) {
      return;
    }
    if (!found->is_object()) {
      throw std::invalid_argument("curves must be an object");
    }
    for (const auto& [name, value] : found->items()) {
      if (!value.is_array()) {
        throw std::invalid_argument("curve " + name + " must be a list of points");
      }
      std::vector<std::pair<double, double>> points;
      for (const Json& point : value) {
        if (!point.is_array() || point.size() != 2 || !point.at(0).is_number() ||
            !point.at(1).is_number()) {
          throw std::invalid_argument("curve " + name + ": a point must be [x, y]");
        }
        points.emplace_back(point.at(0).get<double>(), point.at(1).get<double>());
      }
      curves_.emplace(name, Curve(std::move(points)));
    }
  }

  [[nodiscard]] std::optional<Curve> reference(const Section& section, const char* name) const {
    const std::string value = section.text(name, {});
    if (value.empty()) {
      return std::nullopt;
    }
    if (value.compare(0, kCurvePrefix.size(), kCurvePrefix) != 0) {
      throw std::invalid_argument(section.path() + "." + name + " must be \"curve:<name>\"");
    }
    const std::string curveName = value.substr(kCurvePrefix.size());
    if (const auto found = curves_.find(curveName); found != curves_.end()) {
      return found->second;
    }
    if (curveName == "linear") {
      return Curve::linear();
    }
    if (curveName == "soft") {
      return Curve::soft();
    }
    if (curveName == "hard") {
      return Curve::hard();
    }
    throw std::invalid_argument(section.path() + "." + name + ": unknown curve " + curveName);
  }

 private:
  std::map<std::string, Curve> curves_;
};

template <typename Enum>
Enum choice(const Section& section, const char* name, Enum fallback,
            std::initializer_list<std::pair<std::string_view, Enum>> options) {
  if (!section.has(name)) {
    return fallback;
  }
  const std::string value = section.text(name, {});
  for (const auto& [option, result] : options) {
    if (value == option) {
      return result;
    }
  }
  throw std::invalid_argument(section.path() + "." + name + ": unknown value " + value);
}

TextureFiltering filtering(const Section& section, TextureFiltering fallback) {
  return choice(section, "filtering", fallback,
                {{"none", TextureFiltering::None},
                 {"classic", TextureFiltering::Classic},
                 {"improved", TextureFiltering::Improved}});
}

Dynamics dynamicsEntry(const Section& section, const CurveTable& curves, Dynamics fallback) {
  Dynamics result;
  result.minimum = section.number("min", fallback.minimum, 0, 1);
  result.pressure =
      section.has("pressure") ? curves.reference(section, "pressure") : fallback.pressure;
  result.pressureAmount = section.number("pressureAmount", fallback.pressureAmount, 0, 1);
  result.tilt = section.has("tilt") ? curves.reference(section, "tilt") : fallback.tilt;
  result.tiltAmount = section.number("tiltAmount", fallback.tiltAmount, 0, 1);
  result.speed = section.number("speed", fallback.speed, -1, 1);
  result.jitter = section.number("jitter", fallback.jitter, 0, 1);
  return result;
}

TaperSettings taperSettings(const Section& section) {
  TaperSettings result;
  result.start = section.number("start", result.start, 0, 1e6);
  result.end = section.number("end", result.end, 0, 1e6);
  result.relative = section.boolean("relative", result.relative);
  result.minSize = section.number("minSize", result.minSize, 0, 1);
  result.minOpacity = section.number("minOpacity", result.minOpacity, 0, 1);
  result.pressureMode = section.boolean("pressureMode", result.pressureMode);
  result.shape = section.number("shape", result.shape, -1, 1);
  result.tip = section.number("tip", result.tip, 0, 1);
  result.tipAnimation = section.boolean("tipAnimation", result.tipAnimation);
  return result;
}

BrushParameters readBrush(const Json& document) {
  if (!document.is_object()) {
    throw std::invalid_argument("a brush must be a JSON object");
  }
  const Section root(&document, "brush");
  if (root.number("format", 0, 0, 1e9) != kBrushFormat) {
    throw std::invalid_argument("unsupported brush format");
  }
  const CurveTable curves(document);
  BrushParameters brush;

  const Section path = root.child("path");
  PathParameters& pathParameters = brush.path;
  pathParameters.spacing = path.number("spacing", pathParameters.spacing, 0.005, 2);
  pathParameters.jitterLateral = path.number("jitterLateral", 0, 0, 2);
  pathParameters.streamline = path.number("streamline", 0, 0, 1);
  pathParameters.stabilization = path.number("stabilization", 0, 0, 1);
  pathParameters.jitterFilter = path.number("jitterFilter", 0, 0, 1);
  pathParameters.pressureSmoothing = path.number("pressureSmoothing", 0, 0, 1);
  pathParameters.falloff = path.number("falloff", 0, 0, 1);
  pathParameters.jitterFilterExpression = path.number("jitterFilterExpression", 0, 0, 1);

  const Section shape = root.child("shape");
  ShapeParameters& shapeParameters = brush.shape;
  shapeParameters.texture = shape.text("texture", "shape.png");
  shapeParameters.hardness = shape.number("hardness", 1, 0, 1);
  shapeParameters.rotationMode = choice(shape, "rotationMode", RotationMode::FollowStroke,
                                        {{"fixed", RotationMode::Fixed},
                                         {"followStroke", RotationMode::FollowStroke},
                                         {"penAzimuth", RotationMode::PenAzimuth},
                                         {"random", RotationMode::Random}});
  if (shape.boolean("azimuth", false)) {
    shapeParameters.rotationMode = RotationMode::PenAzimuth;
  }
  constexpr double kMaximumAngle = 1e3;
  shapeParameters.rotation = shape.number("rotation", 0, -kMaximumAngle, kMaximumAngle);
  shapeParameters.rotationJitter = shape.number("rotationJitter", 0, 0, 1);
  shapeParameters.roundness = shape.number("roundness", 1, 0.05, 1);
  shapeParameters.pressureRoundness = curves.reference(shape, "pressureRoundness");
  shapeParameters.pressureRoundnessAmount = shape.number("pressureRoundnessAmount", 0, 0, 1);
  shapeParameters.tiltRoundnessAmount = shape.number("tiltRoundnessAmount", 0, 0, 1);
  shapeParameters.scatter = shape.number("scatter", 0, 0, 2);
  shapeParameters.count = static_cast<int32_t>(shape.number("count", 1, 1, 16));
  shapeParameters.countJitter = shape.number("countJitter", 0, 0, 1);
  shapeParameters.flipXJitter = shape.number("flipXJitter", 0, 0, 1);
  shapeParameters.flipYJitter = shape.number("flipYJitter", 0, 0, 1);
  shapeParameters.invert = shape.boolean("invert", false);
  shapeParameters.filtering = filtering(shape, TextureFiltering::Improved);
  shapeParameters.randomized = shape.boolean("randomized", false);

  const Section grain = root.child("grain");
  GrainParameters& grainParameters = brush.grain;
  grainParameters.texture = grain.text("texture", {});
  const bool moving = choice(grain, "mode", true, {{"moving", true}, {"texturized", false}});
  grainParameters.movement = grain.number("movement", moving ? 1.0 : 0.0, 0, 1);
  grainParameters.zoom = grain.number("zoom", 0, 0, 1);
  grainParameters.scale = grain.number("scale", 1, 0.05, 16);
  grainParameters.rotation = grain.number("rotation", 0, -kMaximumAngle, kMaximumAngle);
  grainParameters.rotateWithDab = grain.boolean("rotateWithDab", false);
  grainParameters.offsetJitter = grain.boolean("offsetJitter", true);
  grainParameters.depth = grain.number("depth", 1, 0, 1);
  grainParameters.depthMinimum = grain.number("depthMinimum", 0, 0, 1);
  grainParameters.depthJitter = grain.number("depthJitter", 0, 0, 1);
  grainParameters.pressureDepth = curves.reference(grain, "pressureDepth");
  grainParameters.pressureDepthAmount = grain.number("pressureDepthAmount", 0, 0, 1);
  grainParameters.brightness = grain.number("brightness", 0, -1, 1);
  grainParameters.contrast = grain.number("contrast", 0, -1, 1);
  grainParameters.invert = grain.boolean("invert", false);
  grainParameters.blend = choice(grain, "blend", GrainBlend::Multiply,
                                 {{"multiply", GrainBlend::Multiply},
                                  {"subtract", GrainBlend::Subtract},
                                  {"linearBurn", GrainBlend::LinearBurn},
                                  {"colorBurn", GrainBlend::ColorBurn},
                                  {"overlay", GrainBlend::Overlay},
                                  {"height", GrainBlend::Height}});
  grainParameters.perStroke = grain.boolean("perStroke", false);
  grainParameters.filtering = filtering(grain, TextureFiltering::Improved);

  const Section dynamics = root.child("dynamics");
  DynamicsParameters& dynamicsParameters = brush.dynamics;
  const Section size = dynamics.child("size");
  dynamicsParameters.size = dynamicsEntry(size, curves, Dynamics{});
  dynamicsParameters.tiltSizeCompression = size.number("tiltSizeCompression", 0, 0, 1);
  dynamicsParameters.opacity = dynamicsEntry(dynamics.child("opacity"), curves, Dynamics{});
  dynamicsParameters.flow = dynamicsEntry(dynamics.child("flow"), curves, Dynamics{});
  dynamicsParameters.bleed =
      dynamicsEntry(dynamics.child("bleed"), curves, dynamicsParameters.bleed);
  dynamicsParameters.tiltGradation = dynamics.number("tiltGradation", 0, 0, 1);
  dynamicsParameters.fingerPressureFromSpeed =
      choice(dynamics, "fingerPressure", false, {{"constant", false}, {"speed", true}});

  const Section taper = root.child("taper");
  brush.taper.stylus = taperSettings(taper.child("stylus"));
  brush.taper.finger = taperSettings(taper.child("finger"));
  brush.taper.classic = taper.boolean("classic", false);

  const Section render = root.child("render");
  brush.render.accumulation = choice(render, "accumulation", Accumulation::GlazeLight,
                                     {{"buildUp", Accumulation::BuildUp},
                                      {"wash", Accumulation::Wash},
                                      {"glazeLight", Accumulation::GlazeLight},
                                      {"glazeUniform", Accumulation::GlazeUniform},
                                      {"glazeIntense", Accumulation::GlazeIntense},
                                      {"glazeHeavy", Accumulation::GlazeHeavy},
                                      {"blendUniform", Accumulation::BlendUniform},
                                      {"blendIntense", Accumulation::BlendIntense}});
  brush.render.flow = render.number("flow", 1, 0, 1);
  if (render.has("blend")) {
    const std::string blend = render.text("blend", {});
    const auto mode = canvas::blendModeFromId(blend);
    if (!mode || *mode == canvas::BlendMode::PassThrough) {
      throw std::invalid_argument(render.path() + ".blend: unknown value " + blend);
    }
    brush.render.blend = *mode;
  }
  brush.render.wetEdges = render.number("wetEdges", 0, 0, 1);
  brush.render.burntEdges = render.number("burntEdges", 0, 0, 1);
  if (render.has("burntEdgesBlend")) {
    const std::string blend = render.text("burntEdgesBlend", {});
    const auto mode = canvas::blendModeFromId(blend);
    if (!mode || *mode == canvas::BlendMode::PassThrough || *mode == canvas::BlendMode::Behind) {
      throw std::invalid_argument(render.path() + ".burntEdgesBlend: unknown value " + blend);
    }
    brush.render.burntEdgesBlend = *mode;
  }
  brush.render.gammaCorrect = render.boolean("gammaCorrect", false);
  brush.render.luminanceBlending = render.boolean("luminanceBlending", false);

  const Section color = root.child("colorDynamics");
  ColorDynamicsParameters& colorParameters = brush.colorDynamics;
  colorParameters.hueJitter = color.number("hueJitter", 0, 0, 1);
  colorParameters.saturationJitter = color.number("saturationJitter", 0, 0, 1);
  colorParameters.brightnessJitter = color.number("brightnessJitter", 0, 0, 1);
  colorParameters.darknessJitter = color.number("darknessJitter", 0, 0, 1);
  colorParameters.strokeHueJitter = color.number("strokeHueJitter", 0, 0, 1);
  colorParameters.strokeSaturationJitter = color.number("strokeSaturationJitter", 0, 0, 1);
  colorParameters.strokeBrightnessJitter = color.number("strokeBrightnessJitter", 0, 0, 1);
  colorParameters.strokeDarknessJitter = color.number("strokeDarknessJitter", 0, 0, 1);
  colorParameters.pressureHue = color.number("pressureHue", 0, -1, 1);
  colorParameters.pressureSaturation = color.number("pressureSaturation", 0, -1, 1);
  colorParameters.pressureBrightness = color.number("pressureBrightness", 0, -1, 1);
  colorParameters.tiltHue = color.number("tiltHue", 0, -1, 1);
  colorParameters.tiltSaturation = color.number("tiltSaturation", 0, -1, 1);
  colorParameters.tiltBrightness = color.number("tiltBrightness", 0, -1, 1);
  colorParameters.secondaryColorByPressure = color.number("secondaryColorByPressure", 0, 0, 1);
  colorParameters.tiltSecondaryColor = color.number("tiltSecondaryColor", 0, 0, 1);
  colorParameters.secondaryColorJitter = color.number("secondaryColorJitter", 0, 0, 1);
  colorParameters.strokeSecondaryColorJitter =
      color.number("strokeSecondaryColorJitter", 0, 0, 1);
  colorParameters.pressureCurve = curves.reference(color, "pressureColorCurve");

  const Section limits = root.child("limits");
  constexpr double kMaximumSizePixels = 10000;
  brush.limits.minSize = limits.number("minSize", 1, 0.1, kMaximumSizePixels);
  brush.limits.maxSize = limits.number("maxSize", 500, brush.limits.minSize, kMaximumSizePixels);
  brush.limits.minOpacity = limits.number("minOpacity", 0, 0, 1);
  brush.limits.maxOpacity = limits.number("maxOpacity", 1, brush.limits.minOpacity, 1);

  const Section defaults = root.child("defaults");
  brush.defaults.size = defaults.number("size", brush.defaults.size, 0, 1);
  brush.defaults.opacity = defaults.number("opacity", brush.defaults.opacity, 0, 1);

  const Section properties = root.child("properties");
  brush.orientToScreen = properties.boolean("orientToScreen", false);

  const Section smudge = root.child("smudge");
  brush.smudge.strength = smudge.number("strength", properties.number("smudge", 0.8, 0, 1), 0, 1);
  brush.smudge.pickUp = smudge.number("pickUp", brush.smudge.pickUp, 0, 1);
  brush.smudge.length = smudge.number("length", brush.smudge.length, 0, 1);
  brush.smudge.useBrushColor = smudge.number("useBrushColor", 0, 0, 1);

  const Section dual = root.child("dual");
  brush.dual.enabled = dual.boolean("enabled", true);
  brush.dual.brushId = dual.text("brushId", {});
  brush.dual.blend = choice(dual, "blend", DualBlend::Multiply,
                            {{"multiply", DualBlend::Multiply},
                             {"subtract", DualBlend::Subtract},
                             {"darken", DualBlend::Darken},
                             {"lighten", DualBlend::Lighten},
                             {"overlay", DualBlend::Overlay},
                             {"height", DualBlend::Height}});

  const Section stamp = root.child("stamp");
  brush.stamp.enabled = stamp.boolean("enabled", false);
  brush.stamp.placeOnRelease = stamp.boolean("placeOnRelease", false);
  brush.stamp.keepColors = stamp.boolean("keepColors", false);

  const Section roll = root.child("roll");
  brush.roll.enabled = roll.boolean("enabled", false);
  brush.roll.amount = roll.number("amount", brush.roll.amount, -4, 4);

  const Section particles = root.child("particles");
  ParticleParameters& particle = brush.particles;
  particle.rate = particles.number("rate", 0, 0, 1000);
  particle.lifetimeMs = particles.number("lifetimeMs", particle.lifetimeMs, 1, 10000);
  particle.initialSpeed = particles.number("initialSpeed", particle.initialSpeed, 0, 20000);
  particle.speedJitter = particles.number("speedJitter", 0, 0, 1);
  particle.direction = choice(particles, "direction", ParticleDirection::Random,
                              {{"followStroke", ParticleDirection::FollowStroke},
                               {"random", ParticleDirection::Random},
                               {"outward", ParticleDirection::Outward}});
  particle.gravity = particles.number("gravity", 0, -100000, 100000);
  particle.drag = particles.number("drag", 0, 0, 1);
  particle.sizeStart = particles.number("sizeStart", particle.sizeStart, 0, 4);
  particle.sizeEnd = particles.number("sizeEnd", particle.sizeEnd, 0, 4);
  particle.opacityStart = particles.number("opacityStart", 1, 0, 1);
  particle.opacityEnd = particles.number("opacityEnd", 0, 0, 1);
  particle.rotationSpeed = particles.number("rotationSpeed", 0, -1000, 1000);
  particle.useShape = particles.boolean("useShape", true);

  const Section wetMix = root.child("wetMix");
  WetMixParameters& wet = brush.wetMix;
  wet.enabled = wetMix.boolean("enabled", false);
  wet.dilution = wetMix.number("dilution", wet.dilution, 0, 1);
  wet.charge = wetMix.number("charge", wet.charge, 0, 1);
  wet.attack = wetMix.number("attack", wet.attack, 0, 1);
  wet.pull = wetMix.number("pull", wet.pull, 0, 1);
  wet.grade = wetMix.number("grade", wet.grade, 0, 1);
  wet.blur = wetMix.number("blur", wet.blur, 0, 1);
  wet.blurJitter = wetMix.number("blurJitter", wet.blurJitter, 0, 1);
  wet.wetnessJitter = wetMix.number("wetnessJitter", wet.wetnessJitter, 0, 1);
  return brush;
}

}  // namespace

std::optional<BrushParameters> parseBrush(const nlohmann::json& document, std::string& error) {
  try {
    return readBrush(document);
  } catch (const std::exception& exception) {
    error = exception.what();
    return std::nullopt;
  }
}

std::optional<BrushParameters> parseBrush(std::string_view jsonText, std::string& error) {
  try {
    return readBrush(Json::parse(jsonText));
  } catch (const std::exception& exception) {
    error = exception.what();
    return std::nullopt;
  }
}

}  // namespace nanquim::stroke
