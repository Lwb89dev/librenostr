cmake_minimum_required(VERSION 3.22)
project(translation_engine)

# ---------------------------------------------------------------------------
# Fetch bergamot-translator (MPL-2.0, github.com/browsermt/bergamot-translator)
# at a specific commit already validated in this session: the full dependency
# tree (marian-dev, sentencepiece, ssplit-cpp, ruy, pcre2) was confirmed to
# cross-compile cleanly for Android arm64-v8a via plain CMake/NDK, producing
# genuine ARM aarch64 ELF output, with the three fixes applied below. See
# the project's translation-engine plan notes for the full investigation.
#
# Deliberately NOT vendored as a git submodule of this repo (no other module
# here vendors C++ source trees; every other dependency is Gradle/Maven-
# declared) — FetchContent keeps this repo lean and the pin explicit via
# GIT_TAG, exactly like a version number anywhere else in this project.
# ---------------------------------------------------------------------------
include(FetchContent)

set(BERGAMOT_COMMIT "9271618ebbdc5d21ac4dc4df9e72beb7ce644774")

FetchContent_Declare(
    bergamot_translator
    GIT_REPOSITORY https://github.com/browsermt/bergamot-translator.git
    GIT_TAG ${BERGAMOT_COMMIT}
    GIT_SUBMODULES ""  # we init only the submodules we actually need, below
    GIT_SHALLOW FALSE  # need full history to check out submodules at pinned commits
)
FetchContent_Populate(bergamot_translator)

# Selective, minimal submodule init: bergamot-translator's own .gitmodules
# also pulls in pybind11 (Python bindings, unused) and a full test-data repo;
# marian-dev's own .gitmodules additionally pulls in CUDA/NCCL, FBGEMM
# (an x86-only alternative to ruy we don't use), a websocket server (marian's
# server mode, unused here), and onnxjs (unused) — none of which this
# translate-only, on-device feature needs, so we skip them rather than
# pulling gigabytes of irrelevant C++ history on every clean build.
execute_process(
    COMMAND git submodule update --init --depth 1
        3rd_party/marian-dev
        3rd_party/ssplit-cpp
    WORKING_DIRECTORY ${bergamot_translator_SOURCE_DIR}
    COMMAND_ERROR_IS_FATAL ANY
)
execute_process(
    COMMAND git submodule update --init --depth 1
        src/3rd_party/sentencepiece
        src/3rd_party/ruy
        src/3rd_party/simd_utils
    WORKING_DIRECTORY ${bergamot_translator_SOURCE_DIR}/3rd_party/marian-dev
    COMMAND_ERROR_IS_FATAL ANY
)
execute_process(
    COMMAND git submodule update --init --depth 1
        third_party/cpuinfo
    WORKING_DIRECTORY ${bergamot_translator_SOURCE_DIR}/3rd_party/marian-dev/src/3rd_party/ruy
    COMMAND_ERROR_IS_FATAL ANY
)

# Map Android's ABI name to the -march= value marian-dev expects. Its own
# BUILD_ARCH cache variable defaults to "native", which clang rejects outright
# when cross-compiling (see plan notes) — every ABI needs an explicit value.
# arm64-v8a is the only ABI this module builds for (see build.gradle.kts).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(_build_arch "armv8-a")
else()
    message(FATAL_ERROR "translation-engine: unhandled ANDROID_ABI '${ANDROID_ABI}' — add a BUILD_ARCH mapping for it")
endif()

set(BUILD_ARCH ${_build_arch} CACHE STRING "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_PYTHON OFF CACHE BOOL "" FORCE)
set(COMPILE_WASM OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
# Deliberately left unset on Android: kpu/intgemm has zero ARM/NEON support
# (confirmed against upstream, last pushed Dec 2023 — genuinely x86-only, not
# just untested). marian-dev's own CMakeLists.txt auto-detects the arm target
# and defaults USE_RUY + USE_RUY_SGEMM to ON in that branch, which already
# covers both the quantized and float32 code paths correctly — do not set
# USE_INTGEMM, USE_RUY, or USE_RUY_SGEMM explicitly here; let architecture
# detection do it. (Forcing them on a non-arm host does NOT hit the same code
# path — that branch is gated to real arm targets only.)
set(USE_STATIC_LIBS OFF CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)

# Android's Bionic libc has pthread built in rather than as a separate
# library, which trips CMake's stock FindThreads module (it looks for a
# system libpthread the way glibc-based Linux has one).
set(THREADS_PREFER_PTHREAD_FLAG OFF CACHE BOOL "" FORCE)
set(CMAKE_HAVE_LIBC_PTHREAD 1 CACHE INTERNAL "")
set(CMAKE_USE_PTHREADS_INIT 1 CACHE INTERNAL "")
set(CMAKE_THREAD_LIBS_INIT "" CACHE STRING "")
set(CMAKE_HAVE_THREADS_LIBRARY 1 CACHE INTERNAL "")

# The Android NDK's CMake toolchain defaults the debuggable Gradle build type
# to -O0, meant for easier breakpoint debugging — but that makes actual NMT
# inference (matrix-heavy transformer math) tens of times slower than it will
# be in the real (release) app, to the point of being unusable for real
# testing. There is no realistic scenario where single-stepping through
# Bergamot/Marian's vendored math kernels is useful, so always build this
# dependency tree optimized regardless of the app's own debug/release build
# type; the app's own Kotlin code is unaffected and still fully debuggable.
add_compile_options(-O2)

add_subdirectory(${bergamot_translator_SOURCE_DIR} ${bergamot_translator_BINARY_DIR})

# ---------------------------------------------------------------------------
# Our own thin JNI bridge
# ---------------------------------------------------------------------------
add_library(translation_engine SHARED
    jni_bridge.cpp
)

target_link_libraries(translation_engine
    bergamot-translator
    log
)
