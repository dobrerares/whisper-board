package com.whisperboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.whisperboard.bubble.BubbleOverlayService
import com.whisperboard.bubble.BubbleSettingsRepository
import com.whisperboard.model.BehaviorSettingsRepository
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.LlmModelRepository
import com.whisperboard.model.ModelRepository
import com.whisperboard.onboarding.FirstLaunchPrompt
import com.whisperboard.postprocessing.PostProcessingSettingsRepository
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.ui.theme.WhisperBoardTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var repository: ModelRepository
    private lateinit var llmModelRepository: LlmModelRepository
    private lateinit var languageRepository: LanguageRepository

    private val imeEnabled = mutableStateOf(false)
    private val imeSelected = mutableStateOf(false)
    private val overlayPermissionGranted = mutableStateOf(false)
    private val pendingFileName = mutableStateOf<String?>(null)
    private val pendingUri = mutableStateOf<android.net.Uri?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly — permission is now granted or denied */ }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingUri.value = uri
            val cursor = contentResolver.query(uri, null, null, null, null)
            val name = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else null
                } else null
            }
            pendingFileName.value = name ?: uri.lastPathSegment ?: "unknown.bin"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ModelRepository(applicationContext)
        llmModelRepository = LlmModelRepository(applicationContext)
        languageRepository = LanguageRepository(applicationContext)
        val apiSettingsRepository = ApiSettingsRepository(applicationContext)
        val postProcessingSettingsRepository = PostProcessingSettingsRepository(applicationContext)
        val behaviorSettingsRepository = BehaviorSettingsRepository(applicationContext)
        val bubbleSettingsRepository = BubbleSettingsRepository(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        enableEdgeToEdge()

        setContent {
            WhisperBoardTheme {
                // Gate: on first launch (or until completion is recorded),
                // surface the language-profile prompt over the settings tree.
                // We default the gate to `true` — show settings — until the
                // flow's first emission arrives, so we don't briefly flash an
                // empty state.
                val onboardingComplete by languageRepository.onboardingComplete
                    .collectAsState(initial = true)

                if (!onboardingComplete) {
                    val initial by languageRepository.spokenLanguages
                        .collectAsState(initial = LanguageRepository.DEFAULT_SPOKEN_LANGUAGES)
                    FirstLaunchPrompt(
                        initialSelection = initial,
                        onSkip = {
                            // "Skip" preserves the default profile but flips
                            // the completion flag so the prompt does not
                            // re-appear. The default is `[auto]`, which the
                            // post-processor reads as "language not declared".
                            lifecycleScope.launch {
                                languageRepository.setSpokenLanguages(
                                    LanguageRepository.DEFAULT_SPOKEN_LANGUAGES,
                                )
                                languageRepository.setOnboardingComplete(true)
                            }
                        },
                        onSave = { picked ->
                            lifecycleScope.launch {
                                languageRepository.setSpokenLanguages(picked)
                                languageRepository.setOnboardingComplete(true)
                            }
                        },
                    )
                } else {
                    SettingsScreen(
                        modelRepository = repository,
                        llmModelRepository = llmModelRepository,
                        languageRepository = languageRepository,
                        apiSettingsRepository = apiSettingsRepository,
                        postProcessingSettingsRepository = postProcessingSettingsRepository,
                        behaviorSettingsRepository = behaviorSettingsRepository,
                        bubbleSettingsRepository = bubbleSettingsRepository,
                        imeEnabled = imeEnabled.value,
                        imeSelected = imeSelected.value,
                        overlayPermissionGranted = overlayPermissionGranted.value,
                        onOpenImeSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onOpenImePicker = {
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showInputMethodPicker()
                        },
                        onPickFile = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        onRequestOverlayPermission = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                .setData(android.net.Uri.parse("package:$packageName"))
                            startActivity(intent)
                        },
                        onStartBubbleService = {
                            BubbleOverlayService.start(applicationContext)
                        },
                        onStopBubbleService = {
                            BubbleOverlayService.stop(applicationContext)
                        },
                        pendingFileName = pendingFileName.value,
                        pendingUri = pendingUri.value,
                        onImportComplete = {
                            pendingUri.value = null
                            pendingFileName.value = null
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshImeStatus()
        refreshOverlayStatus()
    }

    private fun refreshImeStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val myId = "$packageName/.WhisperBoardIME"
        imeEnabled.value = imm.enabledInputMethodList.any { it.id == myId }
        imeSelected.value = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        ) == myId
    }

    private fun refreshOverlayStatus() {
        overlayPermissionGranted.value = BubbleOverlayService.hasOverlayPermission(this)
    }
}
