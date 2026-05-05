# Hybrid surface architecture with accessibility-optional bubble

We ship two input surfaces in parallel: the existing IME (`InputMethodService`) and a new floating bubble overlay (`SYSTEM_ALERT_WINDOW`). The bubble works in *standalone mode* (records, displays, auto-copies to clipboard) without the accessibility service, and upgrades to *in-place insertion* when the user grants `BIND_ACCESSIBILITY_SERVICE` — at which point it can write the transcript directly into the focused field. Both surfaces feed the same `EngineRouter`, so transcription is decoupled from surface.

## Why this shape

Wispr Flow's "speak anywhere" UX is fundamentally an overlay, not a keyboard. Keeping only the IME caps the product at "voice input when a field is focused" — a smaller product than the one we want to build. But making the bubble the *only* surface forces the accessibility ask before any value is delivered, which gutters onboarding (Android's accessibility consent dialog is intentionally scary). The hybrid + permission gradient lets users earn the upgrade after experiencing standalone-mode value, while keeping the IME as a permissions-free first-run path.

## Considered and rejected

- **IME-only.** Closes off the speak-anywhere use case entirely. Whisper Board would be limited to "speak when the keyboard is up," which is what most STT keyboards already are.
- **Bubble-only with mandatory accessibility.** Hostile install funnel: every user must grant accessibility before any feature works. Empirically, users won't grant accessibility before they trust the app.
- **Clipboard-only fallback (no accessibility ever).** Removes accessibility from the equation, but caps the UX at "speak → copy → paste manually" forever — never reaches Wispr-Flow-class smoothness in the use cases that need in-field insertion.
