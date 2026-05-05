package com.whisperboard.model.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row in the dictation history. The persistent record of a single
 * utterance — polished + raw text + when it happened + which language(s)
 * Whisper saw + which app was focused.
 *
 * **No audio is stored.** Per CONTEXT.md the entry holds polished, raw,
 * timestamp, detected language(s), and target app name. Re-dictation is
 * cheap; storing audio is a privacy and storage cost we explicitly decline.
 *
 * [detectedLanguages] is persisted as a comma-separated list of ISO codes so
 * that Whisper's per-utterance label (currently a single code; future
 * code-switched output may carry several) round-trips through Room without a
 * type converter. Empty string means "unknown" — treat the entry as
 * monolingual-but-unlabelled rather than multilingual.
 */
@Entity(tableName = "dictation_entries")
data class DictationEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val polishedTranscript: String,
    val rawTranscript: String,
    val timestampMs: Long,
    /**
     * Comma-separated language codes the engine reported for this utterance.
     * Empty string when unknown. The list-shaped accessor [languageCodes]
     * splits and trims, so callers don't need to know the wire format.
     */
    val detectedLanguages: String,
    /** Package name of the app that owned the focused field, if any. */
    val targetAppName: String?,
) {
    val languageCodes: List<String>
        get() = if (detectedLanguages.isBlank()) {
            emptyList()
        } else {
            detectedLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
}
