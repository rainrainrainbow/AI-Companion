cmake_minimum_required(VERSION 3.22)
project("aicompanion_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp
add_subdirectory(libs/llama.cpp llama)

# whisper.cpp
add_subdirectory(libs/whisper.cpp whisper)

# VITS TTS
add_library(vits_tts SHARED src/tts_jni.cpp)
target_link_libraries(vits_tts onnxruntime)

# JNI bridge
add_library(companion_jni SHARED
    src/llama_jni.cpp
    src/whisper_jni.cpp
    src/tts_jni.cpp
)

target_include_directories(companion_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libs/llama.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/libs/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(companion_jni
    llama
    whisper
    jnigraphics
    log
)

# 编译选项：优化ARM64
target_compile_options(companion_jni PRIVATE
    -O3
    -march=armv8.2a+dotprod+fp16
    -ffast-math
    -funsafe-math-optimizations
)

# 架构相关
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(companion_jni PRIVATE __ARM64__)
endif()