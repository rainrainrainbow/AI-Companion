cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

find_library(log-lib log)

# llama.cpp headers are at ../llama.cpp (cloned by CI / build_native.sh)
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)

# Our JNI library - builds a separate libllama_jni.so that links against the
# pre-built core libllama.so (which is built by build_native.sh without JNI)
add_library(llama_jni SHARED
    llama_jni.cpp
)

# Include llama.cpp headers
target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

# Import the pre-built core libllama.so as an IMPORTED target
# This creates a proper DT_NEEDED entry in the JNI library
add_library(llama_core SHARED IMPORTED)
set_target_properties(llama_core PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so
)

target_link_libraries(llama_jni
    PRIVATE
    ${log-lib}
    android
    jnigraphics
    llama_core
)

# Optimize for mobile
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp)
endif()

set_target_properties(llama_jni PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections -Wl,-z,nocopyreloc"
)