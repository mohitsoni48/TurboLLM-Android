#!/usr/bin/env bash
# Build the Vulkan engine as a SINGLE STATIC binary and install it into this repo's jniLibs.
#
# THIS is the variant that ships. scripts/build-vulkan-engine.sh builds the dynamic-backend
# variant, which must never be packaged — see the header on that file.
#
# Why static and not the shared build:
#   1. nativeLibraryDir is FLAT. The shared Vulkan build emits libggml.so / libllama.so /
#      libllama-server-impl.so / ... under exactly the same names the shipping CPU engine
#      uses, so the two cannot coexist there at all.
#   2. Measured on the Galaxy S21 FE (Mali-G78): with libggml-vulkan.so merely PRESENT in
#      the directory, llama-server dies during load_model even at -ngl 0. Moving that one
#      file aside makes the identical binary load and serve in ~2 s. A shared Vulkan build
#      therefore doesn't just fail to help — it takes the CPU engine down with it.
# Linking Vulkan into one self-contained executable keeps that blast radius to the Vulkan
# engine alone: if it crashes, the user switches back and CPU still works.
#
# GGML_BACKEND_DL must be OFF here — the runtime backend loader is a shared-library
# mechanism and is incompatible with BUILD_SHARED_LIBS=OFF.
#
# Run it under WSL, not Windows (BUILDING-ENGINES.md § "Why WSL and not Windows" — the
# cross-compile also builds host tools that need a GCC-style host compiler). The script
# finds the repo from its own location, so either checkout works:
#
#   wsl -d Ubuntu-24.04 -u root -- bash "/mnt/d/llama-turbo/TurboLLM Android/scripts/build-static-engine.sh"
#   DRY_RUN=1 bash scripts/build-static-engine.sh   # print resolved paths + commands, build nothing
#
# Every path defaults to the /root/vk layout and every one is an env override:
#   NDK= SRC= KHRONOS= BUILD= OUT= BACKUP_DIR=
#
# The previously built engine is BACKED UP before anything is deleted. The original WSL
# script's first act was `rm -rf "$OUT"`, which is how a verified engine got destroyed once;
# the binary in Play build 1130603 survived only because it had already been copied out of
# there by hand. Nothing is written into jniLibs until the build AND every check pass.
set -euo pipefail

NDK=${NDK:-/root/ndk/android-ndk-r29}
SRC=${SRC:-/root/vk/llama.cpp}
KHRONOS=${KHRONOS:-/root/vk/khronos}
BUILD=${BUILD:-/root/vk/build-vk-static}
OUT=${OUT:-/root/vk/out-static}
BACKUP_DIR=${BACKUP_DIR:-/root/vk/verified-prev}
DRY_RUN=${DRY_RUN:-0}

# Named _vk so it sits beside the CPU engine's libllama_server.so without clashing, and
# lib*.so because Android only extracts and permits execution of that shape.
ENGINE=libllama_server_vk.so

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
JNILIBS="$REPO_ROOT/composeApp/src/androidMain/jniLibs/arm64-v8a"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
GLSLC="$NDK/shader-tools/linux-x86_64/glslc"
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
STRIP="$LLVM_BIN/llvm-strip"
READELF="$LLVM_BIN/llvm-readelf"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP="$BACKUP_DIR/$STAMP"

# A backup inside the repo would be a 38 MB binary in a gitignored tree that the next
# `git clean -xdf` deletes — i.e. not a backup at all.
case "$BACKUP_DIR/" in
    "$REPO_ROOT"/*)
        echo "FATAL: BACKUP_DIR must be OUTSIDE the repo, got $BACKUP_DIR" >&2
        exit 1
        ;;
esac

CMAKE_CONFIGURE=(
    cmake -B "$BUILD" -S "$SRC" -G Ninja
    -DCMAKE_MAKE_PROGRAM=/usr/bin/ninja
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
    -DANDROID_ABI=arm64-v8a
    # android-28, not the CPU engine's 24, and forced rather than chosen: ggml-vulkan calls
    # vkGetPhysicalDeviceFeatures2, whose symbol only appears in the API-28 stub. The
    # consequence is that a Vulkan-enabled engine cannot run below Android 9.
    -DANDROID_PLATFORM=android-28
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_PREFIX_PATH="$KHRONOS"
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
    -DVulkan_GLSLC_EXECUTABLE="$GLSLC"
    # The NDK sysroot ships Vulkan's C header but NOT the C++ bindings that ggml-vulkan.cpp
    # includes, so point cmake at the Vulkan-Headers checkout explicitly.
    -DVulkan_INCLUDE_DIR="$KHRONOS/include"
    -DGGML_VULKAN=ON
    -DGGML_BACKEND_DL=OFF
    -DGGML_NATIVE=OFF
    -DGGML_OPENMP=OFF
    -DGGML_RPC=OFF
    -DLLAMA_BUILD_UI=OFF
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DBUILD_SHARED_LIBS=OFF
)
CMAKE_BUILD=(cmake --build "$BUILD" --target llama-server -j"$(nproc 2>/dev/null || echo 4)")

# ── Resolved configuration ────────────────────────────────────────────────────────────────
echo "=== build-static-engine.sh ==="
printf '  %-11s %s\n' \
    NDK "$NDK" \
    SRC "$SRC" \
    KHRONOS "$KHRONOS" \
    BUILD "$BUILD" \
    OUT "$OUT/$ENGINE" \
    BACKUP "$BACKUP" \
    REPO_ROOT "$REPO_ROOT" \
    INSTALL "$JNILIBS/$ENGINE"

# In DRY_RUN a missing prerequisite is reported rather than fatal, so the path resolution
# above can be checked from a machine that has no NDK at all.
require() {
    local path=$1 what=$2
    if [ -e "$path" ]; then
        if [ "$DRY_RUN" = 1 ]; then printf '  ok        %s\n' "$what"; fi
        return 0
    fi
    if [ "$DRY_RUN" = 1 ]; then printf '  MISSING   %s (%s)\n' "$what" "$path"; return 0; fi
    echo "FATAL: missing $what: $path" >&2
    exit 1
}

echo "--- prerequisites ---"
require "$TOOLCHAIN" "NDK cmake toolchain"
require "$GLSLC" "NDK glslc"
require "$STRIP" "llvm-strip"
require "$READELF" "llvm-readelf"
require "$KHRONOS/include/vulkan/vulkan.hpp" "vulkan.hpp (Vulkan-Headers)"
require "$SRC" "llama.cpp checkout"
require "$REPO_ROOT/composeApp/build.gradle.kts" "repo root (resolved from this script)"

# ── What the binary should claim to be ────────────────────────────────────────────────────
SRC_COMMIT=$(git -C "$SRC" rev-parse --short=7 HEAD 2>/dev/null || true)
if [ -z "$SRC_COMMIT" ]; then
    echo "WARNING: $SRC is not a git checkout — the embedded-commit check will be SKIPPED."
elif [ -n "$(git -C "$SRC" status --porcelain 2>/dev/null)" ]; then
    echo "WARNING: $SRC has uncommitted changes — commit $SRC_COMMIT does not describe what"
    echo "         will actually be built, and the result is not reproducible from git."
fi
echo "  source commit: ${SRC_COMMIT:-unknown}"

if [ "$DRY_RUN" = 1 ]; then
    echo "--- commands (DRY_RUN=1, nothing will be built) ---"
    printf '  rm -rf %q\n' "$BUILD"
    printf '  '; printf '%q ' "${CMAKE_CONFIGURE[@]}"; printf '\n'
    printf '  '; printf '%q ' "${CMAKE_BUILD[@]}"; printf '\n'
    printf '  cp %q %q\n' "$BUILD/bin/llama-server" "$OUT/$ENGINE"
    printf '  %q --strip-unneeded %q\n' "$STRIP" "$OUT/$ENGINE"
    printf '  cp %q %q\n' "$OUT/$ENGINE" "$JNILIBS/$ENGINE"
    echo "DRY_RUN_OK"
    exit 0
fi

# ── Back up whatever is already verified, BEFORE deleting anything ────────────────────────
echo "--- backup ---"
backed_up=0
for previous in "$OUT/$ENGINE" "$JNILIBS/$ENGINE"; do
    if [ -f "$previous" ]; then
        label=$(basename "$(dirname "$previous")")
        mkdir -p "$BACKUP/$label"
        cp -p "$previous" "$BACKUP/$label/$ENGINE"
        printf '  saved %s -> %s\n' "$previous" "$BACKUP/$label/$ENGINE"
        backed_up=$((backed_up + 1))
    fi
done
if [ "$backed_up" = 0 ]; then
    echo "  no previously built engine found — nothing to back up"
fi

# ── Build ─────────────────────────────────────────────────────────────────────────────────
echo "--- build ---"
rm -rf "$BUILD"
"${CMAKE_CONFIGURE[@]}"
"${CMAKE_BUILD[@]}"

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$BUILD/bin/llama-server" "$OUT/$ENGINE"
"$STRIP" --strip-unneeded "$OUT/$ENGINE"

# ── Verify, and only then install ─────────────────────────────────────────────────────────
BIN="$OUT/$ENGINE"
failures=0
note_failure() { echo "  FAIL: $1"; failures=$((failures + 1)); }

echo "--- verification ---"

# Here-strings rather than `printf ... | grep -q`: with `set -o pipefail` a -q grep exits on
# the first match, the writer takes SIGPIPE, and the pipeline reports 141 — a *successful*
# check would read as a failure.
header=$("$READELF" -h "$BIN")
{ grep -E '^ +(Class|Data|Machine|Type):' <<<"$header" || true; } | sed 's/^ */  /'
grep -q 'Class: *ELF64' <<<"$header" || note_failure "not ELF64"
grep -q 'Machine: *AArch64' <<<"$header" || note_failure "not AArch64 — wrong toolchain or ABI"

# A static engine may only need the platform's own libraries. Anything else here means the
# build silently went dynamic again, which is the failure this whole script exists to avoid.
ALLOWED_NEEDED=" libm.so libdl.so libvulkan.so libc.so liblog.so "
needed=$("$READELF" -d "$BIN" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
echo "  NEEDED: $(printf '%s' "$needed" | tr '\n' ' ')"
while IFS= read -r lib; do
    [ -n "$lib" ] || continue
    case "$ALLOWED_NEEDED" in
        *" $lib "*) ;;
        *) note_failure "non-system shared dependency: $lib (expected a self-contained binary)" ;;
    esac
done <<<"$needed"

# llama.cpp compiles its short commit hash in via build-info; `strings` would work but tr
# needs no binutils and finds the same NUL-delimited literal. Counted rather than `grep -q`
# for the SIGPIPE reason above — here it matters, the input is 38 MB.
if [ -n "$SRC_COMMIT" ]; then
    if [ "$(LC_ALL=C tr -c '[:print:]' '\n' <"$BIN" | grep -Fxc "$SRC_COMMIT")" != 0 ]; then
        echo "  commit: $SRC_COMMIT embedded, matches $SRC HEAD"
    else
        echo "  embedded 7-hex candidates: $(LC_ALL=C tr -c '[:print:]' '\n' <"$BIN" | grep -Ex '[0-9a-f]{7}' | sort -u | tr '\n' ' ')"
        note_failure "binary does not carry $SRC HEAD ($SRC_COMMIT) — it is a stale build"
    fi
else
    echo "  commit: SKIPPED (no git checkout at $SRC)"
fi

echo "  size:   $(stat -c %s "$BIN") bytes"
echo "  sha256: $(sha256sum "$BIN" | cut -d' ' -f1)"

if [ "$failures" -ne 0 ]; then
    echo "FATAL: $failures check(s) failed — jniLibs left untouched." >&2
    exit 1
fi

# ── Install ───────────────────────────────────────────────────────────────────────────────
# Via a temp name so an interrupted copy can never leave a truncated engine in place of the
# working one — the packaging guard in composeApp/build.gradle.kts checks existence, not size.
echo "--- install ---"
mkdir -p "$JNILIBS"
cp "$BIN" "$JNILIBS/$ENGINE.tmp"
mv -f "$JNILIBS/$ENGINE.tmp" "$JNILIBS/$ENGINE"
echo "  installed $JNILIBS/$ENGINE"
echo "  package it with: ./gradlew :composeApp:bundleRelease -PturbollmVulkanEngine=true"
echo "STATIC_OK"
