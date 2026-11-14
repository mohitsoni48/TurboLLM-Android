package dev.turbollm.sdk

import android.content.Context
import dev.turbollm.sdk.api.ChatCompletion
import dev.turbollm.sdk.api.ChatCompletionChunk
import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.chat.ChatService
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.chat.ModelResolver
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.models.DownloadEnvironment
import dev.turbollm.sdk.models.DownloadPlanner
import dev.turbollm.sdk.models.Downloader
import dev.turbollm.sdk.models.HfCatalog
import dev.turbollm.sdk.models.HfFileSource
import dev.turbollm.sdk.models.LoadTargets
import dev.turbollm.sdk.models.ModelManagerImpl
import dev.turbollm.sdk.models.ModelStore
import dev.turbollm.sdk.server.ControlRoutes
import dev.turbollm.sdk.server.LocalServer
import dev.turbollm.sdk.server.ModelListing
import dev.turbollm.sdk.server.OpenAiRoutes
import io.ktor.server.routing.Route
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The SDK's process-wide entry point. It owns every other part: the loaded model, chat, model management and
 * the opt-in local server. Creating it is cheap: nothing loads and nothing starts until it is asked to.
 */
class TurboLLM private constructor(
    private val config: TurboLLMConfig,
    private val dependencies: TurboLLMDependencies,
) : Closeable {

    private val modelRoot = config.modelRoot.canonicalFile
    private val cacheDir = config.cacheDir ?: dependencies.defaultCacheDir
    private val scope = CoroutineScope(SupervisorJob() + dependencies.coreDispatcher)
    private val unloadScope = CoroutineScope(SupervisorJob() + dependencies.coreDispatcher)
    private val previousUnload: Job? = closing
    private val gate: GenerationGate = GenerationGate(config.maxQueuedRequests) { host.showOccupancy() }
    private val sentinel = LoadSentinel(modelRoot)
    private val host: EngineHost = EngineHost(dependencies.engines, gate, sentinel) { previousUnload?.join() }
    private val store = ModelStore(modelRoot, dependencies.clock) { loadedModel()?.id }
    private val targets = LoadTargets(store, modelRoot, config.allowLoadByPath, cacheDir, dependencies.engines)
    private val chatService = ChatService(
        ModelResolver(store, host, config.autoLoadOnRequest, cacheDir, dependencies.engines),
        gate,
        host,
        dependencies.clock,
    )
    private val localServer = LocalServer(dependencies.transport, dependencies.logger) { installRoutes(this) }
    private val serverLifecycle = Mutex()
    private val runningServer = MutableStateFlow<ServerHandle?>(null)
    private val modelManager = lazy(::createModelManager)
    private val closed = AtomicBoolean(false)

    /**
     * The model a native load was loading when the previous process ended, read by the first core of this
     * process for its model root; else null (a later core never reads a closing core's live record). The process
     * may have crashed, been quit or been killed during that load: nothing tells these apart, so a host should
     * not reload that model automatically.
     */
    val interruptedLoad: String? = staleLoadOnce(modelRoot, sentinel)

    val state: StateFlow<EngineState> = host.state

    /** Created on first use: it reads the download manifest, which creating the core must not. */
    val models: ModelManager get() = modelManager.value

    val server: StateFlow<ServerHandle?> = runningServer.asStateFlow()

    /** The engines this SDK build hosts. Catalog order is the preference order for by-format routing. */
    fun engines(): List<EngineInfo> = dependencies.engines.list()

    suspend fun load(model: ModelRef, options: LoadOptions = LoadOptions()): LoadedModel =
        host.load(targets.of(model, options))

    suspend fun unload() = host.unload()

    suspend fun chat(request: ChatCompletionRequest): ChatCompletion = chatService.complete(request)

    /** Cold: nothing runs until it is collected, and cancelling the collector aborts the generation. */
    fun chatStream(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = chatService.stream(request)

    /** Starts the loopback server once; while it runs, every call returns the same handle. */
    suspend fun startServer(options: ServerOptions = ServerOptions()): ServerHandle = serverLifecycle.withLock {
        refuseServerDisabledForTesting()
        runningServer.value ?: localServer.start(options).also { runningServer.value = it }
    }

    suspend fun stopServer() = serverLifecycle.withLock {
        withContext(dependencies.ioDispatcher) { stopServerNow() }
    }

    /**
     * Stops the server, cancels in-flight work and pauses running downloads, then unloads the model in the
     * background: it never blocks on a generation that has to finish on the caller's own thread. Afterwards
     * [getOrCreate] makes a fresh core, whose loads wait until this unload has ended.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val unloading = unloadScope.launch(start = CoroutineStart.LAZY) {
            previousUnload?.join()
            host.unload()
        }
        retire(this, unloading)
        stopServerNow()
        scope.cancel()
        unloading.start()
    }

    private fun refuseServerDisabledForTesting() {
        if (!dependencies.debugFaults.noServer) return
        dependencies.logger.info("Local server not started: disabled for testing by the debug faults file.")
        error("The SDK server is disabled for testing (debug build).")
    }

    private fun stopServerNow() {
        localServer.stop()
        runningServer.value = null
    }

    private fun installRoutes(route: Route) {
        ControlRoutes(dependencies.engines, targets, host, gate, scope).install(route)
        OpenAiRoutes(chatService, ModelListing(store) { host.state.value }).install(route)
    }

    private fun createModelManager(): ModelManager {
        val catalog = HfCatalog(dependencies.fetcher, config.hfBaseUrl, config.hfToken, dependencies.clock)
        val downloader = Downloader(
            source = HfFileSource(dependencies.fetcher, config.hfBaseUrl, config.hfToken),
            planner = DownloadPlanner(catalog::expand),
            root = modelRoot,
            environment = DownloadEnvironment(
                scope = scope,
                io = dependencies.ioDispatcher,
                clock = dependencies.clock,
                freeBytes = dependencies.freeBytes,
            ),
            onModelInstalled = store::invalidate,
        )
        return ModelManagerImpl(store, catalog, downloader)
    }

    private fun loadedModel(): LoadedModel? = (host.state.value as? EngineState.Ready)?.model

    private fun sharedWith(requested: TurboLLMConfig): TurboLLM {
        check(requested == config) { "TurboLLM is already running with a different config; a process has one." }
        return this
    }

    companion object {
        private val lock = Any()

        @Volatile
        private var live: TurboLLM? = null

        /** The unload of the last closed core; the next core's loads wait for it. */
        @Volatile
        private var closing: Job? = null

        private val rootsReadForStaleLoads = mutableSetOf<File>()

        /**
         * Process singleton. A second call returns the same instance; a config that differs from the live one
         * is refused (IllegalStateException), so two parts of one app cannot silently disagree.
         */
        fun getOrCreate(context: Context, config: TurboLLMConfig): TurboLLM =
            liveOrNew(config) { TurboLLMDependencies.forAndroid(context.applicationContext) }

        fun currentOrNull(): TurboLLM? = live

        internal fun createForTest(config: TurboLLMConfig, dependencies: TurboLLMDependencies): TurboLLM =
            liveOrNew(config) { dependencies }

        private fun liveOrNew(config: TurboLLMConfig, dependencies: () -> TurboLLMDependencies): TurboLLM =
            synchronized(lock) {
                live?.sharedWith(config) ?: TurboLLM(config, dependencies()).also { live = it }
            }

        private fun retire(core: TurboLLM, unloading: Job) = synchronized(lock) {
            if (live === core) live = null
            closing = unloading
        }

        private fun staleLoadOnce(modelRoot: File, sentinel: LoadSentinel): String? = synchronized(lock) {
            if (rootsReadForStaleLoads.add(modelRoot)) sentinel.consumeStale() else null
        }
    }
}
