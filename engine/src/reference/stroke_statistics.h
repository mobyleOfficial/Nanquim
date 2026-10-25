#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "png_file.h"

namespace nanquim::reference {

struct StrokeStatistic {
  std::string name;
  double value = 0;
  double tolerance = 1;
};

std::vector<StrokeStatistic> measureSheet(const Rgba8Image& image);

struct StatisticsComparison {
  double distance = 0;
  struct Entry {
    std::string name;
    double target = 0;
    double actual = 0;
    double normalizedDifference = 0;
  };
  std::vector<Entry> entries;
};

StatisticsComparison compareStatistics(const std::vector<StrokeStatistic>& target,
                                       const std::vector<StrokeStatistic>& actual);

inline constexpr double kAcceptanceDistance = 1.0;

}  // namespace nanquim::reference
