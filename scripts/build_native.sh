#!/bin/bash
# Build llama.cpp + JNI wrapper for Android using NDK standalone toolchain
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

LLAMA_VERSION="b4667"
API_LEVEL=26

# Detect NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk/"* ]; then
        ANDROID_NDK_HOME=$(ls -d $ANDROID_SDK_ROOT/ndk/* 2>/dev/null | sort -V | tail -1)
    elif [ -d "$ANDROID_HOME/ndk/"* ]; then
        ANDROID_HOME=$(ls -d $ANDROID_HOME/ndk/* 2>/dev/null | sort -V | tail -1)
    fi
fi

echo "Using NDK: $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

# Clone llama.cpp if not present
LLAMA_DIR="$REPO_DIR/llama.cpp"
if [ ! -d "$LLAMA_DIR" ]; then
    echo "Cloning llama.cpp v$LLAMA_VERSION..."
    git clone --depth 1 --branch $LLAMA_VERSION https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
fi

# Verify llama.h exists
if [ ! -f "$LLAMA_DIR/llama.h" ]; then
    echo "ERROR: llama.h not found in $LLAMA_DIR"
    ls -la "$LLAMA_DIR/"
    exit 1
fi

echo "llama.h found at $LLAMA_DIR/llama.h"

# Key include paths for llama.cpp
INCLUDE_DIRS="-I$LLAMA_DIR -I$LLAMA_DIR/common -I$LLAMA_DIR/ggml/include"

build_abi() {
    local ABI=$1
    local TARGET=$2
    local OUTPUT_DIR="$REPO_DIR/app/src/main/jniLibs/$ABI"
    
    echo "Building for $ABI ($TARGET)..."
    mkdir -p "$OUTPUT_DIR"
    
    local CC="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang"
    local CXX="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang++"
    local AR="$TOOLCHAIN/bin/llvm-ar"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    # Verify compiler exists
    if [ ! -f "$CXX" ]; then
        echo "ERROR: Compiler not found: $CXX"
        ls "$TOOLCHAIN/bin/" | grep "$TARGET" | head -5
        exit 1
    fi
    
    local FLAGS="-O3 -std=c++17 -fPIC -DNDEBUG $INCLUDE_DIRS"
    
    if [ "$ABI" = "arm64-v8a" ]; then
        FLAGS="$FLAGS -march=armv8.2-a+fp16+rcpc+dotprod"
    elif [ "$ABI" = "armeabi-v7a" ]; then
        FLAGS="$FLAGS -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp"
    fi
    
    local BUILD_DIR=$(mktemp -d)
    local OBJ_FILES=""
    
    # Compile llama.cpp core sources
    echo "  Compiling llama.cpp sources..."
    
    # ggml base C files
    for src in "$LLAMA_DIR/ggml/src/"*.c; do
        basename=$(basename "$src")
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CC $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # ggml base C++ files
    for src in "$LLAMA_DIR/ggml/src/"*.cpp; do
        basename=$(basename "$src")
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # llama.cpp C++ files (skip examples, tests, etc)
    for src in "$LLAMA_DIR/"*.cpp; do
        basename=$(basename "$src")
        case "$basename" in
            main.cpp|server.cpp|train.cpp|benchmark*|test*|perplexity*|convert*|quantize*|embedding*|batched*|parallel*|simple*|speculative*|baby-llama*|save-load-state*|gptneox-wip*|gguf-py*)
                continue ;;
        esac
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # Compile common/ files if they exist
    if [ -d "$LLAMA_DIR/common" ]; then
        for src in "$LLAMA_DIR/common/"*.cpp; do
            if [ -f "$src" ]; then
                basename=$(basename "$src")
                obj="$BUILD_DIR/common_${basename}.o"
                echo "    common/$basename"
                $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
                OBJ_FILES="$OBJ_FILES $obj"
            fi
        done
    fi
    
    # Compile our JNI wrapper
    echo "  Compiling JNI wrapper..."
    local JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
    $CXX $FLAGS -c "$JNI_SRC" -o "$BUILD_DIR/llama_jni.o"
    OBJ_FILES="$OBJ_FILES $BUILD_DIR/llama_jni.o"
    
    # Check if we have any object files
    local OBJ_COUNT=$(ls "$BUILD_DIR/"*.o 2>/dev/null | wc -l)
    echo "  Object files: $OBJ_COUNT"
    
    # Link into shared library
    echo "  Linking libllama.so..."
    $CXX -shared -o "$BUILD_DIR/libllama.so" \
        $OBJ_FILES \
        -landroid -llog -lm -lz \
        -Wl,--gc-sections -Wl,-z,nocopyreloc
    
    # Strip and copy
    $STRIP --strip-all "$BUILD_DIR/libllama.so" -o "$OUTPUT_DIR/libllama.so"
    
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null || stat -f%z "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024/1024))MB)"
    
    rm -rf "$BUILD_DIR"
}

# Build for arm64-v8a
echo ""
echo "=== Building arm64-v8a ==="
build_abi "arm64-v8a" "aarch64-linux-android"

# Build for armeabi-v7a
echo ""
echo "=== Building armeabi-v7a ==="
build_abi "armeabi-v7a" "armv7a-linux-androideabi"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"
echo "  - app/src/main/jniLibs/armeabi-v7a/libllama.so"