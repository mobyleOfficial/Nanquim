#include "fuzz_targets.h"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
  nanquim::fuzz::NANQUIM_FUZZ_TARGET(data, size);
  return 0;
}
