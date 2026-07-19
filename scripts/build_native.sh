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
[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found!" && exit 1
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
    cmake --build "$LLAMA_BUILD" -- -j$(nproc) 2>&1 | tail -5
    
    local LLAMA_STATIC=$(find "$LLAMA_BUILD" -name "libllama.a" -type f 2>/dev/null | head -1)
    if [ -z "$LLAMA_STATIC" ]; then
        echo "ERROR: libllama.a not found!"
        find "$LLAMA_BUILD" -name "*.a" -type f 2>/dev/null
        exit 1
    fi
    echo "  Static library: $LLAMA_STATIC"
    
    # Step 2: Build JNI wrapper - directly compile and link
    # We use the NDK toolchain directly to avoid CMake include path issues
    local CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API_LEVEL}-clang"
    local CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API_LEVEL}-clang++"
    
    echo "  Compiling JNI wrapper with direct NDK compiler..."
    $CXX -O3 -std=c++17 -fPIC \
        -I"$LLAMA_DIR/include" \
        -I"$LLAMA_DIR/ggml/include" \
        -c "$REPO_DIR/app/src/main/cpp/llama_jni.cpp" \
        -o "$BUILD_DIR/llama_jni.o" 2>&1
    
    echo "  Linking libllama.so..."
    $CXX -shared -o "$BUILD_DIR/libllama.so" \
        "$BUILD_DIR/llama_jni.o" "$LLAMA_STATIC" \
        -landroid -llog -lz \
        -Wl,--gc-sections -Wl,-z,nocopyreloc \
        -static-libstdc++ 2>&1
    
    # Strip
    local STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    $STRIP --strip-all "$BUILD_DIR/libllama.so" -o "$OUTPUT_DIR/libllama.so" 2>/dev/null || \
        cp "$BUILD_DIR/libllama.so" "$OUTPUT_DIR/libllama.so"
    
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024))KB)"
}

# Build for arm64-v8a (primary target)
build_abi "arm64-v8a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"