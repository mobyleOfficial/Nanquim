#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "formats/psd_writer.h"

namespace nanquim::formats {

ExportResult exportPng(int32_t width, int32_t height, double dpi, CompositeSource& composite,
                       const std::string& outputPath, bool deep = false,
                       const std::vector<uint8_t>& iccProfile = {});

}  // namespace nanquim::formats
