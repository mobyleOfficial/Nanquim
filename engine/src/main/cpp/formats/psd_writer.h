#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "color/color_profile.h"
#include "formats/document_import.h"

namespace nanquim::formats {

class CompositeSource {
 public:
  virtual ~CompositeSource() = default;
  virtual bool readRows(int32_t top, int32_t count, uint8_t* rgba) = 0;
  [[nodiscard]] virtual bool hasDeepRows() const { return false; }
  virtual bool readDeepRows(int32_t /*top*/, int32_t /*count*/, uint16_t* /*rgba*/) {
    return false;
  }
};

struct CmykOutput {
  const color::CmykTransform* transform = nullptr;
  std::vector<uint8_t> profile;
};

struct PsdExportOptions {
  std::string backgroundLayerName = "Background";
  std::vector<uint8_t> iccProfile;
  bool deep = false;
  const CmykOutput* cmyk = nullptr;
};

struct ExportResult {
  bool ok = false;
  std::string errorCode;
  std::string errorDetail;
  std::vector<ImportWarning> warnings;
};

ExportResult exportPsd(const ImportedDocument& document, const std::string& documentFolder,
                       CompositeSource& composite, const PsdExportOptions& options,
                       const std::string& outputPath);

std::string psdBlendKey(const std::string& blendId);

}  // namespace nanquim::formats
