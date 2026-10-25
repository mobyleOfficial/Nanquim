#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "canvas/layer_tree.h"
#include "reference_image.h"

namespace nanquim::reference {

bool writeDocumentFolder(const std::string& folder, const canvas::LayerTree& tree, int32_t width,
                         int32_t height, const std::map<canvas::LayerId, Image>& surfaces,
                         const std::string& name, std::string& error);

struct LoadedDocument {
  int32_t width = 0;
  int32_t height = 0;
  canvas::LayerTree tree;
  std::map<canvas::LayerId, Image> surfaces;
  std::vector<std::string> warnings;
};

std::optional<LoadedDocument> readDocumentFolder(const std::string& folder, std::string& error);

Image renderDocument(const LoadedDocument& document);

}  // namespace nanquim::reference
