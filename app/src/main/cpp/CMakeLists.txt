# JARVIS local LLM — native build.
#
# This builds llama.cpp from source (fetched into `app/build/llama-cpp/` by the
# `fetchLlamaCpp` Gradle task) and links our own thin JNI bridge against it.
#
# Why build from source rather than ship a prebuilt .so:
#
#  * We need exactly one ABI (arm64-v8a). The upstream Android artefacts ship
#    their own set of shared libraries that would have to be unpacked and
#    re-packaged anyway, and their symbol set does not match the llama.cpp
#    version our JNI bridge is written against.
#  * Building here means the C API our bridge calls (`llama_model_get_chat_template`,
#    `llama_sampler_chain_init`, `llama_vocab_is_eog`, ...) is pinned to the exact
#    source revision we vendored, so the Kotlin side cannot drift out of sync
#    with the native side.
#
# Vulkan is deliberately NOT enabled. On the Adreno 6xx generation that this
# app targets, the Vulkan backend is frequently *slower* than the CPU kernels,
# which use hand-tuned NEON and dotprod instructions. CPU-only is also what
# keeps the shipped binary small and the build reproducible.
#
# 32-bit (armeabi-v7a) is not built: the app's `abiFilters` only allow arm64-v8a
# for the llama.cpp path, matching the "phones only" target.

cmake_minimum_required(VERSION 3.22.1)

project("jarvis-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp
# ---------------------------------------------------------------------------

# `LLAMA_SRC_DIR` is injected by Gradle as a cache variable pointing at the
# unpacked llama.cpp source tree. Fall back to a conventional location so the
# project can also be opened in Android Studio without Gradle.
set(LLAMA_SRC_DIR "${LLAMA_SRC_DIR}" CACHE PATH "Path to the unpacked llama.cpp source tree")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # The source check only applies to the ABI that actually uses llama.cpp.
    if(LLAMA_SRC_DIR STREQUAL "" OR NOT EXISTS "${LLAMA_SRC_DIR}/include/llama.h")
        message(FATAL_ERROR
            "llama.cpp sources were not found. Expected include/llama.h under "
            "'${LLAMA_SRC_DIR}'. The Gradle 'fetchLlamaCpp' task downloads and "
            "unpacks them before the native build runs.")
    endif()

    message(STATUS "llama.cpp source: ${LLAMA_SRC_DIR}")

    set(GGML_SYSTEM_ARCH "ARM")
    # KleidiAI gives the best NEON/dotprod kernels on ARMv8.2-A and above.
    #
    # The cache entries are written with FORCE because ggml reads them with
    # `option()` deep inside its own CMakeLists: a plain `set()` here would
    # shadow the cache entry but the `option()` call would still see the
    # cached default, so the two would silently disagree.
    #
    # FORCE also makes these overridable from the command line in the way we
    # need: a build can pass -DGGML_CPU_KLEIDIAI=OFF to fall back to the
    # standard ARM NEON kernels if the upstream KleidiAI download is ever
    # unavailable. (KleidiAI is fetched by ggml with FetchContent at
    # configure time, so it is a build-time network dependency.)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "ggml: use KleidiAI optimized kernels if applicable")
    set(GGML_OPENMP ON CACHE BOOL "ggml: use OpenMP")
    set(JARVIS_BUILD_LLAMA ON)
else()
    # The llama.cpp path is arm64-v8a only. The app still ships 32-bit
    # sherpa-onnx libraries, so for any other ABI we simply do not build the
    # language-model bridge at all rather than failing the whole build.
    #
    # The Kotlin side handles this: `LlamaNative.isAvailable` returns false
    # when the library is missing, and the assistant falls back to the
    # rule-based replies.
    message(STATUS
        "Skipping the llama.cpp bridge for ABI ${ANDROID_ABI}: arm64-v8a only")
    set(JARVIS_BUILD_LLAMA OFF)
endif()

if(JARVIS_BUILD_LLAMA)
    # Build llama.cpp as a static library. A static link keeps the APK to a
    # single extra .so (our own bridge) instead of shipping llama.cpp's many
    # backend libraries separately.
    set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP         ${GGML_OPENMP} CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
    # The NDK sets -fPIC by default, but stating it explicitly keeps the
    # static llama.cpp archive linkable into our shared bridge even if a
    # toolchain default ever changes.
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)

    add_subdirectory("${LLAMA_SRC_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build" EXCLUDE_FROM_ALL)

    # -----------------------------------------------------------------------
    # The JNI bridge
    # -----------------------------------------------------------------------

    add_library(jarvis_llama SHARED jarvis_llama.cpp)

    target_include_directories(jarvis_llama PRIVATE
        "${LLAMA_SRC_DIR}"
        "${LLAMA_SRC_DIR}/include"
        "${LLAMA_SRC_DIR}/ggml/include"
        "${LLAMA_SRC_DIR}/ggml/src"
    )

    target_compile_definitions(jarvis_llama PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
    )

    target_link_libraries(jarvis_llama
        llama
        ggml
        android
        log
    )

    # llama.cpp is built with -ffast-math and friends; our bridge only uses
    # plain C++ so it needs no special flags, but we do want warnings on our
    # own file.
    target_compile_options(jarvis_llama PRIVATE -Wall -Wextra -Wno-unused-parameter)
endif()
