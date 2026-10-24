# Android privacy / Data Safety notes

> For whoever fills in Play Console's Data Safety form and/or hosts the public privacy policy
> (both out of scope for this pass — this file only prepares the input). Verified against the
> actual Android app source, not assumed to mirror the desktop app. Checked 2026-09-05 against
> `TurboLLM Android` (package `com.turbollm`, versionName aligned to desktop TurboLLM,
> currently `1.12.4`).
>
> **2026-09-05 correction (ADR-398):** this file originally concluded the app has "zero
> telemetry, zero analytics, zero crash-reporting SDK." That was accurate for the code AS OF THAT
> READ, but founder direction the same day overrides it: **the app will ship with Firebase
> Crashlytics and Firebase Analytics.** Neither is in `build.gradle.kts` yet — this is a
> to-do for the dev team before store submission, tracked in `docs/TODO.md`. Do NOT tell Play
> Console or the public listing "no data collected" — see the revised sections below.

## Headline finding: Android's OWN telemetry system is hard-disabled — but Firebase Crashlytics + Analytics are being added separately

Desktop TurboLLM ships its home-grown telemetry **on by default** (`docs/architecture/08-telemetry-and-consent.md`,
ADR-320). **The Android app hard-disables that specific system.** `MainActivity.kt` launches the
embedded daemon with:

```kotlin
args = arrayOf("--no-telemetry", "--port", ANDROID_DAEMON_PORT.toString()),
```
— `composeApp/src/androidMain/kotlin/com/turbollm/app/MainActivity.kt`, in the `startScript` call
that launches the daemon

`--no-telemetry` is documented as a one-way kill switch in the telemetry doc — no runtime toggle
can turn it back on. So the desktop-style telemetry system (usage levels, event log, opt-out UI
in Settings) genuinely does not apply to this app, and that part of the earlier finding still
holds.

**What's different now:** per founder direction (2026-09-05), the Android app will separately
integrate **Firebase Crashlytics** (crash/exception reports) and **Firebase Analytics** (usage
analytics) — the standard combination most Android apps ship. This is unrelated to the
`--no-telemetry` flag above; it's a second, independent data channel that goes to Google/Firebase,
not to TurboLLM's own telemetry backend. As of this check, neither Firebase SDK is present in
`composeApp/build.gradle.kts` — it needs to be added before this listing goes live, and the public
privacy policy / Data Safety form must describe it accurately once it is.

## What this app actually does over the network

Checked `composeApp/build.gradle.kts` dependencies (ktor client, Lottie, Compose, AppCompat,
Material — no Firebase, no Crashlytics, no ads SDK, no analytics SDK present **yet**; see the
2026-09-05 correction above — Firebase Crashlytics + Analytics are planned additions, not yet in
this file) and grepped all Kotlin source for network calls. Findings below describe the app AS
CURRENTLY CODED; once Firebase is integrated, add a corresponding "app diagnostics" row.

1. **Native app code (Kotlin) only ever talks to `127.0.0.1`**: all of it goes to the embedded
   daemon at `http://127.0.0.1:7000`. `DaemonWebView.kt` loads the UI from there, and
   `DaemonAuth.kt` makes the app's other calls to it: the check that the daemon proves itself
   before the app sends its secret, and the authenticated requests from `EngineService.kt`
   (activity, downloads, status, and the request to write the chat database to disk before
   Quit and before a close with nothing running ends the app), `ModelImport.kt` (adding the
   imported-models folder) and `StorageAccess.kt` (rescan). (`DaemonClient.kt` in `commonMain`
   points at `127.0.0.1:6996`; it is unused Spike A code that only the old `App()` screen, which
   the Android app never shows, constructs.) The web UI in that WebView goes further, though: it
   loads the images its pages point at from any https host, and from any host over plain http
   on port 80. The daemon's CSP for the app allows that on purpose
   (`APP_SECRET_CSP` in its `server.ts`: images and media from `'self'`, any `https:` host,
   `http://*`, which with no port matches plain http on port 80 only, `data:` and `blob:`), and
   `DaemonWebView.kt` refuses only other `127.0.0.1` ports. Those are the images in a model card
   (the README in the Hugging Face dialog, which can embed images from any site) and markdown
   images in chat replies and the other text the UI renders as markdown. An image in a reply loads
   as soon as the reply shows, with no approval, so a reply (after a prompt injection in a pasted
   file or a search result, say) can make the phone contact a host the model names and pass it
   text from the chat in the image's URL. That behaviour is TurboLLM#287's "Known, not fixed
   here" entry "Markdown images in chat", an owner decision; fill in the Data safety form with it
   in mind.
2. **The embedded daemon (the same `turbollm` product code that runs on desktop) makes the same
   categories of outbound request desktop does, all user-initiated except where noted:**
   - Searching and browsing Hugging Face from Discover: opening it lists popular repos, typing
     searches them, and opening a repo fetches its details, its file list and its model card (the
     README; for a repo of checkpoints, their config files too), all from `huggingface.co` over
     HTTPS, with the user's Hugging Face token when one is set (`hf/hf.ts`).
   - Downloading a model you chose, from Hugging Face.
   - Downloading an inference engine, which a normal install never does: the engines ship inside
     the APK (the CPU `llama-server`, the LiteRT-LM server unless a build turns it off, and the
     Vulkan `llama-server` in builds made with it) and are registered from there at every boot
     (`engines/seed.ts`). The app has no way to install another: Android lets it run only code
     installed with it, so its Engines page lists the built-in engines with no install control.
     The daemon downloads one (TurboLLM's own Android build of `llama-server`, from Hugging Face)
     in two cases only: once at boot, not started by you, if no engine in the APK could be
     registered at all, and when a client calls its engine-install API directly, which nothing in
     the app does.
   - Any in-chat tool call you explicitly approve (e.g. `web_search`), same approval-gated flow as
     desktop.
   - **Not present on this build:** the periodic telemetry upload — disabled per above.
   - **Not present in the app either:** desktop's check for a newer TurboLLM, which fetches
     `registry.npmjs.org/turbollm/latest` at every daemon start and when Settings reads a stale
     answer. The app updates through Play, so the daemon skips that check inside it (TurboLLM#287,
     "fix(update): the Android app does not check npm for an app update"); Termux installs, which
     update through npm, keep it.
3. **`AndroidManifest.xml` permissions**, and why each exists:
   - `INTERNET` — the above network use.
   - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` — `EngineService`, a foreground
     service of type `specialUse` that keeps the daemon, the engines and a loaded model running
     while the app is in the background. It starts when the app is opened and runs until Quit in its
     notification, or until the app is closed (Back from its main screen, or swiped away from
     Recents) with nothing running: no model loaded or loading, nothing generating, importing or
     downloading, no reply waiting for an approval, no routine running, and no LAN access, remote
     access or Turbo Link for other devices on. That close ends the app's process about 3 seconds
     later, as Quit does (Quit too waits those 3 seconds), whether notifications are on or off;
     opening the app again within them, or after Quit bringing it back on screen, keeps it running.
     Closed with something running, the service keeps running after that work ends or that access is
     turned off, until Quit or a later close with nothing running (opening the app and closing it
     again, or swiping away the card a Back left in Recents); with notifications off there is no
     Quit, and on Android 13+ the system's list of active apps can stop it. Routines only scheduled
     for later do not count as running: a close with nothing running stops the routine scheduler
     with the app. `PLAY-FGS-DECLARATION.md` has the text for Play's declaration.
   - `POST_NOTIFICATIONS` — that service's ongoing notification, which carries the Quit action and
     the "phone is hot" warning. On Android 13+ the app asks for it each time it starts until it is
     granted; Android stops showing the prompt once the user has declined it twice. The service
     runs either way.
   - `WAKE_LOCK` — a partial wake lock, held only while a reply is being generated, a model
     being loaded, auto-tuned, imported or downloaded, or a skill written, so that work finishes
     with the screen off; never while idle or while a reply waits for the user to approve a tool
     call.
   - `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`, Android 10 and older only
     (`maxSdkVersion="29"`, with `requestLegacyExternalStorage`) — for a model folder the user adds
     in shared storage, such as `/sdcard/AI`: read to scan and load its models, write because the
     daemon downloads into the primary model folder. Asked for, after a dialog, only when such a
     folder is set (`StorageAccess.kt`). On Android 11+ the app requests no storage permission, so
     in shared storage it can read only files it created itself; models are imported with the
     system file picker, which needs none.
   - No location, camera, microphone or contacts permission, and no All files access
     (`MANAGE_EXTERNAL_STORAGE`). `FOREGROUND_SERVICE_DATA_SYNC`, which an earlier build declared
     for a foreground service it never implemented and Play's automated review flagged, is gone;
     the foreground service now in the app is the `specialUse` one above.
     `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which earlier builds declared but never used (no code
     asked for the exemption), is gone too.

## Where data lives

`HOME` is set to the app's private `filesDir` (`MainActivity.kt`), so the daemon's usual
`~/.turbollm`-equivalent state (chats, config, downloaded engines) lands inside Android's
app-private storage, which other apps cannot read. So do the models, unless the user adds a model
folder somewhere else: imported ones go to `files/models/`, and downloads go to the primary model
folder, which is one of the app's own unless the user picks another. A model folder the user adds
can be:
- in shared storage, such as `/sdcard/AI`: the app reads it on Android 10 and older, with the
  storage permission, and downloads into it when it is the primary model folder. Other apps with
  storage access, and a computer over USB, can read the models there. On Android 11+ the app
  cannot read the models the user or another app put there, and offers to import them instead
  (`StorageAccess.kt`). Android still lets it create files under `Download/` and `Documents/`,
  though, so if the primary model folder is in one of those, the daemon's downloads can land in
  shared storage there: the app reads them back, and a computer over USB or an app with All files
  access can read them too.
- in the app's external files folder, `Android/data/com.turbollm/files/`: no permission needed,
  and downloaded into when it is the primary model folder. A computer over USB can read it, and
  on Android 10 and older so can other apps with the storage permission.

Chats and settings stay in `filesDir` either way.

**Backups — one open decision for the dev team before shipping:**
`AndroidManifest.xml` has `android:allowBackup="true"` with backup rules
(`res/xml/data_extraction_rules.xml` for Android 12+, `res/xml/backup_rules.xml` before). They only
exclude models, caches, logs and the extracted daemon under `files/`, and the app's external files
folder as a whole (below); everything else Auto Backup takes by default stays in. So Android's own
Auto Backup (to the user's Google account, not to any TurboLLM server; end-to-end encrypted with
the lock-screen credential on Android 9+ when the phone has one) and device-to-device transfer
carry the rest of the app's own files:

- `files/`: the chat history and the rest of the daemon's state, including its settings file,
  `files/.turbollm/config.json`.
- `app_webview/`: the WebView's own data. Among it are its cookie store (the only cookie the app
  sets is the per-launch app secret, which no longer works after a restart) and the web UI's
  localStorage: UI settings such as the theme and pinned models, the assistant and user names
  and custom instructions set under personalization, each conversation's agent, reasoning effort
  and thinking budget (by conversation id), the id of the chat last open, the draft last typed
  into the text classification playground, and any API key pasted into the web UI's key prompt,
  in plaintext (`tllm.authToken`). The rest is the WebView's other site storage and caches.
- `shared_prefs/`: the app's own preferences (whether the model-folder storage prompt was
  answered), next to any preference files the WebView keeps there for itself (no user data: the
  app grants pages no permissions).

The app's external files folder, `Android/data/com.turbollm/files/` (app-specific external
storage, the "external" domain), is left out of both the backup and the transfer: the app writes
nothing there itself, but a model folder the user adds inside it holds multi-GB models (put there
by the user, or downloaded into it). In a cloud backup they would go over Android's 25 MB quota,
and Android would then skip the whole cloud backup, chats and `config.json` included. Like the
other excluded models, they can be copied or downloaded again. Android itself never backs up
`cache/`, `code_cache/` or `no_backup/`.

When the cloud backup actually runs: Android's scheduled Auto Backup takes an app at most once a
day, and by default only while the phone is idle, charging and on Wi-Fi (any unmetered network, or
mobile data if the user allowed it). It also passes over an app that counts as in the foreground,
and an app running a foreground service does. `EngineService` is one, and it runs from the moment
the app is opened until Quit or until the app is closed with nothing running, so while it runs
Android puts TurboLLM's backup off and tries again 1 to 3 hours later. A phone that keeps the app
open day and night can go a long time without a new TurboLLM backup; one is taken on a night the
app is not running, after Quit or such a close, a reboot, or Android ending the process.
Excluding the models only keeps a backup that does run under the 25 MB quota.
`android:backupInForeground="true"` would remove the wait, but Android stops the app to back it
up, which would unload the model and cut off a reply, so it is not set.

`config.json` also holds, in plaintext, every third-party credential the user has entered: the
Hugging Face and GitHub tokens, the Cloudflare tunnel token, the ngrok authtoken, the Tavily/Kagi
search keys, MCP server keys and env, and the raw Turbo Link peer tokens that grant access to the
user's other TurboLLM machines. (API keys the daemon issues are stored hashed there; one pasted
into the web UI's key prompt is in the localStorage above.) None of these are needed to restore
chats. Either disclose it plainly, or keep the credentials out of backups: moving them
to a separate file the rules exclude takes a daemon change and a config migration; excluding
`config.json` itself would also drop every setting on restore, and excluding `app_webview/`
would drop the web UI's own settings along with a pasted key.

## Suggested Play Console "Data safety" form answers

**Do NOT answer "no data collected."** Once Firebase Crashlytics + Analytics are integrated
(per the 2026-09-05 correction above), the honest answers are:

- **Does your app collect or share any of the required user data types?** Yes. Firebase
  Crashlytics collects **crash logs / diagnostics** (App activity → Crash logs, and Device or
  other IDs for the installation identifier); Firebase Analytics collects **app interactions /
  usage data** (App activity → App interactions, Device or other IDs). Both are shared with
  Google as the processor (Play's form has a specific "shared with Firebase/Google for analytics"
  path — use it, don't mark this as "not shared"). Your own chat content, prompts, files, and
  model weights are NOT collected or shared by either SDK or by TurboLLM. TurboLLM itself
  collects nothing, but an image in a model reply loads from the host it names (any https host,
  or plain http on port 80) as soon as the reply shows, so text a reply puts in an image's URL
  reaches that host (item 1 under "What this app actually does over the network").
- **Is data encrypted in transit?** Yes for Firebase's traffic (Google's SDKs use HTTPS/TLS).
  N/A for the app's own loopback traffic; Hugging Face/GitHub downloads use HTTPS. Images in a
  reply or a model card can load over plain http, on port 80 (item 1).
- **Can users request data deletion?** Yes for local app data — uninstalling removes it, and there
  is no TurboLLM-side account/record. For Firebase-held crash/analytics data, follow Firebase's
  own data deletion process (Google Analytics for Firebase supports user-level deletion requests) —
  confirm the exact mechanism before publishing the privacy policy's deletion-request section.
- **Is data collection optional?** Check whether the Firebase integration will offer an opt-out
  (e.g. `setAnalyticsCollectionEnabled(false)` / `setCrashlyticsCollectionEnabled(false)` behind a
  Settings toggle) before finalizing this answer — not decided as of this note.

## What still needs the parallel privacy-policy update

`docs/site-build/pages/docs/privacy.html` (read live, 2026-09-05) has **no Android-specific
section yet** — it describes only the desktop telemetry-on-by-default model. Whoever owns that
page (a separate agent, per this task's scope) needs to add language covering:
1. The Android build ships with telemetry hard-off, not just defaulted differently.
2. The same "when TurboLLM touches the network" categories apply, minus the telemetry row.
3. A note on where Android app data physically lives (app-private storage, not `~/.turbollm`).

This file is the input for that update — not a replacement for it, and not something this pass
publishes anywhere.
