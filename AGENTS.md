# PennyWise AI — AGENTS.md

> Privacy-first, AI-powered expense tracker that extracts transactions from bank SMS **entirely on-device**.
> Android (Jetpack Compose + Material 3 Expressive); Kotlin Multiplatform `parser-core`, `shared`; Swift `iosApp`; Cloudflare `pennywise-web` + `relay` (Worker + Durable Object MCP).
> Version 2.24.0 (versionCode 108) — schema v67, 27+ entities, 170+ parsers across 26 countries.

---

## Current Status (2026-11-05)

| Area | Status |
|------|--------|
| Android app (standard / fdroid flavors) | **Stable** — builds, tests pass; F-Droid flavor canonical for dev/testing |
| `parser-core` (KMP, JVM + iOS) | **Stable** — 2092+ tests pass; CI guarded by `SupportedBanksDocTest` |
| `shared` (KMP, Android + iOS) | **Active** — Room, parsers, rules, statement import, KSP codegen |
| iOS app (`iosApp`) | **Active, on App Store** — SwiftUI, consumes `Shared` framework |
| `pennywise-web` (Ktor + Cloudflare Containers) | **Deployed** — parser tester, guide, bank pages, Pro licensing |
| `relay` (Cloudflare Worker + Durable Object MCP) | **Active** — OAuth 2.1, 6-digit pairing, 4 read-only MCP tools |
| On-device AI (LiteRT-LM) | **Multi-model** — Qwen 2.5, Gemma 4 E2B, Gemma 3n E2B, Whisper, Custom HF |
| Build gate (`init.sh`) | **Passing** — `:parser-core:test` + `:app:compileStandardDebugKotlin` + `:app:testStandardDebugUnitTest` |

---

## Project Overview

**Purpose**: Turn bank SMS into a private, zero-setup expense timeline with on-device AI assistance.
No accounts, no sign-ups, no servers collecting your data. AGPL v3 licensed.

**Architecture**:
- **Modules**: `:app` (Android), `:parser-core` (pure-Kotlin SMS parsers, KMP), `:shared` (KMP), `:iosApp`, `pennywise-web`, `relay`
- **Stack**: Kotlin 2.3.21 · Jetpack Compose + Material 3 Expressive · MVVM + Clean Architecture · StateFlow (UDF) · Hilt · Room 3 (SQLite 3.18) · WorkManager · Google AI Edge LiteRT-LM · KSP · Ktor
- **UI**: Material You dynamic color (Android 12+), AMOLED, branded themes; full light/dark/AMOLED; 8dp grid; Material 3 Expressive type scale; `PennyWiseScaffold` for consistent system-bar/TopAppBar handling

**Key Constraints**:
- minSdk 26 (Android 8) → SQLite 3.18; **all migrations must run on 3.18** (test on `Api26_Migration` AVD)
- `fallbackToDestructiveMigration()` **never used** — missing migration fails loudly
- F-Droid flavor strips Play Billing, In-App Updates/Reviews; Pro features unlocked free
- **Hard constraints** (see CLAUDE.md): money always currency-tagged, never sum across currencies, backup fields must have Kotlin defaults, zero PII, version/changelogs only via `scripts/release.sh`

---

## Building and Running

### Prerequisites
- **JDK 21** (enforced by `init.sh`; Android Studio JBR, Temurin, or SDKMAN)
- **Android SDK** (`ANDROID_HOME` / `ANDROID_SDK_ROOT` → `C:\Android` on this machine)
- **Gradle 9.8** (wrapper `gradle-9.8.0-bin.zip`; **never** `-all`)
- **Node.js + bun** (for `pennywise-web` / `relay` deployments)

### Commands (from root)

| Command | Purpose |
|---------|---------|
| `./init.sh` | **Verification gate** — runs `:parser-core:test` + `:app:compileStandardDebugKotlin` + `:app:testStandardDebugUnitTest` (same as CI) |
| `./init.sh parser` | Parser tests only (fast) |
| `./init.sh app` | App compile + unit tests only |
| `.\gradlew.bat :app:assembleFdroidDebug --no-daemon --console=plain` | **Canonical debug APK** (F-Droid flavor; Pro unlocked) |
| `adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` | Install on device |
| `adb shell am start -n com.pennywiseai.tracker.debug/com.pennywiseai.tracker.MainActivity` | Launch |
| `.\gradlew.bat bundlePlayRelease --no-daemon --max-workers=1 --stacktrace` | Signed AAB for Play Store |
| `./scripts/release.sh patch|minor|major --yes` | **Single source of truth** for versioning — bumps version, generates changelogs, builds APKs, tags, pushes, cuts GitHub release |

### Windows Gotchas
- Use `.\gradlew.bat` (never `gradle` global, never `./gradlew` Unix-style in Windows shells)
- `local.properties` is machine-local (not committed; `sdk.dir` → `C:\Android` or omit if `ANDROID_HOME` set)
- `GRADLE_USER_HOME` = `D:\GradleHome` (junction from `C:\Users\DiegoGzz\.gradle`)
- `adb` = `C:\Users\DiegoGzz\Documents\Programas\Scrcpy\adb.exe` (in PATH)

---

## Development Conventions

### Kotlin / Android
- **Comments**: Spanish (es-MX) for new comments (per project rule). Explain *why*, not *what*.
- **Strings**: Always in `res/values*/strings.xml` — **never** hardcode user-facing text in composables/layouts.
- **I/O**: Always in `withContext(Dispatchers.IO)` — **never** `runBlocking` in app code.
- **ProGuard/R8**: Verify `proguard-rules.pro` when adding dependencies with reflection.
- **AGP**: Decompresses `*.gz` assets at build time and **removes extension** — verify final asset names at runtime.

### TypeScript (pennywise-web / relay)
- Strict mode; ESLint + Prettier; `bun` for scripts.

### Code Style
- Feature-based packaging (`presentation/`, `data/`, `domain/`, `ui/`)
- Clear layer boundaries; interface-based dependencies
- Sealed classes for results; graceful degradation; user-friendly errors

### Testing
- **Unit**: ViewModels with mock repositories; Use cases with mock data sources
- **UI**: Compose testing framework; screenshot tests; navigation tests
- **Integration**: Room database migrations; SMS parsing accuracy; AI categorization
- **Gate**: `./init.sh` **before claiming a change is done** — it runs the exact CI compile/test gate

### Git & Commits
- **Conventional Commits**: `feat:|fix:|docs:|refactor:|chore:` — subject focused on *why*, body with *what*
- **User**: `Diego Gonzalez <diegoalgg88@gmail.com>`
- **Never** force push, broad staging (`git add .` ❌), or `git stash` in parallel-agent repos (mutates shared worktree; use `isolation: "worktree"`)
- **Pre-commit**: `./init.sh` must pass (ruff/eslint/tests per project)
- **Push/PR/deploy**: **Always explicit user confirmation** — silence does NOT authorize

---

## Architecture Deep-Dive

### Module Structure
```
pennywiseai-tracker/
├── app/                          # Android app (standard / fdroid flavors)
│   ├── src/main/java/com/pennywiseai/tracker/
│   │   ├── presentation/         # Feature screens + ViewModels
│   │   │   ├── home/             # HomeScreen, HomeViewModel
│   │   │   ├── transactions/     # TransactionsScreen, Detail, Add
│   │   │   ├── accounts/         # Manage, Detail, AddAccount, BalanceHistory
│   │   │   ├── budgetgroups/     # Budgets, categories, snapshots
│   │   │   ├── goals/            # Goals, GoalDetail, CreateGoal
│   │   │   ├── loans/            # Loans, LoanShareEntity (split bills)
│   │   │   ├── subscriptions/    # Subscriptions, e-mandates
│   │   │   ├── recurring/        # Manual recurring templates
│   │   │   ├── categories/       # Custom categories, emoji, sub-categories
│   │   │   ├── tags/             # Transaction tags (many-to-many)
│   │   │   ├── groups/           # Transaction groups
│   │   │   ├── statement/        # PDF/CSV import (Slice/PhonePe/GPay)
│   │   │   ├── exchangerates/    # Exchange rates + custom overrides
│   │   │   ├── webhooks/         # Webhook profiles, logs, cursors
│   │   │   ├── aiaccess/         # AI Access connection, relay config
│   │   │   ├── common/           # Shared presentation components
│   │   │   └── navigation/       # Navigation graph (NavDisplay, @Serializable NavKey)
│   │   ├── ui/                   # Shared UI components
│   │   │   ├── components/       # PennyWiseCardV2, GroupedList, SectionHeaderV2, etc.
│   │   │   ├── theme/            # Design system tokens (Spacing, Dimensions, Type, TextStyles)
│   │   │   ├── viewmodel/        # App-level ViewModels (Theme, AppLock, Settings)
│   │   │   └── screens/          # Analytics, Chat, Onboarding, Rules, Settings, Unrecognized
│   │   ├── domain/               # Business logic
│   │   │   ├── model/            # Domain models
│   │   │   ├── usecase/          # AddTransaction, DeleteTransaction, ApplyRules, etc.
│   │   │   ├── service/          # LlmService, AiContextRepository
│   │   │   └── repository/       # Repository interfaces
│   │   ├── data/                 # Data layer
│   │   │   ├── database/         # Room (PennyWiseDatabase, DAOs, entities, converters)
│   │   │   ├── repository/       # Repository implementations
│   │   │   ├── manager/          # SmsTransactionProcessor, sync managers
│   │   │   ├── preferences/      # DataStore-backed settings (UserPreferencesRepository)
│   │   │   ├── backup/           # BackupExporter/Importer, BackupModels, BackupSchemaGuardTest
│   │   │   ├── rules/            # Rule engine (RuleEntity, conditions/actions JSON)
│   │   │   ├── webhook/          # Webhook sync (WebhookSyncWorker, 1-24h intervals, 3 retries)
│   │   │   ├── statement/        # PDF/CSV parsers (Slice, PhonePe, GPay)
│   │   │   └── currency/         # ExchangeRateProvider, CurrencyConversionService
│   │   ├── di/                   # Hilt modules (DatabaseModule, LlmModule, RuleModule)
│   │   ├── receiver/             # SmsBroadcastReceiver (priority 999), BankNotificationListenerService
│   │   ├── worker/               # WorkManager jobs (OptimizedSmsReaderWorker, RecurringTransactionWorker, etc.)
│   │   ├── widget/               # Home-screen widgets (Budget, CategoryPie, Recent, SpendingSummary, Goals, AddTransaction)
│   │   ├── tile/                 # QS Tiles (QuickAddTileService, AiSessionTileService)
│   │   ├── billing/              # Play Billing + FreeTierLimits (FdroidBillingGateway for fdroid)
│   │   ├── core/                 # Constants, utilities
│   │   └── initializer/          # App startup initialization
│   └── build.gradle.kts          # Flavors, KSP, Hilt, Room, Compose, LiteRT-LM, Ktor, etc.
├── parser-core/                  # Pure-Kotlin bank SMS parsers (KMP: JVM + iOS)
│   └── src/main/kotlin/com/pennywiseai/parser/core/
│       ├── bank/                 # 170+ parsers (BaseIndianBankParser, UAEBankParser, BaseIranianBankParser,
│       │                          # BaseThailandBankParser, BaseMexicoBankParser, BankParser)
│       ├── CompiledPatterns.kt   # Centralized regex (amounts, balances, accounts, merchants, dates, currencies)
│       ├── ParsedTransaction.kt  # Output: amount, type, merchant, accountLast4, balance, hash, etc.
│       └── BankParserFactory.kt  # Registry (order matters: specific before broad)
├── shared/                       # KMP code shared with iOS
│   └── src/.../com/pennywiseai/shared/
│       ├── data/                 # Shared entities, DAOs, repositories, use cases
│       ├── domain/               # SharedRuleEvaluator, statement import extractors
│       └── PennyWiseSharedFacade.kt # iOS entry point
├── iosApp/                       # SwiftUI app (App Store)
│   └── .../PennyWiseApp/         # Tabs: Home, Transactions, Analytics, Settings
├── pennywise-web/                # Ktor (Netty) + kotlinx.html + HTMX
│   ├── src/main/kotlin/          # Routing: /, /tools/parse, /banks, /guide, /pro, /api/report
│   └── worker.js + Dockerfile    # Cloudflare Container deployment
└── relay/                        # Cloudflare Worker + Durable Object MCP
    └── src/                      # endpoints: /device, /device/pair, /device/connect, /mcp, dashboard
```

### Key Architectural Patterns

| Pattern | Implementation |
|---------|----------------|
| **DI** | Hilt (`@Singleton`, `@ViewModelScoped`); modules in `di/` |
| **Async** | Kotlin Coroutines + Flow + StateFlow; `Dispatchers.IO` for I/O |
| **State** | ViewModel + StateFlow + `stateIn(WhileSubscribed(5s))`; `collectAsStateWithLifecycle()` |
| **Navigation** | Navigation 3 (`NavDisplay`), `@Serializable` `NavKey`, one back stack per bottom tab |
| **DB** | Room 3 (`AndroidSQLiteDriver` = system SQLite); `SCHEMA_VERSION = 67` top-level const; `ALL_MIGRATIONS` single array consumed by Hilt + fallback builders |
| **SMS Pipeline** | `SmsBroadcastReceiver` (priority 999) → `SmsTransactionProcessor` → `BankParserFactory` (content-aware dispatch) → Room |
| **On-Device AI** | LiteRT-LM `Engine` + `Conversation`; `automaticToolCalling = false`; 10 tools (3 mutations via `PendingChatAction` user confirmation) |
| **Backup** | `.pennywisebackup` JSON (kotlinx.serialization); `coerceInputValues=true`, `ignoreUnknownKeys=true`; 3 strategies (REPLACE_ALL/MERGE/SELECTIVE); receipts base64-embedded |

### On-Device AI Chat (LiteRT-LM) — Current Capabilities

| Model | ID | Context | Min RAM | Size | Capabilities |
|-------|-----|---------|---------|------|--------------|
| Qwen 2.5 1.5B | `qwen25-1.5b` | 4096 | 0 GB (fallback) | 1.5 GB | Text, tools |
| Gemma 4 E2B | `gemma4-e2b` | 8192 | 6 GB | 2.6 GB | Text, tools, thinking |
| Gemma 3n E2B | `gemma3n-e2b` | 8192 | 4 GB | 3.7 GB | Text, vision, audio, tools |
| Whisper Base | `whisper-base` | 0 (ASR) | 2 GB | 42 MB | Audio transcription |
| Custom HF | `custom-*` | user-defined | user-defined | user-defined | Text, tools (if supports FC) |

**RAM gate** (`ModelRepository.deviceSupports`): filters catalog at runtime; fallback to Qwen 1.5B with warning log.
**Integrity**: SHA-256 streaming verification on every load; `.verified` marker is UI-only, never short-circuits load path.

**Tools (15 total, `PennyWiseTools.kt`)**:
| Tool | Type | Params |
|------|------|--------|
| `add_transaction` | Mutation | amount, merchant, category, type (EXPENSE/INCOME), account |
| `delete_transaction` | Mutation | merchant, amount, daysAgo |
| `update_transaction` | Mutation | merchant, amount, daysAgo, newCategory, newMerchant |
| `month_summary` | Read-only | — |
| `spending_by_category` | Read-only | category |
| `budget_status` | Read-only | — |
| `category_breakdown` | Read-only | limit (max 10) |
| `recent_transactions` | Read-only | limit (max 20), daysBack (max 30) |
| `account_balance` | Read-only | — |
| `spending_forecast` | Read-only | — |
| `search_transactions` | Read-only | query, dateFrom, dateTo, category, merchant, amountMin, amountMax, account, currency, type, limit |
| `spending_trend` | Read-only | monthsBack (3-12), granularity (MONTH/WEEK) |
| `subscription_summary` | Read-only | status (ACTIVE/PAUSED/CANCELLED) |
| `exchange_rate` | Read-only | amount, fromCurrency, toCurrency, useCustomRate |
| `category_stats` | Read-only | category, monthsBack |

**Key invariant** (`LlmServiceImpl.kt:86`): `automaticToolCalling = false` — mutations never auto-execute; user confirms via `PendingChatAction` card in chat.

**Multimodal (Gemma 3n E2B)**: Vision (`sendMessageWithImage` → `Content.ImageFile`), Audio (`sendMessageWithAudio` → `Content.AudioFile`). UI gates attach/mic buttons by `activeSpec()`.

**Thinking (Gemma 4 E2B, developer mode only)**: `ThinkingConfig` in `ConversationConfig` (litertlm 0.17.1+); budget tokens from prefs (`thinking_budget_tokens`, default 1024); raw tokens visible only in developer mode toggle.

**Token Budget** (`TokenUtils.kt`): Qwen 4096 - 2304 reserved = **1792** chat budget; Gemma 8192 - 2304 reserved = **5888** chat budget.

### Core Features (Post-F8)

| Feature | Entities | Backup | Key Files |
|---------|----------|--------|-----------|
| **Savings Goals** (#914) | `GoalEntity`, `GoalContributionEntity` (v66+) | FULL; dedup by `uid` on MERGE | `GoalDao`, `GoalContributionDao` |
| **Split Bills / Loan Shares** (#346) | `LoanShareEntity` (v67+), `transactions.lent_shares_total` | FULL only; shares restored onto loans from same import | `LoanDao`, `TransactionSplitDao` |
| **Transaction Tags** | `TagEntity`, `TransactionTagCrossRef` (v58+) | FULL/ANONYMOUS; dedup by name on MERGE | `TagDao` |
| **Webhooks** | `WebhookProfileEntity`, `WebhookLogEntity`, `WebhookCursorEntity`, `WebhookDeliveredTransactionEntity` (v62+) | — | `WebhookSyncWorker` (1-24h, 3 retries) |
| **Mexico Bank Parsers** (W21) | `BBVAMexicoParser` (sender `266845`), `AmexMexicoParser` (sender `AMEX`), `TelcelMexicoParser` (sender `TELCEL`) | — | `BaseMexicoBankParser` for MXN/SMS formats |

### W0/W1 Contract Fixes (2026-11-04)
- **INV-1**: Non-system messages in Room alternate user/assistant, end in assistant when idle
- **INV-2**: Conversation created/replaced **never** includes the message being sent in `initialMessages`
- **INV-3**: On failure/cancel, close engine conversation so next send rebuilds from Room
- **Anti-hallucination**: `CLAIMS_A_CHANGE` regex catches narrated mutations, adds disclaimer
- **Narrated tool fallback**: `NARRATED_TOOL` regex converts bare tool names to `ToolCall`

### Vision Backend Fix (2026-11-05)
- **Bug**: `INVALID_ARGUMENT: Provided more images than expected in the prompt` when sending images with Gemma 3n E2B
- **Root cause**: `visionBackend` in `EngineConfig` was using the main `backend` (GPU when available), but Gemma 3n E2B's vision executor requires CPU
- **Fix**: `LlmServiceImpl.kt:302` — `visionBackend = if (multimodal) Backend.CPU() else null`
- **Verification**: `./init.sh app` ✅; on-device with Gemma 3n E2B + image attachment ✅

---

## Key Files Reference

| File | Role |
|------|------|
| `app/build.gradle.kts` | Flavors, KSP, Hilt, Room, Compose, LiteRT-LM, Ktor, versionCode/Name |
| `app/src/main/java/.../PennyWiseDatabase.kt` | Room DB, entities, `SCHEMA_VERSION=67`, `ALL_MIGRATIONS`, auto-migrations + specs |
| `app/src/main/java/.../di/DatabaseModule.kt` | Hilt provider for DB + all DAOs; consumes `ALL_MIGRATIONS` |
| `app/src/main/java/.../data/service/LlmServiceImpl.kt` | LiteRT-LM engine, GPU→CPU fallback, conversation lifecycle, multimodal |
| `app/src/main/java/.../data/service/PennyWiseTools.kt` | 10 tools (bodies return `""`; runtime never executes; app intercepts) |
| `app/src/main/java/.../domain/service/LlmService.kt` | Interface: initialize, createConversation, sendMessage, sendMessageWithImage/Audio |
| `app/src/main/java/.../data/repository/LlmRepository.kt` | System prompt, tool dispatch, history from Room, `PendingChatAction` |
| `app/src/main/java/.../data/repository/ModelRepository.kt` | Model download, SHA-256 streaming verify, RAM gate, catalog |
| `app/src/main/java/.../data/backup/BackupModels.kt` | Wrapper schema (`PennyWiseBackup`, `DatabaseSnapshot`, etc.) — all fields defaulted |
| `app/src/main/java/.../data/backup/BackupExporter.kt` | Reads DB → builds `PennyWiseBackup` → `backupJson.encodeToString` |
| `app/src/main/java/.../data/backup/BackupImporter.kt` | `decodeFromString` → inserts (MERGE/REPLACE_ALL), FK remap, skips bad rows |
| `parser-core/src/main/kotlin/.../bank/BankParserFactory.kt` | 170+ parsers registry; `getParsers(sender)` → content-aware `parse()` |
| `parser-core/src/main/kotlin/.../bank/BaseIndianBankParser.kt` | Mandate, subscription, balance logic for Indian banks |
| `parser-core/src/main/kotlin/.../CompiledPatterns.kt` | Centralized regex for amounts, balances, accounts, merchants, dates, currencies |
| `shared/src/.../data/bootstrap/DefaultCategoryData.kt` | Default categories seeded in `Migration7To8.onPostMigrate` |
| `init.sh` | **Verification gate** (JDK 21, parser tests, app compile + unit tests) |
| `scripts/release.sh` | **Single source of truth** for versioning (semver, changelogs, APKs, tag, push, release) |
| `scripts/update-supported-banks.sh` | Regenerates `supported-banks.json`, README block, Play listing, parser samples |

---

## Protocol Constants & Invariants

| Constant | Value | Source |
|----------|-------|--------|
| `SCHEMA_VERSION` | 67 | `PennyWiseDatabase.kt:83` |
| `MODEL_URL` (Qwen) | `https://pub-...r2.dev/models/v1/Qwen2.5-1.5B-Instruct-q8-ekv4096.litertlm` | `Constants.kt` |
| `MODEL_SHA256` (Qwen) | `faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9` | `Constants.kt` |
| `LIVE_PROTOCOL_VERSION` (relay) | 9 | `relay/src/shared/protocol.ts` |
| Chat token budget (Qwen) | 2560 | `TokenUtils.kt` |
| Chat token budget (Gemma) | 6640 | `TokenUtils.kt` |
| Reserved tokens (tools + template) | 1536 | `TokenUtils.kt` |

---

## Common Tasks

### Add a New Bank Parser
1. Create parser in `parser-core/src/main/kotlin/.../bank/` extending correct base class:
   - Indian → `BaseIndianBankParser`
   - UAE → `UAEBankParser`
   - Iran → `BaseIranianBankParser`
   - Thailand → `BaseThailandBankParser`
   - Mexico → `BaseMexicoBankParser`
   - Other → `BankParser`
2. Implement `getBankName()`, `canHandle(sender)`, override `extractAmount()`/`extractMerchant()` as needed
3. Register in `BankParserFactory.parsers` **above any broader matcher** that could claim same senders
4. Add tests using `ParserTestUtils` (`@TestFactory` + `DynamicTest`; see `parser-test-standards.md`)
5. Run `./scripts/update-supported-banks.sh` (regenerates JSON, README, Play listing, samples)
6. Verify: `./init.sh parser`

### Add a Database Migration
1. Bump `SCHEMA_VERSION` in `PennyWiseDatabase.kt`
2. Make entity changes
3. **Additive only**: add `AutoMigration(from, to)` to `@Database` (+ spec class if ambiguous), build once to export schema JSON, commit it
4. **Data moves/nullability flips**: write `Migration(from, to)` + register in `ALL_MIGRATIONS` (single array, consumed by both builders)
5. **Test on `Api26_Migration` AVD** (Android 8, SQLite 3.18) — **mandatory**: JVM tests can't catch old-SQLite syntax crashes (#922)
6. Verify fresh install still works (migrations only run on existing DBs)

### Add a New AI Tool
1. Add `@Tool` method to `PennyWiseTools.kt` with descriptive `@ToolParam` (schema only; bodies return `""`)
2. **Mutation** → set `_pendingAction` (new `PendingChatAction` subtype) + "confirm below" text; **never** write Room directly
3. **Read-only** → resolve from Room (`AiContextRepository` or relevant repo) and return text line
4. Handle in `LlmRepository.handleToolCallInner` (supports camelCase + snake_case via `arg()`)
5. Test anti-hallucination (`CLAIMS_A_CHANGE` regex) and narrated fallback (`NARRATED_TOOL` regex)
6. Gate: `./init.sh app`; on-device verification recommended

### Modify Visual/Audio Capture (AI)
- **Screen on-demand**: `AppshotCaptureService.captureFrame()` → native `captureAppshot()`
- **Screen live-feed**: `captureDisplayFrame()` → native `captureDisplay()` → `encodeScreenFrame()` (JPEG attempts)
- **Camera**: `CameraEngine` (preload) → `getUserMedia` → IPC
- Any native change must keep both modules returning same shape/error codes

---

## MUST / NEVER

**MUST**
- Keep `SCHEMA_VERSION` top-level so non-Room code (`BackupExporter`) uses same number
- Register **all** manual migrations in `ALL_MIGRATIONS` (single array, dual-builder safety)
- Run `./init.sh` before claiming a change is done — exact CI gate
- Use `CurrencyFormatter.formatCurrency(amount, currencyCode)` — never currency-less overload (`@Deprecated(ERROR)`)
- Sum mixed currencies with `Iterable.sumByCurrency({ it.currency }, { it.amount })` → `Map<currency, Money>`
- Give **every** backup-serialized field a Kotlin default (entity + `BackupModels.kt`); `BackupSchemaGuardTest` enforces
- Keep `automaticToolCalling = false` — user confirmation required for every mutation
- Verify model SHA-256 on **every load** (streaming); `.verified` marker is UI-only
- Run `./scripts/release.sh` for version/changelog — **never hand-edit**

**NEVER**
- Never use `fallbackToDestructiveMigration()` — missing migration must fail loudly
- Never sum amounts across currencies (`Money.+` throws on mismatch)
- Never put PII in code, comments, tests, or anywhere
- Never hand-edit versionCode/versionName or fastlane changelogs
- Never open PR against `sarim2000/pennywiseai-tracker:main` (upstream, read-only); all changes land in fork `diegoalgg88/pennywiseai-tracker`
- Never add `node:child_process`, `spawn`, `execFile` to `src/` (even comments)
- Never assume POSIX permission bits on Windows (use `enforcesPosixModes()` pattern)
- Never commit `dist/`, `release/`, `node_modules/`, generated `.node` binaries
- Never broad-stage (`git add .`) — stage explicit paths; verify `git status --porcelain` first

---

## Known Issues & Limitations

- **Old Android SQLite**: migrations run on phone's SQLite (minSdk 26 = 3.18). Syntax valid in JVM tests (3.39+) can crash every launch on Android ≤13. **Always test migrations on `Api26_Migration` AVD before merge/release.**
- **`MIGRATION_63_64` incident** (2.22.0): `HAVING` without `GROUP BY` (needs 3.39) crashed Android ≤13 on every launch.
- **Schema gap**: `48.json` never committed (history runs 47 → 49). Nothing migrates against it, but don't treat as precedent.
- **Pro on Play**: in-app purchase unavailable (payments-profile verification). Existing purchases work; new buyers use website key.
- **iOS**: no SMS parsing (iOS restriction); entry via PDF import only.
- **Windows build**: not applicable (Android/iOS only); `pennywise-web`/`relay` deploy from macOS/Linux CI.

---

## Related Projects

- **qwen-live-host** (`../../qwen-live-host/`) — native macOS/Windows host for Live Voice (Electron + native modules)
- **qwen-live daemon** (`../../qwen-live/`) — standalone Live daemon (TypeScript, Node.js)
- **Qwen Code CLI** (`../../cli/`) — main CLI, includes WebShell Live integration

---

## Quick Reference

| Task | Command |
|------|---------|
| Verify everything (CI gate) | `./init.sh` |
| Parser tests only | `./init.sh parser` |
| App compile + unit tests | `./init.sh app` |
| Build F-Droid debug APK | `.\gradlew.bat :app:assembleFdroidDebug --no-daemon --console=plain` |
| Install on device | `adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` |
| Build signed AAB (Play) | `.\gradlew.bat bundlePlayRelease --no-daemon --max-workers=1 --stacktrace` |
| Release (patch/minor/major) | `./scripts/release.sh patch|minor|major --yes` |
| Regenerate bank docs | `./scripts/update-supported-banks.sh` |
| Typecheck (parser-core) | `.\gradlew.bat :parser-core:compileKotlinJvm` |
| Typecheck (app) | `.\gradlew.bat :app:compileStandardDebugKotlin` |

---

*Updated 2026-11-05. Verify against current source before making changes.*