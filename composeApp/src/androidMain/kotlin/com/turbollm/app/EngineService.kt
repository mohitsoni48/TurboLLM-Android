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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

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
/** The longest the daemon is given to write its database before the service stops (flushDatabase). */
private const val FLUSH_WAIT_MS = 2000L
/** The primary engine states in which it holds a model (the daemon's manager.ts State): see queryLoaded. */
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
 *  It also holds a partial wake lock, but only while there is work: a reply being generated (the
 *  daemon's own /api/v1/activity, the gate the UI uses), a model download (/api/v1/downloads),
 *  both polled over loopback, or a model import (ModelImport). So a long reply or a multi-GB
 *  transfer finishes with the screen off without keeping the CPU awake while idle. */
class EngineService : Service() {
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var busy = false
    @Volatile private var importing = false
    /** What the notification shows while a model download is running or queued (downloadNotice), null while none
     *  is. Written by the poll, read wherever the notification is built. */
    @Volatile private var download: DownloadNotice? = null
    /** Polls in a row that could not read the download list (see pollActivity). Poll thread only. */
    private var downloadMisses = 0
    @Volatile private var thermal = 0
    /** Set when the app was closed with notifications off: stop once no model is loaded and nothing is generating,
     *  importing or downloading (see appClosed). */
    @Volatile private var stopWhenIdle = false
    /** Set when the app was closed with Quit reachable, so the service stayed for it: the poll then keeps checking
     *  that Quit can still be reached (see pollActivity). */
    @Volatile private var closedWithQuit = false
    /** The newest start request, so a stop only takes effect if no start arrived after it (stopSelfResult). Without
     *  this, Back then a quick reopen could stop the service under a pending startForegroundService, which Android
     *  answers by crashing the app ("did not then call Service.startForeground()"). */
    @Volatile private var lastStartId = 0
    private val stopping = AtomicBoolean(false)
    /** Guards `running` against the poll thread: with it held, a check of `running` and the wake-lock or
     *  notification call after it are one step, so onDestroy cannot slip between them. */
    private val stateLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

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
        lastStartId = startId
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
            // service too).
            if (intent.getIntExtra(EXTRA_ACTIVITY, 0) != newestActivity) return START_NOT_STICKY
            appClosed()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPOST) {
            // Android 14+ lets the user swipe the engine notification away (setOngoing no longer prevents it), and its
            // Quit with it, while appClosed() keeps counting on Quit being there. Put it straight back. MainActivity
            // sends this too whenever it comes on screen (repostNotification).
            if (running) updateNotification() else stopSelf(startId)
            return START_NOT_STICKY
        }
        stopWhenIdle = false
        closedWithQuit = false
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
     *  the process takes the engine children with it (Android kills the app's whole process group). */
    private fun quit() {
        Log.i(TAG, "quit from the notification")
        synchronized(stateLock) {
            running = false
            releaseWakeLock()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    /** Swiped away from Recents: the same as backing out of the app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running) appClosed()
        super.onTaskRemoved(rootIntent)
    }

    /** The user left the app (Back, or swiped from Recents). Leaving never unloads the model: only the user does (in
     *  the app, or with Quit), or the daemon's own idle timer (Settings, daemon.idleTtlMinutes, which the user can
     *  change or turn off). With the notification visible, its Quit is how they free the model, so the service stays.
     *  Without it there is nothing to reach, so the service stays only while a model is loaded or work runs, and stops
     *  once neither is true (stopNow), and the process becomes an ordinary closed app Android can reclaim. */
    private fun appClosed() {
        // Post it again first. quitReachable() reads the settings, not the shade, and a post made while notifications
        // were off is dropped for good: on Android 13+ startForeground posts before the user can answer the
        // permission prompt, and a later grant (at that prompt, or in Settings with the process still alive) re-posts
        // nothing. Without this, Back after such a grant left the service and the model running with no notification
        // and no Quit. Posted now, the notification is up whenever the settings say it can be.
        updateNotification()
        if (quitReachable()) {
            closedWithQuit = true
            return
        }
        closedWithQuit = false
        stopWhenIdle = true
        if (!busy) stopNow()
    }

    /** Whether the notification, and its Quit, can be seen: notifications on for the app (Android 13+ asks) and the
     *  Engine channel not turned off on its own. It reads the settings, not the shade, so appClosed posts first. */
    private fun quitReachable(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Stop, once no model is loaded and nothing runs, unless the app came back or a newer start request arrived in
     *  the meantime. It unloads nothing. It used to stop the model (POST /api/v1/engine/stop) as soon as nothing was
     *  generating, so a loaded model was gone after every Back with notifications off; a model is unloaded only by
     *  the user or by the daemon's idle timer, and until one of them has, the poll keeps the service up. The daemon
     *  writes its chat database to disk first (flushDatabase): a stopped service lets Android freeze the process and
     *  later kill it, which loses whatever the daemon had not written yet. */
    private fun stopNow() {
        if (!stopping.compareAndSet(false, true)) return
        val startId = lastStartId
        // The app came back if an activity was created since: MainActivity.onCreate takes its number at once, while
        // the start it sends waits on the main thread behind that onCreate, and stopWhenIdle stays set until it runs.
        val activity = newestActivity
        Thread({
            // `busy` is the poll's view, up to 3 s old: a reply sent or a model load started just before Back would
            // not be in it yet. Ask the daemon now, and again after the flush, right before stopping, so the service
            // never stops under work or a model that has just started.
            val stop = idleNow() && run {
                flushDatabase()
                stopWhenIdle && newestActivity == activity && idleNow()
            }
            ContextCompat.getMainExecutor(this).execute {
                stopping.set(false)
                // onDestroy releases the wake lock and clears `running` once the stop takes effect.
                if (stop && stopWhenIdle && newestActivity == activity && !busy) stopSelfResult(startId)
            }
        }, "turbollm-stop").start()
    }

    /** No model loaded and nothing generating, importing or downloading, asked of the daemon now (see stopNow). */
    private fun idleNow(): Boolean = !queryBusy() && !queryDownloading() && !ModelImport.isRunning() && !queryLoaded()

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
            if (importing != this.importing) {
                this.importing = importing
                updateNotification()
            }
            // So does a model download (Discover): a stopped service or a sleeping CPU would stall a multi-GB transfer.
            val downloads = queryDownloads()
            val downloading = !downloads.isNullOrEmpty()
            val nowBusy = queryBusy() || importing || downloading
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
                if (download != this.download) {
                    this.download = download
                    updateNotification()
                }
                if (nowBusy != busy) {
                    busy = nowBusy
                    if (nowBusy) wakeLock?.acquire(30 * 60 * 1000L) else releaseWakeLock()
                    updateNotification()
                } else if (nowBusy) {
                    wakeLock?.acquire(30 * 60 * 1000L) // renew the timeout for work that is still going
                }
            }
            // The service stayed for Quit when the app was closed, but the user can still turn the Engine channel off
            // (long-press on the notification). That removes the notification without sending its delete intent, so
            // nothing else notices: decide again, as appClosed would now (on the main thread, where onDestroy runs, so
            // a destroyed service posts nothing). (Revoking the app's notification permission on Android 13+ kills
            // the process, which frees the model anyway.)
            if (closedWithQuit && !quitReachable()) {
                ContextCompat.getMainExecutor(this).execute { if (running && closedWithQuit) appClosed() }
            }
            // A loaded model keeps the service up too, until the user or the daemon's idle timer unloads it (stopNow).
            // Asked only while the app is closed with notifications off, the one case it decides anything.
            if (stopWhenIdle && !nowBusy && !queryLoaded()) {
                ContextCompat.getMainExecutor(this).execute { if (stopWhenIdle && !busy) stopNow() }
            }
            if (++polls % 20 == 0) DaemonLog.rotateIfLarge(filesDir) // about once a minute
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** True while a chat, Code run, routine or API generation is in flight. Any failure (daemon still booting) = idle. */
    private fun queryBusy(): Boolean = try {
        val conn = DaemonAuth.open("/api/v1/activity")
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                false
            } else {
                // Parsed, not string-matched: any shape this does not recognise reads as idle, so a format change
                // can never pin the wake lock on with the screen off.
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optBoolean("engineGenerating", false) || (json.optJSONArray("items")?.length() ?: 0) > 0
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /** True while the daemon holds a model: the primary engine loading, running or still stopping (`engine.state`
     *  starting, running or stopping), or any model alive in a pool slot beside it, such as an embedding model or a
     *  Jev or Laya model, all from GET /api/v1/status. `poolModels` counts those slots (running, starting or
     *  stopping); a daemon from before TurboLLM#287 leaves it out, which reads as 0, and names only a Jev or Laya model
     *  (`jev`, `laya`), so those are still read. An engine in `error` holds nothing. Any failure reads as not loaded,
     *  as the work checks read it as idle: a daemon that no longer answers has nothing to keep, and a stop needs two
     *  readings to agree, stopNow's before and after the flush (appClosed calls stopNow at once when nothing is busy;
     *  when the poll starts the stop, its own reading comes first). */
    private fun queryLoaded(): Boolean = try {
        val conn = DaemonAuth.open("/api/v1/status")
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                false
            } else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optJSONObject("engine")?.optString("state") in LOADED_STATES || json.optInt("poolModels") > 0 ||
                    json.optJSONObject("jev") != null || json.optJSONObject("laya") != null
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /** True while the daemon has a model download running or queued (GET /api/v1/downloads). Any failure = idle, as
     *  above; a paused or failed download does not count, as nothing is transferring. */
    private fun queryDownloading(): Boolean = !queryDownloads().isNullOrEmpty()

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
        val text = when {
            importing -> "Importing models"
            download != null -> download.text
            hot && busy -> "Generating. The phone is hot, so it is throttling speed"
            hot -> "Running. The phone is hot, so generation will be slower"
            busy -> "Generating"
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
         *  (see ACTION_ACTIVITY_FINISHED). Set on the main thread, read by the stop thread too. */
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

        /** The app came on screen: post the notification again (ACTION_REPOST). One dropped while notifications were
         *  off then shows as soon as the user allows them, at the first-launch prompt or in Settings, since Android
         *  re-posts nothing on a grant and returning to the app (singleTask, no recreation) does not run start(). */
        fun repostNotification(context: Context) {
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
