# PennyWise — Agent Guide (CLAUDE.md)

PennyWise is a privacy-first, AI-powered expense tracker that extracts
transactions from bank SMS **entirely on-device**. The primary app is Android
(Jetpack Compose + Material 3 Expressive); the repo also contains a Kotlin Multiplatform
`parser-core` module (JVM + iOS), a `shared` module (Android + iOS), an `iosApp`
(SwiftUI, App Store), a `pennywise-web` frontend (Ktor + Cloudflare Containers),
and a `relay` (Cloudflare Worker + Durable Object MCP).

**This file is a router, not a manual.** Read the linked topic doc when a task
touches its area. Everything under **Hard Constraints** is non-negotiable.

**Apply judgment to feature/bug requests — an issue is input, not a spec.** When
an issue lists several asks, evaluate each on its own merit: ship the clear wins,
and leave out (with a short note on the issue/PR saying why) the parts that
reshape a core figure/flow or aren't clearly worth it. Don't implement everything
an issue lists by default — bias to the smallest change that solves the real
complaint.

**Current version:** 2.24.0 (versionCode 108) — schema v67, 27+ entities, 170+ bank parsers across 26 countries.

## Hard Constraints (never violate)

1. **Money is always currency-tagged.** Format with
   `CurrencyFormatter.formatCurrency(amount, currencyCode)` (or the
   `formatBalance()` / `formatAmount()` entity extensions) — never the
   currency-less overload (it defaults to ₹ and is `@Deprecated(ERROR)`, so it
   won't compile). It returns `••••` while the user hides amounts
   (`AmountPrivacy`); anything that leaves the screen or is stored
   (notifications, AI prompts/chat text, logs, widgets) passes
   `maskIfHidden = false`.

2. **Never sum amounts across currencies.** "₹500 + $10" is not 510 of anything.
   Total a possibly-mixed list with `Iterable.sumByCurrency({ it.currency },
   { it.amount })` (`utils/MoneyTotals.kt`) → `Map<currency, Money>`, and render
   with `CurrencyFormatter.formatByCurrency(...)`. `Money.+` throws on a currency
   mismatch. A single-figure total is only valid after filtering/converting to
   one currency. (Both rules guarded by `CurrencyFormatterTest`.)

3. **Every backup-serialized field must have a Kotlin default value** — Room
   entity columns *and* the wrapper models in `BackupModels.kt`. Dropping a
   default re-introduces the "can't restore old backup" bug (#414);
   `BackupSchemaGuardTest` enforces this. Use `@Contextual` for
   `BigDecimal`/`LocalDate`/`LocalDateTime`, `@Serializable` for enums. See
   `docs/backup-format.md` or the **backup-maintainer** subagent before touching
   entities or `data/backup/`.

4. **Never put PII in code, comments, tests, or anywhere.**

5. **Never hand-edit the version or changelogs.** `scripts/release.sh` owns
   `versionCode` / `versionName` and `fastlane/.../changelogs/*` (see Releasing).

6. **Parser tests use the shared `ParserTestUtils` helpers** — see
   `docs/parser-test-standards.md`.

7. **New bank parsers** extend the correct base class (`BaseIndianBankParser` /
   `UAEBankParser` / `BaseIranianBankParser` / `BaseThailandBankParser` /
   `BaseMexicoBankParser` / `BankParser`) and are registered in
   `BankParserFactory` — see `docs/adding-bank-parsers.md`.

8. **Migration SQL must run on the oldest SQLite we support.** minSdk 26 ships
   SQLite 3.18; Android 13 ships 3.32. The JVM and our new-Android emulators
   are newer and accept syntax old phones reject: 2.22.0's `HAVING` without
   `GROUP BY` (3.39+) crashed every launch on Android ≤13. Before merging or
   releasing a migration, run the update path on the `Api26_Migration` AVD —
   see `docs/database-migrations.md`.

9. **Never open a pull request against `sarim2000/pennywiseai-tracker:main`.**
   That repository belongs to the upstream maintainer: `origin` points at it and
   the account `diegoalgg88` has read-only access (a push returns HTTP 403).
   Every change lands in the fork **`diegoalgg88/pennywiseai-tracker`** (the
   `fork` remote), whose `main` is the canonical, most complete and corrected
   build. `origin` is a fetch-only reference: never push to it, never
   force-push either remote, and never open a PR against it.

## Verification — Definition of Done

Run **`./init.sh`** before claiming a change is done — it runs the same
compile/test gate CI applies (CI's App Compile job additionally assembles a
debug APK, so a change that compiles but breaks assembly will still surface
there). Scope it to what you touched:

| Change | Command |
|---|---|
| Parser (`parser-core`) | `./init.sh parser` |
| App (Kotlin) | `./init.sh app` |
| Everything | `./init.sh` (default) |
| Runtime / UI | plus an on-device check (use the **emulator-verify** skill) |
| DB migration | plus the old-Android update check in `docs/database-migrations.md` |

`init.sh` sets `JAVA_HOME` to a JDK 21 (it tries `JAVA_HOME`, macOS `java_home`,
Android Studio's JBR, then SDKMAN). Run the raw `./gradlew` tasks it wraps only
if you need finer control. `./gradlew build` and `lint` are heavier and **not**
part of the gate — don't use them to verify.

## Architecture at a glance

- **Stack:** Kotlin 2.3.21 · Jetpack Compose + Material 3 Expressive · MVVM + Clean Architecture ·
  StateFlow (unidirectional data flow) · Hilt DI · Room 3 (SQLite 3.18 via `AndroidSQLiteDriver`) ·
  WorkManager (background SMS scanning) · Google AI Edge LiteRT-LM (Qwen 2.5, Gemma 4 E2B, Gemma 3n E2B, Whisper, Custom HF) for on-device AI.
- **Modules:** `app` (Android, flavors: standard/fdroid) · `parser-core` (pure-Kotlin bank SMS parsers, no Android deps, KMP) · `shared` (KMP) · `iosApp` (SwiftUI, App Store) · `pennywise-web` (Ktor + Cloudflare Containers) · `relay` (Cloudflare Worker + Durable Object MCP).
- **UI:** Material You dynamic color (Android 12+), AMOLED, branded themes, full light/dark with semantic color roles, 8dp grid, Material 3 Expressive type scale. All screens use `PennyWiseScaffold` for consistent system-bar / TopAppBar handling. Always check light, dark, and AMOLED themes.
- **Versioning:** SemVer (MAJOR breaking / MINOR features / PATCH fixes). The live version is in `app/build.gradle.kts` (v2.24.0, versionCode 108).

## Vision Backend Fix (2026-11-05)

- **Bug**: `INVALID_ARGUMENT: Provided more images than expected in the prompt` when sending images with Gemma 3n E2B.
- **Root cause**: `visionBackend` in `EngineConfig` used the main `backend` (GPU when available), but Gemma 3n E2B's vision executor requires CPU.
- **Fix**: `LlmServiceImpl.kt:302` — `visionBackend = if (multimodal) Backend.CPU() else null`.
- **Verification**: `./init.sh app` ✅; on-device with Gemma 3n E2B + image attachment ✅.

## Mexico Bank Parsers

- `BBVAMexicoParser` (sender `266845`)
- `AmexMexicoParser` (sender `AMEX`)
- `TelcelMexicoParser` (sender `TELCEL`) — added 2026-11-05, extends `BaseMexicoBankParser`, handles billing reminders (saldo vencido, cargo fallido, fecha de pago)

## Topic Docs — read when the task touches the area

| Area | Doc | Read when |
|---|---|---|
| Architecture / layers | `docs/architecture.md` | Changing layer responsibilities, DI, data flow |
| Design system | `docs/design.md` | Any UI work — colors, typography, components |
| State management | `docs/state-management.md` | ViewModels, StateFlow, UI state |
| Backup & Restore | `docs/backup-format.md` | Touching backed-up entities or `data/backup/` |
| Adding a bank parser | `docs/adding-bank-parsers.md` | New/changed parser in `parser-core` |
| Parser tests | `docs/parser-test-standards.md` | Writing parser tests |
| DB migrations | `docs/database-migrations.md` | Room schema changes |
| Supported banks | `docs/BANK_SUPPORT.md`, `docs/supported-banks.json` | Which banks/patterns are covered (170+ banks, 26 countries) |
| AI access (MCP relay, dashboard) | `docs/ai-access.md`, `relay/README.md`, `docs/adr/0002-*` | Touching `aiaccess/`, the relay or the dashboard |
| Roadmap / requirements | `docs/planned-features.md`, `docs/prd-unified-currency.md` | Feature scope / intent |

**Subagents:** `backup-maintainer` (backup-serialized changes) ·
`parser-author` (write/fix a parser end-to-end) · `parser-triage` (assess a
fresh `[BANK]` issue).

## Releasing

For a routine release: merge the PRs, then — from an up-to-date `main` with a
clean tree — run `./scripts/release.sh patch|minor|major --yes` (preview first
with `--dry-run`). It commits/tags the current branch and pushes `main`, so the
branch state matters.

`scripts/release.sh` is the single source of truth — it bumps the version,
generates changelogs + release notes, builds the APKs (standard flavor signed;
the F-Droid APK stays unsigned for IzzyOnDroid), tags, pushes, and cuts the
GitHub release. Full flag reference in `docs/RELEASE.md`.

`--web` needs Docker (OrbStack) running and `bun install` done in
`pennywise-web/` — a fresh worktree has no `node_modules`.

## Common Workflows

### Adding a Bank Parser
1. Create parser in `parser-core/src/main/kotlin/.../bank/` extending correct base class (Indian → `BaseIndianBankParser`, UAE → `UAEBankParser`, Iran → `BaseIranianBankParser`, Thailand → `BaseThailandBankParser`, Mexico → `BaseMexicoBankParser`, other → `BankParser`).
2. Implement `getBankName()`, `canHandle(sender)`, override `extractAmount()`/`extractMerchant()` as needed.
3. Register in `BankParserFactory.parsers` **above any broader matcher** that could claim same senders.
4. Add tests using `ParserTestUtils` (`@TestFactory` + `DynamicTest`; see `parser-test-standards.md`).
5. Run `./scripts/update-supported-banks.sh` (regenerates JSON, README, Play listing, samples).
6. Verify: `./init.sh parser`.

### Adding a Database Migration
1. Bump `SCHEMA_VERSION` in `PennyWiseDatabase.kt`.
2. Make entity changes.
3. **Additive only**: add `AutoMigration(from, to)` to `@Database` (+ spec class if ambiguous), build once to export schema JSON, commit it.
4. **Data moves/nullability flips**: write `Migration(from, to)` + register in `ALL_MIGRATIONS` (single array, consumed by both Hilt + fallback builders).
5. **Test on `Api26_Migration` AVD** (Android 8, SQLite 3.18) — **mandatory**: JVM tests can't catch old-SQLite syntax crashes (#922).
6. Verify fresh install still works (migrations only run on existing DBs).

### Adding an AI Tool
1. Add `@Tool` method to `PennyWiseTools.kt` with descriptive `@ToolParam` (schema only; bodies return `""`).
2. **Mutation** → set `_pendingAction` (new `PendingChatAction` subtype) + "confirm below" text; **never** write Room directly.
3. **Read-only** → resolve from Room (`AiContextRepository` or relevant repo) and return text line.
4. Handle in `LlmRepository.handleToolCallInner` (supports camelCase + snake_case via `arg()`).
5. Test anti-hallucination (`CLAIMS_A_CHANGE` regex) and narrated fallback (`NARRATED_TOOL` regex).
6. Gate: `./init.sh app`; on-device verification recommended.

## Key Files Quick Reference

| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Flavors, KSP, Hilt, Room, Compose, LiteRT-LM, Ktor, versionCode/Name |
| `PennyWiseDatabase.kt` | Room DB, entities, `SCHEMA_VERSION=67`, `ALL_MIGRATIONS`, auto-migrations |
| `DatabaseModule.kt` | Hilt provider for DB + all DAOs; consumes `ALL_MIGRATIONS` |
| `LlmServiceImpl.kt` | LiteRT-LM engine, GPU→CPU fallback, conversation lifecycle, multimodal |
| `PennyWiseTools.kt` | 15 tools (bodies return `""`; runtime never executes; app intercepts) |
| `LlmService.kt` | Interface: initialize, createConversation, sendMessage, sendMessageWithImage/Audio |
| `LlmRepository.kt` | System prompt, tool dispatch, history from Room, `PendingChatAction` |
| `ModelRepository.kt` | Model download, SHA-256 streaming verify, RAM gate, catalog |
| `BackupModels.kt` | Wrapper schema (`PennyWiseBackup`, `DatabaseSnapshot`, etc.) — all fields defaulted |
| `BackupExporter.kt` | Reads DB → builds `PennyWiseBackup` → `backupJson.encodeToString` |
| `BackupImporter.kt` | `decodeFromString` → inserts (MERGE/REPLACE_ALL), FK remap, skips bad rows |
| `BankParserFactory.kt` | 170+ parsers registry; `getParsers(sender)` → content-aware `parse()` |
| `init.sh` | **Verification gate** (JDK 21, parser tests, app compile + unit tests) |
| `scripts/release.sh` | **Single source of truth** for versioning (semver, changelogs, APKs, tag, push, release) |

## AI On-Device — Key Invariants

- **Models**: Qwen 2.5 1.5B (fallback), Gemma 4 E2B (thinking), Gemma 3n E2B (vision/audio), Whisper (ASR), Custom HF (experimental).
- **Tools**: 15 total (3 mutations: `add_transaction`, `delete_transaction`, `update_transaction`; 12 read-only). All via `PennyWiseTools.kt`.
- **`automaticToolCalling = false`** — mutations **never** auto-execute; user confirms via `PendingChatAction` card in chat.
- **Token budget**: Qwen 1792, Gemma 5888 (context - 2304 reserved). `fitsChatBudget()` throws "Chat memory is full" if exceeded.
- **Integrity**: SHA-256 streaming verification on **every load**; `.verified` marker is UI-only, never short-circuits load path.
- **RAM gate**: `ModelRepository.deviceSupports(spec)` filters catalog at runtime; fallback to Qwen with warning log.

## W0/W1 Contract Fixes (2026-11-04)

- **INV-1**: Non-system messages in Room alternate user/assistant, end in assistant when idle.
- **INV-2**: Conversation created/replaced **never** includes the message being sent in `initialMessages`.
- **INV-3**: On failure/cancel, close engine conversation so next send rebuilds from Room.
- **Anti-hallucination**: `CLAIMS_A_CHANGE` regex catches narrated mutations, adds disclaimer.
- **Narrated tool fallback**: `NARRATED_TOOL` regex converts bare tool names to `ToolCall`.

## Build & Test Commands

```bash
# Verification gate (exact CI gate)
./init.sh                    # everything
./init.sh parser             # parser-core only
./init.sh app                # app only

# Canonical debug build (F-Droid flavor, Pro unlocked)
.\gradlew.bat :app:assembleFdroidDebug --no-daemon --console=plain

# Signed AAB for Play Store
.\gradlew.bat bundlePlayRelease --no-daemon --max-workers=1 --stacktrace

# Release (bumps version, changelogs, APKs, tag, push, GitHub release)
./scripts/release.sh patch|minor|major --yes

# Regenerate bank documentation (after parser changes)
./scripts/update-supported-banks.sh
```

## Windows Environment (this machine)

- `ANDROID_HOME` = `ANDROID_SDK_ROOT` = `C:\Android`
- `GRADLE_USER_HOME` = `D:\GradleHome` (junction from `C:\Users\DiegoGzz\.gradle`)
- `adb` = `C:\Users\DiegoGzz\Documents\Programas\Scrcpy\adb.exe`
- JDK: MS OpenJDK 21 (`C:\Program Files\Microsoft\jdk-21...`)
- **Always** `.\gradlew.bat` (never `gradle` global, never `./gradlew` Unix-style)
- Wrapper: `gradle-9.8.0-bin.zip` (standard; **never** `-all`)

---

*Verify against current source before making changes. This file is the single source of truth for agent behavior.*