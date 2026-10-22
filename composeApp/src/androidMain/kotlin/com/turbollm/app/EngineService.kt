package com.turbollm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

private const val TAG = "TurboLLM"
private const val CHANNEL_ID = "engine"
private const val NOTIFICATION_ID = 1
private const val ACTION_QUIT = "com.turbollm.app.QUIT"
private const val ACTION_ACTIVITY_FINISHED = "com.turbollm.app.ACTIVITY_FINISHED"
private const val ACTION_REPOST = "com.turbollm.app.REPOST"
private const val EXTRA_ACTIVITY = "com.turbollm.app.ACTIVITY"
private const val POLL_MS = 3000L
/** How many polls in a row may fail to read the download list before the notification drops its last progress. */
private const val DOWNLOAD_MISSES = 3
/** The longest the daemon is given to write its database before the process ends (flushDatabase). */
private const val FLUSH_WAIT_MS = 2000L
/** How long a close made while the daemon gives no proof (still starting, mostly) waits for it before it is decided
 *  (appClosed), give or take one proof: one that hangs to its 3 s deadline as this runs out stretches the wait to
 *  about 33.5 s. A daemon that has not proved itself by then counts as something running. */
private const val BOOT_WAIT_MS = 30_000L
/** How often that close asks the daemon for its proof again, as the splash does (DaemonWebView). */
private const val BOOT_POLL_MS = 500L
/** The shortest time from a close (appClosed) or a Quit (quit) to the process kill. The WebView writes the page's
 *  localStorage (the theme, the font size, the chat to open again, the onboarding step) to disk only 1 s after a
 *  change: WebView turns on Chromium's aggressive DOM storage flushing, whose commit delay is 1 s, and a change made
 *  while a commit is under way waits for that one first. Nothing writes it when the process is killed. */
private const val CLOSE_HOLD_MS = 3000L
/** The primary engine states in which it holds a model (the daemon's manager.ts State): see statusRuns. */
private val LOADED_STATES = setOf("starting", "running", "stopping")

/** Keeps the daemon and its engine processes running while the app is not on screen.
 *
 *  Without it, Android treats a backgrounded TurboLLM like any other cached app. It freezes the
 *  process (the cached-apps freezer, Android 12+), so a reply stops mid-sentence the moment the
 *  user switches apps. The low-memory killer also picks it early, which loses the loaded model,
 *  and the phantom-process limits apply to the engine children. Termux avoids all of this the
 *  same way, with an always-on foreground service.
 *
 *  Type `specialUse` (Android 14+): a local inference server fits none of the fixed types, and
 *  `dataSync` would be wrong and is capped at 6 hours on Android 15.
 *
 *  It also holds a partial wake lock, but only while there is work: a reply being generated, or a
 *  model load, an auto-tune or a skill being written (the daemon's own /api/v1/activity, the gate
 *  the UI uses), a model download (/api/v1/downloads), both polled over loopback, or a model
 *  import (ModelImport). So a long reply or a multi-GB transfer finishes with the screen off
 *  without keeping the CPU awake while idle, or while a chat waits for the user to answer a tool
 *  approval. */
class EngineService : Service() {
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var busy = false
    /** The notification's line for what the daemon is running (DaemonActivity.work), null while nothing is. Written by
     *  the poll, read wherever the notification is built, like the fields below. */
    @Volatile private var work: String? = null
    /** A chat waits for the user to answer a tool approval (DaemonActivity.waiting): named in the notification, not
     *  work, so no wake lock is held for it. */
    @Volatile private var waiting = false
    @Volatile private var importing = false
    /** What the notification shows while a model download is running or queued (downloadNotice), null while none
     *  is. Written by the poll, read wherever the notification is built. */
    @Volatile private var download: DownloadNotice? = null
    /** Polls in a row that could not read the download list (see pollActivity). Poll thread only. */
    private var downloadMisses = 0
    @Volatile private var thermal = 0
    /** The newest plain start request (EngineService.start, from MainActivity.onCreate), so a close quits only if no
     *  start arrived after it (see appClosed). A close, a repost and Quit do not count: a repost (the notification
     *  swiped away) or a second close while a close is being decided must not cancel the quit. */
    @Volatile private var lastStartId = 0
    /** The close being decided (appClosed), as the session it was made in (session), or null while none is. A close
     *  made in the same one (Back, then the card it left in Recents swiped away) shares its verdict instead of
     *  reading and flushing again. Main thread only. */
    private var deciding: Triple<Int, Int, Int>? = null
    /** The Quit under way (quit), as the session it was tapped in, or null while none is. It ends the app unless the
     *  app is opened again during its hold, so a close made in that session decides nothing, and one being decided
     *  there is called off. Set on the main thread, read by the close's thread too. */
    @Volatile private var quitting: Triple<Int, Int, Int>? = null
    /** The number of the newest Quit (quit), so a Quit tapped again during the hold of the last one takes over from
     *  it. Set on the main thread. */
    @Volatile private var quits = 0
    /** Guards `running` against the poll thread: with it held, a check of `running` and the wake-lock or
     *  notification call after it are one step, so onDestroy cannot slip between them. */
    private val stateLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    /** The app's session, as the service sees it: the newest activity (newestActivity), the newest plain start
     *  (lastStartId) and how many times the app came on screen (appShown). A close or a Quit keeps the one it was
     *  made in: any change since means the app was opened again. */
    private fun session() = Triple(newestActivity, lastStartId, appShown)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Engine", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while TurboLLM's local engine is running"
                    setShowBadge(false)
                },
            )
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboLLM:generation").apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= 29) {
            thermal = power.currentThermalStatus
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                thermal = status
                updateNotification()
            }.also { power.addThermalStatusListener(ContextCompat.getMainExecutor(this), it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            quit()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ACTIVITY_FINISHED) {
            if (!running) {
                stopSelf(startId) // this intent created the service; it was not running in the foreground
                return START_NOT_STICKY
            }
            // Only the newest activity's close counts. Android destroys a finished activity once the next one on
            // screen is idle, so after Back and a quick reopen the old activity's close can come after the new one's
            // start, and acting on it unloaded the model under the open app (and with notifications off stopped the
            // service too); now it could quit the open app.
            if (intent.getIntExtra(EXTRA_ACTIVITY, 0) != newestActivity) return START_NOT_STICKY
            appClosed()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPOST) {
            // Android 14+ lets the user swipe the engine notification away (setOngoing no longer prevents it), and its
            // Quit with it, while a service the app was closed under keeps running until Quit (appClosed). Put it
            // straight back. MainActivity sends this too whenever it comes on screen (repostNotification).
            if (running) updateNotification() else stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStartId = startId
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!running) {
            running = true
            Thread(::pollActivity, "turbollm-activity").apply { isDaemon = true }.start()
        }
        // The daemon lives in this process: if Android kills it, there is nothing for a restarted service to keep alive.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            running = false
            releaseWakeLock()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        super.onDestroy()
    }

    /** "Quit" in the notification: how the user frees a loaded model's memory without going back into the app, since
     *  the service otherwise outlives the activity (with notifications off there is no Quit, see appClosed). Killing
     *  the process takes the engine children with it (Android kills the app's whole process group).
     *
     *  The daemon writes its chat database to disk first (flushDatabase). The kill used to come at once, and the
     *  daemon writes the database only on its own schedule, so a reply that had just finished, or a chat started just
     *  before Quit, was gone when the app was opened again. The flush runs off the main thread, where this is called,
     *  and takes at most FLUSH_WAIT_MS; the service stays in the foreground until then, so Android cannot freeze the
     *  process under the write. The kill follows whatever the flush answered.
     *
     *  The flush and the kill wait until CLOSE_HOLD_MS after the tap, as a close's do (appClosed): the kill came right
     *  after the flush, so a setting the page had stored less than a second before Quit (the theme, say) was lost
     *  with it. The app opened again during the hold or the flush (session: a new activity, a newer start, or the app
     *  brought back on screen after Home, which the kill would otherwise end under the user) calls the Quit off, and
     *  the service goes on as before the tap. A Quit tapped again meanwhile takes over, with a hold of its own. A close
     *  made before the app is opened again decides nothing: the Quit ends the app (appClosed). */
    private fun quit() {
        Log.i(TAG, "quit from the notification")
        val session = session()
        quitting = session
        val quit = ++quits
        val tappedAt = SystemClock.uptimeMillis()
        Thread({
            // Not called off: no activity created, no newer start and the app not back on screen since, no newer Quit.
            fun current() = quits == quit && session() == session
            val hold = CLOSE_HOLD_MS - (SystemClock.uptimeMillis() - tappedAt)
            if (hold > 0) Thread.sleep(hold)
            if (current()) flushDatabase()
            ContextCompat.getMainExecutor(this).execute {
                // On the main thread, where MainActivity.onCreate takes its number: the app is either open again by
                // now, or it is not opened before the kill.
                if (quits == quit) quitting = null
                if (current()) {
                    synchronized(stateLock) {
                        running = false
                        releaseWakeLock()
                    }
                    exit()
                }
            }
        }, "turbollm-quit").start()
    }

    /** How Quit ends, and a close with nothing running (appClosed): out of the foreground, stopped, and the process
     *  killed, the daemon and its engine children with it. Main thread, after flushDatabase. */
    private fun exit() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    /** Swiped away from Recents: the same as backing out of the app. Also the card that Back left there, with no
     *  activity left in it. A swipe sends only this: the activity it finishes sends no close of its own
     *  (MainActivity.onDestroy), so one swipe is one close. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running) appClosed()
        super.onTaskRemoved(rootIntent)
    }

    /** The user closed the app: Back from its root screen, or a swipe from Recents (Home is not a close). With nothing
     *  running, that is a Quit, whatever the notification settings: the database is written (flushDatabase) and the
     *  process ends (exit), so nothing of the app is left in memory. With anything running (anythingRuns: a model
     *  loaded or loading, work in progress, a reply waiting for a tool approval, a download or an import, or a server
     *  feature on: LAN access, remote access, Turbo Link peers hosted), the service stays in the foreground and keeps
     *  the daemon up, also after that work ends or that feature is turned off: only Quit, or a later close with
     *  nothing running (opening the app again and closing it, or swiping away the card a Back left in Recents), ends
     *  it. Leaving never unloads the model: only the user does (in the app, or with Quit), or the daemon's own idle
     *  timer (Settings, daemon.idleTtlMinutes), which leaves the service up. A routine counts while it runs (an
     *  activity item); one only scheduled for later does not, and the routine scheduler ends with the app.
     *
     *  It used to quit nothing: with the notification visible the service stayed until Quit, even with no model
     *  loaded, and with notifications off it stopped once nothing was loaded or running, leaving the process to
     *  Android, which kept it as a cached app. With notifications off there is no Quit: on Android 13+ the system's
     *  active-apps list can still stop the service, and a later close with nothing running quits.
     *
     *  The poll's view (`busy`) is up to 3 s old: a reply sent or a model load started just before the close would not
     *  be in it yet. So the daemon is asked now, and again after the flush, right before the kill. A query that fails
     *  counts as something running: the daemon is never quit on a guess. Nor does the kill go ahead once an activity
     *  was created since the close (the app opened again: MainActivity.onCreate takes its number at once, while the
     *  start it sends waits on the main thread behind that onCreate) or a newer start arrived. The kill comes no
     *  sooner than CLOSE_HOLD_MS after the close, so the WebView has written what the page last stored. A Quit tapped
     *  meanwhile calls the decision off and ends the app itself (quit).
     *
     *  A close made while the daemon gives no proof (DaemonAuth.daemonProven, which the splash waits on too) first
     *  waits for it: it asks again every BOOT_POLL_MS, for about BOOT_WAIT_MS (a proof that hangs to its 3 s deadline
     *  as that runs out stretches it to about 33.5 s) and only while the close is still the app's last, and is then
     *  decided on what the daemon says. Mostly that is a close during start-up, before the daemon listens (a swipe
     *  from Recents during the splash, and before Android 12 Back there too), but it is also one made in the gap
     *  while a LAN toggle rebinds the daemon's port, or while the daemon is up but does not answer in time. Read at
     *  once, the daemon not up yet failed the query, which counted as something running, so a close during the
     *  splash kept the app until Quit with nothing ever run. A daemon that has not proved itself by the end of the
     *  wait, or that proves itself and then answers badly, still counts as something running. The hold still counts
     *  from the close. With the model's auto-load at start on (Settings), that load begins only after the daemon's
     *  boot scan, so a close read right after the daemon first answers can quit before it begins: nothing ran at the
     *  close.
     *
     *  A second close in the same session (session) while one is being decided (Back, then the card it left swiped
     *  away) shares that decision's verdict: decided afresh once the first had ended, it would read the daemon
     *  seconds after that close, so a close made while a reply ran would quit the app if the reply had ended by then.
     *  A close after the app was opened again is decided on its own, at once, as a first close is: the earlier
     *  decision is called off by that reopening, and its reading may predate it. Queued behind that decision, a close
     *  made before the reopening would be decided once the decision ended, in the open app's session, and kill the
     *  app on screen. */
    private fun appClosed() {
        // Post it again first: a post made while notifications were off is dropped for good (on Android 13+
        // startForeground posts before the user can answer the permission prompt), and a later grant, at that prompt
        // or in Settings with the process still alive, re-posts nothing. Without this, Back after such a grant could
        // leave the service running with no notification and no Quit.
        updateNotification()
        val session = session()
        // A Quit tapped in this session ends the app itself; a close in it already being decided shares that one.
        if (quitting == session || deciding == session) return
        deciding = session
        // uptimeMillis, as the WebView's commit timer, does not count deep sleep.
        val closedAt = SystemClock.uptimeMillis()
        Thread({
            // Still the app's last close: no activity created and no newer start since (the app opened again), and no
            // Quit tapped in its session.
            fun current() = running && quitting != session && session() == session
            // No proof from the daemon (still starting, its port rebound for a LAN toggle, or not answering in time):
            // wait for it, so the close is decided on what it runs, not on its silence.
            val bootUntil = SystemClock.uptimeMillis() + BOOT_WAIT_MS
            while (current() && SystemClock.uptimeMillis() < bootUntil && !DaemonAuth.daemonProven()) {
                Thread.sleep(BOOT_POLL_MS)
            }
            // true: quit. false: something runs, so the service stays. null: called off, the app was opened again or
            // Quit tapped.
            val verdict: Boolean? = if (!current()) null else if (anythingRuns()) false else {
                val hold = CLOSE_HOLD_MS - (SystemClock.uptimeMillis() - closedAt)
                if (hold > 0) Thread.sleep(hold)
                if (!current()) {
                    null
                } else {
                    flushDatabase()
                    if (current()) !anythingRuns() else null
                }
            }
            ContextCompat.getMainExecutor(this).execute {
                if (deciding == session) deciding = null
                if (verdict == true && current()) {
                    Log.i(TAG, "closed with nothing running: quitting")
                    synchronized(stateLock) {
                        running = false
                        releaseWakeLock()
                    }
                    exit()
                }
            }
        }, "turbollm-close").start()
    }

    /** Whether anything runs, asked of the daemon now (see appClosed): a model import, work in progress or a reply
     *  waiting for a tool approval (GET /api/v1/activity's items, engineGenerating or waiting), a model download
     *  running or queued (a paused or failed one does not count), or a model loaded or loading or a server feature on
     *  (queryStatus). A query that fails, or a reply it does not recognise, counts as something running. */
    private fun anythingRuns(): Boolean {
        if (ModelImport.isRunning()) return true
        val activity = queryActivity() ?: return true
        if (activity.work != null || activity.waiting) return true
        if (queryDownloads()?.isEmpty() != true) return true
        return queryStatus() != false
    }

    /** Ask the daemon to write its chat database to disk now (POST /api/v1/daemon/flush), waiting at most
     *  FLUSH_WAIT_MS. On Android the database is kept in memory (sql.js) and written out on the daemon's own schedule,
     *  so what it had not written yet when the process went was lost. Best-effort: a daemon without the route (404),
     *  one that does not answer, or a failed proof only ends the wait. The wait has a deadline of its own because
     *  DaemonAuth.open's proof alone may take up to 3 s against a port that does not answer. */
    private fun flushDatabase() {
        val flush = Thread({
            runCatching {
                val conn = DaemonAuth.open("/api/v1/daemon/flush")
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 500
                    conn.readTimeout = 1500
                    conn.outputStream.use { it.write("{}".toByteArray()) }
                    val code = conn.responseCode
                    if (code != 200) Log.w(TAG, "the daemon did not write its database (HTTP $code)")
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "could not ask the daemon to write its database", it) }
        }, "turbollm-flush").apply { isDaemon = true }
        flush.start()
        runCatching { flush.join(FLUSH_WAIT_MS) }
    }

    private fun pollActivity() {
        var polls = 0
        while (running) {
            // A model import counts as work too: the service must neither stop nor let the CPU sleep under the copy.
            val importing = ModelImport.isRunning()
            // So does a model download (Discover): a stopped service or a sleeping CPU would stall a multi-GB transfer.
            val downloads = queryDownloads()
            val downloading = !downloads.isNullOrEmpty()
            val activity = queryActivity()
            val work = activity?.work
            val waiting = activity?.waiting == true
            val nowBusy = work != null || importing || downloading
            // The query can take seconds. Under the lock, so the service being destroyed meanwhile is seen before the
            // wake lock is re-taken or a removed notification is posted again.
            synchronized(stateLock) {
                if (!running) return
                // A download's progress changes from one poll to the next, so the notification is posted again
                // whenever its download lines would read differently, at the poll's pace (3 s) and quietly
                // (setOnlyAlertOnce, setSilent). A poll that could not read the list keeps the last lines up for up
                // to DOWNLOAD_MISSES polls in a row (the daemon slow to answer for a moment) instead of flashing
                // another state for 3 s. It still counts as idle above, as before, so a daemon that stops answering
                // never pins the wake lock.
                val download = when {
                    downloads != null -> downloadNotice(downloads).also { downloadMisses = 0 }
                    ++downloadMisses <= DOWNLOAD_MISSES -> this.download
                    else -> null
                }
                // Everything the notification shows is updated first, then it is posted once, and only if any of it
                // changed. Posted after each change, it showed the state in between: "Generating" for a moment at the
                // end of every download or import, with the download gone and the busy state not yet updated.
                val changed = importing != this.importing || download != this.download || work != this.work ||
                    waiting != this.waiting
                this.importing = importing
                this.download = download
                this.work = work
                this.waiting = waiting
                if (nowBusy != busy) {
                    busy = nowBusy
                    if (nowBusy) wakeLock?.acquire(30 * 60 * 1000L) else releaseWakeLock()
                } else if (nowBusy) {
                    wakeLock?.acquire(30 * 60 * 1000L) // renew the timeout for work that is still going
                }
                if (changed) updateNotification()
            }
            // Nothing here stops the service: once the app was closed with something running, it stays until Quit or
            // the next close with nothing running (appClosed), also once that work has ended, or the daemon's idle
            // timer has unloaded the model.
            if (++polls % 20 == 0) DaemonLog.rotateIfLarge(filesDir) // about once a minute
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** What the daemon is doing (GET /api/v1/activity, see DaemonActivity), or null when it could not be read: the poll
     *  reads that as idle, a close (anythingRuns) as something running. */
    private fun queryActivity(): DaemonActivity? = try {
        val conn = DaemonAuth.open("/api/v1/activity")
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                // Parsed, not string-matched: any shape this does not recognise reads as idle, so a format change
                // can never pin the wake lock on with the screen off. A reply with no `items` list is not the
                // daemon's: unreadable, so a close does not take it for nothing running.
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .takeIf { it.optJSONArray("items") != null }
                    ?.let(::daemonActivity)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Whether GET /api/v1/status shows something running (statusRuns), or null when the status could not be read,
     *  which a close (anythingRuns) counts as running: it never quits the daemon on a guess. */
    private fun queryStatus(): Boolean? = try {
        val conn = DaemonAuth.open("/api/v1/status")
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                statusRuns(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** The daemon's running and queued model downloads (parseLiveDownloads), or null when the list could not be read
     *  (the daemon still booting, a reply this does not recognise). */
    private fun queryDownloads(): List<LiveDownload>? = try {
        val conn = DaemonAuth.open("/api/v1/downloads")
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                parseLiveDownloads(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val repost = PendingIntent.getService(
            this, 2,
            Intent(this, EngineService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hot = thermal >= PowerManager.THERMAL_STATUS_SEVERE
        // A download names its file (or the count) and shows its progress, unless an import, named first, is running.
        val download = this.download?.takeUnless { importing }
        val work = this.work
        val text = when {
            importing -> "Importing models"
            download != null -> download.text
            hot && work != null -> "$work. The phone is hot, so it is throttling speed"
            work != null -> work
            // A reply parked on a tool approval waits for the user, in the app, not for the phone: say so. It holds
            // no wake lock (see DaemonActivity).
            waiting -> "Waiting for your approval"
            hot -> "Running. The phone is hot, so generation will be slower"
            else -> "Running in the background"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_engine)
            .setContentTitle(download?.title ?: "TurboLLM")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .addAction(0, "Quit", quit)
            .apply {
                if (download != null) setProgress(DOWNLOAD_PROGRESS_MAX, download.progress, download.indeterminate)
            }
            .build()
    }

    companion object {
        /** The number of the newest MainActivity instance (activityOpened), so a close from an older one is ignored
         *  (see ACTION_ACTIVITY_FINISHED). Set on the main thread, read by the close thread too. */
        @Volatile private var newestActivity = 0

        /** MainActivity.onCreate: this activity's number, to hand back to activityFinished. */
        fun activityOpened(): Int = ++newestActivity

        /** The activity numbered [activity] is finishing (Back): see ACTION_ACTIVITY_FINISHED. */
        fun activityFinished(context: Context, activity: Int) {
            runCatching {
                val intent = Intent(context, EngineService::class.java).setAction(ACTION_ACTIVITY_FINISHED)
                context.startService(intent.putExtra(EXTRA_ACTIVITY, activity))
            }.onFailure { Log.w(TAG, "could not reach the engine service", it) }
        }

        /** How many times the app came on screen (repostNotification), so a Quit is called off when the app is brought
         *  back during its hold, also without a new activity (left with Home, not closed): see quit. Set on the main
         *  thread, read by the Quit's thread too. */
        @Volatile private var appShown = 0

        /** The app came on screen: post the notification again (ACTION_REPOST). One dropped while notifications were
         *  off then shows as soon as the user allows them, at the first-launch prompt or in Settings, since Android
         *  re-posts nothing on a grant and returning to the app (singleTask, no recreation) does not run start(). */
        fun repostNotification(context: Context) {
            appShown++
            runCatching {
                context.startService(Intent(context, EngineService::class.java).setAction(ACTION_REPOST))
            }.onFailure { Log.w(TAG, "could not reach the engine service", it) }
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
            }.onFailure { Log.w(TAG, "could not start the engine service", it) }
        }
    }
}

/** What GET /api/v1/activity says the daemon is doing. [work] is the notification's line for what is in progress, null
 *  while nothing is; [waiting] is true while a chat waits for the user to answer a tool approval, which is not work
 *  (the daemon lists those chats under `waiting`, not `items`). */
private class DaemonActivity(val work: String?, val waiting: Boolean)

/** What GET /api/v1/status counts as running (see anythingRuns). A model is loaded while the primary engine is
 *  loading, running or still stopping (`engine.state` starting, running or stopping), or any model is alive in a pool
 *  slot beside it, such as an embedding model or a Jev or Laya model. `poolModels` counts those slots (running,
 *  starting or stopping); a daemon from before TurboLLM#287 leaves it out, which reads as 0, and names only a Jev or
 *  Laya model (`jev`, `laya`), so those are still read. An engine in `error` holds nothing. A server feature on counts
 *  too (daemonServes). Null for a reply with no `engine`, which is not the daemon's status: unreadable. */
private fun statusRuns(json: JSONObject): Boolean? {
    val engine = json.optJSONObject("engine") ?: return null
    return engine.optString("state") in LOADED_STATES || json.optInt("poolModels") > 0 ||
        json.optJSONObject("jev") != null || json.optJSONObject("laya") != null || daemonServes(json)
}

/** A server feature is on (`serving` in GET /api/v1/status, TurboLLM's serving-status.ts): the daemon listens beyond
 *  loopback (`lan`, LAN access), remote access is up or coming up (`remote`: a tunnel, Tailscale Serve or Funnel), or
 *  it hosts Turbo Link peers (`linkPeers`, the link tokens it minted for other machines). Quitting would cut off the
 *  devices using it, so a close keeps the daemon up. A daemon from before that field leaves it out, which reads as
 *  nothing served, as does a `serving` that is not an object, a `lan` or `remote` that is not a boolean and a
 *  `linkPeers` that is not a number: only the daemon's own true or count says something is served. */
private fun daemonServes(json: JSONObject): Boolean {
    val serving = json.optJSONObject("serving") ?: return false
    return serving.opt("lan") == true || serving.opt("remote") == true ||
        ((serving.opt("linkPeers") as? Number)?.toDouble() ?: 0.0) > 0
}

/** The work line comes from the items' `kind`. A model load is named first: nothing generates on that model until it
 *  is loaded, and a chat waiting for it is listed too. Then an auto-tune, which runs the engine itself. A chat, Code
 *  run or routine is "Generating", and so is a kind this does not know, or an item that is not an object, so new work
 *  still reads as work. A skill being written is named only when nothing else runs. An API client's generation has no
 *  item, only `engineGenerating`. */
private fun daemonActivity(json: JSONObject): DaemonActivity {
    val items = json.optJSONArray("items")
    val kinds = (0 until (items?.length() ?: 0)).map { items?.optJSONObject(it)?.optString("kind").orEmpty() }
    val work = when {
        "load" in kinds -> "Loading a model"
        "tune" in kinds -> "Auto-tuning"
        kinds.any { it != "skill" } -> "Generating"
        kinds.isNotEmpty() -> "Writing a skill"
        json.optBoolean("engineGenerating", false) -> "Generating"
        else -> null
    }
    return DaemonActivity(work, (json.optJSONArray("waiting")?.length() ?: 0) > 0)
}
