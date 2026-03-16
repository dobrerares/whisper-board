package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import com.whisperboard.model.WhisperLanguages

@Composable
fun LanguagePickerDialog(
    activeLanguage: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val allLanguages = WhisperLanguages.codes.entries.toList()

    val filtered = if (searchQuery.isBlank()) {
        allLanguages
    } else {
        val query = searchQuery.lowercase()
        allLanguages.filter { (code, name) ->
            code.contains(query) || name.lowercase().contains(query)
        }
    }

    val favoriteEntries = filtered.filter { it.key in favorites }
    val otherEntries = filtered.filter { it.key !in favorites }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            Column {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search languages...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(top = 8.dp)
                ) {
                    // Auto-detect option
                    item {
                        LanguageRow(
                            code = "auto",
                            displayName = "Auto-detect",
                            isActive = activeLanguage == "auto",
                            isFavorite = false,
                            showStar = false,
                            onSelect = { onSelectLanguage("auto") },
                            onToggleFavorite = {},
                        )
                    }

                    // Favorites section
                    if (favoriteEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(favoriteEntries, key = { "fav-${it.key}" }) { (code, name) ->
                            LanguageRow(
                                code = code,
                                displayName = name,
                                isActive = code == activeLanguage,
                                isFavorite = true,
                                showStar = true,
                                onSelect = { onSelectLanguage(code) },
                                onToggleFavorite = { onToggleFavorite(code) },
                            )
                        }
                    }

                    // All languages section
                    item {
                        Text(
                            text = "All Languages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(otherEntries, key = { "all-${it.key}" }) { (code, name) ->
                        LanguageRow(
                            code = code,
                            displayName = name,
                            isActive = code == activeLanguage,
                            isFavorite = false,
                            showStar = true,
                            onSelect = { onSelectLanguage(code) },
                            onToggleFavorite = { onToggleFavorite(code) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun LanguageRow(
    code: String,
    displayName: String,
    isActive: Boolean,
    isFavorite: Boolean,
    showStar: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (code != "auto") {
                Text(
                    text = code.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showStar) {
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
}
