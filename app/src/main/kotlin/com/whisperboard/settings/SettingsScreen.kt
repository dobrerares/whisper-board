package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import com.whisperboard.model.DownloadProgress
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelInfo
import com.whisperboard.model.ModelManifest
import com.whisperboard.model.ModelRepository
import com.whisperboard.model.WhisperLanguages
import com.whisperboard.transcription.ApiProvider
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.transcription.EngineStrategy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelRepository: ModelRepository,
    languageRepository: LanguageRepository,
    apiSettingsRepository: ApiSettingsRepository,
    imeEnabled: Boolean = true,
    imeSelected: Boolean = true,
    onOpenImeSettings: () -> Unit = {},
    onOpenImePicker: () -> Unit = {},
    onPickFile: () -> Unit = {},
    pendingFileName: String? = null,
    pendingUri: android.net.Uri? = null,
    onImportComplete: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val downloadedModels by modelRepository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by modelRepository.activeModelName.collectAsState(initial = null)
    val downloadingModel by modelRepository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by modelRepository.downloadProgress.collectAsState(initial = null)
    val favoriteLanguages by languageRepository.favoriteLanguages.collectAsState(initial = emptySet())
    val allModels by modelRepository.allModels.collectAsState(initial = ModelManifest.models)
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Whisper Board",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }

            // --- Setup banner ---
            if (!imeEnabled || !imeSelected) {
                item {
                    SetupBanner(
                        imeEnabled = imeEnabled,
                        imeSelected = imeSelected,
                        onOpenImeSettings = onOpenImeSettings,
                        onOpenImePicker = onOpenImePicker,
                    )
                }
            }

            // --- Models section ---
            item {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(allModels, key = { it.name }) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = model.name in downloadedModels,
                    isActive = model.name == activeModelName,
                    isDownloading = model.name == downloadingModel,
                    progress = if (model.name == downloadingModel) downloadProgress else null,
                    isCustom = model.isCustom,
                    languageHint = model.languageHint,
                    onDownload = {
                        scope.launch {
                            val result = modelRepository.download(model)
                            result.onFailure { e ->
                                snackbarHostState.showSnackbar(
                                    "Download failed: ${e.message ?: "Unknown error"}"
                                )
                            }
                        }
                    },
                    onDelete = {
                        if (model.name == activeModelName) {
                            modelToDelete = model
                        } else {
                            scope.launch { modelRepository.delete(model) }
                        }
                    },
                    onSelect = {
                        scope.launch { modelRepository.setActiveModel(model.name) }
                    },
                    onCancel = {
                        modelRepository.cancelDownload()
                    },
                )
            }

            item {
                OutlinedCard(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+ Import Model",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // --- Transcription section ---
            item {
                Text(
                    text = "Transcription",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                TranscriptionSettingsSection(apiSettingsRepository = apiSettingsRepository)
            }

            // --- Languages section ---
            item {
                Text(
                    text = "Languages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = "Star languages to pin them in the keyboard picker and Android IME switcher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(
                WhisperLanguages.codes.entries.toList(),
                key = { "lang-${it.key}" }
            ) { (code, name) ->
                LanguageSettingsRow(
                    code = code,
                    displayName = name,
                    isFavorite = code in favoriteLanguages,
                    onToggleFavorite = {
                        scope.launch {
                            if (code in favoriteLanguages) {
                                languageRepository.removeFavorite(code)
                            } else {
                                languageRepository.addFavorite(code)
                            }
                        }
                    },
                )
            }
        }
    }

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete Active Model?") },
            text = { Text("\"${model.displayName}\" is currently in use. The keyboard will stop working until you select another model.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { modelRepository.delete(model) }
                    modelToDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showImportDialog) {
        ImportModelDialog(
            onDismiss = {
                showImportDialog = false
                onImportComplete()
            },
            onImportFile = { displayName, languageHint ->
                showImportDialog = false
                val uri = pendingUri ?: return@ImportModelDialog
                scope.launch {
                    val result = modelRepository.importFromFile(uri, displayName, languageHint)
                    onImportComplete()
                    result.onFailure { e ->
                        snackbarHostState.showSnackbar(
                            "Import failed: ${e.message ?: "Unknown error"}"
                        )
                    }
                    result.onSuccess {
                        snackbarHostState.showSnackbar("Model imported successfully")
                    }
                }
            },
            onImportUrl = { url, displayName, languageHint ->
                showImportDialog = false
                scope.launch {
                    val result = modelRepository.importFromUrl(url, displayName, languageHint)
                    result.onFailure { e ->
                        snackbarHostState.showSnackbar(
                            "Import failed: ${e.message ?: "Unknown error"}"
                        )
                    }
                    result.onSuccess {
                        snackbarHostState.showSnackbar("Model imported successfully")
                    }
                }
            },
            selectedFileName = pendingFileName,
            onBrowseFile = onPickFile,
        )
    }
}

@Composable
private fun LanguageSettingsRow(
    code: String,
    displayName: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = code.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                painter = painterResource(
                    id = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                ),
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SetupBanner(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Keyboard Setup",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Step 1: Enable
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (imeEnabled) "1. Enabled" else "1. Enable Whisper Board",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                if (!imeEnabled) {
                    Button(onClick = onOpenImeSettings) {
                        Text("Open Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Step 2: Select
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (imeSelected) "2. Selected" else "2. Select as active keyboard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                if (imeEnabled && !imeSelected) {
                    Button(onClick = onOpenImePicker) {
                        Text("Switch Keyboard")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionSettingsSection(
    apiSettingsRepository: ApiSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val strategy by apiSettingsRepository.engineStrategy.collectAsState(initial = EngineStrategy.LOCAL_ONLY)
    val provider by apiSettingsRepository.provider.collectAsState(initial = ApiProvider.OPENAI)
    val customUrl by apiSettingsRepository.baseUrl.collectAsState(initial = "")
    val customModel by apiSettingsRepository.model.collectAsState(initial = "")
    val fallbackTimeout by apiSettingsRepository.fallbackTimeoutSeconds.collectAsState(initial = 15)
    var apiKey by remember { mutableStateOf(apiSettingsRepository.getApiKey()) }

    Column {
        // Strategy selector
        Text("Engine Strategy", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EngineStrategy.entries.forEachIndexed { index, s ->
                SegmentedButton(
                    selected = strategy == s,
                    onClick = { scope.launch { apiSettingsRepository.setEngineStrategy(s) } },
                    shape = SegmentedButtonDefaults.itemShape(index, EngineStrategy.entries.size),
                ) {
                    Text(
                        text = when (s) {
                            EngineStrategy.LOCAL_ONLY -> "Local"
                            EngineStrategy.API_ONLY -> "API"
                            EngineStrategy.LOCAL_PREFERRED -> "Local+API"
                            EngineStrategy.API_WHEN_ONLINE -> "Auto"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // Strategy description
        Text(
            text = when (strategy) {
                EngineStrategy.LOCAL_ONLY -> "Always use on-device model"
                EngineStrategy.API_ONLY -> "Always use cloud API"
                EngineStrategy.LOCAL_PREFERRED -> "Try local first, fall back to API on timeout"
                EngineStrategy.API_WHEN_ONLINE -> "Use API when online, local when offline"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        // API settings (hidden when LOCAL_ONLY)
        if (strategy != EngineStrategy.LOCAL_ONLY) {
            // Provider picker
            var providerExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it },
            ) {
                OutlinedTextField(
                    value = provider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                ) {
                    ApiProvider.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.displayName) },
                            onClick = {
                                scope.launch { apiSettingsRepository.setProvider(p) }
                                providerExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // API Key (shown for providers that need auth)
            if (provider != ApiProvider.SELF_HOSTED) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        apiSettingsRepository.setApiKey(it)
                    },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Custom URL (shown for SELF_HOSTED and CUSTOM)
            if (provider.isCustomUrl) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { scope.launch { apiSettingsRepository.setBaseUrl(it) } },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-server.com/v1/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Custom model name (shown for CUSTOM only)
            if (provider == ApiProvider.CUSTOM) {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { scope.launch { apiSettingsRepository.setModel(it) } },
                    label = { Text("Model Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Fallback timeout (shown for LOCAL_PREFERRED only)
            if (strategy == EngineStrategy.LOCAL_PREFERRED) {
                Text(
                    text = "Fallback timeout: ${fallbackTimeout}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fallbackTimeout.toFloat(),
                    onValueChange = {
                        scope.launch { apiSettingsRepository.setFallbackTimeout(it.toInt()) }
                    },
                    valueRange = 5f..30f,
                    steps = 24,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    isCustom: Boolean = false,
    languageHint: String? = null,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (isCustom) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = "Custom",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (isActive) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (languageHint != null) {
                        Text(
                            text = WhisperLanguages.displayName(languageHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when {
                    isDownloading -> {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    isDownloaded && !isActive -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onSelect) { Text("Use") }
                            TextButton(onClick = onDelete) { Text("Delete") }
                        }
                    }
                    isDownloaded && isActive -> {
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    else -> {
                        TextButton(onClick = onDownload) { Text("Download") }
                    }
                }
            }

            if (isDownloading && progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${progress.bytesDownloaded / 1_000_000} / ${progress.totalBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
