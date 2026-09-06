# Releasing the TurboLLM Android app

Build steps and the decisions baked into them. For the engine binaries themselves see
[BUILDING-ENGINES.md](BUILDING-ENGINES.md); for the daemon that ships inside the APK see its
[daemon payload](BUILDING-ENGINES.md#the-daemon-payload--what-makes-the-apk-self-sufficient)
section.

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

- **Bump the version in `turbollm/package.json`.** `versionName` is read from it, and
  `versionCode` is derived from the same semver (`1.12.4` → `11204`). There is no version to
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
`versionName` = `turbollm/package.json`'s version; `versionCode` = `major*10000 + minor*100 +
patch`. Two digits each for minor and patch, so they cap at 99 — far past anything used so far.

### armeabi-v7a is not shipped
`jniLibs` only carries arm64-v8a and x86_64 engine builds. Listing armeabi-v7a in `abiFilters`
told Play it could serve 32-bit devices, where the daemon starts and then finds no engine to
register — an app that opens and cannot infer. Add the ABI back only alongside an armeabi-v7a
engine build.

### The Vulkan engine is built but not shipped
`libllama_server_vk.so` is excluded from packaging. It works as a build; it does not work as a
feature — on the two devices tested it either refuses to initialise (Adreno 616, "Vulkan 1.2
required") or initialises and then crashes (Mali-G78), and there its mere presence took the
working CPU engine down with it. `engines/seed.ts` registers the second engine only when the
binary is in the APK, so excluding the file is the entire switch:

```bash
./gradlew :composeApp:bundleRelease -PturbollmVulkanEngine=true   # once it is proven
```

The intent is to ship both engines as a user choice — see BUILDING-ENGINES.md for what has to
be true first.

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

## Not yet automated

Cutting the npm release and the Android release are still two separate flows. Wiring the Android
build (with a freshly compiled engine) into `docs/RELEASE.md` is planned but not done. The Play
publish step above is unverified end-to-end — no service-account credential has been created and
no manual first upload has happened, so no `publish*`/`upload*` task in this project has ever
actually been run.
