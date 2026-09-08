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
— `composeApp/src/androidMain/kotlin/com/turbollm/app/MainActivity.kt:69`

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

1. **Native app code (Kotlin) only ever talks to `127.0.0.1`** — `DaemonClient.kt` and
   `DaemonWebView.kt` both hit `http://127.0.0.1:7000` (the embedded daemon), nothing external.
2. **The embedded daemon (the same `turbollm` product code that runs on desktop) makes the same
   categories of outbound request desktop does, all user-initiated:**
   - Downloading a model you chose, from Hugging Face.
   - Downloading the inference engine binary the first time it's needed (an NDK-cross-compiled
     `llama-server`, hosted on TurboLLM's own GitHub/Hugging Face releases — ADR-391).
   - Any in-chat tool call you explicitly approve (e.g. `web_search`), same approval-gated flow as
     desktop.
   - **Not present on this build:** the periodic telemetry upload — disabled per above.
3. **`AndroidManifest.xml` permissions**, and why each exists:
   - `INTERNET` — the above network use.
   - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — lets the user optionally exempt TurboLLM from the OS
     killing an in-progress local inference run.
   - No location, camera, microphone, contacts, notification, or broad storage permission
     requested. (`FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`/`POST_NOTIFICATIONS` were
     declared in earlier builds for a foreground-service notification that was never actually
     implemented — the daemon just runs on a background thread inside the activity — and were
     removed after Play's automated review flagged the unused `FOREGROUND_SERVICE_DATA_SYNC`
     grant.)

## Where data lives

`HOME` is set to the app's private `filesDir` (`MainActivity.kt`), so the daemon's usual
`~/.turbollm`-equivalent state (chats, config, downloaded models/engines) lands entirely inside
Android's app-private storage — not shared storage, not readable by other apps.

**One open item, not yet resolved in code — flag for the dev team before shipping:**
`AndroidManifest.xml` has `android:allowBackup="true"` with no `dataExtractionRules`/
`fullBackupContent` exclusion file present. That means Android's own Auto Backup (to the user's
Google account, not to any TurboLLM server) could sweep up the app's private files — including
local chat history — into the user's device backup by default. This is a very different privacy
surface than "nothing ever leaves the device" (it's the user's own Google backup, not a third
party TurboLLM controls, but it's still data leaving the device to *somewhere*). Recommend either
(a) adding a backup-rules XML that excludes the daemon's data directory, or (b) disclosing it
plainly if the team decides the Google-account-only exposure is acceptable. Not fixed here — out
of scope for asset prep — but should not be silently left unaddressed.

## Suggested Play Console "Data safety" form answers

**Do NOT answer "no data collected."** Once Firebase Crashlytics + Analytics are integrated
(per the 2026-09-05 correction above), the honest answers are:

- **Does your app collect or share any of the required user data types?** Yes. Firebase
  Crashlytics collects **crash logs / diagnostics** (App activity → Crash logs, and Device or
  other IDs for the installation identifier); Firebase Analytics collects **app interactions /
  usage data** (App activity → App interactions, Device or other IDs). Both are shared with
  Google as the processor (Play's form has a specific "shared with Firebase/Google for analytics"
  path — use it, don't mark this as "not shared"). Your own chat content, prompts, files, and
  model weights are NOT collected or shared by either SDK or by TurboLLM.
- **Is data encrypted in transit?** Yes for Firebase's traffic (Google's SDKs use HTTPS/TLS).
  N/A for the app's own loopback traffic; Hugging Face/GitHub downloads use HTTPS.
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
