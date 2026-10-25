#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "png_file.h"
#include "stroke/loaded_brush.h"
#include "stroke_statistics.h"

namespace nanquim::reference {

struct TunableParameter {
  std::string path;
  double minimum = 0;
  double maximum = 1;
};

std::vector<TunableParameter> standardTunableParameters();

Rgba8Image renderTestSheet(const nlohmann::json& brushDocument, const stroke::LoadedBrush& textures,
                           double scale, double sizePixels = 0, double opacity = 0);

struct TuningResult {
  nlohmann::json brush;
  double distance = 0;
  double initialDistance = 0;
  int32_t evaluations = 0;
};

TuningResult tuneBrush(const nlohmann::json& brushDocument, const stroke::LoadedBrush& textures,
                       const std::vector<StrokeStatistic>& target,
                       const std::vector<TunableParameter>& parameters, int32_t maximumEvaluations,
                       double scale, const std::function<void(int32_t, double)>& progress = {});

std::vector<double> minimizeNelderMead(const std::function<double(const std::vector<double>&)>& f,
                                       std::vector<double> start, int32_t maximumEvaluations,
                                       double initialStep, int32_t* evaluations = nullptr);

}  // namespace nanquim::reference
