#pragma once

#include <string>
#include <string_view>

namespace nanquim::gpu {

std::string withEmbeddedIncludes(std::string_view source);

}  // namespace nanquim::gpu
