#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "formats/document_import.h"
#include "formats/psd_writer.h"

namespace nanquim::formats {

struct PdfExportOptions {
  bool jpeg = false;
  int32_t jpegQuality = 90;
  std::string title;
  std::vector<uint8_t> iccProfile;
  const CmykOutput* cmyk = nullptr;
};

ExportResult exportPdf(int32_t width, int32_t height, double dpi,
                       const std::vector<CompositeSource*>& pages, const PdfExportOptions& options,
                       const std::string& outputPath);

std::vector<std::unique_ptr<CompositeSource>> layerPages(const ImportedDocument& document,
                                                         const std::string& documentFolder);

}  // namespace nanquim::formats
