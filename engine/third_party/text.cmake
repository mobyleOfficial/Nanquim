# Text engine dependencies (spec 12, decision D-152): HarfBuzz, FreeType, SheenBidi. Included by
# the Android and the host CMake files with THIRD_PARTY_DIRECTORY set.
set(HARFBUZZ_DIRECTORY ${THIRD_PARTY_DIRECTORY}/harfbuzz)
add_library(harfbuzz STATIC ${HARFBUZZ_DIRECTORY}/src/harfbuzz.cc)
# Only the OpenType shaper and its own font functions: no FreeType, ICU, GLib or platform shapers.
target_compile_definitions(harfbuzz PUBLIC HB_NO_MT HB_NO_PRAGMA_GCC_DIAGNOSTIC_ERROR)
target_compile_options(harfbuzz PRIVATE -w -fno-exceptions -fno-rtti)
target_include_directories(harfbuzz SYSTEM PUBLIC ${HARFBUZZ_DIRECTORY}/src)
if(MINGW)
  target_compile_options(harfbuzz PRIVATE -Wa,-mbig-obj)  # More sections than COFF allows.
endif()

set(FREETYPE_DIRECTORY ${THIRD_PARTY_DIRECTORY}/freetype)
add_library(freetype STATIC
            ${FREETYPE_DIRECTORY}/src/base/ftsystem.c ${FREETYPE_DIRECTORY}/src/base/ftinit.c
            ${FREETYPE_DIRECTORY}/src/base/ftdebug.c ${FREETYPE_DIRECTORY}/src/base/ftbase.c
            ${FREETYPE_DIRECTORY}/src/base/ftbbox.c ${FREETYPE_DIRECTORY}/src/base/ftglyph.c
            ${FREETYPE_DIRECTORY}/src/base/ftstroke.c ${FREETYPE_DIRECTORY}/src/base/ftbitmap.c
            ${FREETYPE_DIRECTORY}/src/base/ftfstype.c ${FREETYPE_DIRECTORY}/src/base/ftmm.c
            ${FREETYPE_DIRECTORY}/src/sfnt/sfnt.c ${FREETYPE_DIRECTORY}/src/truetype/truetype.c
            ${FREETYPE_DIRECTORY}/src/cff/cff.c ${FREETYPE_DIRECTORY}/src/psaux/psaux.c
            ${FREETYPE_DIRECTORY}/src/pshinter/pshinter.c ${FREETYPE_DIRECTORY}/src/psnames/psnames.c
            ${FREETYPE_DIRECTORY}/src/smooth/smooth.c)
target_compile_definitions(freetype PRIVATE FT2_BUILD_LIBRARY)
target_compile_definitions(freetype PUBLIC "FT_CONFIG_OPTIONS_H=<nanquim_ftoption.h>"
                                           "FT_CONFIG_MODULES_H=<nanquim_ftmodule.h>")
target_compile_options(freetype PRIVATE -w)
target_include_directories(freetype SYSTEM PUBLIC ${FREETYPE_DIRECTORY}/include
                                                  ${THIRD_PARTY_DIRECTORY}/freetype-config)

set(SHEENBIDI_DIRECTORY ${THIRD_PARTY_DIRECTORY}/sheenbidi)
add_library(sheenbidi STATIC ${SHEENBIDI_DIRECTORY}/Source/SheenBidi.c)
target_compile_definitions(sheenbidi PRIVATE SB_CONFIG_UNITY)
target_compile_options(sheenbidi PRIVATE -w)
target_include_directories(sheenbidi SYSTEM PUBLIC ${SHEENBIDI_DIRECTORY}/Headers)
target_include_directories(sheenbidi PRIVATE ${SHEENBIDI_DIRECTORY}/Source)
