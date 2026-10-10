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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "TurboLLM"
private const val CHANNEL_ID = "engine"
private const val NOTIFICATION_ID = 1
private const val ACTION_QUIT = "com.turbollm.app.QUIT"
private const val POLL_MS = 3000L

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
 *  It also holds a partial wake lock, but only while something is generating, so a long reply
 *  finishes with the screen off without keeping the CPU awake while idle. "Generating" comes
 *  from the daemon's own /api/v1/activity (the gate the UI uses), polled over loopback. */
class EngineService : Service() {
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var busy = false
    @Volatile private var thermal = 0

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
        if (intent?.action == ACTION_QUIT) {
            quit()
            return START_NOT_STICKY
        }
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
        running = false
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        super.onDestroy()
    }

    /** "Quit" in the notification: the only way to free a loaded model's memory short of force-stop, since the
     *  service otherwise outlives the activity. Killing the process takes the engine children with it (Android kills
     *  the app's whole process group). */
    private fun quit() {
        Log.i(TAG, "quit from the notification")
        running = false
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun pollActivity() {
        while (running) {
            val nowBusy = queryBusy()
            if (nowBusy != busy) {
                busy = nowBusy
                if (nowBusy) wakeLock?.acquire(30 * 60 * 1000L) else releaseWakeLock()
                updateNotification()
            } else if (nowBusy) {
                wakeLock?.acquire(30 * 60 * 1000L) // renew the timeout for a generation that is still going
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** True while a chat, Code run, routine or API generation is in flight. Any failure (daemon still booting) = idle. */
    private fun queryBusy(): Boolean = try {
        val conn = URL("http://127.0.0.1:$ANDROID_DAEMON_PORT/api/v1/activity").openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode != 200) {
                false
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                body.contains("\"engineGenerating\":true") || !body.contains("\"items\":[]")
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
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
        val hot = thermal >= PowerManager.THERMAL_STATUS_SEVERE
        val text = when {
            hot && busy -> "Generating. The phone is hot, so it is throttling speed"
            hot -> "Running. The phone is hot, so generation will be slower"
            busy -> "Generating"
            else -> "Running in the background"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TurboLLM")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Quit", quit)
            .build()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
            }.onFailure { Log.w(TAG, "could not start the engine service", it) }
        }
    }
}
