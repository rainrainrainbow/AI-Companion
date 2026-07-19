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

# Copy JNI wrapper into llama.cpp source tree - put it in src/ so it's with the other sources
JNI_SRC="$REPO_DIR/app/src/main/cpp/llama_jni.cpp"
cp "$JNI_SRC" "$LLAMA_DIR/src/llama_jni.cpp"
echo "Copied JNI wrapper to $LLAMA_DIR/src/llama_jni.cpp"

# ============================================================
# CRITICAL FIX: Add llama_jni.cpp to the llama target in src/CMakeLists.txt
# The add_library(llama ...) is defined in src/CMakeLists.txt
# ============================================================
SRC_CMAKE="$LLAMA_DIR/src/CMakeLists.txt"
echo "Patching $SRC_CMAKE..."

if grep -q "llama_jni.cpp" "$SRC_CMAKE" 2>/dev/null; then
    echo "  llama_jni.cpp already in src/CMakeLists.txt"
else
    echo "  Adding llama_jni.cpp to src/CMakeLists.txt..."
    
    # Use sed to insert after the first source file line in the add_library(llama block
    # The file typically has: add_library(llama\n  llama.cpp\n  llama-adapter.cpp\n  ...)
    # Insert after 'llama.cpp' line
    sed -i '/^    llama\.cpp$/a\    llama_jni.cpp' "$SRC_CMAKE"
    
    # Verify
    if grep -q "llama_jni.cpp" "$SRC_CMAKE" 2>/dev/null; then
        echo "  SUCCESS: llama_jni.cpp added to src/CMakeLists.txt"
        grep -n "llama_jni" "$SRC_CMAKE"
    else
        echo "  sed failed, trying python fallback..."
        python3 -c "
path = '$SRC_CMAKE'
with open(path, 'r') as f:
    content = f.read()

# Find the add_library(llama block and insert llama_jni.cpp
idx = content.find('add_library(llama')
if idx >= 0:
    # Find the closing parenthesis
    depth = 0
    for i in range(idx, len(content)):
        if content[i] == '(':
            depth += 1
        elif content[i] == ')':
            depth -= 1
            if depth == 0:
                content = content[:i] + '\\n    llama_jni.cpp' + content[i:]
                break
    
    with open(path, 'w') as f:
        f.write(content)
    
    if 'llama_jni.cpp' in content:
        print('SUCCESS: llama_jni.cpp added via python fallback')
    else:
        print('ERROR: Failed to add llama_jni.cpp')
        exit(1)
else:
    print('ERROR: add_library(llama not found in src/CMakeLists.txt')
    exit(1)
"
    fi
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
    cmake --build "$BUILD_DIR" --target llama -- -j$(nproc) 2>&1 | tail -30
    
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
    if command -v nm &>/dev/null; then
        local JNI_COUNT=$(nm -D "$OUTPUT_DIR/libllama.so" 2>/dev/null | grep -c "Java_com_ai" || echo 0)
        echo "  JNI functions found in .so: $JNI_COUNT"
        if [ "$JNI_COUNT" -eq 0 ]; then
            echo "  WARNING: No JNI functions found!"
        fi
    fi
}

build_abi "arm64-v8a"

echo ""
echo "✅ Native libraries built successfully!"
echo "  - app/src/main/jniLibs/arm64-v8a/libllama.so"