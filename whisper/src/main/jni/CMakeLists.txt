cmake_minimum_required(VERSION 3.10)

project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Get whisper.cpp version
file(READ "${WHISPER_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS "Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

# Always optimize — ML inference is unusable at -O0.
# Global flags so ggml (FetchContent) also gets -O3.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Enable ARM dotprod + fp16 for fast quantized inference (ARMv8.2+, ~2017 onwards).
if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

include(FetchContent)

add_library(
    whisper_jni
    SHARED
    ${SOURCE_FILES}
)

target_compile_definitions(whisper_jni PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_compile_options(whisper_jni PRIVATE -ffunction-sections -fdata-sections)

    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)
    target_link_options(whisper_jni PRIVATE -flto)
endif ()

FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_link_libraries(whisper_jni ${LOG_LIB} android ggml)

include_directories(${WHISPER_DIR})
include_directories(${WHISPER_DIR}/src)
include_directories(${WHISPER_DIR}/include)
include_directories(${WHISPER_DIR}/ggml/include)
include_directories(${WHISPER_DIR}/ggml/src)
include_directories(${WHISPER_DIR}/ggml/src/ggml-cpu)
