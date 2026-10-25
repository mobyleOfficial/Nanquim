#pragma once

#include <string>
#include <vector>

namespace nanquim::cli {

int runInterchangeTool(const std::vector<std::string>& args);

void printInterchangeToolUsage();

}  // namespace nanquim::cli
