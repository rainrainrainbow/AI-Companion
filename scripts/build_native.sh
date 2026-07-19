#!/bin/bash
# Build llama.cpp + JNI wrapper for Android using CMake + NDK toolchain
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

LLAMA_VERSION="b4667"
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
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "ERROR: Android toolchain file not found at $TOOLCHAIN_FILE"
    exit 1
fi

# Clone llama.cpp
LLAMA_DIR="$REPO_DIR/llama.cpp"
if [ ! -d "$LLAMA_DIR" ]; then
    echo "Cloning llama.cpp (commit $LLAMA_COMMIT)..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
    cd "$LLAMA_DIR"
    git fetch --depth 1 origin $LLAMA_COMMIT
    git checkout $LLAMA_COMMIT
    cd "$REPO_DIR"
fi

# Verify structure
echo "Checking llama.cpp structure..."
[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found" && exit 1
echo "llama.cpp structure OK"

# Copy JNI wrapper into llama.cpp source tree so it's built together
JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
if [ -f "$JNI_SRC" ]; then
    cp "$JNI_SRC" "$LLAMA_DIR/llama_jni.cpp"
    echo "Copied JNI wrapper to llama.cpp tree"
fi

build_abi() {
    local ABI=$1
    local OUTPUT_DIR="$REPO_DIR/app/src/main/jniLibs/$ABI"
    
    echo ""
    echo "=== Building $ABI ==="
    mkdir -p "$OUTPUT_DIR"
    
    local BUILD_DIR="$REPO_DIR/build_llama/$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"
    
    # Create a minimal CMakeLists.txt that builds llama.cpp + JNI wrapper
    cat > "$BUILD_DIR/CMakeLists.txt" << EOF
cmake_minimum_required(VERSION 3.22.1)
project(llama_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Add llama.cpp as a subdirectory
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama_build)

# Add JNI wrapper as a separate source file to the llama target
# We need to use an object library approach
set(JNI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/llama_jni.cpp)

# Create a shared library that includes both llama and JNI
target_sources(llama PRIVATE ${JNI_SRC})
EOF
    
    # Configure with CMake
    echo "Configuring with CMake..."
    cmake -S "$BUILD_DIR" -B "$BUILD_DIR/build" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=$API_LEVEL \
        -DBUILD_SHARED_LIBS=ON \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_STATIC=OFF \
        -DLLAMA_METAL=OFF \
        -DLLAMA_CUDA=OFF \
        -DLLAMA_VULKAN=OFF \
        -DLLAMA_BLAS=OFF \
        -DLLAMA_CURL=OFF 2>&1 | tail -10
    
    # Build
    echo "Building with CMake..."
    cmake --build "$BUILD_DIR/build" -- -j\$(nproc) 2>&1 | tail -30
    
    # Find the built library
    local LIB_FILE=$(find "$BUILD_DIR/build" -name "libllama.so" -type f 2>/dev/null | head -1)
    if [ -z "$LIB_FILE" ]; then
        echo "ERROR: libllama.so not found!"
        find "$BUILD_DIR/build" -name "*.so" -type f 2>/dev/null
        exit 1
    fi
    
    # Copy to jniLibs
    cp "$LIB_FILE" "$OUTPUT_DIR/libllama.so"
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024))KB)"
}

# Build for arm64-v8a (primary target)
build_abi "arm64-v8a"

# Build for armeabi-v7a
build_abi "armeabi-v7a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"
echo "  - app/src/main/jniLibs/armeabi-v7a/libllama.so"