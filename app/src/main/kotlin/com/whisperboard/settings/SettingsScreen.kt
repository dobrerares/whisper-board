package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import com.whisperboard.model.BehaviorSettingsRepository
import com.whisperboard.model.DownloadProgress
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelInfo
import com.whisperboard.model.ModelManifest
import com.whisperboard.model.ModelRepository
import com.whisperboard.model.WhisperLanguages
import com.whisperboard.postprocessing.PostProcessingSettingsRepository
import com.whisperboard.postprocessing.PostProcessingStrategy
import com.whisperboard.transcription.ApiProvider
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.transcription.EngineStrategy
import kotlinx.coroutines.launch

/**
 * Top-level settings entry. Replaces the old single-page layout with a
 * navigable list of categorised pages. Pages currently live:
 *
 * - **Behavior** — auto-insert, auto-copy.
 * - **Privacy** — skeleton; populated in slice #6b (issue #8).
 * - **Languages** — favourite-language picker today; slice #5 (issue #5) will
 *   add the language profile here.
 * - **History** — skeleton; populated in slice #6b (issue #8).
 * - **Models** — Whisper model picker + import.
 * - **Transcription** — engine strategy + API config.
 * - **Post-processing** — polish-mode toggle + strategy picker.
 *
 * Navigation is intentionally a hand-rolled enum + state rather than the
 * Navigation Compose library — the screen graph is shallow (1 level) and the
 * existing surface area doesn't yet warrant the dependency.
 */
private enum class SettingsPage(val title: String) {
    Root("Whisper Board"),
    Behavior("Behavior"),
    Privacy("Privacy"),
    Languages("Languages"),
    History("History"),
    Models("Models"),
    Transcription("Transcription"),
    PostProcessing("Post-processing"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelRepository: ModelRepository,
    languageRepository: LanguageRepository,
    apiSettingsRepository: ApiSettingsRepository,
    postProcessingSettingsRepository: PostProcessingSettingsRepository,
    behaviorSettingsRepository: BehaviorSettingsRepository,
    imeEnabled: Boolean = true,
    imeSelected: Boolean = true,
    onOpenImeSettings: () -> Unit = {},
    onOpenImePicker: () -> Unit = {},
    onPickFile: () -> Unit = {},
    pendingFileName: String? = null,
    pendingUri: android.net.Uri? = null,
    onImportComplete: () -> Unit = {},
) {
    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPage.title) },
                navigationIcon = {
                    if (currentPage != SettingsPage.Root) {
                        IconButton(onClick = { currentPage = SettingsPage.Root }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        when (currentPage) {
            SettingsPage.Root -> RootPage(
                imeEnabled = imeEnabled,
                imeSelected = imeSelected,
                onOpenImeSettings = onOpenImeSettings,
                onOpenImePicker = onOpenImePicker,
                onNavigate = { currentPage = it },
                modifier = Modifier.padding(padding),
            )
            SettingsPage.Behavior -> BehaviorPage(
                behaviorSettingsRepository = behaviorSettingsRepository,
                modifier = Modifier.padding(padding),
            )
            SettingsPage.Privacy -> PrivacyPage(modifier = Modifier.padding(padding))
            SettingsPage.Languages -> LanguagesPage(
                languageRepository = languageRepository,
                modifier = Modifier.padding(padding),
            )
            SettingsPage.History -> HistoryPage(modifier = Modifier.padding(padding))
            SettingsPage.Models -> ModelsPage(
                modelRepository = modelRepository,
                languageRepository = languageRepository,
                snackbarHostState = snackbarHostState,
                onPickFile = onPickFile,
                pendingFileName = pendingFileName,
                pendingUri = pendingUri,
                onImportComplete = onImportComplete,
                modifier = Modifier.padding(padding),
            )
            SettingsPage.Transcription -> TranscriptionPage(
                apiSettingsRepository = apiSettingsRepository,
                modifier = Modifier.padding(padding),
            )
            SettingsPage.PostProcessing -> PostProcessingPage(
                postProcessingSettingsRepository = postProcessingSettingsRepository,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

// --- Root page (page list) ---

@Composable
private fun RootPage(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit,
    onNavigate: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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

        items(
            listOf(
                SettingsPage.Behavior to "How transcripts reach the focused field or clipboard",
                SettingsPage.Privacy to "Retention, telemetry, and what's sent to the LLM",
                SettingsPage.Languages to "Favourite languages for the picker and IME switcher",
                SettingsPage.History to "Recent dictation entries",
                SettingsPage.Models to "Whisper models for on-device transcription",
                SettingsPage.Transcription to "Local vs API engine selection and API credentials",
                SettingsPage.PostProcessing to "Polish raw transcripts with an LLM",
            ),
            key = { it.first.name },
        ) { (page, description) ->
            PageRow(
                title = page.title,
                description = description,
                onClick = { onNavigate(page) },
            )
        }
    }
}

@Composable
private fun PageRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Behavior page ---

@Composable
private fun BehaviorPage(
    behaviorSettingsRepository: BehaviorSettingsRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val autoInsert by behaviorSettingsRepository.autoInsertEnabled
        .collectAsState(initial = BehaviorSettingsRepository.DEFAULT_AUTO_INSERT)
    val autoCopy by behaviorSettingsRepository.autoCopyEnabled
        .collectAsState(initial = BehaviorSettingsRepository.DEFAULT_AUTO_COPY)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToggleRow(
            title = "Auto-insert into focused field",
            description = "Polished transcript is written to the focused field as soon as " +
                "transcription completes. Turn off to review and tap to send.",
            checked = autoInsert,
            onCheckedChange = { enabled ->
                scope.launch { behaviorSettingsRepository.setAutoInsertEnabled(enabled) }
            },
        )

        HorizontalDivider()

        ToggleRow(
            title = "Auto-copy to clipboard",
            description = "When the bubble runs without a focused field, the polished " +
                "transcript is auto-copied to the clipboard.",
            checked = autoCopy,
            onCheckedChange = { enabled ->
                scope.launch { behaviorSettingsRepository.setAutoCopyEnabled(enabled) }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// --- Privacy page (skeleton — slice #6b populates this) ---

@Composable
private fun PrivacyPage(modifier: Modifier = Modifier) {
    // Slice #6b (issue #8) will populate this with retention picker,
    // "send transcripts to LLM" toggle, and "log diagnostics" toggle.
    SkeletonPage(
        modifier = modifier,
        message = "Privacy controls arrive in a future update.",
    )
}

// --- History page (skeleton — slice #6b populates this) ---

@Composable
private fun HistoryPage(modifier: Modifier = Modifier) {
    // Slice #6b (issue #8) will populate this with the dictation-history list.
    SkeletonPage(
        modifier = modifier,
        message = "Dictation history arrives in a future update.",
    )
}

@Composable
private fun SkeletonPage(modifier: Modifier, message: String) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Languages page ---

@Composable
private fun LanguagesPage(
    languageRepository: LanguageRepository,
    modifier: Modifier = Modifier,
) {
    // The Languages page hosts two independent concerns:
    //
    // - The **language profile** (slice #5 / issue #5) — the set of languages
    //   the user has declared they speak. Consumed by the post-processor as
    //   system-prompt context for code-switching recovery (per ADR-0003); not
    //   passed to whisper.cpp. Editable post-onboarding here.
    // - **Favourites** — the cosmetic IME picker subset. Independent of the
    //   profile per CONTEXT.md.
    val scope = rememberCoroutineScope()
    val favoriteLanguages by languageRepository.favoriteLanguages
        .collectAsState(initial = emptySet())
    val spokenLanguages by languageRepository.spokenLanguages
        .collectAsState(initial = LanguageRepository.DEFAULT_SPOKEN_LANGUAGES)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // --- Language profile section ---
        item {
            Text(
                text = "Languages you speak",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Used by the post-processor to recover code-switched phrases " +
                    "and to spell proper nouns correctly. Not passed to speech-to-text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(
            WhisperLanguages.codes.entries.toList(),
            key = { "profile-${it.key}" },
        ) { (code, name) ->
            LanguageProfileRow(
                code = code,
                displayName = name,
                isInProfile = code in spokenLanguages,
                onToggleProfile = {
                    scope.launch {
                        // Build the next set in-memory then write it back; the
                        // repository collapses an empty set to the default so
                        // we don't need to special-case "user unchecked the
                        // last language".
                        val next = if (code in spokenLanguages) {
                            spokenLanguages - code
                        } else {
                            // Drop the `auto` sentinel as soon as the user
                            // declares anything explicit — `auto` is the
                            // empty-profile placeholder, not a declared
                            // language.
                            (spokenLanguages - "auto") + code
                        }
                        languageRepository.setSpokenLanguages(next)
                    }
                },
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = "Favourites",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Star languages to pin them in the keyboard picker and Android IME switcher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(
            WhisperLanguages.codes.entries.toList(),
            key = { "fav-${it.key}" },
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

@Composable
private fun LanguageProfileRow(
    code: String,
    displayName: String,
    isInProfile: Boolean,
    onToggleProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isInProfile,
            onCheckedChange = { onToggleProfile() },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = code.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Models page ---

@Composable
private fun ModelsPage(
    modelRepository: ModelRepository,
    languageRepository: LanguageRepository,
    snackbarHostState: SnackbarHostState,
    onPickFile: () -> Unit,
    pendingFileName: String?,
    pendingUri: android.net.Uri?,
    onImportComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val downloadedModels by modelRepository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by modelRepository.activeModelName.collectAsState(initial = null)
    val downloadingModel by modelRepository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by modelRepository.downloadProgress.collectAsState(initial = null)
    val allModels by modelRepository.allModels.collectAsState(initial = ModelManifest.models)
    val spokenLanguages by languageRepository.spokenLanguages
        .collectAsState(initial = LanguageRepository.DEFAULT_SPOKEN_LANGUAGES)
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    // A profile is "multilingual" when the user has declared 2+ real languages.
    // The `auto` sentinel is filtered out — `[auto, en]` is monolingual. This
    // mirrors the PromptBuilder's gate for the code-switching recovery clause.
    val isMultilingualProfile = spokenLanguages.count { it != "auto" } > 1

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(allModels, key = { it.name }) { model ->
            ModelCard(
                model = model,
                isDownloaded = model.name in downloadedModels,
                isActive = model.name == activeModelName,
                isDownloading = model.name == downloadingModel,
                progress = if (model.name == downloadingModel) downloadProgress else null,
                isCustom = model.isCustom,
                languageHint = model.languageHint,
                showCodeSwitchingBadge = isMultilingualProfile && isCodeSwitchingFavoured(model),
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
                onCancel = { modelRepository.cancelDownload() },
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
    }

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete Active Model?") },
            text = {
                Text(
                    "\"${model.displayName}\" is currently in use. The keyboard will stop " +
                        "working until you select another model."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { modelRepository.delete(model) }
                    modelToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showImportDialog) {
        ImportModelDialog(
            onDismiss = { showImportDialog = false },
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

// --- Transcription page ---

@Composable
private fun TranscriptionPage(
    apiSettingsRepository: ApiSettingsRepository,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TranscriptionSettingsSection(apiSettingsRepository = apiSettingsRepository)
    }
}

// --- Post-processing page ---

@Composable
private fun PostProcessingPage(
    postProcessingSettingsRepository: PostProcessingSettingsRepository,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        PostProcessingSettingsSection(
            postProcessingSettingsRepository = postProcessingSettingsRepository,
        )
    }
}

// --- shared rows / sections ---

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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                painter = painterResource(
                    id = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                ),
                contentDescription = if (isFavorite) {
                    "Remove from favorites"
                } else {
                    "Add to favorites"
                },
                tint = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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

    // Model discovery state
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsFetchError by remember { mutableStateOf<String?>(null) }
    var modelsFetching by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    // Fetch models when provider, API key, or base URL changes
    LaunchedEffect(provider, apiKey, customUrl) {
        if (strategy == EngineStrategy.LOCAL_ONLY) return@LaunchedEffect
        modelsFetching = true
        modelsFetchError = null
        val result = apiSettingsRepository.fetchAvailableModels()
        modelsFetching = false
        result.onSuccess {
            availableModels = it
            modelsFetchError = null
        }
        result.onFailure {
            availableModels = emptyList()
            modelsFetchError = it.message
        }
    }

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

            // Model picker — dropdown if models fetched, text field as fallback
            val modelLabel = customModel.ifBlank { provider.defaultModel }.ifBlank { "Select model" }
            if (availableModels.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                ) {
                    OutlinedTextField(
                        value = modelLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        availableModels.forEach { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = {
                                    scope.launch { apiSettingsRepository.setModel(modelId) }
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { scope.launch { apiSettingsRepository.setModel(it) } },
                    label = { Text("Model") },
                    placeholder = { Text(provider.defaultModel.ifBlank { "model name" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (modelsFetching) {
                Text(
                    text = "Fetching available models...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (modelsFetchError != null && availableModels.isEmpty()) {
                Text(
                    text = "Could not fetch models — enter manually",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

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
private fun PostProcessingSettingsSection(
    postProcessingSettingsRepository: PostProcessingSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val polishMode by postProcessingSettingsRepository.polishModeEnabled
        .collectAsState(initial = PostProcessingSettingsRepository.DEFAULT_POLISH_MODE)
    val strategy by postProcessingSettingsRepository.strategy
        .collectAsState(initial = PostProcessingSettingsRepository.DEFAULT_STRATEGY)

    Column {
        // Master polish-mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Polish mode",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Clean fillers, fix punctuation, format lists and paragraphs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = polishMode,
                onCheckedChange = { enabled ->
                    scope.launch { postProcessingSettingsRepository.setPolishModeEnabled(enabled) }
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Strategy picker — only API_ONLY routes meaningfully in slice 1.
        Text(
            text = "Strategy",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))

        val routableStrategies = listOf(
            PostProcessingStrategy.OFF,
            PostProcessingStrategy.API_ONLY,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            routableStrategies.forEachIndexed { index, s ->
                SegmentedButton(
                    selected = strategy == s,
                    onClick = {
                        scope.launch { postProcessingSettingsRepository.setStrategy(s) }
                    },
                    enabled = polishMode,
                    shape = SegmentedButtonDefaults.itemShape(index, routableStrategies.size),
                ) {
                    Text(
                        text = when (s) {
                            PostProcessingStrategy.OFF -> "Off"
                            PostProcessingStrategy.API_ONLY -> "API"
                            else -> s.name
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Text(
            text = when (strategy) {
                PostProcessingStrategy.OFF -> "Polish runs only when explicitly enabled."
                PostProcessingStrategy.API_ONLY -> "Send raw transcript to the configured chat-completions endpoint for polishing."
                PostProcessingStrategy.LOCAL_ONLY,
                PostProcessingStrategy.LOCAL_PREFERRED,
                PostProcessingStrategy.API_WHEN_ONLINE -> "Local SLM not yet available — falls back to raw transcript."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        Text(
            text = "Local on-device polishing arrives in a future update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether [model] is one of the whisper-large-v3 family that ADR-0003 calls
 * out as the meaningfully better choice for code-switching. The badge in the
 * model picker uses this. Match is by name substring so user-imported variants
 * (e.g. `large-v3-turbo`, `large-v3-q5_0`) get the same hint as the canonical
 * manifest entry, which keeps the badge useful even before a stock entry
 * lands.
 */
private fun isCodeSwitchingFavoured(model: ModelInfo): Boolean =
    model.name.contains("large-v3", ignoreCase = true) ||
        model.displayName.contains("large-v3", ignoreCase = true)

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    isCustom: Boolean = false,
    languageHint: String? = null,
    showCodeSwitchingBadge: Boolean = false,
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleSmall,
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
                        if (showCodeSwitchingBadge) {
                            // Soft hint, not a hard block — users on weak
                            // hardware should still be free to pick a smaller
                            // model. The badge appears only when the user has
                            // declared 2+ languages, so it's contextual and
                            // unobtrusive for monolingual users.
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "Best for code-switching",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (isActive) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
