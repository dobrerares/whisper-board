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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelRepository: ModelRepository,
    languageRepository: LanguageRepository,
) {
    val scope = rememberCoroutineScope()
    val downloadedModels by modelRepository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by modelRepository.activeModelName.collectAsState(initial = null)
    val downloadingModel by modelRepository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by modelRepository.downloadProgress.collectAsState(initial = null)
    val favoriteLanguages by languageRepository.favoriteLanguages.collectAsState(initial = emptySet())
    var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Whisper Board Settings") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Models section ---
            item {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(ModelManifest.models) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = model.name in downloadedModels,
                    isActive = model.name == activeModelName,
                    isDownloading = model.name == downloadingModel,
                    progress = if (model.name == downloadingModel) downloadProgress else null,
                    onDownload = {
                        scope.launch { modelRepository.download(model) }
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
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    progress: DownloadProgress?,
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
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (isActive) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
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
