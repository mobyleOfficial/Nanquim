cmake_minimum_required(VERSION 3.22)
project(nanquim_engine_host_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.17.0.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()

option(NANQUIM_FUZZ "Build the libFuzzer harnesses (clang)" OFF)
if(NANQUIM_FUZZ)
  if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(FATAL_ERROR "NANQUIM_FUZZ needs clang (libFuzzer)")
  endif()
  add_compile_options(-fsanitize=fuzzer-no-link,address,undefined -fno-sanitize-recover=undefined)
  add_link_options(-fsanitize=address,undefined)
endif()

set(ENGINE_SOURCE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
set(REFERENCE_SOURCE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../reference)
set(CLI_SOURCE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../cli)
set(FUZZ_SOURCE_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../fuzz)
set(THIRD_PARTY_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party)
set(GOLDENS_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../goldens)

add_library(lz4 STATIC ${THIRD_PARTY_DIRECTORY}/lz4/lz4.c ${THIRD_PARTY_DIRECTORY}/lz4/lz4hc.c
            ${THIRD_PARTY_DIRECTORY}/lz4/lz4frame.c ${THIRD_PARTY_DIRECTORY}/lz4/xxhash.c)
target_include_directories(lz4 SYSTEM PUBLIC ${THIRD_PARTY_DIRECTORY}/lz4)
add_library(miniz STATIC ${THIRD_PARTY_DIRECTORY}/miniz/miniz.c)
target_compile_definitions(miniz PUBLIC MINIZ_NO_STDIO MINIZ_NO_TIME MINIZ_NO_ZLIB_COMPATIBLE_NAMES)
target_include_directories(miniz SYSTEM PUBLIC ${THIRD_PARTY_DIRECTORY}/miniz)
set(LCMS2_DIRECTORY ${THIRD_PARTY_DIRECTORY}/lcms2)
file(GLOB LCMS2_SOURCES ${LCMS2_DIRECTORY}/src/*.c)
add_library(lcms2 STATIC ${LCMS2_SOURCES})
target_include_directories(lcms2 SYSTEM PUBLIC ${LCMS2_DIRECTORY}/include)
target_include_directories(lcms2 PRIVATE ${LCMS2_DIRECTORY}/src)
if(NANQUIM_FUZZ)
  target_compile_options(lcms2 PRIVATE -fno-sanitize=function)
endif()
include(${THIRD_PARTY_DIRECTORY}/text.cmake)
add_library(third_party_headers INTERFACE)
target_include_directories(third_party_headers SYSTEM INTERFACE
    ${THIRD_PARTY_DIRECTORY}/stb
    ${THIRD_PARTY_DIRECTORY}/nlohmann_json/include)

add_library(engine_portable STATIC
    ${ENGINE_SOURCE_DIRECTORY}/adjust/adjustment.cpp
    ${ENGINE_SOURCE_DIRECTORY}/adjust/spatial.cpp
    ${ENGINE_SOURCE_DIRECTORY}/adjust/liquify.cpp
    ${ENGINE_SOURCE_DIRECTORY}/canvas/composite_plan.cpp
    ${ENGINE_SOURCE_DIRECTORY}/canvas/flood_fill.cpp
    ${ENGINE_SOURCE_DIRECTORY}/canvas/layer_tree.cpp
    ${ENGINE_SOURCE_DIRECTORY}/canvas/polygon_coverage.cpp
    ${ENGINE_SOURCE_DIRECTORY}/canvas/transform_mesh.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/binary_plist.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/document_import.cpp
    ${ENGINE_SOURCE_DIRECTORY}/color/color_profile.cpp
    ${ENGINE_SOURCE_DIRECTORY}/color/display_lut.cpp
    ${ENGINE_SOURCE_DIRECTORY}/gpu/shader_source.cpp
    ${ENGINE_SOURCE_DIRECTORY}/gpu/spirv_reflection.cpp
    ${ENGINE_SOURCE_DIRECTORY}/gpu/uniform_layout.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/icc_profile.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/input_source.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/output_file.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/palette_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/pdf_writer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/procreate_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/psd_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/psd_writer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/source_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/tiff_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/animation_writer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/color_quantizer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/png_encoding.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/png_writer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/tiff_writer.cpp
    ${ENGINE_SOURCE_DIRECTORY}/formats/zip_reader.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/document_manifest.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/document_tile.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/document_transaction.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/file_system.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/scratch_file.cpp
    ${ENGINE_SOURCE_DIRECTORY}/io/tile_codec.cpp
    ${ENGINE_SOURCE_DIRECTORY}/scene/scene.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/auto_repeat.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/brush_json.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/brush_texture.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/catmull_rom.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/color_dynamics.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/curve.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/dab_generator.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/input_sample.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/loaded_brush.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/paint_mixing.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/particles.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/smoothing.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/stroke_effects.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/symmetry.cpp
    ${ENGINE_SOURCE_DIRECTORY}/stroke/tip_coverage.cpp
    ${ENGINE_SOURCE_DIRECTORY}/text/font_face.cpp
    ${ENGINE_SOURCE_DIRECTORY}/text/layout.cpp
    ${ENGINE_SOURCE_DIRECTORY}/text/shaper.cpp
    ${ENGINE_SOURCE_DIRECTORY}/text/text_model.cpp
    ${ENGINE_SOURCE_DIRECTORY}/text/text_raster.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/blend_reference.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/brush_pad.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/brush_tuning.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/png_file.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/procedural_texture.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/reference_compositor.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/reference_document.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/reference_image.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/reference_renderer.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/reference_scene.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/stroke_statistics.cpp
    ${REFERENCE_SOURCE_DIRECTORY}/test_sheet.cpp
)
target_include_directories(engine_portable PUBLIC ${ENGINE_SOURCE_DIRECTORY} ${REFERENCE_SOURCE_DIRECTORY})
target_compile_options(engine_portable PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(engine_portable PUBLIC lz4 lcms2 miniz harfbuzz freetype sheenbidi third_party_headers)

add_executable(nanquim-cli ${CLI_SOURCE_DIRECTORY}/nanquim_cli.cpp ${CLI_SOURCE_DIRECTORY}/brush_tools.cpp
               ${CLI_SOURCE_DIRECTORY}/interchange_tools.cpp ${CLI_SOURCE_DIRECTORY}/color_tools.cpp)
target_include_directories(nanquim-cli PRIVATE ${CLI_SOURCE_DIRECTORY})
target_compile_options(nanquim-cli PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(nanquim-cli PRIVATE engine_portable)

add_library(fuzz_targets STATIC ${FUZZ_SOURCE_DIRECTORY}/fuzz_targets.cpp)
target_include_directories(fuzz_targets PUBLIC ${FUZZ_SOURCE_DIRECTORY})
target_compile_options(fuzz_targets PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(fuzz_targets PUBLIC engine_portable)

add_executable(engine_core_tests
    adjust/adjustment_test.cpp
    adjust/spatial_test.cpp
    adjust/liquify_test.cpp
    canvas/composite_plan_test.cpp
    color/cmyk_export_test.cpp
    color/color_profile_test.cpp
    color/display_lut_test.cpp
    canvas/flood_fill_test.cpp
    canvas/layer_tree_test.cpp
    canvas/polygon_coverage_test.cpp
    canvas/transform_mesh_test.cpp
    canvas/level_selection_test.cpp
    canvas/tile_coordinate_test.cpp
    core/affine_transform_test.cpp
    core/half_float_test.cpp
    core/json_writer_test.cpp
    core/lru_tracker_test.cpp
    core/task_queue_test.cpp
    core/worker_pool_test.cpp
    formats/byte_reader_test.cpp
    formats/document_import_test.cpp
    formats/palette_reader_test.cpp
    formats/palette_fixtures_test.cpp
    formats/pdf_writer_test.cpp
    formats/fuzz_corpus_test.cpp
    formats/procreate_reader_test.cpp
    formats/psd_reader_test.cpp
    formats/animation_writer_test.cpp
    formats/png_writer_test.cpp
    formats/psd_writer_test.cpp
    formats/tiff_test.cpp
    formats/zip_reader_test.cpp
    gpu/shader_source_test.cpp
    gpu/shader_spirv_test.cpp
    gpu/uniform_layout_test.cpp
    io/document_tile_test.cpp
    io/document_transaction_test.cpp
    io/scratch_file_test.cpp
    io/tile_codec_test.cpp
    reference/banding_test.cpp
    reference/blend_reference_test.cpp
    reference/brush_pad_test.cpp
    reference/procedural_texture_test.cpp
    reference/brush_tuning_test.cpp
    reference/reference_compositor_test.cpp
    reference/reference_document_test.cpp
    reference/reference_renderer_test.cpp
    scene/scene_test.cpp
    stroke/auto_repeat_test.cpp
    stroke/brush_json_test.cpp
    stroke/brush_texture_test.cpp
    stroke/catmull_rom_test.cpp
    stroke/color_dynamics_test.cpp
    stroke/curve_test.cpp
    stroke/dab_generator_test.cpp
    stroke/input_sample_test.cpp
    stroke/paint_mixing_test.cpp
    stroke/particles_test.cpp
    stroke/random_test.cpp
    stroke/smoothing_test.cpp
    stroke/stroke_effects_test.cpp
    stroke/symmetry_test.cpp
    stroke/tip_coverage_test.cpp
    text/layout_test.cpp
    text/shaper_test.cpp
    text/text_raster_test.cpp
)
target_include_directories(engine_core_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(engine_core_tests PRIVATE -Wall -Wextra -Wpedantic)
target_compile_definitions(engine_core_tests PRIVATE
    NANQUIM_GOLDENS_DIRECTORY="${GOLDENS_DIRECTORY}"
    NANQUIM_PROFILES_DIRECTORY="${CMAKE_CURRENT_SOURCE_DIR}/../../../../assets-src/profiles"
    NANQUIM_SHADERS_DIRECTORY="${ENGINE_SOURCE_DIRECTORY}/../shaders"
    NANQUIM_FUZZ_CORPUS_DIRECTORY="${FUZZ_SOURCE_DIRECTORY}/corpus")
target_link_libraries(engine_core_tests PRIVATE GTest::gtest_main engine_portable fuzz_targets)
file(GLOB NDK_SHADER_TOOLS $ENV{ANDROID_NDK_HOME}/shader-tools/*
     $ENV{ANDROID_HOME}/ndk/*/shader-tools/* $ENV{ANDROID_SDK_ROOT}/ndk/*/shader-tools/*
     $ENV{LOCALAPPDATA}/Android/Sdk/ndk/*/shader-tools/* $ENV{HOME}/Library/Android/sdk/ndk/*/shader-tools/*
     $ENV{HOME}/Android/Sdk/ndk/*/shader-tools/*)
list(SORT NDK_SHADER_TOOLS ORDER DESCENDING)
find_program(NANQUIM_GLSLC glslc HINTS ${NANQUIM_SHADER_TOOLS} ${NDK_SHADER_TOOLS})
find_program(NANQUIM_SPIRV_DIS spirv-dis HINTS ${NANQUIM_SHADER_TOOLS} ${NDK_SHADER_TOOLS})
if(NANQUIM_GLSLC AND NANQUIM_SPIRV_DIS)
  target_compile_definitions(engine_core_tests PRIVATE NANQUIM_GLSLC="${NANQUIM_GLSLC}"
                             NANQUIM_SPIRV_DIS="${NANQUIM_SPIRV_DIS}")
else()
  message(STATUS "glslc or spirv-dis not found: the SPIR-V shader test is skipped")
endif()

if(NANQUIM_FUZZ)
  set(FUZZ_FLAGS -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=undefined)
  foreach(FUZZ_PAIR "zip:fuzzZipReader" "psd:fuzzPsdReader" "tiff:fuzzTiffReader"
          "procreate:fuzzProcreateReader" "plist:fuzzBinaryPlist" "palette:fuzzPaletteReader"
          "icc:fuzzIccProfile")
    string(REPLACE ":" ";" FUZZ_PARTS ${FUZZ_PAIR})
    list(GET FUZZ_PARTS 0 FUZZ_NAME)
    list(GET FUZZ_PARTS 1 FUZZ_FUNCTION)
    add_executable(fuzz_${FUZZ_NAME} ${FUZZ_SOURCE_DIRECTORY}/fuzzer_main.cpp
                   ${FUZZ_SOURCE_DIRECTORY}/fuzz_targets.cpp)
    target_include_directories(fuzz_${FUZZ_NAME} PRIVATE ${FUZZ_SOURCE_DIRECTORY})
    target_compile_definitions(fuzz_${FUZZ_NAME} PRIVATE NANQUIM_FUZZ_TARGET=${FUZZ_FUNCTION})
    target_compile_options(fuzz_${FUZZ_NAME} PRIVATE ${FUZZ_FLAGS})
    target_link_options(fuzz_${FUZZ_NAME} PRIVATE ${FUZZ_FLAGS})
    target_link_libraries(fuzz_${FUZZ_NAME} PRIVATE engine_portable)
  endforeach()
endif()

include(GoogleTest)
gtest_discover_tests(engine_core_tests)

file(GLOB GOLDEN_SCENES CONFIGURE_DEPENDS ${GOLDENS_DIRECTORY}/scenes/*.json)
foreach(SCENE_PATH ${GOLDEN_SCENES})
  get_filename_component(SCENE_NAME ${SCENE_PATH} NAME_WE)
  set(ACTUAL_PATH ${CMAKE_CURRENT_BINARY_DIR}/goldens/actual/${SCENE_NAME}.png)
  file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/goldens/actual)
  add_test(NAME golden.${SCENE_NAME}.render COMMAND nanquim-cli render ${SCENE_PATH} ${ACTUAL_PATH})
  add_test(NAME golden.${SCENE_NAME}.compare
           COMMAND nanquim-cli compare ${ACTUAL_PATH} ${GOLDENS_DIRECTORY}/expected/${SCENE_NAME}.png)
  set_tests_properties(golden.${SCENE_NAME}.compare PROPERTIES DEPENDS golden.${SCENE_NAME}.render)
  file(READ ${SCENE_PATH} SCENE_TEXT)
  string(FIND "${SCENE_TEXT}" "\"tint\"" TINT_POSITION)
  if(NOT TINT_POSITION EQUAL -1)
    continue()
  endif()
  set(DOCUMENT_PATH ${CMAKE_CURRENT_BINARY_DIR}/goldens/documents/${SCENE_NAME})
  set(DOCUMENT_RENDER_PATH ${CMAKE_CURRENT_BINARY_DIR}/goldens/actual/${SCENE_NAME}.document.png)
  add_test(NAME golden.${SCENE_NAME}.document.write
           COMMAND nanquim-cli convert ${SCENE_PATH} ${DOCUMENT_PATH})
  add_test(NAME golden.${SCENE_NAME}.document.render
           COMMAND nanquim-cli render ${DOCUMENT_PATH} ${DOCUMENT_RENDER_PATH})
  add_test(NAME golden.${SCENE_NAME}.document.compare
           COMMAND nanquim-cli compare ${DOCUMENT_RENDER_PATH}
                   ${GOLDENS_DIRECTORY}/expected/${SCENE_NAME}.png)
  set_tests_properties(golden.${SCENE_NAME}.document.render
                       PROPERTIES DEPENDS golden.${SCENE_NAME}.document.write)
  set_tests_properties(golden.${SCENE_NAME}.document.compare
                       PROPERTIES DEPENDS golden.${SCENE_NAME}.document.render)
endforeach()

set(FORMAT_FIXTURES_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../formats/src/test/resources/nanquim)
foreach(FIXTURE_PAIR "v1-layer-tree:layer_tree")
  string(REPLACE ":" ";" FIXTURE_PARTS ${FIXTURE_PAIR})
  list(GET FIXTURE_PARTS 0 FIXTURE_NAME)
  list(GET FIXTURE_PARTS 1 FIXTURE_GOLDEN)
  set(FIXTURE_RENDER_PATH ${CMAKE_CURRENT_BINARY_DIR}/goldens/actual/format_${FIXTURE_NAME}.png)
  add_test(NAME format.${FIXTURE_NAME}.render
           COMMAND nanquim-cli render ${FORMAT_FIXTURES_DIRECTORY}/${FIXTURE_NAME} ${FIXTURE_RENDER_PATH})
  add_test(NAME format.${FIXTURE_NAME}.compare
           COMMAND nanquim-cli compare ${FIXTURE_RENDER_PATH}
                   ${GOLDENS_DIRECTORY}/expected/${FIXTURE_GOLDEN}.png)
  set_tests_properties(format.${FIXTURE_NAME}.compare PROPERTIES DEPENDS format.${FIXTURE_NAME}.render)
endforeach()

set(PSD_ROUND_TRIP_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/psd-round-trip)
file(GLOB PSD_SEEDS CONFIGURE_DEPENDS ${FUZZ_SOURCE_DIRECTORY}/corpus/psd/*.psd
     ${FUZZ_SOURCE_DIRECTORY}/corpus/psd/*.psb)
list(FILTER PSD_SEEDS EXCLUDE REGEX "regression-")
foreach(PSD_PATH ${PSD_SEEDS})
  get_filename_component(PSD_NAME ${PSD_PATH} NAME_WE)
  get_filename_component(PSD_EXTENSION ${PSD_PATH} EXT)
  set(PSD_PREFIX ${PSD_ROUND_TRIP_DIRECTORY}/${PSD_NAME})
  file(MAKE_DIRECTORY ${PSD_ROUND_TRIP_DIRECTORY})
  add_test(NAME psd.${PSD_NAME}.import COMMAND nanquim-cli import-psd ${PSD_PATH} ${PSD_PREFIX}.first)
  add_test(NAME psd.${PSD_NAME}.render
           COMMAND nanquim-cli render ${PSD_PREFIX}.first ${PSD_PREFIX}.first.png)
  add_test(NAME psd.${PSD_NAME}.export
           COMMAND nanquim-cli export-psd ${PSD_PREFIX}.first ${PSD_PREFIX}.out${PSD_EXTENSION})
  add_test(NAME psd.${PSD_NAME}.reimport
           COMMAND nanquim-cli import-psd ${PSD_PREFIX}.out${PSD_EXTENSION} ${PSD_PREFIX}.second)
  add_test(NAME psd.${PSD_NAME}.rerender
           COMMAND nanquim-cli render ${PSD_PREFIX}.second ${PSD_PREFIX}.second.png)
  add_test(NAME psd.${PSD_NAME}.compare
           COMMAND nanquim-cli compare ${PSD_PREFIX}.second.png ${PSD_PREFIX}.first.png)
  set_tests_properties(psd.${PSD_NAME}.render psd.${PSD_NAME}.export
                       PROPERTIES DEPENDS psd.${PSD_NAME}.import)
  set_tests_properties(psd.${PSD_NAME}.reimport PROPERTIES DEPENDS psd.${PSD_NAME}.export)
  set_tests_properties(psd.${PSD_NAME}.rerender PROPERTIES DEPENDS psd.${PSD_NAME}.reimport)
  set_tests_properties(psd.${PSD_NAME}.compare
                       PROPERTIES DEPENDS "psd.${PSD_NAME}.render;psd.${PSD_NAME}.rerender")
endforeach()

set(TIFF_ROUND_TRIP_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/tiff-round-trip)
file(GLOB TIFF_SEEDS CONFIGURE_DEPENDS ${FUZZ_SOURCE_DIRECTORY}/corpus/tiff/*.tif)
list(FILTER TIFF_SEEDS EXCLUDE REGEX "regression-")
foreach(TIFF_PATH ${TIFF_SEEDS})
  get_filename_component(TIFF_NAME ${TIFF_PATH} NAME_WE)
  set(TIFF_PREFIX ${TIFF_ROUND_TRIP_DIRECTORY}/${TIFF_NAME})
  file(MAKE_DIRECTORY ${TIFF_ROUND_TRIP_DIRECTORY})
  add_test(NAME tiff.${TIFF_NAME}.import
           COMMAND nanquim-cli import-tiff ${TIFF_PATH} ${TIFF_PREFIX}.first)
  add_test(NAME tiff.${TIFF_NAME}.render
           COMMAND nanquim-cli render ${TIFF_PREFIX}.first ${TIFF_PREFIX}.first.png)
  add_test(NAME tiff.${TIFF_NAME}.export
           COMMAND nanquim-cli export-tiff ${TIFF_PREFIX}.first ${TIFF_PREFIX}.out.tif)
  add_test(NAME tiff.${TIFF_NAME}.reimport
           COMMAND nanquim-cli import-tiff ${TIFF_PREFIX}.out.tif ${TIFF_PREFIX}.second)
  add_test(NAME tiff.${TIFF_NAME}.rerender
           COMMAND nanquim-cli render ${TIFF_PREFIX}.second ${TIFF_PREFIX}.second.png)
  add_test(NAME tiff.${TIFF_NAME}.compare
           COMMAND nanquim-cli compare ${TIFF_PREFIX}.second.png ${TIFF_PREFIX}.first.png)
  set_tests_properties(tiff.${TIFF_NAME}.render tiff.${TIFF_NAME}.export
                       PROPERTIES DEPENDS tiff.${TIFF_NAME}.import)
  set_tests_properties(tiff.${TIFF_NAME}.reimport PROPERTIES DEPENDS tiff.${TIFF_NAME}.export)
  set_tests_properties(tiff.${TIFF_NAME}.rerender PROPERTIES DEPENDS tiff.${TIFF_NAME}.reimport)
  set_tests_properties(tiff.${TIFF_NAME}.compare
                       PROPERTIES DEPENDS "tiff.${TIFF_NAME}.render;tiff.${TIFF_NAME}.rerender")
  add_test(NAME tiff.${TIFF_NAME}.pdf
           COMMAND nanquim-cli export-pdf ${TIFF_PREFIX}.first ${TIFF_PREFIX}.pdf)
  add_test(NAME tiff.${TIFF_NAME}.pdf-layers
           COMMAND nanquim-cli export-pdf ${TIFF_PREFIX}.first ${TIFF_PREFIX}.layers.pdf layers jpeg)
  set_tests_properties(tiff.${TIFF_NAME}.pdf tiff.${TIFF_NAME}.pdf-layers
                       PROPERTIES DEPENDS tiff.${TIFF_NAME}.import)
endforeach()

set(PROCREATE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/procreate)
file(MAKE_DIRECTORY ${PROCREATE_DIRECTORY})
file(GLOB PROCREATE_SEEDS CONFIGURE_DEPENDS ${FUZZ_SOURCE_DIRECTORY}/corpus/procreate/*.procreate)
list(FILTER PROCREATE_SEEDS EXCLUDE REGEX "regression-")
foreach(PROCREATE_PATH ${PROCREATE_SEEDS})
  get_filename_component(PROCREATE_NAME ${PROCREATE_PATH} NAME_WE)
  add_test(NAME procreate.seed.${PROCREATE_NAME}.import
           COMMAND nanquim-cli import-procreate ${PROCREATE_PATH} ${PROCREATE_DIRECTORY}/seed-${PROCREATE_NAME})
  add_test(NAME procreate.seed.${PROCREATE_NAME}.render
           COMMAND nanquim-cli render ${PROCREATE_DIRECTORY}/seed-${PROCREATE_NAME}
                   ${PROCREATE_DIRECTORY}/seed-${PROCREATE_NAME}.png)
  set_tests_properties(procreate.seed.${PROCREATE_NAME}.render
                       PROPERTIES DEPENDS procreate.seed.${PROCREATE_NAME}.import)
endforeach()
set(PROCREATE_LOCAL_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../formats/src/test/resources/procreate)
file(GLOB PROCREATE_LOCAL CONFIGURE_DEPENDS ${PROCREATE_LOCAL_DIRECTORY}/*.procreate)
foreach(PROCREATE_PATH ${PROCREATE_LOCAL})
  get_filename_component(PROCREATE_NAME ${PROCREATE_PATH} NAME_WE)
  set(PROCREATE_PREFIX ${PROCREATE_DIRECTORY}/local-${PROCREATE_NAME})
  add_test(NAME procreate.local.${PROCREATE_NAME}.import
           COMMAND nanquim-cli import-procreate ${PROCREATE_PATH} ${PROCREATE_PREFIX}.layers)
  add_test(NAME procreate.local.${PROCREATE_NAME}.render
           COMMAND nanquim-cli render ${PROCREATE_PREFIX}.layers ${PROCREATE_PREFIX}.layers.png)
  add_test(NAME procreate.local.${PROCREATE_NAME}.composite
           COMMAND nanquim-cli import-procreate ${PROCREATE_PATH} ${PROCREATE_PREFIX}.composite --composite)
  add_test(NAME procreate.local.${PROCREATE_NAME}.render-composite
           COMMAND nanquim-cli render ${PROCREATE_PREFIX}.composite ${PROCREATE_PREFIX}.composite.png)
  add_test(NAME procreate.local.${PROCREATE_NAME}.compare
           COMMAND nanquim-cli compare ${PROCREATE_PREFIX}.layers.png ${PROCREATE_PREFIX}.composite.png)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.render
                       PROPERTIES DEPENDS procreate.local.${PROCREATE_NAME}.import)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.render-composite
                       PROPERTIES DEPENDS procreate.local.${PROCREATE_NAME}.composite)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.compare
                       PROPERTIES DEPENDS "procreate.local.${PROCREATE_NAME}.render;procreate.local.${PROCREATE_NAME}.render-composite")
  add_test(NAME procreate.local.${PROCREATE_NAME}.psd-export
           COMMAND nanquim-cli export-psd ${PROCREATE_PREFIX}.layers ${PROCREATE_PREFIX}.psd)
  add_test(NAME procreate.local.${PROCREATE_NAME}.psd-import
           COMMAND nanquim-cli import-psd ${PROCREATE_PREFIX}.psd ${PROCREATE_PREFIX}.psd-again)
  add_test(NAME procreate.local.${PROCREATE_NAME}.psd-render
           COMMAND nanquim-cli render ${PROCREATE_PREFIX}.psd-again ${PROCREATE_PREFIX}.psd-again.png)
  add_test(NAME procreate.local.${PROCREATE_NAME}.psd-compare
           COMMAND nanquim-cli compare ${PROCREATE_PREFIX}.psd-again.png ${PROCREATE_PREFIX}.layers.png)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.psd-export
                       PROPERTIES DEPENDS procreate.local.${PROCREATE_NAME}.import)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.psd-import
                       PROPERTIES DEPENDS procreate.local.${PROCREATE_NAME}.psd-export)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.psd-render
                       PROPERTIES DEPENDS procreate.local.${PROCREATE_NAME}.psd-import)
  set_tests_properties(procreate.local.${PROCREATE_NAME}.psd-compare
                       PROPERTIES DEPENDS "procreate.local.${PROCREATE_NAME}.psd-render;procreate.local.${PROCREATE_NAME}.render")
endforeach()
