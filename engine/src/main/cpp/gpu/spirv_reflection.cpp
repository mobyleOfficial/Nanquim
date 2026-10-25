#include "gpu/spirv_reflection.h"

#include <algorithm>
#include <map>
#include <optional>

namespace nanquim::gpu {
namespace {

constexpr uint32_t kMagic = 0x07230203;
constexpr uint32_t kHeaderWords = 5;
constexpr uint32_t kOpName = 5;
constexpr uint32_t kOpTypeInt = 21;
constexpr uint32_t kOpTypeImage = 25;
constexpr uint32_t kOpTypeSampledImage = 27;
constexpr uint32_t kOpTypeArray = 28;
constexpr uint32_t kOpTypeRuntimeArray = 29;
constexpr uint32_t kOpTypeStruct = 30;
constexpr uint32_t kOpTypePointer = 32;
constexpr uint32_t kOpConstant = 43;
constexpr uint32_t kOpVariable = 59;
constexpr uint32_t kOpDecorate = 71;
constexpr uint32_t kDecorationBlock = 2;
constexpr uint32_t kDecorationBufferBlock = 3;
constexpr uint32_t kDecorationBinding = 33;
constexpr uint32_t kDecorationDescriptorSet = 34;
constexpr uint32_t kStorageUniformConstant = 0;
constexpr uint32_t kStorageUniform = 2;
constexpr uint32_t kStorageStorageBuffer = 12;
constexpr uint32_t kDim2D = 1;
constexpr uint32_t kDim3D = 2;

struct Type {
  uint32_t opcode = 0;
  std::vector<uint32_t> operands;
};

struct Decorations {
  std::optional<uint32_t> set;
  std::optional<uint32_t> binding;
  bool block = false;
  bool bufferBlock = false;
};

std::string literalString(const std::vector<uint32_t>& words, size_t first, size_t end) {
  std::string text;
  for (size_t word = first; word < end; ++word) {
    for (uint32_t byte = 0; byte < 4; ++byte) {
      const auto character = static_cast<char>((words[word] >> (byte * 8U)) & 0xFFU);
      if (character == '\0') {
        return text;
      }
      text += character;
    }
  }
  return text;
}

}  // namespace

bool reflectSpirv(const std::vector<uint32_t>& words, std::vector<SpirvResource>& resources,
                  std::string& errorMessage) {
  resources.clear();
  if (words.size() < kHeaderWords || words[0] != kMagic) {
    errorMessage = "not a SPIR-V module";
    return false;
  }
  std::map<uint32_t, Type> types;
  std::map<uint32_t, Decorations> decorations;
  std::map<uint32_t, std::string> names;
  std::map<uint32_t, uint32_t> constants;
  struct Variable {
    uint32_t id;
    uint32_t pointerType;
    uint32_t storageClass;
  };
  std::vector<Variable> variables;
  for (size_t at = kHeaderWords; at < words.size();) {
    const uint32_t wordCount = words[at] >> 16U;
    const uint32_t opcode = words[at] & 0xFFFFU;
    if (wordCount == 0 || at + wordCount > words.size()) {
      errorMessage = "truncated SPIR-V instruction";
      return false;
    }
    const auto operand = [&](uint32_t index) { return words[at + 1 + index]; };
    switch (opcode) {
      case kOpName:
        if (wordCount >= 3) {
          names[operand(0)] = literalString(words, at + 2, at + wordCount);
        }
        break;
      case kOpDecorate:
        if (wordCount >= 3) {
          Decorations& target = decorations[operand(0)];
          const uint32_t decoration = operand(1);
          if (decoration == kDecorationBinding && wordCount >= 4) {
            target.binding = operand(2);
          } else if (decoration == kDecorationDescriptorSet && wordCount >= 4) {
            target.set = operand(2);
          } else if (decoration == kDecorationBlock) {
            target.block = true;
          } else if (decoration == kDecorationBufferBlock) {
            target.bufferBlock = true;
          }
        }
        break;
      case kOpTypeInt:
      case kOpTypeImage:
      case kOpTypeSampledImage:
      case kOpTypeArray:
      case kOpTypeRuntimeArray:
      case kOpTypeStruct:
      case kOpTypePointer:
        if (wordCount >= 2) {
          Type& type = types[operand(0)];
          type.opcode = opcode;
          type.operands.assign(words.begin() + static_cast<std::ptrdiff_t>(at + 2),
                               words.begin() + static_cast<std::ptrdiff_t>(at + wordCount));
        }
        break;
      case kOpConstant:
        if (wordCount == 4) {
          constants[operand(1)] = operand(2);
        }
        break;
      case kOpVariable:
        if (wordCount >= 4) {
          variables.push_back({operand(1), operand(0), operand(2)});
        }
        break;
      default:
        break;
    }
    at += wordCount;
  }

  const auto typeOf = [&](uint32_t id) -> const Type* {
    const auto found = types.find(id);
    return found != types.end() ? &found->second : nullptr;
  };
  for (const Variable& variable : variables) {
    const auto decorated = decorations.find(variable.id);
    const std::optional<uint32_t> binding =
        decorated != decorations.end() ? decorated->second.binding : std::nullopt;
    if (!binding) {
      continue;
    }
    const Type* pointer = typeOf(variable.pointerType);
    if (pointer == nullptr || pointer->opcode != kOpTypePointer || pointer->operands.size() < 2) {
      continue;
    }
    uint32_t pointeeId = pointer->operands[1];
    const Type* pointee = typeOf(pointeeId);
    uint32_t count = 1;
    while (pointee != nullptr &&
           (pointee->opcode == kOpTypeArray || pointee->opcode == kOpTypeRuntimeArray)) {
      if (pointee->opcode == kOpTypeArray && pointee->operands.size() >= 2) {
        const auto length = constants.find(pointee->operands[1]);
        count *= length != constants.end() ? length->second : 1;
      }
      pointeeId = pointee->operands.at(0);
      pointee = typeOf(pointeeId);
    }
    if (pointee == nullptr) {
      continue;
    }
    SpirvResource resource;
    resource.count = count;
    resource.set = decorated->second.set.value_or(0);
    resource.binding = binding.value_or(0);
    const auto name = names.find(variable.id);
    if (name != names.end()) {
      resource.name = name->second;
    }
    const Decorations& structDecorations = decorations[pointeeId];
    if (pointee->opcode == kOpTypeStruct) {
      if (variable.storageClass == kStorageStorageBuffer || structDecorations.bufferBlock) {
        resource.kind = SpirvResource::Kind::StorageBuffer;
      } else if (variable.storageClass == kStorageUniform && structDecorations.block) {
        resource.kind = SpirvResource::Kind::UniformBuffer;
      } else {
        continue;
      }
    } else if (variable.storageClass == kStorageUniformConstant &&
               (pointee->opcode == kOpTypeSampledImage || pointee->opcode == kOpTypeImage)) {
      const Type* image =
          pointee->opcode == kOpTypeSampledImage ? typeOf(pointee->operands.at(0)) : pointee;
      if (image == nullptr || image->opcode != kOpTypeImage || image->operands.size() < 7) {
        continue;
      }
      resource.kind = pointee->opcode == kOpTypeSampledImage ? SpirvResource::Kind::SampledImage
                      : image->operands[5] == 2              ? SpirvResource::Kind::StorageImage
                                                             : SpirvResource::Kind::SampledImage;
      const uint32_t dim = image->operands[1];
      resource.dimension = dim == kDim2D   ? SpirvResource::Dimension::Texture2D
                           : dim == kDim3D ? SpirvResource::Dimension::Texture3D
                                           : SpirvResource::Dimension::Other;
      const Type* sampled = typeOf(image->operands[0]);
      if (sampled != nullptr && sampled->opcode == kOpTypeInt && sampled->operands.size() >= 2) {
        resource.sampledType = sampled->operands[1] != 0 ? SpirvResource::SampledType::Int
                                                         : SpirvResource::SampledType::UInt;
      }
    } else {
      continue;
    }
    resources.push_back(resource);
  }
  std::sort(resources.begin(), resources.end(), [](const SpirvResource& a, const SpirvResource& b) {
    return a.set != b.set ? a.set < b.set : a.binding < b.binding;
  });
  return true;
}

}  // namespace nanquim::gpu
