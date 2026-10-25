#include "io/tile_codec.h"

#include <lz4.h>

#include <limits>

namespace nanquim::io {

std::vector<uint8_t> compressTile(const uint8_t* pixels, size_t byteCount) {
  if (byteCount > static_cast<size_t>(LZ4_MAX_INPUT_SIZE)) {
    return {};
  }
  const int sourceSize = static_cast<int>(byteCount);
  std::vector<uint8_t> compressed(static_cast<size_t>(LZ4_compressBound(sourceSize)));
  const int compressedSize = LZ4_compress_default(reinterpret_cast<const char*>(pixels),
                                                  reinterpret_cast<char*>(compressed.data()),
                                                  sourceSize, static_cast<int>(compressed.size()));
  compressed.resize(compressedSize > 0 ? static_cast<size_t>(compressedSize) : 0);
  compressed.shrink_to_fit();
  return compressed;
}

bool decompressTile(const std::vector<uint8_t>& compressed, uint8_t* pixels, size_t byteCount) {
  if (compressed.empty() ||
      compressed.size() > static_cast<size_t>(std::numeric_limits<int>::max()) ||
      byteCount > static_cast<size_t>(std::numeric_limits<int>::max())) {
    return false;
  }
  const int decodedSize = LZ4_decompress_safe(
      reinterpret_cast<const char*>(compressed.data()), reinterpret_cast<char*>(pixels),
      static_cast<int>(compressed.size()), static_cast<int>(byteCount));
  return decodedSize == static_cast<int>(byteCount);
}

}  // namespace nanquim::io
