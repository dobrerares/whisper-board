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
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelRepository
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
    private lateinit var engineRouter: EngineRouter
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

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        engineRouter = EngineRouter(apiSettingsRepository, connectivityManager)

        viewModel = KeyboardViewModel(audioPipeline, languageRepository)
        viewModel.setEngineRouter(engineRouter)

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

        // Watch API settings → rebuild API engine
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
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure API engine", e)
                        engineRouter.apiEngine = null
                    }
                }
        }
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
            setViewTreeLifecycleOwner(ime)
            setViewTreeViewModelStoreOwner(ime)
            setViewTreeSavedStateRegistryOwner(ime)
            super.onAttachedToWindow()
        }
    }
}
