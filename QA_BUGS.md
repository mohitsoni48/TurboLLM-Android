# TurboLLM Android — QA Bug Report

**Date:** 2026-09-04
**Build tested:** `composeApp-debug.apk`, versionName `0.1.0-spike-a`, versionCode 1
**Device:** Android emulator `Pixel_10` (API/Android 17 preview image, `sdk_gphone16k_x86_64`), 1080×2424 @ 420dpi, gestural navigation
**Tools used:** `adb`, Maestro 2.6.1, Chrome DevTools Protocol (via `WebView.setWebContentsDebuggingEnabled` + `adb forward`), `uiautomator dump`
**Screenshots:** all evidence referenced below lives in [`qa/screenshots/`](qa/screenshots/) in this repo.

---

## RETEST — 2026-09-04, later build (commit `f18458b`, fresh APK built 15:21)

Re-tested after `f18458b` claimed to fix BUG-01, BUG-02, BUG-05, BUG-08. Verdict: **regressed, not
fixed** — the app now crashes on 100% of launches, before it even reaches the point any of those
four fixes could matter. This is a new, more severe P0 than anything in the original pass.

### BUG-14 (NEW, P0): App crashes with SIGSEGV on every single launch — daemon never boots
Freshly installed the rebuilt APK (`composeApp-debug.apk`, rebuilt 2026-09-04 15:21, same-day as
the fix commit) on a clean emulator boot. Every launch attempt (3/3) crashes silently within ~1.5s
— no error dialog, no crash UI, just an instant snap back to the home screen mid-launch-animation
(see [retest-02-t1s.png](qa/screenshots/retest-02-t1s.png), caught mid-icon-zoom-out as Android
tears the task down). `logcat` confirms a real native crash both times:
```
E nodejs  : Error [ERR_UNKNOWN_BUILTIN_MODULE]: No such built-in module: node:sqlite
E nodejs  :     at new NodeError (node:internal/errors:405:5)
E nodejs  :     at ModuleLoader.builtinStrategy (node:internal/modules/esm/translators:300:11)
E nodejs  : Node.js v18.20.4
...
I Zygote  : Process 3404 exited due to signal 11 (Segmentation fault)
```
**Root cause, fully traced:** this is *not* a device/cache artifact — it's a real release gap.
- `turbollm/src/chat/store/sqlite-adapter.ts` (main `llama-turbo` repo) is correctly gated —
  `openSqlDb()` only calls `requireSync('node:sqlite')` when `process.platform !== 'android'`,
  exactly as intended (see that file's own header comment explaining why a *static* top-level
  `import ... from 'node:sqlite'` would crash Android's Node 18.20.4 the instant the module loads).
- But `turbollm/src/chat/db.ts` used to have its own separate top-level
  `import { DatabaseSync } from 'node:sqlite'` that bypassed the adapter entirely. That's fixed in
  the *current* local source (`db.ts` now correctly imports `openSqlDb` from `sqlite-adapter.js`
  — verified by reading the file directly), and the *locally built* `turbollm/dist/cli.js`
  reflects that fix correctly.
- **The published `turbollm@1.12.2` npm package does not.** Downloaded the actual tarball npm
  serves right now (`https://registry.npmjs.org/turbollm/-/turbollm-1.12.2.tgz`) and its bundled
  `dist/cli.js` still contains the broken static import at line 14488:
  `import { DatabaseSync } from "node:sqlite";` — byte-identical to what's sitting in this
  device's cached daemon at `/data/data/com.turbollm.android/files/daemon/dist/cli.js:14554`
  (pulled directly off the device with `run-as` to confirm). The package.json `version` field is
  `1.12.2` in all three places (local source, local dist, published npm) — **the fix commit landed
  in git but the version was never bumped and the package was never republished**, so npm is still
  serving the pre-fix build, and every Android install (this one included, cache or fresh) gets it.
- The Android app itself has no fallback here: `DaemonWebView.kt`/`NodeRuntimeLauncher.android.kt`
  hand the daemon whatever's in the npm tarball straight to the embedded Node 18.20.4 runtime,
  which has never had `node:sqlite` at any version — so `db.ts`'s top-level import throws
  synchronously on the very first module evaluation, before the daemon's HTTP server, the WebView,
  or *any* app UI exists. That's why nothing ever appears — not even the "Starting TurboLLM…"
  splash from the first QA pass.
**Impact:** every one of BUG-01/02/05/08's fixes (all inside the Android app repo) is currently
**unverifiable** — the app can't reach the point where any of them would be exercised. This is
strictly worse than the original P0 (BUG-01: model load fails) since that at least got you to a
usable chat shell; this crashes before any screen renders at all.
**Fix:** bump `turbollm/package.json`'s version and run the project's normal release/publish step
(`docs/RELEASE.md` in the main repo) so npm actually serves a `dist/cli.js` matching current
`main` — then reinstall/re-fetch the daemon on the Android app and retest. No Android-app-side
change is needed for this specific bug; the fix already exists, it's just not released.
**Evidence:** [retest-01-launch.png](qa/screenshots/retest-01-launch.png) through
[retest-04-t4s.png](qa/screenshots/retest-04-t4s.png) (home screen every time, no app UI ever
painted); root-cause trace in this session's transcript (full `logcat`, pulled `dist/cli.js` from
both the device and the npm tarball, `grep`-diffed against local `turbollm/src`).

### Everything else in this pass
Untestable as a direct result of BUG-14 — the app cannot progress past the crash to reach the
Workspace/Models/Engines screens where BUG-01 through BUG-13 live, so none of them could be
re-verified this session. Recommend re-running this full retest once BUG-14 is resolved (republish
`turbollm` to npm); until then treat every bug below as **still open, unconfirmed-fixed**,
regardless of what the `f18458b` commit message claims to have addressed.

---

## ORIGINAL PASS — 2026-09-04, first build

## How to read this report

The app is a native Compose shell hosting one full-screen `WebView` that renders TurboLLM's
existing desktop web UI (`http://127.0.0.1:7000`). Because the WebView exposes a single opaque
node to the Android accessibility tree (see BUG-13), most of these bugs had to be verified two
ways: (a) a real on-device tap via `adb`/Maestro, cross-checked against (b) the DOM's actual
`getBoundingClientRect()` via Chrome DevTools Protocol, so coordinates below are not guesses —
every "tap here" bug was reproduced against the live layout.

---

## P0 — Blocking / broken functionality

### BUG-01: Model load fails on every attempt — native engine binary missing (ENOENT)
Tapping **Load** on the only installed model (`Gemma-4-E4B-It`, Models tab) fails every time with:
```
Model load failed: spawn
/data/app/~~Cixf90TYK0GOHNRoWBV3jQ==/com.turbollm.android-BVGoIBuM6BW6d9PoN6jkQ==/lib/x86_64/libllama_server.so
ENOENT (exit -1)
```
The Engines tab confirms it: the active engine `llama-server-android` shows a persistent **Error**
badge with the same ENOENT, and "No log output captured." Chat is completely unusable as a result
— you cannot get past "Select a model above to begin" no matter what you do.
Root cause: the APK's bundled native library is missing/wrong for the `x86_64` ABI the emulator
reports (log path literally ends in `/lib/x86_64/libllama_server.so`). This may well work today
on the real arm64 phone the team already validated, but it means the app hard-fails on any x86_64
device or emulator (including some Chromebooks and older tablets), with no fallback and no
pre-flight ABI check — the failure only surfaces after the user has already downloaded/selected a
model and pressed Load.
**Evidence:** [qa-32-model-loading.png](qa/screenshots/qa-32-model-loading.png), [qa-34-engines-tab.png](qa/screenshots/qa-34-engines-tab.png)
**Fix suggestion:** bundle (or gracefully detect/download) an engine binary for every ABI you ship
`android:extractNativeLibs`/`abiFilters` for, and fail fast with a clear "your device architecture
isn't supported yet" message before the user goes through the model-download flow.

### BUG-02: Android Back exits the app instead of closing an open drawer/overlay
With the conversation-history drawer open (see BUG-03), pressing the system **Back**
gesture/button does not close the drawer — it exits the Activity straight to the home screen.
Reproduced twice. This is the single most user-hostile bug found: any user who opens the chat
history panel and instinctively taps Back (which is idiomatic Android for "close this
overlay") gets kicked out of the app entirely instead.
**Evidence:** drawer open in [qa-28-retap-hamburger-3x.png](qa/screenshots/qa-28-retap-hamburger-3x.png) → home screen in [qa-29-back-button-test.png](qa/screenshots/qa-29-back-button-test.png) after one Back press.
**Fix suggestion:** intercept the system back callback (Compose/Activity `OnBackPressedCallback`,
or listen for it inside the WebView via the JS bridge) while any modal/drawer/sheet is open and
close that first; only fall through to exiting the Activity when the app is at its root state.

---

## P1 — Major usability / layout defects

### BUG-03: Header controls (menu, model picker, sliders, thinking-budget) sit under the status bar and are unreliable to tap
The whole top toolbar is laid out with zero top safe-area inset. Confirmed via CDP that the menu
button's actual clickable center is at **physical (74, 62)** — inside both the status bar
(`[0,142]`) *and* Android's `mandatorySystemGestures` top zone (`[0,174]`), which the OS reserves
for pulling down the notification shade.
Practical effect: real single taps (via `adb input tap`, tried 3× on the exact coordinate CDP
reported) on the "Open conversations" menu button **did not open the drawer**. The exact same
coordinate opened it instantly when the click was dispatched programmatically inside the page
(`el.click()`). In other words, the button is visually present and functional in the DOM, but a
real user's finger is fighting the system gesture-exclusion zone to hit it. The same applies to
the model-picker pill, the sliders icon, and the "Thinking budget" (brain) icon — all four sit at
physical y≈62, all inside the same unsafe zone.
**Evidence:** [qa-02-crop-top.png](qa/screenshots/qa-02-crop-top.png) (clock "2:17" rendering
directly behind/through the sidebar icon and model pill); repeated failed real-tap attempts in
[qa-03](qa/screenshots/qa-03-sidebar-tap.png)/[qa-05](qa/screenshots/qa-05-tap-retry.png) vs.
successful programmatic click in [qa-27](qa/screenshots/qa-27-after-programmatic-click.png).
**Fix suggestion:** apply `WindowInsets.statusBars` (or the CSS `env(safe-area-inset-top)`
equivalent via the WebView's inset handling) as top padding on the header bar, same as any other
edge-to-edge Android app.

### BUG-04: Bottom tab bar overlaps the gesture-navigation home indicator
The 7-item bottom tab bar (Workspace/Models/Engines/Customize/Usage/Developer/Settings) has no
bottom safe-area inset either. The system's opaque gesture-navigation pill (confirmed via
`dumpsys window` insets: `navigationBars frame=[0,2361][1080,2424]`) renders directly on top of
the "Customize" and "Usage" labels, visually slicing through the text and shrinking their already
small tap targets right at the bottom edge where mis-taps are easiest.
**Evidence:** [qa-02-crop-bottom.png](qa/screenshots/qa-02-crop-bottom.png)
**Fix suggestion:** pad the tab bar container by `WindowInsets.navigationBars` at the bottom.

### BUG-05: Bottom tab bar is not pinned — floats mid-screen with a large dead gap on short pages
On tabs whose content is short (e.g. **Models** with only one downloaded model, or **Engines**
before scrolling), the 7-item tab bar is *not* anchored to the bottom of the viewport. It renders
immediately after the content instead, leaving 40-45% of the screen as blank white dead space
below it. On the **Workspace** tab (also fairly empty — "Select a model above to begin" plus a
disabled composer) the same tab bar correctly sticks to the true bottom of the screen. This
inconsistency makes some screens look broken/unfinished and wastes a huge amount of vertical
space on a phone screen.
**Evidence:** compare [qa-06-models-tab.png](qa/screenshots/qa-06-models-tab.png) (tab bar
pinned, Workspace) vs. [qa-31-models-tab.png](qa/screenshots/qa-31-models-tab.png) (tab bar
floating ~40% up the screen, Models tab with 1 model, huge white gap below it).
**Fix suggestion:** give the shared app-shell layout a real `min-height: 100dvh` flex column with
the tab bar as a `flex-shrink: 0` footer, or `position: sticky; bottom: 0`, so it behaves
identically regardless of per-tab content height.

### BUG-05b: Discover tab's model list has a fixed 45vh cap that crops the last row mid-line, and the selected model's detail pane is out of view below it
On the Models → **Discover** tab, the scrollable results list sits inside a container styled
`max-h-[45vh]` (confirmed via computed style: `max-height: 415.35px`, i.e. exactly 45% of the
923px CSS viewport height) wrapping an inner `overflow-y-auto` list whose real content is
**1902px tall** (many more trending results than fit). At 415px/~59px-per-row, that's room for
~5.7 rows — so the box doesn't stop on a row boundary, it slices the 6th row clean in half:
`orcarouter/Qwen3.8-Flash-Next-Uncensored-G…`'s avatar and title are cut off mid-glyph with no
fade/gradient hinting there's more below.
Compounding it: tapping a model **does** correctly select it (confirmed — orange highlight
border appears) and populates a details/"Loading repo…" panel, but that panel is a *separate*
block stacked below the already-cramped 45vh list, not a replacement view or a same-viewport
detail column. On a real phone this means selecting a model and then finding out what you
selected requires scrolling past the clipped list every time — a two-pane desktop layout
(list | details side-by-side) that was collapsed to mobile by stacking instead of adapting, so the
thing you just tapped is routinely off-screen.
The **Library** tab (currently only 1 downloaded model, so nothing to clip yet) does *not* use
this same `max-h-[45vh]` wrapper — its list container measured `max-height: none` — so it isn't
at risk of the mid-row clipping specifically, but it shares the exact same "big blank dead zone
below a small content block" symptom described in BUG-05 above, which is likely what reads as
"cut in half" there: the useful content occupies roughly the top half of the screen and the
bottom half is empty rather than the tab bar/content adapting to it.
**Evidence:** [qa-40-discover-tab.png](qa/screenshots/qa-40-discover-tab.png) (full view),
zoomed crop at [crop-discover-list-clipped-row2.png](qa/screenshots/crop-discover-list-clipped-row2.png)
(the sliced-in-half row), [qa-41-discover-select-model.png](qa/screenshots/qa-41-discover-select-model.png)
(selected row highlighted, detail pane with "Loading repo…" stacked below the still-clipped list).
**Fix suggestion:** on mobile widths, either drop the `45vh` cap and let the list flow naturally
while pushing model details to a dedicated screen/route on tap (recommended — matches how every
other mobile app-store-style list works), or at minimum (a) size the max-height to a clean
multiple of the row height so it never crops mid-row, and (b) auto-scroll the newly-selected
row's detail panel into view (or the page to it) when a model is tapped so the result of the tap
is never off-screen.

### BUG-06: Conversation-history drawer's "Chat"/"Code" toggle is illegible
Opening the hamburger drawer (conversation list) renders a pill-shaped Chat/Code segmented toggle
at the very top of the drawer, in the exact same unsafe top zone as BUG-03. The status bar clock
and system icons render directly on top of the pill's text, producing an unreadable overlapping
mess (looks like "🔧2 Ⓒhat🔧" instead of legible "Chat / Code" labels).
**Evidence:** [qa-27-after-programmatic-click.png](qa/screenshots/qa-27-after-programmatic-click.png),
zoomed crop at [crop-drawer-toggle-garbled.png](qa/screenshots/crop-drawer-toggle-garbled.png)
**Fix suggestion:** same top-inset fix as BUG-03, applied inside the drawer's own header too (it's
a separate scroll container so it needs the padding independently).

### BUG-07: Persistent model-load error banner is not dismissible and repeats on every tab
Once the model-load error from BUG-01 fires, a large (~300px tall) red error banner is pinned to
the top of *every* tab (Workspace, Models, Engines, etc. — confirmed on 3 different tabs). There is
no visible close/dismiss (×) control, so the only way to get the vertical space back is to fix the
underlying engine error. It also inherits the top-safe-area problem (BUG-03): the green battery
icon pokes out from behind it at the very top-right corner.
**Evidence:** [qa-32-model-loading.png](qa/screenshots/qa-32-model-loading.png),
[qa-34-engines-tab.png](qa/screenshots/qa-34-engines-tab.png) (same banner, different tab),
zoomed crop at [crop-error-banner-statusbar.png](qa/screenshots/crop-error-banner-statusbar.png)
**Fix suggestion:** add a dismiss control, and/or scope the banner to the Workspace/Engines tabs
where it's actionable instead of showing globally.

---

## P2 — Polish / smaller issues

### BUG-08: Blank white/pink flash on cold start with zero loading feedback
Launching via a full `force-stop` → cold start (as Maestro's `launchApp` does) produced a
completely blank, off-white/pink screen — no "Starting TurboLLM…" text, no spinner, nothing but
the status bar — for a measurable stretch after the Android splash screen hands off to the
Compose/WebView content, before "Starting TurboLLM…" (or the loaded UI) appears. Reproduced twice
via Maestro; a plain `adb am start` cold launch (not preceded by force-stop) showed the
"Starting TurboLLM…" splash correctly at the same timing, so this looks specific to the
force-stop→cold-start path and may be a native-splash-to-WebView handoff race under load.
**Evidence:** [qa-maestro-tap1.png](qa/screenshots/qa-maestro-tap1.png) (blank, ~1s in),
[qa-maestro-loaded.png](qa/screenshots/qa-maestro-loaded.png) (still blank 8s later, same run).
**Fix suggestion:** keep the native splash (with the TurboLLM mark) visible until the WebView
fires its first paint/`onPageFinished`, rather than tearing it down on a timer.

### BUG-09: Splash-screen loading indicator is a static, non-animating glyph
The "Starting TurboLLM…" splash shows what should be a spinner but renders as a single static
curved stroke that looks like a stray parenthesis — it doesn't visibly animate/spin in the
screenshots taken a few seconds apart, undermining the "something is happening" cue during the
~3-5s daemon boot.
**Evidence:** [qa-01-launch.png](qa/screenshots/qa-01-launch.png), [qa-16-cold-t2.png](qa/screenshots/qa-16-cold-t2.png)
**Fix suggestion:** verify the spinner animation actually runs inside the WebView at this stage
(it may be CSS-animated but janked/frozen because the JS thread is busy booting the daemon) — if
so, use a natively-animated (Compose) spinner here instead so it's not blocked by app boot.

### BUG-10: Forced portrait orientation — landscape is completely ignored
Rotating the device to landscape (confirmed the OS itself rotated — home screen, launcher icons,
and status bar all correctly went landscape) has **no effect on the app**: relaunching it in
landscape still renders the full portrait 1080×2424 layout unrotated. This is very likely an
intentional `screenOrientation="portrait"` lock rather than a bug, but flagging it for an explicit
product decision — TurboLLM is fundamentally a text-heavy chat/config app that some users will
expect to be usable in landscape (e.g. with a folding phone or a bluetooth keyboard propped up).
**Evidence:** [qa-37-landscape.png](qa/screenshots/qa-37-landscape.png) (home screen correctly
landscape) vs. [qa-38-landscape-app.png](qa/screenshots/qa-38-landscape-app.png) (app still full
portrait dimensions after being launched while the device is in landscape).
**Fix suggestion:** if intentional, no action needed beyond confirming with product; if not,
remove the orientation lock and verify the desktop-oriented layout doesn't break in landscape.

### BUG-11: "Show launch command and diagnostics" (Engines error card) appears unresponsive
Tapping the "Show launch command and diagnostics" link/button under the model-load error did not
visibly expand any additional content in three attempts. Lower confidence than the header-button
findings above (I did not cross-verify this one against CDP click dispatch the way I did for
BUG-03), but worth a look since diagnostics are exactly what a user needs after BUG-01.
**Evidence:** [qa-32-model-loading.png](qa/screenshots/qa-32-model-loading.png) vs.
[qa-33-diagnostics.png](qa/screenshots/qa-33-diagnostics.png) (visually identical after the tap).

### BUG-12: Unlabeled black pill placeholder under the error banner
Directly below the "Show launch command and diagnostics" / "Retry" buttons on the error card,
there's an empty solid-black rounded bar (~830px wide, no visible text or icon) that reads as a
broken/glitched UI element — likely a log-output or progress-bar component rendering with no
content and default (black) background instead of being hidden when empty.
**Evidence:** [qa-32-model-loading.png](qa/screenshots/qa-32-model-loading.png)
**Fix suggestion:** hide the element entirely when there's no log/progress to show, matching the
"No log output captured." placeholder text used elsewhere on the same screen.

---

## Accessibility

### BUG-13: WebView content is completely invisible to the Android accessibility tree
`uiautomator dump` of the app's entire UI — regardless of which tab/screen is showing — returns a
single opaque `android.webkit.WebView` node marked `NAF="true"` ("not accessibility-friendly")
with no children. Every button, label, input, and heading in the app (all of it, since the whole
UI is one WebView) is invisible to TalkBack and any other accessibility service, and to
UI-Automator/Espresso-style test tooling. This also explains why Maestro's `assertVisible`/
`waitForVisible` (which walk the accessibility tree) could not find any on-screen text during this
session — only raw coordinate taps and CDP worked.
**Evidence:** `window_dump.xml` captured mid-session (not committed; the dump was a single
`<node class="android.webkit.WebView" NAF="true" ... />` with zero descendants).
**Fix suggestion:** verify the WebView's `setWebContentsDebuggingEnabled`/accessibility settings —
Chrome's accessibility bridge for WebView needs to be explicitly enabled
(`WebView.setAccessibilityDelegate` alone isn't enough); confirm `AccessibilityNodeProvider` is
wired up, and manually verify with TalkBack turned on that at least primary chat controls are
announced.

---

## Notes on testing methodology (for whoever picks this up)

- The WebView's CSS viewport is **411×923** at `devicePixelRatio: 2.625`, not the physical
  1080×2424 — a naive "read pixel position off a screenshot, tap that pixel" approach is off by
  ~2.6x and will silently hit the wrong element. All coordinates in this report were taken from
  `getBoundingClientRect()` via CDP and converted with `physical = css * dpr`, then verified against
  a screenshot.
- Remote debugging is already enabled in the app (`DaemonWebView.kt:143`,
  `WebView.setWebContentsDebuggingEnabled(true)`) — the fastest way to keep testing this build is
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` (get `<pid>` from
  `adb shell cat /proc/net/unix | grep devtools`) and drive it over the Chrome DevTools Protocol;
  `uiautomator`/Maestro element selectors will not work (see BUG-13).
- Not tested this pass, worth a follow-up session once BUG-01 is fixed: actually sending a chat
  message and getting a real completion, the Customize/Usage tabs' content, the API-key creation
  flow on the Developer tab, and dark mode.
