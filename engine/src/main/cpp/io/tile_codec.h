#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace nanquim::io {

std::vector<uint8_t> compressTile(const uint8_t* pixels, size_t byteCount);

bool decompressTile(const std::vector<uint8_t>& compressed, uint8_t* pixels, size_t byteCount);

}  // namespace nanquim::io
