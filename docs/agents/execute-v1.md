# Execute Whisper Board v1

Handoff prompt for a fresh Claude Code session to orchestrate the seven v1 implementation slices via parallel subagents in git worktrees.

Paste everything below the line into a new Claude Code session at the repo root.

---

You are picking up the v1 implementation of **whisper-board** — an Android speech-to-text keyboard becoming a Wispr-Flow-class dictation tool. The planning is done; your job is to **orchestrate execution** of seven implementation slices that have been triaged onto GitHub issues #2–#8, each labeled `ready-for-agent`.

## Step 0 — Read first

Read these in order before doing anything else:

1. `CLAUDE.md` — build instructions and gotchas. Critical ones: NixOS aapt2 override, submodule update after worktree operations, worktree removal needs `rm -rf <path> && git worktree prune` (not `git worktree remove`), DataStore singleton rule, Compose `pointerInput` vs `clickable` rule, IME `Theme.Material.*` (no AppCompat).
2. `CONTEXT.md` — domain glossary. Use these terms exactly in code, commits, PRs.
3. `docs/adr/0001-hybrid-surface-architecture.md` — surface architecture
4. `docs/adr/0002-two-stage-pipeline.md` — pipeline architecture
5. `docs/adr/0003-multilingual-via-profile-and-llm.md` — multilingual decision
6. `docs/agents/issue-tracker.md` — how to use `gh`
7. `docs/agents/triage-labels.md` — label vocabulary

Then run `gh issue list --label ready-for-agent --json number,title --jq '.[] | "\(.number) \(.title)"'` and confirm seven open issues — each has an "Agent Brief" comment that is the durable contract for that slice.

## Step 1 — Confirm workflow with the human

Before spawning any agents, confirm two things:

1. **PR-based or direct-to-main?** `CLAUDE.md` says "solo project — commit and push directly to main." For a coordinated 7-slice rollout, PR-based is safer because merge ordering matters. **Default: open PRs, the human merges in dependency order.** If the human prefers direct-to-main, document that and serialize all seven slices instead of parallelizing — you cannot run parallel direct-to-main agents on the same branch without races.
2. **One wave at a time, or all four?** Default: one wave at a time. Spawn the wave, wait for all PRs to land, merge them in dependency order, then spawn the next wave. The human reviews each wave before progressing.

## Step 2 — The dependency graph

```
        #2 (Slice 1: post-processing pipeline)
         │
        #6 (Slice 6a: settings restructure + auto-insert)
         │
    ┌────┼────┬─────┐
    │    │    │     │
   #3   #4   #5   #8
    │
   #7
```

The shape is **two serial bottlenecks (#2, #6) followed by a 4-way parallel fan-out (#3/#4/#5/#8), then a single trailer (#7)**.

Note: #6 was originally drawn as a peer of #3/#4/#5 in the PRD slice plan, but it owns the Settings page structure that #5 needs to write into. Running them concurrently invites rebase pain. Serializing #6 as Wave 2 alone trades a few days of wall-clock for a much cleaner Wave 3.

## Step 3 — Wave plan

| Wave | Issues | Topology | Rough size |
|---|---|---|---|
| 1 | #2 | sequential | 1–2 days |
| 2 | #6 | sequential | 1 day |
| 3 | #3, #4, #5, #8 | 4-way parallel | 3–7 days (long pole: #4) |
| 4 | #7 | sequential | 1–2 days |

For each wave:

1. Spawn one subagent per issue using the `Agent` tool with `isolation: "worktree"`. Each agent gets a fresh worktree off the current `main`.
2. Wait for all subagents in the wave to complete (do not poll — the runtime notifies on completion).
3. Review their PRs. Merge them in dependency order (within Wave 3 the order doesn't matter; across waves, earlier waves merge first).
4. After Wave 3 merges, **run `git submodule update --init --recursive`** in the orchestrator workspace (per CLAUDE.md gotcha — submodules need refresh after merges that touched submodule pointers).
5. Move to the next wave. Pull the latest `main` so the next wave's worktrees branch off current state.

## Step 4 — Per-subagent prompt template

When spawning a subagent for issue #N, use this prompt as `prompt`. Substitute `<N>`, `<short-slug>`, and any wave-specific notes (e.g., for Wave 3, mention the other parallel slices so the agent knows what NOT to touch).

> You are implementing GitHub issue #<N> in the whisper-board repo. The orchestrator has placed you in a fresh git worktree on a branch named `slice/<short-slug>` off `main`.
>
> **Read in this order:**
>
> 1. The issue body and the **Agent Brief** comment: `gh issue view <N> --comments`. The Agent Brief is your contract.
> 2. `CONTEXT.md` — domain glossary. Use these terms exactly.
> 3. The relevant ADRs in `docs/adr/` (the brief references them).
> 4. `CLAUDE.md` — build commands and gotchas.
>
> **Implement the slice end-to-end.** For every module the brief tags as "deep," write unit tests that exercise external behavior — inputs, outputs, observable state transitions — not internal implementation details. Tests live next to the module under `src/test/kotlin/com/whisperboard/...`. Prior art for shape: the existing `transcription/` package is the closest mirror for `postprocessing/`.
>
> **Build verification:** the debug build must pass before you commit. Use:
>
> ```
> nix develop --command bash -c './gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2'
> ```
>
> or `./gradlew :app:assembleDebug` if `ANDROID_HOME` is available directly. Run unit tests with `./gradlew :app:testDebugUnitTest`.
>
> **Commit hygiene:** make multiple commits along natural boundaries (e.g., abstraction first, implementation second, tests third, settings UI fourth). Don't squash. Don't reference file paths or line numbers in commit messages — use type names.
>
> **PR:** push the branch and open a PR against `main` titled `Slice <N>: <short title>` with a body that includes `Closes #<N>`. Comment on the issue with the PR URL.
>
> **Anti-patterns:**
>
> - Do NOT add features beyond the brief's "Acceptance criteria." The brief's "Out of scope" list is enforced.
> - Do NOT mock data layers if a real instance would work in tests (Room supports in-memory; use it).
> - Do NOT add Tier 3+ LLM features — voice commands, tone-aware rewrites, free-form rewrites are explicitly out of scope.
> - Do NOT bundle UI polish (animations, color tweaks) into this slice unless the brief calls for it.
> - Do NOT skip pre-commit hooks (`--no-verify`) or signing bypasses.
>
> **Wave 3 specifics (only if applicable):** other agents are working on issues #X, #Y, #Z in parallel. You're working on a largely independent module tree. If you discover you must touch shared code (e.g., `KeyboardViewModel`, `WhisperBoardIME`, `SettingsScreen`), keep your edits minimal and surgical — these are merge hotspots.
>
> **Stuck?** If the brief is ambiguous or contradicts what you find in the codebase, surface the contradiction in your final report rather than guessing. The orchestrator will escalate.
>
> **Final report:** one paragraph. Format:
>
> ```
> Issue #<N> (<title>): <PR URL>
> Build: pass | fail (details)
> Tests: <count> tests added, all pass | <count> failing (details)
> Notes: any deviations from the brief, surprises, follow-up items
> ```

## Step 5 — When to escalate to the human

Stop and ask before doing any of the following:

- **Force-push, branch deletion, history rewrite.** Especially on `main`.
- **Merge conflicts within a wave.** If two parallel PRs touch overlapping code, surface the conflict; don't auto-resolve.
- **A subagent reports a build failure that smells like environment** (NixOS aapt2 path, missing `ANDROID_HOME`, submodule init missing). Don't burn time on these — escalate.
- **CI red after merge.** If `main` breaks, stop the next wave.
- **A subagent surfaces a contradiction with the Agent Brief.** Don't update the brief unilaterally; ask the human whether to amend the brief or the implementation.
- **Scope drift signal:** if a subagent's PR diff includes work outside the brief's acceptance criteria, flag it before merging.

Do NOT escalate for:

- Routine implementation choices the brief leaves open (variable names, internal helper structure, exact prompt wording within the spec).
- Trivial follow-ups (a tiny refactor a slice exposed but doesn't need to fix).

## Step 6 — Cleanup after each wave

After all PRs in a wave are merged:

1. `git checkout main && git pull origin main`
2. `git submodule update --init --recursive`
3. For each worktree the subagents used: `rm -rf <worktree-path> && git worktree prune` (per CLAUDE.md — `git worktree remove` fails with submodules)
4. `git branch -d slice/<short-slug>` for each merged branch
5. Sanity-check: `./gradlew :app:assembleDebug` passes on the new `main`

## Step 7 — Done state

All seven slice issues are closed via merged PRs. The app builds cleanly on `main`, all unit tests pass, and the v1 feature set works end-to-end on a real device:

- Bubble overlay (#3) records and copies; with accessibility (#7), it inserts in-place
- Post-processing pipeline (#2) cleans Whisper output via remote API; (#4) extends it with a local SLM
- Language profile (#5) is set on first launch and shapes post-processing prompts
- Auto-insert (#6) is the IME default with the new Settings layout
- Dictation history (#8) persists in the IME transcript area as a tap-to-reinsert scroll

Post a summary comment on the closed PRD issue (#1) listing the merged PRs by date and slice number.

## Step 8 — Begin

Confirm Step 1 with the human, then dispatch Wave 1.
