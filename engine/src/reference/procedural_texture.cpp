#include "procedural_texture.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>

#include "stroke/random.h"

namespace nanquim::reference {

namespace {

constexpr int32_t kMaximumSize = 4096;
constexpr double kPi = 3.14159265358979323846;

uint8_t toByte(double value) {
  return static_cast<uint8_t>(std::lround(std::clamp(value, 0.0, 1.0) * 255.0));
}

double smoothstep(double edge0, double edge1, double value) {
  const double t = std::clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}

class ValueNoise {
 public:
  ValueNoise(int32_t cells, stroke::Random& random) : cells_(cells) {
    values_.resize(static_cast<size_t>(cells) * static_cast<size_t>(cells));
    for (double& value : values_) {
      value = random.nextUnit();
    }
  }

  [[nodiscard]] double at(double u, double v) const {
    const double x = u * cells_;
    const double y = v * cells_;
    const auto x0 = static_cast<int32_t>(std::floor(x));
    const auto y0 = static_cast<int32_t>(std::floor(y));
    const double fx = smoothstep(0, 1, x - x0);
    const double fy = smoothstep(0, 1, y - y0);
    const double top = lattice(x0, y0) * (1 - fx) + lattice(x0 + 1, y0) * fx;
    const double bottom = lattice(x0, y0 + 1) * (1 - fx) + lattice(x0 + 1, y0 + 1) * fx;
    return top * (1 - fy) + bottom * fy;
  }

 private:
  [[nodiscard]] double lattice(int32_t x, int32_t y) const {
    const int32_t wrappedX = ((x % cells_) + cells_) % cells_;
    const int32_t wrappedY = ((y % cells_) + cells_) % cells_;
    return values_[static_cast<size_t>(wrappedY) * static_cast<size_t>(cells_) +
                   static_cast<size_t>(wrappedX)];
  }

  int32_t cells_;
  std::vector<double> values_;
};

ProceduralTexture disc(int32_t size, double hardness) {
  ProceduralTexture texture{size, std::vector<uint8_t>(static_cast<size_t>(size * size))};
  const double radius = size / 2.0 - 1;
  const double inner = radius * std::clamp(hardness, 0.0, 1.0);
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const double distance = std::hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0);
      const double value = distance <= inner ? 1.0 : 1.0 - smoothstep(inner, radius, distance);
      texture.pixels[static_cast<size_t>(y * size + x)] = toByte(value);
    }
  }
  return texture;
}

ProceduralTexture bristles(int32_t size, uint64_t seed, double count) {
  ProceduralTexture texture{size, std::vector<uint8_t>(static_cast<size_t>(size * size))};
  stroke::Random random(seed);
  const auto bristleCount = std::max<int32_t>(2, static_cast<int32_t>(count));
  std::vector<double> strength(static_cast<size_t>(bristleCount));
  for (double& value : strength) {
    value = 0.45 + 0.55 * random.nextUnit();
  }
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const double u = (x + 0.5) / size * 2 - 1;
      const double v = (y + 0.5) / size * 2 - 1;
      const double ellipse = 1.0 - smoothstep(0.85, 1.0, std::hypot(u / 0.35, v / 0.95));
      const double position = (v + 1) / 2 * bristleCount;
      const auto index = std::clamp(static_cast<int32_t>(position), 0, bristleCount - 1);
      const double withinBristle = position - std::floor(position);
      const double profile = std::sin(withinBristle * 3.14159265358979323846);
      texture.pixels[static_cast<size_t>(y * size + x)] =
          toByte(ellipse * strength[static_cast<size_t>(index)] * (0.4 + 0.6 * profile));
    }
  }
  return texture;
}

ProceduralTexture paper(int32_t size, uint64_t seed, double frequency) {
  ProceduralTexture texture{size, std::vector<uint8_t>(static_cast<size_t>(size * size))};
  stroke::Random random(seed);
  const auto baseCells = std::max<int32_t>(1, static_cast<int32_t>(frequency));
  std::vector<ValueNoise> octaves;
  std::vector<double> weights;
  for (int32_t octave = 0, cells = baseCells; octave < 5 && cells <= size; ++octave, cells *= 2) {
    octaves.emplace_back(cells, random);
    weights.push_back(std::pow(0.55, octave));
  }
  double total = 0;
  for (const double weight : weights) {
    total += weight;
  }
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const double u = (x + 0.5) / size;
      const double v = (y + 0.5) / size;
      double value = 0;
      for (size_t octave = 0; octave < octaves.size(); ++octave) {
        value += octaves[octave].at(u, v) * weights[octave];
      }
      value /= total;
      texture.pixels[static_cast<size_t>(y * size + x)] = toByte(smoothstep(0.3, 0.7, value));
    }
  }
  return texture;
}

ProceduralTexture noise(int32_t size, uint64_t seed, double density) {
  ProceduralTexture texture{size, std::vector<uint8_t>(static_cast<size_t>(size * size))};
  stroke::Random random(seed);
  for (uint8_t& pixel : texture.pixels) {
    pixel = random.nextUnit() < density ? 255 : 0;
  }
  return texture;
}

ProceduralTexture chalk(int32_t size, uint64_t seed, double roughness) {
  ProceduralTexture texture{size, std::vector<uint8_t>(static_cast<size_t>(size * size))};
  stroke::Random random(seed);
  const ValueNoise edge(16, random);
  const ValueNoise grain(std::max(4, size / 8), random);
  const double rough = std::clamp(roughness, 0.0, 1.0);
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      const double u = (x + 0.5) / size * 2 - 1;
      const double v = (y + 0.5) / size * 2 - 1;
      const double angle = std::atan2(v, u) / (2 * 3.14159265358979323846) + 0.5;
      const double radius = 0.92 - rough * 0.35 * edge.at(angle, 0.5);
      const double inside = 1.0 - smoothstep(radius - 0.08, radius, std::hypot(u, v));
      const double speckle = smoothstep(0.2, 0.6, grain.at((u + 1) / 2, (v + 1) / 2));
      texture.pixels[static_cast<size_t>(y * size + x)] =
          toByte(inside * (1 - rough * 0.6 * (1 - speckle)));
    }
  }
  return texture;
}

double fract(double value) { return value - std::floor(value); }

double distanceToLines(double value, double repeats) {
  const double f = fract(value * repeats);
  return std::min(f, 1 - f) / repeats;
}

double line(double distance, double halfWidth, double pixel) {
  return 1.0 - smoothstep(halfWidth - pixel / 2, halfWidth + pixel / 2, distance);
}

template <typename Function>
ProceduralTexture fill(int32_t size, Function value) {
  ProceduralTexture texture{
      size, std::vector<uint8_t>(static_cast<size_t>(size) * static_cast<size_t>(size))};
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      texture.pixels[static_cast<size_t>(y) * static_cast<size_t>(size) + static_cast<size_t>(x)] =
          toByte(value((x + 0.5) / size, (y + 0.5) / size));
    }
  }
  return texture;
}

class Voronoi {
 public:
  struct Query {
    size_t site = 0;
    double offsetX = 0;
    double offsetY = 0;
    double edge = 0;
  };

  Voronoi(int32_t columns, int32_t rows, std::vector<std::array<double, 2>> sites)
      : columns_(columns), rows_(rows), sites_(std::move(sites)) {}

  static Voronoi hexagonal(int32_t columns) {
    columns = std::max(columns, 1);
    const int32_t rows =
        std::max(2, 2 * static_cast<int32_t>(std::lround(columns / std::sqrt(3.0))));
    std::vector<std::array<double, 2>> sites;
    for (int32_t row = 0; row < rows; ++row) {
      for (int32_t column = 0; column < columns; ++column) {
        sites.push_back({(column + 0.5 + (row % 2) * 0.5) / columns, (row + 0.5) / rows});
      }
    }
    return {columns, rows, std::move(sites)};
  }

  static Voronoi random(int32_t cells, uint64_t seed) {
    cells = std::max(cells, 1);
    stroke::Random random(seed);
    std::vector<std::array<double, 2>> sites;
    for (int32_t row = 0; row < cells; ++row) {
      for (int32_t column = 0; column < cells; ++column) {
        const double x = (column + 0.15 + 0.7 * random.nextUnit()) / cells;
        const double y = (row + 0.15 + 0.7 * random.nextUnit()) / cells;
        sites.push_back({x, y});
      }
    }
    return {cells, cells, std::move(sites)};
  }

  [[nodiscard]] Query at(double u, double v) const {
    const auto column = static_cast<int32_t>(std::floor(u * columns_));
    const auto row = static_cast<int32_t>(std::floor(v * rows_));
    std::array<Neighbor, 25> neighbors{};
    size_t count = 0;
    for (int32_t dy = -2; dy <= 2; ++dy) {
      for (int32_t dx = -2; dx <= 2; ++dx) {
        const int32_t wrappedColumn = ((column + dx) % columns_ + columns_) % columns_;
        const int32_t wrappedRow = ((row + dy) % rows_ + rows_) % rows_;
        const size_t index = static_cast<size_t>(wrappedRow) * static_cast<size_t>(columns_) +
                             static_cast<size_t>(wrappedColumn);
        const auto& site = sites_[index];
        const double x = site[0] - std::round(site[0] - u);
        const double y = site[1] - std::round(site[1] - v);
        neighbors[count++] = {index, x, y};
      }
    }
    size_t nearest = 0;
    double best = 1e9;
    for (size_t index = 0; index < count; ++index) {
      const double distance = std::hypot(u - neighbors[index].x, v - neighbors[index].y);
      if (distance < best) {
        best = distance;
        nearest = index;
      }
    }
    const Neighbor& site = neighbors[nearest];
    double edge = 1e9;
    for (size_t index = 0; index < count; ++index) {
      const Neighbor& other = neighbors[index];
      const double dx = other.x - site.x;
      const double dy = other.y - site.y;
      const double length = std::hypot(dx, dy);
      if (index == nearest || length < 1e-9) {
        continue;
      }
      const double midX = (site.x + other.x) / 2;
      const double midY = (site.y + other.y) / 2;
      edge = std::min(edge, ((midX - u) * dx + (midY - v) * dy) / length);
    }
    return {site.site, u - site.x, v - site.y, edge};
  }

 private:
  struct Neighbor {
    size_t site = 0;
    double x = 0;
    double y = 0;
  };

  int32_t columns_;
  int32_t rows_;
  std::vector<std::array<double, 2>> sites_;
};

class Turbulence {
 public:
  Turbulence(int32_t cells, stroke::Random& random) {
    for (int32_t octave = 0; octave < 4; ++octave) {
      octaves_.emplace_back(cells << octave, random);
    }
  }

  [[nodiscard]] double at(double u, double v) const {
    double value = 0;
    double weight = 1;
    double total = 0;
    for (const ValueNoise& octave : octaves_) {
      value += octave.at(u, v) * weight;
      total += weight;
      weight *= 0.5;
    }
    return value / total;
  }

 private:
  std::vector<ValueNoise> octaves_;
};

int32_t repeats(double parameter) {
  return std::max(1, static_cast<int32_t>(std::lround(parameter)));
}

ProceduralTexture grid(int32_t size, double parameter) {
  const double cells = repeats(parameter);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const double halfWidth = 0.06 / cells;
    return std::max(line(distanceToLines(u, cells), halfWidth, pixel),
                    line(distanceToLines(v, cells), halfWidth, pixel));
  });
}

ProceduralTexture stripes(int32_t size, double parameter) {
  const double count = repeats(parameter);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const double distance = distanceToLines(u + v, count) / std::sqrt(2.0);
    return line(distance, 0.25 / (count * std::sqrt(2.0)), pixel);
  });
}

ProceduralTexture dots(int32_t size, double parameter) {
  const double cells = repeats(parameter);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const double distance = std::hypot(fract(u * cells) - 0.5, fract(v * cells) - 0.5) / cells;
    return line(distance, 0.3 / cells, pixel);
  });
}

ProceduralTexture voronoiEdges(int32_t size, const Voronoi& voronoi, double halfWidth) {
  const double pixel = 1.0 / size;
  return fill(size,
              [&](double u, double v) { return line(voronoi.at(u, v).edge, halfWidth, pixel); });
}

ProceduralTexture polygons(int32_t size, uint64_t seed, double parameter) {
  const int32_t cells = repeats(parameter);
  const Voronoi voronoi = Voronoi::random(cells, seed);
  stroke::Random random(seed + 1);
  std::vector<double> grays(static_cast<size_t>(cells) * static_cast<size_t>(cells));
  for (double& gray : grays) {
    gray = 0.3 + 0.7 * random.nextUnit();
  }
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const Voronoi::Query query = voronoi.at(u, v);
    return grays[query.site] * (1 - line(query.edge, 0.02 / cells, pixel));
  });
}

ProceduralTexture cubes(int32_t size, double parameter) {
  const int32_t cells = repeats(parameter);
  const Voronoi voronoi = Voronoi::hexagonal(cells);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const Voronoi::Query query = voronoi.at(u, v);
    const double angle = std::atan2(-query.offsetY, query.offsetX) * 180 / kPi;
    const double degrees = angle < 0 ? angle + 360 : angle;
    double shade = 0.35;
    if (degrees >= 30 && degrees < 150) {
      shade = 1.0;
    } else if (degrees >= 150 && degrees < 270) {
      shade = 0.65;
    }
    return shade * (1 - line(query.edge, 0.025 / cells, pixel));
  });
}

ProceduralTexture triangles(int32_t size, double parameter) {
  const double cells = repeats(parameter);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const double halfWidth = 0.05 / cells;
    const double diagonal = distanceToLines(u - v, cells) / std::sqrt(2.0);
    return std::max({line(distanceToLines(u, cells), halfWidth, pixel),
                     line(distanceToLines(v, cells), halfWidth, pixel),
                     line(diagonal, halfWidth, pixel)});
  });
}

ProceduralTexture bricks(int32_t size, uint64_t seed, double parameter) {
  const int32_t rows = repeats(parameter);
  stroke::Random random(seed);
  std::vector<std::vector<double>> joints(static_cast<size_t>(rows));
  for (auto& row : joints) {
    double x = random.nextUnit() / rows;
    while (x < 1) {
      row.push_back(x);
      x += (0.6 + 1.6 * random.nextUnit()) / rows;
    }
  }
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const auto& row =
        joints[static_cast<size_t>(std::min<int32_t>(rows - 1, static_cast<int32_t>(v * rows)))];
    double joint = 1;
    for (const double x : row) {
      const double d = std::abs(u - x);
      joint = std::min({joint, d, 1 - d});
    }
    const double distance = std::min(joint, distanceToLines(v, rows));
    return 1 - line(distance, 0.06 / rows, pixel);
  });
}

ProceduralTexture wood(int32_t size, uint64_t seed, double parameter) {
  const double rings = repeats(parameter);
  stroke::Random random(seed);
  const Turbulence turbulence(2, random);
  return fill(size, [&](double u, double v) {
    const double t = rings * u + 1.5 * turbulence.at(u, v);
    return smoothstep(0.55, 0.95, 0.5 + 0.5 * std::sin(2 * kPi * t));
  });
}

ProceduralTexture marble(int32_t size, uint64_t seed, double parameter) {
  const double veins = repeats(parameter);
  stroke::Random random(seed);
  const Turbulence turbulence(2, random);
  return fill(size, [&](double u, double v) {
    const double t = veins * (u + v) + 3.0 * turbulence.at(u, v);
    return 1 - smoothstep(0.0, 0.25, std::abs(std::sin(kPi * t)));
  });
}

ProceduralTexture waves(int32_t size, double parameter) {
  const double lines = repeats(parameter);
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const double t = v + 0.25 / lines * std::sin(2 * kPi * 2 * u);
    return line(distanceToLines(t, lines), 0.12 / lines, pixel);
  });
}

ProceduralTexture rosette(int32_t size, double parameter) {
  const double cells = repeats(parameter);
  const double pixel = cells / size;
  return fill(size, [&](double u, double v) {
    const double x = fract(u * cells) - 0.5;
    const double y = fract(v * cells) - 0.5;
    const double radius = std::hypot(x, y);
    const double angle = std::atan2(y, x);
    const double petals = line(radius, 0.42 * (0.45 + 0.55 * std::abs(std::cos(3 * angle))), pixel);
    const double center = line(radius, 0.07, pixel);
    return std::max(petals * (1 - center), line(std::abs(radius - 0.47), 0.015, pixel));
  });
}

ProceduralTexture ornament(int32_t size, double parameter) {
  const double cells = repeats(parameter);
  const double pixel = cells / size;
  return fill(size, [&](double u, double v) {
    const double x = fract(u * cells) - 0.5;
    const double y = fract(v * cells) - 0.5;
    const double radius = std::hypot(x, y);
    const double flower =
        line(radius, 0.3 * (0.35 + 0.65 * std::abs(std::cos(2 * std::atan2(y, x)))), pixel);
    const double diamond =
        line(std::abs(std::abs(x) + std::abs(y) - 0.5) / std::sqrt(2.0), 0.02, pixel);
    return std::max(flower, diamond);
  });
}

double segmentDistance(double x, double y, double x0, double y0, double x1, double y1) {
  const double dx = x1 - x0;
  const double dy = y1 - y0;
  const double t = std::clamp(((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy), 0.0, 1.0);
  return std::hypot(x - x0 - t * dx, y - y0 - t * dy);
}

template <typename Function>
ProceduralTexture tip(int32_t size, Function value) {
  return fill(size, [&](double u, double v) {
    const double x = u * 2 - 1;
    const double y = v * 2 - 1;
    return value(x, y) * (1 - smoothstep(0.96, 1.0, std::hypot(x, y)));
  });
}

ProceduralTexture spatter(int32_t size, uint64_t seed, double parameter) {
  const int32_t count = std::clamp(static_cast<int32_t>(parameter), 1, 2000);
  stroke::Random random(seed);
  struct Drop {
    double x, y, radius;
  };
  std::vector<Drop> drops;
  for (int32_t index = 0; index < count; ++index) {
    const double distance = 0.85 * std::pow(random.nextUnit(), 0.75);
    const double angle = 2 * kPi * random.nextUnit();
    const double radius = (0.01 + 0.07 * std::pow(random.nextUnit(), 3)) * (1.2 - distance);
    drops.push_back({distance * std::cos(angle), distance * std::sin(angle), radius});
  }
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    double value = 0;
    for (const Drop& drop : drops) {
      value = std::max(value, line(std::hypot(x - drop.x, y - drop.y), drop.radius, pixel));
    }
    return value;
  });
}

ProceduralTexture blotch(int32_t size, uint64_t seed, double roughness) {
  stroke::Random random(seed);
  const ValueNoise edge(8, random);
  const Turbulence inside(4, random);
  const double rough = std::clamp(roughness, 0.0, 1.0);
  return tip(size, [&](double x, double y) {
    const double angle = std::atan2(y, x) / (2 * kPi) + 0.5;
    const double radius = 0.85 - rough * 0.35 * edge.at(angle, 0.25);
    const double distance = std::hypot(x, y);
    const double shape = 1 - smoothstep(radius - 0.03, radius, distance);
    const double rim = smoothstep(radius - 0.2, radius - 0.02, distance);
    const double mottle = 0.75 + 0.25 * inside.at((x + 1) / 2, (y + 1) / 2);
    return shape * (0.45 + 0.35 * mottle + 0.3 * rim);
  });
}

ProceduralTexture sponge(int32_t size, uint64_t seed, double parameter) {
  const int32_t cells = repeats(parameter);
  const Voronoi pores = Voronoi::random(cells, seed);
  stroke::Random random(seed + 1);
  const ValueNoise edge(10, random);
  return tip(size, [&](double x, double y) {
    const Voronoi::Query query = pores.at((x + 1) / 2, (y + 1) / 2);
    const double hole =
        smoothstep(0.1 / cells, 0.3 / cells, std::hypot(query.offsetX, query.offsetY));
    const double radius = 0.8 - 0.15 * edge.at(std::atan2(y, x) / (2 * kPi) + 0.5, 0.5);
    return hole * (1 - smoothstep(radius - 0.1, radius, std::hypot(x, y)));
  });
}

ProceduralTexture star(int32_t size, uint64_t seed, double parameter) {
  const int32_t count = std::clamp(static_cast<int32_t>(parameter), 2, 64);
  stroke::Random random(seed);
  struct Spike {
    double dx, dy, length;
  };
  std::vector<Spike> spikes;
  for (int32_t index = 0; index < count; ++index) {
    const double angle = 2 * kPi * (index + 0.6 * random.nextUnit()) / count;
    spikes.push_back({std::cos(angle), std::sin(angle), 0.45 + 0.5 * random.nextUnit()});
  }
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    double value = line(std::hypot(x, y), 0.08, pixel);
    for (const Spike& spike : spikes) {
      const double along = std::clamp(x * spike.dx + y * spike.dy, 0.0, spike.length);
      const double across = std::hypot(x - along * spike.dx, y - along * spike.dy);
      value = std::max(value, line(across, 0.035 * (1 - along / spike.length), pixel));
    }
    return value;
  });
}

ProceduralTexture drip(int32_t size, double parameter) {
  const double width = 0.04 + 0.12 * std::clamp(parameter, 0.0, 1.0);
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    const double head = line(std::hypot(x, y - 0.6), 0.22, pixel);
    const double t = std::clamp((y + 0.85) / 1.45, 0.0, 1.0);
    const double tail =
        y > -0.85 && y < 0.6 ? line(std::abs(x), width * (0.35 + 0.65 * t), pixel) : 0.0;
    return std::max(head, tail);
  });
}

ProceduralTexture stick(int32_t size, double parameter) {
  const double halfWidth = 0.02 + 0.06 * std::clamp(parameter, 0.0, 1.0);
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    const double head = std::abs(std::hypot(x, y + 0.6) - 0.2);
    const double body = std::min(
        {segmentDistance(x, y, 0, -0.4, 0, 0.25), segmentDistance(x, y, -0.4, -0.15, 0.4, -0.15),
         segmentDistance(x, y, 0, 0.25, -0.3, 0.85), segmentDistance(x, y, 0, 0.25, 0.3, 0.85)});
    return line(std::min(head, body), halfWidth, pixel);
  });
}

ProceduralTexture streak(int32_t size, uint64_t seed, double thickness) {
  const double maximum = 0.05 + 0.25 * std::clamp(thickness, 0.0, 1.0);
  stroke::Random random(seed);
  const ValueNoise fibers(24, random);
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    const double t = (x + 0.95) / 1.9;
    if (t < 0 || t > 1) {
      return 0.0;
    }
    const double halfWidth = maximum * std::sqrt(1 - t) * std::min(1.0, t * 8);
    const double fiber = 0.7 + 0.3 * fibers.at(0.5, (y + 1) / 2);
    return line(std::abs(y), halfWidth, pixel) * fiber;
  });
}

ProceduralTexture metal(int32_t size, uint64_t seed, double parameter) {
  const int32_t count = std::clamp(static_cast<int32_t>(parameter), 4, 4000);
  stroke::Random random(seed);
  struct Scratch {
    double y, x0, length, strength, width;
  };
  std::vector<Scratch> scratches;
  for (int32_t index = 0; index < count; ++index) {
    scratches.push_back({random.nextUnit(), random.nextUnit(), 0.1 + 0.6 * random.nextUnit(),
                         0.3 + 0.7 * random.nextUnit(), (0.3 + random.nextUnit()) / size});
  }
  const ValueNoise tone(8, random);
  std::vector<double> values(static_cast<size_t>(size) * static_cast<size_t>(size));
  for (int32_t y = 0; y < size; ++y) {
    for (int32_t x = 0; x < size; ++x) {
      values[static_cast<size_t>(y) * size + x] =
          0.4 + 0.2 * tone.at((x + 0.5) / size, (y + 0.5) / size);
    }
  }
  for (const Scratch& scratch : scratches) {
    const auto row = static_cast<int32_t>(scratch.y * size);
    const auto first = static_cast<int32_t>(scratch.x0 * size);
    const auto length = static_cast<int32_t>(scratch.length * size);
    for (int32_t step = 0; step < length; ++step) {
      const int32_t x = (first + step) % size;
      const double fade = std::sin(kPi * step / std::max(length, 1));
      double& value = values[static_cast<size_t>(row) * size + x];
      value = std::max(value, std::min(1.0, 0.6 + 0.4 * scratch.strength * fade));
    }
  }
  return fill(size, [&](double u, double v) {
    const auto x = std::min(static_cast<int32_t>(u * size), size - 1);
    const auto y = std::min(static_cast<int32_t>(v * size), size - 1);
    return values[static_cast<size_t>(y) * size + x];
  });
}

ProceduralTexture concrete(int32_t size, uint64_t seed, double parameter) {
  const int32_t pits = repeats(parameter);
  stroke::Random random(seed);
  const Turbulence tone(4, random);
  const Voronoi holes = Voronoi::random(pits, seed + 1);
  stroke::Random sizes(seed + 2);
  std::vector<double> radii(static_cast<size_t>(pits) * static_cast<size_t>(pits));
  for (double& radius : radii) {
    radius = sizes.nextUnit() < 0.4 ? (0.05 + 0.25 * sizes.nextUnit()) / pits : 0;
  }
  return fill(size, [&](double u, double v) {
    const Voronoi::Query query = holes.at(u, v);
    const double radius = radii[query.site % radii.size()];
    const double pit =
        radius > 0 ? smoothstep(radius * 0.6, radius, std::hypot(query.offsetX, query.offsetY)) : 1;
    return (0.45 + 0.4 * tone.at(u, v)) * (0.25 + 0.75 * pit);
  });
}

ProceduralTexture fiber(int32_t size, uint64_t seed, double parameter) {
  const int32_t count = std::clamp(static_cast<int32_t>(parameter), 1, 1000);
  stroke::Random random(seed);
  struct Strand {
    double x, y, angle, length, curl;
  };
  std::vector<Strand> strands;
  for (int32_t index = 0; index < count; ++index) {
    strands.push_back({random.nextUnit(), random.nextUnit(), 2 * kPi * random.nextUnit(),
                       0.2 + 0.5 * random.nextUnit(), (random.nextUnit() - 0.5) * 6});
  }
  std::vector<double> values(static_cast<size_t>(size) * static_cast<size_t>(size), 0.0);
  for (const Strand& strand : strands) {
    double x = strand.x;
    double y = strand.y;
    double angle = strand.angle;
    const int32_t steps = static_cast<int32_t>(strand.length * size);
    for (int32_t step = 0; step < steps; ++step) {
      angle += strand.curl / size;
      x += std::cos(angle) / size;
      y += std::sin(angle) / size;
      const int32_t column = ((static_cast<int32_t>(std::floor(x * size)) % size) + size) % size;
      const int32_t row = ((static_cast<int32_t>(std::floor(y * size)) % size) + size) % size;
      double& value = values[static_cast<size_t>(row) * size + column];
      value = std::min(1.0, value + 0.6);
    }
  }
  return fill(size, [&](double u, double v) {
    const auto x = std::min(static_cast<int32_t>(u * size), size - 1);
    const auto y = std::min(static_cast<int32_t>(v * size), size - 1);
    return values[static_cast<size_t>(y) * size + x];
  });
}

ProceduralTexture minerals(int32_t size, uint64_t seed, double parameter) {
  const int32_t cells = repeats(parameter);
  const Voronoi facets = Voronoi::random(cells, seed);
  stroke::Random random(seed + 1);
  std::vector<double> shades(static_cast<size_t>(cells) * static_cast<size_t>(cells));
  for (double& shade : shades) {
    shade = 0.25 + 0.6 * random.nextUnit();
  }
  const double pixel = 1.0 / size;
  return fill(size, [&](double u, double v) {
    const Voronoi::Query query = facets.at(u, v);
    const double shade =
        shades[query.site % shades.size()] + 0.25 * cells * (query.offsetX - query.offsetY);
    return std::max(std::clamp(shade, 0.0, 1.0), line(query.edge, 0.02 / cells, pixel));
  });
}

ProceduralTexture foliage(int32_t size, uint64_t seed, double parameter) {
  const int32_t count = std::clamp(static_cast<int32_t>(parameter), 1, 32);
  stroke::Random random(seed);
  struct Leaf {
    double x, y, angle, length, width;
  };
  std::vector<Leaf> leaves;
  for (int32_t index = 0; index < count; ++index) {
    const double distance = count == 1 ? 0 : 0.35 * random.nextUnit();
    const double around = 2 * kPi * random.nextUnit();
    leaves.push_back({distance * std::cos(around), distance * std::sin(around),
                      2 * kPi * random.nextUnit(), 0.35 + 0.3 * random.nextUnit(),
                      0.1 + 0.08 * random.nextUnit()});
  }
  const double pixel = 2.0 / size;
  return tip(size, [&](double x, double y) {
    double value = 0;
    for (const Leaf& leaf : leaves) {
      const double dx = x - leaf.x;
      const double dy = y - leaf.y;
      const double along = (dx * std::cos(leaf.angle) + dy * std::sin(leaf.angle)) / leaf.length;
      const double across = (-dx * std::sin(leaf.angle) + dy * std::cos(leaf.angle));
      if (along < 0 || along > 1) {
        continue;
      }
      const double halfWidth = leaf.width * std::sin(kPi * along);
      const double body = line(std::abs(across), halfWidth, pixel);
      const double rib = line(std::abs(across), 0.006, pixel);
      value = std::max(value, body * (1 - 0.5 * rib));
    }
    return value;
  });
}

ProceduralTexture bokeh(int32_t size, double parameter) {
  const double rim = std::clamp(parameter, 0.0, 1.0);
  return tip(size, [&](double x, double y) {
    const double distance = std::hypot(x, y);
    const double disc = 1 - smoothstep(0.8, 0.86, distance);
    return disc * (0.55 + 0.45 * rim * smoothstep(0.55, 0.82, distance));
  });
}

ProceduralTexture fire(int32_t size, uint64_t seed, double parameter) {
  const double turbulence = std::clamp(parameter, 0.0, 1.0);
  stroke::Random random(seed);
  const Turbulence flicker(6, random);
  return tip(size, [&](double x, double y) {
    const double t = std::clamp((0.8 - y) / 1.7, 0.0, 1.0);
    const double sway = turbulence * 0.25 * (flicker.at(0.3, (y + 1) / 2) - 0.5) * t;
    if (y > 0.8) {
      return 0.0;
    }
    const double halfWidth =
        0.45 * std::sqrt(1 - t) * (1 - turbulence * 0.4 * flicker.at((x + 1) / 2, (y + 1) / 2));
    const double body = 1 - smoothstep(halfWidth * 0.6, halfWidth, std::abs(x - sway));
    return body * (0.5 + 0.5 * (1 - t));
  });
}

ProceduralTexture cloud(int32_t size, uint64_t seed, double roughness) {
  const double rough = std::clamp(roughness, 0.0, 1.0);
  stroke::Random random(seed);
  const Turbulence puffs(4, random);
  return tip(size, [&](double x, double y) {
    const double distance = std::hypot(x, y);
    const double billow = puffs.at((x + 1) / 2, (y + 1) / 2);
    const double radius = 0.85 - rough * 0.4 * (1 - billow);
    return (1 - smoothstep(radius * 0.4, radius, distance)) * (0.6 + 0.4 * billow);
  });
}

}  // namespace

std::optional<ProceduralTexture> generateTexture(const std::string& kind, int32_t size,
                                                 uint64_t seed, double parameter,
                                                 std::string& error) {
  if (size < 1 || size > kMaximumSize) {
    error = "size must be 1–4096";
    return std::nullopt;
  }
  if (kind == "disc") {
    return disc(size, parameter);
  }
  if (kind == "bristles") {
    return bristles(size, seed, parameter);
  }
  if (kind == "paper") {
    return paper(size, seed, parameter);
  }
  if (kind == "noise") {
    return noise(size, seed, parameter);
  }
  if (kind == "chalk") {
    return chalk(size, seed, parameter);
  }
  if (kind == "grid") {
    return grid(size, parameter);
  }
  if (kind == "stripes") {
    return stripes(size, parameter);
  }
  if (kind == "dots") {
    return dots(size, parameter);
  }
  if (kind == "hexagons") {
    const int32_t cells = repeats(parameter);
    return voronoiEdges(size, Voronoi::hexagonal(cells), 0.05 / cells);
  }
  if (kind == "cells") {
    const int32_t cells = repeats(parameter);
    return voronoiEdges(size, Voronoi::random(cells, seed), 0.04 / cells);
  }
  if (kind == "polygons") {
    return polygons(size, seed, parameter);
  }
  if (kind == "cubes") {
    return cubes(size, parameter);
  }
  if (kind == "triangles") {
    return triangles(size, parameter);
  }
  if (kind == "bricks") {
    return bricks(size, seed, parameter);
  }
  if (kind == "wood") {
    return wood(size, seed, parameter);
  }
  if (kind == "marble") {
    return marble(size, seed, parameter);
  }
  if (kind == "waves") {
    return waves(size, parameter);
  }
  if (kind == "rosette") {
    return rosette(size, parameter);
  }
  if (kind == "ornament") {
    return ornament(size, parameter);
  }
  if (kind == "spatter") {
    return spatter(size, seed, parameter);
  }
  if (kind == "blotch") {
    return blotch(size, seed, parameter);
  }
  if (kind == "sponge") {
    return sponge(size, seed, parameter);
  }
  if (kind == "star") {
    return star(size, seed, parameter);
  }
  if (kind == "drip") {
    return drip(size, parameter);
  }
  if (kind == "stick") {
    return stick(size, parameter);
  }
  if (kind == "streak") {
    return streak(size, seed, parameter);
  }
  if (kind == "metal") {
    return metal(size, seed, parameter);
  }
  if (kind == "concrete") {
    return concrete(size, seed, parameter);
  }
  if (kind == "fiber") {
    return fiber(size, seed, parameter);
  }
  if (kind == "minerals") {
    return minerals(size, seed, parameter);
  }
  if (kind == "foliage") {
    return foliage(size, seed, parameter);
  }
  if (kind == "bokeh") {
    return bokeh(size, parameter);
  }
  if (kind == "fire") {
    return fire(size, seed, parameter);
  }
  if (kind == "cloud") {
    return cloud(size, seed, parameter);
  }
  error = "unknown texture kind: " + kind;
  return std::nullopt;
}

}  // namespace nanquim::reference
