package dev.turbollm.sdk.models

import dev.turbollm.sdk.DownloadError
import dev.turbollm.sdk.DownloadHandle
import dev.turbollm.sdk.DownloadState
import dev.turbollm.sdk.DownloadStatus
import dev.turbollm.sdk.internal.Clock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_CONCURRENT_DOWNLOADS = 2
private const val SPEED_REFRESH_MILLIS = 500L
private const val BYTES_PER_GB = 1_000_000_000.0

/** Resolves what a download of `file` in `repo` fetches: the commit and every shard (HfCatalog.expand). */
internal fun interface DownloadPlanner {
    suspend fun plan(repo: String, file: String): HfDownloadPlan
}

/** Where downloads run: [scope] owns them, [io] runs file and network work, [freeBytes] measures a folder. */
internal class DownloadEnvironment(
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val clock: Clock,
    val freeBytes: (File) -> Long,
)

/**
 * Resumable, verified downloads into `<root>/<owner>/<repo>/<path>`, a port of the daemon's `DownloadManager`
 * (`downloads.ts`): at most [MAX_CONCURRENT_DOWNLOADS] at once, no automatic retry, rows persisted in a
 * [DownloadManifest] on every state change and brought back [DownloadState.PAUSED] at startup. One row is one
 * model: every shard of a split GGUF, fetched in order. The public operations never block: disk work runs on
 * [DownloadEnvironment.io].
 */
internal class Downloader(
    private val source: HfFileSource,
    private val planner: DownloadPlanner,
    private val root: File,
    private val environment: DownloadEnvironment,
    private val onModelInstalled: () -> Unit,
) {
    private val manifest = DownloadManifest(root)
    private val manifestWrites = Mutex()
    private val lock = Any()
    private val rows = LinkedHashMap<String, DownloadRow>()
    private val runs = mutableMapOf<String, Job>()
    private val openResponses = mutableMapOf<String, HttpResponse>()
    private var speedTicker: Job? = null
    private val statuses = MutableStateFlow<List<DownloadStatus>>(emptyList())
    val downloads: StateFlow<List<DownloadStatus>> = statuses.asStateFlow()

    init {
        manifest.load().map { DownloadRow.restored(it) }.forEach { row ->
            row.bytes = bytesOnDisk(row)
            rows[row.id] = row
        }
        publish()
    }

    fun download(repo: String, file: String): DownloadHandle = synchronized(lock) {
        val row = rows.values.firstOrNull { it.repo == repo && it.file == file }
            ?: DownloadRow.queued(UUID.randomUUID().toString(), repo, file).also {
                rows[it.id] = it
                changed()
            }
        startQueued()
        DownloadHandle(row.id, row.repo, row.file)
    }

    /** Stops a running or queued row; its `.part` is kept for [resume]. */
    fun pause(id: String): Unit = synchronized(lock) {
        val row = rows[id]?.takeIf { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
            ?: return
        row.state = DownloadState.PAUSED
        row.bytesPerSec = 0
        stopRun(id)
        changed()
        startQueued()
    }

    /** Queues a paused or failed row again; it continues from its `.part`. */
    fun resume(id: String): Unit = synchronized(lock) {
        val row = rows[id]?.takeIf { it.state == DownloadState.PAUSED || it.state == DownloadState.ERROR } ?: return
        row.state = DownloadState.QUEUED
        row.error = null
        changed()
        startQueued()
    }

    /** Removes the row and its `.part`; files already installed stay. */
    fun cancel(id: String): Unit = synchronized(lock) {
        val row = rows.remove(id) ?: return
        if (id in runs) stopRun(id) else environment.scope.launch(environment.io) { deletePart(row) }
        changed()
        startQueued()
    }

    private fun startQueued() {
        val free = MAX_CONCURRENT_DOWNLOADS - rows.values.count { it.state == DownloadState.RUNNING }
        rows.values.filter { it.state == DownloadState.QUEUED && it.id !in runs }.take(free).forEach(::start)
    }

    private fun start(row: DownloadRow) {
        row.state = DownloadState.RUNNING
        row.speed = DownloadSpeed(environment.clock, row.bytes)
        changed()
        val run = environment.scope.launch(environment.io, CoroutineStart.LAZY) { runDownload(row) }
        runs[row.id] = run
        run.invokeOnCompletion { settle(row) }
        run.start()
        startSpeedTicker()
    }

    private fun stopRun(id: String) {
        runs[id]?.cancel()
        openResponses.remove(id)?.close()
    }

    private suspend fun runDownload(row: DownloadRow) {
        try {
            install(row)
            synchronized(lock) { row.finish() }
            onModelInstalled()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (failure: Exception) {
            synchronized(lock) { if (!wasStopped(row)) row.fail(failure.message ?: failure.toString()) }
        }
    }

    /** Paused or cancelled while running: closing its response can surface as an IOException, not a cancel. */
    private fun wasStopped(row: DownloadRow): Boolean = row.id !in rows || row.state != DownloadState.RUNNING

    /**
     * Runs once a run has ended, even one cancelled before it started. A cancelled row's `.part` goes now that
     * its writer has closed it; any other unfinished row counts what is left on disk (a failed check deletes the
     * `.part`), and one stopped by its scope (the SDK closing) is paused.
     */
    private fun settle(row: DownloadRow) = synchronized(lock) {
        runs.remove(row.id)
        openResponses.remove(row.id)
        if (row.id !in rows) {
            deletePart(row)
        } else if (row.state != DownloadState.DONE) {
            if (row.state == DownloadState.RUNNING) row.state = DownloadState.PAUSED
            row.bytesPerSec = 0
            row.bytes = bytesOnDisk(row)
        }
        changed()
        startQueued()
    }

    private suspend fun install(row: DownloadRow) {
        val targets = plannedTargets(row)
        requireFreeSpace(targets)
        var finishedBytes = 0L
        targets.forEach { target ->
            if (!target.isInstalled()) transfer(row, target, finishedBytes)
            finishedBytes += target.shard.sizeBytes
        }
    }

    private suspend fun plannedTargets(row: DownloadRow): List<ShardTarget> {
        if (row.shards.isEmpty()) {
            val plan = planner.plan(row.repo, row.file)
            plan.files.forEach { destinationOf(row.repo, it.path) }
            synchronized(lock) {
                row.commit = plan.commit
                row.shards = plan.files
                changed()
            }
        }
        return targetsOf(row)
    }

    private fun requireFreeSpace(targets: List<ShardTarget>) {
        val stillNeeded = targets.sumOf { it.bytesStillNeeded() }
        val required = stillNeeded + stillNeeded / 10
        root.mkdirs()
        val free = environment.freeBytes(root)
        if (free < required) {
            throw DownloadError("Not enough free space: needs about ${gigabytes(required)}, ${gigabytes(free)} free.")
        }
    }

    private suspend fun transfer(row: DownloadRow, target: ShardTarget, finishedBytes: Long) {
        val listener = RowTransferListener(row, finishedBytes)
        ShardTransfer(source, target, listener).fetch(synchronized(lock) { row.validator })
        synchronized(lock) { row.validator = null }
    }

    private inner class RowTransferListener(
        private val row: DownloadRow,
        private val finishedBytes: Long,
    ) : TransferListener {
        override fun onResponse(response: HttpResponse) = synchronized(lock) {
            openResponses[row.id] = response
        }

        override fun onValidator(validator: String?): Unit = synchronized(lock) {
            if (validator == row.validator) return
            row.validator = validator
            persist()
        }

        override fun onShardBytes(bytes: Long) = synchronized(lock) {
            row.bytes = finishedBytes + bytes
            row.speed?.record(row.bytes)
            row.bytesPerSec = row.speed?.bytesPerSec() ?: 0
            publish()
        }
    }

    private fun startSpeedTicker() {
        if (speedTicker?.isActive == true) return
        speedTicker = environment.scope.launch(environment.io) {
            do {
                delay(SPEED_REFRESH_MILLIS)
            } while (refreshSpeeds())
        }
    }

    /** Re-reads every running row's speed, so a stalled transfer drops to 0; false once none is running. */
    private fun refreshSpeeds(): Boolean = synchronized(lock) {
        val running = rows.values.filter { it.state == DownloadState.RUNNING }
        running.forEach { it.bytesPerSec = it.speed?.bytesPerSec() ?: 0 }
        publish()
        running.isNotEmpty()
    }

    private fun bytesOnDisk(row: DownloadRow): Long = targetsOf(row).sumOf { it.bytesOnDisk() }

    private fun deletePart(row: DownloadRow) {
        targetsOf(row).forEach { Files.deleteIfExists(it.part.toPath()) }
    }

    private fun targetsOf(row: DownloadRow): List<ShardTarget> {
        val commit = row.commit ?: return emptyList()
        return row.shards.map { ShardTarget(row.repo, commit, it, destinationOf(row.repo, it.path)) }
    }

    /** `<root>/<owner>/<repo>/<path>`; a repo or path that could leave the root is refused. */
    private fun destinationOf(repo: String, path: String): File {
        val repoSegments = repo.split('/')
        val segments = repoSegments + path.split('/')
        if (repoSegments.size != 2 || segments.any(::isUnsafeSegment)) {
            throw DownloadError("Refusing to write '$path' from '$repo' outside the model folder.")
        }
        return segments.fold(root) { folder, segment -> File(folder, segment) }
    }

    private fun changed() {
        persist()
        publish()
    }

    private fun publish() {
        statuses.value = rows.values.map { it.status() }
    }

    /**
     * Each write saves the rows as they are when it runs, so the last write always holds the latest state.
     * A failed write is dropped on purpose: the manifest only brings rows back after a restart, a full disk
     * must not crash the app or fail a download whose bytes are fine, and the next state change writes it again.
     */
    private fun persist() {
        environment.scope.launch(environment.io) {
            manifestWrites.withLock {
                try {
                    manifest.save(synchronized(lock) { manifestRows() })
                } catch (unwritable: IOException) {
                    return@withLock
                }
            }
        }
    }

    private fun manifestRows(): List<ManifestRow> =
        rows.values.filter { it.state != DownloadState.DONE }.map { it.manifestRow() }
}

/** One model download's live state; [commit] and [shards] are known once the planner has run. */
private class DownloadRow(
    val id: String,
    val repo: String,
    val file: String,
    var commit: String?,
    var shards: List<HfRemoteFile>,
    var validator: String?,
    var state: DownloadState,
) {
    var bytes = 0L
    var bytesPerSec = 0L
    var error: String? = null
    var speed: DownloadSpeed? = null

    private val total: Long get() = shards.sumOf { it.sizeBytes }

    fun finish() {
        state = DownloadState.DONE
        bytes = total
        bytesPerSec = 0
    }

    fun fail(reason: String) {
        state = DownloadState.ERROR
        error = reason
        bytesPerSec = 0
    }

    fun status() = DownloadStatus(id, repo, file, state, bytes, total, bytesPerSec, error)

    fun manifestRow() = ManifestRow(
        id = id,
        repo = repo,
        file = file,
        sha = commit,
        path = shards.firstOrNull()?.let { "$repo/${it.path}" },
        total = total,
        state = state.name,
        shards = shards.map { ManifestShard(it.path, it.sizeBytes, it.sha256) },
        validator = validator,
    )

    companion object {
        fun queued(id: String, repo: String, file: String) =
            DownloadRow(id, repo, file, commit = null, shards = emptyList(), validator = null, DownloadState.QUEUED)

        fun restored(row: ManifestRow) = DownloadRow(
            id = row.id,
            repo = row.repo,
            file = row.file,
            commit = row.sha,
            shards = row.shards.map { HfRemoteFile(it.path, it.size, it.sha256) },
            validator = row.validator,
            state = DownloadState.PAUSED,
        )
    }
}

private fun isUnsafeSegment(segment: String): Boolean =
    segment.isEmpty() || segment == "." || segment == ".." || '\\' in segment || ':' in segment

private fun gigabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / BYTES_PER_GB)
