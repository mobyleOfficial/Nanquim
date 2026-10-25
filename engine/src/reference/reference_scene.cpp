#include "reference_scene.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <map>

#include "adjust/adjustment.h"
#include "adjust/spatial.h"
#include "canvas/color_conversion.h"
#include "canvas/flood_fill.h"
#include "reference_compositor.h"
#include "reference_renderer.h"
#include "stroke/dab_generator.h"
#include "stroke/particles.h"
#include "stroke/stroke_effects.h"
#include "text/layout.h"
#include "text/text_raster.h"

namespace nanquim::reference {

std::vector<stroke::Dab> generateDabs(const scene::StrokeAction& stroke) {
  stroke::DabGenerator generator(stroke.brush, stroke.settings);
  std::vector<stroke::Dab> dabs;
  for (const stroke::InputSample& sample : stroke.samples) {
    if (stroke::isCanceledSample(sample)) {
      return {};
    }
    if (stroke::isPathSample(sample)) {
      generator.addPoint(stroke::pathPointFrom(sample), dabs);
    }
  }
  generator.finish(dabs);
  return dabs;
}

namespace {

std::vector<uint8_t> floodCoverage(const Image& source, double x, double y, double threshold,
                                   bool expand, bool whole, bool linear) {
  const size_t count = static_cast<size_t>(source.width) * source.height;
  if (whole) {
    return std::vector<uint8_t>(count, 255);
  }
  std::vector<uint8_t> pixels(count * 4);
  if (linear) {
    for (size_t pixel = 0; pixel < count; ++pixel) {
      const std::array<float, 4> value{static_cast<float>(source.rgba[pixel * 4]),
                                       static_cast<float>(source.rgba[pixel * 4 + 1]),
                                       static_cast<float>(source.rgba[pixel * 4 + 2]),
                                       static_cast<float>(source.rgba[pixel * 4 + 3])};
      canvas::premultipliedLinearToSrgb8(value.data(), &pixels[pixel * 4]);
    }
  } else {
    for (size_t index = 0; index < pixels.size(); ++index) {
      pixels[index] =
          static_cast<uint8_t>(std::lround(std::clamp(source.rgba[index], 0.0, 1.0) * 255));
    }
  }
  canvas::FloodFillOptions options;
  options.threshold = threshold;
  options.expand = expand;
  return canvas::floodFillCoverage(pixels.data(), source.width, source.height,
                                   static_cast<int32_t>(std::floor(x)),
                                   static_cast<int32_t>(std::floor(y)), options);
}

std::vector<stroke::Dab> linearized(std::vector<stroke::Dab> dabs) {
  for (stroke::Dab& dab : dabs) {
    for (size_t channel = 0; channel < 3; ++channel) {
      dab.color[channel] = canvas::srgbToLinearValue(dab.color[channel]);
    }
  }
  return dabs;
}

}  // namespace

std::map<canvas::LayerId, Image> renderSceneSurfaces(const scene::Scene& scene) {
  std::map<canvas::LayerId, Image> surfaces;
  const canvas::PixelFormat format = scene.layers.pixelFormat;
  const bool linear = canvas::isLinear(format);
  auto inDocument = [linear](const std::vector<stroke::Dab>& dabs) {
    return linear ? linearized(dabs) : dabs;
  };

  auto surface = [&](canvas::LayerId id, bool mask) -> Image& {
    auto found = surfaces.find(id);
    if (found == surfaces.end()) {
      found = surfaces
                  .emplace(id, mask ? whiteMask(scene.width, scene.height)
                                    : Image(scene.width, scene.height))
                  .first;
    }
    return found->second;
  };
  auto selection = [&]() -> Image& { return surface(scene::kSelectionSurface, false); };
  for (const scene::SceneAction& action : scene.actions) {
    if (action.type == scene::SceneAction::Type::Select) {
      const scene::SelectAction& select = action.select;
      Image& image = selection();
      switch (select.operation) {
        case scene::SelectAction::Operation::All:
          std::fill(image.rgba.begin(), image.rgba.end(), 1.0);
          break;
        case scene::SelectAction::Operation::None:
          std::fill(image.rgba.begin(), image.rgba.end(), 0.0);
          break;
        case scene::SelectAction::Operation::Invert:
          for (size_t offset = 0; offset < image.rgba.size(); offset += 4) {
            const double value = 1 - image.rgba[offset];
            image.rgba[offset] = image.rgba[offset + 1] = image.rgba[offset + 2] = value;
            image.rgba[offset + 3] = 1;
          }
          break;
        case scene::SelectAction::Operation::Polygon: {
          const canvas::TileCoverageMap tiles =
              canvas::rasterizePolygon(select.polygon, scene.width, scene.height);
          for (int32_t y = 0; y < scene.height; ++y) {
            for (int32_t x = 0; x < scene.width; ++x) {
              const auto found =
                  tiles.find({x / canvas::kTileSizePixels, y / canvas::kTileSizePixels});
              const double coverage =
                  found == tiles.end()
                      ? 0.0
                      : found->second[static_cast<size_t>(y % canvas::kTileSizePixels) *
                                          canvas::kTileSizePixels +
                                      static_cast<size_t>(x % canvas::kTileSizePixels)] /
                            255.0;
              double* pixel = image.pixel(x, y);
              const double value = canvas::combineSelection(select.combine, pixel[0], coverage);
              pixel[0] = pixel[1] = pixel[2] = value;
              pixel[3] = 1;
            }
          }
          quantize(image, format);
          break;
        }
        case scene::SelectAction::Operation::Auto: {
          const std::vector<uint8_t> coverage =
              floodCoverage(surface(static_cast<canvas::LayerId>(select.layer), false), select.x,
                            select.y, select.threshold, select.expand, false, linear);
          for (size_t index = 0; index < coverage.size(); ++index) {
            double* pixel = &image.rgba[index * 4];
            const double value =
                canvas::combineSelection(select.combine, pixel[0], coverage[index] / 255.0);
            pixel[0] = pixel[1] = pixel[2] = value;
            pixel[3] = 1;
          }
          quantize(image, format);
          break;
        }
        case scene::SelectAction::Operation::Feather:
          featherSelection(image, canvas::featherWeights(select.radius), format);
          break;
        case scene::SelectAction::Operation::Contents: {
          const Image& layer = surface(static_cast<canvas::LayerId>(select.layer), false);
          for (size_t offset = 0; offset < image.rgba.size(); offset += 4) {
            const double alpha = layer.rgba[offset + 3];
            image.rgba[offset] = image.rgba[offset + 1] = image.rgba[offset + 2] = alpha;
            image.rgba[offset + 3] = alpha > 0 ? 1 : 0;
          }
          break;
        }
      }
      continue;
    }
    if (action.type == scene::SceneAction::Type::Transform) {
      const scene::TransformAction& transform = action.transform;
      Image& layer = surface(static_cast<canvas::LayerId>(transform.layer), false);
      Image lifted = layer;
      Image result(scene.width, scene.height);
      if (transform.selection) {
        const Image& limit = selection();
        for (size_t index = 0; index < layer.rgba.size(); ++index) {
          const double selected = limit.rgba[(index / 4) * 4];
          lifted.rgba[index] = layer.rgba[index] * selected;
          result.rgba[index] = layer.rgba[index] * (1 - selected);
        }
        quantize(lifted, format);
        quantize(result, format);
      }
      canvas::rasterizeMesh(transform.mesh, lifted.rgba, result.rgba, scene.width, scene.height,
                            transform.filter);
      layer = result;
      quantize(layer, format);
      continue;
    }
    if (action.type == scene::SceneAction::Type::Adjust) {
      const scene::AdjustAction& adjustment = action.adjust;
      Image& stored = surface(static_cast<canvas::LayerId>(adjustment.layer), false);
      Image layer = linear ? encodedToSrgb(stored) : stored;
      const Image* limit = adjustment.selection ? &selection() : nullptr;
      Image* mask = adjustment.pencil ? &surface(scene::kAdjustmentMaskSurface, false) : nullptr;
      const bool spatial = adjust::isSpatial(adjustment.parameters.filter);
      const std::vector<double> filtered =
          spatial
              ? adjust::filterImage(adjustment.parameters, layer.rgba, layer.width, layer.height)
              : std::vector<double>{};
      for (size_t offset = 0; offset < layer.rgba.size(); offset += 4) {
        double weight = adjustment.amount;
        if (limit != nullptr) {
          weight *= limit->rgba[offset];
        }
        if (mask != nullptr) {
          weight *= mask->rgba[offset];
        }
        if (spatial) {
          for (size_t channel = 0; channel < 4; ++channel) {
            layer.rgba[offset + channel] +=
                (filtered[offset + channel] - layer.rgba[offset + channel]) * weight;
          }
          continue;
        }
        const auto pixel = static_cast<int32_t>(offset / 4);
        const adjust::PixelPosition position{static_cast<double>(pixel % layer.width),
                                             static_cast<double>(pixel / layer.width), layer.width,
                                             layer.height};
        const std::array<double, 4> result =
            adjust::applyToPixel(adjustment.parameters,
                                 {layer.rgba[offset], layer.rgba[offset + 1],
                                  layer.rgba[offset + 2], layer.rgba[offset + 3]},
                                 weight, position);
        std::copy(result.begin(), result.end(),
                  layer.rgba.begin() + static_cast<ptrdiff_t>(offset));
      }
      stored = linear ? decodedFromSrgb(layer) : layer;
      quantize(stored, format);
      if (mask != nullptr) {
        std::fill(mask->rgba.begin(), mask->rgba.end(), 0.0);
      }
      continue;
    }
    if (action.type == scene::SceneAction::Type::Liquify) {
      const scene::LiquifyAction& liquify = action.liquify;
      Image& stored = surface(static_cast<canvas::LayerId>(liquify.layer), false);
      Image layer = linear ? encodedToSrgb(stored) : stored;
      const Image* limit = liquify.selection ? &selection() : nullptr;
      const adjust::LiquifyField field = scene::liquifyField(liquify, layer.width, layer.height);
      const std::vector<double> moved =
          adjust::liquifyImage(field, layer.rgba, layer.width, layer.height);
      for (size_t offset = 0; offset < layer.rgba.size(); offset += 4) {
        const double weight = limit != nullptr ? limit->rgba[offset] : 1.0;
        for (size_t channel = 0; channel < 4; ++channel) {
          layer.rgba[offset + channel] +=
              (moved[offset + channel] - layer.rgba[offset + channel]) * weight;
        }
      }
      stored = linear ? decodedFromSrgb(layer) : layer;
      quantize(stored, format);
      continue;
    }
    if (action.type == scene::SceneAction::Type::Text) {
      const scene::TextAction& textAction = action.text;
      Image& layer = surface(static_cast<canvas::LayerId>(textAction.layer), false);
      std::fill(layer.rgba.begin(), layer.rgba.end(), 0.0);
      std::string error;
      const std::optional<text::TextModel> model = textAction.model(linear, error);
      if (!model) {
        continue;
      }
      const text::TextLayout layout = text::TextLayout::build(model->layout);
      const auto bounds = text::textBounds(layout, model->paints, model->transform);
      if (bounds) {
        const int32_t left = std::max(bounds->x, 0);
        const int32_t top = std::max(bounds->y, 0);
        const int32_t right = std::min(bounds->x + bounds->width, scene.width);
        const int32_t bottom = std::min(bounds->y + bounds->height, scene.height);
        if (right > left && bottom > top) {
          const text::PixelRect target{left, top, right - left, bottom - top};
          const std::vector<float> pixels =
              text::rasterizeText(layout, model->paints, model->transform, target);
          for (int32_t y = 0; y < target.height; ++y) {
            for (int32_t x = 0; x < target.width; ++x) {
              const float* source = pixels.data() + (static_cast<size_t>(y) * target.width + x) * 4;
              double* pixel = layer.pixel(left + x, top + y);
              for (size_t channel = 0; channel < 4; ++channel) {
                pixel[channel] = source[channel];
              }
            }
          }
        }
      }
      quantize(layer, format);
      continue;
    }
    if (action.type == scene::SceneAction::Type::CopyLayer) {
      const scene::CopyLayerAction& copy = action.copyLayer;
      const Image source = surface(static_cast<canvas::LayerId>(copy.from), false);
      Image& target = surface(static_cast<canvas::LayerId>(copy.to), false);
      target = source;
      if (copy.masked) {
        const Image& limit = selection();
        for (size_t index = 0; index < target.rgba.size(); ++index) {
          target.rgba[index] *= limit.rgba[(index / 4) * 4];
        }
        quantize(target, format);
      }
      continue;
    }
    if (action.type == scene::SceneAction::Type::FloodFill) {
      const scene::FloodFillAction& fill = action.floodFill;
      const canvas::LayerTreeNode* layer =
          scene.layers.find(static_cast<canvas::LayerId>(fill.layer));
      Image& target = fill.mask ? surface(layer->mask->id, true) : surface(layer->id, false);
      const std::vector<uint8_t> coverage =
          floodCoverage(surface(static_cast<canvas::LayerId>(fill.source), false), fill.x, fill.y,
                        fill.threshold, fill.expand, fill.whole, linear);
      const Image* limit = fill.selection ? &selection() : nullptr;
      const double luminance = scene::maskLuminance(fill.color);
      for (size_t index = 0; index < coverage.size(); ++index) {
        double amount = coverage[index] / 255.0;
        if (limit != nullptr) {
          amount *= limit->rgba[index * 4];
        }
        double* pixel = &target.rgba[index * 4];
        if (fill.mask) {
          const double value = pixel[0] * (1 - amount) + luminance * amount;
          pixel[0] = pixel[1] = pixel[2] = value;
          pixel[3] = 1;
          continue;
        }
        const double sourceAlpha = fill.color[3] * amount;
        for (size_t channel = 0; channel < 3; ++channel) {
          const double color = linear ? canvas::srgbToLinearValue(fill.color[channel])
                                      : fill.color[channel];
          pixel[channel] = color * sourceAlpha + pixel[channel] * (1 - sourceAlpha);
        }
        pixel[3] = sourceAlpha + pixel[3] * (1 - sourceAlpha);
      }
      quantize(target, format);
      continue;
    }
    if (action.type == scene::SceneAction::Type::Fill) {
      const scene::FillAction& fill = action.fill;
      const bool gray = fill.mask || fill.selection;
      Image& image =
          fill.selection ? selection()
          : fill.mask
              ? surface(scene.layers.find(static_cast<canvas::LayerId>(fill.layer))->mask->id, true)
              : surface(static_cast<canvas::LayerId>(fill.layer), false);
      const int32_t firstX = std::max(fill.rect[0], 0);
      const int32_t firstY = std::max(fill.rect[1], 0);
      const int32_t endX = std::min(fill.rect[0] + fill.rect[2], scene.width);
      const int32_t endY = std::min(fill.rect[1] + fill.rect[3], scene.height);
      for (int32_t y = firstY; y < endY; ++y) {
        for (int32_t x = firstX; x < endX; ++x) {
          const std::array<double, 4> color = fill.colorAt(x, y);
          double* pixel = image.pixel(x, y);
          if (gray) {
            const double luminance = scene::maskLuminance(color);
            pixel[0] = pixel[1] = pixel[2] = luminance;
            pixel[3] = 1;
          } else {
            for (size_t channel = 0; channel < 3; ++channel) {
              pixel[channel] =
                  (linear ? canvas::srgbToLinearValue(color[channel]) : color[channel]) * color[3];
            }
            pixel[3] = color[3];
          }
        }
      }
      quantize(image, format);
      continue;
    }
    const scene::StrokeAction stroke =
        action.stroke.replacement.empty() ? action.stroke : action.stroke.replaced();
    const std::vector<stroke::Dab> dabs = generateDabs(stroke);
    if (dabs.empty()) {
      continue;
    }
    const stroke::PaintMixing mixing =
        stroke.mask || stroke.selectionTarget || stroke.clone ? stroke::PaintMixing::None
        : stroke.tool == scene::Tool::Smudge                  ? stroke::PaintMixing::Smudge
        : stroke.tool == scene::Tool::Paint && stroke.brush.wetMix.enabled
            ? stroke::PaintMixing::WetMix
            : stroke::PaintMixing::None;
    if (mixing != stroke::PaintMixing::None) {
      const canvas::LayerTreeNode* layer =
          scene.layers.find(static_cast<canvas::LayerId>(stroke.layer));
      Image& image = surface(layer->id, false);
      Image working = image;
      stroke::MixingState state;
      renderMixingDabs(working, inDocument(dabs), stroke.tipStyle(), mixing, stroke.brush,
                       stroke.settings.opacity, state, format);
      commitWorking(image, working, stroke.selection ? &selection() : nullptr, layer->alphaLock);
      quantize(image, format);
      continue;
    }
    if (stroke.tool == scene::Tool::Smudge) {
      continue;
    }
    Image strokeBuffer(scene.width, scene.height);
    const bool paintsLayerColors =
        stroke.tool == scene::Tool::Paint && !stroke.mask && !stroke.selectionTarget;
    const stroke::RenderParameters render =
        stroke::effectiveRender(stroke.brush.render, paintsLayerColors, stroke.clone.has_value(),
                                stroke.selectionTarget, linear);
    if (stroke.clone) {
      Image source;
      if (stroke.clone->composite) {
        source = composeLayerTree(scene.layers, scene.width, scene.height,
                                  [&surfaces](canvas::LayerId id) -> const Image* {
                                    auto found = surfaces.find(id);
                                    return found == surfaces.end() ? nullptr : &found->second;
                                  });
        quantize(source, format);
      } else {
        source = surface(static_cast<canvas::LayerId>(stroke.clone->layer), false);
      }
      renderCloneDabs(strokeBuffer, dabs, stroke.tipStyle(),
                      stroke::primitiveFor(stroke.brush.render.accumulation).primitive, source,
                      stroke.clone->offset);
    } else {
      const bool mixesLayer =
          stroke.brush.render.accumulation == stroke::Accumulation::BlendIntense &&
          paintsLayerColors;
      renderDabs(strokeBuffer, dabs, stroke.tipStyle(), stroke.brush.render.accumulation,
                 mixesLayer ? &surface(static_cast<canvas::LayerId>(stroke.layer), false)
                            : nullptr,
                 render.gammaCorrect, linear);
    }
    Image dualBuffer;
    DualCoverage dual;
    if (stroke.dual && stroke.brush.dual.enabled && !stroke.clone) {
      scene::StrokeAction secondary = stroke;
      secondary.brush = stroke.dual->parameters;
      secondary.shapeTexture = stroke.dual->shapeTexture;
      secondary.grainTexture = stroke.dual->grainTexture;
      secondary.settings = stroke::dualStrokeSettings(stroke.settings);
      dualBuffer = Image(scene.width, scene.height);
      renderDabs(dualBuffer, generateDabs(secondary), secondary.tipStyle(),
                 secondary.brush.render.accumulation, nullptr, false, linear);
      dual = DualCoverage{&dualBuffer, stroke.brush.dual.blend,
                          {canvas::documentChannel(stroke.settings.color[0], linear),
                           canvas::documentChannel(stroke.settings.color[1], linear),
                           canvas::documentChannel(stroke.settings.color[2], linear)}};
    }
    Image particleBuffer;
    const bool particles =
        stroke.brush.particles.active() && !stroke.clone && !stroke.selectionTarget;
    if (particles) {
      stroke::ParticleSystem system(stroke.brush.particles, stroke.settings.seed);
      system.emit(dabs);
      double lastSampleMilliseconds = 0;
      for (const stroke::InputSample& sample : stroke.samples) {
        lastSampleMilliseconds =
            std::max(lastSampleMilliseconds, static_cast<double>(sample.eventTimeNanos) / 1e6);
      }
      std::vector<stroke::Dab> particleDabs;
      system.advance(lastSampleMilliseconds + stroke::ParticleSystem::kMaximumTailMilliseconds,
                     particleDabs);
      stroke::TipStyle style = stroke.tipStyle();
      if (!stroke.brush.particles.useShape) {
        style.shape = nullptr;
      }
      particleBuffer = Image(scene.width, scene.height);
      renderDabs(particleBuffer, particleDabs, style, stroke::Accumulation::BuildUp, nullptr, false,
                 linear);
    }
    applyStrokeEffects(strokeBuffer, render, stroke.settings.sizePixels,
                       dual.coverage != nullptr ? &dual : nullptr,
                       particles ? &particleBuffer : nullptr);
    if (stroke.selectionTarget) {
      Image& target =
          stroke.adjustmentMaskTarget ? surface(scene::kAdjustmentMaskSurface, false) : selection();
      commitMask(target, strokeBuffer, stroke.settings.opacity,
                 stroke.tool == scene::Tool::Erase ? 0.0 : 1.0);
      quantize(target, format);
      continue;
    }
    if (stroke.selection) {
      applySelection(strokeBuffer, selection());
    }
    const canvas::LayerTreeNode* layer =
        scene.layers.find(static_cast<canvas::LayerId>(stroke.layer));
    if (stroke.mask) {
      Image& mask = surface(layer->mask->id, true);
      const double luminance =
          stroke.tool == scene::Tool::Erase ? 0.0 : scene::maskLuminance(stroke.settings.color);
      commitMask(mask, strokeBuffer, stroke.settings.opacity, luminance);
      quantize(mask, format);
      continue;
    }
    Image& image = surface(layer->id, false);
    if (stroke.tool == scene::Tool::Paint) {
      commitPaint(image, strokeBuffer, stroke.settings.opacity, stroke.brush.render.blend,
                  layer->alphaLock, render.luminanceBlending);
    } else {
      commitErase(image, strokeBuffer, stroke.settings.opacity, layer->alphaLock);
    }
    quantize(image, format);
  }
  return surfaces;
}

Image renderScene(const scene::Scene& scene) {
  const std::map<canvas::LayerId, Image> surfaces = renderSceneSurfaces(scene);
  Image result = composeLayerTree(scene.layers, scene.width, scene.height,
                                  [&surfaces](canvas::LayerId id) -> const Image* {
                                    auto found = surfaces.find(id);
                                    return found == surfaces.end() ? nullptr : &found->second;
                                  });
  quantize(result, scene.layers.pixelFormat);
  if (canvas::isLinear(scene.layers.pixelFormat)) {
    result = encodedToSrgb(result);
    quantizeToRgba8(result);
  }
  return result;
}

}  // namespace nanquim::reference
