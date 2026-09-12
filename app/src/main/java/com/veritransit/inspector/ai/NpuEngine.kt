package com.veritransit.inspector.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * On-device inference backend: Qwen3-VL-4B-Instruct running on the Snapdragon
 * NPU through the Qualcomm GenieX SDK (QAIRT runtime, HTP compute unit).
 *
 * The weights are **not** in the APK. Qualcomm publishes one pre-compiled
 * w4a16 bundle per chipset; the SDK's model manager pulls the bundle matching
 * the chipset this phone actually reports and caches it under the app's files
 * dir. That is a ~3 GB one-time download, so every screen treats the model as
 * optional and degrades to the offline demo path when it is absent.
 *
 * State is exposed as Compose snapshot state — the same convention the rest of
 * the app uses for `Repo` — so screens can observe it without a ViewModel.
 */
object NpuEngine {

    /** AI Hub repo id. The manager canonicalises this to `qualcomm/…` internally. */
    const val MODEL_NAME = "ai-hub-models/Qwen3-VL-4B-Instruct"
    const val DISPLAY_NAME = "Qwen3-VL-4B-Instruct"

    /**
     * The only precision Qualcomm publishes a GenieX-QAIRT bundle at for this
     * model: 4-bit weights, 16-bit activations. The `q4_0` variant on the same
     * release is a llama.cpp GGUF and would run on CPU/GPU, not the NPU.
     */
    const val PRECISION = "w4a16"

    /** Used when the SDK cannot name the SoC — Snapdragon 8 Elite Gen 5. */
    const val FALLBACK_CHIPSET = "SM8850"

    /**
     * Rough on-disk cost of the chipset bundle, for the pre-download prompt.
     * The zip is ~3.0 GB; what the manager actually reports is the sum of the
     * unpacked context binaries, so quote the larger figure rather than have
     * the progress readout overshoot the number the officer agreed to.
     */
    const val BUNDLE_BYTES = 4_380_000_000L

    private const val TAG = "NpuEngine"

    /**
     * Device mode for the runtime. It must be one of the SDK's own
     * [ComputeUnitValue] names — an unrecognised string is not rejected as a
     * device at all, it is re-read as an `--ngl` count, which the qairt plugin
     * then refuses outright.
     */
    private val COMPUTE_UNIT = ComputeUnitValue.NPU.value

    private const val RUNTIME_QAIRT = "qairt"

    /**
     * Square edge the evidence photo is resized to before it reaches the vision
     * tower.
     *
     * Taken from the bundle's own `img-enc-htp.json`, which declares
     * `vision-param: {height: 32, width: 32}` — a 32x32 grid of Qwen3-VL's
     * 16 px patches, so 512 px. `metadata.json` corroborates it: the encoder
     * takes 1024 patches (32x32) and emits 256 image tokens after the 2x2
     * spatial merge. Handing it anything smaller throws away detail for no
     * saving, since the cost is fixed at 256 tokens either way.
     */
    const val VISION_INPUT_PX = 512

    /** What one photograph costs against [CONTEXT_TOKENS], from the same file. */
    const val VISION_TOKENS = 256

    /**
     * Context the bundle was compiled with, from its own `genie_config.json`
     * (`dialog.context.size`). Fixed at compile time — the runtime cannot widen
     * it (`ModelConfig.nCtx` must stay 0) — and one photo already costs ~256 of
     * it, so prompts and `maxTokens` are budgeted against [bundleContextTokens]
     * rather than against the 4096 the model supports off-device.
     *
     * This constant is the fallback assumed until the downloaded bundle has
     * been read; [refreshBundleContext] replaces the effective value with the
     * bundle's own declaration, so a future bundle compiled with a larger
     * window is used to the full automatically.
     */
    const val CONTEXT_TOKENS = BundleContext.DEFAULT_CONTEXT_TOKENS

    /**
     * Context window currently in force, read from the downloaded bundle when
     * available. Observed as Compose state so the On-device AI screen shows
     * the number the budgets actually run against.
     */
    var bundleContextTokens by mutableStateOf(CONTEXT_TOKENS)
        private set

    /** Whether [bundleContextTokens] came from the bundle rather than [CONTEXT_TOKENS]. */
    var contextFromBundle by mutableStateOf(false)
        private set

    /** Window every prompt budget ([ChatSession], [InspectorAi]) must fit. */
    val effectiveContextTokens: Int get() = bundleContextTokens

    enum class Status {
        /** Nothing attempted yet. */
        COLD,
        INITIALIZING,
        /** SDK up, chipset bundle not on disk. */
        NOT_DOWNLOADED,
        DOWNLOADING,
        /** Bundle cached, model not resident on the NPU. */
        DOWNLOADED,
        LOADING,
        /** Resident and idle. */
        READY,
        /** Resident, inference in flight. */
        BUSY,
        ERROR,
    }

    var status by mutableStateOf(Status.COLD)
        private set

    /** Chipset the SDK resolved for this device, e.g. `SM8850`. */
    var chipset by mutableStateOf<String?>(null)
        private set

    var downloadPercent by mutableStateOf(0)
        private set

    var downloadedBytes by mutableStateOf(0L)
        private set

    var totalBytes by mutableStateOf(0L)
        private set

    /** Last failure, surfaced verbatim — these are usually actionable. */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Timings from the most recent completed generation. */
    var lastProfile by mutableStateOf<ProfilingData?>(null)
        private set

    /** Free-text description of what the NPU is doing, for the scan screens. */
    var activity by mutableStateOf<String?>(null)
        private set

    val isReady: Boolean get() = status == Status.READY || status == Status.BUSY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One NPU context; concurrent generate calls would corrupt its KV cache. */
    private val inferenceLock = Mutex()

    /**
     * Serialises creating and destroying the NPU session. Without it an
     * unload landing between a load's state check and its assignment would
     * strand a resident wrapper the engine no longer knows about — cDSP memory
     * held until the process dies.
     */
    private val lifecycleMutex = Mutex()

    /** [SystemClock.elapsedRealtime] of the last load or inference completion. */
    @Volatile
    private var lastUsedAt = 0L

    /**
     * True between the host's onStop and onStart. A load that finishes in
     * that window must not take residency: fast_freezer parks the process
     * ~10 s after the screen goes off, and a ~4 GB session pinned in a frozen
     * process is memory nobody can use until the user returns.
     */
    @Volatile
    private var hostBackgrounded = false

    private var watchdogStarted = false

    /** Catalog name resolved at init; falls back to [MODEL_NAME]. */
    private var hubModelName: String = MODEL_NAME

    @Volatile
    private var vlm: VlmWrapper? = null
    private var appContext: Context? = null
    private var downloadJob: Job? = null
    private var sdkInitialised = false
    private var loadFailures = 0

    // ---------------------------------------------------------------- init

    /**
     * Brings the SDK up and reports whether the chipset bundle is already
     * cached. Safe to call on every app start; the SDK's own init is idempotent
     * but the disk check is not free, so it runs off the main thread.
     */
    fun initialize(context: Context) {
        if (status != Status.COLD && status != Status.ERROR) return
        appContext = context.applicationContext
        status = Status.INITIALIZING
        startResidencyWatchdog()
        scope.launch {
            try {
                if (!sdkInitialised) {
                    initSdk(context.applicationContext)
                    sdkInitialised = true
                }
                probeCatalog()
                Log.i(TAG, "GenieX up, chipset=$chipset, hub model=$hubModelName")
                val cached = isBundleCached()
                if (cached) {
                    // The bundle is already on disk: learn its real context
                    // window now so every budget below runs against it.
                    refreshBundleContext(runCatching { ModelManagerWrapper.getPaths(hubModelName)?.model_dir }.getOrNull())
                }
                status = if (cached) Status.DOWNLOADED else Status.NOT_DOWNLOADED
            } catch (e: Exception) {
                fail("GenieX init failed: ${e.message}")
            }
        }
    }

    private suspend fun initSdk(context: Context) = suspendCoroutine { cont ->
        GenieXSdk.getInstance().init(
            context,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    cont.resume(Unit)
                }

                override fun onFailure(reason: String) {
                    cont.resume(Unit).also { Log.e(TAG, "GenieX init reported: $reason") }
                }
            },
        )
    }

    /** `getPaths` returns null while a pull is still staged in `.inflight/`. */
    private suspend fun isBundleCached(): Boolean =
        runCatching { ModelManagerWrapper.getPaths(hubModelName) != null }.getOrDefault(false)

    /**
     * Resolves the catalog entry and the chipset key for this handset.
     *
     * Three spellings are in play: `detectChipset` answers with a device name
     * ("Snapdragon 8 Elite Gen 5 QRD"), `Build.SOC_MODEL` with an SoC id
     * ("SM8850"), and the release assets are keyed by slug
     * ("qualcomm-snapdragon-8-elite-gen5"). The SDK's chipset table carries all
     * three for each device, so it is the bridge between them.
     *
     * Matching is exact, never substring: `sm8850` is a prefix of the Galaxy
     * S26's `sm8850-ad`, and a loose match silently picks the for-Galaxy bin,
     * which this model does not publish assets for.
     */
    private suspend fun probeCatalog() {
        val detected = runCatching { ModelManagerWrapper.detectChipset() }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val soc = runCatching { Build.SOC_MODEL }.getOrNull()?.takeIf { it.isNotBlank() }
        val table = runCatching { ModelManagerWrapper.listChipsets() }.getOrDefault(emptyList())
        Log.i(TAG, "chipset probe: detected=$detected soc=$soc entries=${table.size}")

        val keys = listOfNotNull(detected, soc).map(::norm).toSet()
        val device = table.firstOrNull { info ->
            norm(info.name) in keys || info.aliases.any { norm(it) in keys }
        }

        val models = runCatching { ModelManagerWrapper.listHubModels() }.getOrDefault(emptyList())
        val target = norm(DISPLAY_NAME)
        val model = models.firstOrNull { norm(it.name.substringAfterLast('/')) == target }
        if (model == null) {
            Log.w(TAG, "'$DISPLAY_NAME' absent from the ${models.size}-model AI Hub catalog")
        } else {
            hubModelName = model.name
            Log.i(TAG, "catalog: ${model.name} type=${model.model_type} chipsets=${model.chipsets.joinToString()}")
        }

        // Prefer the alias this model actually ships assets under; a device the
        // SDK knows but the model does not cover should fail at the pull with a
        // real message rather than silently fetch a neighbouring chipset's bin.
        val supported = model?.chipsets?.map(::norm)?.toSet().orEmpty()
        chipset = device?.aliases?.firstOrNull { norm(it) in supported }
            ?: device?.aliases?.firstOrNull()
            ?: device?.name
            ?: soc
            ?: FALLBACK_CHIPSET

        if (device != null && supported.isNotEmpty() && device.aliases.none { norm(it) in supported }) {
            Log.w(TAG, "${device.name} is not in this model's supported chipsets")
        }
    }

    /** Device names, slugs and SoC ids compared on their letters and digits. */
    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    // ------------------------------------------------------------ download

    /**
     * Pulls the chipset-matched bundle. Cancelling the returned job leaves the
     * partial files on disk — calling this again resumes rather than restarts.
     */
    fun download(context: Context) {
        if (downloadJob?.isActive == true) return
        if (status == Status.DOWNLOADING) return
        val app = context.applicationContext
        appContext = app
        downloadPercent = 0
        downloadedBytes = 0L
        totalBytes = 0L
        lastError = null
        status = Status.DOWNLOADING

        // The pull outlives the screen going dark at a weighbridge.
        val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "veritransit:model_pull")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        downloadJob = scope.launch {
            try {
                if (isBundleCached()) {
                    status = Status.DOWNLOADED
                    return@launch
                }
                // AIHUB rather than AUTO: these are pre-compiled NPU bundles, and
                // AUTO has resolved the same name to a HuggingFace GGUF before,
                // which would land on the CPU.
                val input = ModelPullInput(
                    model_name = hubModelName,
                    precision = PRECISION,
                    hub = HubSource.AIHUB,
                    chipset = chipset ?: FALLBACK_CHIPSET,
                    display_name = DISPLAY_NAME,
                )
                Log.i(TAG, "pulling ${input.model_name} @ ${input.precision} for ${input.chipset}")
                ModelManagerWrapper.pullFlow(input).collect { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            val total = event.files.sumOf { it.total_bytes.coerceAtLeast(0L) }
                            val done = event.files.sumOf { it.downloaded_bytes }
                            totalBytes = total
                            downloadedBytes = done
                            downloadPercent = if (total > 0) ((done * 100) / total).toInt() else 0
                        }

                        is ModelManagerWrapper.PullEvent.Completed -> {
                            downloadPercent = 100
                            status = Status.DOWNLOADED
                        }

                        is ModelManagerWrapper.PullEvent.Error -> {
                            fail("Download failed (rc=${event.code}): ${event.message}")
                        }
                    }
                }
                // A flow that ends without Completed still leaves usable files
                // when the bundle was already whole on disk.
                if (status == Status.DOWNLOADING) {
                    status = if (isBundleCached()) Status.DOWNLOADED else Status.NOT_DOWNLOADED
                }
            } catch (e: Exception) {
                fail("Download failed: ${e.message}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        status = Status.NOT_DOWNLOADED
    }

    /** Frees the cached bundle. Refuses while the model is resident. */
    fun deleteBundle() {
        if (isReady) return
        scope.launch {
            runCatching { ModelManagerWrapper.remove(hubModelName) }
                .onFailure { Log.w(TAG, "remove failed", it) }
            downloadPercent = 0
            downloadedBytes = 0L
            // The window claim died with the bundle: back to the default
            // assumption, or the screen keeps citing a config that is gone.
            bundleContextTokens = BundleContext.DEFAULT_CONTEXT_TOKENS
            contextFromBundle = false
            status = Status.NOT_DOWNLOADED
        }
    }

    // ---------------------------------------------------------------- load

    /** Makes the model resident on the NPU. Takes ~10-30 s on first load. */
    fun load() {
        if (status == Status.LOADING || isReady) return
        status = Status.LOADING
        lastError = null
        scope.launch {
            try {
                lifecycleMutex.withLock {
                    // An unload may have completed while this call was being
                    // scheduled; trust the mutex-protected state, not the
                    // pre-lock check above.
                    if (vlm != null) {
                        status = Status.READY
                        return@withLock
                    }
                    val paths = ModelManagerWrapper.getPaths(hubModelName)
                    if (paths == null) {
                        status = Status.NOT_DOWNLOADED
                        lastError = "Model bundle not on disk — download it first."
                        return@withLock
                    }
                    // The on-disk bundle is the authority on the context window:
                    // re-read it on every load in case a re-pull changed it.
                    refreshBundleContext(paths.model_dir)
                    // The bundle's manifest names the runtime it was compiled for.
                    // Anything but qairt means we pulled a GGUF by mistake and would
                    // silently land on the CPU.
                    val runtimeId = paths.runtime_id.ifEmpty { RUNTIME_QAIRT }
                    if (runtimeId != RUNTIME_QAIRT) {
                        fail("Bundle is '$runtimeId', not an NPU (qairt) build.")
                        return@withLock
                    }
                    // QAIRT rejects non-zero n_ctx / n_gpu_layers: both are baked in
                    // at compile time in the AI Hub bundle and cannot be overridden.
                    val config = ModelConfig(nCtx = 0, nGpuLayers = 0, nThreads = 8)
                    val input = VlmCreateInput(
                        model_path = paths.model_path,
                        mmproj_path = paths.mmproj_path,
                        config = config,
                        runtime_id = runtimeId,
                        compute_unit = COMPUTE_UNIT,
                    )

                    // The bundle is four weight-shared context binaries. Creating
                    // them asks the DSP for a large block up front, and when
                    // something else on the phone is already holding cDSP memory the
                    // third one comes back QNN_COMMON_ERROR_RESOURCE_UNAVAILABLE
                    // (1007). That clears on its own once the other client lets go,
                    // so the load is retried before it is called a failure.
                    var created: VlmWrapper? = null
                    var lastLoadError: Throwable? = null
                    var attempt = 0
                    while (created == null && attempt < LOAD_ATTEMPTS) {
                        attempt++
                        val result = VlmWrapper.builder().vlmCreateInput(input).build()
                        created = result.getOrNull()
                        if (created == null) {
                            lastLoadError = result.exceptionOrNull()
                            Log.w(TAG, "load attempt $attempt/$LOAD_ATTEMPTS failed: ${lastLoadError?.message}")
                            if (attempt < LOAD_ATTEMPTS) delay(attempt * LOAD_RETRY_BACKOFF_MS)
                        }
                    }

                    if (created != null) {
                        if (hostBackgrounded) {
                            // The host went away while this create was in
                            // flight (onHostBackgrounded no-ops on LOADING).
                            // Roll the fresh session back instead of pinning
                            // it in a process the freezer is about to park;
                            // the next foregrounded use reloads on demand.
                            Log.i(TAG, "load completed while host backgrounded — releasing the fresh session")
                            runCatching { created.stopStream() }
                            runCatching { created.destroy() }
                            status = if (isBundleCached()) Status.DOWNLOADED else Status.NOT_DOWNLOADED
                        } else {
                            vlm = created
                            loadFailures = 0
                            lastUsedAt = SystemClock.elapsedRealtime()
                            status = Status.READY
                            Log.i(TAG, "Qwen3-VL-4B resident on $COMPUTE_UNIT (attempt $attempt)")
                        }
                    } else {
                        loadFailures++
                        // Nothing in this process can release another client's DSP
                        // allocation, and our own stranded contexts only go when the
                        // process does — so say what actually helps.
                        val hint = if (loadFailures > 1) {
                            " The NPU is busy. Close other AI apps, or restart this app."
                        } else {
                            ""
                        }
                        fail("Load failed: ${lastLoadError?.message}.$hint")
                    }
                }
            } catch (e: Exception) {
                fail("Load failed: ${e.message}")
            }
        }
    }

    /**
     * Recovers from [Status.ERROR]: re-probes, then carries on to whichever
     * step the engine had reached, so the officer is not made to work out
     * whether the failure was the download or the load.
     */
    fun retry(context: Context) {
        status = Status.ERROR
        lastError = null
        scope.launch {
            try {
                if (!sdkInitialised) {
                    initSdk(context.applicationContext)
                    sdkInitialised = true
                }
                probeCatalog()
                if (isBundleCached()) {
                    status = Status.DOWNLOADED
                    load()
                } else {
                    status = Status.NOT_DOWNLOADED
                    download(context)
                }
            } catch (e: Exception) {
                fail("Retry failed: ${e.message}")
            }
        }
    }

    fun unload() {
        scope.launch {
            inferenceLock.withLock {
                lifecycleMutex.withLock {
                    vlm?.let { w ->
                        runCatching { w.stopStream() }
                        runCatching { w.destroy() }
                    }
                    vlm = null
                    lastProfile = null
                    activity = null
                    status = if (isBundleCached()) Status.DOWNLOADED else Status.NOT_DOWNLOADED
                }
            }
        }
    }

    // ----------------------------------------------------------- inference

    /**
     * Runs one stateless turn: the session is reset first, so each call sees
     * only [systemPrompt], [userPrompt] and [imagePaths]. Keeping turns
     * independent matters on the bundle's fixed context window — an inspection
     * shift would otherwise overflow it after a handful of photos.
     *
     * If the session was released (idle/background unload) the cached bundle is
     * made resident again first — [awaitResident] blocks until it is ready.
     *
     * [onToken] fires on a background thread for each decoded token.
     */
    suspend fun run(
        systemPrompt: String,
        userPrompt: String,
        imagePaths: List<String> = emptyList(),
        maxTokens: Int = 640,
        temperature: Float = 0.2f,
        label: String? = null,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        awaitResident() ?: return Result.failure(IllegalStateException(notResidentReason()))
        return inferenceLock.withLock {
            val w = vlm ?: return@withLock Result.failure(IllegalStateException("Model was unloaded — try again"))
            status = Status.BUSY
            activity = label
            try {
                val userTurn = VlmChatMessage(
                    role = "user",
                    contents = buildList {
                        imagePaths.forEach { add(VlmContent("image", it)) }
                        add(VlmContent("text", userPrompt))
                    },
                )
                val turns = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(VlmChatMessage("system", listOf(VlmContent("text", systemPrompt))))
                    }
                    add(userTurn)
                }
                withContext(Dispatchers.IO) {
                    generate(w, turns, userTurn, maxTokens, temperature, onToken)
                }
            } finally {
                activity = null
                lastUsedAt = SystemClock.elapsedRealtime()
                if (status == Status.BUSY) status = Status.READY
            }
        }
    }

    /**
     * Multi-turn conversation. [turns] is the whole exchange including the new
     * user message; the caller owns the history and is responsible for keeping
     * it inside [effectiveContextTokens].
     *
     * [mediaTurn] is the single turn whose images are handed to the encoder —
     * normally the latest. The SDK tokenises media incrementally, so replaying
     * earlier turns' images desyncs the image markers against the bitmaps.
     *
     * Sliding-window attention stays armed: the caller trims history to the
     * window, but if measurement drift ever lets a longer prompt through, the
     * runtime evicts middle tokens past [SLIDING_N_KEEP] instead of failing
     * the officer's chat outright. The stateless inspection path ([run]) keeps
     * it off — its prompts are exactly budgeted and its OCR quality must not
     * depend on eviction behaviour.
     */
    suspend fun converse(
        turns: List<VlmChatMessage>,
        mediaTurn: VlmChatMessage,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit = {},
    ): Result<String> {
        awaitResident() ?: return Result.failure(IllegalStateException(notResidentReason()))
        return inferenceLock.withLock {
            val w = vlm ?: return@withLock Result.failure(IllegalStateException("Model was unloaded — try again"))
            status = Status.BUSY
            activity = "Thinking"
            try {
                withContext(Dispatchers.IO) {
                    generate(
                        w, turns, mediaTurn, maxTokens, temperature, onToken,
                        slidingWindow = true, slidingWindowNKeep = SLIDING_N_KEEP,
                    )
                }
            } finally {
                activity = null
                lastUsedAt = SystemClock.elapsedRealtime()
                if (status == Status.BUSY) status = Status.READY
            }
        }
    }

    /**
     * Returns the resident wrapper, bringing the cached bundle back onto the
     * NPU first if residency was released. Returns null — with [notResidentReason]
     * explaining — when residency cannot be reached (nothing on disk, engine
     * error, or a load that never finished).
     */
    private suspend fun awaitResident(): VlmWrapper? {
        vlm?.let { return it }
        when (status) {
            Status.DOWNLOADED -> {
                activity = "Waking the NPU"
                load()
            }
            Status.LOADING, Status.READY, Status.BUSY -> Unit
            else -> return null
        }
        val deadline = SystemClock.elapsedRealtime() + LOAD_WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            vlm?.let { return it }
            // A load that ended in failure, or a bundle that went away (delete,
            // cancelled download) will not become resident — stop waiting.
            if (status != Status.LOADING && status != Status.READY && status != Status.BUSY) break
            delay(250)
        }
        activity = null
        return null
    }

    private fun notResidentReason(): String = when (status) {
        Status.NOT_DOWNLOADED -> "Model bundle not on disk — install it from the On-device AI screen."
        Status.DOWNLOADING -> "Model bundle is still downloading."
        Status.ERROR -> lastError ?: "Engine error — retry from the On-device AI screen."
        else -> "Model is not resident yet — try again shortly."
    }

    private suspend fun generate(
        wrapper: VlmWrapper,
        turns: List<VlmChatMessage>,
        mediaTurn: VlmChatMessage,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit,
        slidingWindow: Boolean = false,
        slidingWindowNKeep: Int = 0,
    ): Result<String> {
        // The whole exchange is re-sent every call, so the session is cleared
        // first; otherwise the runtime would prepend its own copy of it.
        runCatching { wrapper.reset() }

        val templated = wrapper.applyChatTemplate(turns.toTypedArray(), null, false)
            .getOrElse { return Result.failure(it) }

        val base = GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(temperature = temperature, topP = 0.9f, topK = 20),
            slidingWindow = slidingWindow,
            slidingWindowNKeep = slidingWindowNKeep,
        )
        val config = wrapper.injectMediaPathsToConfig(arrayOf(mediaTurn), base)

        val sb = StringBuilder()
        var failure: Throwable? = null
        wrapper.generateStreamFlow(templated.formattedText, config).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> {
                    sb.append(result.text)
                    onToken(result.text)
                }

                is LlmStreamResult.Completed -> {
                    lastProfile = result.profile
                    Log.d(TAG, "done: ${result.profile.decodingSpeed} tok/s decode")
                }

                is LlmStreamResult.Error -> failure = result.throwable
            }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(sb.toString())
    }

    /**
     * Reads the downloaded bundle's own `genie_config.json` and adopts its
     * declared context window. When the dir is unknown or the config is
     * unreadable — no bundle, a corrupt pull, a re-pull in flight — the state
     * reverts to the default *assumption* with [contextFromBundle] down: the
     * claim must always describe the bundle actually on disk, and a previous
     * bundle's window no longer backs it once that bundle is gone.
     */
    fun refreshBundleContext(modelDir: String?) {
        val config = modelDir?.takeIf { it.isNotBlank() }
            ?.let { File(it, BundleContext.GENIE_CONFIG_NAME) }
            ?.takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        val declared = config?.let { BundleContext.parseContextSizeOrNull(it) }
        val (tokens, fromBundle) = BundleContext.applyDeclaration(declared)
        if (tokens != bundleContextTokens || fromBundle != contextFromBundle) {
            Log.i(TAG, "bundle context window: $tokens tokens (${if (fromBundle) "bundle config" else "default assumption"})")
        }
        bundleContextTokens = tokens
        contextFromBundle = fromBundle
    }

    /** Aborts the in-flight generation; the coroutine unwinds via Completed. */
    fun stop() {
        scope.launch { runCatching { vlm?.stopStream() } }
    }

    // ------------------------------------------------- residency lifecycle

    /**
     * Called by the host activity when it stops being visible — app switch,
     * screen off, home. Releases the session immediately rather than after a
     * grace period: this ROM's fast_freezer parks a backgrounded process
     * within ~10 s of losing the screen, so anything longer never runs while
     * the user is actually away — it would fire on their return instead. The
     * release queues behind any in-flight inference and the next use reloads
     * the cached bundle on demand.
     */
    fun onHostBackgrounded() {
        hostBackgrounded = true
        if (isReady) {
            Log.i(TAG, "host backgrounded — releasing the NPU session")
            unload()
        }
        // A LOADING session cannot be cancelled mid-create; the commit check
        // in load() rolls it back against this flag when it finishes.
    }

    /** Called by the host activity when it becomes visible again. */
    fun onHostForegrounded() {
        hostBackgrounded = false
    }

    /**
     * Releases the session after [IDLE_UNLOAD_MS] without an inference, in the
     * foreground as well — residency nobody is using is cDSP memory some other
     * client (or our own next load) cannot get.
     */
    private fun startResidencyWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                if (!isReady) continue
                // Backgrounded residency is released on the first tick after
                // it is observed — a safety net for any commit that raced the
                // flag, so the window from issue #18 stays closed even if a
                // future change reorders the load commit.
                val idleMs = SystemClock.elapsedRealtime() - lastUsedAt
                if (hostBackgrounded || idleMs >= IDLE_UNLOAD_MS) {
                    Log.i(TAG, "releasing the NPU session (backgrounded=$hostBackgrounded, idle ${idleMs / 1000} s)")
                    unload()
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun fail(message: String) {
        Log.e(TAG, message)
        lastError = message
        status = Status.ERROR
    }

    /** Generous ceiling for a 4 GB pull on a slow field connection. */
    private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

    /** Attempts before a load is reported as failed. */
    private const val LOAD_ATTEMPTS = 3

    /** Multiplied by the attempt number, so gaps grow: 2 s, then 4 s. */
    private const val LOAD_RETRY_BACKOFF_MS = 2_000L

    /** Idle time after which a resident session is released, freeing cDSP memory. */
    private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L

    /** How long an inference caller waits for an on-demand reload to finish. */
    private const val LOAD_WAIT_TIMEOUT_MS = 90 * 1000L

    /** Watchdog polling period. */
    private const val WATCHDOG_TICK_MS = 15_000L

    /**
     * Prompt prefix the sliding window always retains on the chat path: the
     * system prompt plus the opening exchange. Covers ~70 tokens of system
     * instruction with room to spare; everything past it is recency-kept.
     */
    private const val SLIDING_N_KEEP = 256
}
