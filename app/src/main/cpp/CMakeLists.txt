cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Android log
find_library(log-lib log)

# ========== llama.cpp library (vendored via CMake FetchContent) ==========
include(FetchContent)

# We'll use a pre-built version of llama.cpp to avoid long compile times
# The CI will download and build llama.cpp separately
# For local builds, we fetch from GitHub

FetchContent_Declare(
    llama.cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b4667  # Stable release tag
    GIT_SHALLOW TRUE
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp
)

# Only fetch if not already present
if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/CMakeLists.txt)
    message(STATUS "Fetching llama.cpp source...")
    FetchContent_Populate(llama.cpp)
endif()

# Add llama.cpp library (only the parts we need)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama_build EXCLUDE_FROM_ALL)

# Build only the llama library target
if(TARGET llama)
    set(LLAMA_LIB llama)
else()
    # Fallback: build from source files directly
    message(STATUS "Building llama.cpp from source files...")
    file(GLOB_RECURSE LLAMA_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/*.c
    )
    list(FILTER LLAMA_SOURCES EXCLUDE REGEX "main\\.cpp|server\\.cpp|train\\.cpp|benchmark\\.cpp|test\\.cpp|perplexity\\.cpp|convert\\.cpp|quantize\\.cpp|embedding\\.cpp|batched\\.cpp|parallel\\.cpp|simple\\.cpp|speculative\\.cpp|baby-llama\\.cpp|save-load-state\\.cpp|gptneox-wip\\.cpp|gguf\\.cpp|gguf-py|scripts|examples|tests")
    
    add_library(llama STATIC ${LLAMA_SOURCES})
    target_include_directories(llama PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)
    target_compile_definitions(llama PRIVATE
        GGML_USE_ACCELERATE
        GGML_USE_OPENMP
    )
    find_package(OpenMP QUIET)
    if(OpenMP_CXX_FOUND)
        target_link_libraries(llama PRIVATE OpenMP::OpenMP_CXX)
        target_compile_definitions(llama PRIVATE GGML_USE_OPENMP)
    endif()
    set(LLAMA_LIB llama)
endif()

# ========== Our JNI library ==========
add_library(llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(llama_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/..
)

target_link_libraries(llama_jni
    PRIVATE
    ${LLAMA_LIB}
    ${log-lib}
    jnigraphics
    android
)

# Optimize for mobile
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
    target_compile_options(llama PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp)
    target_compile_options(llama PRIVATE -O3 -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp)
endif()

# Strip debug symbols for release
set_target_properties(llama_jni PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,nocopyreloc"
)
