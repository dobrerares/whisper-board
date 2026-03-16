package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.whisperboard.R
import com.whisperboard.model.WhisperLanguages
import kotlinx.coroutines.launch

@Composable
fun LanguagePickerDialog(
    activeLanguage: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allLanguages = WhisperLanguages.codes.entries.toList()
    val favoriteEntries = allLanguages.filter { it.key in favorites }
    val otherEntries = allLanguages.filter { it.key !in favorites }

    // Build item list to compute indices for A-Z sidebar
    data class ListItem(val key: String, val type: String, val code: String = "", val name: String = "")

    val items = buildList {
        // Auto-detect
        add(ListItem(key = "auto", type = "language", code = "auto", name = "Auto-detect"))
        // Favorites
        if (favoriteEntries.isNotEmpty()) {
            add(ListItem(key = "header-fav", type = "header"))
            favoriteEntries.forEach { (code, name) ->
                add(ListItem(key = "fav-$code", type = "language", code = code, name = name))
            }
        }
        // All languages header
        add(ListItem(key = "header-all", type = "header"))
        otherEntries.forEach { (code, name) ->
            add(ListItem(key = "all-$code", type = "language", code = code, name = name))
        }
    }

    // Map first letter → index in items list (only "All Languages" section)
    val letterToIndex = remember(otherEntries) {
        val map = mutableMapOf<Char, Int>()
        val allHeaderIndex = items.indexOfFirst { it.key == "header-all" }
        otherEntries.forEachIndexed { i, (_, name) ->
            val letter = name.first().uppercaseChar()
            if (letter !in map) {
                map[letter] = allHeaderIndex + 1 + i
            }
        }
        map
    }
    val availableLetters = remember(letterToIndex) {
        ('A'..'Z').filter { it in letterToIndex }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Language",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Row(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                        state = listState,
                    ) {
                        items(items, key = { it.key }) { item ->
                            when (item.type) {
                                "header" -> {
                                    val label = if (item.key == "header-fav") "Favorites" else "All Languages"
                                    val color = if (item.key == "header-fav") {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                "language" -> {
                                    LanguageRow(
                                        code = item.code,
                                        displayName = item.name,
                                        isActive = item.code == activeLanguage,
                                        isFavorite = item.code in favorites,
                                        showStar = item.code != "auto",
                                        onSelect = { onSelectLanguage(item.code) },
                                        onToggleFavorite = { onToggleFavorite(item.code) },
                                    )
                                }
                            }
                        }
                    }

                    // A-Z quick scroll sidebar
                    AlphabetSidebar(
                        letters = availableLetters,
                        onLetterSelected = { letter ->
                            val index = letterToIndex[letter] ?: return@AlphabetSidebar
                            scope.launch { listState.scrollToItem(index) }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(24.dp)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlphabetSidebar(
    letters: List<Char>,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightPx by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val index = ((down.position.y / heightPx) * letters.size)
                        .toInt()
                        .coerceIn(0, letters.lastIndex)
                    onLetterSelected(letters[index])

                    drag(down.id) { change ->
                        change.consume()
                        val dragIndex = ((change.position.y / heightPx) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        onLetterSelected(letters[dragIndex])
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            Text(
                text = letter.toString(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
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
