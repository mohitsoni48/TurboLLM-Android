# TurboLLM Android — QA / UX Report

**Date:** 2026-09-05
**Device:** Realme RMX1921 (serial `cef913fd`), Android 11, API 30, arm64, 1080×2340, on-screen nav bar (not gestural)
**Build under test:** `com.turbollm.android`, versionName `1.12.4`, versionCode 1 (already installed; not rebuilt/reinstalled for this pass)
**How driven:** adb (screenshots, input, logcat, dumpsys), and the app's own Chrome DevTools Protocol harness (`.omc/qa/qa.mjs` — `dump`/`audit`/`shot`/`click`/`input`/`eval`/`events`) forwarded over `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`. No source files were edited, no rebuild/reinstall was performed, and the app was not force-stopped except where a genuine hardware-back defect (F-01) killed it on its own.
**Screenshots:** all referenced files are under `C:\Users\Owner\AppData\Local\Temp\claude\D--llama-turbo\24a4cd32-737e-4001-b403-1ff47a82a9a7\scratchpad\`.

Screens covered: Workspace (Chat + Code), conversation drawer, Models (Library + Discover), model config sheet, Engines (including Add-engine dialog), Customize (Agents/Skills/MCP Servers), Usage (Overview/Models/API), Developer (API keys, connect-an-app), Settings (General/Models & loading/Tools & safety). A model (`qwen2.5-0.5b-instruct`, Q2_K, CPU-only) was loaded and a real chat message was sent and completed end-to-end.

A note on method: two Radix-style popovers (the Code tab's "Choose a repo" menu, and a model's "…" actions menu) did not respond to synthetic JS `.click()` calls — real `adb input tap` opened them fine. Anywhere a menu "did nothing" under CDP I re-verified with a real tap before concluding anything was broken; this is called out per-finding.

---

## Findings by severity

### P1 — blocks release

#### F-01: Hardware Back button exits the whole app instead of closing the "Add your own engine" dialog
> **RESOLVED 2026-09-05**, verified on-device with real taps: Back now closes the dialog and stays
> on Engines; a further Back goes Engines → Chat; a Back at the root screen exits, as it should.
> The same sweep was run against the Discover detail sheet, the engine Delete confirmation, and the
> conversations drawer — all four now pass. Two independent defects were behind this one report:
> 1. **The web fix**: `useBackableOverlay` listed `onClose` in its `popstate` effect deps. Every
>    caller passes an inline closure, so a genuine back — handled by the router's listener first —
>    re-rendered the caller, changed `onClose`'s identity, and made the effect remove and re-add its
>    own listener *inside the same dispatch*. A listener removed mid-dispatch is never invoked, so
>    the overlay never closed. It now subscribes once and reads `onClose` from a ref.
> 2. **The native fix**: `DaemonWebView.kt` gated `BackHandler` on a `canGoBack` value mirrored from
>    WebViewClient callbacks; it asks `webView.canGoBack()` at press time instead, and finishes the
>    Activity itself only when there is genuinely nowhere to go.
>
> **Caveat for future QA runs — trust real taps, not `qa.mjs click`.** That helper fires
> `element.click()`, which is not a user gesture, so Chromium's history-manipulation intervention
> marks the resulting `pushState` entry *skippable* and `canGoBack()` reports **false**. Back then
> exits the app **even when the app is correct**. Any "Back exits the app" finding driven by
> synthetic clicks must be re-confirmed with `adb shell input tap` before it is believed. Mapping
> for this device: `physical = (css_x × 3, css_y × 3 + 96)`.
- **Screen:** Engines → "Add engine" → "Add your own engine" dialog.
- **Steps:** Engines tab → tap **Add engine** → dialog opens ("Add your own engine", with Choose folder / Cancel / X) → press the device **Back** button.
- **Expected:** Back dismisses the dialog and returns to the Engines list (the dialog's own **X** button and **Cancel** both do exactly this).
- **Actual:** The entire app is torn down to the home launcher. Confirmed via `dumpsys activity activities`: before Back, `mResumedActivity` is `com.turbollm.android/.MainActivity`; immediately after one Back press it is `com.oppo.launcher/.Launcher`. Relaunching the app shows a brand-new activity (new PID each time — 12002 → 14208 → 14969 across the session), i.e. the task was fully killed, not just backgrounded. **Reproduced twice in a row**, same result both times.
- **Isolation:** The dialog's **X** button was verified separately and closes it correctly without affecting the app (screenshot 42), so the dialog's dismiss logic is fine — specifically the Android hardware-back handler for this dialog is missing/not wired to intercept back-press, so it falls through to the Activity/system default (finish task).
- **Evidence:** `30-dialog-open-confirm.png` (dialog open, confirmed via screenshot before pressing back), `dumpsys` output showing `mResumedActivity: ...com.oppo.launcher/.Launcher` immediately after Back, `29-relaunch-check.png` / `31-check-state.png` (launcher/relaunch), `42-x-close-confirm.png` (X works correctly, isolating the bug to Back specifically).
- **Confidence:** High — reproduced twice with hard evidence (activity dump), not a guess.
- **Why P1:** this is exactly the "does Back close it or exit the app" check called out in the test brief, and it fails in the most damaging way (full app kill, not just an unwanted screen change). Any reviewer or user who opens this dialog and instinctively taps Back loses their place in the app entirely.

---

### P2 — should fix before release

#### F-02: Usage → Models chart defaults to a scroll position that hides the only data point, looking empty
> **RESOLVED 2026-09-05.** DailyBarChart now opens scrolled to the right edge, the same
> `useLayoutEffect` ActivityHeatmap already used for the same reason. Verified on-device: the same
> chart (`scrollWidth` 1090 / `clientWidth` 294) now loads at `scrollLeft` 796 with today's bar
> visible instead of empty axis.
- **Screen:** Usage tab → Models sub-tab.
- **Steps:** Usage → Models (with "All" time range selected, the default). Chart's `overflow-x-auto` container loads with `scrollLeft = 0`.
- **Expected:** A chart for a user with exactly one day of usage should either default to showing that data, or make it obvious more content is scrollable.
- **Actual:** The chart renders with only axis labels/gridlines visible (looks completely empty — no bar, no line) even though the Overview tab and the legend directly below the chart ("Qwen2.5-0.5b-Instruct · 2.3K in · 18 out · 100.0%") prove data exists. The chart's scrollable area is `scrollWidth: 1090px` vs a visible `clientWidth: 294px` (≈3.7×) and defaults to the far-left (oldest possible date range, `03-10`…`04-02`), while today's (`09-05`) single data bar sits far off-screen to the right. Manually setting `scrollLeft = scrollWidth` reveals the bar. The only affordance hinting this is scrollable is a thin native scrollbar thumb near the axis labels — easy to miss.
- **Evidence:** `15-usage-models.png` (looks empty), `16-usage-chart-scrolled.png` (same chart, scrolled right, showing the orange bar at `09-05`).
- **Confidence:** High, clearly reproduced and explained.
- **Impact:** A new user with real usage would very plausibly conclude "Usage tracking is broken / shows nothing" the first time they open this tab.

#### F-03: Chat/Code input placeholder & hint text is hard-clipped mid-word/mid-glyph instead of wrapping or ellipsizing
> **RESOLVED 2026-09-05**, all three instances — and the CSS route this report implies does not
> work here. `text-overflow: ellipsis` on `::placeholder` has **no effect in Android's WebView**:
> the rule ships in the bundle and the class is on the element, yet a long placeholder still chops
> mid-glyph on-device (confirmed by forcing a long value and screenshotting). So the two placeholder
> cases are shortened in JS instead — the idle string is short enough to fit 360px outright, and a
> model name over 22 characters is cut with a real "…" — and the Code hint drops its keybind half on
> mobile, keeping the privacy claim whole (measured: 241px, no truncation).
- **Screens:** Workspace → Chat (both with no model loaded, and with a model loaded), Workspace → Code.
- **Steps:** Open Chat with no model loaded; read the message input placeholder. Load a model; read it again. Open the Code tab; read the hint line under the input.
- **Expected:** Placeholder/hint text that doesn't fit should wrap to a second line, ellipsize, or the container should be wide enough.
- **Actual:**
  - No model loaded: placeholder is actually `"Load a model above to start chatting"` (confirmed via `element.placeholder`) but renders visually as `"Load a model above to start cha"` — cut off mid-word, no ellipsis. CSS on the element: `overflow: auto`, `white-space: pre-wrap`, `text-overflow: clip`, and the wrapped second line is clipped away vertically by a fixed single-line height.
  - Model loaded: placeholder is `"Message qwen2.5-0.5b-instruct…"` (correctly has a trailing `…` character in the source string) but renders as `"Message qwen2.5-0.5b-instruct."` — the ellipsis glyph itself gets clipped down to what reads as a single stray period.
  - Code tab: the hint line's real text is `"Enter to start · Shift+Enter for newline · 100% local — your code never leaves this device"` but renders clipped to `"Enter to start · Shift+Enter for newline · 100% local — your code never leaves t"` — cutting off exactly the privacy/trust statement ("...never leaves this device"), which is an odd message to have cut off.
- **Evidence:** `02-workspace-chat.png`, `33-chat-loaded.png`, `34-after-send-attempt.png`, `05-code-tab.png`; DOM checks via `qa.mjs eval` reading `.placeholder` and computed style directly.
- **Confidence:** High — same underlying pattern reproduced in 3 separate places, with actual vs. rendered text captured directly from the DOM.

#### F-04: Deleting a Developer API key has no confirmation, unlike deleting a conversation
> **RESOLVED 2026-09-05.** Revoking now opens an AlertDialog naming the key and saying plainly that
> any app still using it stops working. Verified on-device end to end with a throwaway key: tapping
> the trash icon leaves the key in place and shows the confirmation; confirming removes it.
- **Screen:** Developer tab → API keys.
- **Steps:** Create a test key ("test-key-1"), then tap its trash-can icon.
- **Expected:** Given the app already has an explicit, default-on "Confirm before deleting a conversation" setting under Settings → General (screenshot `22-settings.png`), a comparable destructive action — permanently revoking an API key that may be wired into an external tool (Claude Code, opencode, Kilo Code) — would reasonably get the same treatment.
- **Actual:** The key is deleted instantly on tap, no confirmation dialog, no undo/toast.
- **Evidence:** `20-newkey-created.png` (key present) → `21-after-delete.png` (gone instantly, same session, single tap).
- **Confidence:** High, directly reproduced.
- **Note:** this is a design-consistency observation, not a crash/data-loss bug — flagging because the app's own General settings screen sets the user's expectation that destructive actions get confirmed.

---

### Found outside this report (user-reported 2026-09-05, both fixed)

#### The keyboard covered the text field
The activity declared no `windowSoftInputMode`, so the window never resized and the soft keyboard
simply sat on top of the bottom-pinned composer. Now `adjustResize`; HEIGHT_FIX_JS already
re-applies `window.innerHeight` on every resize, so the web layout follows. Verified on-device with
a model loaded: opening the keyboard takes the viewport 704 → 449 CSS px and the composer's bottom
lands at 348 — fully visible, with the bottom nav pinned exactly at the new 449 edge.

#### The attach button did nothing
Chat's attach control is a plain `<input type="file">`, and a WebView **ignores those entirely**
unless the app supplies a `WebChromeClient.onShowFileChooser` — there was no WebChromeClient at all,
so the button was inert with no error anywhere. Now wired to an activity-result launcher.
**Partially verified:** the picker opens, and cancelling releases the callback (a second tap
re-opens it, so the input isn't left wedged). Actually *selecting* a file could not be driven from
adb — DocumentsUI's list rows ignore synthetic taps, the same real-touch-only behaviour F-06 notes
for Radix popovers — so the picked-URI-reaches-the-page step is unverified here and wants one real
tap to confirm.

---

### P3 — polish

#### F-05: Settings sub-tab label "Tools & safety" is clipped to "Tools &" at the screen edge with no ellipsis or scroll cue
> **RESOLVED 2026-09-05.** The strip wraps below md instead of scrolling, so all three pills are
> simply visible — no hidden-scroll affordance to discover. Verified on-device: "Tools & safety"
> now sits on a second row, fully within the 360px viewport.
- **Screen:** Settings (any sub-tab).
- **Steps:** Open Settings; look at the three sub-tab pills (General / Models & loading / Tools & safety).
- **Actual:** The third tab's full label is `"Tools & safety"` (confirmed via `button.innerText`) but only `"Tools &"` is visible before the pill is cut off at the right edge of the screen. The tab strip is horizontally scrollable (confirmed — clicking the tab by exact text still works), but there's no fade/peek/arrow indicating more tabs exist off-screen; a user has to guess to scroll.
- **Evidence:** `22-settings.png`, `24-settings-tools.png`.
- **Confidence:** High.

#### F-06: "Choose a repo" / model "…" actions menus don't respond to non-touch activation (dev note, not itself a bug)
- Not filing as a defect — noted only so a re-tester doesn't waste time: these Radix-style popovers require a real touch/pointer event sequence; programmatic `.click()` silently no-ops. Confirmed both open correctly with an actual `adb input tap` (screenshots `06c-real-tap.png`, and the model "…" menu was reachable the same way). No app-facing issue found here.

#### F-07 (unconfirmed, low confidence): Back-navigation out of the model-config sheet lands on an unrelated tab
> **RESOLVED 2026-09-05** — and it was real, not a tab-history artifact: the same F-01 listener bug.
> The overlay's history entry was popped while the sheet stayed open, so the app was one entry
> further back than the screen suggested. Re-tested the exact reported path (Settings → Models →
> Load settings → Back): the sheet closes and the app stays on Models.
- **Screen:** Models → model "sliders" (Load settings) full-screen sheet.
- **Steps:** From Settings tab, navigated to Models, opened the model config sheet, pressed hardware Back once.
- **Actual:** Back correctly closed the sheet and kept the app open (unlike F-01), but landed on the **Settings** tab rather than the Models tab that was visible immediately before the sheet opened.
- **Confidence:** Low — only observed once, not deliberately re-tested in isolation, and could simply be an artifact of how bottom-tab navigation manages history (tab switches may not push history entries, so Back can legitimately skip past a tab that never pushed a history state). Flagging as a "look into it" rather than a confirmed defect.

---

## Explicitly not bugs (verified, ruled out)

- **Suspected lost chat message:** after sending a message to the loaded model, switching to the Engines tab and back to Workspace showed a blank "new chat" welcome screen, which initially looked like the in-flight message/response had been lost. Verified via the conversation drawer that this was **not** data loss — the conversation (auto-titled "Hello") was intact with both the user message and the completed `"Hello!"` response (101.6s total on CPU). Tapping the **Workspace** bottom-nav tab simply opens a fresh compose view rather than resuming whatever conversation was last active elsewhere; the data itself is safe. (Screenshots `36`, `37`, `40`, `41`.)
- **Rapid 5× tap on "Load"** for a model: handled cleanly — no duplicate load requests, no crash; button state changed to "Eject" after the first successful load and the rest were no-ops.
- **Empty-input validation** ("New key" with blank name, Chat/Code submit): the "New key" button is properly `disabled` when the name field is empty (verified via DOM), no dead click.
- Numerous `audit` "offscreen"/"text-clip-h" findings on Engines, Discover, Customize, and Settings turned out to be either (a) content simply below the fold on a normal scrollable page, or (b) intentional `truncate`/`line-clamp-2` ellipsis patterns working as designed, or (c) 2–3px sub-pixel rounding noise from the audit tool. These were visually cross-checked against screenshots and are **not** reported as bugs.
- No console errors or uncaught exceptions were observed via `qa.mjs events` after model load, chat send, dialog open/close, or tab switches (note: each `events` call only captures activity during that specific CDP connection's lifetime, so this is a spot-check, not continuous monitoring across the whole session).

---

## What I could not test, and why

- **Cannot fully rule out other dialogs sharing F-01's missing-back-handler bug.** Time did not allow testing every dialog/sheet in the app (e.g. "Save as…" preset dialog, folder-add dialogs in Models, MCP server config dialogs, Skills detail) against the hardware Back button specifically. Given F-01 was found on the very first modal tested this way, **I recommend an explicit sweep of every dialog/modal/sheet against hardware Back before release**, not just this one.
- **Real model downloads / Discover → install flow** were not exercised (would require downloading a multi-GB file from Hugging Face on a real device — out of scope and would burn significant time/data for a one-off UI check).
- **"Choose folder…" / "Add via git repo" engine-add flows** were not carried through to completion (opening the native Android folder picker and actually adding a second engine) — out of scope per the instructions not to modify app state/config in ways that would need cleanup, and not needed to confirm F-01.
- **GPU-specific engine paths** — device is CPU-only, so "Manage GPU builds" and GPU-specific engine cards could only be reviewed as static cards, not exercised.
- Did not test with a second, larger, or long-context model (context-length edge cases, very long generations, thinking-budget behavior beyond reading the UI) — the single small CPU model was enough to prove the chat pipeline works end-to-end, but perf/edge-case behavior with bigger models is unverified.
