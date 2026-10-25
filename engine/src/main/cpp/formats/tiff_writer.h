#pragma once

#include <cstdint>
#include <string>

#include "formats/psd_writer.h"

namespace nanquim::formats {

ExportResult exportTiff(int32_t width, int32_t height, double dpi, CompositeSource& composite,
                        const std::string& outputPath, bool deep = false,
                        const std::vector<uint8_t>& iccProfile = {},
                        const CmykOutput* cmyk = nullptr);

}  // namespace nanquim::formats
