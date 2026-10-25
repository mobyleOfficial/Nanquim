#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "formats/document_import.h"
#include "formats/input_source.h"

namespace nanquim::formats {

ImportResult importPsd(InputSource& source, const std::string& folder, const ImportLimits& limits);

const std::vector<std::pair<std::string, std::string>>& psdBlendKeys();

std::string psdBlendModeId(const std::string& key);

}  // namespace nanquim::formats
