#pragma once

#include <miniz.h>

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "formats/source_reader.h"

namespace nanquim::formats::testing {

struct PsdTestChannel {
  int16_t id = 0;
  std::vector<uint8_t> samples;
};

struct PsdTestLayer {
  int32_t top = 0;
  int32_t left = 0;
  int32_t bottom = 0;
  int32_t right = 0;
  std::vector<PsdTestChannel> channels;
  std::string blendKey = "norm";
  uint8_t opacity = 255;
  uint8_t clipping = 0;
  uint8_t flags = 0;
  std::string name;
  std::optional<std::u16string> unicodeName;
  std::optional<uint32_t> sectionType;
  std::optional<std::string> sectionBlendKey;
  std::optional<uint8_t> fillOpacity;
  std::vector<std::string> extraKeys;
  bool hasMask = false;
  int32_t maskTop = 0;
  int32_t maskLeft = 0;
  int32_t maskBottom = 0;
  int32_t maskRight = 0;
  uint8_t maskDefault = 0;
  uint8_t maskFlags = 0;
};

struct PsdTestFile {
  uint16_t version = 1;
  uint16_t channels = 3;
  uint32_t width = 0;
  uint32_t height = 0;
  uint16_t depth = 8;
  uint16_t colorMode = 3;
  uint16_t compression = 1;
  std::vector<PsdTestLayer> layers;
  bool negativeLayerCount = false;
  bool layersInLr16 = false;
  std::vector<std::vector<uint8_t>> merged;
  std::optional<double> dpi;
};

class Writer {
 public:
  void u8(uint32_t value) { bytes.push_back(static_cast<uint8_t>(value)); }
  void u16(uint32_t value) {
    u8(value >> 8U);
    u8(value);
  }
  void u32(uint32_t value) {
    u16(value >> 16U);
    u16(value);
  }
  void u64(uint64_t value) {
    u32(static_cast<uint32_t>(value >> 32U));
    u32(static_cast<uint32_t>(value));
  }
  void text(const std::string& value) { bytes.insert(bytes.end(), value.begin(), value.end()); }
  void append(const std::vector<uint8_t>& value) {
    bytes.insert(bytes.end(), value.begin(), value.end());
  }
  void patch32(size_t offset, uint32_t value) {
    for (int index = 0; index < 4; ++index) {
      bytes[offset + static_cast<size_t>(index)] =
          static_cast<uint8_t>(value >> (24U - 8U * static_cast<uint32_t>(index)));
    }
  }
  std::vector<uint8_t> bytes;
};

inline std::vector<uint8_t> encodeChannel(const std::vector<uint8_t>& samples, int32_t width,
                                          int32_t height, int32_t bytesPerSample,
                                          uint16_t compression, bool psb) {
  Writer writer;
  writer.u16(compression);
  const size_t rowBytes = static_cast<size_t>(width) * static_cast<size_t>(bytesPerSample);
  if (compression == 0) {
    writer.append(samples);
  } else if (compression == 1) {
    std::vector<std::vector<uint8_t>> rows(static_cast<size_t>(height));
    for (int32_t row = 0; row < height; ++row) {
      encodePackBits(samples.data() + static_cast<size_t>(row) * rowBytes, rowBytes,
                     rows[static_cast<size_t>(row)]);
    }
    for (const auto& row : rows) {
      psb ? writer.u32(static_cast<uint32_t>(row.size()))
          : writer.u16(static_cast<uint32_t>(row.size()));
    }
    for (const auto& row : rows) {
      writer.append(row);
    }
  } else {
    std::vector<uint8_t> data = samples;
    if (compression == 3) {
      for (int32_t row = 0; row < height; ++row) {
        uint8_t* line = data.data() + static_cast<size_t>(row) * rowBytes;
        if (bytesPerSample == 1) {
          for (int32_t x = width - 1; x > 0; --x) {
            line[x] = static_cast<uint8_t>(line[x] - line[x - 1]);
          }
        } else {
          for (int32_t x = width - 1; x > 0; --x) {
            const auto value = static_cast<uint16_t>((line[x * 2] << 8U) | line[x * 2 + 1]);
            const auto previous = static_cast<uint16_t>((line[x * 2 - 2] << 8U) | line[x * 2 - 1]);
            const auto delta = static_cast<uint16_t>(value - previous);
            line[x * 2] = static_cast<uint8_t>(delta >> 8U);
            line[x * 2 + 1] = static_cast<uint8_t>(delta & 0xFFU);
          }
        }
      }
    }
    mz_ulong size = mz_compressBound(static_cast<mz_ulong>(data.size()));
    std::vector<uint8_t> compressed(size);
    mz_compress(compressed.data(), &size, data.data(), static_cast<mz_ulong>(data.size()));
    compressed.resize(size);
    writer.append(compressed);
  }
  return writer.bytes;
}

inline std::vector<uint8_t> buildPsd(const PsdTestFile& file) {
  const bool psb = file.version == 2;
  const int32_t bytesPerSample = file.depth / 8;
  Writer writer;
  writer.text("8BPS");
  writer.u16(file.version);
  writer.u16(0);
  writer.u32(0);
  writer.u16(file.channels);
  writer.u32(file.height);
  writer.u32(file.width);
  writer.u16(file.depth);
  writer.u16(file.colorMode);
  writer.u32(0);

  Writer resources;
  if (file.dpi) {
    resources.text("8BIM");
    resources.u16(1005);
    resources.u16(0);
    resources.u32(16);
    resources.u32(static_cast<uint32_t>(*file.dpi * 65536));
    resources.u16(1);
    resources.u16(1);
    resources.u32(static_cast<uint32_t>(*file.dpi * 65536));
    resources.u16(1);
    resources.u16(1);
  }
  writer.u32(static_cast<uint32_t>(resources.bytes.size()));
  writer.append(resources.bytes);

  Writer info;
  if (!file.layers.empty()) {
    const auto count = static_cast<int16_t>(file.layers.size());
    info.u16(static_cast<uint16_t>(file.negativeLayerCount ? -count : count));
    std::vector<std::vector<std::vector<uint8_t>>> encoded;
    for (const PsdTestLayer& layer : file.layers) {
      std::vector<std::vector<uint8_t>> channels;
      for (const PsdTestChannel& channel : layer.channels) {
        const bool mask = channel.id == -2;
        const int32_t width = mask ? layer.maskRight - layer.maskLeft : layer.right - layer.left;
        const int32_t height = mask ? layer.maskBottom - layer.maskTop : layer.bottom - layer.top;
        channels.push_back(
            encodeChannel(channel.samples, width, height, bytesPerSample, file.compression, psb));
      }
      encoded.push_back(channels);
      info.u32(static_cast<uint32_t>(layer.top));
      info.u32(static_cast<uint32_t>(layer.left));
      info.u32(static_cast<uint32_t>(layer.bottom));
      info.u32(static_cast<uint32_t>(layer.right));
      info.u16(static_cast<uint32_t>(layer.channels.size()));
      for (size_t index = 0; index < layer.channels.size(); ++index) {
        info.u16(static_cast<uint16_t>(layer.channels[index].id));
        psb ? info.u64(channels[index].size())
            : info.u32(static_cast<uint32_t>(channels[index].size()));
      }
      info.text("8BIM");
      info.text(layer.blendKey);
      info.u8(layer.opacity);
      info.u8(layer.clipping);
      info.u8(layer.flags);
      info.u8(0);
      Writer extra;
      if (layer.hasMask) {
        extra.u32(20);
        extra.u32(static_cast<uint32_t>(layer.maskTop));
        extra.u32(static_cast<uint32_t>(layer.maskLeft));
        extra.u32(static_cast<uint32_t>(layer.maskBottom));
        extra.u32(static_cast<uint32_t>(layer.maskRight));
        extra.u8(layer.maskDefault);
        extra.u8(layer.maskFlags);
        extra.u16(0);
      } else {
        extra.u32(0);
      }
      extra.u32(0);
      extra.u8(static_cast<uint32_t>(layer.name.size()));
      extra.text(layer.name);
      for (size_t pad = 0; pad < (4 - (1 + layer.name.size()) % 4) % 4; ++pad) {
        extra.u8(0);
      }
      auto block = [&extra](const std::string& key, const std::vector<uint8_t>& data) {
        extra.text("8BIM");
        extra.text(key);
        extra.u32(static_cast<uint32_t>(data.size()));
        extra.append(data);
      };
      if (layer.unicodeName) {
        Writer data;
        data.u32(static_cast<uint32_t>(layer.unicodeName->size()));
        for (const char16_t unit : *layer.unicodeName) {
          data.u16(unit);
        }
        block("luni", data.bytes);
      }
      if (layer.sectionType) {
        Writer data;
        data.u32(*layer.sectionType);
        if (layer.sectionBlendKey) {
          data.text("8BIM");
          data.text(*layer.sectionBlendKey);
        }
        block("lsct", data.bytes);
      }
      if (layer.fillOpacity) {
        block("iOpa", {*layer.fillOpacity, 0, 0, 0});
      }
      for (const std::string& key : layer.extraKeys) {
        block(key, {0, 0, 0, 0});
      }
      info.u32(static_cast<uint32_t>(extra.bytes.size()));
      info.append(extra.bytes);
    }
    for (const auto& channels : encoded) {
      for (const auto& channel : channels) {
        info.append(channel);
      }
    }
  }

  Writer section;
  if (file.layersInLr16) {
    psb ? section.u64(0) : section.u32(0);
    section.u32(0);
    section.text("8BIM");
    section.text("Lr16");
    psb ? section.u64(info.bytes.size()) : section.u32(static_cast<uint32_t>(info.bytes.size()));
    section.append(info.bytes);
  } else if (!info.bytes.empty()) {
    psb ? section.u64(info.bytes.size()) : section.u32(static_cast<uint32_t>(info.bytes.size()));
    section.append(info.bytes);
    section.u32(0);
  }
  psb ? writer.u64(section.bytes.size()) : writer.u32(static_cast<uint32_t>(section.bytes.size()));
  writer.append(section.bytes);

  const uint16_t mergedCompression = file.compression == 0 ? 0 : 1;
  writer.u16(mergedCompression);
  std::vector<std::vector<uint8_t>> planes = file.merged;
  const size_t planeBytes =
      static_cast<size_t>(file.width) * file.height * static_cast<size_t>(bytesPerSample);
  planes.resize(file.channels);
  for (auto& plane : planes) {
    plane.resize(planeBytes, 0);
  }
  if (mergedCompression == 0) {
    for (const auto& plane : planes) {
      writer.append(plane);
    }
  } else {
    const size_t rowBytes = static_cast<size_t>(file.width) * static_cast<size_t>(bytesPerSample);
    std::vector<std::vector<uint8_t>> rows;
    for (const auto& plane : planes) {
      for (uint32_t row = 0; row < file.height; ++row) {
        rows.emplace_back();
        encodePackBits(plane.data() + row * rowBytes, rowBytes, rows.back());
      }
    }
    for (const auto& row : rows) {
      psb ? writer.u32(static_cast<uint32_t>(row.size()))
          : writer.u16(static_cast<uint32_t>(row.size()));
    }
    for (const auto& row : rows) {
      writer.append(row);
    }
  }
  return writer.bytes;
}

}  // namespace nanquim::formats::testing
