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
  daemon into the APK for you (`stageDaemonAssets`), and builds the web UI again first
  whenever `turbollm/web` has changed since its last build. It only needs the `turbollm`
  checkout beside this repo, or `-Pturbollm.dir=<path>`, with `npm ci` run in it and in its
  `web/`. See [the daemon payload](#the-daemon-payload--what-makes-the-apk-self-sufficient)
  — read that before touching anything about how the daemon is packaged, because the failure
  mode is an APK that builds and installs cleanly and then dies at first launch.
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
- **The engine tether?** Nothing to do — every build compiles `libtether_exec.so`, the helper
  that makes the kernel end each engine with the app, with the same NDK (`stageTetherExec`). See
  [the engine tether](#the-engine-tether).
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
APK can never ship a daemon older than the source. The web UI (`turbollm/src/webdist`, which git
does not hold) takes 40-55 s to build, so the script builds it only when `turbollm/web` is not
what it was last built from: it hashes every file under `web/` but `node_modules`,
dot-directories and test output (under 50 ms) and compares that with `build-inputs.json`, the
stamp it writes into `webdist` after each web build it runs. When they differ (after a pull or a
branch switch, or when the web UI was built by hand, which leaves no stamp) it runs
`npm run build` in `turbollm/web` before staging, so an APK never carries a web UI older than its
checkout. **You never need to build the web UI yourself for the app.** It does need
`turbollm/web/node_modules` as `turbollm/web/package-lock.json` has it: when that is missing or
out of date the build stops and names the command to run, `npm --prefix "<turbollm>/web" ci`.
`node --test scripts/stage-daemon-assets.test.mjs` covers the check on a stand-in checkout.

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

Four things in that build are load-bearing, each one a crash that was hit and fixed. The first
three are set in that config; the last is written by `stage-daemon-assets.mjs`:

| Setting | Without it |
|---|---|
| `target: 'node18'` | nodejs-mobile embeds Node **18.20.4**, not the 22 the desktop package targets — syntax the phone cannot parse |
| `banner` defines `require` | bundled CommonJS keeps esbuild's `__require` shim, which throws: `Dynamic require of "assert" is not supported` (undici) |
| `banner` defines `__dirname` | `ReferenceError: __dirname is not defined in ES module scope` — sql.js locates its own `.wasm` that way |
| `daemon/package.json` = `{"type":"module","version":…}` | Node decides ESM by walking up for the nearest `package.json`; without one above the extracted bundle it reads the ESM entrypoint as CommonJS and dies on `import`. The daemon also reads its version from the file's `version` field (`dist/../package.json`) and reports 0.1.1 when that field is missing |

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
engines are missing. Uninstalling first does not always make the next launch a first one
either: Android restores an app's Auto Backup whenever it is installed, `adb install` included,
if the backup was made by an APK signed with the same key (for the debug APK above, one of your
own earlier debug builds). On such a phone the restored `.turbollm/config.json` already lists
the engines, at the old install's library path. That launch then logs the `refreshed …` or
`repaired …` line for an engine whose path or version changed and nothing for the others, and
the grep above finds nothing. To keep the check a first registration, turn install-time restore
off around it: note what `adb shell settings get secure backup_auto_restore` prints (`1`, or
`null` if it was never changed, means on), run `adb shell bmgr autorestore false` before
`adb install`, and once done run `adb shell bmgr autorestore true` only if it was on, since it
is the phone's own Automatic restore setting. Or read the engines from the API. To query the
API itself, create a key in the app (Customize → Connect → API keys) and send it as
`X-TurboLLM-Auth: <key>`. Only a debug build lets an adb host skip the key: its WebView accepts
Chrome DevTools over adb, and script run there acts as the app. Release builds turn WebView
debugging off.

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
`litert-lm-api` wheel. So the app ships that same library, with one dependency renamed (see
[libandroid.so and its shim](#libandroidso-and-its-shim)), and replaces only the layer above it:

| File (jniLibs/&lt;abi&gt;/) | What it is | Where it comes from |
|---|---|---|
| `liblitert-lm.so` | Google's LiteRT-LM runtime (CPU + GPU) | Extracted from the `litert-lm-api` 0.18.0 Android wheel on PyPI, **sha256-pinned** in `scripts/stage-litert-lm-engine.mjs`. The build renames its `libandroid.so` dependency to `liblrtandr.so` (7 bytes of `.dynstr`) and checks the result against a second pinned hash; otherwise byte-identical to what Termux runs. Apache-2.0. |
| `liblitertlm_server.so` | Native port of `litert-lm serve` — an executable named like a library so Android installs it | Built from `engines/litert-lm-server/` with the NDK on every build |
| `liblrtandr.so` | Stands in for `libandroid.so`, which the engine process cannot load | Generated from the library's own imports and built with the NDK on every build |

`stageLitertLmEngine` (`composeApp/build.gradle.kts`) does all three, then checks each file's ELF
architecture and that every LOAD segment is 16 KB-aligned (the wheel's library already is).
Output goes to `composeApp/build/generated/litert-lm/jniLibs/`, never into `src/`. Cost: about
14 MB compressed per ABI.

### libandroid.so and its shim

`liblitert-lm.so` is built for apps, which load it into their own process, and it lists
`libandroid.so` among its dependencies. The server is not an app process: the daemon executes it
from `nativeLibraryDir`, and in a freshly executed process the linker has to find all of
`libandroid.so`'s dependencies itself (`libandroid_runtime`, `libhwui`, the media stack), which an
app, forked from the zygote, already has loaded. On the owner's phone that failed and no
`.litertlm` ever loaded:

```
cannot load liblitert-lm.so:
  .../lib/arm64/liblitert-lm.so: dlopen failed: library "libstagefright.so" not found: needed by /system/lib64/libmediandk.so in namespace (default)
```

The library uses `libandroid.so` only to read APK assets (`AAssetManager_*`, `AAsset_*`), and the
server loads models from file paths. So `scripts/stage-litert-lm-engine.mjs` replaces that
dependency, working from the library it ships rather than from a list written for one version:

1. It reads the library's dynamic section: its `DT_NEEDED` entries and every undefined dynamic
   symbol with its binding and type. `LOADABLE_LIBS` names the libraries a standalone process can
   load (bionic's, `liblog`, `libz`, EGL/GLES/Vulkan/`libnativewindow`); every other `DT_NEEDED`
   entry is replaced. Today that is `libandroid.so` alone.
2. Against the NDK's stub libraries, at every API level from the app's minSdk (24) to the newest
   the NDK has, it takes the GLOBAL imports only a replaced library provides (today 8 functions:
   `AAssetManager_fromJava`, `AAssetManager_open`, `AAssetManager_openDir`,
   `AAssetDir_getNextFileName`, `AAssetDir_close`, `AAsset_close`, `AAsset_getBuffer`,
   `AAsset_getLength`). The build fails, naming the symbols, if one of them is data rather than a
   function, if a GLOBAL import has no provider at all, if a GLOBAL import comes from both a
   replaced and a loadable library, if a WEAK one comes only from a replaced library, or if a
   replaced library's imports carry symbol versions, at any of those levels: a WEAK import that
   `libandroid.so` gained some levels before `libnativewindow.so` did would be unresolved on the
   devices in between, even though the minSdk and the newest stubs agree.
3. It writes a C shim per replaced library into the build directory and builds it with the NDK's
   clang: one function per import, each of which, if it is ever called, writes
   `liblitert-lm.so called <function>(), which libandroid.so provides, but libandroid.so cannot be
   loaded in the engine process (liblrtandr.so stands in for it); stopping.` to stderr (which the
   daemon shows when the engine exits) and to logcat, then calls `abort()`. None returns a value.
   The shim's file name has the replaced name's length, fits `lib*.so` and starts with `liblr`;
   the build checks that it exports exactly those functions and needs only loadable libraries. A
   shim an earlier run staged and this one does not is removed; nothing else in the output
   directory is touched, so pointing `--out` at `composeApp/src/androidMain/jniLibs` by hand
   leaves the llama.cpp engines there alone.
4. It renames the `DT_NEEDED` string in place (refusing if it is the tail of a longer string or
   if any symbol, version entry or other dynamic tag shares its bytes; the second copy of
   `libandroid.so` in `.rodata`, next to TFLite's NNAPI strings, is left alone) and checks the
   file against `patchedSha256`.
5. It links a probe executable against the staged library, the shim and the NDK stubs of the
   library's other dependencies with `-Wl,--no-allow-shlib-undefined`, so a GLOBAL import nothing
   in the library's dependency list provides fails the build with the linker's own message.

WEAK imports get no shim function: the library allows them to be absent. `AHardwareBuffer_*` is
the case that matters: `libandroid.so` defines those too from API 26, and with it gone they bind to
`libnativewindow.so`'s, which `libEGL.so` needs (checked on the Android 15 emulator image).

`node --test scripts/stage-litert-lm-engine.test.mjs` covers the reading, the checks, the rename,
the naming and the removal of stale shims on synthetic ELF files.

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

**Constrained decoding is on only for a request with tools**, which is all it is for: the data
processors that support it (Gemma 3's, Gemma 4's and FunctionGemma's) then constrain the model's
tool calls to the tools given; the others, Qwen3's for one, never do. The Python server turns it
on for every request (0.18.0 and upstream's main), and some data processors refuse it whatever
the request: LFM2's always, and Gemma 3's, Gemma 4's and FunctionGemma's when the FST constraint
library rejects the model's vocabulary (gemma-3-270m-it's does). Every request to such a model
fails with `litert-lm serve`, and did here. A request with tools to such a model is now answered
without the constraint (the model's own tool-call parsing still applies, and a call it writes in
a form that parsing cannot read fails the request, as for any model whose calls are not
constrained), and the server logs once per load that the model cannot constrain tool calls. A
`response_format` has its own constraint (llguidance) and works on these models too; one the
model cannot follow, or whose regex or JSON schema llguidance rejects, gets a 400 that names the
reason instead of a 500. `node --test engines/litert-lm-server/constrained-decoding.test.mjs`
checks this against a stand-in `liblitert-lm.so` that answers as 0.18.0 does for those models,
and on real models when `LITERT_LM_TEST_LIBRARY` and `LITERT_LM_TEST_MODELS` are set (see the
file).

**A model whose decoder keeps a recurrent state gets a new engine for each conversation after
the first.** LiteRT-LM 0.18.0 starts every new conversation on an engine at step 0 with the
executor's buffers as the last one left them: right for a KV cache, whose entries past the step
are masked, but not for LFM2's short-convolution cache, which is read as it is, so each chat
turn (each is a new request, and the daemon's title, memory and compaction requests come in
between) began from the end of the one before (LiteRT-LM #3165). The server tells such a model
by its own header: LlmMetadata's model type is LFM2, or ExecutorMetadata declares a
linear-attention state or LFM2's `kv_cache_c_*` caches (files packed as `generic_model` declare
those too). The C API cannot reset that state, so the engine is created again with the same
settings: 0.13 to 0.17 s per conversation for LFM2.5-230M on a 4-core x86-64 CPU with the
compiled-model cache, 0.9 s without it, and about 1.5 s for LFM2.5-VL-450M with the cache (its
decoder, not its vision encoder, takes that long); GPU unmeasured. The daemon's title, memory
and compaction requests are conversations too, and each pays it. Sending a request that only
extends the previous conversation to that conversation instead is not done: LiteRT-LM's own
multi-turn continuation does not prefill the tokens a fresh render of the same history does
(other token counts, other replies, for Gemma models too), so it would change what the model
answers. `node --test engines/litert-lm-server/model-settings.test.mjs` checks which models get
a new engine against the stand-in library, and on real models (see the file) that a
conversation's reply does not depend on the ones before it.

**A model that takes images or audio gets its encoders**, on the backend its file prefers (the
encoder section's first `backend_constraint`, else CPU, as `litert-lm serve` chooses) or on the
config's `vision_backend` or `audio_backend`. Without a backend named for it LiteRT-LM creates no
vision or audio executor, and every image sent to LFM2.5-VL-450M got a 500. The encoders come
with the engine, so a text-only chat with such a model holds them too: about 0.2 GB more
resident memory for LFM2.5-VL-450M on CPU without the compiled-model cache; GPU unmeasured. An
image or audio part sent to a model without that input is a 400 that says so, before anything
loads. Which inputs a file takes, on which backends, the server reads from the file's header
itself, as LiteRT-LM does. The C API's `litert_lm_loaded_file_*` answer the same, but read whole
model sections into memory to do it (every vision section, and the main model when its metadata
gives no context size): 285 MB for gemma-3-270m-it, before any engine loads.

**Sampling starts from the model's own parameters.** A session's sampler replaces the model's
(LlmMetadata's `sampler_params`) entirely, so every conversation is created with the model's own
top_k, top_p and temperature (read from the file's LlmMetadata), each field the request sets put
in its place, and a random seed unless the request gives one. A model with no parameters of its
own (LFM2.5-230M, gemma-3-270m-it) gets LiteRT-LM's defaults, which are greedy (top_k 1), so a
temperature or top_p sent without a top_k there samples with no top-k limit (on CPU the largest
k, which its sampler clamps to the vocabulary; on GPU the model's vocabulary size, its number of
logits), and the server logs it once per model. The CPU sampler implements only TOP_P, so a
model whose own type is TOP_K gets TOP_P with p 1. On NPU, and for an Artisan text decoder on GPU
(which LiteRT-LM runs as GPU_ARTISAN; Gemma 4's `-gpu` bundles have one), nothing is set unless
the request sets a field, as before: LiteRT-LM leaves sampling to the model there. A `top_k` of 0
or below means no top-k limit, as in llama.cpp (TurboLLM's Top K 0 means disabled), and one above
0 is passed as set, on GPU too, where LiteRT-LM 0.18.0 sets no limit of its own; a `top_p`
outside 0 to 1, a negative `temperature`, and a `top_k` or `seed` with a fraction are a 400 that
names the field instead of the runtime's 500. LiteRT-LM takes these values and the repetition
penalties below as floats, so a `temperature` or penalty beyond a float's range (3.4028235e+38
either way), which reached it as an infinity, is a 400 that names the field too.

**Thinking is asked for the ways clients ask for it.** In order, the first that says: the
top-level `reasoning_effort` (what the Python server reads, and what the daemon sends for a
LiteRT-LM model), `chat_template_kwargs.enable_thinking`,
`chat_template_kwargs.reasoning_effort`, then `thinking_budget_tokens` (0 is off, n > 0 a budget
of n tokens, which also applies when another field turned thinking on), then the config's
`thinking` keys. `chat_template_kwargs` is passed to the model's template as its extra context,
with the `enable_thinking` decided above so that the template and the decoder's budget agree,
and its other keys (`preserve_thinking`, which LFM2's template reads) as sent. A model that does
not think (its metadata says so, or it has neither the template's switch nor a thinking channel:
gemma-3-270m-it) gets a 400 for thinking on, and thinking off changes nothing there. A model
whose file does not say and whose template has no switch, but that has a thinking channel
(LFM2.5-230M, which does not think, and LFM2.5-1.2B-Thinking, which always does; LFM2-700M's and
LFM2.5-350M's say they do not), gets the request's thinking as asked: LiteRT-LM 0.18.0 cannot
stop such a model from thinking (a budget of 0 sets no constraint), and nothing in the file tells
the two apart, so a 400 for one would refuse the other. The server logs that once, and every such
reply carries `X-Thinking: unverified`, since a client sees nothing of the server's log.

**OpenAI's `stop` is applied by the server**, which the C API cannot do (it knows only the
model's stop tokens): the reply's text ends before the first stop string, streamed or not, and
the generation is cancelled there, with `finish_reason` "stop". Text that could be the start of
a stop string is held back until the next chunk tells, so no part of one is ever sent. It
applies to the reply's text only, not to its thinking or tool calls, as OpenAI's does.

**Repetition penalties go through LiteRT-LM's own** (`RepetitionPenaltyConfig`, applied per
message while it decodes): `presence_penalty` and `frequency_penalty` as OpenAI defines them,
and `repetition_penalty`, or llama.cpp's `repeat_penalty`, which the daemon sends
(multiplicative, 1 is none). The window is `repeat_last_n` when the request sets it, as
llama.cpp reads it (0 turns the penalties off, -1 is the whole reply); else the last 64 tokens
for `repeat_penalty`, llama.cpp's default; else the whole reply. LiteRT-LM counts the reply's
tokens only. A repetition penalty below 1, which LiteRT-LM would clamp to 1 without a word, is a
400. `min_p`, which it has no sampler for, is not applied, and a reply to a request with one
above 0 says so in its `X-Unapplied-Parameters: min_p` header: a 400 would fail every chat whose
saved settings have a Min P, chats that worked when it was dropped unread.

**`tool_choice` "none" leaves the tools out of the conversation** (earlier calls and results
stay in the history), and "auto" or none at all keeps them. LiteRT-LM 0.18.0's C API cannot make
a model call a tool (constrained decoding keeps a call well formed; Gemma's "function call only"
mode comes from the model file), so `tool_choice` "required" or a named function is a 400 that
says so.

**A context larger than the model takes is cut to the model's, and said.** The runtime used the
model's own limit with only a warning in its log (LFM2.5-230M has a fixed 4096), and "length"
was judged against the size asked for. Now the engine is given the model's limit (its
LlmMetadata's `max_num_tokens`, or where that is missing, as in gemma-3-270m-it, the size of its
prefill signature's mask, which is where LiteRT-LM reads it), the server logs that once per
model and size asked, and it reports the window in each chat reply's `X-Context-Window` header
and as `max_model_len` on `/v1/models`, with `X-Context-Window-Requested` and
`max_model_len_requested` when more was asked. That window is the one LiteRT-LM gives the
engine, worked out as it does: with no size asked for, the model's own, but 4096 for a file that
gives none and on GPU no more than 4096; for a model with a dynamic context (a prime above 10,
its magic number), the size asked when it is below that number, and otherwise LiteRT-LM's
default for it, the largest multiple of 256 below it (below 256, the largest power of two), also
with none asked. A prompt that does not fit the window is a 400 that names its size; in a
stream, already answered 200, it is the `response.error` event, with the same words.

**With no backend in the model reference or the config, the model's own is used**: the first its
header lists for its text (a GPU-only bundle, Gemma 4's `-gpu` one among them, lists gpu alone; a
file without a backend constraint, LFM2.5-230M for one, lists cpu first; an Artisan text decoder
puts gpu first), as `litert-lm serve` picks it. The server picked CPU for every model, so a
GPU-only bundle sent without a backend failed to load. When the model's own backend does not
start, the engine falls back to the next one the model lists, CPU for most, and never to one it
does not list, and the server logs why; when none starts, the error gives each one's reason. A
backend the user set is used as set, failing as before, also when an engine whose own backend fell
back is loaded: an engine is kept for the backend it runs on.

**A conversation that ends with the assistant's own words is a 400.** A client that prefills the
start of a reply asks for it to be continued, which LiteRT-LM 0.18.0 can do only from C++
(`has_pending_message`); through the C API the partial reply was sent as a finished turn and the
model began a new one, which the client then appended to its prefill.

**The library is loaded with `dlopen(RTLD_LOCAL)`, not linked**, and the server's own C++
runtime is static and unexported. `liblitert-lm.so` carries its own statically linked C++
runtime and *exports* it; when the server was linked against the library, the linker
satisfied the server's `std::runtime_error`/`__cxa_*` references from those exports, and
AddressSanitizer caught an exception being destroyed by the other runtime. See
`src/litert_lm_api.h`.

**It ends with whatever started it.** The first thing `main()` does is arm
`PR_SET_PDEATHSIG(SIGKILL)`, so the kernel kills the server the moment the thread that started it
ends, and it exits 1 without starting when its parent is already init (the starter died before
the `prctl`). The daemon's [engine tether](#the-engine-tether) does the same before it execs the
server; this covers a server started any other way: by a daemon without the tether, through the
daemon's `/system/bin/sh` fallback, or by hand. Off Android, a parent that is PID 1 of a
container's PID namespace, its entrypoint (tini, a shell), is taken for the server's starter, as
it usually is: the kernel keeps no record that would tell an orphan that PID 1 adopted from its
child. `node --test engines/litert-lm-server/parent-death.test.mjs` builds the server for the
machine (Linux, `cc` and `c++`) against a stand-in `liblitert-lm.so` whose functions do nothing,
and checks that a stand-in daemon's SIGKILL takes the listening server with it, that an orphaned
start exits (outside a container's PID namespace, where that test is skipped), and, where `unshare`
can make a PID namespace, that a server its PID 1 starts runs.

### GPU

The app reads the GPU's name from a throwaway GL context (`MainActivity.gpuEnv`) and passes it
as `TURBOLLM_ANDROID_GPU`. That is the counterpart of `vulkaninfo` under Termux — the one fact
that makes LiteRT-LM's default "auto" backend choose the GPU (`litertLmProfileToConfig`). The
server is spawned from `nativeLibraryDir` exactly like the llama.cpp engines, i.e. the same
kind of `/data` executable Termux's Python is, so it gets the same linker namespace for the
system Vulkan/OpenCL/GL libraries.

### Bumping LiteRT-LM

Change `LITERT_LM_VERSION` and both wheel hashes in `scripts/stage-litert-lm-engine.mjs`
(PyPI publishes them at `https://pypi.org/pypi/litert-lm-api/<version>/json`), then both
`patchedSha256` values: the build stops with the new hash once the shim, its checks and the link
probe have passed, and a changed list of shimmed functions is worth a look. Refresh
`engines/litert-lm-server/third_party/litert_lm/c/*.h` from the matching
`google-ai-edge/LiteRT-LM` tag, and check that every function listed in
`src/litert_lm_api.h` still exists — the server refuses to start, naming the missing ones, if
it doesn't.

### Not yet verified

- **No phone has answered a chat yet.** On the owner's phone the wheel's library failed to load,
  which is why it now has [a shim](#libandroidso-and-its-shim). With the patched one the same
  phone (Mali-G715) loaded LFM2.5-230M, and then the daemon's one-token warm-up request failed
  because constrained decoding was on for every request (see above), so the model never finished
  loading. The patched library was run in a chroot of the x86_64 Android 15 emulator image's
  system and vendor partitions, with that image's linker and its generated `/linkerconfig`: it
  loads and answers a chat request on CPU, and still does with `libstagefright.so` removed from
  the image, where the unpatched one fails to load as it did on the owner's phone. In that chroot
  (image unmodified) it also answers LFM2.5-230M, gemma-3-270m-it, gemma-3-1b-it, a FunctionGemma
  270M and Qwen3-0.6B on CPU, streamed or not, with tools and with a `response_format`. The code
  uses only POSIX/bionic APIs available at API 24.
- GPU speed versus Termux on the same phone, and whether every GPU path (OpenCL, WebGPU/Vulkan)
  loads from an app-spawned process the way it does from Termux. The linker namespace of a
  `/data` executable searches `/system` and `/system_ext` only, so a vendor `libOpenCL.so` may
  not be found. A GPU that cannot be initialised fails the load with the runtime's error (there
  is no automatic fallback); the app then suggests switching the model's Backend to CPU.

---

## The engine tether

Every engine the daemon starts (the llama.cpp ones and the LiteRT-LM server) is meant to end the
moment the app process does, however it ends. The app ships a helper for that,
`libtether_exec.so`, and the daemon starts each engine as
`libtether_exec.so <daemon pid> <engine> <args...>`, and each probe the same way (a short run of
a program whose output it reads, such as an engine's version check). Only those: the helper ties
the one program it execs, not what that program starts, so every other process the daemon ties
to its life (an MCP stdio server, a tunnel) keeps the daemon's `/system/bin/sh` lifeline: a
process group of its own, which a stop ends as a whole, and a watcher that kills that group
when the daemon dies. The helper:

1. arms `PR_SET_PDEATHSIG(SIGKILL)`, so the kernel kills the process the moment the thread that
   started it ends;
2. exits 127 without starting the engine if its parent is no longer that pid (the daemon
   process ended between the fork and the `prctl`, so no signal would ever come). This sees the
   process end, not the thread: had only the forking thread ended, the kernel would have moved
   the helper to another thread of the daemon, and the signal would come when that one ends;
   the daemon's thread ends only with the daemon;
3. `exec`s the engine in place: the engine keeps the helper's pid, stdio and exit status, and no
   extra process is left behind.

libuv forks on the thread that calls `spawn`, the one `MainActivity` starts `node::Start` on,
which lives exactly as long as the daemon. So whichever way the app process ends (Quit, which
is `Process.killProcess`, a crash, Android killing the app), the kernel ends the engines with
it at once. Behind that, system_server still kills whatever is left in the
app's cgroup once the app process has died (AOSP 7 to 15); before the helper, that was an
engine's only tie to the app. `liblitertlm_server.so` also arms the signal itself, at the top of
its `main()` ([what the server is](#what-the-server-is-and-why-it-is-shaped-this-way)).

| | |
|---|---|
| Source | `engines/tether-exec/tether_exec.c` |
| Built by | `scripts/stage-tether-exec.mjs` (`stageTetherExec` in `composeApp/build.gradle.kts`, hooked to `merge*JniLibFolders`), with the NDK's clang for API 24, as a PIE with 16 KB-aligned LOAD segments, stripped, checked with the LiteRT-LM staging's `checkElf` |
| Output | `composeApp/build/generated/tether-exec/jniLibs/<abi>/libtether_exec.so`, about 6 KB per ABI |
| In the APK | Always. `assemble*`/`bundle*` fail if it is missing for a shipped ABI |

**The daemon's side** is TurboLLM's `util/tether.ts` (`fix(daemon): a process the daemon starts can
be tied to its life`). It looks for the helper in `TURBOLLM_ANDROID_NATIVE_LIB_DIR` and, at each
start, before it spawns anything, checks it by watching it work, while the app lives (a force-stop
cannot test it: the cgroup kill would hide a helper that does nothing).
It runs `/system/bin/sh -c '"$0" "$$" /system/bin/sleep 30 & echo "$!"; read -r _' <helper>`
and must see `/system/bin/sleep 30` running with that `sh` as its parent, then gone within 1 s
of ending the `sh`'s stdin. It logs `tether: libtether_exec.so self-test passed (… ms)` to
`files/daemon.log`, or `… self-test failed: <step and reason>; tethered spawns use the
/system/bin/sh lifeline instead`, or, when the APK has no helper, `tether: libtether_exec.so not
found in <dir>; tethered spawns use the /system/bin/sh lifeline instead`. Either way it then ties each engine
with a `/system/bin/sh` watcher instead (one more process per engine, counted against
Android 12+'s phantom process limit). A daemon without that commit starts engines directly:
then the cgroup kill alone ties the llama.cpp engines, and the LiteRT-LM server is also tied
by its own signal.

`node --test scripts/stage-tether-exec.test.mjs` builds the helper for the machine with `cc`
(Linux) and runs it: a stand-in daemon's SIGKILL takes its engine; a parent that is not the
given pid, or a pid that is not a number above 1, exits 127 without running anything; the pid,
stdin, stdout, exit status and signals pass through; it passes the daemon's self-test under each
shell found (`/bin/sh`, bash, mksh), while a helper that execs without the signal and one that
exits 127 fail it. With an NDK (`ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, or 27.0.12077973 under
`ANDROID_HOME` or `ANDROID_SDK_ROOT`) it also builds both ABIs and checks the files.

### Checking it on a device

With a model loaded:

```bash
adb shell run-as com.turbollm cat files/daemon.log | grep 'tether:'   # "self-test passed"
adb shell ps -A -o PID,PPID,NAME | grep -E 'llama_server|litertlm|tether'
#   the engine's PPID is the app's pid, and no libtether_exec.so is listed (it exec'd the engine)
adb shell am force-stop com.turbollm
adb shell ps -A | grep -E 'llama_server|litertlm'                      # nothing
```

Then the same `ps` after each other way the app ends with a model loaded, loading it again
each time: the notification's Quit (the app ends about 3 seconds after the tap), and
`adb shell run-as com.turbollm kill -9 <app pid>` (a debug build). No engine is left after
either. After Quit, a reply that finished just before is there when the app is opened again:
the daemon wrote its chat database before the kill.

Back from the app's main screen, or a swipe from Recents, with a model loaded does not end the
app, by design: a loaded model counts as running, so the app and its engine stay (`ps` still
lists the engine under the app, and the notification reads "Idle · " and the model's name)
until Quit, or until the daemon's idle timer has unloaded the model and the app is closed again
with nothing running (opened and closed, or the card a Back left in Recents swiped away).

With nothing loaded or running, Back or a swipe from Recents ends the app about 3 seconds
later: `adb shell pidof com.turbollm` prints nothing, and the `ps` above lists no engine. A
reply that finished just before is there when the app is opened again.

On the first launch after an install or an update, close the app only once it shows its own
splash: the animated bolt (an orange badge that pops in, then pulses) with "Starting TurboLLM…"
under it, not the still launcher icon Android shows before it from Android 12. Until then the app
is still unpacking the daemon and has not started the service that hears a close, so whether a
swipe made then ends the app is up to Android.

Only the `tether:` line tests the helper itself. Every ending above would also pass on the
cgroup kill alone.

### Not yet verified

No device has run it. The x86_64 NDK build was run in a chroot of the x86_64 Android 15
emulator image (its own linker, `/system/bin/sh`, which is mksh, and toybox's `sleep`): it
passed the self-test above, and a stand-in daemon's SIGKILL took its engine within 20 ms. The
arm64 build has only been checked as a file (architecture, PIE, `/system/bin/linker64`,
alignment).

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

A Windows checkout that had the scripts before `.gitattributes` pinned them and `gradlew` to LF
still has the CRLF copies Git for Windows wrote by default (`core.autocrlf=true`), and bash stops
at the first `\r`: "set: pipefail: invalid option name". Pulling does not rewrite them, since
their contents did not change, and `git status` stays clean. Write them again once, with Git for
Windows in that checkout (this discards uncommitted edits to those four files, nothing else):

```bash
git rm -q --cached -- "scripts/*.sh" gradlew
git checkout HEAD -- "scripts/*.sh" gradlew
```

The script is the WSL original plus the four things that original did not do, each of which
exists because of something that went wrong:

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
