cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Find Android log library
find_library(log-lib log)

# llama.cpp headers are at ../llama.cpp (cloned by CI / build_native.sh)
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)

# Our JNI library - builds a separate libllama_jni.so that links against the
# pre-built core libllama.so (which is built by build_native.sh without JNI)
#
# Architecture: Gradle calls build_native.sh first (builds libllama.so), then
# builds this JNI wrapper via CMake. The JNI library is small and links
# dynamically to the core llama library.

add_library(llama_jni SHARED
    llama_jni.cpp
)

# Include llama.cpp headers
target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

# Link to pre-built core libllama.so
set(LIBLLAMA_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so)

# Import the pre-built core library
target_link_libraries(llama_jni
    PRIVATE
    ${log-lib}
    android
    jnigraphics
)

# Tell the linker where to find libllama.so at link time
# At runtime, Android's linker will load libllama.so from jniLibs first
# because System.loadLibrary("llama_jni") triggers loading of dependencies
target_link_directories(llama_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# We don't link directly to libllama.so at build time (it's built by build_native.sh)
# Instead, we use dlopen/dlsym at runtime OR rely on the linker resolving symbols
# when libllama.so is loaded. For safety, we add a dependency: 
# The JNI library constructor will verify libllama is loaded.

# Optimize for mobile
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp)
endif()

set_target_properties(llama_jni PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,nocopyreloc"
)