package com.turbollm.app

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** The engine notification's progress bar is out of this many steps: a multi-GB byte count does not fit the Int
 *  setProgress takes, and a thousand steps is finer than the bar can draw. */
internal const val DOWNLOAD_PROGRESS_MAX = 1000

/** One row of the daemon's GET /api/v1/downloads that is running or queued, the two statuses that keep the engine
 *  service at work (a paused or failed download transfers nothing). [total] is 0 while the size is unknown: a raw
 *  URL until its reply names a length. [bytesPerSec] is the daemon's own measure, 0 before the first one. */
internal data class LiveDownload(
    val name: String,
    val queued: Boolean,
    val received: Long,
    val total: Long,
    val bytesPerSec: Double,
)

/** What the engine notification shows while downloads run or wait (EngineService.buildNotification). [progress] is
 *  out of [DOWNLOAD_PROGRESS_MAX] and means nothing when [indeterminate]. */
internal data class DownloadNotice(val title: String, val text: String, val progress: Int, val indeterminate: Boolean)

/** The running and queued rows of a GET /api/v1/downloads reply, or null when the reply is not the list the daemon
 *  sends (the poll then counts as failed, and as idle, as before). A row with a field missing or of another type
 *  reads that field as 0 instead of failing the whole list. */
internal fun parseLiveDownloads(body: String): List<LiveDownload>? =
    runCatching { JSONObject(body) }.getOrNull()?.let(::liveDownloads)

/** The running and queued rows of [json]'s `downloads`, as parseLiveDownloads reads them: the list GET
 *  /api/v1/downloads answers, and the one a GET /api/v1/activity reply carries too from a daemon that sends it there
 *  (TurboLLM's active-work.ts, its running and queued rows only). Null when [json] has no such list. */
internal fun liveDownloads(json: JSONObject): List<LiveDownload>? {
    val list = json.optJSONArray("downloads") ?: return null
    return (0 until list.length()).mapNotNull { i ->
        val row = list.optJSONObject(i) ?: return@mapNotNull null
        val status = row.optString("status")
        if (status != "downloading" && status != "queued") return@mapNotNull null
        val speed = row.optDouble("bytesPerSec", 0.0)
        LiveDownload(
            name = row.optString("name"),
            queued = status == "queued",
            received = row.optLong("received").coerceAtLeast(0),
            total = row.optLong("total").coerceAtLeast(0),
            bytesPerSec = if (speed.isFinite() && speed > 0) speed else 0.0,
        )
    }
}

/** The notification for [rows], or null when there are none. One running file is named in the title (the system
 *  ellipsizes a long name), with "<received> of <total> · <speed>" under it; several are summed under "Downloading
 *  models", as "N downloads · ...". Queued rows are counted as "N waiting", not summed: nothing of theirs is moving.
 *  The bar runs over the summed totals of the running files, and is indeterminate while any of their sizes is
 *  unknown or nothing is running yet. A speed of 0 (none measured yet) is left out, as the web's Downloads panel
 *  leaves it out. */
internal fun downloadNotice(rows: List<LiveDownload>): DownloadNotice? {
    if (rows.isEmpty()) return null
    val running = rows.filter { !it.queued }
    val waiting = rows.size - running.size
    val received = running.sumOf { it.received }
    val total = running.sumOf { it.total }
    val sized = running.isNotEmpty() && running.all { it.total > 0 }
    val speed = running.sumOf { it.bytesPerSec }
    val amount = buildString {
        append(formatSize(received))
        if (sized) append(" of ").append(formatSize(total))
        if (speed > 0) append(" · ").append(formatSpeed(speed))
    }
    val text = when (running.size) {
        0 -> if (waiting == 1) "1 download waiting" else "$waiting downloads waiting"
        1 -> amount
        else -> "${running.size} downloads · $amount"
    }
    return DownloadNotice(
        title = running.singleOrNull()?.name?.takeIf { it.isNotBlank() } ?: "Downloading models",
        text = if (running.isNotEmpty() && waiting > 0) "$text · $waiting waiting" else text,
        progress = if (sized) (received.coerceAtMost(total) * DOWNLOAD_PROGRESS_MAX / total).toInt() else 0,
        indeterminate = !sized,
    )
}

/** A size as the web's Downloads panel writes it (DownloadsPanel.tsx fmtSize), so the shade and the app agree:
 *  decimal units, "1.23 GB" from 1 GB up and whole megabytes below. Locale.ROOT, like the panel's toFixed. */
internal fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
    else "${(bytes / 1e6).roundToLong()} MB"

/** A speed as the same panel writes it (fmtSpeed): "12.3 MB/s" from 1 MB/s up, whole kilobytes below. */
internal fun formatSpeed(bytesPerSec: Double): String =
    if (bytesPerSec >= 1e6) String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / 1e6)
    else "${(bytesPerSec / 1e3).roundToLong()} KB/s"
