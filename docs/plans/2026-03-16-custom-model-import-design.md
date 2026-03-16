# Custom ASR Model Import — Design

## Summary

Allow users to import custom GGML whisper.cpp models into Whisper Board via file picker or URL download. Custom models appear alongside built-in models in a unified list with a "Custom" badge.

## Data Model

Extend `ModelInfo` with two fields:

- `isCustom: Boolean = false` — distinguishes custom from built-in models
- `languageHint: String? = null` — optional language tag (e.g., "en", "de")

Custom models are persisted in DataStore as JSON under `KEY_CUSTOM_MODELS` using `kotlinx.serialization`.

## Import Flows

### File Picker

1. User taps "Import Model" in Settings → opens `ImportModelDialog`
2. Selects "File" tab → Android `OpenDocument` contract launches
3. User picks a `.bin` file → app copies it to `filesDir/models/custom_<timestamp>.bin`
4. User provides display name + optional language hint
5. App validates the model by attempting `whisper_init_from_file`; rejects if invalid
6. `ModelInfo` created with `isCustom = true`, added to DataStore

### URL Download

1. User selects "URL" tab in the import dialog
2. Enters URL + display name + optional language hint
3. App creates `ModelInfo` with the URL and generated filename
4. Reuses existing `ModelRepository.download()` — same progress, cancellation, `.tmp` → rename
5. On success, validated and added to DataStore

### Validation

After file copy or download, attempt to load the model with `whisper_init_from_file`. If it returns a null pointer, delete the file and show an error ("Not a valid whisper model").

## UI Changes

### Settings Model List

- `SettingsScreen` iterates `ModelRepository.allModels` (built-in + custom combined)
- Custom model cards show a "Custom" badge and language hint if present
- Delete/Use buttons work identically to built-in models

### Import Dialog

- "Import Model" card at the bottom of the model list
- Dialog with two tabs: "File" | "URL"
- File tab: "Browse..." button launching file picker, shows selected filename
- URL tab: text field for the URL
- Common fields: display name (required) + language dropdown (optional, defaults to "Auto-detect")
- Language dropdown uses existing `WhisperLanguages` list
- "Import" button disabled until name + file/URL are provided
- Progress bar during URL download or file copy

### Error States

- Invalid model → snackbar: "Not a valid whisper model"
- Download failure → snackbar with error message
- Duplicate name → auto-append number suffix

## Storage & Lifecycle

### Persistence

- `KEY_CUSTOM_MODELS` in DataStore: JSON-serialized list of custom `ModelInfo` entries
- `ModelRepository` exposes `val allModels: Flow<List<ModelInfo>>` = `ModelManifest.models + customModels`

### Deletion

- Same as built-in: delete file, remove from `KEY_DOWNLOADED`
- Additionally: remove from `KEY_CUSTOM_MODELS`

### No Migration

- Existing keys (`KEY_DOWNLOADED`, `KEY_ACTIVE`) unchanged
- New `KEY_CUSTOM_MODELS` starts as empty list
- Built-in models unaffected

## Approach

Extend `ModelRepository` with a `customModels` flow backed by DataStore. No new classes or dependencies beyond `kotlinx.serialization` (already available). Reuses all existing download, storage, and deletion infrastructure.
