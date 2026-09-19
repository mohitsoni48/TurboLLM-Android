#!/usr/bin/env bash
# ┌───────────────────────────────────────────────────────────────────────────────────────┐
# │ DYNAMIC-BACKEND VARIANT — DO NOT SHIP WHAT THIS STAGES.                               │
# │ It stages build-vulkan-engine.sh's output, which must never be packaged. The engine    │
# │ that ships is built AND installed into jniLibs by scripts/build-static-engine.sh.      │
# └───────────────────────────────────────────────────────────────────────────────────────┘
#
# Why not: Android's nativeLibraryDir is FLAT, and this set carries libggml.so / libllama.so
# / libllama-server-impl.so under the same names the shipping CPU engine uses — they cannot
# coexist. Worse, measured on the Galaxy S21 FE (Mali-G78), libggml-vulkan.so merely being
# present in that directory kills the CPU engine during load_model even at -ngl 0. Note this
# script also renames the executable to libllama_server.so, i.e. straight over the CPU
# engine. See BUILDING-ENGINES.md § "Build B".
#
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
