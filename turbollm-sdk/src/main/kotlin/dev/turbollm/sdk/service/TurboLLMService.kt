package dev.turbollm.sdk.service

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.R
import dev.turbollm.sdk.TurboLLM
import dev.turbollm.sdk.TurboLLMConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An opt-in foreground service that keeps the process, and the [TurboLLM] core it holds, alive while the host's
 * UI is gone. The SDK's manifest does not declare it: a host that wants it declares this class, or its own
 * subclass, with the foreground-service permissions (see the SDK README).
 *
 * The service never closes the core and never kills the process. The host owns both, because something else in
 * the process may still be using the core when the service stops.
 */
open class TurboLLMService : Service() {

    protected lateinit var core: TurboLLM
        private set

    protected open val notificationId: Int = DEFAULT_NOTIFICATION_ID

    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationUpdates: Job? = null

    override fun onCreate() {
        super.onCreate()
        core = TurboLLM.getOrCreate(applicationContext, provideConfig())
    }

    /** A subclass that overrides this must call [startInForeground] itself. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        repostOnEveryStateChange()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationScope.cancel()
        super.onDestroy()
    }

    /** The default reads [defaultConfig], which the host sets before [start]. */
    protected open fun provideConfig(): TurboLLMConfig = checkNotNull(defaultConfig) {
        "Set TurboLLMService.defaultConfig before starting the service, or override provideConfig()."
    }

    protected open fun buildNotification(state: EngineState): Notification = notificationBuilder()
        .setSmallIcon(R.drawable.ic_turbollm_sdk_stat)
        .setContentTitle(getString(R.string.turbollm_sdk_notification_title))
        .setContentText(notificationText(state))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    /** The same foreground start as AndroidX `ServiceCompat.startForeground`, as a special-use service on 34+. */
    protected fun startInForeground() {
        val notification = buildNotification(core.state.value)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(notificationId, notification, NO_FOREGROUND_SERVICE_TYPE)
            else -> startForeground(notificationId, notification)
        }
    }

    private fun repostOnEveryStateChange() {
        if (notificationUpdates?.isActive == true) return
        notificationUpdates = notificationScope.launch {
            core.state.collect(::repostNotification)
        }
    }

    private fun repostNotification(state: EngineState) {
        notificationManager().notify(notificationId, buildNotification(state))
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, createChannel())
        else Notification.Builder(this)

    @TargetApi(Build.VERSION_CODES.O)
    private fun createChannel(): String {
        val name = getString(R.string.turbollm_sdk_channel_name)
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW),
        )
        return CHANNEL_ID
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val DEFAULT_NOTIFICATION_ID = 0x7E11
        private const val NO_FOREGROUND_SERVICE_TYPE = 0
        private const val CHANNEL_ID = "turbollm_sdk"

        /** The config the default [provideConfig] uses. A host sets it before [start]. */
        @Volatile
        var defaultConfig: TurboLLMConfig? = null

        /** Starts [service], the class the host's manifest declares: this one, or the host's own subclass. */
        fun start(context: Context, service: Class<out TurboLLMService> = TurboLLMService::class.java) {
            val intent = serviceIntent(context, service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** Stops [service], the same class [start] was given. */
        fun stop(context: Context, service: Class<out TurboLLMService> = TurboLLMService::class.java) {
            context.stopService(serviceIntent(context, service))
        }

        internal fun serviceIntent(context: Context, service: Class<out TurboLLMService>) = Intent(context, service)
    }
}
