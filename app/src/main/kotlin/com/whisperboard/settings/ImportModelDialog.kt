package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.WhisperLanguages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportModelDialog(
    onDismiss: () -> Unit,
    onImportFile: (displayName: String, languageHint: String?) -> Unit,
    onImportUrl: (url: String, displayName: String, languageHint: String?) -> Unit,
    selectedFileName: String?,
    onBrowseFile: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var languageExpanded by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }

    val languageLabel = selectedLanguage?.let { WhisperLanguages.displayName(it) } ?: "Auto-detect"

    val canImport = displayName.isNotBlank() && when (tab) {
        0 -> selectedFileName != null
        1 -> url.isNotBlank()
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Model") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) {
                        Text("File", modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("URL", modifier = Modifier.padding(12.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (tab) {
                    0 -> {
                        OutlinedButton(
                            onClick = onBrowseFile,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedFileName ?: "Browse...")
                        }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            placeholder = { Text("https://huggingface.co/.../model.bin") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = languageLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Language (optional)") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto-detect") },
                            onClick = {
                                selectedLanguage = null
                                languageExpanded = false
                            },
                        )
                        WhisperLanguages.codes.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedLanguage = code
                                    languageExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (tab) {
                        0 -> onImportFile(displayName, selectedLanguage)
                        1 -> onImportUrl(url, displayName, selectedLanguage)
                    }
                },
                enabled = canImport,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
