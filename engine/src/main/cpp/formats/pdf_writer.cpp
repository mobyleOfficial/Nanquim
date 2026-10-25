#include "formats/pdf_writer.h"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>
#include <utility>

#include "formats/icc_profile.h"
#include "formats/output_file.h"
#include "io/document_tile.h"
#include "io/file_system.h"

#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include <stb_image_write.h>

namespace nanquim::formats {

namespace {

using canvas::kTileSizePixels;

constexpr int32_t kBandRows = 64;
constexpr size_t kDeflateChunkBytes = size_t{64} * 1024;

std::string format(const char* pattern, double value) {
  char text[64];
  std::snprintf(text, sizeof(text), pattern, value);
  return text;
}

std::string pdfTextString(const std::string& utf8) {
  std::string hex = "<FEFF";
  size_t index = 0;
  const auto appendUnit = [&hex](uint32_t unit) {
    char text[8];
    std::snprintf(text, sizeof(text), "%04X", unit);
    hex += text;
  };
  while (index < utf8.size()) {
    const auto lead = static_cast<uint8_t>(utf8[index]);
    size_t length = lead < 0x80            ? 1
                    : (lead >> 5U) == 0x6  ? 2
                    : (lead >> 4U) == 0xE  ? 3
                    : (lead >> 3U) == 0x1E ? 4
                                           : 0;
    uint32_t codePoint = 0xFFFD;
    if (length == 1) {
      codePoint = lead;
    } else if (length > 1 && index + length <= utf8.size()) {
      codePoint = lead & (0x7FU >> length);
      for (size_t next = 1; next < length; ++next) {
        codePoint = (codePoint << 6U) | (static_cast<uint8_t>(utf8[index + next]) & 0x3FU);
      }
      if (codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
        codePoint = 0xFFFD;
      }
    } else {
      length = 1;
    }
    index += length;
    if (codePoint >= 0x10000) {
      codePoint -= 0x10000;
      appendUnit(0xD800U + (codePoint >> 10U));
      appendUnit(0xDC00U + (codePoint & 0x3FFU));
    } else {
      appendUnit(codePoint);
    }
  }
  return hex + ">";
}

class PdfWriter {
 public:
  PdfWriter(int32_t width, int32_t height, double dpi, PdfExportOptions options)
      : width_(width), height_(height), dpi_(dpi), options_(std::move(options)) {}

  ExportResult run(const std::vector<CompositeSource*>& pages, const std::string& outputPath);

 private:
  bool fail(const char* code, const std::string& detail = {}) {
    result_.ok = false;
    result_.errorCode = code;
    result_.errorDetail = detail;
    return false;
  }

  uint32_t newObject() {
    offsets_.push_back(0);
    return static_cast<uint32_t>(offsets_.size() - 1);
  }
  void beginObject(uint32_t object) {
    offsets_[object] = out_->position();
    out_->text(std::to_string(object) + " 0 obj\n");
  }
  void endObject() { out_->text("endobj\n"); }
  uint32_t beginStream(uint32_t object, const std::string& dictionary) {
    const uint32_t length = newObject();
    beginObject(object);
    out_->text("<< " + dictionary + " /Length " + std::to_string(length) + " 0 R >>\nstream\n");
    streamBegin_ = out_->position();
    return length;
  }
  void endStream(uint32_t lengthObject) {
    const uint64_t length = out_->position() - streamBegin_;
    out_->text("\nendstream\n");
    endObject();
    beginObject(lengthObject);
    out_->text(std::to_string(length) + "\n");
    endObject();
  }

  bool writePage(CompositeSource& page, uint32_t pagesObject, std::vector<uint32_t>& kids);
  bool writeFlateSamples(CompositeSource& page, bool alpha, bool& opaque);
  bool writeJpeg(CompositeSource& page);

  int32_t width_;
  int32_t height_;
  double dpi_;
  PdfExportOptions options_;
  ExportResult result_;
  std::unique_ptr<OutputFile> out_;
  std::vector<uint64_t> offsets_{0};
  uint64_t streamBegin_ = 0;
  uint32_t iccObject_ = 0;
  std::vector<uint8_t> band_;
  std::vector<uint8_t> samples_;
  std::vector<uint8_t> inks_;
};

bool PdfWriter::writeFlateSamples(CompositeSource& page, bool alpha, bool& opaque) {
  mz_stream stream{};
  if (mz_deflateInit(&stream, MZ_DEFAULT_LEVEL) != MZ_OK) {
    return fail("writeFailed", "deflate");
  }
  const color::CmykTransform* cmyk = options_.cmyk != nullptr ? options_.cmyk->transform : nullptr;
  const size_t components = alpha ? 1 : cmyk != nullptr ? 4 : 3;
  const size_t rowSamples = static_cast<size_t>(width_) * components;
  std::vector<uint8_t> compressed(kDeflateChunkBytes);
  band_.resize(static_cast<size_t>(width_) * kBandRows * 4);
  samples_.resize(rowSamples * kBandRows);
  opaque = true;
  bool ok = true;
  for (int32_t top = 0; top < height_ && ok; top += kBandRows) {
    const int32_t rows = std::min(kBandRows, height_ - top);
    if (!page.readRows(top, rows, band_.data())) {
      ok = fail("compositeFailed");
      break;
    }
    if (!alpha && cmyk != nullptr) {
      inks_.resize(static_cast<size_t>(width_) * kBandRows * 5);
      cmyk->convert(band_.data(), inks_.data(),
                    static_cast<size_t>(width_) * static_cast<size_t>(rows));
    }
    for (int32_t row = 0; row < rows; ++row) {
      const uint8_t* source = band_.data() + static_cast<size_t>(row) * width_ * 4;
      uint8_t* destination = samples_.data() + static_cast<size_t>(row) * rowSamples;
      for (int32_t x = 0; x < width_; ++x) {
        if (!alpha && cmyk != nullptr) {
          std::memcpy(destination + static_cast<size_t>(x) * 4,
                      inks_.data() + (static_cast<size_t>(row) * width_ + x) * 5, 4);
        } else if (alpha) {
          destination[x] = source[x * 4 + 3];
          opaque = opaque && source[x * 4 + 3] == 255;
        } else {
          std::memcpy(destination + static_cast<size_t>(x) * 3, source + static_cast<size_t>(x) * 4,
                      3);
        }
      }
      for (size_t index = rowSamples; index-- > components;) {
        destination[index] =
            static_cast<uint8_t>(destination[index] - destination[index - components]);
      }
    }
    const bool last = top + rows >= height_;
    stream.next_in = samples_.data();
    stream.avail_in = static_cast<unsigned int>(rowSamples * static_cast<size_t>(rows));
    int status = MZ_OK;
    do {
      stream.next_out = compressed.data();
      stream.avail_out = static_cast<unsigned int>(compressed.size());
      status = mz_deflate(&stream, last ? MZ_FINISH : MZ_NO_FLUSH);
      if (status != MZ_OK && status != MZ_STREAM_END && status != MZ_BUF_ERROR) {
        ok = fail("writeFailed", "deflate");
        break;
      }
      out_->bytes(compressed.data(), compressed.size() - stream.avail_out);
    } while (stream.avail_in > 0 || (last && status != MZ_STREAM_END));
  }
  mz_deflateEnd(&stream);
  return ok;
}

bool PdfWriter::writeJpeg(CompositeSource& page) {
  std::vector<uint8_t> rgb(static_cast<size_t>(width_) * static_cast<size_t>(height_) * 3);
  band_.resize(static_cast<size_t>(width_) * kBandRows * 4);
  for (int32_t top = 0; top < height_; top += kBandRows) {
    const int32_t rows = std::min(kBandRows, height_ - top);
    if (!page.readRows(top, rows, band_.data())) {
      return fail("compositeFailed");
    }
    const size_t pixels = static_cast<size_t>(width_) * static_cast<size_t>(rows);
    uint8_t* destination = rgb.data() + static_cast<size_t>(top) * width_ * 3;
    for (size_t pixel = 0; pixel < pixels; ++pixel) {
      std::memcpy(destination + pixel * 3, band_.data() + pixel * 4, 3);
    }
  }
  const auto write = [](void* context, void* data, int size) {
    static_cast<OutputFile*>(context)->bytes(data, static_cast<size_t>(size));
  };
  if (stbi_write_jpg_to_func(write, out_.get(), width_, height_, 3, rgb.data(),
                             std::clamp(options_.jpegQuality, 1, 100)) == 0) {
    return fail("writeFailed", "jpeg");
  }
  return true;
}

bool PdfWriter::writePage(CompositeSource& page, uint32_t pagesObject,
                          std::vector<uint32_t>& kids) {
  const std::string size =
      " /Width " + std::to_string(width_) + " /Height " + std::to_string(height_);
  const uint32_t mask = newObject();
  const uint32_t maskLength = beginStream(
      mask, "/Type /XObject /Subtype /Image" + size +
                " /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /DecodeParms "
                "<< /Predictor 2 /Colors 1 /BitsPerComponent 8 /Columns " +
                std::to_string(width_) + " >>");
  bool opaque = true;
  if (!writeFlateSamples(page, true, opaque)) {
    return false;
  }
  endStream(maskLength);

  const uint32_t image = newObject();
  const bool cmyk = options_.cmyk != nullptr && options_.cmyk->transform != nullptr;
  const bool jpeg = options_.jpeg && !cmyk;
  std::string dictionary = "/Type /XObject /Subtype /Image" + size + " /ColorSpace [/ICCBased " +
                           std::to_string(iccObject_) + " 0 R] /BitsPerComponent 8";
  if (jpeg) {
    dictionary += " /Filter /DCTDecode";
  } else {
    dictionary += std::string(" /Filter /FlateDecode /DecodeParms << /Predictor 2 /Colors ") +
                  (cmyk ? "4" : "3") + " /BitsPerComponent 8 /Columns " + std::to_string(width_) +
                  " >>";
  }
  if (!opaque) {
    dictionary += " /SMask " + std::to_string(mask) + " 0 R";
  }
  const uint32_t imageLength = beginStream(image, dictionary);
  bool ignored = true;
  if (!(jpeg ? writeJpeg(page) : writeFlateSamples(page, false, ignored))) {
    return false;
  }
  endStream(imageLength);

  const std::string pageWidth = format("%.4f", width_ * 72.0 / dpi_);
  const std::string pageHeight = format("%.4f", height_ * 72.0 / dpi_);
  const std::string content = "q\n" + pageWidth + " 0 0 " + pageHeight + " 0 0 cm\n/Im0 Do\nQ\n";
  const uint32_t contents = newObject();
  beginObject(contents);
  out_->text("<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content +
             "\nendstream\n");
  endObject();

  const uint32_t pageObject = newObject();
  beginObject(pageObject);
  out_->text("<< /Type /Page /Parent " + std::to_string(pagesObject) + " 0 R /MediaBox [0 0 " +
             pageWidth + " " + pageHeight + "] /Resources << /XObject << /Im0 " +
             std::to_string(image) + " 0 R >> >> /Contents " + std::to_string(contents) +
             " 0 R >>\n");
  endObject();
  kids.push_back(pageObject);
  return true;
}

ExportResult PdfWriter::run(const std::vector<CompositeSource*>& pages,
                            const std::string& outputPath) {
  if (width_ <= 0 || height_ <= 0 || pages.empty() || !(dpi_ >= 1 && dpi_ <= 100000)) {
    fail("invalidDocument");
    return result_;
  }
  out_ = OutputFile::create(outputPath);
  if (!out_) {
    fail("writeFailed", "create");
    return result_;
  }
  out_->text("%PDF-1.4\n%");
  out_->bytes(std::vector<uint8_t>{0xE2, 0xE3, 0xCF, 0xD3, '\n'});
  const uint32_t catalog = newObject();
  const uint32_t pagesObject = newObject();
  const uint32_t info = newObject();
  iccObject_ = newObject();

  const bool cmyk = options_.cmyk != nullptr && options_.cmyk->transform != nullptr;
  const std::vector<uint8_t> profile = cmyk                          ? options_.cmyk->profile
                                       : options_.iccProfile.empty() ? srgbIccProfile()
                                                                     : options_.iccProfile;
  beginObject(iccObject_);
  out_->text(
      std::string(cmyk ? "<< /N 4 /Alternate /DeviceCMYK" : "<< /N 3 /Alternate /DeviceRGB") +
      " /Length " + std::to_string(profile.size()) + " >>\nstream\n");
  out_->bytes(profile);
  out_->text("\nendstream\n");
  endObject();

  std::vector<uint32_t> kids;
  for (CompositeSource* page : pages) {
    if (page == nullptr || !writePage(*page, pagesObject, kids)) {
      if (result_.errorCode.empty()) {
        fail("invalidDocument", "page");
      }
      return result_;
    }
  }
  beginObject(pagesObject);
  std::string kidList;
  for (const uint32_t kid : kids) {
    kidList += std::to_string(kid) + " 0 R ";
  }
  out_->text("<< /Type /Pages /Kids [" + kidList + "] /Count " + std::to_string(kids.size()) +
             " >>\n");
  endObject();
  beginObject(catalog);
  out_->text("<< /Type /Catalog /Pages " + std::to_string(pagesObject) + " 0 R >>\n");
  endObject();
  beginObject(info);
  out_->text("<< /Producer " + pdfTextString("Nanquim") +
             (options_.title.empty() ? "" : " /Title " + pdfTextString(options_.title)) + " >>\n");
  endObject();

  const uint64_t xref = out_->position();
  out_->text("xref\n0 " + std::to_string(offsets_.size()) + "\n0000000000 65535 f \n");
  for (size_t object = 1; object < offsets_.size(); ++object) {
    char line[32];
    std::snprintf(line, sizeof(line), "%010llu 00000 n \n",
                  static_cast<unsigned long long>(offsets_[object]));
    out_->text(line);
  }
  out_->text("trailer\n<< /Size " + std::to_string(offsets_.size()) + " /Root " +
             std::to_string(catalog) + " 0 R /Info " + std::to_string(info) +
             " 0 R >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n");
  if (!out_->close()) {
    fail("writeFailed");
    return result_;
  }
  result_.ok = true;
  return result_;
}

using TileSet = std::set<std::pair<int32_t, int32_t>>;

TileSet tilesOf(const std::string& surfaceFolder) {
  bool rebuilt = false;
  TileSet tiles;
  for (const canvas::TileCoordinate coordinate :
       io::readSurfaceIndex(surfaceFolder, rebuilt).tiles) {
    tiles.emplace(coordinate.column, coordinate.row);
  }
  return tiles;
}

class LayerPage final : public CompositeSource {
 public:
  LayerPage(const ImportedDocument& document, const ImportedNode& layer,
            const std::string& documentFolder)
      : width_(document.width),
        height_(document.height),
        opacity_(std::clamp(layer.opacity, 0.0, 1.0)),
        layerFolder_(io::joinPath(io::joinPath(documentFolder, "layers"), layer.uuid)),
        layerTiles_(tilesOf(layerFolder_)),
        columns_((document.width + kTileSizePixels - 1) / kTileSizePixels),
        layerBand_(static_cast<size_t>(columns_) * kTileSizePixels * kTileSizePixels * 4),
        maskBand_(static_cast<size_t>(columns_) * kTileSizePixels * kTileSizePixels),
        tile_(canvas::kTileBytesRgba8) {
    if (document.backgroundColor && !document.backgroundHidden) {
      background_ = *document.backgroundColor;
    }
    if (layer.mask && layer.mask->enabled) {
      maskFolder_ = io::joinPath(io::joinPath(documentFolder, "masks"), layer.mask->uuid);
      maskTiles_ = tilesOf(maskFolder_);
      maskInverted_ = layer.mask->inverted;
    }
  }

  bool readRows(int32_t top, int32_t count, uint8_t* rgba) override {
    for (int32_t y = top; y < top + count; ++y) {
      if (y < 0 || y >= height_) {
        return false;
      }
      if (y / kTileSizePixels != loadedRow_) {
        load(y / kTileSizePixels);
      }
      const auto bandRow = static_cast<size_t>(y % kTileSizePixels);
      uint8_t* out = rgba + static_cast<size_t>(y - top) * width_ * 4;
      for (int32_t x = 0; x < width_; ++x, out += 4) {
        const size_t index =
            bandRow * static_cast<size_t>(columns_) * kTileSizePixels + static_cast<size_t>(x);
        const uint8_t* pixel = layerBand_.data() + index * 4;
        const double coverage = opacity_ * maskBand_[index] / 255.0;
        const double alpha = pixel[3] / 255.0 * coverage;
        const double below = background_[3] * (1.0 - alpha);
        const double total = alpha + below;
        for (size_t channel = 0; channel < 3; ++channel) {
          const double premultiplied =
              pixel[channel] / 255.0 * coverage + background_[channel] * below;
          const double straight = total > 0 ? premultiplied / total : 0;
          out[channel] = static_cast<uint8_t>(std::lround(std::clamp(straight, 0.0, 1.0) * 255));
        }
        out[3] = static_cast<uint8_t>(std::lround(std::clamp(total, 0.0, 1.0) * 255));
      }
    }
    return true;
  }

 private:
  void load(int32_t tileRow) {
    std::fill(layerBand_.begin(), layerBand_.end(), 0);
    std::fill(maskBand_.begin(), maskBand_.end(), 255);
    const size_t rowPixels = static_cast<size_t>(columns_) * kTileSizePixels;
    for (int32_t column = 0; column < columns_; ++column) {
      const size_t left = static_cast<size_t>(column) * kTileSizePixels;
      if (layerTiles_.count({column, tileRow}) > 0 &&
          io::readDocumentTile(layerFolder_, {column, tileRow}, io::DocumentTileFormat::Rgba8,
                               tile_.data()) == io::TileReadResult::Ok) {
        for (int32_t row = 0; row < kTileSizePixels; ++row) {
          std::memcpy(layerBand_.data() + (static_cast<size_t>(row) * rowPixels + left) * 4,
                      tile_.data() + static_cast<size_t>(row) * kTileSizePixels * 4,
                      static_cast<size_t>(kTileSizePixels) * 4);
        }
      }
      if (maskTiles_.count({column, tileRow}) > 0 &&
          io::readDocumentTile(maskFolder_, {column, tileRow}, io::DocumentTileFormat::R8,
                               tile_.data()) == io::TileReadResult::Ok) {
        for (int32_t row = 0; row < kTileSizePixels; ++row) {
          for (int32_t x = 0; x < kTileSizePixels; ++x) {
            maskBand_[static_cast<size_t>(row) * rowPixels + left + static_cast<size_t>(x)] =
                tile_[(static_cast<size_t>(row) * kTileSizePixels + static_cast<size_t>(x)) * 4];
          }
        }
      }
    }
    if (maskInverted_) {
      for (uint8_t& value : maskBand_) {
        value = static_cast<uint8_t>(255 - value);
      }
    }
    loadedRow_ = tileRow;
  }

  int32_t width_;
  int32_t height_;
  double opacity_;
  std::array<double, 4> background_{0, 0, 0, 0};
  std::string layerFolder_;
  TileSet layerTiles_;
  std::string maskFolder_;
  TileSet maskTiles_;
  bool maskInverted_ = false;
  int32_t columns_;
  int32_t loadedRow_ = -1;
  std::vector<uint8_t> layerBand_;
  std::vector<uint8_t> maskBand_;
  std::vector<uint8_t> tile_;
};

void collectLayers(const std::vector<ImportedNode>& nodes, std::vector<const ImportedNode*>& out) {
  for (const ImportedNode& node : nodes) {
    if (node.type == ImportedNode::Type::Group) {
      collectLayers(node.children, out);
    } else {
      out.push_back(&node);
    }
  }
}

}  // namespace

ExportResult exportPdf(int32_t width, int32_t height, double dpi,
                       const std::vector<CompositeSource*>& pages, const PdfExportOptions& options,
                       const std::string& outputPath) {
  return PdfWriter(width, height, dpi, options).run(pages, outputPath);
}

std::vector<std::unique_ptr<CompositeSource>> layerPages(const ImportedDocument& document,
                                                         const std::string& documentFolder) {
  std::vector<const ImportedNode*> layers;
  collectLayers(document.layers, layers);
  std::vector<std::unique_ptr<CompositeSource>> pages;
  for (const ImportedNode* layer : layers) {
    pages.push_back(std::make_unique<LayerPage>(document, *layer, documentFolder));
  }
  return pages;
}

}  // namespace nanquim::formats
