# Bubble edge-sliver redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the v1.0.0 floating-circle bubble with an 8 dp edge sliver + summoned action sheet; add drag-tear dismiss with configurable cooldown; fix the AlwaysVisible-doesn't-show bug as part of the same work.

**Architecture:** Two-window overlay pattern (narrow persistent sliver window + transient full-screen drag-tracker window). State indication moves from bubble geometry to sliver color/pulse. New pure-logic modules (`BubbleCooldown`, drag-tear classifier) keep visibility decisions JVM-testable. Foreground-service-type fix toggles `MICROPHONE` only while actively recording.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX DataStore, Material 3, Robolectric (already wired for Room tests), JUnit 4. No new dependencies.

**Reference spec:** `docs/superpowers/specs/2026-05-06-bubble-edge-sliver-redesign-design.md`

**Build commands (Windows + Git Bash; ANDROID_HOME is set):**
- Compile: `./gradlew :app:compileDebugKotlin`
- Test: `./gradlew :app:testDebugUnitTest`
- Build APK: `./gradlew :app:assembleDebug`

Do **not** use `-Pandroid.aapt2FromMavenOverride=...` (NixOS-only).

---

## Task 1: BubbleCooldown pure-logic primitive

**Why first:** The cooldown calculation is referenced by the visibility rules (Task 5), the state machine (Task 4), and the settings repository (Task 3). Landing the pure type first means everything else can depend on a stable contract.

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/bubble/BubbleCooldown.kt`
- Create: `app/src/test/kotlin/com/whisperboard/bubble/BubbleCooldownTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/whisperboard/bubble/BubbleCooldownTest.kt`:

```kotlin
package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleCooldownTest {

    @Test
    fun `inactive when never dismissed`() {
        val c = BubbleCooldown(dismissedAt = null, cooldownMs = 5 * 60_000L)
        assertFalse(c.isActive(now = 1_000_000L))
    }

    @Test
    fun `active when within cooldown window`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertTrue(c.isActive(now = 3_000L))
    }

    @Test
    fun `inactive when cooldown elapsed`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertFalse(c.isActive(now = 6_000L))
    }

    @Test
    fun `inactive exactly at cooldown end`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertFalse(c.isActive(now = 6_000L))
    }

    @Test
    fun `MAX_VALUE cooldown stays active for any reasonable now`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = Long.MAX_VALUE)
        assertTrue(c.isActive(now = 1_000_000_000L))
    }

    @Test
    fun `zero cooldown immediately inactive`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 0L)
        assertFalse(c.isActive(now = 1_000L))
    }
}
```

- [ ] **Step 2: Run the tests; verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleCooldownTest"`
Expected: compilation failure ("unresolved reference: BubbleCooldown").

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/kotlin/com/whisperboard/bubble/BubbleCooldown.kt`:

```kotlin
package com.whisperboard.bubble

/**
 * Pure cooldown calculation. The bubble is hidden for [cooldownMs] after
 * [dismissedAt], then auto-returns. Lives in its own file so its tests
 * don't pull in the rest of the bubble package.
 *
 * - [dismissedAt] = null means never dismissed; cooldown is never active.
 * - [cooldownMs] == Long.MAX_VALUE means "Until next time the user opens
 *   the app" — always active until external code clears [dismissedAt].
 *
 * The "now" parameter is injected so tests don't need a clock mock; the
 * service passes [System.currentTimeMillis] in production.
 */
data class BubbleCooldown(
    val dismissedAt: Long?,
    val cooldownMs: Long,
) {
    fun isActive(now: Long): Boolean {
        val start = dismissedAt ?: return false
        if (cooldownMs == Long.MAX_VALUE) return true
        return now < start + cooldownMs
    }
}
```

- [ ] **Step 4: Run tests; verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleCooldownTest"`
Expected: 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleCooldown.kt \
        app/src/test/kotlin/com/whisperboard/bubble/BubbleCooldownTest.kt
git commit -m "introduce BubbleCooldown pure primitive for dismiss-with-cooldown"
```

---

## Task 2: DragTear classifier pure function

**Why next:** The state machine (Task 4) needs a way to decide "did this drag pattern cross the dismiss threshold?". Keeping it as a pure function means the state machine stays JVM-testable and we can unit-test the threshold logic without a touch-event harness.

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/bubble/DragTear.kt`
- Create: `app/src/test/kotlin/com/whisperboard/bubble/DragTearTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/whisperboard/bubble/DragTearTest.kt`:

```kotlin
package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DragTearTest {

    @Test
    fun `right-edge drag past 30 percent inward is a dismiss`() {
        // Right edge = startX near right; inward = decreasing x.
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f - (1080f * 0.31f),
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertTrue(classified)
    }

    @Test
    fun `right-edge drag of 29 percent is not a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f - (1080f * 0.29f),
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertFalse(classified)
    }

    @Test
    fun `right-edge drag in the wrong direction is not a dismiss`() {
        // Drag goes outward (off screen) — that's the existing draggedOffEdge,
        // not a dismiss-tear.
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f + 200f,
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertFalse(classified)
    }

    @Test
    fun `left-edge drag past 30 percent inward is a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = 1080f * 0.31f,
            screenWidth = 1080,
            edge = Edge.LEFT,
        )
        assertTrue(classified)
    }

    @Test
    fun `left-edge drag in the wrong direction is not a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = -200f,
            screenWidth = 1080,
            edge = Edge.LEFT,
        )
        assertFalse(classified)
    }

    @Test
    fun `zero or negative screenWidth never classifies`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = 100f,
            screenWidth = 0,
            edge = Edge.LEFT,
        )
        assertFalse(classified)
    }
}
```

- [ ] **Step 2: Run the tests; verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.DragTearTest"`
Expected: compilation failure ("unresolved reference: DragTear, Edge").

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/whisperboard/bubble/DragTear.kt`:

```kotlin
package com.whisperboard.bubble

/**
 * Which screen edge the bubble sliver lives on. Persisted in
 * [BubbleSettingsRepository]; consumed by the overlay service to anchor
 * the window and by [DragTear] to interpret gesture direction.
 */
enum class Edge { LEFT, RIGHT }

/**
 * Pure gesture classifier. The user dismisses the sliver by dragging it
 * perpendicular to the edge (away from the edge, across the screen). If
 * the perpendicular travel passes [DISMISS_FRACTION] of the screen width,
 * the gesture is a dismiss.
 *
 * The function is intentionally framework-free: only Float / Int / enum
 * inputs, so it runs as a JVM unit test.
 */
object DragTear {

    /**
     * Threshold for "dragged far enough to dismiss". 30% of screen width
     * is high enough to avoid accidental dismisses but low enough to feel
     * responsive on a 360 dp phone (~108 dp on 1080-px width).
     */
    const val DISMISS_FRACTION: Float = 0.30f

    fun isDismissAttempt(
        startX: Float,
        currentX: Float,
        screenWidth: Int,
        edge: Edge,
    ): Boolean {
        if (screenWidth <= 0) return false
        val travel = when (edge) {
            Edge.RIGHT -> startX - currentX // inward = decreasing x
            Edge.LEFT -> currentX - startX // inward = increasing x
        }
        if (travel <= 0f) return false
        return travel >= screenWidth * DISMISS_FRACTION
    }
}
```

- [ ] **Step 4: Run tests; verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.DragTearTest"`
Expected: 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/DragTear.kt \
        app/src/test/kotlin/com/whisperboard/bubble/DragTearTest.kt
git commit -m "introduce Edge enum and DragTear pure classifier for dismiss gesture"
```

---

## Task 3: BubbleSettingsRepository — edge / cooldownMs / dismissedAt

**Why next:** The state machine (Task 4) and visibility rules (Task 5) read the cooldown duration and dismiss timestamp from this repository. Service window placement (Task 10) reads the edge.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/bubble/BubbleSettingsRepository.kt` (add `edge`, `cooldownMs`, `dismissedAt` fields + setters)
- Modify: `app/src/test/kotlin/com/whisperboard/bubble/BubbleSettingsRepositoryTest.kt` (add round-trip tests for new fields)

- [ ] **Step 1: Write the failing tests**

Add the following new tests to the bottom of `BubbleSettingsRepositoryTest.kt` (do not delete existing tests):

```kotlin
    @Test
    fun `edge defaults to RIGHT when unset`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(Edge.RIGHT, repo.edge.first())
    }

    @Test
    fun `setEdge persists and reads back`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setEdge(Edge.LEFT)
        assertEquals(Edge.LEFT, repo.edge.first())
    }

    @Test
    fun `cooldownMs defaults to 5 minutes`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(5L * 60_000L, repo.cooldownMs.first())
    }

    @Test
    fun `setCooldownMs persists and reads back`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setCooldownMs(15L * 60_000L)
        assertEquals(15L * 60_000L, repo.cooldownMs.first())
    }

    @Test
    fun `dismissedAt defaults to null`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(null, repo.dismissedAt.first())
    }

    @Test
    fun `setDismissedAt persists and clearDismissedAt removes`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setDismissedAt(123_456L)
        assertEquals(123_456L, repo.dismissedAt.first())
        repo.clearDismissedAt()
        assertEquals(null, repo.dismissedAt.first())
    }
```

If `runTest` and `first()` aren't already imported in the file, add (next to the existing imports):

```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
```

The `testStore()` helper already exists in this test file (used by existing tests). If not, copy this helper next to the existing tests:

```kotlin
private fun testStore(): DataStore<Preferences> {
    // Existing helper pattern in this test file — reuse it. If absent,
    // copy from BehaviorSettingsRepositoryTest.kt:testStore() which uses
    // PreferenceDataStoreFactory.create with a temp file.
}
```

- [ ] **Step 2: Run the tests; verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleSettingsRepositoryTest"`
Expected: compilation failures on `Edge`, `repo.edge`, `repo.cooldownMs`, `repo.dismissedAt`, `repo.setEdge`, `repo.setCooldownMs`, `repo.setDismissedAt`, `repo.clearDismissedAt`.

- [ ] **Step 3: Add the new fields to the repository**

Edit `app/src/main/kotlin/com/whisperboard/bubble/BubbleSettingsRepository.kt`. Inside the existing `companion object`, add the new keys and default below the existing keys:

```kotlin
        private val KEY_EDGE = stringPreferencesKey("bubble_edge")
        private val KEY_COOLDOWN_MS = androidx.datastore.preferences.core.longPreferencesKey("bubble_cooldown_ms")
        private val KEY_DISMISSED_AT = androidx.datastore.preferences.core.longPreferencesKey("bubble_dismissed_at")

        val DEFAULT_EDGE: Edge = Edge.RIGHT

        /** Default cooldown after dismiss: 5 minutes. */
        const val DEFAULT_COOLDOWN_MS: Long = 5L * 60_000L
```

Below the existing `setAccessibilityNudgeDismissed` function, add (inside the class):

```kotlin
    val edge: Flow<Edge> = dataStore.data.map { prefs ->
        prefs[KEY_EDGE]?.let { stored ->
            runCatching { Edge.valueOf(stored) }.getOrNull()
        } ?: DEFAULT_EDGE
    }

    suspend fun setEdge(edge: Edge) {
        dataStore.edit { it[KEY_EDGE] = edge.name }
    }

    val cooldownMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_COOLDOWN_MS] ?: DEFAULT_COOLDOWN_MS
    }

    suspend fun setCooldownMs(ms: Long) {
        dataStore.edit { it[KEY_COOLDOWN_MS] = ms }
    }

    val dismissedAt: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISSED_AT]
    }

    suspend fun setDismissedAt(now: Long) {
        dataStore.edit { it[KEY_DISMISSED_AT] = now }
    }

    suspend fun clearDismissedAt() {
        dataStore.edit { it.remove(KEY_DISMISSED_AT) }
    }
```

- [ ] **Step 4: Run tests; verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleSettingsRepositoryTest"`
Expected: all tests (existing + 6 new) pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleSettingsRepository.kt \
        app/src/test/kotlin/com/whisperboard/bubble/BubbleSettingsRepositoryTest.kt
git commit -m "extend BubbleSettingsRepository with edge, cooldownMs, dismissedAt"
```

---

## Task 4: BubbleStateMachine — Dismissed and Peeked states

**Why next:** Wires user gestures to the new states. The visibility rules (Task 5) and the service drag handling (Task 10) both depend on the state-machine surface.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/bubble/BubbleStateMachine.kt`
- Modify: `app/src/test/kotlin/com/whisperboard/bubble/BubbleStateMachineTest.kt`

**Background — read first:** Open `BubbleStateMachine.kt`. The existing states are `Idle`, `Recording(pushToTalk: Boolean)`, `Processing`, `Result(text: String)` — all under `sealed interface BubbleState`. Existing events include `Tap`, `LongPressStart`, `LongPressEnd`, `TranscriptReady(text)`, `Dismiss`, `Error(reason)`. Note: there is **no** `BubbleState.Disabled` — the "disabled" concept lives in `BubbleVisibilityMode.Disabled` (a service-level visibility setting, not a state-machine state). The existing `BubbleEvent.Dismiss` collapses Result back to Idle — that semantic stays. The new `DragTearComplete` is a different event with different meaning.

`handle(event)` returns a `BubbleTransition` (a data class with `state: BubbleState` plus side-effect `effects`). Tests can read either the returned transition's `state` or the machine's current `state` property (`val state: BubbleState get() = currentState`).

- [ ] **Step 1: Write the failing tests**

Add to `BubbleStateMachineTest.kt` (do not delete existing tests):

```kotlin
    @Test
    fun `idle plus DragTearComplete transitions to Dismissed`() {
        val machine = BubbleStateMachine()
        val transition = machine.handle(
            BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L)
        )
        val state = transition.state
        assertTrue("expected Dismissed, was $state", state is BubbleState.Dismissed)
        assertEquals(6_000L, (state as BubbleState.Dismissed).cooldownEndsAt)
    }

    @Test
    fun `recording ignores DragTearComplete`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.LongPressStart)
        val before = machine.state
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        assertEquals(before, machine.state)
    }

    @Test
    fun `processing ignores DragTearComplete`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.LongPressStart)
        machine.handle(BubbleEvent.LongPressEnd)
        val before = machine.state
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        assertEquals(before, machine.state)
    }

    @Test
    fun `dismissed plus CooldownElapsed transitions to Idle`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        val transition = machine.handle(BubbleEvent.CooldownElapsed)
        assertTrue(transition.state is BubbleState.Idle)
    }

    @Test
    fun `idle plus Peek transitions to Peeked`() {
        val machine = BubbleStateMachine()
        val transition = machine.handle(BubbleEvent.Peek)
        assertTrue(transition.state is BubbleState.Peeked)
    }

    @Test
    fun `peeked plus PeekTimeout transitions to Idle`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Peek)
        val transition = machine.handle(BubbleEvent.PeekTimeout)
        assertTrue(transition.state is BubbleState.Idle)
    }

    @Test
    fun `peeked plus LongPressStart transitions to Recording`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Peek)
        val transition = machine.handle(BubbleEvent.LongPressStart)
        assertTrue(transition.state is BubbleState.Recording)
    }
```

- [ ] **Step 2: Run the tests; verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleStateMachineTest"`
Expected: compilation failures on `BubbleEvent.DragTearComplete`, `BubbleEvent.CooldownElapsed`, `BubbleEvent.Peek`, `BubbleEvent.PeekTimeout`, `BubbleState.Dismissed`, `BubbleState.Peeked`.

- [ ] **Step 3: Add new states and events to the state machine**

Edit `app/src/main/kotlin/com/whisperboard/bubble/BubbleStateMachine.kt`:

In the `sealed interface BubbleState` block (or `sealed class`, whichever the file uses), add next to the existing states:

```kotlin
    /** The bubble is dismissed; sliver hidden until [cooldownEndsAt]. */
    data class Dismissed(val cooldownEndsAt: Long) : BubbleState

    /**
     * The user tapped the sliver. Sheet is shown with mic + last result.
     * Auto-collapses to Idle after PeekTimeout (4 s of inactivity in the
     * sheet) or on outside tap.
     */
    data object Peeked : BubbleState
```

In the `sealed interface BubbleEvent` block (or `sealed class`), add:

```kotlin
    /** User completed a drag-tear gesture. Records dismissedAt = now and
     *  computes the cooldown end. */
    data class DragTearComplete(val now: Long, val cooldownMs: Long) : BubbleEvent

    /** Cooldown clock fired and reached `cooldownEndsAt`. */
    data object CooldownElapsed : BubbleEvent

    /** User tapped the sliver to open the sheet (peek). */
    data object Peek : BubbleEvent

    /** The 4-second inactivity timer elapsed while in Peeked. */
    data object PeekTimeout : BubbleEvent
```

In the `handle()` reducer, add the new transitions. Locate the `when (currentState)` (or equivalent) block and add cases. The pattern matches the existing reducer's style — it should be obvious how to integrate. For each case below, add a branch:

```kotlin
            is BubbleState.Idle -> when (event) {
                // ...existing Idle handlers...
                is BubbleEvent.Peek -> updateState(BubbleState.Peeked)
                is BubbleEvent.DragTearComplete ->
                    updateState(BubbleState.Dismissed(cooldownEndsAt = event.now + event.cooldownMs))
                else -> Unit
            }
            is BubbleState.Peeked -> when (event) {
                is BubbleEvent.PeekTimeout -> updateState(BubbleState.Idle)
                is BubbleEvent.LongPressStart -> updateState(BubbleState.Recording)
                else -> Unit
            }
            is BubbleState.Dismissed -> when (event) {
                is BubbleEvent.CooldownElapsed -> updateState(BubbleState.Idle)
                else -> Unit
            }
            is BubbleState.Recording -> when (event) {
                // ...existing Recording handlers...
                is BubbleEvent.DragTearComplete -> Unit // explicit drop
                else -> Unit
            }
            // ...same explicit drop in Processing and Result branches...
```

Adapt to the existing reducer's exact style — preserve all existing transitions for `Idle`, `Recording`, `Processing`, `Result`. The `else -> Unit` ensures unknown events are dropped silently.

- [ ] **Step 4: Run tests; verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleStateMachineTest"`
Expected: all tests (existing + 7 new) pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleStateMachine.kt \
        app/src/test/kotlin/com/whisperboard/bubble/BubbleStateMachineTest.kt
git commit -m "extend BubbleStateMachine with Dismissed and Peeked states"
```

---

## Task 5: BubbleVisibilityRules — cooldown input

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/bubble/BubbleVisibilityRules.kt`
- Modify: `app/src/test/kotlin/com/whisperboard/bubble/BubbleVisibilityRulesTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `BubbleVisibilityRulesTest.kt`:

```kotlin
    @Test
    fun `cooldown active hides AlwaysVisible`() {
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.AlwaysVisible,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = true,
            )
        )
        assertFalse(visible)
    }

    @Test
    fun `cooldown active does not override Disabled`() {
        // Disabled is checked first; cooldown evaluation is moot.
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.Disabled,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = true,
            )
        )
        assertFalse(visible)
    }

    @Test
    fun `cooldown inactive plus AlwaysVisible is visible`() {
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.AlwaysVisible,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = false,
            )
        )
        assertTrue(visible)
    }
```

- [ ] **Step 2: Run the tests; verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleVisibilityRulesTest"`
Expected: compilation failure on `cooldownActive` parameter not present.

- [ ] **Step 3: Add the input and rule**

Edit `app/src/main/kotlin/com/whisperboard/bubble/BubbleVisibilityRules.kt`. Add the field to `BubbleVisibilityInputs`:

```kotlin
data class BubbleVisibilityInputs(
    val mode: BubbleVisibilityMode,
    val onLockscreen: Boolean,
    val foregroundAppIsFullscreen: Boolean,
    val draggedOffEdge: Boolean,
    val imeVisible: Boolean,
    val isSummoned: Boolean = false,
    /** True iff the dismiss cooldown is currently active.
     *  See [BubbleCooldown] for the calculation. */
    val cooldownActive: Boolean = false,
)
```

In `BubbleVisibilityRules.shouldBeVisible`, add the cooldown check just after the Disabled check and before the Lockscreen check:

```kotlin
    fun shouldBeVisible(inputs: BubbleVisibilityInputs): Boolean {
        if (inputs.mode == BubbleVisibilityMode.Disabled) return false
        if (inputs.cooldownActive) return false
        if (inputs.onLockscreen) return false
        if (inputs.foregroundAppIsFullscreen) return false
        if (inputs.draggedOffEdge) return false
        return when (inputs.mode) {
            BubbleVisibilityMode.AlwaysVisible -> true
            BubbleVisibilityMode.SummonedOnly -> inputs.isSummoned
            BubbleVisibilityMode.Disabled -> false
        }
    }
```

Update the docstring above the rules list (rules 1–6) to include rule 2 (cooldown) and renumber.

- [ ] **Step 4: Run tests; verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.whisperboard.bubble.BubbleVisibilityRulesTest"`
Expected: all tests (existing + 3 new) pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleVisibilityRules.kt \
        app/src/test/kotlin/com/whisperboard/bubble/BubbleVisibilityRulesTest.kt
git commit -m "add cooldownActive input to BubbleVisibilityRules"
```

---

## Task 6: Foreground service type policy fix

**Why now:** Independent of all UI changes, this fixes the AlwaysVisible bug. Lands as soon as we have it. After this task, even with the v1.0.0 UI, AlwaysVisible should attach successfully on Android 14+.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/bubble/BubbleOverlayService.kt`

This task is verified manually on device — JVM unit tests can't exercise `startForeground`.

- [ ] **Step 1: Read the existing `startForegroundIfNeeded()` function**

Open `app/src/main/kotlin/com/whisperboard/bubble/BubbleOverlayService.kt` and locate `startForegroundIfNeeded()` (around line 305). Note the existing branches for `UPSIDE_DOWN_CAKE`, `Q`, and pre-Q.

- [ ] **Step 2: Extract `setForegroundType(recording: Boolean)`**

Replace the body of `startForegroundIfNeeded()` (and any other call site that calls `startForeground` directly) with calls to a new private function:

```kotlin
    private fun startForegroundIfNeeded() {
        ensureNotificationChannel()
        setForegroundType(recording = false)
    }

    private fun setForegroundType(recording: Boolean) {
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Whisper Board")
            .setContentText("Bubble is active")
            .setOngoing(true)
            .build()

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
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }
```

Adapt the notification builder to match the existing one verbatim — copy the existing fields (icon, title, text, etc.). The change is the *type bitmask*, not the notification.

- [ ] **Step 3: Wire `setForegroundType(true/false)` to recording state changes**

Find the existing state-machine observer in `BubbleOverlayService.kt` (look for where `BubbleState.Recording` is observed; it's likely in an `observeState` or similar function). Add a side effect on enter/leave Recording:

```kotlin
    private fun observeRecordingForegroundType() {
        serviceScope.launch {
            stateMachine.state
                .map { it is BubbleState.Recording }
                .distinctUntilChanged()
                .collect { isRecording ->
                    setForegroundType(recording = isRecording)
                }
        }
    }
```

Call `observeRecordingForegroundType()` from `onCreate()` after `startForegroundIfNeeded()`.

- [ ] **Step 4: Build and verify on device**

Build: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual verification (device required):
1. Install the new APK.
2. Open the app, complete onboarding.
3. Settings → Bubble → grant overlay permission → tap AlwaysVisible.
4. **Expected:** the existing v1.0.0 64 dp circle bubble appears on screen.
5. Long-press the bubble to record. Release. **Expected:** no crash, no foreground service exception in logcat.

If logcat shows `ForegroundServiceStartNotAllowedException` or `MissingForegroundServiceTypeException`, the fix is wrong — investigate which state path fired and adjust.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleOverlayService.kt
git commit -m "fix AlwaysVisible: use SPECIAL_USE only at idle, MICROPHONE while recording"
```

---

## Task 7: EdgeSliver composable

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/bubble/EdgeSliver.kt`

Compose-side rendering is verified manually on device per the spec. No JVM tests for this task.

- [ ] **Step 1: Define `SliverState` and `EdgeSliver`**

Create `app/src/main/kotlin/com/whisperboard/bubble/EdgeSliver.kt`:

```kotlin
package com.whisperboard.bubble

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Visual state of the [EdgeSliver]. Color and pulse are picked from this
 * enum; the consumer (BubbleView) maps the bubble's [BubbleState] to a
 * SliverColor.
 */
enum class SliverColor { IdleBlue, RecordingRed, ProcessingAmber, ResultGreen, Hidden }

/** Width of the visible sliver — see spec §1 (Idle state). */
val SLIVER_WIDTH = 8.dp

/** Vertical length of the sliver. ~48 dp matches a Material Design FAB
 *  small rail tab — long enough to see, short enough to not dominate. */
val SLIVER_HEIGHT = 48.dp

/**
 * The 8 dp visible edge sliver. Touch handling is the caller's job — this
 * composable only renders. The caller wraps it in a Modifier that adds the
 * 24 dp invisible touch margin and the gesture-detection pointerInput.
 */
@Composable
fun EdgeSliver(
    color: SliverColor,
    edge: Edge,
    modifier: Modifier = Modifier,
) {
    if (color == SliverColor.Hidden) return

    val pulse = if (color == SliverColor.RecordingRed) {
        val transition = rememberInfiniteTransition(label = "sliver-pulse")
        transition.animateFloatAsState(
            initialValue = 0.95f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sliver-pulse-scale",
        ).value
    } else 1f

    val tint = when (color) {
        SliverColor.IdleBlue -> Color(0x9938A3FF)        // ~60% alpha primary
        SliverColor.RecordingRed -> Color(0xFFE53935)
        SliverColor.ProcessingAmber -> Color(0xFFFFB300)
        SliverColor.ResultGreen -> Color(0xFF43A047)
        SliverColor.Hidden -> Color.Transparent
    }

    val cornerShape = when (edge) {
        Edge.RIGHT -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
        Edge.LEFT -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    }

    Box(
        modifier = modifier
            .width(SLIVER_WIDTH)
            .height(SLIVER_HEIGHT)
            .scale(pulse)
            .clip(cornerShape)
            .background(tint),
    )
}
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/EdgeSliver.kt
git commit -m "introduce EdgeSliver composable with state-driven color and pulse"
```

---

## Task 8: SummonedSheet composable

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/bubble/SummonedSheet.kt`

- [ ] **Step 1: Implement the composable**

Create `app/src/main/kotlin/com/whisperboard/bubble/SummonedSheet.kt`:

```kotlin
package com.whisperboard.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whisperboard.R

/**
 * The action sheet that slides out when the user taps the sliver.
 * Anchored visually to the sliver's edge by [edge]. Contains:
 *  - a mic button (32 dp); long-press to record from inside the sheet;
 *  - a one-line preview of the most recent dictation entry, plus
 *    re-insert and copy affordances.
 *
 * The sheet's overall width is bounded so it never takes more than ~50%
 * of a 360 dp phone width.
 */
@Composable
fun SummonedSheet(
    lastResultText: String?,
    edge: Edge,
    onMicLongPress: () -> Unit,
    onReinsert: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetCorners = when (edge) {
        Edge.RIGHT -> RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
        Edge.LEFT -> RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
    }

    Surface(
        modifier = modifier.widthIn(min = 160.dp, max = 220.dp),
        shape = sheetCorners,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_mic),
                        contentDescription = "Long-press to record",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Hold to dictate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }

            if (lastResultText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastResultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onReinsert) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_mic),
                            contentDescription = "Re-insert into focused field",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onCopy) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_mic),
                            contentDescription = "Copy to clipboard",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
```

(All three icon buttons use `R.drawable.ic_mic` because that's the only relevant drawable currently in `app/src/main/res/drawable/`. This is a deliberate placeholder for v1 of this redesign — text labels under the buttons disambiguate. File a follow-up issue "add ic_redo and ic_copy drawables to bubble sheet" before merging if you want; do **not** block on it.)

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/SummonedSheet.kt
git commit -m "introduce SummonedSheet composable for the bubble's tap-to-peek panel"
```

---

## Task 9: BubbleView rewrite

**Files:**
- Modify (rewrite): `app/src/main/kotlin/com/whisperboard/bubble/BubbleView.kt`

This is the largest task. Read the existing file completely before starting — there are gesture handlers (`detectDragGestures`, `awaitEachGesture`) and pulse animations that are being replaced wholesale. Tests for BubbleView don't exist (it's pure UI); verification is on-device.

- [ ] **Step 1: Replace BubbleView with the orchestrator**

Open `app/src/main/kotlin/com/whisperboard/bubble/BubbleView.kt` and replace the entire file with:

```kotlin
package com.whisperboard.bubble

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Top-level Compose root for the bubble overlay window. Decides whether
 * to render [EdgeSliver] alone, [SummonedSheet] anchored next to it, or
 * neither (when in [BubbleState.Dismissed] cooldown — but the service
 * detaches the window entirely in that case, so this branch is defensive).
 *
 * Touch handling: a 24 dp invisible band along the edge is the gesture
 * detection zone. The actual gesture math (long-press vs tap, drag-tear
 * threshold) lives in the service — this composable forwards raw pointer
 * events upward.
 */
@Composable
fun BubbleView(
    state: BubbleState,
    edge: Edge,
    lastResultText: String?,
    waveformAmplitude: Float,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDragHorizontal: (Float, Float) -> Unit, // (startX, currentX); service classifies
    onDragVertical: (Float) -> Unit, // dy for reposition
    onDragEnd: () -> Unit,
    onPeekTimeout: () -> Unit,
    onReinsert: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliverColor = when (state) {
        is BubbleState.Idle -> SliverColor.IdleBlue
        is BubbleState.Recording -> SliverColor.RecordingRed
        is BubbleState.Processing -> SliverColor.ProcessingAmber
        is BubbleState.Result -> SliverColor.ResultGreen
        is BubbleState.Peeked -> SliverColor.IdleBlue
        is BubbleState.Dismissed -> SliverColor.Hidden
    }

    // 4-second peek timeout while in Peeked.
    if (state is BubbleState.Peeked) {
        LaunchedEffect(state) {
            delay(4_000L)
            onPeekTimeout()
        }
    }

    val sheetVisible = state is BubbleState.Peeked ||
        state is BubbleState.Recording ||
        state is BubbleState.Processing ||
        state is BubbleState.Result

    Box(modifier = modifier) {
        when (edge) {
            Edge.RIGHT -> Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 0.dp),
            ) {
                if (sheetVisible) {
                    SummonedSheet(
                        lastResultText = lastResultText,
                        edge = edge,
                        onMicLongPress = onLongPressStart,
                        onReinsert = onReinsert,
                        onCopy = onCopy,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 24.dp) // invisible touch margin
                        .pointerInput(state, edge) {
                            // Tap / long-press disambiguation
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downAt = System.currentTimeMillis()
                                val up = waitForUpOrCancellation()
                                val elapsed = System.currentTimeMillis() - downAt
                                if (up != null) {
                                    if (elapsed >= 200L) {
                                        onLongPressStart()
                                        onLongPressEnd()
                                    } else {
                                        onTap()
                                    }
                                }
                            }
                        }
                        .pointerInput(state, edge) {
                            // Drag detection — service decides classification.
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onDragHorizontal(offset.x, offset.x)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragHorizontal(change.previousPosition.x, change.position.x)
                                    onDragVertical(dragAmount.y)
                                },
                            )
                        },
                ) {
                    EdgeSliver(color = sliverColor, edge = edge)
                }
            }
            Edge.LEFT -> Row(
                modifier = Modifier
                    .align(Alignment.CenterStart),
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 24.dp)
                        .pointerInput(state, edge) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downAt = System.currentTimeMillis()
                                val up = waitForUpOrCancellation()
                                val elapsed = System.currentTimeMillis() - downAt
                                if (up != null) {
                                    if (elapsed >= 200L) {
                                        onLongPressStart()
                                        onLongPressEnd()
                                    } else {
                                        onTap()
                                    }
                                }
                            }
                        }
                        .pointerInput(state, edge) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onDragHorizontal(offset.x, offset.x)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragHorizontal(change.previousPosition.x, change.position.x)
                                    onDragVertical(dragAmount.y)
                                },
                            )
                        },
                ) {
                    EdgeSliver(color = sliverColor, edge = edge)
                }
                if (sheetVisible) {
                    SummonedSheet(
                        lastResultText = lastResultText,
                        edge = edge,
                        onMicLongPress = onLongPressStart,
                        onReinsert = onReinsert,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}
```

(Imports for `Row`, `awaitEachGesture`, `awaitFirstDown`, `waitForUpOrCancellation`, `detectDragGestures`, `androidx.compose.foundation.gestures.*` may need to be added — Compose's IDE picker handles these; copy from the original BubbleView file if porting is faster than re-typing.)

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If you get unresolved references for `BubbleState.Disabled`, the existing state machine may use a different name — match it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleView.kt
git commit -m "rewrite BubbleView as edge-sliver + summoned-sheet orchestrator"
```

---

## Task 10: BubbleOverlayService — window management + drag-tracker + cooldown wiring

This is the second-largest task. Read `BubbleOverlayService.kt` completely before starting. Substantive changes in three areas: persistent window LayoutParams, transient drag-tracker window lifecycle, and connecting the cooldown flow to `BubbleVisibilityRules`.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/bubble/BubbleOverlayService.kt`

- [ ] **Step 0: Migration note for v1.0.0 persisted position**

The spec discards v1.0.0's `bubble_x` / `bubble_y` keys. **Do not delete the `BubbleSettingsRepository.position` API** — it stays for backward compat. Instead:

- The service still calls `bubbleSettings.setPosition(x, y)` from the existing reposition path; pass `x = 0` always (the edge picks horizontal).
- The service reads `bubbleSettings.position.first().y` for the window y offset; ignore `.x`.

The `bubble_x` key in DataStore goes stale (always written 0; never read meaningfully). DataStore doesn't grow unbounded from this — single Int prefs are tiny.

- [ ] **Step 1: Change persistent window LayoutParams**

Locate `buildOverlayParams(x: Int, y: Int)` (around line 406). Replace with:

```kotlin
    private fun buildOverlayParams(yOffset: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val edge = runBlocking { bubbleSettings.edge.first() } // OK on Main thread; emit is hot
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (edge) {
                Edge.RIGHT -> Gravity.TOP or Gravity.END
                Edge.LEFT -> Gravity.TOP or Gravity.START
            }
            x = 0
            y = yOffset
        }
    }
```

Update all call sites of `buildOverlayParams(DEFAULT_X, DEFAULT_Y)` to `buildOverlayParams(DEFAULT_Y)`. Remove the `DEFAULT_X` constant; it's no longer used.

- [ ] **Step 2: Add the transient drag-tracker window**

Inside the service, add:

```kotlin
    private var dragTrackerView: View? = null

    private fun ensureDragTracker() {
        if (dragTrackerView != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val view = ComposeBubbleView(this) {
            // Compose root that listens for drag and reports back to the
            // service. On drag-tear classification firing, the service
            // emits BubbleEvent.DragTearComplete and tears down this view.
            DragTrackerScrim(
                onMove = ::handleDragTrackerMove,
                onUp = ::handleDragTrackerUp,
            )
        }
        windowManager.addView(view, params)
        dragTrackerView = view
    }

    private fun teardownDragTracker() {
        val view = dragTrackerView ?: return
        runCatching { windowManager.removeView(view) }
        dragTrackerView = null
    }

    private fun handleDragTrackerMove(startX: Float, currentX: Float) {
        val width = resources.displayMetrics.widthPixels
        val edge = runBlocking { bubbleSettings.edge.first() }
        if (DragTear.isDismissAttempt(startX, currentX, width, edge)) {
            val now = System.currentTimeMillis()
            val cooldownMs = runBlocking { bubbleSettings.cooldownMs.first() }
            stateMachine.handle(BubbleEvent.DragTearComplete(now, cooldownMs))
            serviceScope.launch { bubbleSettings.setDismissedAt(now) }
            teardownDragTracker()
        }
    }

    private fun handleDragTrackerUp() {
        teardownDragTracker()
    }
```

(`DragTrackerScrim` is a new composable that just consumes pointer events and reports them. Inline it in `BubbleOverlayService.kt` next to the helpers, or put it in a new file `bubble/DragTrackerScrim.kt`. Skeleton:

```kotlin
@Composable
private fun DragTrackerScrim(
    onMove: (Float, Float) -> Unit,
    onUp: () -> Unit,
) {
    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset -> onMove(offset.x, offset.x) },
                onDragEnd = { onUp() },
                onDragCancel = { onUp() },
                onDrag = { change, _ ->
                    change.consume()
                    onMove(change.previousPosition.x, change.position.x)
                },
            )
        }
    )
}
```

)

In the service, call `ensureDragTracker()` from the persistent BubbleView's `onDragHorizontal` callback when the gesture starts, and `teardownDragTracker()` from `onDragEnd`. Adjust the existing `onDrag` plumbing through the BubbleView signature defined in Task 9.

- [ ] **Step 3: Wire the cooldown clock to visibility**

Locate `refreshOverlayVisibility()` (around line 786). Update `BubbleVisibilityInputs` construction to pass `cooldownActive`:

```kotlin
    private fun refreshOverlayVisibility() {
        serviceScope.launch {
            val mode = bubbleSettings.visibilityMode.first()
            val dismissedAt = bubbleSettings.dismissedAt.first()
            val cooldownMs = bubbleSettings.cooldownMs.first()
            val cooldown = BubbleCooldown(dismissedAt, cooldownMs)
            val cooldownActive = cooldown.isActive(System.currentTimeMillis())
            val inputs = BubbleVisibilityInputs(
                mode = mode,
                onLockscreen = visibilityController.isOnLockscreen(),
                foregroundAppIsFullscreen = isFullscreenAppForeground,
                draggedOffEdge = dragOffEdge,
                imeVisible = false,
                isSummoned = visibilityController.isSummoned,
                cooldownActive = cooldownActive,
            )
            val shouldShow = BubbleVisibilityRules.shouldBeVisible(inputs)
            if (shouldShow) attachOverlay() else detachOverlay()
        }
    }
```

Also add a 1-second tick coroutine that re-runs `refreshOverlayVisibility()` while the bubble is in `Dismissed` state. Cancel it when the state leaves Dismissed:

```kotlin
    private var cooldownTickJob: Job? = null

    private fun observeDismissedTick() {
        serviceScope.launch {
            stateMachine.state
                .map { it is BubbleState.Dismissed }
                .distinctUntilChanged()
                .collect { dismissed ->
                    cooldownTickJob?.cancel()
                    if (dismissed) {
                        cooldownTickJob = serviceScope.launch {
                            while (true) {
                                delay(1_000L)
                                val now = System.currentTimeMillis()
                                val dismissedAt = bubbleSettings.dismissedAt.first() ?: break
                                val cooldownMs = bubbleSettings.cooldownMs.first()
                                if (now >= dismissedAt + cooldownMs) {
                                    bubbleSettings.clearDismissedAt()
                                    stateMachine.handle(BubbleEvent.CooldownElapsed)
                                    break
                                }
                                refreshOverlayVisibility()
                            }
                        }
                    }
                }
        }
    }
```

Call `observeDismissedTick()` from `onCreate()` after `observeVisibility()`.

- [ ] **Step 4: Build and compile-check**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If gestures or lifecycle observation has compile errors, adapt to existing function signatures in the service.

- [ ] **Step 5: Manual verification on device**

1. Install the new APK.
2. Settings → Bubble → grant overlay permission → AlwaysVisible.
3. **Expected:** narrow blue sliver at right edge.
4. Tap sliver → sheet slides out with mic + last result.
5. Long-press sliver → recording starts, sheet shows.
6. Drag sliver up/down → repositions vertically; survives a kill-and-relaunch.
7. Drag sliver leftward across the screen → past 30%, releases as dismiss → sliver hidden.
8. Wait 5 minutes (or set cooldown to 1 min in Settings, then wait 1 min) → sliver returns.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/bubble/BubbleOverlayService.kt \
        app/src/main/kotlin/com/whisperboard/bubble/DragTrackerScrim.kt
git commit -m "wire edge-sliver window, drag-tracker, and cooldown clock in service"
```

---

## Task 11: Settings UI — Edge picker + Cooldown picker

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt` (BubblePage section)

- [ ] **Step 1: Extend BubblePage signature**

Add new parameters to `BubblePage`:

```kotlin
@Composable
private fun BubblePage(
    bubbleSettingsRepository: BubbleSettingsRepository,
    overlayPermissionGranted: Boolean,
    accessibilityServiceEnabled: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartBubbleService: () -> Unit,
    onStopBubbleService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val mode by bubbleSettingsRepository.visibilityMode
        .collectAsState(initial = BubbleSettingsRepository.DEFAULT_VISIBILITY)
    val edge by bubbleSettingsRepository.edge
        .collectAsState(initial = BubbleSettingsRepository.DEFAULT_EDGE)
    val cooldownMs by bubbleSettingsRepository.cooldownMs
        .collectAsState(initial = BubbleSettingsRepository.DEFAULT_COOLDOWN_MS)
    // ...existing standalone-use-count, nudge state collection stays...

    Column(...) {
        // ...existing permission card, nudge, accessibility row, visibility radio...

        HorizontalDivider()

        Text(
            text = "Edge side",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Which screen edge the sliver lives on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Edge.entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = entry == edge,
                    onClick = {
                        scope.launch { bubbleSettingsRepository.setEdge(entry) }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (entry) {
                        Edge.RIGHT -> "Right"
                        Edge.LEFT -> "Left"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        HorizontalDivider()

        Text(
            text = "Cooldown after dismiss",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "How long the sliver stays hidden after you drag-tear it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            60_000L to "1 minute",
            5L * 60_000L to "5 minutes",
            15L * 60_000L to "15 minutes",
            60L * 60_000L to "1 hour",
            Long.MAX_VALUE to "Until you open Whisper Board",
        ).forEach { (ms, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = ms == cooldownMs,
                    onClick = {
                        scope.launch { bubbleSettingsRepository.setCooldownMs(ms) }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
```

- [ ] **Step 2: Wire `clearDismissedAt()` on SettingsActivity entry**

Edit `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`. In `onCreate()`, after the existing repository constructions, add:

```kotlin
        val bubbleSettingsRepository = BubbleSettingsRepository(applicationContext)
        // "Until you open Whisper Board" cooldown is cleared whenever the
        // user opens Settings (the only entry point that signals re-engagement).
        lifecycleScope.launch {
            val cd = bubbleSettingsRepository.cooldownMs.first()
            if (cd == Long.MAX_VALUE) {
                bubbleSettingsRepository.clearDismissedAt()
            }
        }
```

(Place this near where `bubbleSettingsRepository` is constructed; if it's already constructed in `setContent`, lift it above.)

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt \
        app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt
git commit -m "add edge and cooldown pickers to Settings -> Bubble page"
```

---

## Task 12: Full on-device verification + final commit

**Files:** none (verification only)

Build a fresh APK with all tasks landed. Walk through the user-facing acceptance criteria from the spec. Capture any issues as follow-up tasks rather than fixing inline (the spec's "Open questions / follow-ups" section is the right home for anything that surfaces).

- [ ] **Step 1: Full clean build + tests**

Run: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (existing + ~20 new across BubbleCooldownTest / DragTearTest / BubbleSettingsRepositoryTest extensions / BubbleStateMachineTest extensions / BubbleVisibilityRulesTest extensions).

- [ ] **Step 2: Install APK and run the acceptance walkthrough**

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Walkthrough (each line is a separate step):

1. Open the app fresh. Onboarding (FirstLaunchPrompt) appears for language profile. Skip or pick languages.
2. Settings → Bubble. Verify the page shows: Permission card, Visibility radio, Edge side radio (Right selected), Cooldown radio (5 min selected).
3. Tap "Open System Settings" → grant *Display over other apps* → press back.
4. Tap AlwaysVisible. **Expected:** narrow blue sliver appears at right edge, vertically near center.
5. Drag the sliver vertically. **Expected:** position follows finger, persists after closing/reopening Settings.
6. Tap the sliver. **Expected:** sheet slides out with mic + (if you've recorded once) last-result row.
7. Long-press the sliver. **Expected:** sliver turns red and pulses; sheet shows recording UI; release commits.
8. After release, sliver turns amber (polishing) then green (3 s) then back to blue.
9. Drag the sliver leftward across ~30% of screen width and release. **Expected:** sliver disappears.
10. Wait 5 minutes (or change cooldown to 1 min in Settings, dismiss again, wait 1 min). **Expected:** sliver returns to its previous y position.
11. Settings → Bubble → set Edge side = Left. **Expected:** sliver moves to left edge immediately.
12. Settings → Bubble → set Cooldown = "Until you open Whisper Board". Dismiss. Without opening Settings, the sliver stays hidden indefinitely (verify by waiting >1 minute). Open Settings: sliver returns.

- [ ] **Step 3: Logcat review**

Run: `adb logcat -d -s BubbleOverlayService:V` and confirm no `ForegroundServiceStartNotAllowedException`, `MissingForegroundServiceTypeException`, or `RemoteException` from `WindowManager.addView`.

- [ ] **Step 4: Final commit (only if any small fixes were needed)**

If the walkthrough surfaced issues that need touching up the implementation, commit those fixes here. Otherwise no commit is needed at this step — the work is complete on the prior task's commits.

```bash
# only if needed
git add <fixed files>
git commit -m "fix bubble redesign issues found during on-device walkthrough"
```

- [ ] **Step 5: PR-ready summary**

The branch should now contain ~12 focused commits. Review with:

```bash
git log --oneline main..HEAD
```

Open a PR titled `Bubble edge-sliver redesign + AlwaysVisible fix` with body referencing the design spec and the v1.0.0 bug context. Include a note that closes any tracked issue if one exists.
