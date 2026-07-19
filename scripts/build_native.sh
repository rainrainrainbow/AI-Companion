#!/bin/bash
# Build llama.cpp + JNI wrapper for Android using CMake + NDK toolchain
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

LLAMA_COMMIT="d2fe216fb2fb7ca8627618c9ea3a2e7886325780"
API_LEVEL=26

# Detect NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    for d in "$ANDROID_SDK_ROOT/ndk/"* "$ANDROID_HOME/ndk/"*; do
        [ -d "$d" ] && ANDROID_NDK_HOME="$d"
    done
fi
echo "Using NDK: $ANDROID_NDK_HOME"

TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
[ ! -f "$TOOLCHAIN_FILE" ] && echo "ERROR: Android toolchain file not found" && exit 1

# Clone llama.cpp
LLAMA_DIR="$REPO_DIR/llama.cpp"
if [ ! -d "$LLAMA_DIR" ]; then
    echo "Cloning llama.cpp..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
    cd "$LLAMA_DIR"
    git fetch --depth 1 origin $LLAMA_COMMIT
    git checkout $LLAMA_COMMIT
    cd "$REPO_DIR"
fi

# Verify
[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found!" && ls -la "$LLAMA_DIR/include/" && exit 1
echo "llama.cpp structure OK"

build_abi() {
    local ABI=$1
    local OUTPUT_DIR="$REPO_DIR/app/src/main/jniLibs/$ABI"
    
    echo ""
    echo "=== Building $ABI ==="
    mkdir -p "$OUTPUT_DIR"
    
    local BUILD_DIR="$REPO_DIR/build_llama/$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    # Step 1: Build llama.cpp as a static library
    local LLAMA_BUILD="$BUILD_DIR/llama_build"
    mkdir -p "$LLAMA_BUILD"
    
    echo "  Configuring llama.cpp (static)..."
    cmake -S "$LLAMA_DIR" -B "$LLAMA_BUILD" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=$API_LEVEL \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_METAL=OFF \
        -DLLAMA_CUDA=OFF \
        -DLLAMA_VULKAN=OFF \
        -DLLAMA_BLAS=OFF \
        -DLLAMA_CURL=OFF 2>&1 | tail -5
    
    echo "  Building llama.cpp (static)..."
    cmake --build "$LLAMA_BUILD" -- -j$(nproc) 2>&1 | tail -10
    
    local LLAMA_STATIC=$(find "$LLAMA_BUILD" -name "libllama.a" -type f 2>/dev/null | head -1)
    if [ -z "$LLAMA_STATIC" ]; then
        echo "ERROR: libllama.a not found!"
        find "$LLAMA_BUILD" -name "*.a" -type f 2>/dev/null
        exit 1
    fi
    echo "  Static library: $LLAMA_STATIC"
    
    # Step 2: Build JNI wrapper shared library
    local JNI_BUILD="$BUILD_DIR/jni_build"
    mkdir -p "$JNI_BUILD"
    
    cat > "$JNI_BUILD/CMakeLists.txt" << CMAKEEOF
cmake_minimum_required(VERSION 3.22.1)
project(llama_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

find_library(log-lib log)

# Import the static llama library
add_library(llama STATIC IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${LLAMA_STATIC}")

# Include paths
set(LLAMA_INCLUDE_DIRS
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
)

# Our JNI shared library
add_library(llama_jni SHARED
    "${REPO_DIR}/app/src/main/cpp/llama_jni.cpp"
)

target_include_directories(llama_jni PRIVATE ${LLAMA_INCLUDE_DIRS})

target_link_libraries(llama_jni PRIVATE llama ${log-lib} android)

# Output as libllama.so for System.loadLibrary("llama")
set_target_properties(llama_jni PROPERTIES OUTPUT_NAME "llama")
CMAKEEOF
    
    echo "  Configuring JNI wrapper..."
    cmake -S "$JNI_BUILD" -B "$JNI_BUILD/build" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=$API_LEVEL 2>&1 | tail -5
    
    echo "  Building JNI wrapper..."
    cmake --build "$JNI_BUILD/build" -- -j$(nproc) 2>&1 | tail -20
    
    local JNI_LIB=$(find "$JNI_BUILD/build" -name "libllama.so" -type f 2>/dev/null | head -1)
    if [ -z "$JNI_LIB" ]; then
        echo "ERROR: libllama.so not found!"
        find "$JNI_BUILD/build" -name "*.so" -type f 2>/dev/null
        exit 1
    fi
    
    cp "$JNI_LIB" "$OUTPUT_DIR/libllama.so"
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024))KB)"
}

# Build for arm64-v8a (primary target)
build_abi "arm64-v8a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"