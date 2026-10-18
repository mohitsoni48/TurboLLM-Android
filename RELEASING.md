# Releasing the TurboLLM Android app

Build steps and the decisions baked into them. For the engine binaries themselves see
[BUILDING-ENGINES.md](BUILDING-ENGINES.md); for the daemon that ships inside the APK see its
[daemon payload](BUILDING-ENGINES.md#the-daemon-payload--what-makes-the-apk-self-sufficient)
section.

## What a clean build needs

A fresh checkout downloads two pinned, SHA-256-checked inputs during the build, so the first build needs network
access to GitHub and PyPI (later builds use the cached copies under `composeApp/src/androidMain/cpp/libnode` and
`composeApp/build/generated/litert-lm/cache`):

- **libnode** (`scripts/fetch-libnode.mjs`, run by `preBuild`): digidem nodejs-mobile v18.20.4 from GitHub releases.
  On Linux it is unpacked with `unzip`, which must be on `PATH`.
- **LiteRT-LM** (`scripts/stage-litert-lm-engine.mjs`, run before native libs are merged): the `litert-lm-api` wheel
  from PyPI; its native server is then compiled per ABI.

And from the Android SDK: **NDK 27.0.12077973** (pinned as `ndkVersion`) and **CMake 3.22.1** (the server is built
with its `cmake` and `ninja`), plus Node on `PATH` for the scripts. Build with `-PturbollmLitertLmEngine=false` to
leave LiteRT-LM out, which also drops the PyPI and CMake requirements.

## One-time: the upload keystore

```bash
node scripts/create-release-keystore.mjs
```

Creates `keystore/turbollm-upload.jks` (RSA 4096, ~27 years) and appends four
`TURBOLLM_UPLOAD_*` properties to `local.properties`. Both are gitignored.

The password is generated on your machine and **never printed** — not to the terminal, not to a
chat, not to a log. That is deliberate: a signing password that has appeared in a transcript has
to be assumed compromised, and this is not a key you can quietly rotate.

**Back up the `.jks` file and those `local.properties` lines off this machine, now.** Once the
app is live, losing them means you cannot ship an update without a Google support key reset.
The script refuses to overwrite an existing keystore for the same reason.

## Every release

```bash
./gradlew :composeApp:bundleRelease
# → composeApp/build/outputs/bundle/release/composeApp-release.aab
```

That single command also stages the Node daemon into the APK (`stageDaemonAssets`), which
rebuilds the daemon bundle from the `turbollm` checkout first. If turbollm is not a sibling
directory, pass `-Pturbollm.dir=<path>`.

Use `:composeApp:assembleRelease` instead if you want an installable `.apk` (for sideloading a
beta build); Play takes the `.aab`.

### Before you upload

- **First upload with `EngineService`:** file the foreground-service declaration in Play Console
  (App content → Foreground service permissions). The text and a demo-video script are in
  [PLAY-FGS-DECLARATION.md](PLAY-FGS-DECLARATION.md). The Play build does **not** request
  `MANAGE_EXTERNAL_STORAGE`: models in shared storage are imported with the file picker instead.

- **Bump the version in `turbollm/package.json`.** `versionName` is read from it, and
  `versionCode` is derived from the same semver (`1.12.4` → `112040003` — see CI section). There is no version to
  edit in `build.gradle.kts`, on purpose — the app is a shell around that daemon, and two
  independently-bumped version lines would only ever disagree.
- **Run lint yourself:** `./gradlew :composeApp:lintDebug`. Release lint is disabled (see below),
  so nothing else will catch a fatal lint issue for you.
- **Verify the artifact** rather than trusting the build:

  ```bash
  BT="$LOCALAPPDATA/Android/Sdk/build-tools/37.0.0"
  "$BT/aapt2.exe" dump badging composeApp/build/outputs/apk/release/composeApp-release.apk \
    | grep -E "^package:|native-code"
  "$BT/apksigner.bat" verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk
  ```

  Expect `versionName` to match `turbollm/package.json`, `native-code: 'arm64-v8a' 'x86_64'`,
  and the signer DN `CN=TurboLLM`.

## Decisions baked into the release build

### Version comes from the npm package
`versionName` = `turbollm/package.json`'s version; `versionCode` = `(major*10000 + minor*100 +
patch) * 10000 + attempt`. Two digits each for minor and patch, so they cap at 99 — far past anything used so far.

### armeabi-v7a is not shipped
`jniLibs` only carries arm64-v8a and x86_64 engine builds. Listing armeabi-v7a in `abiFilters`
told Play it could serve 32-bit devices, where the daemon starts and then finds no engine to
register — an app that opens and cannot infer. Add the ABI back only alongside an armeabi-v7a
engine build.

### The Vulkan engine ships only behind a flag
`libllama_server_vk.so` — one self-contained static binary, not a second set of `.so`s — is
excluded from packaging unless you ask for it:

```bash
./gradlew :composeApp:bundleRelease -PturbollmVulkanEngine=true
```

`engines/seed.ts` registers the second engine only when the binary is in the APK, so that
exclude is the entire switch. Play build 1130603 went out with the flag on, from fork pin
`72d4bcd` — the commit that fixed the Mali-G78 crashes. It is **not device-verified** as of
2026-09-19, which is why it is opt-in rather than the default; it also costs +38 MB. It does
not change `minSdk`, which stays at 24 — only the Vulkan binary itself needs API 28.

**Build the engine first — it is gitignored, so it is not in a fresh clone.** Inside WSL:

```bash
wsl -d Ubuntu-24.04 -u root -- bash "/mnt/d/llama-turbo/TurboLLM Android/scripts/build-static-engine.sh"
```

On a Windows checkout that had the scripts before `.gitattributes` pinned them to LF, this stops
at once with "set: pipefail: invalid option name" until they are written again; the one-time
commands for that are in
[BUILDING-ENGINES.md](BUILDING-ENGINES.md#the-build-script--scriptsbuild-static-enginesh).

That script verifies the result (AArch64, system-only `NEEDED`, embedded commit == `$SRC`
HEAD, size, sha256) and installs it into `composeApp/src/androidMain/jniLibs/arm64-v8a/`
itself. See [BUILDING-ENGINES.md](BUILDING-ENGINES.md#build-b--the-vulkan-gpu-engine).

Skip that and the flag alone does nothing useful — it only removes a packaging exclude, and
the bundle comes out CPU-only with nothing in the log saying so. `requireVulkanEngineBinary`
(`composeApp/build.gradle.kts`) exists to stop exactly that: with the flag set and the binary
missing it fails the build in seconds, before any compiling. It is wired to `preBuild` and to
Gradle Play Publisher's `publish*` tasks, and only when the flag is set — a default build's
task graph is untouched.

### Release lint is off
`lintVitalAnalyzeRelease` crashes on this project (`KaCallableMemberCall` /
`NonNullableMutableLiveDataDetector` — a lint vs Kotlin analysis API skew), which blocks every
release build. Disabling the single issue id does not help: the detector still loads during the
analysis phase. So `checkReleaseBuilds = false`, and the cost is that release builds no longer
fail on fatal lint issues — run `lintDebug` yourself until the tooling is back in step.

## Publishing to Play Console (Gradle Play Publisher)

Modeled on a second local project (`AndroidStudioProjects/Scout`) that already publishes to Play
this way, with one deliberate difference: Scout commits its own `play-account.json` straight into
git. A live Play publishing credential in git history is a real secret leak regardless of whether
the repo is private, so this project does not repeat that — the credential path lives in
`local.properties` (gitignored) exactly like the signing keystore above, never in the build script
or the repo.

**This is scaffolding, not a working publish pipeline yet.** Two things have to happen before
`publishReleaseBundle` can succeed, and neither is done:

1. **The very first artifact on a brand-new Play Console app must be uploaded through the Play
   Console web UI by hand.** This is a Google Play platform rule, not a limitation of this setup —
   there is no API path around it for an app that has never had anything uploaded before. Every
   `publish*`/`upload*` task this plugin adds will fail until that one manual upload has happened.
2. **No service-account credential exists yet.** Create one in Google Cloud Console, grant it
   "Release manager" (or narrower) access to this app under Play Console → Setup → API access,
   download the JSON key, and point `local.properties` at it:

   ```properties
   TURBOLLM_PLAY_SERVICE_ACCOUNT=/path/to/the-key.json
   ```

Once both are true:

```bash
./gradlew :composeApp:publishReleaseBundle
```

uploads the signed AAB to whatever track `playTrack` resolves to — **`internal`** by default
(Play's internal testing track: up to 100 named testers, no review, live in minutes), deliberately
not Scout's `production` + `COMPLETED`. Override per-invocation:

```bash
./gradlew :composeApp:publishReleaseBundle -PturbollmPlayTrack=closed
```

`./gradlew :composeApp:tasks --group=publishing` lists everything the plugin adds — bootstrapping
the store listing from Play, promoting a release between tracks, internal-sharing uploads, etc.

### Why Gradle Play Publisher 3.13.0, not the latest 4.x

Confirmed live: version 4.1.1 refuses to even apply — "Gradle Play Publisher's minimum Gradle
version is at least Gradle 9.1.0 and yours is Gradle 8.13." This project's whole toolchain (AGP
8.7.3, Kotlin 2.2.20) is still on the Gradle 8.x line; bumping to Gradle 9 to satisfy one plugin
was judged a much bigger, untested change than this task asked for. 3.13.0 is the newest release
still on Gradle 8.x. Revisit the version once the toolchain itself moves to Gradle 9.

## CI: PR builds to the Play alpha track

`.github/workflows/pr-alpha.yml` builds a signed release bundle and runs `publishReleaseBundle`
against the **alpha** track on every PR open / push / reopen (markdown-only changes and fork PRs
are skipped; a newer push cancels an in-flight build for the same PR). It fetches the TurboLLM
daemon from `mohitsoni48/TurboLLM@main`, builds its web UI, fetches `libnode.so` with
`scripts/fetch-libnode.mjs` (the digidem 16 KB-aligned nodejs-mobile build, verified against the
SHA-256s in the script and PROVENANCE.md), stages the LiteRT-LM engine from PyPI, and uses the
tracked CPU llama.cpp engine in `jniLibs/`. The Vulkan engine is not built in CI. Because the daemon
comes from TurboLLM `main`, a PR here that needs daemon changes builds a working alpha only once
those daemon changes are merged.

Secrets live in the **`play-alpha` environment** (Settings → Environments), not as repo secrets. That environment requires the owner's approval for every run, `main` requires an owner-approved PR (`.github/CODEOWNERS`), and fork PRs never get secrets. Already created:

| Secret | Value |
|---|---|
| `TURBOLLM_UPLOAD_KEYSTORE_BASE64` | `base64 -w0 keystore/turbollm-upload.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\turbollm-upload.jks"))`) |
| `TURBOLLM_UPLOAD_STORE_PASSWORD` / `TURBOLLM_UPLOAD_KEY_ALIAS` / `TURBOLLM_UPLOAD_KEY_PASSWORD` | the matching `TURBOLLM_UPLOAD_*` lines of `local.properties` |
| `TURBOLLM_PLAY_SERVICE_ACCOUNT_JSON` | full contents of the Play service-account JSON key |

`build.gradle.kts` reads these as environment variables of the same name (env wins over
`local.properties`), so nothing is written into the checkout.

Things to know:
- **versionCode.** CI passes `-PturbollmReleaseAttempt=<github run number>`. The scheme is now
  `(major*10000 + minor*100 + patch) * 10000 + attempt` (attempt 0–9999, enforced), i.e. `1.13.6`
  attempt 3 → `113060003`. It was `*100`, which only had 100 slots per semver. This is a one-time
  jump above everything shipped under the old scheme.
- **Re-running** a job that already uploaded reuses its run number, so Play rejects the duplicate
  code. Push a new commit instead.
- **One release per track.** Alpha holds a single current release, so with several open PRs the
  last upload wins.
- `-x stagePlayListing`: the listing images are gitignored, and a PR build must not rewrite the
  store listing anyway.

## Not yet automated

Cutting the npm release and the Android release are still two separate flows. Wiring the Android
build (with a freshly compiled engine) into `docs/RELEASE.md` is planned but not done. The Play
publish step above is unverified end-to-end — no service-account credential has been created and
no manual first upload has happened, so no `publish*`/`upload*` task in this project has ever
actually been run.
