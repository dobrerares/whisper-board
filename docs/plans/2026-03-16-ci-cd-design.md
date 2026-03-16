# CI/CD Design: GitHub Actions for Whisper Board

## Overview

Three GitHub Actions workflows for build verification, tagged releases with signed APKs, and nightly builds.

## Workflows

### 1. `ci.yml` — PR / Push checks

- **Triggers:** push to `main`, pull requests targeting `main`
- **Job:** `build-debug` — checkout + submodules, JDK 17, Android SDK + NDK 26.1, Gradle cache, `./gradlew :app:assembleDebug`
- **Purpose:** catch build failures early, gate PRs

### 2. `release.yml` — Tagged releases

- **Triggers:** push tag `v*` (e.g. `v0.1.0`)
- **Job:** `build-release` — checkout + submodules, decode keystore from secret, sign release APK via `./gradlew :app:assembleRelease`, create GitHub Release with signed APK
- **Signing:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as GitHub Secrets
- **Release notes:** auto-generated from commits since last tag

### 3. `nightly.yml` — Nightly builds

- **Triggers:** `schedule: cron '0 3 * * *'` (3 AM UTC), plus `workflow_dispatch`
- **Job:** `build-nightly` — release build with nightly version suffix (e.g. `0.1.0-nightly.20260316`)
- **Artifacts:** GitHub Actions artifact (90-day retention) AND rolling `nightly` pre-release on GitHub Releases
- **Skip if no changes:** check HEAD against last nightly, skip if unchanged

## Build Environment (shared)

- Runner: `ubuntu-latest`
- JDK: temurin 17 via `actions/setup-java`
- Android SDK + NDK 26.1.10909125
- Caching: `~/.gradle/caches`, `~/.gradle/wrapper`
- Submodules: `actions/checkout` with `submodules: recursive`

## Signing Setup

- Generate `whisper-board-release.jks`
- `signingConfigs` in `app/build.gradle.kts` reads from env vars (CI) or `local.properties` (local)
- 4 GitHub Secrets for keystore

## Out of Scope

- F-Droid, Play Store distribution
- Lint/test jobs (no test suite yet)
- AAB builds
