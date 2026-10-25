/* Nanquim build options for FreeType (decision D-152): the stock options without zlib (no
 * compressed fonts or WOFF), and without the TrueType bytecode interpreter (glyphs are rendered
 * unhinted, at any transform). */
#include <freetype/config/ftoption.h>

#undef FT_CONFIG_OPTION_USE_ZLIB
#undef FT_CONFIG_OPTION_USE_BZIP2
#undef FT_CONFIG_OPTION_USE_PNG
#undef FT_CONFIG_OPTION_USE_HARFBUZZ
#undef FT_CONFIG_OPTION_USE_BROTLI
#undef TT_CONFIG_OPTION_BYTECODE_INTERPRETER
