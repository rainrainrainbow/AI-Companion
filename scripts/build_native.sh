#!/bin/bash
# Build llama.cpp + JNI wrapper for Android using NDK standalone toolchain
# This script is used by CI to produce libllama.so for arm64-v8a and armeabi-v7a

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
LLAMA_VERSION="b4667"
NDK_VERSION="27.0.12077973"
API_LEVEL=26

# Detect NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
        ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
    elif [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
    else
        echo "Error: NDK not found. Set ANDROID_NDK_HOME"
        exit 1
    fi
fi

echo "Using NDK: $ANDROID_NDK_HOME"

# Toolchain path
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

# Clone llama.cpp if not present
if [ ! -d "$REPO_DIR/llama.cpp" ]; then
    echo "Cloning llama.cpp v$LLAMA_VERSION..."
    git clone --depth 1 --branch $LLAMA_VERSION https://github.com/ggerganov/llama.cpp.git "$REPO_DIR/llama.cpp"
fi

# Build for each target ABI
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
    
    # Compile flags
    local FLAGS="-O3 -std=c++17 -fPIC -DNDEBUG"
    local FLAGS="$FLAGS -I$REPO_DIR/llama.cpp -I$REPO_DIR/llama.cpp/common -I$REPO_DIR/llama.cpp/ggml/include -I$REPO_DIR/llama.cpp/ggml/src"
    local FLAGS="$FLAGS -I$REPO_DIR/app/src/main/cpp"
    
    if [ "$ABI" = "arm64-v8a" ]; then
        FLAGS="$FLAGS -march=armv8.2-a+fp16+rcpc+dotprod"
    elif [ "$ABI" = "armeabi-v7a" ]; then
        FLAGS="$FLAGS -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp"
    fi
    
    local BUILD_DIR=$(mktemp -d)
    
    # Compile llama.cpp sources
    echo "  Compiling llama.cpp sources..."
    for src in "$REPO_DIR/llama.cpp/"*.cpp "$REPO_DIR/llama.cpp/ggml/src/"*.c "$REPO_DIR/llama.cpp/ggml/src/"*.cpp; do
        basename=$(basename "$src")
        # Skip test/example files
        case "$basename" in
            main.cpp|server.cpp|train.cpp|benchmark*|test*|perplexity*|convert*|quantize*|embedding*|batched*|parallel*|simple*|speculative*|baby-llama*|save-load-state*|gptneox-wip*)
                continue ;;
        esac
        
        obj="$BUILD_DIR/${basename}.o"
        
        if [[ "$src" == *.c ]]; then
            $CC $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        else
            $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        fi
    done
    
    # Compile our JNI wrapper
    echo "  Compiling JNI wrapper..."
    $CXX $FLAGS -c "$REPO_DIR/app/src/main/cpp/llama_jni.cpp" -o "$BUILD_DIR/llama_jni.o"
    
    # Link into shared library
    echo "  Linking libllama.so..."
    $CXX -shared -o "$BUILD_DIR/libllama.so" \
        "$BUILD_DIR/"*.o \
        -landroid -llog -lm -lz \
        -Wl,--gc-sections -Wl,-z,nocopyreloc
    
    # Strip and copy
    $STRIP --strip-all "$BUILD_DIR/libllama.so" -o "$OUTPUT_DIR/libllama.so"
    
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null || stat -f%z "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024/1024))MB)"
    
    rm -rf "$BUILD_DIR"
}

# Build for arm64-v8a
build_abi "arm64-v8a" "aarch64-linux-android"

# Build for armeabi-v7a
build_abi "armeabi-v7a" "armv7a-linux-androideabi"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"
echo "  - app/src/main/jniLibs/armeabi-v7a/libllama.so"