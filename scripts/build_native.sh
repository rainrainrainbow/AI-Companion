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
[ ! -f "$LLAMA_DIR/include/llama.h" ] && echo "ERROR: llama.h not found at $LLAMA_DIR/include/llama.h" && exit 1
echo "llama.cpp structure OK"

# Copy JNI wrapper into llama.cpp source tree
JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
cp "$JNI_SRC" "$LLAMA_DIR/llama_jni.cpp"
echo "Copied JNI wrapper to llama.cpp tree"

# ============================================================
# CRITICAL FIX: Add llama_jni.cpp to the llama target in CMake
# ============================================================
LLAMA_CMAKE="$LLAMA_DIR/CMakeLists.txt"
if grep -q "llama_jni.cpp" "$LLAMA_CMAKE" 2>/dev/null; then
    echo "llama_jni.cpp already in CMakeLists.txt, skipping patch"
else
    echo "Patching llama.cpp CMakeLists.txt to include llama_jni.cpp..."
    python3 -c "
import re
with open('$LLAMA_CMAKE', 'r') as f:
    content = f.read()

content = re.sub(
    r'(add_library\\(llama[^)]*?\\n\\s+src/llama\\.cpp)',
    r'\\1\\n    llama_jni.cpp',
    content,
    flags=re.DOTALL
)

with open('$LLAMA_CMAKE', 'w') as f:
    f.write(content)
print('CMakeLists.txt patched successfully')
"
    grep -n "llama_jni" "$LLAMA_CMAKE" || echo "WARNING: llama_jni not found after patch!"
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

    echo "  Configuring llama.cpp with JNI wrapper..."
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
    
    echo "  Building..."
    cmake --build "$BUILD_DIR" --target llama -- -j$(nproc) 2>&1 | tail -20
    
    local LIB_FILE=$(find "$BUILD_DIR" -name "libllama.so" -type f 2>/dev/null | head -1)
    if [ -z "$LIB_FILE" ]; then
        echo "ERROR: libllama.so not found!"
        find "$BUILD_DIR" -name "*.so" -type f 2>/dev/null
        exit 1
    fi
    
    cp "$LIB_FILE" "$OUTPUT_DIR/libllama.so"
    local SIZE=$(stat -c%s "$OUTPUT_DIR/libllama.so" 2>/dev/null || echo 0)
    echo "  Output: $OUTPUT_DIR/libllama.so ($((SIZE/1024))KB)"
    
    # Verify JNI symbols
    local JNI_COUNT=$(nm -D "$OUTPUT_DIR/libllama.so" 2>/dev/null | grep -c "Java_com_ai" || echo 0)
    echo "  JNI functions found: $JNI_COUNT"
    if [ "$JNI_COUNT" -eq 0 ]; then
        echo "  WARNING: No JNI functions! llama_jni.cpp may not be compiled in!"
    fi
}

build_abi "arm64-v8a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"