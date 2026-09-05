#!/usr/bin/env bash
# Cross-compile llama.cpp's server for Android arm64 WITH the Vulkan backend, inside WSL.
#
# Why WSL and not the Windows host: the cross-compile also builds two *host* tools
# (llama-ui-embed and vulkan-shaders-gen). Those need a GCC-style host compiler, and the
# Windows box has only MSVC (cl.exe), whose flags don't match the `-O2 -std=c++17 -o`
# command llama.cpp hardcodes; the NDK's own clang can't target Windows either. Under
# Linux the NDK's clang *is* a valid host compiler, which is exactly why upstream CI does
# this on ubuntu.
#
# GGML_BACKEND_DL=ON is the important flag: Vulkan and CPU become separately loadable
# backends chosen at runtime, so this ONE build replaces the current CPU-only engine
# rather than sitting beside it — which also avoids a filename collision, since Android's
# nativeLibraryDir is flat and a second dynamic build would ship its own libggml.so.
set -euo pipefail

# android-28, not the CPU engine's 24, and this is forced rather than chosen: ggml-vulkan calls
# vkGetPhysicalDeviceFeatures2, a Vulkan 1.1 entry point, and Android's Vulkan 1.1 symbols only
# appear in the API-28 stub — linking against 24 fails with `undefined symbol:
# vkGetPhysicalDeviceFeatures2`. This is why upstream's Android-Vulkan CI pins 28 too. The
# consequence to decide on: a Vulkan-enabled engine cannot run below Android 9.
NDK=/root/ndk/android-ndk-r29
SRC=/root/vk/llama.cpp
BUILD=/root/vk/build-vk
KHRONOS=/root/vk/khronos

test -f "$NDK/build/cmake/android.toolchain.cmake" || { echo "FATAL: no NDK toolchain at $NDK"; exit 1; }
test -x "$NDK/shader-tools/linux-x86_64/glslc" || { echo "FATAL: no glslc in NDK"; exit 1; }
# The NDK sysroot ships Vulkan's C header but NOT the C++ bindings, and ggml-vulkan.cpp
# includes <vulkan/vulkan.hpp>. find_package(Vulkan) otherwise resolves to the sysroot copy
# and the C++ header is simply absent, so point it at the Vulkan-Headers checkout explicitly.
test -f "$KHRONOS/include/vulkan/vulkan.hpp" || { echo "FATAL: no vulkan.hpp in $KHRONOS"; exit 1; }

rm -rf "$BUILD"
cmake -B "$BUILD" -S "$SRC" -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/usr/bin/ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$KHRONOS" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
  -DVulkan_GLSLC_EXECUTABLE="$NDK/shader-tools/linux-x86_64/glslc" \
  -DVulkan_INCLUDE_DIR="$KHRONOS/include" \
  -DGGML_VULKAN=ON \
  -DGGML_BACKEND_DL=ON \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_RPC=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DBUILD_SHARED_LIBS=ON

cmake --build "$BUILD" --target llama-server -j"$(nproc)"

echo "=== ARTIFACTS ==="
ls -la "$BUILD/bin/"
echo "BUILD_OK"
