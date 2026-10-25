#pragma once

#include <map>
#include <vector>

#include "reference_image.h"
#include "scene/scene.h"
#include "stroke/dab.h"

namespace nanquim::reference {

std::vector<stroke::Dab> generateDabs(const scene::StrokeAction& stroke);

std::map<canvas::LayerId, Image> renderSceneSurfaces(const scene::Scene& scene);

Image renderScene(const scene::Scene& scene);

}  // namespace nanquim::reference
