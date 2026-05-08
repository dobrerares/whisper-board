# Auto-submit fires only on declared `imeAction`, not blunt `KEYCODE_ENTER`

The **auto-submit** toggle in `BehaviorSettingsRepository` (default off, IME only) sends the focused field's declared IME action after `InputConnection.commitText` succeeds, but *only* when `EditorInfo.imeOptions` masks to `IME_ACTION_SEND`, `IME_ACTION_DONE`, or `IME_ACTION_GO`. The IME captures the action via `viewModel.currentImeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION` (already wired in `WhisperBoardIME.onStartInputView`); auto-submit reads that captured action and dispatches via `InputConnection.performEditorAction(action)`. In fields where the action is `IME_ACTION_NONE`, `IME_ACTION_UNSPECIFIED`, `IME_ACTION_NEXT`, or `IME_ACTION_PREVIOUS`, auto-submit is a deliberate no-op — no fallback to a blunt `KEYCODE_ENTER`. The Bubble's accessibility delivery has no `EditorInfo` and is unaffected by the toggle.

## Why imeAction-gated, not universal

A blunt `KEYCODE_ENTER` after every commit (Handy's `auto_submit` model) is destructive in the wrong contexts: in a multi-line note it inserts a newline; in a search bar it might trigger search before the user wanted; in a chat app's draft area it sends a message that can't be unsent. The `imeAction` mask is the platform's own statement of "this field expects a single 'go ahead' gesture" — chat apps set `SEND`, single-line forms set `DONE` or `GO`, multi-line drafts and free-text fields don't. Restricting auto-submit to the explicit-go-ahead actions makes the feature destructive *only* in fields the field's author has already declared destructive-friendly.

The "smarter than Handy" framing isn't aesthetic — it's a different feature shape that prevents a class of "auto-submit ruined my note draft" complaints that desktop Handy (with no equivalent platform signal) cannot prevent.

## Why no `KEYCODE_ENTER` fallback

A natural-feeling extension would be: "if the field has no `imeAction`, send `KEYCODE_ENTER` as a fallback so the toggle does *something* in every field." This was rejected: it reintroduces the exact destructive behaviour the imeAction gate was designed to prevent (Enter in a note adds a newline; Enter in some custom views does unpredictable things). Users who want blunt Enter can use the `EditingKeysRow`'s manual Enter key, which is always available.

## Considered and rejected

- **Always on, no toggle.** Destructive default; user has no escape from "every dictation submits."
- **Toggle on, default on, blunt `KEYCODE_ENTER`.** Matches Handy's model. Wrong for the multi-app fluidity the IME serves — same toggle would be helpful in WhatsApp and ruinous in a notes app two seconds later.
- **Toggle on, default off, blunt `KEYCODE_ENTER` (Handy-equivalent).** Less destructive (opt-in), but still requires the user to remember to disable before switching to a notes app. The imeAction gate makes the toggle context-aware so the user doesn't have to.
- **Toggle on, default off, imeAction-gated, with `KEYCODE_ENTER` fallback in non-imeAction fields.** Considered for "do something in every field." Rejected for reintroducing destructive behaviour in fields that explicitly declared they don't want it (by setting `IME_ACTION_NONE` or no action at all).
- **Bubble auto-submit via accessibility "tap send button" gesture.** Would require per-app knowledge of where the send button is. Out of scope; the Bubble's delivery surface is text-only.
