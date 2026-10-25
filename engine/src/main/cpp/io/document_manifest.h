#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "canvas/layer_tree.h"
#include "io/document_tile.h"

namespace nanquim::io {

struct DocumentSurface {
  canvas::LayerId id = 0;
  std::string uuid;
  std::string folder;
  DocumentTileFormat format = DocumentTileFormat::Rgba8;
};

struct DocumentManifest {
  int32_t width = 0;
  int32_t height = 0;
  canvas::LayerTree tree;
  std::vector<DocumentSurface> surfaces;
};

inline constexpr int64_t kMaximumManifestBytes = 64 * 1024 * 1024;
inline constexpr int32_t kSupportedManifestVersion = 1;

std::optional<DocumentManifest> parseDocumentManifest(std::string_view jsonText,
                                                      std::string& error);

std::string writeDocumentManifest(const canvas::LayerTree& tree, int32_t width, int32_t height,
                                  const std::vector<DocumentSurface>& surfaces,
                                  const std::string& documentName);

std::vector<DocumentSurface> surfacesOf(const canvas::LayerTree& tree);

}  // namespace nanquim::io
