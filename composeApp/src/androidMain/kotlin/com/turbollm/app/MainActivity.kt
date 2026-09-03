package com.turbollm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

private const val TAG = "TurboLLM"

class MainActivity : ComponentActivity() {
    private companion object {
        const val CHANNEL_ID = "TurboLLM_Daemon"
    }

    private var startedNodeAlready = false
    private val nodeRuntime = NodeRuntimeLauncher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        if (!startedNodeAlready) {
            startedNodeAlready = true
            Thread {
                try {
                    nodeRuntime.start()
                    Log.i(TAG, "Node.js started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Node.js", e)
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Node.js start failed: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.start()
        }

        setContent { App() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TurboLLM Daemon",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "TurboLLM local LLM daemon service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
