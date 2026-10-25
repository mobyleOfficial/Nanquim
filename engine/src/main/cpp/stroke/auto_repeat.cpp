#include "stroke/auto_repeat.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "stroke/random.h"

namespace nanquim::stroke {

namespace {

constexpr int32_t kMaximumOutputSide = 2048;
constexpr int32_t kMinimumOutputSide = 16;
constexpr int32_t kMinimumPatchSide = 8;
constexpr int32_t kRandomCandidates = 32;
constexpr int32_t kPyramidLevels = 4;
constexpr int32_t kPyramidMinimumSide = 8;

struct Plane {
  int32_t width = 0;
  int32_t height = 0;
  std::vector<float> values;

  Plane() = default;
  Plane(int32_t w, int32_t h, float fill = 0)
      : width(w), height(h), values(size_t(w) * size_t(h), fill) {}
  float& at(int32_t x, int32_t y) { return values[size_t(y) * size_t(width) + size_t(x)]; }
  [[nodiscard]] float at(int32_t x, int32_t y) const {
    return values[size_t(y) * size_t(width) + size_t(x)];
  }
};

int32_t wrap(int32_t value, int32_t size) {
  const int32_t remainder = value % size;
  return remainder < 0 ? remainder + size : remainder;
}

Plane boxBlur(const Plane& source, int32_t radius) {
  Plane rows(source.width, source.height);
  const float scale = 1.0F / float(2 * radius + 1);
  for (int32_t y = 0; y < source.height; ++y) {
    float sum = 0;
    for (int32_t k = -radius; k <= radius; ++k) {
      sum += source.at(std::clamp(k, 0, source.width - 1), y);
    }
    for (int32_t x = 0; x < source.width; ++x) {
      rows.at(x, y) = sum * scale;
      sum += source.at(std::min(x + radius + 1, source.width - 1), y) -
             source.at(std::max(x - radius, 0), y);
    }
  }
  Plane result(source.width, source.height);
  for (int32_t x = 0; x < source.width; ++x) {
    float sum = 0;
    for (int32_t k = -radius; k <= radius; ++k) {
      sum += rows.at(x, std::clamp(k, 0, source.height - 1));
    }
    for (int32_t y = 0; y < source.height; ++y) {
      result.at(x, y) = sum * scale;
      sum += rows.at(x, std::min(y + radius + 1, source.height - 1)) -
             rows.at(x, std::max(y - radius, 0));
    }
  }
  return result;
}

Plane withoutLowBands(const Plane& source) {
  const int32_t radius = std::max(1, std::min(source.width, source.height) / 8);
  Plane blurred = boxBlur(boxBlur(boxBlur(source, radius), radius), radius);
  double mean = 0;
  for (float value : source.values) {
    mean += value;
  }
  mean /= double(source.values.size());
  Plane result(source.width, source.height);
  for (size_t index = 0; index < source.values.size(); ++index) {
    result.values[index] =
        std::clamp(source.values[index] - blurred.values[index] + float(mean), 0.0F, 1.0F);
  }
  return result;
}

struct Candidate {
  int32_t x = 0;
  int32_t y = 0;
  bool mirrored = false;
};

class Quilter {
 public:
  Quilter(Plane source, const AutoRepeatParameters& parameters)
      : source_(std::move(source)), parameters_(parameters), random_(parameters.seed) {
    const int32_t shorter = std::min(source_.width, source_.height);
    side_ = parameters.outputSize > 0 ? parameters.outputSize : shorter;
    side_ = std::clamp(side_, kMinimumOutputSide, kMaximumOutputSide);
    patch_ = std::clamp(int32_t(std::lround(parameters.patchScale * shorter)),
                        std::min(kMinimumPatchSide, shorter), shorter);
    overlap_ =
        std::clamp(int32_t(std::lround(std::clamp(parameters.seamOverlap, 0.0, 0.5) * patch_)), 1,
                   std::max(1, patch_ / 2));
    count_ = std::max(1, int32_t(std::ceil(double(side_) / double(patch_ - overlap_))));
    output_ = Plane(side_, side_);
    filled_.assign(size_t(side_) * size_t(side_), false);
    chosen_.resize(size_t(count_) * size_t(count_));
  }

  bool run(const std::function<bool(double)>& progress) {
    const int32_t total = count_ * count_;
    for (int32_t row = 0; row < count_; ++row) {
      for (int32_t column = 0; column < count_; ++column) {
        place(column, row);
        if (progress && !progress(double(row * count_ + column + 1) / total)) {
          return false;
        }
      }
    }
    return true;
  }

  AutoRepeatResult result() const {
    AutoRepeatResult result;
    result.side = side_;
    result.pixels.resize(output_.values.size());
    for (size_t index = 0; index < output_.values.size(); ++index) {
      result.pixels[index] =
          uint8_t(std::lround(std::clamp(output_.values[index], 0.0F, 1.0F) * 255.0F));
    }
    return result;
  }

 private:
  [[nodiscard]] int32_t cellStart(int32_t index) const {
    return int32_t((int64_t(index) * side_) / count_);
  }

  struct Rect {
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
  };
  [[nodiscard]] Rect rectOf(int32_t column, int32_t row) const {
    const int32_t x0 = cellStart(column);
    const int32_t y0 = cellStart(row);
    return {x0 - overlap_, y0 - overlap_, cellStart(column + 1) - x0 + overlap_,
            cellStart(row + 1) - y0 + overlap_};
  }

  [[nodiscard]] float sourceAt(const Candidate& candidate, const Rect& rect, int32_t u,
                               int32_t v) const {
    const int32_t x = candidate.mirrored ? candidate.x + rect.width - 1 - u : candidate.x + u;
    return source_.at(x, candidate.y + v);
  }

  [[nodiscard]] bool filledAt(int32_t x, int32_t y) const {
    return filled_[size_t(wrap(y, side_)) * size_t(side_) + size_t(wrap(x, side_))];
  }

  [[nodiscard]] double cost(const Candidate& candidate, const Rect& rect) const {
    double sum = 0;
    for (int32_t v = 0; v < rect.height; ++v) {
      for (int32_t u = 0; u < rect.width; ++u) {
        if (!filledAt(rect.x + u, rect.y + v)) {
          continue;
        }
        const double difference =
            double(sourceAt(candidate, rect, u, v)) -
            double(output_.at(wrap(rect.x + u, side_), wrap(rect.y + v, side_)));
        sum += difference * difference;
      }
    }
    return sum;
  }

  [[nodiscard]] Candidate clampCandidate(Candidate candidate, const Rect& rect) const {
    candidate.x = std::clamp(candidate.x, 0, source_.width - rect.width);
    candidate.y = std::clamp(candidate.y, 0, source_.height - rect.height);
    return candidate;
  }

  Candidate randomCandidate(const Rect& rect) {
    Candidate candidate;
    candidate.x = int32_t(random_.nextUnit() * double(source_.width - rect.width + 1));
    candidate.y = int32_t(random_.nextUnit() * double(source_.height - rect.height + 1));
    candidate.mirrored = parameters_.mirroring && random_.nextUnit() < 0.5;
    return clampCandidate(candidate, rect);
  }

  Candidate choose(int32_t column, int32_t row, const Rect& rect) {
    bool anyFilled = false;
    for (int32_t v = 0; v < rect.height && !anyFilled; ++v) {
      for (int32_t u = 0; u < rect.width && !anyFilled; ++u) {
        anyFilled = filledAt(rect.x + u, rect.y + v);
      }
    }
    Candidate best = randomCandidate(rect);
    if (!anyFilled) {
      return best;
    }
    double bestCost = cost(best, rect);
    auto consider = [&](Candidate candidate) {
      candidate = clampCandidate(candidate, rect);
      const double value = cost(candidate, rect);
      if (value < bestCost) {
        bestCost = value;
        best = candidate;
      }
    };
    for (int32_t index = 1; index < kRandomCandidates; ++index) {
      consider(randomCandidate(rect));
    }
    const int32_t step = patch_ - overlap_;
    if (column > 0) {
      Candidate left = chosen_[size_t(row) * size_t(count_) + size_t(column - 1)];
      left.x += left.mirrored ? -step : step;
      consider(left);
    }
    if (row > 0) {
      Candidate above = chosen_[size_t(row - 1) * size_t(count_) + size_t(column)];
      above.y += step;
      consider(above);
    }
    for (int32_t radius = std::max(source_.width, source_.height); radius >= 1; radius /= 2) {
      Candidate candidate = best;
      candidate.x += int32_t(std::lround(random_.uniform(-radius, radius)));
      candidate.y += int32_t(std::lround(random_.uniform(-radius, radius)));
      consider(candidate);
    }
    return best;
  }

  std::vector<int32_t> seamThrough(const Plane& patch, const Rect& rect, bool vertical,
                                   int32_t first, int32_t size) const {
    const int32_t length = vertical ? rect.height : rect.width;
    Plane total(size, length);
    for (int32_t a = 0; a < length; ++a) {
      for (int32_t k = 0; k < size; ++k) {
        const int32_t u = vertical ? first + k : a;
        const int32_t v = vertical ? a : first + k;
        const int32_t x = rect.x + u;
        const int32_t y = rect.y + v;
        float error = 0;
        if (filledAt(x, y)) {
          const float difference = patch.at(u, v) - output_.at(wrap(x, side_), wrap(y, side_));
          error = difference * difference;
        }
        float least = 0;
        if (a > 0) {
          least = total.at(k, a - 1);
          if (k > 0) {
            least = std::min(least, total.at(k - 1, a - 1));
          }
          if (k + 1 < size) {
            least = std::min(least, total.at(k + 1, a - 1));
          }
        }
        total.at(k, a) = error + least;
      }
    }
    std::vector<int32_t> seam(static_cast<size_t>(length));
    int32_t k = 0;
    for (int32_t index = 1; index < size; ++index) {
      if (total.at(index, length - 1) < total.at(k, length - 1)) {
        k = index;
      }
    }
    for (int32_t a = length - 1; a >= 0; --a) {
      seam[size_t(a)] = first + k;
      if (a > 0) {
        int32_t next = k;
        for (int32_t index = std::max(0, k - 1); index <= std::min(size - 1, k + 1); ++index) {
          if (total.at(index, a - 1) < total.at(next, a - 1)) {
            next = index;
          }
        }
        k = next;
      }
    }
    return seam;
  }

  [[nodiscard]] float ramp(float distance) const {
    const float feather =
        float(1.0 - std::clamp(parameters_.maskHardness, 0.0, 1.0)) * float(overlap_) / 2.0F;
    if (feather < 0.5F) {
      return distance > 0 ? 1.0F : 0.0F;
    }
    return std::clamp(0.5F + distance / (2.0F * feather), 0.0F, 1.0F);
  }

  [[nodiscard]] bool stripFilled(const Rect& rect, bool vertical, int32_t first,
                                 int32_t size) const {
    int32_t filled = 0;
    int32_t total = 0;
    const int32_t length = vertical ? rect.height : rect.width;
    for (int32_t a = 0; a < length; ++a) {
      for (int32_t b = first; b < first + size; ++b) {
        const int32_t x = rect.x + (vertical ? b : a);
        const int32_t y = rect.y + (vertical ? a : b);
        filled += filledAt(x, y) ? 1 : 0;
        ++total;
      }
    }
    return total > 0 && filled * 2 > total;
  }

  Plane maskFor(const Plane& patch, const Rect& rect) const {
    Plane mask(rect.width, rect.height, 1.0F);
    const int32_t strip = std::min(overlap_, std::min(rect.width, rect.height) / 2);
    if (strip >= 1) {
      for (const bool vertical : {true, false}) {
        const int32_t across = vertical ? rect.width : rect.height;
        for (const bool leading : {true, false}) {
          const int32_t first = leading ? 0 : across - strip;
          if (!stripFilled(rect, vertical, first, strip)) {
            continue;
          }
          const std::vector<int32_t> seam = seamThrough(patch, rect, vertical, first, strip);
          for (int32_t a = 0; a < int32_t(seam.size()); ++a) {
            for (int32_t k = first; k < first + strip; ++k) {
              const float distance =
                  leading ? float(k - seam[size_t(a)]) : float(seam[size_t(a)] - k);
              float& weight = vertical ? mask.at(k, a) : mask.at(a, k);
              weight *= ramp(distance);
            }
          }
        }
      }
    }
    for (int32_t v = 0; v < rect.height; ++v) {
      for (int32_t u = 0; u < rect.width; ++u) {
        if (!filledAt(rect.x + u, rect.y + v)) {
          mask.at(u, v) = 1.0F;
        }
      }
    }
    return mask;
  }

  static Plane downsample(const Plane& plane) {
    Plane result(std::max(1, plane.width / 2), std::max(1, plane.height / 2));
    for (int32_t y = 0; y < result.height; ++y) {
      for (int32_t x = 0; x < result.width; ++x) {
        const int32_t x0 = std::min(2 * x, plane.width - 1);
        const int32_t x1 = std::min(2 * x + 1, plane.width - 1);
        const int32_t y0 = std::min(2 * y, plane.height - 1);
        const int32_t y1 = std::min(2 * y + 1, plane.height - 1);
        result.at(x, y) =
            (plane.at(x0, y0) + plane.at(x1, y0) + plane.at(x0, y1) + plane.at(x1, y1)) / 4;
      }
    }
    return result;
  }

  static Plane upsample(const Plane& plane, int32_t width, int32_t height) {
    Plane result(width, height);
    for (int32_t y = 0; y < height; ++y) {
      for (int32_t x = 0; x < width; ++x) {
        const float sx =
            std::clamp((x + 0.5F) * plane.width / width - 0.5F, 0.0F, float(plane.width - 1));
        const float sy =
            std::clamp((y + 0.5F) * plane.height / height - 0.5F, 0.0F, float(plane.height - 1));
        const auto x0 = int32_t(sx);
        const auto y0 = int32_t(sy);
        const int32_t x1 = std::min(x0 + 1, plane.width - 1);
        const int32_t y1 = std::min(y0 + 1, plane.height - 1);
        const float fx = sx - float(x0);
        const float fy = sy - float(y0);
        const float topRow = plane.at(x0, y0) * (1 - fx) + plane.at(x1, y0) * fx;
        const float bottomRow = plane.at(x0, y1) * (1 - fx) + plane.at(x1, y1) * fx;
        result.at(x, y) = topRow * (1 - fy) + bottomRow * fy;
      }
    }
    return result;
  }

  static Plane pyramidBlend(const Plane& fresh, const Plane& old, const Plane& mask) {
    std::vector<Plane> freshLevels{fresh};
    std::vector<Plane> oldLevels{old};
    std::vector<Plane> maskLevels{mask};
    while (int32_t(freshLevels.size()) < kPyramidLevels &&
           std::min(freshLevels.back().width, freshLevels.back().height) >=
               2 * kPyramidMinimumSide) {
      freshLevels.push_back(downsample(freshLevels.back()));
      oldLevels.push_back(downsample(oldLevels.back()));
      maskLevels.push_back(downsample(maskLevels.back()));
    }
    const size_t levels = freshLevels.size();
    Plane result(freshLevels.back().width, freshLevels.back().height);
    for (size_t index = 0; index < result.values.size(); ++index) {
      const float weight = maskLevels.back().values[index];
      result.values[index] =
          freshLevels.back().values[index] * weight + oldLevels.back().values[index] * (1 - weight);
    }
    for (size_t level = levels - 1; level-- > 0;) {
      const Plane& f = freshLevels[level];
      const Plane& o = oldLevels[level];
      const Plane freshUp = upsample(freshLevels[level + 1], f.width, f.height);
      const Plane oldUp = upsample(oldLevels[level + 1], f.width, f.height);
      Plane next = upsample(result, f.width, f.height);
      for (size_t index = 0; index < next.values.size(); ++index) {
        const float weight = maskLevels[level].values[index];
        next.values[index] += (f.values[index] - freshUp.values[index]) * weight +
                              (o.values[index] - oldUp.values[index]) * (1 - weight);
      }
      result = std::move(next);
    }
    return result;
  }

  void place(int32_t column, int32_t row) {
    const Rect rect = rectOf(column, row);
    const Candidate candidate = choose(column, row, rect);
    chosen_[size_t(row) * size_t(count_) + size_t(column)] = candidate;
    Plane patch(rect.width, rect.height);
    Plane old(rect.width, rect.height);
    for (int32_t v = 0; v < rect.height; ++v) {
      for (int32_t u = 0; u < rect.width; ++u) {
        patch.at(u, v) = sourceAt(candidate, rect, u, v);
        const bool filled = filledAt(rect.x + u, rect.y + v);
        old.at(u, v) =
            filled ? output_.at(wrap(rect.x + u, side_), wrap(rect.y + v, side_)) : patch.at(u, v);
      }
    }
    const Plane mask = maskFor(patch, rect);
    Plane blended(rect.width, rect.height);
    if (parameters_.pyramidBlending) {
      blended = pyramidBlend(patch, old, mask);
    } else {
      for (size_t index = 0; index < blended.values.size(); ++index) {
        blended.values[index] =
            patch.values[index] * mask.values[index] + old.values[index] * (1 - mask.values[index]);
      }
    }
    for (int32_t v = 0; v < rect.height; ++v) {
      for (int32_t u = 0; u < rect.width; ++u) {
        const int32_t x = wrap(rect.x + u, side_);
        const int32_t y = wrap(rect.y + v, side_);
        output_.at(x, y) = blended.at(u, v);
        filled_[size_t(y) * size_t(side_) + size_t(x)] = true;
      }
    }
  }

  Plane source_;
  AutoRepeatParameters parameters_;
  Random random_;
  int32_t side_ = 0;
  int32_t patch_ = 0;
  int32_t overlap_ = 0;
  int32_t count_ = 0;
  Plane output_;
  std::vector<bool> filled_;
  std::vector<Candidate> chosen_;
};

}  // namespace

AutoRepeatResult autoRepeat(const std::vector<uint8_t>& pixels, int32_t width, int32_t height,
                            const AutoRepeatParameters& parameters,
                            const std::function<bool(double)>& progress) {
  if (width < 1 || height < 1 || pixels.size() != size_t(width) * size_t(height)) {
    return {};
  }
  Plane source(width, height);
  for (size_t index = 0; index < pixels.size(); ++index) {
    source.values[index] = float(pixels[index]) / 255.0F;
  }
  if (parameters.ignoreLowBands) {
    source = withoutLowBands(source);
  }
  Quilter quilter(std::move(source), parameters);
  if (!quilter.run(progress)) {
    return {};
  }
  return quilter.result();
}

}  // namespace nanquim::stroke
