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
# CRITICAL FIX: Add llama_jni.cpp to the llama target in src/CMakeLists.txt
# The add_library(llama ...) is defined in src/CMakeLists.txt, not root CMakeLists.txt
# ============================================================
SRC_CMAKE="$LLAMA_DIR/src/CMakeLists.txt"
echo "Patching $SRC_CMAKE..."

# Check if already patched
if grep -q "llama_jni.cpp" "$SRC_CMAKE" 2>/dev/null; then
    echo "  llama_jni.cpp already in src/CMakeLists.txt"
else
    # The file uses llama_add_compile_flags() at the top, then
    # add_library(llama ...) with sources listed
    # Insert llama_jni.cpp after the first .cpp source file in the add_library block
    echo "  Adding llama_jni.cpp to src/CMakeLists.txt..."
    
    # Use python for reliable multiline pattern matching
    python3 << 'PYEOF'
import re

src_cmake = "$LLAMA_DIR/src/CMakeLists.txt"
with open(src_cmake, 'r') as f:
    content = f.read()

# The add_library(llama ...) block lists source files
# Pattern: add_library(llama ...)\n  llama.cpp\n  ...
# We need to insert after the add_library(llama line and before first source
# Or better: find "add_library(llama" and add after the opening paren

# Simpler approach: just append llama_jni.cpp to the source list
# Find the end of the add_library block (the closing parenthesis)
# and insert before it

# First, find the add_library(llama block
idx = content.find('add_library(llama')
if idx >= 0:
    # Find the closing parenthesis of this add_library call
    # The block spans multiple lines
    start = idx
    depth = 0
    for i in range(start, len(content)):
        if content[i] == '(':
            depth += 1
        elif content[i] == ')':
            depth -= 1
            if depth == 0:
                # Insert before the closing )
                content = content[:i] + '\n    llama_jni.cpp' + content[i:]
                print(f"Inserted llama_jni.cpp at position {i}")
                break
else:
    # Try lowercase
    idx = content.find('add_library(llama')
    if idx >= 0:
        start = idx
        depth = 0
        for i in range(start, len(content)):
            if content[i] == '(':
                depth += 1
            elif content[i] == ')':
                depth -= 1
                if depth == 0:
                    content = content[:i] + '\n    llama_jni.cpp' + content[i:]
                    print(f"Inserted llama_jni.cpp at position {i}")
                    break

with open(src_cmake, 'w') as f:
    f.write(content)

# Verify
if 'llama_jni.cpp' in content:
    print("SUCCESS: llama_jni.cpp added to src/CMakeLists.txt")
else:
    print("ERROR: Failed to add llama_jni.cpp")
    exit(1)
PYEOF
    
    # Show verification
    grep -n "llama_jni" "$SRC_CMAKE" || echo "  WARNING: llama_jni not found in $SRC_CMAKE"
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