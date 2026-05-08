# Whisper model unloads on idle, not on memory pressure

The Whisper `WhisperContext` is loaded once when an active model is selected (`WhisperBoardIME.onCreate()`'s `modelRepository.activeModelName.collectLatest`) and unloaded after a configurable idle threshold — default 5 minutes since the last `transcribe()` call. Settings → Advanced exposes the threshold as a slider with a "Never" option for users who prefer to pay the RAM cost for first-utterance latency. The IME does **not** hook `Service.onTrimMemory()`; system-driven memory pressure unloads are deliberately out of scope. Reload happens lazily on the next `transcribe()` call. The `LocalPostProcessor`'s `LlmContext` is already per-call-loaded today and is unaffected.

## Why idle-only

Mobile RAM pressure is real for users running larger Whisper models (`large-v3` is ~1.5 GB resident), but the cohort with devices new enough to run those models tends to have 8 GB+ RAM and rarely sees aggressive system reclamation. The polite-cleanup case (user dictates, switches apps, comes back later) is by far the more common pattern, and idle-based unload covers it cleanly. `onTrimMemory` adds code, a second reload path, and a class of "why did my dictation take 2s longer this time?" surprise without delivering proportional value on the target device class. If usage data later shows low-end-device thrash, this ADR will be revisited.

## Implementation note (not a decision)

Ownership of the idle timer is an open design point. `EngineRouter.transcribe()` is the natural place to refresh `lastUsedAt`; `WhisperBoardIME` is the natural owner of the `WhisperContext` lifecycle. The cleanest refactor is to introduce a `ManagedLocalEngine` (or similar) that owns both the model path/factory and the idle timer, with `EngineRouter` calling `ensureLoaded()` ahead of each `transcribe()`. This is an implementation choice, not a directional one; either centralizing ownership in the engine or threading a callback from the router into the IME satisfies the ADR.

## Considered and rejected

- **Never unload mid-service-lifetime (current behavior).** Fine for `tiny.en` (~75 MB); genuinely bad citizenship for `large-v3`. The model can sit resident across hours of IME service lifetime even when the user isn't dictating.
- **Hook `Service.onTrimMemory()` in addition to idle.** Would cooperate with Android's memory signals on low-RAM devices. Rejected for v1: extra reload path, extra "unexpected reload" surprise, marginal benefit on the target device class. Easy to add later if data shows it's needed.
- **`onTrimMemory()` instead of idle.** Reactive-only. Doesn't help the common polite-cleanup case (user idle for 30 minutes on a non-pressured device). Idle is the right primary lever; system pressure would be a secondary one if added.
- **Aggressive default (1–2 min idle).** Saves more RAM, but small-model users would notice reload hiccups in a "dictate, think, dictate again" pattern. 5 minutes is forgiving enough for that workflow while still freeing memory before the user has fully walked away.
- **No "Never" option in the slider.** Removing the escape hatch would force RAM cost on users who want zero first-utterance latency and have RAM to spare. The setting is already advanced; the option costs nothing.
