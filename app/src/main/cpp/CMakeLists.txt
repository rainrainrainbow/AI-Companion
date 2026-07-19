cmake_minimum_required(VERSION 3.22.1)
project("llama_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

find_library(log-lib log)

# llama.cpp headers are expected at ../llama.cpp (cloned by CI)
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)

# Include llama.cpp headers
target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_DIR}
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

# Our JNI library - compiled as part of the llama shared library
# The actual build is done by build_native.sh which patches llama.cpp's CMakeLists.txt
# This CMakeLists.txt is for local Android Studio development only
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

# Import pre-built libllama.so (built by build_native.sh)
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