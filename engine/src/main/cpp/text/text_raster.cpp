#include "text/text_raster.h"

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include FT_STROKER_H

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace nanquim::text {
namespace {

struct GlyphMapping {
  double scale = 1;
  bool rotated = false;
  double originX = 0;
  double originY = 0;
  Affine transform = kIdentityAffine;

  [[nodiscard]] std::array<double, 2> map(double fontX, double fontY) const {
    double x = fontX * scale;
    double y = -fontY * scale;
    if (rotated) {
      const double turned = -y;
      y = x;
      x = turned;
    }
    x += originX;
    y += originY;
    return {transform[0] * x + transform[2] * y + transform[4],
            transform[1] * x + transform[3] * y + transform[5]};
  }
};

std::array<double, 2> mapPoint(const Affine& transform, double x, double y) {
  return {transform[0] * x + transform[2] * y + transform[4],
          transform[1] * x + transform[3] * y + transform[5]};
}

double transformScale(const Affine& transform) {
  return std::sqrt(std::abs(transform[0] * transform[3] - transform[1] * transform[2]));
}

FT_Vector toFrame(const std::array<double, 2>& canvas) {
  return {static_cast<FT_Pos>(std::lround(canvas[0] * 64.0)),
          static_cast<FT_Pos>(std::lround(-canvas[1] * 64.0))};
}

struct Coverage {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<float> values;
};

void spansIntoCoverage(int y, int count, const FT_Span* spans, void* user) {
  auto& coverage = *static_cast<Coverage*>(user);
  const int32_t row = coverage.height - 1 - y;
  if (row < 0 || row >= coverage.height) {
    return;
  }
  float* line = coverage.values.data() + static_cast<size_t>(row) * coverage.width;
  for (int index = 0; index < count; ++index) {
    const float amount = static_cast<float>(spans[index].coverage) / 255.0F;
    const int32_t first = std::max<int32_t>(spans[index].x, 0);
    const int32_t end = std::min<int32_t>(spans[index].x + spans[index].len, coverage.width);
    for (int32_t x = first; x < end; ++x) {
      line[x] = line[x] + amount - line[x] * amount;
    }
  }
}

void renderOutline(FT_Library library, FT_Outline& outline, Coverage& coverage) {
  FT_Raster_Params parameters{};
  parameters.source = &outline;
  parameters.flags = FT_RASTER_FLAG_AA | FT_RASTER_FLAG_DIRECT | FT_RASTER_FLAG_CLIP;
  parameters.gray_spans = spansIntoCoverage;
  parameters.user = &coverage;
  parameters.clip_box = {0, 0, coverage.width, coverage.height};
  FT_Outline_Render(library, &outline, &parameters);
}

}  // namespace

struct TextRasterizer::Shape {
  FT_Outline outline{};
  size_t style = 0;
  bool stroke = false;
  int32_t left = 0;
  int32_t top = 0;
  int32_t right = 0;
  int32_t bottom = 0;
};

TextRasterizer::TextRasterizer(const TextLayout& layout, std::vector<TextPaint> paints,
                               const Affine& transform)
    : paints_(std::move(paints)) {
  if (layout.glyphs().empty() || paints_.empty()) {
    return;
  }
  library_ = layout.glyphs().front().face->freetypeFace()->glyph->library;
  const double scale = transformScale(transform);
  const auto keep = [this](const FT_Outline& source, size_t style, bool stroke) {
    Shape shape;
    if (FT_Outline_New(library_, static_cast<FT_UInt>(source.n_points), source.n_contours,
                       &shape.outline) != 0) {
      return;
    }
    FT_Outline_Copy(&source, &shape.outline);
    FT_BBox box{};
    FT_Outline_Get_CBox(&shape.outline, &box);
    shape.left = static_cast<int32_t>(std::floor(static_cast<double>(box.xMin) / 64.0)) - 1;
    shape.right = static_cast<int32_t>(std::ceil(static_cast<double>(box.xMax) / 64.0)) + 1;
    shape.top = static_cast<int32_t>(std::floor(-static_cast<double>(box.yMax) / 64.0)) - 1;
    shape.bottom = static_cast<int32_t>(std::ceil(-static_cast<double>(box.yMin) / 64.0)) + 1;
    shape.style = style;
    shape.stroke = stroke;
    shapes_.push_back(shape);
  };
  for (const PlacedGlyph& glyph : layout.glyphs()) {
    if (glyph.style >= paints_.size()) {
      continue;
    }
    FT_Face face = glyph.face->freetypeFace();
    if (FT_Load_Glyph(face, glyph.id, FT_LOAD_NO_SCALE | FT_LOAD_NO_BITMAP) != 0 ||
        face->glyph->format != FT_GLYPH_FORMAT_OUTLINE || face->glyph->outline.n_points == 0) {
      continue;
    }
    FT_Outline& outline = face->glyph->outline;
    GlyphMapping mapping;
    mapping.scale = glyph.sizePixels / static_cast<double>(glyph.face->unitsPerEm());
    mapping.rotated = glyph.rotated;
    mapping.originX = glyph.x;
    mapping.originY = glyph.y;
    mapping.transform = transform;
    for (int16_t index = 0; index < outline.n_points; ++index) {
      outline.points[index] = toFrame(mapping.map(static_cast<double>(outline.points[index].x),
                                                  static_cast<double>(outline.points[index].y)));
    }
    const TextPaint& paint = paints_[glyph.style];
    if (paint.outlineWidth > 0 && paint.outlineColor[3] > 0) {
      FT_Stroker stroker = nullptr;
      if (FT_Stroker_New(library_, &stroker) == 0) {
        FT_Stroker_Set(stroker,
                       static_cast<FT_Fixed>(std::lround(paint.outlineWidth * scale * 64.0)),
                       FT_STROKER_LINECAP_ROUND, FT_STROKER_LINEJOIN_ROUND, 0);
        if (FT_Stroker_ParseOutline(stroker, &outline, false) == 0) {
          FT_UInt points = 0;
          FT_UInt contours = 0;
          FT_Stroker_GetCounts(stroker, &points, &contours);
          FT_Outline stroked{};
          if (points > 0 &&
              FT_Outline_New(library_, points, static_cast<FT_Int>(contours), &stroked) == 0) {
            stroked.n_points = 0;
            stroked.n_contours = 0;
            FT_Stroker_Export(stroker, &stroked);
            keep(stroked, glyph.style, true);
            FT_Outline_Done(library_, &stroked);
          }
        }
        FT_Stroker_Done(stroker);
      }
    }
    keep(outline, glyph.style, false);
  }
  for (const Decoration& decoration : layout.decorations()) {
    if (decoration.style >= paints_.size()) {
      continue;
    }
    FT_Outline band{};
    if (FT_Outline_New(library_, 4, 1, &band) != 0) {
      continue;
    }
    const std::array<std::array<double, 2>, 4> corners{
        mapPoint(transform, decoration.x0, decoration.y0),
        mapPoint(transform, decoration.x1, decoration.y0),
        mapPoint(transform, decoration.x1, decoration.y1),
        mapPoint(transform, decoration.x0, decoration.y1)};
    for (int16_t index = 0; index < 4; ++index) {
      band.points[index] = toFrame(corners[static_cast<size_t>(index)]);
      band.tags[index] = FT_CURVE_TAG_ON;
    }
    band.contours[0] = 3;
    keep(band, decoration.style, false);
    FT_Outline_Done(library_, &band);
  }
  if (!shapes_.empty()) {
    PixelRect rect{std::numeric_limits<int32_t>::max(), std::numeric_limits<int32_t>::max(), 0, 0};
    int32_t right = std::numeric_limits<int32_t>::min();
    int32_t bottom = std::numeric_limits<int32_t>::min();
    for (const Shape& shape : shapes_) {
      rect.x = std::min(rect.x, shape.left);
      rect.y = std::min(rect.y, shape.top);
      right = std::max(right, shape.right);
      bottom = std::max(bottom, shape.bottom);
    }
    rect.width = right - rect.x;
    rect.height = bottom - rect.y;
    bounds_ = rect;
  }
}

TextRasterizer::~TextRasterizer() {
  for (Shape& shape : shapes_) {
    FT_Outline_Done(library_, &shape.outline);
  }
}

std::vector<TextRasterizer::ShapeSignature> TextRasterizer::signatures() const {
  std::vector<ShapeSignature> result;
  result.reserve(shapes_.size());
  for (const Shape& shape : shapes_) {
    uint64_t hash = 1469598103934665603ULL;
    const auto mix = [&hash](uint64_t value) {
      for (int byte = 0; byte < 8; ++byte) {
        hash = (hash ^ ((value >> (byte * 8)) & 0xFFU)) * 1099511628211ULL;
      }
    };
    mix(shape.style);
    mix(shape.stroke ? 1 : 0);
    for (int16_t index = 0; index < shape.outline.n_points; ++index) {
      mix(static_cast<uint64_t>(shape.outline.points[index].x));
      mix(static_cast<uint64_t>(shape.outline.points[index].y));
      mix(static_cast<uint64_t>(static_cast<unsigned char>(shape.outline.tags[index])));
    }
    for (int16_t index = 0; index < shape.outline.n_contours; ++index) {
      mix(static_cast<uint64_t>(shape.outline.contours[index]));
    }
    const TextPaint& paint = paints_[shape.style];
    for (const float value : shape.stroke ? paint.outlineColor : paint.color) {
      uint32_t bits = 0;
      std::memcpy(&bits, &value, sizeof(bits));
      mix(bits);
    }
    result.push_back(
        {hash, {shape.left, shape.top, shape.right - shape.left, shape.bottom - shape.top}});
  }
  return result;
}

bool TextRasterizer::reaches(const PixelRect& target) const {
  return std::any_of(shapes_.begin(), shapes_.end(), [&](const Shape& shape) {
    return shape.right > target.x && shape.left < target.x + target.width &&
           shape.bottom > target.y && shape.top < target.y + target.height;
  });
}

std::vector<float> TextRasterizer::rasterize(const PixelRect& target) const {
  const size_t pixelCount = static_cast<size_t>(std::max(target.width, 0)) *
                            static_cast<size_t>(std::max(target.height, 0));
  std::vector<float> pixels(pixelCount * 4, 0.0F);
  if (pixelCount == 0 || shapes_.empty()) {
    return pixels;
  }
  std::vector<Coverage> fills(paints_.size());
  std::vector<Coverage> outlines(paints_.size());
  const FT_Pos shiftX = -static_cast<FT_Pos>(target.x) * 64;
  const FT_Pos shiftY = static_cast<FT_Pos>(target.y + target.height) * 64;
  for (const Shape& shape : shapes_) {
    if (shape.right <= target.x || shape.left >= target.x + target.width ||
        shape.bottom <= target.y || shape.top >= target.y + target.height) {
      continue;
    }
    Coverage& coverage = (shape.stroke ? outlines : fills)[shape.style];
    if (coverage.values.empty()) {
      coverage.width = target.width;
      coverage.height = target.height;
      coverage.values.assign(pixelCount, 0.0F);
    }
    auto& outline = const_cast<FT_Outline&>(shape.outline);
    FT_Outline_Translate(&outline, shiftX, shiftY);
    renderOutline(library_, outline, coverage);
    FT_Outline_Translate(&outline, -shiftX, -shiftY);
  }
  const auto composite = [&](const Coverage& coverage, const std::array<float, 4>& color) {
    if (coverage.values.empty()) {
      return;
    }
    for (size_t pixel = 0; pixel < pixelCount; ++pixel) {
      const float alpha = color[3] * coverage.values[pixel];
      if (alpha <= 0) {
        continue;
      }
      float* destination = pixels.data() + pixel * 4;
      const float keep = 1 - alpha;
      for (size_t channel = 0; channel < 3; ++channel) {
        destination[channel] = color[channel] * alpha + destination[channel] * keep;
      }
      destination[3] = alpha + destination[3] * keep;
    }
  };
  for (size_t style = 0; style < paints_.size(); ++style) {
    composite(outlines[style], paints_[style].outlineColor);
  }
  for (size_t style = 0; style < paints_.size(); ++style) {
    composite(fills[style], paints_[style].color);
  }
  return pixels;
}

std::optional<PixelRect> textBounds(const TextLayout& layout, const std::vector<TextPaint>& paints,
                                    const Affine& transform) {
  return TextRasterizer(layout, paints, transform).bounds();
}

std::vector<float> rasterizeText(const TextLayout& layout, const std::vector<TextPaint>& paints,
                                 const Affine& transform, const PixelRect& target) {
  return TextRasterizer(layout, paints, transform).rasterize(target);
}

}  // namespace nanquim::text
