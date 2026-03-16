package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.DownloadProgress
import com.whisperboard.model.ModelInfo
import com.whisperboard.model.ModelManifest
import com.whisperboard.model.ModelRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(repository: ModelRepository) {
    val scope = rememberCoroutineScope()
    val downloadedModels by repository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by repository.activeModelName.collectAsState(initial = null)
    val downloadingModel by repository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by repository.downloadProgress.collectAsState(initial = null)

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
                        scope.launch { repository.download(model) }
                    },
                    onDelete = {
                        scope.launch { repository.delete(model) }
                    },
                    onSelect = {
                        scope.launch { repository.setActiveModel(model.name) }
                    },
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
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
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
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
