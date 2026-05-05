package com.whisperboard.bubble

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.whisperboard.R
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.BehaviorSettingsRepository
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelRepository
import com.whisperboard.postprocessing.ApiPostProcessor
import com.whisperboard.postprocessing.PostProcessingContext
import com.whisperboard.postprocessing.PostProcessingOutcome
import com.whisperboard.postprocessing.PostProcessingRouter
import com.whisperboard.postprocessing.PostProcessingSettingsRepository
import com.whisperboard.transcription.ApiEngine
import com.whisperboard.transcription.ApiProvider
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.transcription.EngineRouter
import com.whisperboard.transcription.LocalEngine
import com.whisperboard.ui.theme.WhisperBoardTheme
import com.whisperboard.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts the floating bubble overlay window.
 *
 * Lifecycle:
 * - Started by the Quick Settings tile, the in-app "show bubble" action, or
 *   the launcher when [BubbleVisibilityMode.AlwaysVisible] is on at boot.
 * - Stopped only when the user disables the bubble surface entirely.
 *
 * Component split:
 * - [BubbleStateMachine] holds the gesture/lifecycle logic. Pure.
 * - [BubbleVisibilityRules] decides whether the overlay window should be
 *   attached at any given moment. Pure.
 * - [BubbleClipboardDelivery] decides whether to auto-copy. Pure.
 * - [BubbleSettingsRepository] persists visibility mode + position.
 *
 * This service is the integration point: it owns the [WindowManager] view,
 * the [AudioPipeline], the [EngineRouter], and the foreground notification.
 *
 * Standalone mode only — accessibility / in-place insertion lands in slice
 * #3 (issue #7). Per ADR-0001 the bubble does not subclass or wrap the IME;
 * it is an independent surface that feeds the same `EngineRouter`.
 */
class BubbleOverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "BubbleOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "whisper_board_bubble"
        private const val NOTIFICATION_CHANNEL_NAME = "Whisper Board bubble"
        private const val NOTIFICATION_ID = 0xB1B
        private const val DEFAULT_X = 16
        private const val DEFAULT_Y = 320
        private const val EDGE_DRAG_THRESHOLD_PX = 8

        const val ACTION_START = "com.whisperboard.bubble.START"
        const val ACTION_STOP = "com.whisperboard.bubble.STOP"
        const val ACTION_SUMMON = "com.whisperboard.bubble.SUMMON"

        /**
         * Returns true iff the user has granted the `SYSTEM_ALERT_WINDOW`
         * permission. The settings UI shows an onboarding card driving the
         * user to grant it before they can start the service.
         */
        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        /** Launch the overlay service. No-op if the permission is missing. */
        fun start(context: Context) {
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "Overlay permission missing — refusing to start")
                return
            }
            val intent = Intent(context, BubbleOverlayService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        /** Stop the overlay service. */
        fun stop(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        /**
         * Ensure the bubble is visible, summoning it if the user has the
         * surface configured to [BubbleVisibilityMode.SummonedOnly]. Used by
         * the Quick Settings tile.
         */
        fun summon(context: Context) {
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "Overlay permission missing — refusing to summon")
                return
            }
            val intent = Intent(context, BubbleOverlayService::class.java)
                .setAction(ACTION_SUMMON)
            context.startForegroundService(intent)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInternal = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInternal
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var languageRepository: LanguageRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var apiSettingsRepository: ApiSettingsRepository
    private lateinit var postProcessingSettings: PostProcessingSettingsRepository
    private lateinit var behaviorSettings: BehaviorSettingsRepository
    private lateinit var bubbleSettings: BubbleSettingsRepository
    private lateinit var engineRouter: EngineRouter
    private lateinit var postProcessingRouter: PostProcessingRouter
    private lateinit var stateMachine: BubbleStateMachine
    private lateinit var clipboardDelivery: BubbleClipboardDelivery
    private lateinit var visibilityController: BubbleVisibilityController

    private var bubbleView: AbstractComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var keyguardReceiver: BroadcastReceiver? = null

    private var transcriptionJob: Job? = null
    private var isAttached = false
    private var isFullscreenAppForeground = false
    private var dragOffEdge = false

    /** Renderable state pushed into the composition. */
    private val composeBubbleState = androidx.compose.runtime.mutableStateOf<BubbleState>(BubbleState.Idle)
    private val composeWaveformAmplitude = mutableFloatStateOf(0f)

    private val apiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioPipeline = AudioPipeline(this)
        languageRepository = LanguageRepository(applicationContext)
        modelRepository = ModelRepository(applicationContext)
        apiSettingsRepository = ApiSettingsRepository(applicationContext)
        postProcessingSettings = PostProcessingSettingsRepository(applicationContext)
        behaviorSettings = BehaviorSettingsRepository(applicationContext)
        bubbleSettings = BubbleSettingsRepository(applicationContext)

        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        engineRouter = EngineRouter(apiSettingsRepository, connectivityManager)
        postProcessingRouter = PostProcessingRouter(
            polishModeProvider = { postProcessingSettings.polishModeEnabled.first() },
            strategyProvider = { postProcessingSettings.strategy.first() },
        )

        stateMachine = BubbleStateMachine()
        clipboardDelivery = BubbleClipboardDelivery(
            autoCopyEnabledProvider = { behaviorSettings.autoCopyEnabled.first() },
        )
        visibilityController = BubbleVisibilityController(
            getSystemService(KEYGUARD_SERVICE) as KeyguardManager,
        )

        registerKeyguardReceiver()
        wireEngines()
        observeAutoCopyEvents()
        observeWaveform()
        observeAutoCopyToggle()
        observeVisibility()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SUMMON -> {
                visibilityController.markSummoned(true)
                refreshOverlayVisibility()
            }
            else -> {
                // ACTION_START or null — handled by the visibility flow below.
            }
        }

        // Honour the persisted visibility mode for the initial attach
        // decision; the flow-based subscriber keeps it in sync afterwards.
        refreshOverlayVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        detachOverlay()
        keyguardReceiver?.let { runCatching { unregisterReceiver(it) } }
        transcriptionJob?.cancel()
        serviceScope.cancel()
        engineRouter.close()
        postProcessingRouter.close()
        audioPipeline.release()
        viewModelStoreInternal.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // --- Foreground notification ---

    private fun startForegroundIfNeeded() {
        ensureNotificationChannel()
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Whisper Board")
            .setContentText("Bubble is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            // IMPORTANCE_LOW: the bubble notification should be quiet —
            // visible in the shade so the user can find their way back, but
            // not buzzing or peeking.
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Floating dictation bubble"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    // --- Overlay attach/detach ---

    private fun attachOverlay() {
        if (isAttached) return
        if (!hasOverlayPermission(this)) {
            Log.w(TAG, "Overlay permission missing — cannot attach")
            return
        }

        // Attach with the default placement immediately, then update once
        // the persisted position emits. We avoid runBlocking on the main
        // thread here so the service start path stays responsive.
        layoutParams = buildOverlayParams(DEFAULT_X, DEFAULT_Y)

        val view = ComposeBubbleView(this) {
            BubbleView(
                state = composeBubbleState.value,
                waveformAmplitude = composeWaveformAmplitude.floatValue,
                onTap = { handleEvent(BubbleEvent.Tap) },
                onLongPressStart = { handleEvent(BubbleEvent.LongPressStart) },
                onLongPressEnd = { handleEvent(BubbleEvent.LongPressEnd) },
                onDrag = ::onDragDelta,
                onDragEnd = ::onDragEnd,
                onDismiss = { handleEvent(BubbleEvent.Dismiss) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        bubbleView = view

        try {
            windowManager.addView(view, layoutParams)
            isAttached = true
            // Restore the persisted position once it's loaded — happens off
            // the main thread, then jumps back here to update the layout.
            serviceScope.launch {
                val pos = bubbleSettings.position.first()
                if (!pos.isUnset && layoutParams != null && bubbleView == view) {
                    layoutParams?.x = pos.x
                    layoutParams?.y = pos.y
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay view", e)
            bubbleView = null
        }
    }

    private fun detachOverlay() {
        val view = bubbleView ?: return
        runCatching { windowManager.removeView(view) }
        bubbleView = null
        layoutParams = null
        isAttached = false
    }

    private fun buildOverlayParams(x: Int, y: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    // --- Drag handling ---

    private fun onDragDelta(dx: Float, dy: Float) {
        val params = layoutParams ?: return
        val view = bubbleView ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun onDragEnd() {
        val params = layoutParams ?: return
        // Persist position so the bubble re-appears where the user left it.
        serviceScope.launch {
            bubbleSettings.setPosition(params.x, params.y)
        }
        // Heuristic for "dragged off-edge": the user pushed the bubble
        // far enough negative that it is no longer visible. We
        // intentionally use a generous threshold so the user has to be
        // deliberate about hiding the bubble this way.
        val display = windowManager.defaultDisplay
        val width = display.width
        val height = display.height
        val draggedOff = params.x < -BUBBLE_HIDDEN_OVERHANG ||
            params.x > width - EDGE_DRAG_THRESHOLD_PX ||
            params.y < -BUBBLE_HIDDEN_OVERHANG ||
            params.y > height - EDGE_DRAG_THRESHOLD_PX
        if (draggedOff != dragOffEdge) {
            dragOffEdge = draggedOff
            refreshOverlayVisibility()
        }
    }

    // --- State machine wiring ---

    private fun handleEvent(event: BubbleEvent) {
        // Pull the current auto-copy preference into the machine before
        // each transition so the AutoCopy effect is gated correctly. The
        // machine itself stays pure.
        serviceScope.launch {
            stateMachine.autoCopyEnabled = behaviorSettings.autoCopyEnabled.first()
            val transition = stateMachine.handle(event)
            applyTransition(transition)
        }
    }

    private suspend fun applyTransition(transition: BubbleTransition) {
        composeBubbleState.value = transition.state
        for (effect in transition.effects) {
            applyEffect(effect)
        }
    }

    private suspend fun applyEffect(effect: BubbleEffect) {
        when (effect) {
            BubbleEffect.StartRecording -> startTranscription()
            BubbleEffect.StopRecording -> stopTranscription()
            is BubbleEffect.AutoCopy -> writeToClipboard(effect.text)
            BubbleEffect.ShowCopiedToast -> showToast("Copied")
            is BubbleEffect.ShowError -> showToast(effect.reason)
        }
    }

    // --- Transcription pipeline ---

    private fun startTranscription() {
        if (!audioPipeline.hasRecordPermission()) {
            handleEvent(BubbleEvent.Error("Microphone permission required"))
            return
        }
        if (!engineRouter.canTranscribe()) {
            handleEvent(BubbleEvent.Error("No transcription engine available"))
            return
        }
        audioPipeline.startRecording(serviceScope)
    }

    private fun stopTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = serviceScope.launch {
            try {
                val samples = audioPipeline.stopRecording()
                if (samples.isEmpty()) {
                    handleEvent(BubbleEvent.TranscriptReady(""))
                    return@launch
                }
                val activeLanguage = languageRepository.activeLanguage.first()
                val raw = engineRouter.transcribe(samples, activeLanguage)
                val polished = polishIfEnabled(raw)
                clipboardDelivery.deliver(polished) // stages for the result view
                handleEvent(BubbleEvent.TranscriptReady(polished))
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                handleEvent(BubbleEvent.Error(e.message ?: "Transcription failed"))
            }
        }
    }

    private suspend fun polishIfEnabled(raw: String): String {
        // Slice 5 (issue #5) will populate the language profile from the
        // user's declared spoken languages. Until then we send an empty
        // profile, which the post-processor reads as "language not declared".
        val outcome = postProcessingRouter.polish(
            rawTranscript = raw,
            context = PostProcessingContext(),
        )
        return when (outcome) {
            is PostProcessingOutcome.Polished -> outcome.text
            is PostProcessingOutcome.Skipped -> outcome.text
            is PostProcessingOutcome.Fallback -> {
                Log.w(TAG, "Polish fell back to raw: ${outcome.reason}")
                outcome.text
            }
        }
    }

    private fun writeToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Whisper Board", text))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // --- Engine wiring (mirrors the IME) ---

    private fun wireEngines() {
        // Local engine — follow the active model.
        serviceScope.launch {
            modelRepository.activeModelName.collectLatest { modelName ->
                try {
                    engineRouter.localEngine?.close()
                    engineRouter.localEngine = null

                    if (modelName == null) return@collectLatest
                    val modelPath = modelRepository.getActiveModelPath() ?: return@collectLatest
                    val ctx = WhisperContext.createContext(modelPath)
                    engineRouter.localEngine = LocalEngine(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Whisper model", e)
                }
            }
        }

        // API engine + post-processor — follow API settings.
        serviceScope.launch {
            combine(
                apiSettingsRepository.provider,
                apiSettingsRepository.baseUrl,
                apiSettingsRepository.model,
            ) { provider, baseUrl, model -> Triple(provider, baseUrl, model) }
                .collectLatest {
                    try {
                        val config = apiSettingsRepository.resolveApiConfig()
                        engineRouter.apiEngine = if (config != null) {
                            ApiEngine(apiClient, config.baseUrl, config.apiKey, config.model)
                        } else null
                        postProcessingRouter.apiPostProcessor = if (config != null) {
                            ApiPostProcessor(
                                client = apiClient,
                                baseUrl = config.baseUrl,
                                apiKey = config.apiKey,
                                model = chatModelFor(apiSettingsRepository.provider.first()),
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure API engine", e)
                        engineRouter.apiEngine = null
                        postProcessingRouter.apiPostProcessor = null
                    }
                }
        }
    }

    private fun chatModelFor(provider: ApiProvider): String =
        when (provider) {
            ApiProvider.OPENAI -> "gpt-4o-mini"
            ApiProvider.GROQ -> "llama-3.1-8b-instant"
            ApiProvider.SELF_HOSTED, ApiProvider.CUSTOM -> "default"
        }

    // --- Reactive observers ---

    private fun observeAutoCopyEvents() {
        // BubbleClipboardDelivery emits to autoCopyRequests; the state
        // machine ALSO emits an AutoCopy effect. We treat them as
        // complementary — the delivery flow is the testable record of
        // what's been written; the effect path is what triggers the toast.
        // Avoid double-writes by only acting on one. Here we let the state
        // machine drive the clipboard write and rely on the delivery's
        // staging path for what the result view shows.
    }

    private fun observeAutoCopyToggle() {
        serviceScope.launch {
            behaviorSettings.autoCopyEnabled.collectLatest { enabled ->
                stateMachine.autoCopyEnabled = enabled
            }
        }
    }

    private fun observeWaveform() {
        serviceScope.launch {
            audioPipeline.waveformData.collectLatest { data ->
                composeWaveformAmplitude.floatValue =
                    if (data.isEmpty()) 0f else data.map { kotlin.math.abs(it) }.average()
                        .toFloat().coerceIn(0f, 1f)
            }
        }
    }

    private fun observeVisibility() {
        serviceScope.launch {
            bubbleSettings.visibilityMode.collectLatest { mode ->
                if (mode == BubbleVisibilityMode.Disabled) {
                    detachOverlay()
                    stopSelf()
                } else {
                    refreshOverlayVisibility()
                }
            }
        }
    }

    private fun refreshOverlayVisibility() {
        serviceScope.launch {
            val mode = bubbleSettings.visibilityMode.first()
            val inputs = BubbleVisibilityInputs(
                mode = mode,
                onLockscreen = visibilityController.isOnLockscreen(),
                foregroundAppIsFullscreen = isFullscreenAppForeground,
                draggedOffEdge = dragOffEdge,
                imeVisible = false, // intentionally never hides the bubble
                isSummoned = visibilityController.isSummoned,
            )
            val shouldShow = BubbleVisibilityRules.shouldBeVisible(inputs)
            if (shouldShow) attachOverlay() else detachOverlay()
        }
    }

    // --- Keyguard tracking ---

    private fun registerKeyguardReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        keyguardReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshOverlayVisibility()
            }
        }
        registerReceiver(keyguardReceiver, filter)
    }

    // --- Compose host ---

    private inner class ComposeBubbleView(
        context: Context,
        private val content: @Composable () -> Unit,
    ) : AbstractComposeView(context) {

        @Composable
        override fun Content() {
            WhisperBoardTheme { content() }
        }

        override fun onAttachedToWindow() {
            setViewTreeLifecycleOwner(this@BubbleOverlayService)
            setViewTreeViewModelStoreOwner(this@BubbleOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BubbleOverlayService)
            super.onAttachedToWindow()
        }
    }

}

private const val BUBBLE_HIDDEN_OVERHANG = 32

/**
 * Lightweight wrapper around [KeyguardManager] + a "summoned" flag so the
 * service can ask "should we show the bubble right now?" without leaking
 * state into [BubbleVisibilityRules]. The rule set stays pure; this class
 * is the source of the inputs.
 */
private class BubbleVisibilityController(
    private val keyguardManager: KeyguardManager,
) {
    @Volatile
    var isSummoned: Boolean = false
        private set

    fun markSummoned(value: Boolean) {
        isSummoned = value
    }

    fun isOnLockscreen(): Boolean = keyguardManager.isKeyguardLocked
}
