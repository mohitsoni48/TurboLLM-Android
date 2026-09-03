package com.turbollm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun TurboLLMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6C3CE1),
            primaryContainer = Color(0xFF4A1FB5),
            background = Color(0xFFF8F9FA),
            onBackground = Color(0xFF1A1A1A),
        ),
        content = content,
    )
}

/** Shared across every target — Spike A's whole UI, portable as-is. Only the Node-runtime
 *  bootstrap (NodeRuntimeLauncher, called from each platform's entry point before this
 *  composable is shown) is platform-specific; everything this screen does is plain HTTP
 *  polling of the daemon, which needs no platform branching at all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val client = remember { DaemonClient() }
    var status by remember { mutableStateOf<DaemonStatus?>(null) }
    var waitingReason by remember { mutableStateOf("Waiting for daemon…") }
    var checking by remember { mutableStateOf(false) }

    suspend fun poll() {
        val result = client.status()
        if (result != null) status = result else waitingReason = "Waiting for daemon…"
    }

    LaunchedEffect(Unit) {
        while (true) {
            poll()
            delay(3000)
        }
    }

    TurboLLMTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                "TurboLLM Android",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = Color.White,
                        ),
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Spike A — Node.js Embedded Runtime",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Pass bar: JS console.log visible in adb logcat, HTTP server on :6996",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Daemon HTTP Server (127.0.0.1:6996)", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            if (status != null) {
                                Text("✓ ok", color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                            } else {
                                Text("○ $waitingReason", color = Color(0xFF888888))
                            }
                        }
                    }

                    Button(
                        onClick = { checking = true },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Check Daemon")
                    }
                    LaunchedEffect(checking) {
                        if (checking) {
                            poll()
                            checking = false
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Process Versions (live, from the daemon)", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            val s = status
                            if (s != null) {
                                Text("node: ${s.node}")
                                Text("v8: ${s.v8}")
                                Text("libuv: ${s.uv}")
                                Text("platform: ${s.platform}")
                                Text("arch: ${s.arch}")
                            } else {
                                Text("Not yet available — waiting for the daemon's first response.")
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Verification", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "1. Install APK on device: adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("2. Watch logcat: adb logcat | grep TurboLLM", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "3. Look for: 'TurboLLM: Node.js embedded runtime booted successfully'",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("4. Confirm HTTP responds: curl http://127.0.0.1:6996/", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
