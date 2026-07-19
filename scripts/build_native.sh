#!/bin/bash
# Build llama.cpp + JNI wrapper for Android using NDK
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

LLAMA_VERSION="b4667"
API_LEVEL=26

# Detect NDK (latest version)
if [ -z "$ANDROID_NDK_HOME" ]; then
    for d in "$ANDROID_SDK_ROOT/ndk/"* "$ANDROID_HOME/ndk/"*; do
        [ -d "$d" ] && ANDROID_NDK_HOME="$d"
    done
fi
echo "Using NDK: $ANDROID_NDK_HOME"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"

# Clone llama.cpp
LLAMA_DIR="$REPO_DIR/llama.cpp"
if [ ! -d "$LLAMA_DIR" ]; then
    echo "Cloning llama.cpp v$LLAMA_VERSION..."
    git clone --depth 1 --branch $LLAMA_VERSION https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
fi

# Verify structure
[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found" && exit 1
echo "llama.cpp structure OK"

# Include paths (new structured layout)
INCLUDE_DIRS="-I$LLAMA_DIR/include -I$LLAMA_DIR/src -I$LLAMA_DIR/common -I$LLAMA_DIR/ggml/include -I$LLAMA_DIR/ggml/src"

build_abi() {
    local ABI=$1
    local TARGET=$2
    local OUTPUT_DIR="$REPO_DIR/app/src/main/jniLibs/$ABI"
    
    echo "Building for $ABI ($TARGET)..."
    mkdir -p "$OUTPUT_DIR"
    
    local CC="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang"
    local CXX="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang++"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"
    
    [ ! -f "$CXX" ] && echo "ERROR: Compiler not found: $CXX" && exit 1
    
    local FLAGS="-O3 -std=c++17 -fPIC -DNDEBUG $INCLUDE_DIRS"
    
    if [ "$ABI" = "arm64-v8a" ]; then
        FLAGS="$FLAGS -march=armv8.2-a+fp16+rcpc+dotprod"
    elif [ "$ABI" = "armeabi-v7a" ]; then
        FLAGS="$FLAGS -march=armv7-a+fp -mfpu=neon -mfloat-abi=softfp"
    fi
    
    local BUILD_DIR=$(mktemp -d)
    local OBJ_FILES=""
    
    # Compile ggml C sources
    echo "  Compiling ggml C sources..."
    for src in "$LLAMA_DIR/ggml/src/"*.c; do
        basename=$(basename "$src")
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CC $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # Compile ggml C++ sources
    echo "  Compiling ggml C++ sources..."
    for src in "$LLAMA_DIR/ggml/src/"*.cpp; do
        [ ! -f "$src" ] && continue
        basename=$(basename "$src")
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # Compile llama.cpp src sources
    echo "  Compiling llama.cpp src sources..."
    for src in "$LLAMA_DIR/src/"*.cpp; do
        [ ! -f "$src" ] && continue
        basename=$(basename "$src")
        obj="$BUILD_DIR/${basename}.o"
        echo "    $basename"
        $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
        OBJ_FILES="$OBJ_FILES $obj"
    done
    
    # Compile common sources
    if [ -d "$LLAMA_DIR/common" ]; then
        echo "  Compiling common sources..."
        for src in "$LLAMA_DIR/common/"*.cpp; do
            [ ! -f "$src" ] && continue
            basename=$(basename "$src")
            obj="$BUILD_DIR/common_${basename}.o"
            echo "    $basename"
            $CXX $FLAGS -c "$src" -o "$obj" 2>/dev/null || true
            OBJ_FILES="$OBJ_FILES $obj"
        done
    fi
    
    # Compile JNI wrapper
    echo "  Compiling JNI wrapper..."
    local JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
    $CXX $FLAGS -c "$JNI_SRC" -o "$BUILD_DIR/llama_jni.o"
    OBJ_FILES="$OBJ_FILES $BUILD_DIR/llama_jni.o"
    
    # Check object count
    local OBJ_COUNT=$(ls "$BUILD_DIR/"*.o 2>/dev/null | wc -l)
    echo "  Object files: $OBJ_COUNT"
    
    # Link into shared library
    echo "  Linking libllama.so..."
    $CXX -shared -o "$BUILD_DIR/libllama.so" \
        $OBJ_FILES \
        -landroid -llog -lm -lz \
        -Wl,--gc-sections -Wl,-z,nocopyreloc \
        -static-libstdc++
    
    # Strip and copy
    $STRIP --strip-all "$BUILD_DIR/libllama.so" -o "$OUTPUT_DIR/libllama.so" 2>/dev/null || \
        cp "$BUILD_DIR/libllama.so" "$OUTPUT_DIR/libllama.so"
    
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024/1024))MB)"
    
    rm -rf "$BUILD_DIR"
}

# Build for arm64-v8a (primary target)
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