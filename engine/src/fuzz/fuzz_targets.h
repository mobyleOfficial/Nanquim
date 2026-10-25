#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace nanquim::fuzz {

struct FuzzTarget {
  const char* name;
  void (*run)(const uint8_t* data, size_t size);
};

void fuzzZipReader(const uint8_t* data, size_t size);
void fuzzPsdReader(const uint8_t* data, size_t size);
void fuzzTiffReader(const uint8_t* data, size_t size);
void fuzzProcreateReader(const uint8_t* data, size_t size);
void fuzzBinaryPlist(const uint8_t* data, size_t size);
void fuzzPaletteReader(const uint8_t* data, size_t size);
void fuzzIccProfile(const uint8_t* data, size_t size);

const std::vector<FuzzTarget>& fuzzTargets();

}  // namespace nanquim::fuzz
