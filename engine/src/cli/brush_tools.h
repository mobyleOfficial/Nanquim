#pragma once

#include <string>
#include <vector>

namespace nanquim::cli {

int runBrushTool(const std::vector<std::string>& args);

void printBrushToolUsage();

}  // namespace nanquim::cli
