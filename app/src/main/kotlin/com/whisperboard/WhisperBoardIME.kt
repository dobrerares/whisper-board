package com.whisperboard

import android.content.Context
import android.inputmethodservice.InputMethodService
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
import com.whisperboard.ui.KeyboardScreen
import com.whisperboard.ui.KeyboardViewModel
import com.whisperboard.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

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
    private lateinit var viewModel: KeyboardViewModel

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        audioPipeline = AudioPipeline(this)
        viewModel = KeyboardViewModel(audioPipeline)

        // Load WhisperContext asynchronously
        val modelPath = File(filesDir, "models/ggml-tiny.bin").absolutePath
        val modelFile = File(modelPath)
        if (modelFile.exists()) {
            serviceScope.launch {
                try {
                    val ctx = WhisperContext.createContext(modelPath)
                    viewModel.setWhisperContext(ctx)
                    Log.i(TAG, "Whisper model loaded from $modelPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Whisper model from $modelPath", e)
                }
            }
        } else {
            Log.w(TAG, "Whisper model not found at $modelPath — mic transcription will be unavailable")
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
