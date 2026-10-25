#pragma once

#include <string>

#include "formats/document_import.h"
#include "formats/input_source.h"

namespace nanquim::formats {

ImportResult importTiff(InputSource& source, const std::string& folder, const ImportLimits& limits);

}  // namespace nanquim::formats
