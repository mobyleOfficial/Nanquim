#include "reference_renderer.h"

#include <algorithm>
#include <cmath>

#include "blend_reference.h"
#include "core/half_float.h"
#include "stroke/stroke_effects.h"

namespace nanquim::reference {

namespace {

using stroke::Accumulation;

void glaze(double* destination, const stroke::Dab& dab, const std::array<double, 3>& color,
           double tip, Accumulation accumulation) {
  const double cap = std::clamp(dab.opacity, 0.0, 1.0);
  const bool usesFlow =
      accumulation == Accumulation::GlazeLight || accumulation == Accumulation::GlazeUniform;
  const double rate = std::clamp(tip * (usesFlow ? dab.flow : 1.0), 0.0, 1.0);
  if (rate <= 0 || cap <= destination[3]) {
    return;
  }
  if (accumulation == Accumulation::GlazeHeavy) {
    const double alpha = destination[3] + (cap - destination[3]) * rate;
    for (size_t channel = 0; channel < 3; ++channel) {
      destination[channel] = color[channel] * alpha;
    }
    destination[3] = alpha;
    return;
  }
  for (size_t channel = 0; channel < 3; ++channel) {
    destination[channel] += (color[channel] * cap - destination[channel]) * rate;
  }
  destination[3] += (cap - destination[3]) * rate;
}

}  // namespace

void renderDabs(Image& strokeBuffer, const std::vector<stroke::Dab>& dabs,
                const stroke::TipStyle& style, Accumulation accumulation, const Image* layer,
                bool linearLight, bool linearDocument) {
  const bool linearize = linearLight || linearDocument;
  for (stroke::Dab dab : dabs) {
    if (linearize) {
      for (size_t channel = 0; channel < 3; ++channel) {
        dab.color[channel] = stroke::srgbToLinear(dab.color[channel]);
      }
    }
    const double extent = stroke::dabBoundingRadius(dab, style.textured());
    const int32_t firstX = std::max(0, static_cast<int32_t>(std::floor(dab.x - extent)));
    const int32_t firstY = std::max(0, static_cast<int32_t>(std::floor(dab.y - extent)));
    const int32_t endX =
        std::min(strokeBuffer.width, static_cast<int32_t>(std::ceil(dab.x + extent)) + 1);
    const int32_t endY =
        std::min(strokeBuffer.height, static_cast<int32_t>(std::ceil(dab.y + extent)) + 1);
    const double strength = std::clamp(dab.opacity * dab.flow, 0.0, 1.0);
    for (int32_t y = firstY; y < endY; ++y) {
      for (int32_t x = firstX; x < endX; ++x) {
        const double tip = stroke::dabCoverage(dab, style, x + 0.5, y + 0.5);
        double* destination = strokeBuffer.pixel(x, y);
        std::array<double, 3> color{dab.color[0], dab.color[1], dab.color[2]};
        if (tip > 0 && stroke::dabTipColor(dab, style, x + 0.5, y + 0.5, color) && linearize) {
          for (double& value : color) {
            value = stroke::srgbToLinear(value);
          }
        }
        if (stroke::isGlaze(accumulation)) {
          glaze(destination, dab, color, tip, accumulation);
          continue;
        }
        const double coverage = tip * strength;
        if (coverage <= 0) {
          continue;
        }
        if (accumulation == Accumulation::BlendIntense && layer != nullptr) {
          const double* under = layer->pixel(x, y);
          if (under[3] > 0) {
            const double weight = stroke::kLayerMixAmount * under[3];
            for (size_t channel = 0; channel < 3; ++channel) {
              const double value = under[channel] / under[3];
              color[channel] +=
                  ((linearLight ? stroke::srgbToLinear(value) : value) - color[channel]) * weight;
            }
          }
        }
        const std::array<double, 4> source{color[0] * coverage, color[1] * coverage,
                                           color[2] * coverage, coverage};
        for (size_t channel = 0; channel < 4; ++channel) {
          if (accumulation == Accumulation::Wash) {
            destination[channel] = std::max(destination[channel], source[channel]);
          } else {
            destination[channel] = source[channel] + destination[channel] * (1 - coverage);
          }
        }
      }
    }
  }
}

void renderCloneDabs(Image& strokeBuffer, const std::vector<stroke::Dab>& dabs,
                     const stroke::TipStyle& style, stroke::Accumulation accumulation,
                     const Image& source, std::array<int32_t, 2> offset) {
  for (const stroke::Dab& dab : dabs) {
    const double extent = stroke::dabBoundingRadius(dab, style.textured());
    const int32_t firstX = std::max(0, static_cast<int32_t>(std::floor(dab.x - extent)));
    const int32_t firstY = std::max(0, static_cast<int32_t>(std::floor(dab.y - extent)));
    const int32_t endX =
        std::min(strokeBuffer.width, static_cast<int32_t>(std::ceil(dab.x + extent)) + 1);
    const int32_t endY =
        std::min(strokeBuffer.height, static_cast<int32_t>(std::ceil(dab.y + extent)) + 1);
    const double strength = std::clamp(dab.opacity * dab.flow, 0.0, 1.0);
    for (int32_t y = firstY; y < endY; ++y) {
      for (int32_t x = firstX; x < endX; ++x) {
        const double coverage = stroke::dabCoverage(dab, style, x + 0.5, y + 0.5) * strength;
        if (coverage <= 0) {
          continue;
        }
        const int32_t sourceX = x + offset[0];
        const int32_t sourceY = y + offset[1];
        std::array<double, 4> sampled{0, 0, 0, 0};
        if (sourceX >= 0 && sourceY >= 0 && sourceX < source.width && sourceY < source.height) {
          const double* pixel = source.pixel(sourceX, sourceY);
          sampled = {pixel[0], pixel[1], pixel[2], pixel[3]};
        }
        double* destination = strokeBuffer.pixel(x, y);
        for (size_t channel = 0; channel < 4; ++channel) {
          const double value = sampled[channel] * coverage;
          if (accumulation == stroke::Accumulation::BuildUp) {
            destination[channel] = value + destination[channel] * (1 - sampled[3] * coverage);
          } else {
            destination[channel] = std::max(destination[channel], value);
          }
        }
      }
    }
  }
}

void applyStrokeEffects(Image& strokeBuffer, const stroke::RenderParameters& render,
                        double sizePixels, const DualCoverage* dual, const Image* particles) {
  const int32_t width = strokeBuffer.width;
  const int32_t height = strokeBuffer.height;
  if (render.gammaCorrect) {
    for (size_t offset = 0; offset < strokeBuffer.rgba.size(); offset += 4) {
      double* pixel = &strokeBuffer.rgba[offset];
      if (pixel[3] <= 0) {
        continue;
      }
      for (size_t channel = 0; channel < 3; ++channel) {
        pixel[channel] = stroke::linearToSrgb(pixel[channel] / pixel[3]) * pixel[3];
      }
    }
  }
  auto combineLayers = [&] {
    for (size_t offset = 0; offset < strokeBuffer.rgba.size(); offset += 4) {
      double* pixel = &strokeBuffer.rgba[offset];
      if (dual != nullptr && dual->coverage != nullptr) {
        const double combined =
            stroke::dualCoverage(dual->blend, pixel[3], dual->coverage->rgba[offset + 3]);
        if (pixel[3] > 0) {
          const double scale = combined / pixel[3];
          for (size_t channel = 0; channel < 4; ++channel) {
            pixel[channel] *= scale;
          }
        } else {
          for (size_t channel = 0; channel < 3; ++channel) {
            pixel[channel] = dual->color[channel] * combined;
          }
          pixel[3] = combined;
        }
      }
      if (particles != nullptr) {
        const double* particle = &particles->rgba[offset];
        for (size_t channel = 0; channel < 4; ++channel) {
          pixel[channel] = particle[channel] + pixel[channel] * (1 - particle[3]);
        }
      }
    }
  };
  if (!render.hasEdges()) {
    combineLayers();
    return;
  }
  constexpr int32_t kScale = stroke::kEdgeMapScale;
  const int32_t mapWidth = (width + kScale - 1) / kScale;
  const int32_t mapHeight = (height + kScale - 1) / kScale;
  auto index = [mapWidth](int32_t x, int32_t y) {
    return static_cast<size_t>(y) * static_cast<size_t>(mapWidth) + static_cast<size_t>(x);
  };
  std::vector<double> map(static_cast<size_t>(mapWidth) * static_cast<size_t>(mapHeight), 0.0);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      map[index(x / kScale, y / kScale)] += strokeBuffer.pixel(x, y)[3] / (kScale * kScale);
    }
  }
  const std::vector<float> weights = stroke::edgeBlurWeights(sizePixels);
  const auto radius = static_cast<int32_t>(weights.size()) - 1;
  std::vector<double> rows(map.size(), 0.0);
  for (int32_t y = 0; y < mapHeight; ++y) {
    for (int32_t x = 0; x < mapWidth; ++x) {
      double sum = 0;
      for (int32_t tap = -radius; tap <= radius; ++tap) {
        const int32_t sampleX = x + tap;
        if (sampleX >= 0 && sampleX < mapWidth) {
          sum += weights[static_cast<size_t>(std::abs(tap))] * map[index(sampleX, y)];
        }
      }
      rows[index(x, y)] = sum;
    }
  }
  std::vector<double> blurred(map.size(), 0.0);
  for (int32_t y = 0; y < mapHeight; ++y) {
    for (int32_t x = 0; x < mapWidth; ++x) {
      double sum = 0;
      for (int32_t tap = -radius; tap <= radius; ++tap) {
        const int32_t sampleY = y + tap;
        if (sampleY >= 0 && sampleY < mapHeight) {
          sum += weights[static_cast<size_t>(std::abs(tap))] * rows[index(x, sampleY)];
        }
      }
      blurred[index(x, y)] = sum;
    }
  }
  combineLayers();
  auto sample = [&](int32_t x, int32_t y) {
    const double mapX = (x + 0.5) / kScale - 0.5;
    const double mapY = (y + 0.5) / kScale - 0.5;
    const double floorX = std::floor(mapX);
    const double floorY = std::floor(mapY);
    const double fractionX = mapX - floorX;
    const double fractionY = mapY - floorY;
    const int32_t x0 = std::clamp(static_cast<int32_t>(floorX), 0, mapWidth - 1);
    const int32_t x1 = std::clamp(static_cast<int32_t>(floorX) + 1, 0, mapWidth - 1);
    const int32_t y0 = std::clamp(static_cast<int32_t>(floorY), 0, mapHeight - 1);
    const int32_t y1 = std::clamp(static_cast<int32_t>(floorY) + 1, 0, mapHeight - 1);
    const double top =
        blurred[index(x0, y0)] * (1 - fractionX) + blurred[index(x1, y0)] * fractionX;
    const double bottom =
        blurred[index(x0, y1)] * (1 - fractionX) + blurred[index(x1, y1)] * fractionX;
    return top * (1 - fractionY) + bottom * fractionY;
  };
  const double wet = std::clamp(render.wetEdges, 0.0, 1.0);
  const double burnt = std::clamp(render.burntEdges, 0.0, 1.0);
  for (int32_t y = 0; y < height; ++y) {
    for (int32_t x = 0; x < width; ++x) {
      double* pixel = strokeBuffer.pixel(x, y);
      const double coverage = pixel[3];
      if (coverage <= 0) {
        continue;
      }
      const double edge = stroke::edgeAmount(coverage, sample(x, y));
      Rgb color{pixel[0] / coverage, pixel[1] / coverage, pixel[2] / coverage};
      if (burnt > 0) {
        const Rgb blended = blendColor(render.burntEdgesBlend, color,
                                       {color[0] * 0.5, color[1] * 0.5, color[2] * 0.5});
        for (size_t channel = 0; channel < 3; ++channel) {
          color[channel] += (blended[channel] - color[channel]) * burnt * edge;
        }
      }
      const double alpha = coverage * (1 + ((0.6 + 0.4 * edge) - 1) * wet);
      for (size_t channel = 0; channel < 3; ++channel) {
        pixel[channel] = color[channel] * alpha;
      }
      pixel[3] = alpha;
    }
  }
}

void renderMixingDabs(Image& working, const std::vector<stroke::Dab>& dabs,
                      const stroke::TipStyle& style, stroke::PaintMixing mode,
                      const stroke::BrushParameters& brush, double strokeOpacity,
                      stroke::MixingState& state, canvas::PixelFormat format) {
  const bool half = canvas::isLinear(format);
  for (const stroke::Dab& dab : dabs) {
    const double extent = stroke::dabBoundingRadius(dab, style.textured());
    std::array<double, 4> sum{0, 0, 0, 0};
    double weightSum = 0;
    for (int32_t row = 0; row < stroke::kMixSampleGrid; ++row) {
      for (int32_t column = 0; column < stroke::kMixSampleGrid; ++column) {
        const auto [x, y] = stroke::mixSamplePoint(dab, extent, column, row);
        const auto pixelX = static_cast<int32_t>(std::floor(x));
        const auto pixelY = static_cast<int32_t>(std::floor(y));
        if (pixelX < 0 || pixelY < 0 || pixelX >= working.width || pixelY >= working.height) {
          continue;
        }
        const double weight = stroke::dabCoverage(dab, style, x, y);
        if (weight <= 0) {
          continue;
        }
        const double* pixel = working.pixel(pixelX, pixelY);
        for (size_t channel = 0; channel < 4; ++channel) {
          sum[channel] += pixel[channel] * weight;
        }
        weightSum += weight;
      }
    }
    std::array<double, 4> sample{0, 0, 0, 0};
    if (weightSum > 0) {
      for (size_t channel = 0; channel < 4; ++channel) {
        sample[channel] = sum[channel] / weightSum;
      }
    }
    const stroke::DabMixing mixing = stroke::dabMixing(
        mode, brush, strokeOpacity, dab, state.hasPrevious ? state.previousDistance : dab.distance);
    state.hasPrevious = true;
    state.previousDistance = dab.distance;
    const stroke::MixedPaint paint = stroke::mixPaint(mixing, sample, weightSum > 0, state);
    if (!paint.apply) {
      continue;
    }
    const int32_t firstX = std::max(0, static_cast<int32_t>(std::floor(dab.x - extent)));
    const int32_t firstY = std::max(0, static_cast<int32_t>(std::floor(dab.y - extent)));
    const int32_t endX =
        std::min(working.width, static_cast<int32_t>(std::ceil(dab.x + extent)) + 1);
    const int32_t endY =
        std::min(working.height, static_cast<int32_t>(std::ceil(dab.y + extent)) + 1);
    for (int32_t y = firstY; y < endY; ++y) {
      for (int32_t x = firstX; x < endX; ++x) {
        const double tip = stroke::dabCoverage(dab, style, x + 0.5, y + 0.5);
        if (tip <= 0) {
          continue;
        }
        double* pixel = working.pixel(x, y);
        stroke::applyMixedPaint(mixing, paint, tip, pixel);
        for (size_t channel = 0; channel < 4; ++channel) {
          pixel[channel] =
              half ? core::halfToFloat(core::floatToHalf(static_cast<float>(pixel[channel])))
                   : std::round(std::clamp(pixel[channel], 0.0, 1.0) * 255.0) / 255.0;
        }
      }
    }
  }
}

void commitWorking(Image& layer, const Image& working, const Image* selection, bool alphaLock) {
  for (size_t offset = 0; offset < layer.rgba.size(); offset += 4) {
    const double amount = selection != nullptr ? selection->rgba[offset] : 1.0;
    double* pixel = &layer.rgba[offset];
    const double alpha = pixel[3];
    for (size_t channel = 0; channel < 4; ++channel) {
      pixel[channel] += (working.rgba[offset + channel] - pixel[channel]) * amount;
    }
    if (alphaLock) {
      const double scale = pixel[3] > 0 ? alpha / pixel[3] : 0;
      for (size_t channel = 0; channel < 3; ++channel) {
        pixel[channel] *= scale;
      }
      pixel[3] = alpha;
    }
  }
}

void commitPaint(Image& layer, const Image& strokeBuffer, double strokeOpacity,
                 canvas::BlendMode blend, bool alphaLock, bool luminanceBlending) {
  for (size_t offset = 0; offset < layer.rgba.size(); offset += 4) {
    const double* stroke = &strokeBuffer.rgba[offset];
    if (stroke[3] <= 0) {
      continue;
    }
    double* pixel = &layer.rgba[offset];
    const Rgba source{stroke[0] * strokeOpacity, stroke[1] * strokeOpacity,
                      stroke[2] * strokeOpacity, stroke[3] * strokeOpacity};
    const Rgba backdrop{pixel[0], pixel[1], pixel[2], pixel[3]};
    Rgba result = compositePixel(blend, backdrop, source);
    if (luminanceBlending && backdrop.alpha > 0 && result.alpha > 0) {
      const Rgb layerColor{backdrop.red / backdrop.alpha, backdrop.green / backdrop.alpha,
                           backdrop.blue / backdrop.alpha};
      const Rgb resultColor{result.red / result.alpha, result.green / result.alpha,
                            result.blue / result.alpha};
      const Rgb kept = blendColor(canvas::BlendMode::Luminosity, layerColor, resultColor);
      result = {kept[0] * result.alpha, kept[1] * result.alpha, kept[2] * result.alpha,
                result.alpha};
    }
    if (alphaLock) {
      const double scale = result.alpha > 0 ? backdrop.alpha / result.alpha : 0;
      result = {result.red * scale, result.green * scale, result.blue * scale, backdrop.alpha};
    }
    pixel[0] = result.red;
    pixel[1] = result.green;
    pixel[2] = result.blue;
    pixel[3] = result.alpha;
  }
}

void commitMask(Image& mask, const Image& strokeBuffer, double strokeOpacity, double luminance) {
  for (size_t offset = 0; offset < mask.rgba.size(); offset += 4) {
    const double coverage = strokeBuffer.rgba[offset + 3] * strokeOpacity;
    for (size_t channel = 0; channel < 3; ++channel) {
      mask.rgba[offset + channel] = mask.rgba[offset + channel] * (1 - coverage) + luminance * coverage;
    }
    mask.rgba[offset + 3] = 1;
  }
}

void applySelection(Image& strokeBuffer, const Image& selection) {
  for (size_t offset = 0; offset < strokeBuffer.rgba.size(); offset += 4) {
    const double amount = selection.rgba[offset];
    for (size_t channel = 0; channel < 4; ++channel) {
      strokeBuffer.rgba[offset + channel] *= amount;
    }
  }
}

void featherSelection(Image& selection, const std::vector<float>& weights,
                      canvas::PixelFormat format) {
  const auto halfWidth = static_cast<int32_t>(weights.size()) - 1;
  for (const bool horizontal : {true, false}) {
    const Image source = selection;
    for (int32_t y = 0; y < selection.height; ++y) {
      for (int32_t x = 0; x < selection.width; ++x) {
        double sum = 0;
        for (int32_t offset = -halfWidth; offset <= halfWidth; ++offset) {
          const int32_t sampleX = horizontal ? x + offset : x;
          const int32_t sampleY = horizontal ? y : y + offset;
          if (sampleX < 0 || sampleY < 0 || sampleX >= selection.width ||
              sampleY >= selection.height) {
            continue;
          }
          sum += weights[static_cast<size_t>(std::abs(offset))] *
                 source.rgba[(static_cast<size_t>(sampleY) * selection.width + sampleX) * 4];
        }
        double* pixel = selection.pixel(x, y);
        pixel[0] = pixel[1] = pixel[2] = sum;
        pixel[3] = 1;
      }
    }
    quantize(selection, format);
  }
}

Image whiteMask(int32_t width, int32_t height) {
  Image mask(width, height);
  std::fill(mask.rgba.begin(), mask.rgba.end(), 1.0);
  return mask;
}

void commitErase(Image& layer, const Image& strokeBuffer, double eraseOpacity, bool alphaLock) {
  if (alphaLock) {
    return;
  }
  for (size_t offset = 0; offset < layer.rgba.size(); offset += 4) {
    const double keep = 1 - strokeBuffer.rgba[offset + 3] * eraseOpacity;
    for (size_t channel = 0; channel < 4; ++channel) {
      layer.rgba[offset + channel] *= keep;
    }
  }
}

Image flatten(int32_t width, int32_t height, std::array<double, 4> background,
              const std::vector<CompositeLayer>& layers) {
  Image result(width, height);
  const double backgroundAlpha = background[3];
  for (size_t offset = 0; offset < result.rgba.size(); offset += 4) {
    for (size_t channel = 0; channel < 3; ++channel) {
      result.rgba[offset + channel] = background[channel] * backgroundAlpha;
    }
    result.rgba[offset + 3] = backgroundAlpha;
  }
  for (const CompositeLayer& layer : layers) {
    if (!layer.visible || layer.opacity <= 0 || layer.image == nullptr) {
      continue;
    }
    for (size_t offset = 0; offset < result.rgba.size(); offset += 4) {
      const double sourceAlpha = layer.image->rgba[offset + 3] * layer.opacity;
      for (size_t channel = 0; channel < 4; ++channel) {
        result.rgba[offset + channel] = layer.image->rgba[offset + channel] * layer.opacity +
                                        result.rgba[offset + channel] * (1 - sourceAlpha);
      }
    }
  }
  return result;
}

}  // namespace nanquim::reference
