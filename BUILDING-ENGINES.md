# Building the llama.cpp engine binaries for TurboLLM Android

How the `llama-server` binaries that ship inside the APK are produced. Two builds are
described: **Build A**, the CPU engine that currently ships, and **Build B**, the
Vulkan (GPU) engine, which now compiles cleanly but **crashed on both devices tested and is
not shipped** — see [where that leaves it](#where-that-leaves-the-vulkan-engine) before
spending time on it.

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
- **Want the Vulkan engine?** Run the script in
  [Build B](#build-b--the-vulkan-gpu-engine) inside WSL. Not on Windows — five Windows
  attempts failed, and the reason is structural, not incidental.
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
adb forward tcp:7799 tcp:7000 && curl -s http://127.0.0.1:7799/api/v1/engines
```

Last run of exactly that: daemon booted, UI served HTTP 200, and both engines registered
(`llama-server-android` and `llama-server-android-vulkan`).

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

Note there is no `libggml-vulkan.so` in that list. The Vulkan engine has not shipped.

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

**Status: compiles and links cleanly; runs on-device; but the Vulkan backend crashes on both
devices tested, and its mere presence breaks CPU-only operation on one of them. Not shipped.**

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

### The build script

The working copy is committed at `scripts/build-vulkan-engine.sh`; it is reproduced in
full here so this document stands alone. The staging/strip step is `scripts/stage-vulkan-engine.sh`.

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

**Do not ship the Vulkan build on the strength of the compile alone.** It costs +38 MB and
`minSdk` 24→28, and on both devices tested it delivers nothing — on one of them it is actively
worse than the CPU-only engine. Re-test on an Adreno device reporting Vulkan 1.2+ before
revisiting, and treat "it builds" and "it works" as two separate claims.

**Still NOT verified:** any speedup from Vulkan on any device, and whether the engine behaves when
bundled in the APK rather than run from `/data/local/tmp`.

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

### minSdk vs. the Vulkan engine

The app is `minSdk = 24` (`composeApp/build.gradle.kts`). The Vulkan engine requires
API 28. Unresolved. Three options:

| Option | Cost |
|---|---|
| Raise `minSdk` to 28 | Drops Android 7.0–8.1 devices entirely |
| Ship both engines and select at runtime | Requires static builds (`-DBUILD_SHARED_LIBS=OFF`) to dodge the flat-directory collision in [gotcha 1](#gotcha-1-ggml_backend_dlon-means-one-engine-not-two); roughly doubles APK native payload |
| Vulkan-only, keep `minSdk = 24` | **Not acceptable** — the engine would simply fail to start on API 24–27 |

### Also open

- The Vulkan build has never been executed on a device. Nothing about its runtime behavior
  or performance is known.
- The Vulkan artifacts are unstripped and much larger than the CPU set. APK-size impact is
  unmeasured.
- The Vulkan build has only ever been produced by hand in WSL. There is no CI workflow for
  it, and the script is not yet committed (see the recommendation in
  [Build B](#the-build-script)).
