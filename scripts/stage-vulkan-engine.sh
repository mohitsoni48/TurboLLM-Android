#!/usr/bin/env bash
# Stage the Vulkan build's artifacts into the shape the APK wants:
#   - strip them (the build leaves debug_info in; unstripped they are ~230 MB)
#   - rename the executable to libllama_server.so, since Android only extracts and
#     permits execution of files matching lib*.so inside nativeLibraryDir
set -euo pipefail

STRIP=/root/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
BIN=/root/vk/build-vk/bin
OUT=/root/vk/out

rm -rf "$OUT"
mkdir -p "$OUT"

# vulkan-shaders-gen is a HOST (x86_64 Linux) build tool, not part of the engine — never ship it.
for f in "$BIN"/*.so; do cp "$f" "$OUT"/; done
cp "$BIN/llama-server" "$OUT/libllama_server.so"

for f in "$OUT"/*.so; do "$STRIP" --strip-unneeded "$f"; done

ls -la "$OUT"
echo "TOTAL:"
du -sh "$OUT"
echo "STAGE_OK"
