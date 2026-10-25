#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "formats/document_import.h"
#include "formats/input_source.h"

namespace nanquim::formats {

ImportResult importProcreate(InputSource& source, const std::string& folder,
                             const ImportLimits& limits);

ImportResult importProcreateComposite(InputSource& source, const std::string& folder,
                                      const ImportLimits& limits);

bool decodeProcreateTile(const uint8_t* data, size_t size, uint8_t* output, size_t outputSize);

}  // namespace nanquim::formats
