# Building the llama.cpp engine binaries for TurboLLM Android

How the `llama-server` binaries that ship inside the APK are produced. Two builds are
described: **Build A**, the CPU engine that is in every build, and **Build B**, the
Vulkan (GPU) engine, which ships **only behind `-PturbollmVulkanEngine=true`** and went out
that way in Play build 1130603 — one static binary built from fork pin `72d4bcd`, which
carries the fix for the crashes described below. It is **not device-verified** as of
2026-09-19: see [where that leaves it](#where-that-leaves-the-vulkan-engine).

Almost every non-obvious step below exists because something failed without it. The
failure and its exact error string are given each time — those strings are what you will
paste into a search box in three months.

---

## What you actually need to do

- **Just building the app?** Nothing special — `./gradlew assembleDebug` stages the Node
  daemon into the APK for you (`stageDaemonAssets`). It only needs the `turbollm` checkout
  beside this repo, or `-Pturbollm.dir=<path>`. See
  [the daemon payload](#the-daemon-payload--what-makes-the-apk-self-sufficient) — read that
  before touching anything about how the daemon is packaged, because the failure mode is an
  APK that builds and installs cleanly and then dies at first launch.
- **Shipping a new CPU engine?** You don't build it by hand. Bump `LLAMA_BUILD` in
  `turbollm/src/engines/download.ts`, push to `main`, and the
  [`android-llama-build.yml`](../.github/workflows/android-llama-build.yml) workflow
  cross-compiles and publishes it. Then copy the arm64 archive's contents into
  `composeApp/src/androidMain/jniLibs/arm64-v8a/`, renaming `llama-server` →
  `libllama_server.so`. See [Build A](#build-a--the-cpu-engine-what-currently-ships).
- **Want the Vulkan engine?** Run `scripts/build-static-engine.sh` inside WSL — not on
  Windows, where five attempts failed for a structural reason, not an incidental one. It
  builds, verifies and installs `libllama_server_vk.so` into `jniLibs/arm64-v8a/` itself;
  then build with `-PturbollmVulkanEngine=true`. Skip the script and that flag fails the
  build rather than quietly shipping a CPU-only bundle. See
  [Build B](#build-b--the-vulkan-gpu-engine).
- **LiteRT-LM?** Nothing to do — `assembleDebug`/`bundleRelease` fetch the pinned runtime and
  build its server with the NDK AGP already uses (`stageLitertLmEngine`, needs network on the
  first build). `-PturbollmLitertLmEngine=false` leaves it out. See
  [the LiteRT-LM engine](#the-litert-lm-engine).
- **Before you change anything about packaging**, read
  [the platform constraint](#the-platform-constraint-that-shapes-everything). It is the
  reason the binaries are bundled rather than downloaded, the reason they are named
  `lib*.so`, and the reason `useLegacyPackaging = true` is not optional.

---

## The daemon payload — what makes the APK self-sufficient

The engine binaries are only half of what ships inside the APK. The other half is the **Node
daemon**, and it has to be in there too: someone installing from the Play Store has no `adb`
and no `npm install`.

**You do not run anything by hand.** `./gradlew assembleDebug` (or `bundleRelease`) runs
`scripts/stage-daemon-assets.mjs` first, via the `stageDaemonAssets` task wired up in
`composeApp/build.gradle.kts`. It is attached to the **`merge*Assets`** tasks — not `preBuild`,
which Gradle can skip past — and `assemble*`/`bundle*` additionally fail if the payload is
missing, so a broken APK cannot get as far as a device.

The script expects the `turbollm` checkout as a sibling of this repo. If it lives elsewhere:

```bash
./gradlew assembleDebug -Pturbollm.dir=/path/to/turbollm
# or put turbollm.dir=/path/to/turbollm in gradle.properties
```

It rebuilds the daemon bundle every time (~200 ms) rather than trusting what is on disk, so the
APK can never ship a daemon older than the source. It does **not** rebuild the web UI — that is
slower and changes less often, so run `npm run build` in `turbollm/web` when the UI changes.

### Why the payload is built specially instead of just copying `dist/`

Because copying `dist/` does not work, and fails in a way that looks fine until a user installs
it. The npm build deliberately leaves `dependencies` **external** — correct for npm, which
installs them — so a fresh install crashed at boot with:

```
Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@hono/node-server'
    imported from /data/data/com.turbollm.android/files/daemon/dist/cli.js
```

This went unnoticed for a long time because dev sessions had pushed extra files onto the test
device by hand, so the app only ever ran against injected state a real download never has.

Shipping `node_modules` instead is not an option either: the production tree is **362 MB**,
about 220 MB of which is `@earendil-works/pi-*` plus its `@mistralai`/`@google`/`openai`/
`@opentelemetry` transitives — the Code feature, which Android does not even expose. So
`turbollm/tsup.android.config.ts` is a **separate build pass** that bundles the dependencies in.
Total payload: **14.5 MB**.

Four things in that config are load-bearing, each one a crash that was hit and fixed:

| Setting | Without it |
|---|---|
| `target: 'node18'` | nodejs-mobile embeds Node **18.20.4**, not the 22 the desktop package targets — syntax the phone cannot parse |
| `banner` defines `require` | bundled CommonJS keeps esbuild's `__require` shim, which throws: `Dynamic require of "assert" is not supported` (undici) |
| `banner` defines `__dirname` | `ReferenceError: __dirname is not defined in ES module scope` — sql.js locates its own `.wasm` that way |
| `dist/package.json` = `{"type":"module"}` | Node decides ESM by walking up for the nearest `package.json`; without one above the extracted bundle it reads the ESM entrypoint as CommonJS and dies on `import` |

Two files cannot be bundled and are staged beside the bundle instead:

- **`sql-wasm.wasm`** — a binary sql.js loads at runtime via `__dirname + "/"`. Only the wasm is
  copied; the package's other ~23 MB is asm.js, browser and debug variants.
- **`node_modules/ws`** — reached through a real runtime `require('ws')`, which esbuild cannot
  bundle. The source has exactly three bare requires: `net` (a builtin), `node-pty` (native, and
  only used by the Code feature that Android cuts) and this one.

### On the device

`MainActivity.extractDaemonFromAssets()` unpacks `assets/daemon` into `files/daemon` on first
launch, keyed on the APK's `lastUpdateTime` so an app update cannot be shadowed by a stale copy.
`~/.turbollm` — models, chat history — lives outside `daemon/` and is never touched. A failed
extraction leaves no stamp behind, so the next launch retries instead of booting a half-written
tree.

The staged directory is **gitignored**: it is build output that would otherwise churn on every
daemon change.

### How to verify self-sufficiency (do this before any release)

Injected state is invisible and will lie to you. The only honest test is a full wipe:

```bash
adb uninstall com.turbollm
adb shell "run-as com.turbollm ls files"   # must say: unknown package
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell am start -n com.turbollm/com.turbollm.app.MainActivity
# then, with NOTHING pushed:
adb logcat -d | grep -E "E nodejs|TurboLLM:"
adb forward tcp:7799 tcp:7000 && curl -s http://127.0.0.1:7799/healthz   # {"status":"ok","version":…}
adb shell "run-as com.turbollm cat files/daemon.log" | grep "registered bundled engine"
```

Last recorded run (before the app secret, when the check still read `/api/v1/engines`): daemon
booted, UI served HTTP 200, and both engines registered (`llama-server-android` and
`llama-server-android-vulkan`).

`/api/v1/engines` over the adb forward now answers 401, and that is not a failed boot. The app
starts the daemon with a per-launch secret (`TURBOLLM_APP_SECRET`) that only the app's own
WebView and client carry, so adb, curl and Termux count as another app on the phone and need an
API key like any LAN client. `/healthz` stays open, and on the first launch after a clean
install, as in the procedure above, the daemon logs one `android: registered bundled engine …`
line per engine to `files/daemon.log`. Only that launch registers them: later boots log
`android: refreshed bundled engine …` or `android: repaired stale engine path …` for an engine
whose path or version changed and nothing for the others, and `daemon.log` is truncated each
time Node starts. So on a phone that already had the app, an empty grep does not mean the
engines are missing. To query the API itself, create a key in the app (Customize → Connect →
API keys) and send it as `X-TurboLLM-Auth: <key>`.

---

## The platform constraint that shapes everything

Android's W^X hardening (API 29+) means **an app may not `execve()` a file it wrote into
its own storage**. The only exempt location is the installed APK's `nativeLibraryDir`.

Consequences, all of them load-bearing:

1. **Engine binaries must be bundled into the APK as jniLibs.** They cannot be downloaded
   at runtime, no matter how convenient that would be. This was proven on-device: the
   identical binary that failed to execute from app storage ran fine under `run-as`.
2. **Never use `run-as` to "verify" that a downloaded engine works.** `run-as` is an
   elevated debug shell and is not subject to the same restriction, so it gives a **false
   pass**. A binary that runs under `run-as` tells you nothing about whether the app can
   run it.
3. **Files must be named `lib*.so`.** Android only extracts and grants execute permission
   to jniLibs entries matching that pattern. The server executable is therefore shipped as
   **`libllama_server.so`**, not `llama-server`.
4. **`useLegacyPackaging = true` is required** in `composeApp/build.gradle.kts`:

   ```kotlin
   packaging {
       jniLibs {
           useLegacyPackaging = true
       }
   }
   ```

   Without it, AGP leaves the jniLibs uncompressed inside the APK and mmaps them instead
   of extracting them at install time — so `nativeLibraryDir` comes up **empty** at
   runtime and there is nothing to execute.
5. **`nativeLibraryDir` is a flat directory.** Every `.so` from every engine lands in the
   same folder with no namespacing. This matters in
   [gotcha 1](#gotcha-1-ggml_backend_dlon-means-one-engine-not-two).

### What is currently bundled

`composeApp/src/androidMain/jniLibs/arm64-v8a/` (as of 2026-09-05, CPU engine):

| File | Size | Notes |
|---|---|---|
| `libllama_server.so` | 4.8 KB | the `llama-server` executable, renamed |
| `libllama-server-impl.so` | 3.3 MB | |
| `libllama-common.so` | 4.2 MB | |
| `libllama.so` | 2.6 MB | |
| `libmtmd.so` | 1.0 MB | multimodal |
| `libggml.so` | 668 KB | |
| `libggml-base.so` | 1.0 MB | |
| `libggml-cpu.so` | 868 KB | |
| `libomp.so` | 974 KB | **from the NDK, not from the build output** — see Build A step 3 |

Plus `libllama_server_vk.so` (38.6 MB) once [Build B](#build-b--the-vulkan-gpu-engine) has been
run — packaged only with `-PturbollmVulkanEngine=true`. Note there is still no
`libggml-vulkan.so` anywhere: the Vulkan backend is linked *into* that single file rather than
shipped beside it, which is the whole point of [gotcha 1](#gotcha-1-ggml_backend_dlon-means-one-engine-not-two).

The whole directory is gitignored, so none of these are in a fresh clone.

---

## The LiteRT-LM engine

Termux users get their best speed from LiteRT-LM, which TurboLLM runs there as
`python -m litert_lm_cli.main serve`. The app can't: it has no Python, and it may only execute
files that shipped in its APK. But that Python server is a thin layer — every inference call
goes through ctypes into **one** native library, `liblitert-lm.so`, the only binary in Google's
`litert-lm-api` wheel. So the app ships that same library unchanged and replaces only the layer
above it:

| File (jniLibs/&lt;abi&gt;/) | What it is | Where it comes from |
|---|---|---|
| `liblitert-lm.so` | Google's LiteRT-LM runtime (CPU + GPU) | Extracted from the `litert-lm-api` 0.18.0 Android wheel on PyPI, **sha256-pinned** in `scripts/stage-litert-lm-engine.mjs`. Byte-identical to what Termux runs. Apache-2.0. |
| `liblitertlm_server.so` | Native port of `litert-lm serve` — an executable named like a library so Android installs it | Built from `engines/litert-lm-server/` with the NDK on every build |

`stageLitertLmEngine` (`composeApp/build.gradle.kts`) does both, then checks each file's ELF
architecture and that every LOAD segment is 16 KB-aligned (the wheel's library already is).
Output goes to `composeApp/build/generated/litert-lm/jniLibs/`, never into `src/`. Cost: about
14 MB compressed per ABI.

### What the server is, and why it is shaped this way

`engines/litert-lm-server/src/main.cc` is a translation of the Python serve modules
(`litert_lm_cli/commands/serve/*`), keeping the contract the daemon already speaks:
`serve --config <json> --host <h> --port <p>`, the model path in each request's `model` field,
lazy engine creation on the first chat request, and the runtime's error in the HTTP status line
(where the daemon's warm-up reads it). The daemon registers it as kind `litert-lm`
(`engines/seed.ts`, name `litert-lm-android`), so its config file, warm-up and request handling
apply unchanged; only the launch command, the absent Python preflight and the absent PyPI
updates differ (`isNativeLitertLm` in `engines/litert-lm.ts`).

It was checked against the real Python server on Linux x86-64 with the manylinux build of the
same library and LiteRT-LM's own `runtime/testdata/test_lm.litertlm`: identical generated text
and token counts (streaming and not, multi-turn, content parts, `,cpu` suffixes,
`reasoning_effort`), and the same status codes and messages for every error case tried. It
adds `/health`, llama.cpp-style `timings` from the runtime's own counters, `reasoning_content`
for thinking-channel text, `finish_reason: "length"` when the token budget runs out, and
cancels a generation when its client disconnects. Not ported: `/v1/responses`,
`/v1/embeddings` and http(s) image URLs (TurboLLM uses none of them with this engine).

**The library is loaded with `dlopen(RTLD_LOCAL)`, not linked**, and the server's own C++
runtime is static and unexported. `liblitert-lm.so` carries its own statically linked C++
runtime and *exports* it; when the server was linked against the library, the linker
satisfied the server's `std::runtime_error`/`__cxa_*` references from those exports, and
AddressSanitizer caught an exception being destroyed by the other runtime. See
`src/litert_lm_api.h`.

### GPU

The app reads the GPU's name from a throwaway GL context (`MainActivity.gpuEnv`) and passes it
as `TURBOLLM_ANDROID_GPU`. That is the counterpart of `vulkaninfo` under Termux — the one fact
that makes LiteRT-LM's default "auto" backend choose the GPU (`litertLmProfileToConfig`). The
server is spawned from `nativeLibraryDir` exactly like the llama.cpp engines, i.e. the same
kind of `/data` executable Termux's Python is, so it gets the same linker namespace for the
system Vulkan/OpenCL/GL libraries.

### Bumping LiteRT-LM

Change `LITERT_LM_VERSION` and both wheel hashes in `scripts/stage-litert-lm-engine.mjs`
(PyPI publishes them at `https://pypi.org/pypi/litert-lm-api/<version>/json`), refresh
`engines/litert-lm-server/third_party/litert_lm/c/*.h` from the matching
`google-ai-edge/LiteRT-LM` tag, and check that every function listed in
`src/litert_lm_api.h` still exists — the server refuses to start, naming the missing ones, if
it doesn't.

### Not yet verified

- **No device has run it.** The NDK cross-compile itself was not run while writing it (no NDK
  was available); the first `assembleDebug` is that check. The code uses only POSIX/bionic
  APIs available at API 24.
- GPU speed versus Termux on the same phone, and whether every GPU path (OpenCL, WebGPU/Vulkan)
  loads from an app-spawned process the way it does from Termux.

---

## Build A — the CPU engine (what currently ships)

Built in CI on `ubuntu-latest`, NDK **r29 (29.0.14206865)**, from
`.github/workflows/android-llama-build.yml`. Matrix: `arm64-v8a` (asset `arm64`, OpenMP
arch `aarch64`) and `x86_64` (asset `x64`, OpenMP arch `x86_64`).

The workflow triggers on a push to `main` that touches
`turbollm/src/engines/download.ts`, or via `workflow_dispatch` (with a `force_rebuild`
input that overrides the "release already exists" skip). It reads the llama.cpp tag out of
`download.ts`:

```bash
tag=$(grep -oP "export const LLAMA_BUILD = '\K[^']+" turbollm/src/engines/download.ts)
```

### 1. Clone the pinned llama.cpp

```bash
git clone --depth 1 --branch "$tag" https://github.com/ggml-org/llama.cpp llama.cpp-src
```

### 2. Configure and build

The NDK is preinstalled on GitHub-hosted Ubuntu runners; the workflow resolves it as
`${ANDROID_NDK_LATEST_HOME:-$ANDROID_NDK_HOME}` and fails loudly if neither exists.

```bash
cmake -B build -S llama.cpp-src \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build -j --target llama-server
```

These flags deliberately match `CMAKE_CONFIGURE_ARGS_ANDROID` in `build-prereqs.ts`, so a
CI-built engine and a user-built one are the same thing.

### 3. Package — including `libomp.so`, which is **not** in `build/bin`

```bash
mkdir -p out
cp build/bin/llama-server out/
cp build/bin/*.so* out/ 2>/dev/null || true
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/"*"/lib/linux/$OMP_ARCH/libomp.so" out/
tar -C out -czf "llama-$tag-bin-android-$ASSET_ARCH.tar.gz" .
```

`libomp.so` is the NDK's own OpenMP runtime that ggml's CPU backend links against. It
lives under the toolchain directory, not the build output. Confirmed live: a
`build/bin`-only archive is missing it and the binary fails to even start with

```
CANNOT LINK EXECUTABLE ... library "libomp.so" not found
```

### 4. Publish

The `publish` job creates a release tagged `android-<tag>` on this repo and uploads both
archives. The tag is independent of both upstream llama.cpp release tags and the app's own
`vX.Y.Z` tags, so Android tracks the exact same llama.cpp commit as every other platform
(ADR-390/391). The URL shape `download.ts`'s `availableBackends()` constructs:

```
https://github.com/<repo>/releases/download/android-<tag>/llama-<tag>-bin-android-<arch>.tar.gz
```

That download path is how the **desktop-style** provisioning flow works. On Android the
artifact is instead unpacked into `jniLibs/` and shipped in the APK, for the reason in
[the platform constraint](#the-platform-constraint-that-shapes-everything).

---

## Build B — the Vulkan (GPU) engine

**Status (2026-09-19): shipped in Play build 1130603 behind `-PturbollmVulkanEngine=true`, as a
single STATIC binary built from fork pin `72d4bcd` — the commit that fixed the crashes described
further down. Nobody has confirmed the fixed engine running on a device, and no speedup has been
measured anywhere, so "it ships" and "it works" remain two separate claims.**

The device evidence in this section predates both the fix and the static build. It is kept
because it is the reason the engine has the shape it has — one self-contained file — not because
it describes the binary that ships today.

### Why this was attempted

An outside contributor (`ciroprogamer/llama.cpp`, workflow
`.github/workflows/build-android-vulkan.yml`, discussed on PR
mohitsoni48/TurboLLM#203) showed Android Vulkan is possible. His fork contains **zero code
changes** — it is only a CI workflow, so there is no patch to port. His measured gain is
real but uneven: **"10 t/s to 30 t/s"** on one model, near-nothing on another. Treat that
as the honest expectation, not a promise.

### Why WSL and not Windows

Five Windows attempts failed, and the reason is structural. Cross-compiling llama.cpp also
builds two **host** tools — `llama-ui-embed` and `vulkan-shaders-gen` — which must run on
the build machine, and both need a GCC-style host compiler:

- The NDK's clang can't target Windows: first `inttypes.h` not found, then unresolved
  `-lkernel32`.
- The only MSVC present is `cl.exe`, whose flags don't match the `-O2 -std=c++17 -o`
  command `tools/ui/CMakeLists.txt` hardcodes.
- VS 2022 BuildTools ships only `clang-format` and `clang-tidy` — **no `clang++`**.
- `-DLLAMA_BUILD_UI=OFF` does **not** skip the embed tool.

Escape hatches exist (`-DHOST_CXX_COMPILER=<path>`,
`-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=<file>`) but they all still need a real host clang.

Under Linux, the NDK's own clang **is** a valid host compiler. That is exactly why upstream
CI does this on ubuntu, and why WSL just works.

### One-time WSL setup

Ubuntu-24.04 (`wsl -d Ubuntu-24.04`) already had `cmake`, `ninja`, `git`, `g++`, `curl`,
`unzip`.

The **Windows NDK cannot be reused** — its toolchain binaries are `windows-x86_64`. Fetch
the Linux NDK, deliberately the **same revision r29** as the CPU engine so both engines
share an ABI baseline:

```bash
mkdir -p /root/ndk && cd /root/ndk
curl -LO https://dl.google.com/android/repository/android-ndk-r29-linux.zip
unzip -q android-ndk-r29-linux.zip     # → /root/ndk/android-ndk-r29
```

Clone sources into **WSL's own filesystem** (`/root/vk/`), never `/mnt/c` — the 9p
filesystem bridge makes a build of this size painfully slow:

```bash
mkdir -p /root/vk && cd /root/vk
git clone https://github.com/ggml-org/llama.cpp
git clone https://github.com/KhronosGroup/Vulkan-Headers
git clone https://github.com/KhronosGroup/SPIRV-Headers

# Install both header packages to one prefix that cmake can be pointed at.
for r in Vulkan-Headers SPIRV-Headers; do
  cmake -B /root/vk/b-$r -S /root/vk/$r -DCMAKE_INSTALL_PREFIX=/root/vk/khronos
  cmake --install /root/vk/b-$r
done
```

### The build script — `scripts/build-static-engine.sh`

This is the one whose output ships. Run it inside WSL; it takes no arguments:

```bash
wsl -d Ubuntu-24.04 -u root -- bash "/mnt/d/llama-turbo/TurboLLM Android/scripts/build-static-engine.sh"

DRY_RUN=1 bash scripts/build-static-engine.sh   # resolved paths + the commands, builds nothing
```

It is the WSL original plus the four things that original did not do, each of which exists
because of something that went wrong:

- **Builds ONE static binary** — `-DBUILD_SHARED_LIBS=OFF` with `-DGGML_BACKEND_DL=OFF`, since
  the runtime backend loader is a shared-library mechanism and the two are incompatible. Same
  cmake flags as the dynamic variant otherwise, including the forced `android-28`.
- **Backs up the previous engine before deleting anything.** The original's first act was
  `rm -rf "$OUT"`, which is how a verified engine got destroyed once. Both the build output and
  the copy already in `jniLibs/` are saved to `/root/vk/verified-prev/<UTC timestamp>/` first —
  outside the repo, because a backup in a gitignored tree is one `git clean -xdf` from gone.
- **Verifies before installing, and installs nothing if a check fails**: ELF64/AArch64 from
  `llvm-readelf -h`; every `NEEDED` entry system-only (`libm`/`libdl`/`libvulkan`/`libc`/
  `liblog`, so a build that silently went dynamic again is caught); the commit string compiled
  into the binary matching `git -C $SRC rev-parse --short=7 HEAD`, which is what catches a stale
  binary being re-stamped as a fresh one; then size and sha256 for the record. It also warns if
  `$SRC` has uncommitted changes, because then the commit it reports describes nothing.
- **Installs it into `composeApp/src/androidMain/jniLibs/arm64-v8a/libllama_server_vk.so`**, the
  step that did not exist before — a built engine that never reaches `jniLibs` is not in the
  APK, and `-PturbollmVulkanEngine=true` only removes a packaging exclude, so the bundle came
  out CPU-only with nothing anywhere saying so. `requireVulkanEngineBinary` in
  `composeApp/build.gradle.kts` now fails that build up front instead.

The repo is located from the script's own path, so it works from the WSL checkout or from this
one mounted at `/mnt/d/...`. `NDK`, `SRC`, `KHRONOS`, `BUILD`, `OUT` and `BACKUP_DIR` are env
overrides on the `/root/vk` defaults.

### The dynamic variant — built, never shipped

`scripts/build-vulkan-engine.sh` (staged by `scripts/stage-vulkan-engine.sh`) builds the
`GGML_BACKEND_DL=ON` variant. **Its output must never be packaged** — see
[gotcha 1](#gotcha-1-ggml_backend_dlon-means-one-engine-not-two) and the S21 FE A/B below: it
collides with the CPU engine's `.so` names in the flat `nativeLibraryDir`, and on that device
`libggml-vulkan.so` merely being present killed the CPU engine. Both scripts carry that warning
in their headers. It is kept, and reproduced in full here, because every measurement in the rest
of this section came from it:

```bash
#!/usr/bin/env bash
# Cross-compile llama.cpp's server for Android arm64 WITH the Vulkan backend, inside WSL.
set -euo pipefail

NDK=/root/ndk/android-ndk-r29
SRC=/root/vk/llama.cpp
BUILD=/root/vk/build-vk
KHRONOS=/root/vk/khronos

test -f "$NDK/build/cmake/android.toolchain.cmake" || { echo "FATAL: no NDK toolchain at $NDK"; exit 1; }
test -x "$NDK/shader-tools/linux-x86_64/glslc" || { echo "FATAL: no glslc in NDK"; exit 1; }
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
```

Note `-DGGML_OPENMP=OFF`: unlike [Build A](#3-package--including-libompso-which-is-not-in-buildbin),
this build does **not** need `libomp.so` packaged alongside it.

### Result of the run in this session

The build **completed successfully**. The log ends at step `[634/634] Linking CXX
executable bin/llama-server`, followed by `BUILD_OK`, with no `ld.lld: error` or
`fatal error:` anywhere in the tail. Artifacts produced in `/root/vk/build-vk/bin/`:

| File | Size |
|---|---|
| `llama-server` | 7,112 B |
| `libggml-vulkan.so` | 50.8 MB |
| `libllama-common.so` | 61.2 MB |
| `libllama-server-impl.so` | 41.4 MB |
| `libllama.so` | 40.4 MB |
| `libmtmd.so` | 17.0 MB |
| `libggml-base.so` | 8.1 MB |
| `libggml-rpc.so` | 6.4 MB |
| `libggml-cpu.so` | 4.8 MB |
| `libggml.so` | 4.9 MB |
| `vulkan-shaders-gen` | 353 KB (host tool, not shipped) |

### After stripping

Those sizes are unstripped (the build leaves `debug_info` in). Run `scripts/stage-vulkan-engine.sh`
(reproduced below) to strip them and rename the executable. Stripped, the whole set is **53 MB**,
against **15 MB** for the CPU-only set that ships today — so bundling this costs about **+38 MB**,
and **37 MB of that is `libggml-vulkan.so` alone**, which carries embedded SPIR-V for every
quantisation type × every op.

| File (stripped) | Size |
|---|---|
| `libggml-vulkan.so` | 37.2 MB |
| `libllama-server-impl.so` | 5.9 MB |
| `libllama-common.so` | 4.3 MB |
| `libllama.so` | 3.3 MB |
| `libmtmd.so` | 1.3 MB |
| `libggml-base.so` | 1.1 MB |
| `libggml-cpu.so` | 939 KB |
| `libggml-rpc.so` | 780 KB |
| `libggml.so` | 669 KB |
| `libllama_server.so` (the executable) | 4.6 KB |

### Verified on hardware — and the result is a blocker on this device

Pushed to `/data/local/tmp/vk` and run directly under `adb shell` (NOT `run-as`, and not from app
storage — this was a capability test of the driver, deliberately kept away from the W^X question):

```
$ LD_LIBRARY_PATH=/data/local/tmp/vk ./libllama_server.so --version
ggml_vulkan: Error: Vulkan 1.2 required.
version: 0.4.0-dev (build 1, commit 6a1a922)
built with Clang 21.0.0 for Android aarch64

$ LD_LIBRARY_PATH=/data/local/tmp/vk ./libllama_server.so --list-devices
ggml_vulkan: Error: Vulkan 1.2 required.
Available devices:
  (none)
```

**What this proves:** the binary is genuinely Bionic-native and starts on Android. The build
pipeline in this document works end to end.

**What it also proves, and this is the important part:** llama.cpp's Vulkan backend requires
**Vulkan 1.2**, and the RMX1921 test device exposes only **1.1** (`vulkan.version=4198400` =
1.1.0, Adreno 616 / sdm710). On this handset the GPU backend can never initialise — it reports no
devices and the work runs on CPU.

The error is **non-fatal**: the binary continues and remains a perfectly good CPU engine. But it
means **the contributor's speedup cannot be reproduced or measured on this device.** Any phone
used to validate the GPU path must report Vulkan 1.2 or higher — broadly 2020-onward Snapdragon /
Mali parts with current drivers. Check before assuming a device is a fair test:

```
adb shell "pm list features | grep vulkan"   # want vulkan.version >= 0x00402000 (1.2)
```

### Second device — Vulkan initialises, then crashes (Galaxy S21 FE, Android 16, Mali-G78)

A newer handset was attached specifically to get past the Vulkan 1.2 wall: Samsung SM-G990E,
Android 16, Exynos 2100 / Mali-G78. Note its `pm list features` **also** reports
`vulkan.version=4198400` (1.1) — that feature flag understates the driver, so do not trust it
alone; the binary itself is the real test. Here the backend initialised properly:

```
ggml_vulkan: Found 1 Vulkan devices:
ggml_vulkan: 0 = Mali-G78 (Mali-G78) | uma: 1 | fp16: 1 | bf16: 0 | fp4: 0 |
             warp size: 16 | shared memory: 32768 | int dot: 0 | matrix cores: none
load_backend: loaded Vulkan backend from .../libggml-vulkan.so
Available devices:
  Vulkan0: Mali-G78 (6267 MiB, 6267 MiB free)
```

**And then it crashes as soon as the backend is actually exercised.** `llama-bench` segfaults at
both `-ngl 99` and `-ngl 0`; `llama-server` dies silently during `load_model` at both settings.

This was isolated with a controlled A/B — same binary, same model, same flags, the ONLY variable
being whether `libggml-vulkan.so` is present in the directory:

| `libggml-vulkan.so` | `llama-server -ngl 0` result |
|---|---|
| present | dies during `load_model`, no error printed |
| moved aside | `model loaded` → `listening on http://127.0.0.1:8099`, ~2 s |

So on this device the Vulkan backend's **mere presence breaks CPU-only operation too** — it is not
a case of "GPU doesn't help, falls back gracefully."

CPU baseline from the same device, with the Vulkan backend removed (Llama-3.2-1B-Instruct-Q4_K_M,
4 threads):

| test | t/s |
|---|---|
| pp512 | 27.93 ± 3.68 |
| tg128 | 15.77 ± 1.62 |

### Where that leaves the Vulkan engine

Two devices, two different failure modes, no measured speedup anywhere:

| Device | GPU | Result |
|---|---|---|
| Realme RMX1921, Android 11 | Adreno 616 | `Vulkan 1.2 required` — backend never initialises, harmless CPU fallback |
| Samsung SM-G990E, Android 16 | Mali-G78 | initialises and enumerates, then **crashes**; breaks CPU too |

Caveats, stated honestly: this is upstream llama.cpp at commit **6a1a922** (master at build time),
not whatever revision the contributor pinned — he may be on a commit that works, or on Adreno
hardware with a Vulkan 1.2+ driver, which is neither of the devices here. `matrix cores: none` and
`int dot: 0` also say Mali is a weak target for this backend even when it does run.

**What changed after the above was written.** The three null-pointer dereferences behind the
Mali-G78 failure were fixed in this project's llama.cpp fork —
`mohitsoni48/TurboLLM-Android-Llama`, pin **`72d4bcd`**, *"vulkan: fix three null-pointer crashes
on pre-1.2 devices reporting VK 1.2 features"* — and the engine was rebuilt as a single static
binary, which also removes the second failure mode above: there is no `libggml-vulkan.so` in
`nativeLibraryDir` any more to take the CPU engine down with it. That binary is what Play build
1130603 shipped, behind `-PturbollmVulkanEngine=true`.

**Still NOT verified, as of 2026-09-19:** that the fixed engine runs on the S21 FE or any other
handset; any speedup from Vulkan on any device; and how it behaves launched from
`nativeLibraryDir` rather than `/data/local/tmp`. It still costs +38 MB, and it is still opt-in
for exactly that reason. Note it did **not** raise `minSdk`, which is still 24 — the Vulkan
binary alone is linked against API 28, so on Android 7.0–8.1 it cannot start while the CPU
engine is unaffected (also untested). The next step is a device run, not another build:
install the 1130603 bundle, switch to the Vulkan engine, and watch whether a model loads.

---

## Gotchas

### Gotcha 1 — `GGML_BACKEND_DL=ON` means ONE engine, not two

With `-DGGML_BACKEND_DL=ON`, Vulkan and CPU become **separately loadable backends chosen
at runtime**, with CPU fallback. So the Vulkan build **replaces** the CPU-only engine; it
is not a second engine sitting beside it.

This is not merely convenient, it is close to forced. `nativeLibraryDir` is **flat**
([constraint 5](#the-platform-constraint-that-shapes-everything)), so two dynamic
llama.cpp builds in the same APK would collide on `libggml.so` and `libllama.so`. Shipping
genuinely separate engines would require static builds (`-DBUILD_SHARED_LIBS=OFF`).

That is exactly what `scripts/build-static-engine.sh` does, and why the Vulkan engine that
ships is one 38.6 MB file with no sidecars — and why `GGML_BACKEND_DL` has to be **OFF** there:
the runtime backend loader is a shared-library mechanism, so it cannot survive
`BUILD_SHARED_LIBS=OFF`. Static also bounds the damage. A dynamic Vulkan build that fails takes
the CPU engine with it; a static one that fails is one broken engine the user can switch away
from.

### Gotcha 2 — the NDK sysroot has no `vulkan.hpp`

The NDK sysroot ships Vulkan's **C** header but not the **C++** bindings, and
`ggml-vulkan.cpp` includes `<vulkan/vulkan.hpp>`. `find_package(Vulkan)` happily resolves
to the sysroot copy, and the build then dies with:

```
fatal error: 'vulkan/vulkan.hpp' file not found
```

Fix: point cmake at the Vulkan-Headers checkout explicitly.

```
-DVulkan_INCLUDE_DIR=/root/vk/khronos/include
```

### Gotcha 3 — `android-24` cannot link the Vulkan backend

Linking at the CPU engine's `android-24` fails with:

```
ld.lld: error: undefined symbol: vkGetPhysicalDeviceFeatures2
```

That is a Vulkan **1.1** entry point, and Android only exposes Vulkan 1.1 symbols in the
**API-28** stub library. So `-DANDROID_PLATFORM=android-28` is forced, not chosen — which
means **a Vulkan-enabled engine cannot run below Android 9**. Upstream's Android-Vulkan CI
pins 28 for the same reason.

---

## Device evidence (Realme RMX1921, Android 11, sdm710 / Adreno)

From `pm list features` and the system image:

- `android.hardware.vulkan.compute` — present (this is what ggml-vulkan needs)
- `android.hardware.vulkan.level=1`
- `android.hardware.vulkan.version=4198400` (= Vulkan 1.1)
- `/system/lib64/libvulkan.so` exists

This is **evidence, not proof**, that the contributor's Termux `vulkan-loader-android`
package is a **Termux-environment workaround** rather than something a packaged app needs:
a normal installed app can use the platform loader at `/system/lib64/libvulkan.so`.

**NOT YET VERIFIED: whether the Vulkan engine actually runs, and whether it is faster, on
this device.**

---

## Open decisions

### minSdk vs. the Vulkan engine — RESOLVED (option 2)

The app is `minSdk = 24` (`composeApp/build.gradle.kts`) and the Vulkan engine requires API 28.
The options were:

| Option | Cost |
|---|---|
| Raise `minSdk` to 28 | Drops Android 7.0–8.1 devices entirely |
| **Ship both engines and select at runtime** ← chosen | Requires static builds (`-DBUILD_SHARED_LIBS=OFF`) to dodge the flat-directory collision in [gotcha 1](#gotcha-1-ggml_backend_dlon-means-one-engine-not-two); +38 MB of native payload |
| Vulkan-only, keep `minSdk = 24` | **Not acceptable** — the engine would simply fail to start on API 24–27 |

Option 2, via the static build. `minSdk` stays at 24 and the CPU engine remains the default;
the Vulkan binary is a second, separately-selected executable that happens to need API 28.
**On an API 24–27 device the Vulkan engine is never offered.** The daemon registers a bundled engine
only if probing it (`--version`, then `--help`) succeeds (`engines/seed.ts` → `registry.add` →
`probe`), and the dynamic linker refuses this binary below API 28, so the probe fails, the
registration is skipped inside its own try/catch, and the CPU engine stays the only one. This is
established from the code, **not** from running it on an API 24–27 device.

### Also open

- No device has run the fixed Vulkan engine. Runtime behaviour and performance are still
  unknown — see [where that leaves it](#where-that-leaves-the-vulkan-engine).
- No CI workflow builds the Vulkan engine. `scripts/build-static-engine.sh` is committed and
  reproducible, but it is still run by hand in WSL, unlike Build A's
  [`android-llama-build.yml`](../.github/workflows/android-llama-build.yml).
- Bumping the fork pin is manual too: nothing notices when
  `mohitsoni48/TurboLLM-Android-Llama` moves ahead of the binary sitting in `jniLibs/`. The
  build script's commit check catches a stale binary only on the next rebuild.
