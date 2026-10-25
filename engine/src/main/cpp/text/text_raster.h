#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

#include "text/layout.h"

struct FT_LibraryRec_;

namespace nanquim::text {

struct TextPaint {
  std::array<float, 4> color{0, 0, 0, 1};
  float outlineWidth = 0;
  std::array<float, 4> outlineColor{0, 0, 0, 1};
};

using Affine = std::array<double, 6>;
inline constexpr Affine kIdentityAffine{1, 0, 0, 1, 0, 0};

struct PixelRect {
  int32_t x = 0;
  int32_t y = 0;
  int32_t width = 0;
  int32_t height = 0;
};

class TextRasterizer {
 public:
  TextRasterizer(const TextLayout& layout, std::vector<TextPaint> paints, const Affine& transform);
  TextRasterizer(const TextRasterizer&) = delete;
  TextRasterizer& operator=(const TextRasterizer&) = delete;
  ~TextRasterizer();

  [[nodiscard]] std::optional<PixelRect> bounds() const { return bounds_; }

  [[nodiscard]] std::vector<float> rasterize(const PixelRect& target) const;
  [[nodiscard]] bool reaches(const PixelRect& target) const;

  struct ShapeSignature {
    uint64_t hash = 0;
    PixelRect bounds;
  };
  [[nodiscard]] std::vector<ShapeSignature> signatures() const;

 private:
  struct Shape;

  FT_LibraryRec_* library_ = nullptr;
  std::vector<TextPaint> paints_;
  std::vector<Shape> shapes_;
  std::optional<PixelRect> bounds_;
};

std::optional<PixelRect> textBounds(const TextLayout& layout, const std::vector<TextPaint>& paints,
                                    const Affine& transform);
std::vector<float> rasterizeText(const TextLayout& layout, const std::vector<TextPaint>& paints,
                                 const Affine& transform, const PixelRect& target);

}  // namespace nanquim::text
