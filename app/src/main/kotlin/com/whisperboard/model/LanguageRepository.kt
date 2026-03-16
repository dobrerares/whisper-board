package com.whisperboard.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LanguageRepository(private val context: Context) {

    companion object {
        private val KEY_FAVORITES = stringSetPreferencesKey("favorite_languages")
        private val KEY_ACTIVE_LANGUAGE = stringPreferencesKey("active_language")
    }

    val favoriteLanguages: Flow<Set<String>> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FAVORITES] ?: emptySet()
    }

    val activeLanguage: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LANGUAGE] ?: "auto"
    }

    suspend fun setActiveLanguage(code: String) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LANGUAGE] = code
        }
    }

    suspend fun addFavorite(code: String) {
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current + code
        }
    }

    suspend fun removeFavorite(code: String) {
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current - code
        }
    }
}
