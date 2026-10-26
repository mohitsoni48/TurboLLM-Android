# Play Console: the foreground service declaration

The app declares one foreground service, `EngineService`, of type `specialUse`
(`FOREGROUND_SERVICE_SPECIAL_USE`). Play requires a declaration for it under
**App content → Foreground service permissions**. Play asks for a short description and a video
link. This page has both, ready to paste or record.

## Why `specialUse`

`EngineService` keeps the on-device inference server running while the user is in another app.
Without it, Android treats a backgrounded TurboLLM as a cached app:
- Android 12+ freezes the process, so a reply stops the moment the user switches apps.
- The low-memory killer reclaims it early, unloading the model.
- The engine child processes fall under the phantom-process limits.

No standard type describes this work:
- `dataSync` is for transferring data, and `mediaProcessing` for transcoding media. Both are
  capped at 6 hours on Android 15+.
- `shortService` is limited to about 3 minutes.
- A WorkManager long-running worker still runs as a typed foreground service.

The same service also keeps a model import or an in-app model download going until it is done.
Taken alone, those transfers are `dataSync` work: Android's list for that type includes upload or
download and import or export. They are not why the service runs, though. It starts when the app
is opened, not for them, so it is already up for the inference server by the time the user starts
an import or a download from the app's own screens, and they only bring in the models that server
loads. Declaring the service `dataSync` instead would limit the inference server to 6 hours in 24
while the app is in the background (this app targets Android 15+). The declaration below says so
to Play. If Play asks for the transfers to be declared on their own: it allows more than one type
per service, but adding `dataSync` is a code change (`EngineService`'s `startForeground` type and
the manifest) that this page does not make.

The manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` already states the use. The text below expands
it for the form.

## Declaration text

The answers describe what the code does (`EngineService.kt`). The service starts when the app is opened and stays
in the foreground, also while idle, until the user quits or closes the app with nothing running, so the text says so
rather than suggesting it runs only during generation.

LAN access, remote access and Turbo Link are read from the daemon the app bundles: the `serving` field of its GET
/api/v1/status, from TurboLLM's feat(api): /api/v1/status says which server features are on, or the same field in the
`status` its GET /api/v1/activity carries too, from TurboLLM's fix(api): one activity request carries what the
Android poll reads. A daemon without that field reports them all off (the app reads a missing or malformed `serving`
as nothing served), so a close with only one of them on would quit: the text below holds only for an APK built with a
TurboLLM that has it.

A close made while the app is still starting, before its daemon answers (a swipe from Recents during the splash
screen, and before Android 12 Back there too), waits for the daemon, for up to 30 seconds, and is then decided as the
text says. So the text's daemon that gives "no answer" is one that has not answered by then, or one that stops
answering once it is up. Such a close with nothing running ends the app about 3 seconds after the close, or, when the
daemon takes longer to answer, right after it does.

**Which feature uses the foreground service?**

> On-device AI chat. TurboLLM runs large language models locally on the phone, with no server and no account. The
> app starts a local inference server and loads the user's chosen model into memory (often several GB).

**Why does it need to run in the foreground, and what would happen without it?**

> The service keeps that local server and the loaded model available while the user is in another app. Without it,
> Android freezes the app as soon as it leaves the screen: a reply being written stops mid-sentence and is lost, and
> the model is reclaimed, so the next message waits for it to load again. Replies on a phone take from seconds to
> minutes, and users switch apps while they wait. The service also keeps a model import (a multi-GB file copy) or an
> in-app model download from being frozen halfway.

**What does the user see, and how do they stop it?**

> From the moment the app is opened, a low-importance ongoing notification titled "TurboLLM", with a **Quit** action
> that stops the service, unloads the model and closes the app, about 3 seconds after the tap. On Android 14 and
> later, where the user can swipe this notification away, it is posted again at once, so Quit stays reachable. Its
> text says what is happening: "Idle" while no work is in progress, or "Idle · " and the model's name while a model is
> loaded (the chat model's name, or a text classification model's while no chat model is loaded; an embedding model
> loaded alone is not named), "Generating" while a reply is being written, "Loading a model", "Auto-tuning" or
> "Writing a skill" while the app does one of those, "Waiting for your approval" while a reply waits for the user to
> allow a tool call, "Importing models" during an import, the progress while a model download is running or queued
> (the file's name as the title, or "Downloading models" for several, how much has arrived and how fast, and a
> progress bar), and, when the phone is thermally throttled, "Idle. The phone is hot, so generation will be slower" or
> what is running followed by "The phone is hot, so it is throttling speed" (for example "Generating. The phone is
> hot, so it is throttling speed"). Leaving the app never unloads the model by itself: the user does, in the app or
> with Quit, or the app's idle timer does once the model has gone unused for a while (60 minutes by default; the user
> can change it or turn it off in Settings). Closing the app (Back from its main screen, or swiping it away from
> Recents; pressing Home does not close it) with nothing running works as Quit does, whether notifications are on or
> off: about 3 seconds later the service stops and the app's process ends. Opening the app again within those 3
> seconds, after Quit or such a close, keeps it running, as does the app coming back on screen after Quit (for example
> the phone unlocked onto it). Nothing is running when no model is loaded or loading (any model, an embedding model
> included), nothing is being generated, imported or downloaded (a paused or failed download does not count), no reply
> waits for the user's approval, no routine is running, and no access from other devices is on: LAN access, remote
> access (a tunnel, Tailscale Serve or Funnel), or Turbo Link letting other devices use this phone's models; when the
> app gets no answer from the daemon, or one it cannot read, it counts as running. Closed with something running, the
> app keeps the service, also after that work has ended, after the idle timer has unloaded the model and after that
> access has been turned off, until the user taps Quit or closes the app again with nothing running (after opening it,
> or by swiping away the card it left in Recents). A routine only scheduled for later does not count as running: such
> a close stops the routine scheduler with the app, until the app is opened again. If the user has turned
> notifications off (or the notification's channel), there is no Quit to tap: on Android 13 and later the system's
> list of active apps can still stop the app, and closing it again with nothing running quits. A partial wake lock is
> held only while a reply is being generated, a model being loaded, auto-tuned, imported or downloaded, or a skill
> written, never while idle or while a reply waits for the user's approval.

**Why does no other foreground service type fit?**

> The work is a local, user-started AI inference server, kept running for as long as the user keeps the app in use.
> It is not media playback or processing, location, a phone call, health or connected-device work, and it lasts
> longer than shortService allows. The model imports and downloads the service also keeps going are data transfers,
> but they are not why it runs: it starts when the app is opened, not for them, they run inside it while it is
> already up for the inference server, and they only bring in the models that server loads. dataSync fits those
> transfers alone, and like mediaProcessing it is capped at 6 hours in 24 on Android 15, while the inference server
> runs for as long as the app is in use.

## Video script (about 45 seconds, screen recording on a phone)

Record with the system screen recorder, with touches shown (Developer options → Show taps). Use
a small model, so the first load is quick and the reply visibly continues.

1. **0:00** Open TurboLLM. On the Models screen, load a small GGUF model, for example
   Qwen2.5-0.5B-Instruct (Q4_K_M). It runs on llama.cpp, the engine the app selects until you
   pick another. A `.litertlm` model such as LFM2.5-230M runs only on LiteRT-LM, which the app
   never selects by itself: under llama.cpp the Models screen hides it and a load started
   anyway fails. To record with one, select LiteRT-LM in Engines before you start recording
   (tap Use on its card under "Built into the app", or pick "LiteRT-LM" in the engine selector
   at the top of that screen).
2. **0:08** In Workspace, ask for something long: *"Write a 500-word story about a lighthouse
   keeper."* Let a line or two of the reply appear.
3. **0:15** Pull down the notification shade. Show the TurboLLM notification reading
   "Generating", with the Quit action.
4. **0:20** Press Home and open another app (for example Settings) for about 10 seconds.
5. **0:30** Return to TurboLLM. The reply has kept growing while the app was in the background,
   and is complete or nearly so.
6. **0:38** Pull down the shade again. The notification now reads "Idle · " followed by the
   model's name: the reply is done, and the model stays loaded, ready for the next message,
   which is what the service is for. Tap **Quit**. About 3 seconds later the notification
   disappears and the app closes, freeing the model.

Upload the video unlisted (for example on YouTube) and paste the link into the declaration.

## If Play rejects it

The fallback is a build without the service (option G in TurboLLM-Android#5). Replies then stop
when the user leaves the app. Ask the maintainers before shipping that.
