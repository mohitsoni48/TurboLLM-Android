package com.turbollm.app

import java.io.File
import java.io.RandomAccessFile

/** Keeps files/daemon.log (Node's stdout/stderr, native-lib.cpp) bounded.
 *
 *  It is truncated only when Node starts, once per process, and EngineService now keeps the process alive for as
 *  long as the user lets it, so without this it grew for the life of the process. Above [MAX_BYTES] the current log
 *  becomes daemon.log.1 (replacing the previous one) and daemon.log restarts empty. The descriptor Node writes
 *  through is O_APPEND, so after the truncate its next write lands at offset 0, not at the old end. A line written
 *  between the copy and the truncate can be lost; for a diagnostics log that is acceptable. */
object DaemonLog {
    private const val MAX_BYTES = 4L * 1024 * 1024

    fun file(filesDir: File) = File(filesDir, "daemon.log")

    fun rotateIfLarge(filesDir: File) {
        val log = file(filesDir)
        if (log.length() <= MAX_BYTES) return
        runCatching {
            log.copyTo(File(filesDir, "daemon.log.1"), overwrite = true)
            RandomAccessFile(log, "rw").use { it.setLength(0) }
        }
    }
}
