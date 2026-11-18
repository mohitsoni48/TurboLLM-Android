package com.turbollm.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val Light = Color(0xFFEDEDEC)
private val Muted = Color(0xFFB4B4B2)

/** Shown instead of the WebView when the daemon recorded a fatal start failure (DaemonFatal). On the splash's pinned
 *  dark surface, hence the explicit light text colors, like the renderer-crash screen. [onTryAgain] deletes the record
 *  and starts the app's boot again; nothing here starts Node by itself. */
@Composable
fun DaemonFatalScreen(fatal: DaemonFatal, daemonLog: File, onTryAgain: () -> Unit) {
    val context = LocalContext.current
    // Back leaves the app, as from the WebView's root screen.
    BackHandler(enabled = true) { (context as? Activity)?.finish() }
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TurboLLM could not start", color = Light, fontSize = 22.sp)
        Text(
            "This build of the app is missing a part it needs. Reinstalling or updating the app from the Play Store " +
                "usually fixes it.",
            color = Light,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Scrollable and selectable: the message can be a long CoreLoadError listing every path tried.
        SelectionContainer(modifier = Modifier.weight(1f, fill = false).padding(top = 16.dp)) {
            Text(
                fatal.message,
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTryAgain) { Text("Try again") }
            OutlinedButton(onClick = { copyDetails(context, fatal, daemonLog) }) { Text("Copy details") }
        }
    }
}

/** The message, plus where the full log is, for a bug report. */
private fun fatalDetails(fatal: DaemonFatal, daemonLog: File): String =
    "${fatal.message}\n\n(${fatal.code}, exit ${fatal.exitCode}, ${fatal.at})\nLog: ${daemonLog.absolutePath}"

private fun copyDetails(context: Context, fatal: DaemonFatal, daemonLog: File) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TurboLLM error", fatalDetails(fatal, daemonLog)))
}
