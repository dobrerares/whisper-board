cmake_minimum_required(VERSION 3.18)

project(llm_jni)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

# Always optimize — LLM inference is unusable at -O0. Mirrors :whisper's
# CMakeLists which sets the same flag for the same reason.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# llama.cpp build switches — keep the build small and skip everything we don't
# need (server, examples, tools, tests, curl). Only the static library, the
# CPU backend, and ggml.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

# Per-ABI tuning. KleidiAI + OpenMP are arm64-v8a-only; armeabi-v7a builds
# need them off. x86_64 also stays vanilla. Mirrors the upstream
# examples/llama.android shape but extended to cover armeabi-v7a so this
# module ships across all three ABIs declared in build.gradle.kts.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP       ON CACHE BOOL "" FORCE)
    # Match :whisper's tuning — ARMv8.2+ for fast quantized inference on
    # mainstream hardware (~2017 onwards).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} build-llama)

find_library(LOG_LIB log)

add_library(
    llm_jni
    SHARED
    llm_jni.cpp
)

target_include_directories(llm_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llm_jni
    llama
    ggml
    android
    ${LOG_LIB}
)

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(llm_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(llm_jni PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(llm_jni PRIVATE -Wl,--gc-sections)
    target_link_options(llm_jni PRIVATE -Wl,--exclude-libs,ALL)
endif ()
