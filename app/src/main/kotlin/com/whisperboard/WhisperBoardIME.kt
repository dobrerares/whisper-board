package com.whisperboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
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
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.BehaviorSettingsRepository
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelRepository
import com.whisperboard.model.history.DictationHistoryRepository
import com.whisperboard.model.history.HistorySettingsRepository
import com.whisperboard.model.history.WhisperBoardDatabase
import com.whisperboard.postprocessing.ApiPostProcessor
import com.whisperboard.postprocessing.PostProcessingRouter
import com.whisperboard.postprocessing.PostProcessingSettingsRepository
import com.whisperboard.transcription.ApiEngine
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.transcription.EngineRouter
import com.whisperboard.transcription.LocalEngine
import com.whisperboard.ui.KeyboardScreen
import com.whisperboard.ui.KeyboardViewModel
import com.whisperboard.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WhisperBoardIME : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "WhisperBoardIME"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var audioPipeline: AudioPipeline
    private lateinit var languageRepository: LanguageRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var apiSettingsRepository: ApiSettingsRepository
    private lateinit var postProcessingSettings: PostProcessingSettingsRepository
    private lateinit var behaviorSettings: BehaviorSettingsRepository
    private lateinit var historySettings: HistorySettingsRepository
    private lateinit var historyRepository: DictationHistoryRepository
    private lateinit var engineRouter: EngineRouter
    private lateinit var postProcessingRouter: PostProcessingRouter
    private lateinit var viewModel: KeyboardViewModel

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        audioPipeline = AudioPipeline(this)
        languageRepository = LanguageRepository(applicationContext)
        modelRepository = ModelRepository(applicationContext)
        apiSettingsRepository = ApiSettingsRepository(applicationContext)
        postProcessingSettings = PostProcessingSettingsRepository(applicationContext)
        behaviorSettings = BehaviorSettingsRepository(applicationContext)
        historySettings = HistorySettingsRepository(applicationContext)
        historyRepository = DictationHistoryRepository(
            dao = WhisperBoardDatabase.getInstance(applicationContext).dictationHistoryDao(),
            retentionProvider = { historySettings.retention.first() },
        )

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        engineRouter = EngineRouter(apiSettingsRepository, connectivityManager)
        postProcessingRouter = PostProcessingRouter(
            polishModeProvider = { postProcessingSettings.polishModeEnabled.first() },
            strategyProvider = { postProcessingSettings.strategy.first() },
        )

        viewModel = KeyboardViewModel(
            audioPipeline = audioPipeline,
            languageRepository = languageRepository,
            autoInsertEnabledProvider = { behaviorSettings.autoInsertEnabled.first() },
            // Surface the focused field's owning package name so each
            // persisted entry can record where the words went. The IME has
            // `currentInputEditorInfo` available between onStartInput/onFinishInput.
            targetAppNameProvider = { currentInputEditorInfo?.packageName },
            historyRepository = historyRepository,
            historySettings = historySettings,
            behaviorSettings = behaviorSettings,
        )
        viewModel.setEngineRouter(engineRouter)
        viewModel.setPostProcessingRouter(postProcessingRouter)

        // Watch active model → update local engine
        serviceScope.launch {
            modelRepository.activeModelName.collectLatest { modelName ->
                try {
                    engineRouter.localEngine?.close()
                    engineRouter.localEngine = null

                    if (modelName == null) {
                        Log.w(TAG, "No active model selected — open Settings to download one")
                        return@collectLatest
                    }

                    val modelPath = modelRepository.getActiveModelPath()
                    if (modelPath == null) {
                        Log.w(TAG, "Active model $modelName not found on disk")
                        return@collectLatest
                    }

                    Log.d(TAG, "Loading Whisper model from $modelPath")
                    val ctx = WhisperContext.createContext(modelPath)
                    engineRouter.localEngine = LocalEngine(ctx)
                    Log.d(TAG, "Whisper model loaded: $modelName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Whisper model", e)
                }
            }
        }

        // Watch API settings + the privacy "send transcripts to LLM" toggle.
        // The toggle gates only the post-processor (the LLM polish stage);
        // transcription is allowed to keep using the API since that's the
        // user's chosen STT backend, not the polish surface.
        serviceScope.launch {
            combine(
                apiSettingsRepository.provider,
                apiSettingsRepository.baseUrl,
                apiSettingsRepository.model,
                historySettings.sendTranscriptsToRemoteLlm,
            ) { provider, baseUrl, model, sendToLlm ->
                ApiAndLlmConfig(provider, baseUrl, model, sendToLlm)
            }
                .collectLatest { state ->
                    try {
                        val config = apiSettingsRepository.resolveApiConfig()
                        engineRouter.apiEngine = if (config != null) {
                            ApiEngine(apiClient, config.baseUrl, config.apiKey, config.model)
                        } else {
                            null
                        }
                        // The post-processor reuses the same base URL + API key but
                        // talks to /chat/completions with a chat-capable model.
                        // Gate it on both the API config and the privacy
                        // toggle: when "send transcripts to remote LLM" is
                        // off, leave the post-processor null so the router
                        // falls back to the raw transcript.
                        postProcessingRouter.apiPostProcessor = if (config != null && state.sendToLlm) {
                            ApiPostProcessor(
                                client = apiClient,
                                baseUrl = config.baseUrl,
                                apiKey = config.apiKey,
                                model = chatModelFor(apiSettingsRepository.provider.first()),
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure API engine", e)
                        engineRouter.apiEngine = null
                        postProcessingRouter.apiPostProcessor = null
                    }
                }
        }
    }

    /**
     * Combine result for API config + the "send to LLM" toggle. A `combine`
     * with four sources doesn't have a built-in tuple, so a tiny named
     * record keeps the call site readable.
     */
    private data class ApiAndLlmConfig(
        val provider: com.whisperboard.transcription.ApiProvider,
        val baseUrl: String,
        val model: String,
        val sendToLlm: Boolean,
    )

    /**
     * Default chat-completions model for each provider. Used by the
     * post-processor; the transcription engine continues to use the Whisper
     * model from settings. Slice #6 will give users an explicit chat-model
     * picker; until then a sensible default keeps the polish stage usable
     * out of the box.
     */
    private fun chatModelFor(provider: com.whisperboard.transcription.ApiProvider): String =
        when (provider) {
            com.whisperboard.transcription.ApiProvider.OPENAI -> "gpt-4o-mini"
            com.whisperboard.transcription.ApiProvider.GROQ -> "llama-3.1-8b-instant"
            com.whisperboard.transcription.ApiProvider.SELF_HOSTED,
            com.whisperboard.transcription.ApiProvider.CUSTOM -> "default"
        }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (info != null) {
            viewModel.currentImeAction =
                info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val locale = newSubtype?.languageTag
            ?: newSubtype?.locale
            ?: ""
        val langCode = if (locale.isEmpty()) "auto" else locale.split("-").first().lowercase()
        Log.d(TAG, "IME subtype changed: $langCode")
        serviceScope.launch {
            languageRepository.setActiveLanguage(langCode)
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val ime = this
        return ComposeKeyboardView(this) {
            KeyboardScreen(
                viewModel = viewModel,
                inputConnection = { ime.currentInputConnection }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        viewModel.cleanup()
        engineRouter.close()
        audioPipeline.release()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private inner class ComposeKeyboardView(
        context: Context,
        private val content: @Composable () -> Unit
    ) : AbstractComposeView(context) {

        @Composable
        override fun Content() {
            content()
        }

        override fun onAttachedToWindow() {
            val ime = this@WhisperBoardIME

            // Set owners on the IME window's decor view so Compose can find them
            // when walking up the view tree from our ComposeView.
            val decorView = ime.window?.window?.decorView
            decorView?.let {
                it.setViewTreeLifecycleOwner(ime)
                it.setViewTreeViewModelStoreOwner(ime)
                it.setViewTreeSavedStateRegistryOwner(ime)
            }

            super.onAttachedToWindow()
        }
    }
}
