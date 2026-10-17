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

The manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` already states the use. The text below expands
it for the form.

## Declaration text

The answers describe what the code does (`EngineService.kt`). The service starts when the app is opened and stays
in the foreground, also while idle, until the user quits, so the text says so rather than suggesting it runs only
during generation.

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
> that stops the service, unloads the model and closes the app. On Android 14 and later, where the user can swipe
> this notification away, it is posted again at once, so Quit stays reachable. Its text says what is happening:
> "Running in the background" while idle, "Generating" while a reply is being written, "Importing models" during an
> import, "Downloading models" while a model download is running or queued, and, when the phone is thermally
> throttled, "Running. The phone is hot, so generation will be slower" or "Generating. The phone is hot, so it is
> throttling speed". If the user has turned notifications off, so Quit cannot be reached, leaving the app (Back, or
> swiping it away from Recents) unloads the model and stops the service as soon as nothing is generating, importing or
> downloading; a paused or failed download does not keep it running. Turning the notification off after leaving the
> app does the same. A partial wake lock is held only while a reply is being generated or a model imported or
> downloaded, never while idle.

**Why does no other foreground service type fit?**

> The work is a local, user-started AI inference server. It is not data sync, media playback or processing,
> location, a phone call, health or connected-device work, and it lasts as long as the user keeps the app in use,
> longer than shortService allows. The other candidate types are capped at 6 hours on Android 15 and describe
> different work.

## Video script (about 45 seconds, screen recording on a phone)

Record with the system screen recorder, with touches shown (Developer options → Show taps). Use
a small model, so the first load is quick and the reply visibly continues.

1. **0:00** Open TurboLLM. On the Models screen, load a small model, for example a
   `.litertlm` such as LFM2.5-230M.
2. **0:08** In Workspace, ask for something long: *"Write a 500-word story about a lighthouse
   keeper."* Let a line or two of the reply appear.
3. **0:15** Pull down the notification shade. Show the TurboLLM notification reading
   "Generating", with the Quit action.
4. **0:20** Press Home and open another app (for example Settings) for about 10 seconds.
5. **0:30** Return to TurboLLM. The reply has kept growing while the app was in the background,
   and is complete or nearly so.
6. **0:38** Pull down the shade again. The notification now reads "Running in the background":
   the model stays loaded, ready for the next message, which is what the service is for.
   Tap **Quit**. The notification disappears and the app closes, freeing the model.

Upload the video unlisted (for example on YouTube) and paste the link into the declaration.

## If Play rejects it

The fallback is a build without the service (option G in TurboLLM-Android#5). Replies then stop
when the user leaves the app. Ask the maintainers before shipping that.
