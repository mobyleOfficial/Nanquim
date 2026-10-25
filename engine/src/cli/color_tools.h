#pragma once

#include <string>
#include <vector>

namespace nanquim::cli {

int runColorTool(const std::vector<std::string>& args);

void printColorToolUsage();

}  // namespace nanquim::cli
