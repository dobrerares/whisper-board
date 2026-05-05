# Bubble redesign: edge sliver + summoned sheet + dismiss-cooldown

## Status

Design approved 2026-05-06. Implementation plan to follow.

## Context

The v1.0.0 release shipped the bubble as a 64 dp floating circle (idle) that grew up to a 320 dp wide rounded pill (showing the polished transcript) when a result was ready. Two issues surfaced after release:

1. **Both states feel too big.** The 64 dp idle circle is already Messenger-chat-head sized; the 320 dp result pill is much larger. The user's brief was "make it unobtrusive — like a Messenger chat bubble" but with a smaller footprint than the Messenger conventions.
2. **AlwaysVisible mode doesn't actually attach the overlay.** Likely cause: Android 14+ (`UPSIDE_DOWN_CAKE`) rejects `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE | SPECIAL_USE)` when the service isn't currently using the mic. The bubble's idle state isn't using the mic, so the foreground transition fails silently.

The redesign replaces the floating circle with an **edge sliver** that peeks 8 dp from the screen edge, plus a small **summoned sheet** that slides out on demand. State indication moves from bubble geometry (different sizes per state) to color + a single optional pulse on the sliver. Dismiss gains a configurable cooldown so the user can hide the sliver during focus work without remembering to bring it back.

## Goals

- Bubble's persistent on-screen footprint is **at most 8 dp wide** plus an invisible 24 dp touch zone.
- Default state indication is **legible from edge of vision** (color + pulse) without summoning the sheet.
- Dismiss is a **single gesture** (drag-tear); cooldown brings the sliver back automatically.
- Existing bubble vocabulary (`AlwaysVisible` / `SummonedOnly` / `Disabled`) stays valid; semantics unchanged.
- The AlwaysVisible-doesn't-show bug is fixed as part of this work, not separately.

## Non-goals

- Per-app behavior (different cooldowns / sides per focused app) — out of scope, Tier 4.
- Animations beyond the recording pulse and the 3 s result-ready green — keep the surface quiet.
- Replacing the existing accessibility-based in-place insertion (Slice 3 / PR #15) — accessibility flow and the separate `BubbleAccessibilityDelivery` stay unchanged.
- Bubble configuration via QS tile content — the QS tile keeps its existing summon role.

## User experience

### Idle state

A persistent **8 dp blue sliver** at the right edge (default). Vertical position is draggable along the edge and persists in DataStore. Settings → Bubble exposes an "Edge side" picker (Right / Left, default Right). The sliver has an invisible 24 dp touch margin around it so finger imprecision doesn't break the gesture targets.

### Gestures

| Gesture | Action |
|---|---|
| Tap | Peek — slides out a small sheet anchored to the sliver, showing mic button + last result + re-insert / copy buttons |
| Long-press | Start recording immediately. Sheet auto-opens with live waveform; release commits |
| Drag perpendicular (across the screen, away from the edge) past 30 % of screen width | Dismiss → cooldown → sliver slides back to the edge |
| Drag along the edge | Reposition vertically; new y persists |

The 200 ms long-press threshold (existing constant in `BubbleView`) disambiguates tap from long-press. The drag-perpendicular dismiss is gated on the gesture's start position being within the sliver's touch zone — drags that start anywhere else are ignored.

Drags during Recording / Processing / ResultReady are **dropped** rather than canceling the action. The user can't accidentally lose a transcript by mis-gesturing during a dictation.

### Summoned sheet

The sheet is a small panel that slides out from the sliver, anchored vertically to the sliver's current y position. Contents:

- Mic button (32 dp circle). Long-press the mic to start a recording inside the sheet (same as long-pressing the sliver, but available once the sheet is already open).
- Last result row: a 1-line preview of the most recently committed dictation entry (truncated with ellipsis), plus two affordances:
  - **Re-insert** — re-commits the polished text into the currently focused field via `InputConnection.commitText` (or via the accessibility service if granted, mirroring `BubbleAccessibilityDelivery`).
  - **Copy** — writes the polished text to the clipboard via `ClipboardManager.setPrimaryClip`.

The sheet auto-closes after 4 s of inactivity, or on outside-tap. While the sheet is showing the recording / polishing / result-ready states, the auto-close timer pauses.

### Sliver state-by-color

| State | Sliver appearance |
|---|---|
| Idle | Quiet blue tint (current MaterialTheme primary at ~60 % alpha) |
| Recording | Solid red, slight pulse (sliver length pulses ±10 % at 800 ms cadence — same animation primitive as today's `BubbleAmplitudePulse`) |
| Processing | Solid amber, no pulse |
| Result-ready | Solid green for 3 s, then fades back to idle blue |
| Dismissed (cooldown active) | Sliver hidden entirely; nothing on screen |

### Cooldown

Drag-tear sets `dismissedAt = now()`. The bubble is hidden until `now() - dismissedAt >= cooldownMs`. The default cooldown is 5 minutes; Settings → Bubble exposes a picker:

- 1 min
- 5 min (default)
- 15 min
- 1 hour
- Until next time you open Whisper Board (`cooldownMs = Long.MAX_VALUE`; specifically: cleared by `SettingsActivity.onCreate`, which is the only user-facing entry point that signals "I'm back interacting with this app". The IME binding and the bubble service starting after process death do **not** clear the cooldown — those happen incidentally and shouldn't override the user's "hide it" intent)

**Cooldown × visibility mode interactions:**

| Mode | While dismissed | When cooldown elapses |
|---|---|---|
| `AlwaysVisible` | Sliver hidden | Sliver returns to edge |
| `SummonedOnly` | Sliver hidden, `isSummoned` is reset to `false` on dismiss | Stays hidden — user must re-summon via QS tile or settings |
| `Disabled` | Cooldown logic short-circuited (Disabled wins early in the rule order) | N/A |

## Architecture

### Component changes

**New:**

- `bubble/EdgeSliver.kt` — `@Composable fun EdgeSliver(state: SliverState, modifier: Modifier)`. Replaces the per-state composables in `BubbleView`. One thin `Box` with a height/width pair driven by `state.lengthDp`, plus a color picked by `state.colorRole`. Pulse animation comes from the same `infiniteRepeatable` primitive used today.
- `bubble/SummonedSheet.kt` — `@Composable fun SummonedSheet(...)`. Renders the mic button, last result row, re-insert/copy actions. Anchored visually to the sliver via shared layout coordinates passed from `BubbleView`.
- `bubble/BubbleCooldown.kt` — pure logic. `data class BubbleCooldown(val dismissedAt: Long?, val cooldownMs: Long)` with `fun isActive(now: Long): Boolean`. Lives in its own file so its tests don't pull in the rest of the bubble package.

**Modified:**

- `bubble/BubbleView.kt` — slimmed dramatically. Becomes a top-level orchestrator that decides whether to render `EdgeSliver`, `SummonedSheet`, or both. Loses `IdleBubble`, `ResultBubble`, `BubbleAmplitudePulse`.
- `bubble/BubbleStateMachine.kt` — keeps `Idle / Recording / Processing / Result` states, **adds** `Dismissed(cooldownEndsAt: Long)` and `Peeked` states. Adds events: `BubbleEvent.Peek`, `BubbleEvent.PeekTimeout`, `BubbleEvent.DragTearComplete`, `BubbleEvent.CooldownElapsed`. The drag-tear-during-action rule is enforced by the transition table (Recording / Processing / Result transitions on `DragTearComplete` are no-ops).
- `bubble/BubbleVisibilityRules.kt` — adds `cooldownActive: Boolean` to `BubbleVisibilityInputs`; rule order becomes:
  1. `mode == Disabled` → hidden
  2. `cooldownActive` → hidden
  3. `onLockscreen` → hidden
  4. `foregroundAppIsFullscreen` → hidden
  5. `draggedOffEdge` → hidden (still present for QS-tile-driven re-summon)
  6. mode dispatch (AlwaysVisible / SummonedOnly)
- `bubble/BubbleSettingsRepository.kt` — adds `edge: Flow<Edge>` (LEFT / RIGHT, default RIGHT), `cooldownMs: Flow<Long>` (default 5 × 60 × 1000), `dismissedAt: Flow<Long?>`. New `enum class Edge { LEFT, RIGHT }` lives in `BubbleSettingsRepository.kt` next to the existing `BubbleVisibilityMode` import.
- `bubble/BubbleOverlayService.kt`:
  - `WindowManager.LayoutParams` width changes from `WRAP_CONTENT` to `MATCH_PARENT` so the drag-perpendicular gesture has a wide enough hit surface. The visible sliver still occupies only 8 dp; the rest of the window is transparent and `FLAG_NOT_TOUCHABLE` outside the sliver's invisible 24 dp touch zone (handled in Compose pointer-input filtering).
  - **Foreground service type policy changes** to fix the AlwaysVisible bug:
    - On initial `startForeground`: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` only.
    - When recording starts (state machine emits `Recording`): call `startForeground` again with `FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
    - When recording ends (state machine leaves `Recording`): call `startForeground` again with `SPECIAL_USE` only.
  - The horizontal anchor (left vs right) is read from `BubbleSettingsRepository.edge` and reflected in `LayoutParams.gravity` and the Compose layout direction.
- `settings/SettingsScreen.kt` — Bubble page gains:
  - "Edge side" picker (Right / Left).
  - "Cooldown after dismiss" picker (1 min / 5 min / 15 min / 1 hour / Until next app launch).
  - Visibility-mode picker stays.

**Deleted (functionality moves):**

- `IdleBubble`, `ResultBubble`, `BubbleAmplitudePulse` composables in `BubbleView.kt`.

### State machine — full transition table

Format: `From state ──event──> To state`. Anything not listed is ignored.

```
Disabled ──Enable──> Idle
Idle ──Tap──> Peeked
Idle ──LongPressStart──> Recording
Idle ──DragTearComplete──> Dismissed(now + cooldownMs)
Peeked ──PeekTimeout──> Idle
Peeked ──Tap (outside)──> Idle
Peeked ──LongPressStart──> Recording
Recording ──LongPressEnd──> Processing
Processing ──TranscriptReady(text)──> Result(text)
Result ──ResultDecayElapsed (3 s)──> Idle
Result ──Tap──> Peeked  (re-shows the sheet with the result inline)
Dismissed(t) ──CooldownElapsed (now ≥ t)──> Idle
* ──Disable──> Disabled
```

Recording / Processing / Result drop `DragTearComplete` (no transition).

### Data flow

```
EdgeSliver pointer events
    ─→ BubbleStateMachine (pure)
        ─→ emits BubbleEffect.{Record, AutoCopy, AutoInsert, ...}
            ─→ BubbleOverlayService (handles native side)
                ─→ updates serviceFlow → recomposes EdgeSliver / SummonedSheet
        ─→ emits state transition
            ─→ BubbleSettingsRepository (Dismissed → write dismissedAt)
            ─→ Sliver color/pulse/visibility recompute
```

The `cooldownActive` boolean fed to `BubbleVisibilityRules` is a derived flow: `combine(dismissedAt, cooldownMs, currentTimeFlow) { ... }` where `currentTimeFlow` ticks once per second while in Dismissed state and is dormant otherwise.

### Persistence (BubbleSettingsRepository additions)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `bubble_edge` | String | `RIGHT` | Edge side (`LEFT` / `RIGHT`) |
| `bubble_cooldown_ms` | Long | `300_000` (5 min) | Cooldown duration after dismiss |
| `bubble_dismissed_at` | Long? | absent | Wall-clock timestamp of last dismiss; cleared on cooldown elapse or app launch (when cooldown is "Until next app launch") |

### Window manager: two windows, one persistent + one transient

A single overlay window cannot both (a) cover the full screen for drag-tear detection and (b) let touches fall through to the underlying app outside the sliver. `FLAG_NOT_TOUCH_MODAL` only routes touches *outside* a window's bounds — if width is `MATCH_PARENT`, there's no outside, and the underlying app stops receiving touches.

Pattern instead: **two windows that come and go**.

**Persistent window — the sliver itself:**

- Width: `WRAP_CONTENT` (effectively 8 dp visible + 24 dp invisible touch margin = ~32 dp wide).
- Height: `WRAP_CONTENT` plus the sheet's height when expanded (the sheet renders inside the same window, anchored to the sliver's edge).
- Gravity: `RIGHT` or `LEFT` based on `edge` setting; vertically centered then offset by saved y.
- Flags: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_NOT_TOUCH_MODAL`. Touches *outside* this narrow window pass through to the underlying app.

**Transient window — the drag tracker:**

When the user puts a finger down on the sliver and starts moving perpendicular to the edge (i.e., the gesture might become a drag-tear), the service creates a *second* full-screen transparent overlay (`MATCH_PARENT × MATCH_PARENT`, `FLAG_NOT_FOCUSABLE`) that captures the rest of the drag and detects the threshold crossing. On finger-up — whether the gesture completed a dismiss or not — the transient window is removed. This is the same pattern Messenger and Bubbles API use internally.

The transient window only exists for the lifetime of an active drag (typically <1 s). Pre-creating it and toggling visibility is *not* equivalent — the transparent full-screen window steals touches from the underlying app for as long as it exists, and we don't want that to be the steady state.

Existing `BubbleVisibilityRules.draggedOffEdge` is unaffected — it triggers on intentional drags that exit the persistent window during reposition, not on the new drag-tear (which happens inside the transient window).

### Foreground service type policy

The crucial change for the AlwaysVisible bug:

```kotlin
// In BubbleOverlayService — pseudocode
private fun setForegroundType(recording: Boolean) {
    val notification = buildBubbleNotification()
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            val type = if (recording) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            // Android 10–13 also accepts the type bitmask; combined types are
            // permitted regardless of mic-active state on these versions.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
        else -> {
            // Pre-Android 10 has no per-call type argument.
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
```

Called once on `startForegroundIfNeeded()` with `recording = false`, and again from the state machine observer on enter/exit Recording.

## Settings UI

Bubble page sections (top to bottom):

1. Overlay permission card (existing).
2. Accessibility upgrade nudge (existing, gated by `shouldShowAccessibilityNudge`).
3. Accessibility status row (existing).
4. **Bubble visibility** — radio: AlwaysVisible / SummonedOnly / Disabled (existing).
5. **Edge side** — radio: Right / Left (new).
6. **Cooldown after dismiss** — radio: 1 min / 5 min / 15 min / 1 hour / Until next app launch (new).

The existing visibility-mode radio's `onSelect` callback that calls `onStartBubbleService()` keeps its current behavior. The bug fix in §"Foreground service type policy" is what makes that start actually attach the overlay on Android 14+.

## Migration / behavior changes for existing users

- Users on v1.0.0 who somehow saw the bubble are upgraded to the sliver design — no migration data needed because the new fields default cleanly.
- The persisted `bubble_x` / `bubble_y` from v1.0.0 are **discarded** (we only need vertical position now; the edge picks horizontal).
- Visibility-mode persistence (`bubble_visibility_mode`) carries over verbatim.

## Testing

- `BubbleCooldownTest` (new): pure clock injection. Cases:
  - `dismissedAt == null` → never active.
  - `now < dismissedAt + cooldownMs` → active.
  - `now >= dismissedAt + cooldownMs` → inactive.
  - `cooldownMs == Long.MAX_VALUE` → active for any reasonable now.
- `BubbleVisibilityRulesTest` (extended):
  - `cooldownActive = true` → hidden, regardless of mode.
  - `cooldownActive = true` + `mode = Disabled` → still hidden (Disabled wins early; cooldown isn't reached).
  - Existing IME-up anti-regression test stays.
- `BubbleStateMachineTest` (extended):
  - `Idle + DragTearComplete` → `Dismissed`.
  - `Recording + DragTearComplete` → `Recording` (no transition; emits no effect).
  - `Processing + DragTearComplete` → `Processing` (no transition).
  - `Result + DragTearComplete` → `Result`.
  - `Dismissed + CooldownElapsed` → `Idle`.
  - `Peeked + PeekTimeout` → `Idle`.
- `BubbleSettingsRepositoryTest` (extended): edge / cooldownMs / dismissedAt round-trip; defaults.
- New JVM unit tests for the drag-tear classifier (pure function: `(startX, currentX, screenWidth, edge) -> isDismissAttempt`).
- Existing `BubbleSettingsRepositoryTest` for visibility / position / use-count / nudge-dismissed stays.

Compose-side rendering is verified manually on device; no instrumentation tests planned for v1.

## Open questions / follow-ups (out of scope here)

- The AlwaysVisible mode currently has the user re-tap the already-selected radio button to trigger `onStartBubbleService()` after granting overlay permission. Worth a separate UX pass: auto-fire `onStartBubbleService()` when `overlayPermissionGranted` flips from false to true while AlwaysVisible is the persisted mode.
- The summoned-sheet "outside-tap closes" behavior interacts with `FLAG_NOT_FOCUSABLE` in subtle ways; may need a dedicated `FLAG_WATCH_OUTSIDE_TOUCH` listener on a temporary second window during Peeked.
- Result-ready 3 s green decay is tied to wall-clock time; if the user backgrounds the app during the decay, the sliver should still go back to idle on resume.
