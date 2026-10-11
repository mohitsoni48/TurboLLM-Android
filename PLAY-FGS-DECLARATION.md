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

**Which feature uses the foreground service?**

> On-device AI chat. TurboLLM runs large language models locally on the phone, with no server
> and no account. While the app is open, a model is loaded into memory and served by a local
> inference process that the app starts.

**Why does it need to run in the foreground, and what would happen without it?**

> Generating a reply takes from a few seconds to several minutes on a phone. The user often
> switches to another app while waiting. Without a foreground service, Android freezes the app as
> soon as it leaves the screen. The reply stops mid-sentence and is lost, and the loaded model
> (often several GB) is reclaimed, so the next message has to reload it. The service keeps the
> local inference server running only so that work the user started can finish.

**What does the user see, and how do they stop it?**

> A low-importance ongoing notification, "TurboLLM — Running in the background" ("Generating"
> while a reply is being written), with a Quit action that stops the service and frees the
> model's memory. If notifications are turned off, leaving the app (Back, or swiping it away from
> Recents) unloads the model and stops the service as soon as nothing is generating. A partial
> wake lock is held only while a reply is being generated, never while idle.

**Why does no other foreground service type fit?**

> The work is local, user-initiated AI inference. It is not data sync, media playback or
> processing, location, a phone call, health or connected-device work, and it can last longer
> than shortService allows. The other candidate types are capped at 6 hours on Android 15 and
> describe different work.

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
6. **0:38** Pull down the shade again. The notification now reads "Running in the background".
   Tap **Quit**. The notification disappears and the app closes, freeing the model.

Upload the video unlisted (for example on YouTube) and paste the link into the declaration.

## If Play rejects it

The fallback is a build without the service (option G in TurboLLM-Android#5). Replies then stop
when the user leaves the app. Ask the maintainers before shipping that.
