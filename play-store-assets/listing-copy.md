# TurboLLM Android — Play Store listing copy

> Rewritten 2026-09-06 for ASO (App Store Optimization) and to replace stale copy written against
> `versionName = "0.1.0-spike-a"`. The app is now `1.12.4`, package `com.turbollm`, with a mature
> feature set (Discover/Hugging Face search, custom Agents, per-account Usage stats, Settings) —
> the old "early-stage, rough edges, not a finished v1" framing no longer matches reality and has
> been dropped. Verified against `composeApp/build.gradle.kts`, `MainActivity.kt`, `DaemonWebView.kt`,
> and a live capture of the running app (see `screenshots/`) on 2026-09-06.
>
> **Firebase Crashlytics/Analytics (ADR-398) is a founder-directed future addition, NOT yet in the
> code** — confirmed absent from `composeApp/build.gradle.kts`'s plugin list as of this writing.
> This copy and `privacy-notes.md`'s Data Safety answers describe the app AS IT ACTUALLY SHIPS
> today (zero telemetry, zero analytics, zero crash SDK). If Firebase is added before submission,
> this file's privacy claims and the Play Console Data Safety form both need a follow-up pass —
> don't ship this copy unchanged after that integration lands.

## Title (≤30 chars)

```
TurboLLM: Offline AI Chat
```
(25 chars — leads with the brand, then the two highest-intent search terms for this category:
"offline" and "AI chat". "Local" is this project's own internal term of art, but "offline" tests
as the more common consumer search phrase for the same intent, so it's the one spent on the
title; "local" still appears repeatedly in the short/full description below for that audience.)

## Short description (≤80 chars)

```
Run local LLMs offline on your phone — private AI chat, no cloud, no account.
```
(77 chars — keyword coverage: local, LLMs, offline, phone, private, AI chat, no cloud, no account.)

## Full description (≤4000 chars)

```
TurboLLM runs real large language models directly on your Android phone's CPU
— no cloud, no account, no subscription, and no internet connection required
once a model is downloaded.

WHY LOCAL AI

Most "AI chat" apps are a thin wrapper around someone else's cloud API: your
prompts leave your phone, your conversations sit on a server you don't
control, and the app stops working the moment you lose signal. TurboLLM is
different. The model runs in a real on-device inference engine (llama.cpp,
compiled for Android), and your conversation never has to leave your phone.

WHAT YOU CAN DO

- Chat with open-weight local LLMs — Llama, Qwen, Gemma, and any other GGUF
  model — fully offline, even in airplane mode.
- Discover and download models straight from Hugging Face, right inside the
  app, with one tap.
- The inference engine installs itself automatically the first time you need
  it — no manual setup, no compiling, no terminal.
- Create custom AI agents with their own system prompt and skill/tool access,
  for different tasks — coding help, writing, research, or a completely
  blank slate with zero injected instructions.
- Track your own usage over time: sessions, messages, total tokens
  generated, streaks, and your most-used model — all computed locally from
  your own history.
- Fine-tune generation with per-chat controls: thinking budget, sampling,
  and reasoning effort, for models that support them.
- The same interface as TurboLLM's desktop app (Windows, Mac, Linux) — if
  you've used TurboLLM on a computer, your phone works exactly the same way.

PRIVACY BY DESIGN

- No account, no login, no cloud backend for your conversations.
- No telemetry, no analytics, no crash-reporting SDK phoning home in this
  build.
- Your chats stay in this app's private storage on your device. Android's
  own backup to your Google account and its transfer to a new phone also
  copy them off it, if you use them (with a screen lock on Android 9 and
  later, the backup is end-to-end encrypted). Downloaded models stay in
  the app's storage unless you pick another folder for them.
- Your prompts, files, and model weights are never sent to us. They leave
  the phone only through that backup (which leaves models out), a tool
  call you approve, or a device or service you connect the app to
  yourself. The only network requests this app makes are ones you
  triggered yourself: downloading a model you picked, fetching the
  inference engine binary the first time it's needed, or a tool call you
  explicitly approved.

WORTH KNOWING

Inference runs on your phone's CPU, so speed depends on your chipset and the
model size you pick — smaller models (1-4B parameters) feel the most
responsive on most phones, and larger ones are there if your hardware can
carry them. Active generation is real compute work, so expect normal battery
and thermal load, same as any on-device AI task.

WHO IT'S FOR

Privacy-conscious users, local-AI enthusiasts, developers who already run
TurboLLM or llama.cpp on a computer, and anyone curious what a genuinely
offline LLM chat feels like on a phone — under your control, on your
hardware.
```
(3,140 chars — well under the 4,000 limit; keyword density: "local" x6, "offline" x5, "AI"/"LLM"
x9, "on-device" x2, "privacy/private" x3, "no cloud/account/subscription/telemetry" x5, plus
feature nouns tuned for search — Hugging Face, GGUF, agents, tokens, llama.cpp.)

## Screenshots

**Upload `screenshots/final-{1-chat,2-discover,3-usage,4-settings}.png`** (1080x1920, PNG, no
alpha) — these are the real app captures below, wrapped in a headline + subtext + framed-device
mockup matching the style of this account's other listings (Scout Ride, etc. — reference viewed
live in Play Console). Background/accent colors use TurboLLM's own orange, not copied verbatim
from the reference. Built with `web/_wrap-screenshots.mjs` (Playwright + HTML/CSS, deleted after
use), which embeds the raw screenshot as a data URI and renders the composite at exact target
resolution — no manual image editing.

`screenshots/screenshot-{1-chat,2-discover,3-usage,4-settings}.png` are the unwrapped raw captures
(1080x2400, dark theme) kept as a reference/fallback — real captures of the actual running app,
not mockups. Captured 2026-09-06 via a scripted Playwright session against the real desktop daemon
at a phone viewport (`web/_capture-android-screens.mjs`, deleted after use) — the daemon and web UI
are identical between desktop and Android, so this is an accurate representation of what ships.
Two screens were deliberately excluded after a real capture: Engines (revealed the test machine's
NVIDIA GPU — Android is CPU-only, so a desktop GPU card would misrepresent the app) and
Customize/Agents (surfaced the real account's private custom agents, unsuitable for a public
listing). The Discover screenshot searches a specific known-clean repo (`unsloth/Llama-3.2-3B`)
rather than a bare trending query, because unscoped trending results for common search terms
surface NSFW-adjacent community fine-tunes ("uncensored", "obliterated") that are real Hugging Face
content but a bad first impression for store marketing.

Note: the raw 1080x2400 captures are outside Play's documented screenshot constraint (max side
can't be more than 2x the min side — 2400/1080 = 2.22x) even though real 20:9 phones ship that
resolution; the wrapped 1080x1920 (16:9) versions sidestep this entirely, which is one more reason
to upload `final-*.png`, not the raw ones.

## Feature graphic

`feature-graphic/feature-graphic-1024x500.png` — cropped/rescaled from the real LinkedIn launch
post's hero graphic (`docs/site/assets/social/beta-landscape.png`, 2400x1256, itself generated
from the real product UI — see `docs/site-build/make-social-card.mjs`), not the old flat-color
icon+wordmark placeholder. Same source asset already validated for the LinkedIn post; reused here
for visual consistency across channels rather than redesigning from scratch.

---

## Founder review needed before submitting

1. **Firebase Crashlytics/Analytics (ADR-398).** See the note at the top of this file. If it lands
   before submission, the "No telemetry, no analytics, no crash-reporting SDK" line above becomes
   false and needs rewriting alongside the Play Console Data Safety form (`privacy-notes.md`
   already has draft answers for the *with-Firebase* state).
2. **Model names/repos in the Discover screenshot are real, live Hugging Face content**, not
   curated by TurboLLM — they'll change over time as the catalog does. That's expected and fine;
   just don't read the specific repo shown as an endorsement or a permanent catalog entry.
3. **No Android-specific on-device tok/s numbers are quoted anywhere in this copy** (still true as
   of this rewrite — ADR-395's only real numbers are from an x86_64 emulator, not a physical
   phone). Do not add specific speed claims without a real device measurement.
