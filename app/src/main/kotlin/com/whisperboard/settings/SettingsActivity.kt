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
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelRepository
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.ui.theme.WhisperBoardTheme

class SettingsActivity : ComponentActivity() {

    private lateinit var repository: ModelRepository

    private val imeEnabled = mutableStateOf(false)
    private val imeSelected = mutableStateOf(false)
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
        val languageRepository = LanguageRepository(applicationContext)
        val apiSettingsRepository = ApiSettingsRepository(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        enableEdgeToEdge()

        setContent {
            WhisperBoardTheme {
                SettingsScreen(
                    modelRepository = repository,
                    languageRepository = languageRepository,
                    apiSettingsRepository = apiSettingsRepository,
                    imeEnabled = imeEnabled.value,
                    imeSelected = imeSelected.value,
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

    override fun onResume() {
        super.onResume()
        refreshImeStatus()
    }

    private fun refreshImeStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val myId = "$packageName/.WhisperBoardIME"
        imeEnabled.value = imm.enabledInputMethodList.any { it.id == myId }
        imeSelected.value = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        ) == myId
    }
}
