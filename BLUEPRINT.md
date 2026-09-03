# TurboLLM Android — end-to-end build blueprint

> Status: **Spike A PASSED (2026-09-03)** — built, installed, and verified live on a real
> emulator (see §4's Spike A status note): the embedded Node runtime boots and its HTTP
> server answers real requests. Everything marked **PROVEN** was actually run live, either
> on real hardware in the earlier R&D session (ADR-395) or on this session's emulator run.
> Remaining spikes (B/C/D/E) still need their own verification — Spike A passing doesn't
> imply they do.

## §0 — What changed from the original design, and why

The main repo's `docs/specs/28-android-native-app.md` designed this as a **forked Termux
app** (fork `termux/termux-app`, strip the terminal UI, keep its bootstrap machinery,
bundle Termux's own `nodejs` package). That design works, but forking Termux means the
app shell is a derivative of a **GPLv3-only** project — distributing the APK obligates
publishing the complete source of the forked shell under GPLv3. Spec 28 §4 made that an
explicit hard gate: no fork work starts before a real legal review.

This blueprint takes the alternative that sidesteps the question entirely: **embed Node
via [`nodejs-mobile`](https://github.com/nodejs-mobile/nodejs-mobile) instead of forking
Termux.**

- `nodejs-mobile` is a fork of upstream Node.js maintained for embedding in iOS/Android
  apps. **MIT-licensed** (same as Node itself) — no copyleft, no forced source disclosure.
  Originally built by Janea Systems; Janea stepped back from active maintenance and the
  community continues it at `github.com/nodejs-mobile/nodejs-mobile`.
- It runs Node **in-process**, as a library your own app links against — not a second app,
  not a spawned shell, not a derivative of any GPL'd codebase. The app shell is entirely
  your own code.
- `llama.cpp` (MIT) is unaffected either way — this was never a licensing question.

Net effect: **§4's legal gate goes away.** Nothing here needs a licensing review before
implementation starts, because nothing here forks a GPL'd project. The tradeoff, honestly
stated: `nodejs-mobile` is community-maintained, not battle-tested at TurboLLM's scale, and
several things about it are unverified against *this specific daemon* — hence Phase 0.

### Kotlin Multiplatform, for future iOS (founder decision, 2026-09-03)

The project is structured as **Kotlin Multiplatform + Compose Multiplatform** from the
start, not plain Android, so an iOS target can be added later without a rewrite. Concretely:

- `nodejs-mobile` ships for both Android and iOS, but the *bridge* to it is inherently
  platform-specific either way — JNI on Android, Objective-C/Swift interop on iOS — so KMP
  doesn't unify that part. What it does unify: the daemon HTTP client (`DaemonClient.kt`,
  Ktor-based) and the entire Compose UI (`App.kt`) live in `commonMain`, genuinely shared,
  zero Android-specific code in either.
- Module shape: `composeApp/src/{commonMain,androidMain,iosMain}` — an `expect class
  NodeRuntimeLauncher` in `commonMain` with a real JNI-backed `actual` in `androidMain` and
  a deliberately-unimplemented stub `actual` in `iosMain` (throws `NotImplementedError`,
  clearly commented). iOS targets (`iosX64`/`iosArm64`/`iosSimulatorArm64`) are declared in
  `composeApp/build.gradle.kts` so the module compiles for them, but **no iOS app work has
  started** — no Xcode project, no WKWebView shell, nothing beyond the stub. Don't read the
  declared targets as "iOS is supported."
- Package is `com.turbollm.app` throughout (not `com.turbollm.android`) — reflects that the
  Kotlin code itself is no longer Android-only, even though `applicationId` stays
  `com.turbollm.android` for app-identity continuity.

Everything else from spec 28 carries over unchanged: WebView shell (no URL bar), foreground
service (fixes the real backgrounding bug ADR-395 found — daemon becomes unreachable the
instant the hosting app leaves the foreground), engine downloaded on first launch (not
bundled), Android-aware onboarding model recommendation.

## §1 — Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  TurboLLM.apk  (plain native Android app — no forked GPL project) │
│                                                                     │
│  MainActivity                                                      │
│    └─ full-screen WebView, no chrome, no URL bar (§6)              │
│         loads http://127.0.0.1:6996 once the daemon is listening   │
│                                                                     │
│  TurboLLMDaemonService  (foreground service — §7)                  │
│    └─ nodejs-mobile embedded runtime, started in-process            │
│         └─ runs turbollm/dist/cli.js (bundled in APK assets — §4)  │
│              └─ spawns llama-server (NDK-cross-compiled, downloaded │
│                   on first launch from HF — §2, §3, §5)             │
└──────────────────────────────────────────────────────────────────┘
```

Three components, three different provenances:
1. **The app shell** — written fresh for this project. Kotlin/Java, MainActivity +
   WebView + foreground service. No Termux code anywhere.
2. **The daemon** — `turbollm/dist` (existing product code, already builds clean, already
   proven booting under Node-on-Android this session — ADR-395). Bundled as JS assets,
   executed by the embedded Node runtime.
3. **The inference engine** — `llama-server`, NDK-cross-compiled (§2), hosted on the
   team's own Hugging Face account (§3), downloaded on first app launch (not bundled —
   keeps the APK small, matches how LM Studio's own Android app already works).

## §2 — Compiling `llama-server` for Android in WSL

**PROVEN** — this exact recipe (originally written for GitHub Actions CI,
`.github/workflows/android-llama-build.yml`) is the real, working NDK cross-compile
command set; the R&D session ran the equivalent by hand in WSL and got a genuine
Bionic-native binary (`file` confirmed `ELF ... ARM aarch64 ... interpreter
/system/bin/linker64`) that needed `libomp.so` bundled alongside it to actually run.

### 2.1 One-time WSL setup

```bash
# Inside WSL (Ubuntu). If WSL itself isn't installed yet, do that from Windows first:
#   wsl --install -d Ubuntu   (run from an elevated PowerShell, then reboot if prompted)

sudo apt update && sudo apt install -y git cmake ninja-build unzip openjdk-17-jdk-headless

# Android command-line tools → gives you sdkmanager, which installs the NDK cleanly.
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=~/android-sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager "ndk;27.2.12479018"   # pin a version; check sdkmanager --list for current

export ANDROID_NDK_HOME=~/android-sdk/ndk/27.2.12479018
```

> Verify the NDK version against whatever's current when you actually run this — the CI
> workflow just uses whatever GitHub's Ubuntu runner ships preinstalled
> (`ANDROID_NDK_LATEST_HOME`), so there's no pinned "known good" version yet. Pin one here
> and record it once it's been built + tested against a real device.

### 2.2 Build (per ABI — repeat for `arm64-v8a`, optionally `x86_64` for emulator testing)

```bash
git clone --depth 1 --branch "$(grep -oP "export const LLAMA_BUILD = '\K[^']+" \
  /mnt/d/llama-turbo/turbollm/src/engines/download.ts)" \
  https://github.com/ggml-org/llama.cpp llama.cpp-src
# (or just hardcode the tag — check download.ts's current LLAMA_BUILD const directly)

cmake -B build -S llama.cpp-src \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build -j --target llama-server
```

### 2.3 Package (the step that's easy to get wrong — `libomp.so` is NOT in `build/bin`)

```bash
mkdir -p out
cp build/bin/llama-server out/
cp build/bin/*.so* out/ 2>/dev/null || true
# libomp.so is the NDK's own OpenMP runtime (ggml's CPU backend links against it) —
# lives under the toolchain dir, not the build output. Without it: llama-server fails
# to even start ("CANNOT LINK EXECUTABLE ... library libomp.so not found").
cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/"*"/lib/linux/aarch64/libomp.so" out/

TAG=$(grep -oP "export const LLAMA_BUILD = '\K[^']+" \
  /mnt/d/llama-turbo/turbollm/src/engines/download.ts)
tar -C out -czf "llama-${TAG}-bin-android-arm64.tar.gz" .
```

Repeat with `-DANDROID_ABI=x86_64` (omp arch `x86_64`, output name
`llama-${TAG}-bin-android-x64.tar.gz`) only if emulator testing on an x86_64 AVD is needed
— real devices are arm64.

## §3 — Hosting the binary on your own Hugging Face account

Founder direction: **no CI for now** — build locally, upload manually, on demand.

### 3.1 Create the HF repo (one-time)

Any `model`-type HF repo works for arbitrary binary assets (this is exactly what GGUF
quant repos already are). Suggested layout, one repo holding every asset this app needs:

```
<your-hf-username>/turbollm-android-engines
  ├── llama-<tag>-bin-android-arm64.tar.gz
  └── llama-<tag>-bin-android-x64.tar.gz      (optional, emulator only)
```

```bash
# via huggingface_hub CLI (pip install -U huggingface_hub; hf auth login first)
hf repo create turbollm-android-engines --repo-type model
hf upload <your-hf-username>/turbollm-android-engines \
  llama-<tag>-bin-android-arm64.tar.gz llama-<tag>-bin-android-arm64.tar.gz
```

(Or drag-and-drop through the HF web UI — same result, no CLI needed.)

### 3.2 Consuming it from the daemon — a real, small code change

ADR-391 shipped `download.ts`'s Android path pointed at **GitHub Releases**
(`ANDROID_REPO = 'mohitsoni48/TurboLLM'`, `provisionBackend()`'s URL construction at
`turbollm/src/engines/download.ts:361`). This blueprint's HF-hosting decision means that
should be swapped for the **already-existing** HF client instead of extending the
GitHub-Releases shape further:

- `turbollm/src/hf/hf.ts:382` already has `fileUrl(repo, rfilename): string` →
  `` `${BASE}/${repo}/resolve/main/${rfilename}` `` — exactly the URL-construction primitive
  needed here.
- Change `availableBackends()`'s `android` branch (`download.ts:108`) to resolve through
  that instead of `BackendDef.repo`/`releaseTag` (the GitHub-Releases-shaped fields ADR-391
  added). Concretely: either give `provisionBackend()` an HF-aware branch, or have the
  Android `BackendDef` carry a pre-built HF URL (`hf.fileUrl('<user>/turbollm-android-engines',
  'llama-<tag>-bin-android-arm64.tar.gz')`) instead of `repo`/`releaseTag`.
- This is flagged in spec 28 §8 as "a real code change from what ADR-391 shipped" — it's
  in scope for this blueprint's Phase 4 (§7 below), not a footnote.

## §4 — Phase 0: the spikes (run these before writing any real app code)

Each spike has a clear pass/fail bar. Do them in this order — later ones assume earlier
ones passed. If a spike fails, its fallback is listed; don't skip past a failed spike
without consciously choosing (and recording) the fallback.

### Spike A — `nodejs-mobile` builds and boots on a real device
**Why**: nothing else in this plan matters if Node can't actually run embedded.
**Do**: follow `nodejs-mobile`'s own Android getting-started guide
(`nodejs-mobile.github.io/docs/guide/guide-android/getting-started/`) to produce the
minimal "hello world" sample app, install on the real test phone (or an AVD), confirm a
JS file executes and can write to logcat.
**Pass bar**: a JS `console.log` from inside the embedded runtime shows up in `adb logcat`.
**Fallback if it fails to build/run at all**: fall back to spec 28's original Termux-fork
design (§4 of that doc) — the licensing review becomes necessary again, but the technical
path is already fully proven (ADR-395).
**Status (2026-09-03)**: **PASSED — verified live** on a real emulator (Pixel_10_2 AVD,
API 37, x86_64, 4KB pages). Built (`./gradlew :composeApp:assembleDebug`), installed, and
launched with no crash; both `libnative-lib.so` and `libnode.so` loaded successfully
(confirmed via logcat's `nativeloader` lines). `curl http://127.0.0.1:6996/` (via
`adb forward tcp:6996 tcp:6996`) returned a real, live response from the embedded runtime:
`{"ok":true,"node":"v18.20.4","v8":"10.2.154.26-node.37","uv":"1.44.2","platform":"android","arch":"x64"}`
— and the Compose UI itself rendered that exact data (screenshot:
`spike-a-emulator-screenshot.png`). One deviation from the pass bar as originally written:
`console.log` output did **not** appear in `adb logcat` under any searched tag — this
`nodejs-mobile` integration doesn't auto-redirect Node's stdout to logcat the way the
original spike description assumed. Not a blocker: the HTTP check is strictly more decisive
proof the runtime booted and ran real JS, so the pass bar is met via that half. Worth a
follow-up if console-log visibility in logcat matters later (e.g. redirect stdout/stderr to
a file, or an explicit Android log bridge from within the launched script).

### Spike B — the embedded runtime's Node version satisfies `turbollm`'s `engines` field
**Why**: `turbollm/package.json` requires `"node": ">=22.13.0"`.
**Do**: check which Node version the current `nodejs-mobile` release embeds (check its
release notes / `package.json` in the repo — this drifts, verify at spike time, don't
trust a remembered number).
**Pass bar**: embedded Node version ≥ 22.13.0.
**Fallback if it's older**: check whether `nodejs-mobile` has a newer unreleased branch,
or whether the daemon's actual minimum can be relaxed (audit what specifically needs
22.13+ — likely just `node:sqlite`, see Spike C) rather than blocking on the whole plan.
**Status (2026-09-03)**: **failed, confirmed** — the vendored `libnode.so`'s own
`node_version.h` reads 18.20.4 (`nodejs-mobile`'s actual current release line), well below
22.13.0. This is ground truth from the binary itself, not a guess. Spike C's fallback is
therefore a **confirmed requirement, not a hypothetical** — see its status note below.

### Spike C — `node:sqlite` works inside the embedded runtime (the sharpest real risk)
**Why**: `turbollm`'s build step (`package.json`'s `build` script) rewrites the chat
store's `sqlite` import to **`node:sqlite`** — Node's built-in SQLite module. This is a
Node **core** module, not an npm package — it only exists if the specific Node build
embeds it compiled in. `nodejs-mobile` is a fork/rebuild of upstream Node; there's no
guarantee its build config includes this module the same way a standard Node.js release
does.
**Do**: inside the Spike A sample app, `require('node:sqlite')` and open an in-memory DB.
**Pass bar**: no `ERR_UNKNOWN_BUILTIN_MODULE` (or equivalent) — a real `DatabaseSync`
instance comes back.
**Fallback if it fails**: two real options, pick one and record the decision —
  1. Swap the chat store's DB layer for a WASM SQLite (e.g. `sql.js`/`wa-sqlite`) behind
     the same interface, Android-only — a genuine code change to `turbollm`'s chat-store
     module, gated by platform.
  2. Cross-compile `better-sqlite3` (a native addon) for `nodejs-mobile`'s specific ABI —
     `nodejs-mobile` does support native addons but building one for it is its own
     nontrivial NDK toolchain exercise, not a drop-in.
  Option 1 is very likely less total effort — flag it as the default fallback unless Spike
  C's investigation finds otherwise.
**Status (2026-09-03)**: confirmed necessary (Spike B), **not yet correctly implemented**.
An earlier attempt in this same session went with Option 1's *direction* but the *wrong
execution*: it replaced `node:sqlite` with `sql.js` **globally** in the shared `turbollm`
daemon (`db.ts`, `sqlite-chat-store.ts`, `capabilities.ts`) and dropped the whole product's
`engines.node` floor to `18.20.0` — for every platform, desktop included, not just Android.
That change was found and stashed out of the daemon repo (`D:\llama-turbo`, stash entry
tagged `android-daemon-messed-*`), not merged. The real task is still open: implement the
WASM-SQLite fallback as an **Android-gated** code path (e.g. selected by `process.platform`
or a Node-major-version check) that leaves desktop's `node:sqlite` path untouched.

### Spike D — the daemon boots end-to-end inside the embedded runtime
**Why**: proves the actual product, not just "Node runs."
**Do**: bundle a real build of `turbollm/dist` as assets in the Spike A app, launch
`dist/cli.js` inside the embedded runtime instead of a throwaway script, point it at the
§2/§3 engine binary + a small test GGUF, and hit its own OpenAI-compatible `/v1/chat/completions`
route from the WebView (or `adb shell curl`) the same way ADR-395 already proved on Termux.
**Pass bar**: a real chat completion comes back — same bar ADR-395 already cleared once,
now inside the new runtime instead of Termux's spawned-process one.
**Known-degraded features, not blockers**: `node-pty` (optional dep, powers the in-app
terminal for the Code/Agents feature) is a native addon almost certainly not available for
`nodejs-mobile` out of the box — expect the terminal feature to be unavailable on Android
v1 unless someone separately cross-compiles it. Not a spike blocker; just don't be
surprised by it, and don't spend Phase 0 time chasing it.

### Spike E — the foreground-service backgrounding fix, in this new architecture
**Why**: ADR-395 found a real bug — the daemon becomes unreachable (`SYN_SENT`, no
response) the instant its hosting app leaves the foreground. That was proven against a
Termux **spawned subprocess**; this design runs Node **in-process** via `nodejs-mobile`
inside a foreground service instead, which is a different execution model and should
behave *better* (no separate UID/process to lose network access), but "should" isn't
"proven."
**Do**: with Spike D's daemon running inside a real `Service` marked foreground
(persistent notification), background the app (home button, switch to another app), wait
30+ seconds, then hit `127.0.0.1:6996` again from `adb shell`.
**Pass bar**: still reachable after backgrounding, with and without the foreground-service
flag set (run it once each way to actually confirm the foreground service is doing
something, not just assume it).

## §5 — Phase 1: skeleton app (after Phase 0 passes)

**Status (2026-09-03)**: done, as a Kotlin Multiplatform + Compose Multiplatform project
(`composeApp/`, minSdk 24 matching §2's `ANDROID_PLATFORM=android-24`) rather than a plain
Android Studio project — see "Kotlin Multiplatform, for future iOS" under §0. Steps below
kept for reference/reproducibility, not as an outstanding to-do.

1. ~~New Android Studio project, Kotlin, minSdk matched to `ANDROID_PLATFORM=android-24`
   from §2~~ → done via `composeApp/build.gradle.kts` instead.
2. Integrate `nodejs-mobile`'s Android library per its own docs — this is a real Gradle
   module dependency + a native library init call, not custom low-level JNI work.
3. `MainActivity` — just enough to host a WebView (real UI comes in §6) and confirm the
   Spike D daemon still boots inside a real Activity lifecycle, not just a throwaway
   sample.

## §6 — Phase 2: the WebView shell

Carried over from spec 28 §6, unchanged by the `nodejs-mobile` swap:

- Single full-screen `android.webkit.WebView`, `javaScriptEnabled` + DOM storage on
  (the web UI already depends on both for the desktop/LAN case).
- `onShowFileChooser` wired up (chat attachments need this in a raw `WebView`, unlike a
  real browser).
- No navigation chrome, no address bar; back button → `WebView.goBack()` when there's
  history, else exits.
- A real loading/splash state while the daemon finishes starting (§7's service), not a
  blank `WebView` — `127.0.0.1:6996` isn't listening yet at cold start.
- Uses the **system WebView component** (Chromium-based, auto-updates via Play Store on
  real devices) — inherits the same stale-WebView-on-non-Play-Store-devices caveat ADR-395
  already found and reasoned about for desktop Chrome; real users on Play-Store-updated
  devices are unaffected (spec 28 §11 Q4's decision: leave it, not worth a build-target
  change).

## §7 — Phase 3: bundling the daemon + wiring the HF-hosted engine

1. Land the `download.ts` change from §3.2 in the main `turbollm` repo (a small, focused
   diff — not part of this blueprint's own repo, since it's a change to the shared product
   code that every platform's build consumes).
2. Build `turbollm/dist` (`npm run build` in `turbollm/`) and bundle the output as APK
   assets, extracted to app-private storage on first launch (mirrors how a Termux
   bootstrap zip would've worked, just pre-populated with only what's needed).
3. On first launch: extract daemon assets → start it inside `nodejs-mobile` → daemon's own
   `availableBackends()`/`provisionBackend()` (§3.2) downloads the engine from HF → engine
   registers → WebView loads once `127.0.0.1:6996` responds.
4. Reuse the catalog/compat/probe Android support that already shipped (ADR-390/391):
   `catalog.ts`'s android platform entry, `compat.ts`'s `PLATFORM_DISPLAY`, and critically
   `probe.ts`'s `LD_LIBRARY_PATH` fix (`probe.ts`'s `probeEnv()`) — without it, probing the
   freshly-downloaded `llama-server` fails with `library "libllama-server-impl.so" not
   found` even though the exact same env is set correctly at actual launch time. This was
   a real bug found live in the R&D session; it's platform-general, already fixed in the
   shared code, just needs to keep being exercised on this path too.

## §8 — Phase 4: the foreground service

Per Spike E's findings, implement `TurboLLMDaemonService` as a proper
[foreground service](https://developer.android.com/develop/background-work/services/foreground-services)
with a persistent notification ("TurboLLM is running — tap to open"), same mechanism a
music/podcast app uses. Also prompt for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on first
run so Doze mode doesn't kill a long-running generation mid-response.

## §9 — Phase 5: onboarding — Android-aware model recommendation

Extends the existing `(role, tier, system RAM)` → blessed-model resolution
(`docs/specs/25-onboarding-journey-design.md` §5) with a platform dimension, per spec 28
§9 (unchanged by this blueprint's architecture swap):

- Add `platform` to the resolution key in `recommend.ts`/`models.ts` — Android forces a
  phone-appropriate tier regardless of what raw RAM alone would suggest.
- v1 candidate pool: **Qwen2.5-0.5B-Instruct-GGUF (Q4_K_M)** — already measured live this
  session at 13.7 tok/s prompt / 3.5 tok/s generation on a real budget phone (Realme
  RMX1921, no flagship SoC). Real data, not a guess.
- A second, higher-tier (~1–3B) entry is explicitly deferred until this app actually ships
  and there's a real higher-RAM/flagship device to verify it on — same "verified on real
  hardware before shipping" bar spec 25 already holds every blessed entry to (ADR-239). Do
  not add a second entry on spec alone.

## §10 — Phase 6: release readiness (not urgent — last phase, not a blocker to internal testing)

- Signing config, `applicationId`, app icon/name/splash — standard Android release
  housekeeping, no TurboLLM-specific complexity.
- Decide minSdk floor for real based on what §2/§6 actually needed once built, not
  guessed in advance.
- Play Store listing is a separate, later decision — sideloaded APK is sufficient for
  everything through internal dogfooding and even a soft public launch.

## §11 — Explicit non-goals for v1

- The in-app terminal (Code/Agents feature, `node-pty`) — degraded/unavailable per Spike D,
  not worth chasing for a v1 chat-focused mobile app.
- CI/automated builds for the Android engine binary — founder direction: manual, local,
  on-demand builds only, for now (§2/§3). Revisit once the manual flow is proven annoying
  enough to be worth automating.
- A second onboarding tier (§9) — deferred until ship + real hardware to verify on.
- Any Play Store submission — sideload is enough until the app is actually ready for that
  conversation.
