cmake_minimum_required(VERSION 3.18)
project(apenastc CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The ARM astcenc sources. Drop a copy of the astc-encoder repo into
# src/main/cpp/astc-encoder to build offline; otherwise it is downloaded at configure time.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/astc-encoder/Source/astcenc.h")
    set(ASTC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/astc-encoder")
else()
    include(FetchContent)
    FetchContent_Declare(astcenc
        URL https://github.com/ARM-software/astc-encoder/archive/refs/heads/main.tar.gz)
    FetchContent_GetProperties(astcenc)
    if(NOT astcenc_POPULATED)
        FetchContent_Populate(astcenc)
    endif()
    set(ASTC_ROOT "${astcenc_SOURCE_DIR}")
endif()

file(GLOB ASTC_SRC "${ASTC_ROOT}/Source/astcenc_*.cpp")
# Command line tool files, not part of the library.
list(FILTER ASTC_SRC EXCLUDE REGEX "astcenc_(main|image_external|image_load_store|stb_tga|cli_[a-z_]*)\\.cpp$")

add_library(apenastc SHARED astc_jni.cpp ${ASTC_SRC})
target_include_directories(apenastc PRIVATE "${ASTC_ROOT}/Source")
# ASTCENC_DECOMPRESS_ONLY and ASTCENC_DIAGNOSTICS are checked with #ifdef, so they must stay
# undefined here. Defining them (even as 0) builds a decoder-only library with tracing.
target_compile_definitions(apenastc PRIVATE
    ASTCENC_NEON=1 ASTCENC_SVE=0 ASTCENC_SSE=0 ASTCENC_AVX=0 ASTCENC_POPCNT=0 ASTCENC_F16C=0)
# No fast-math: astcenc refuses to run when built with it.
target_compile_options(apenastc PRIVATE -O3 -fvisibility=hidden -ffp-contract=off)
target_link_libraries(apenastc log)
