#!/bin/bash
# Build llama.cpp for Android with separate JNI wrapper
# 
# Architecture:
#   1. libllama.so  - Core llama.cpp library (pure, no JNI)
#   2. libllama_jni.so - JNI wrapper that links against libllama.so
#
# Both .so files are placed in app/src/main/jniLibs/<abi>/
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

[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found" && exit 1
echo "llama.cpp structure OK"

# Path to our JNI source
JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
[ ! -f "$JNI_SRC" ] && echo "ERROR: JNI source not found at $JNI_SRC" && exit 1

build_abi() {
    local ABI=$1
    local OUTPUT_DIR="$REPO_DIR/app/src/main/jniLibs/$ABI"
    
    echo ""
    echo "=== Building $ABI ==="
    mkdir -p "$OUTPUT_DIR"
    
    local BUILD_DIR="$REPO_DIR/build_llama/$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    # ============================================================
    # Step 1: Build core libllama.so (pure llama.cpp, NO JNI)
    # ============================================================
    echo "  [Step 1] Configuring core llama.cpp..."
    cmake -S "$LLAMA_DIR" -B "$BUILD_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=$API_LEVEL \
        -DBUILD_SHARED_LIBS=ON \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_METAL=OFF \
        -DLLAMA_CUDA=OFF \
        -DLLAMA_VULKAN=OFF \
        -DLLAMA_BLAS=OFF \
        -DLLAMA_CURL=OFF 2>&1 | tail -5
    
    echo "  [Step 1] Building core llama.cpp..."
    cmake --build "$BUILD_DIR" --target llama -- -j$(nproc) 2>&1 | tail -20
    
    local CORE_LIB=$(find "$BUILD_DIR" -name "libllama.so" -type f 2>/dev/null | head -1)
    if [ -z "$CORE_LIB" ]; then
        echo "ERROR: libllama.so not found after build!"
        find "$BUILD_DIR" -name "*.so" -type f 2>/dev/null
        exit 1
    fi
    
    cp "$CORE_LIB" "$OUTPUT_DIR/libllama.so"
    local CORE_SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null || echo 0)
    echo "  [Step 1] Core lib: $OUTPUT_DIR/libllama.so ($((CORE_SIZE/1024))KB)"
    
    # Verify NO JNI symbols in core library
    if command -v nm &>/dev/null; then
        local JNI_CORE=$(nm -D "$OUTPUT_DIR/libllama.so" 2>/dev/null | grep -c "Java_com_ai" || echo 0)
        echo "  [Step 1] JNI in core: $JNI_CORE (expected 0)"
    fi
    
    # ============================================================
    # Step 2: Build JNI wrapper library (libllama_jni.so)
    # Uses a standalone CMake build that links against the core lib
    # ============================================================
    echo "  [Step 2] Building JNI wrapper library..."
    
    local JNI_BUILD_DIR="$BUILD_DIR/jni"
    mkdir -p "$JNI_BUILD_DIR"
    
    # Create a temporary CMakeLists.txt for the JNI wrapper
    cat > "$JNI_BUILD_DIR/CMakeLists.txt" << CMAKE_EOF
cmake_minimum_required(VERSION 3.12)
project(llama_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(llama_jni SHARED
    "${JNI_SRC}"
)

# Include llama.cpp headers
target_include_directories(llama_jni PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/common"
    "${LLAMA_DIR}/ggml/include"
)

# Import the pre-built core library (so we get a DT_NEEDED entry)
add_library(llama_core SHARED IMPORTED)
set_target_properties(llama_core PROPERTIES
    IMPORTED_LOCATION "${OUTPUT_DIR}/libllama.so"
    IMPORTED_SONAME "libllama.so"
)

# Link against core and Android libs
target_link_libraries(llama_jni PRIVATE
    llama_core
    ${log-lib}
    android
)

# Optimize for ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE -O3 -march=armv8.2-a+fp16+rcpc+dotprod)
endif()
CMAKE_EOF
    
    cmake -S "$JNI_BUILD_DIR" -B "$JNI_BUILD_DIR/build" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM=$API_LEVEL 2>&1 | tail -5
    
    cmake --build "$JNI_BUILD_DIR/build" -- -j$(nproc) 2>&1 | tail -20
    
    local JNI_LIB=$(find "$JNI_BUILD_DIR/build" -name "libllama_jni.so" -type f 2>/dev/null | head -1)
    if [ -z "$JNI_LIB" ]; then
        echo "ERROR: libllama_jni.so not found!"
        exit 1
    fi
    
    cp "$JNI_LIB" "$OUTPUT_DIR/libllama_jni.so"
    local JNI_SIZE=$(stat -c%s "$OUTPUT_DIR/libllama_jni.so" 2>/dev/null || echo 0)
    echo "  [Step 2] JNI lib: $OUTPUT_DIR/libllama_jni.so ($((JNI_SIZE/1024))KB)"
    
    # Verify JNI symbols in JNI library
    if command -v nm &>/dev/null; then
        local JNI_COUNT=$(nm -D "$OUTPUT_DIR/libllama_jni.so" 2>/dev/null | grep -c "Java_com_ai" || echo 0)
        echo "  [Step 2] JNI functions in JNI lib: $JNI_COUNT"
        if [ "$JNI_COUNT" -eq 0 ]; then
            echo "  WARNING: No JNI functions found in JNI library!"
        fi
        # Show DT_NEEDED entries
        echo "  [Step 2] DT_NEEDED:"
        readelf -d "$OUTPUT_DIR/libllama_jni.so" 2>/dev/null | grep "NEEDED" || echo "    (readelf not available)"
    fi
    
    # Cleanup temp CMakeLists
    rm -rf "$JNI_BUILD_DIR"
}

build_abi "arm64-v8a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so (core)"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama_jni.so (JNI wrapper)"