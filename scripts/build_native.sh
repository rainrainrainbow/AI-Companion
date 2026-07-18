#!/bin/bash
# Build native libraries for Android
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JNI_DIR="$PROJECT_DIR/app/src/main/jniLibs"
LIBS_DIR="$PROJECT_DIR/app/src/main/cpp/libs"

echo "=== Building Native Libraries for AI Companion ==="

# Setup NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_HOME/ndk" ]; then
        NDK_VERSION=$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
    elif [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
        NDK_VERSION=$(ls "$ANDROID_SDK_ROOT/ndk" | sort -V | tail -1)
        ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
    else
        echo "Error: ANDROID_NDK_HOME not set"
        exit 1
    fi
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# Clone llama.cpp if not exists
if [ ! -d "$LIBS_DIR/llama.cpp" ]; then
    echo "Cloning llama.cpp..."
    git clone --depth 1 --branch b4684 \
        https://github.com/ggml-org/llama.cpp.git \
        "$LIBS_DIR/llama.cpp"
fi

# Clone whisper.cpp if not exists
if [ ! -d "$LIBS_DIR/whisper.cpp" ]; then
    echo "Cloning whisper.cpp..."
    git clone --depth 1 \
        https://github.com/ggerganov/whisper.cpp.git \
        "$LIBS_DIR/whisper.cpp"
fi

# Build for arm64-v8a
echo "Building for arm64-v8a..."
mkdir -p build/arm64-v8a
cd build/arm64-v8a

cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_ARM_NEON=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DLLAMA_METAL=OFF \
    -DLLAMA_CUDA=OFF \
    -DLLAMA_VULKAN=ON \
    "$PROJECT_DIR/app/src/main/cpp"

cmake --build . -j$(nproc)

# Copy libraries
mkdir -p "$JNI_DIR/arm64-v8a"
cp *.so "$JNI_DIR/arm64-v8a/" 2>/dev/null || true
echo "arm64-v8a build complete"

# Build for armeabi-v7a
echo "Building for armeabi-v7a..."
cd ..
mkdir -p armeabi-v7a
cd armeabi-v7a

cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_ARM_NEON=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    "$PROJECT_DIR/app/src/main/cpp"

cmake --build . -j$(nproc)

mkdir -p "$JNI_DIR/armeabi-v7a"
cp *.so "$JNI_DIR/armeabi-v7a/" 2>/dev/null || true
echo "armeabi-v7a build complete"

echo "=== Native build complete! ==="
echo "Libraries placed in: $JNI_DIR"