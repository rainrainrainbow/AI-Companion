cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

find_library(log-lib log)

# llama.cpp headers are expected at ../llama.cpp (cloned by CI)
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)

# We link against pre-built libraries from jniLibs
# The CI downloads pre-built .so files and places them in jniLibs/
# For the build to succeed, we only need the headers

target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

# Our JNI library - links against the pre-built libllama.so
add_library(llama_jni SHARED
    llama_jni.cpp
)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/..
)

target_link_libraries(llama_jni
    PRIVATE
    ${log-lib}
    jnigraphics
    android
)

# We don't link against llama directly - it's loaded at runtime via System.loadLibrary
# Just declare it as a dependency for build ordering
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so)

# Optimize for mobile
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp)
endif()

set_target_properties(llama_jni PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,nocopyreloc"
)
