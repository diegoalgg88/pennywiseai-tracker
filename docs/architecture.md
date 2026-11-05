# PennyWise Architecture Guide

## Overview
PennyWise follows modern Android architecture guidelines with MVVM pattern, Clean Architecture principles, and Unidirectional Data Flow (UDF).

## Core Architectural Principles

### 1. Separation of Concerns
- UI components (Activities, Fragments, Composables) contain minimal logic
- Business logic resides in ViewModels and Use Cases
- Data operations handled by repositories

### 2. Drive UI from Data Models
- Persistent models survive configuration changes
- Room database as single source of truth
- StateFlow for reactive UI updates

### 3. Single Source of Truth (SSOT)
- All transaction data originates from Room database
- Repositories centralize data mutations
- Immutable data exposed to UI layer

### 4. Unidirectional Data Flow (UDF)
- State flows: Repository → ViewModel → UI
- Events flow: UI → ViewModel → Repository
- Predictable state management with StateFlow

## Architecture Layers

### UI Layer (Presentation)
**Components:**
- Jetpack Compose screens
- ViewModels with StateFlow
- UI state classes

**Responsibilities:**
- Render UI based on state
- Handle user interactions
- Navigate between screens

**Key Classes:**
```kotlin
- presentation/home/HomeScreen.kt
- presentation/home/HomeViewModel.kt
- ui/viewmodel/ThemeViewModel.kt
- UiState data classes
```

### Domain Layer (Business Logic)
**Components:**
- Use Cases/Interactors
- Domain models
- Business rules

**Responsibilities:**
- Complex business logic
- Data transformation
- Validation rules

**Key Classes:**
```kotlin
- AddTransactionUseCase.kt
- DeleteTransactionUseCase.kt
- ApplyRulesToPastTransactionsUseCase.kt
- MarkSubscriptionPaidUseCase.kt
- domain/service/LlmService.kt (on-device AI interface)
```

### Data Layer (Data Sources)
**Components:**
- Repositories
- Room DAOs
- Data sources (SMS, notification listener, AI)
- Data models

**Responsibilities:**
- Abstract data sources
- Cache management
- Data synchronization

**Key Classes:**
```kotlin
- TransactionRepository.kt
- TransactionDao.kt
- data/manager/SmsTransactionProcessor.kt (orchestrates SMS → parser → database)
- data/repository/LlmRepository.kt (backs LlmService with the on-device model)
- receiver/BankNotificationListenerService.kt (notification-to-SMS parser bridge for whitelisted bank apps)
```

SMS parsing itself lives in the `parser-core` module (pure Kotlin, no Android
dependencies). Parsers register in `BankParserFactory`, which dispatches each
message to every candidate parser by content — see
[adding-bank-parsers.md](adding-bank-parsers.md).

## Module Structure
```
app/
├── src/main/java/com/pennywiseai/tracker/
│   ├── presentation/            # Feature screens + ViewModels (home, accounts,
│   │   │                        #   add, transactions, budgetgroups, categories,
│   │   │                        #   subscriptions, loans, groups, paywall, share,
│   │   │                        #   statement, exchangerates, common; also a
│   │   │                        #   navigation/ subpackage for feature routes)
│   ├── ui/                      # Shared components, theme, ViewModels
│   │   ├── components/          # Reusable Compose components
│   │   ├── theme/               # Design system tokens
│   │   ├── viewmodel/           # App-level ViewModels (theme, app lock, ...)
│   │   └── screens/             # analytics, chat, onboarding, rules,
│   │                            #   settings, unrecognized
│   ├── navigation/              # Navigation graph
│   ├── domain/                  # Business logic
│   │   ├── model/
│   │   ├── usecase/
│   │   ├── service/             # LlmService and friends
│   │   └── repository/          # Repository interfaces
│   ├── data/                    # Data Layer
│   │   ├── database/            # Room database, DAOs, entities
│   │   ├── repository/          # Repository implementations
│   │   ├── manager/             # SmsTransactionProcessor, sync managers
│   │   ├── preferences/         # DataStore-backed settings
│   │   ├── currency/ backup/ contacts/
│   │   └── mapper/
│   ├── di/                      # Hilt modules (DatabaseModule, etc.)
│   ├── receiver/                # SMS / notification entry points
│   ├── worker/                  # WorkManager jobs (SMS scan retry, scheduled backups)
│   ├── widget/                  # Home-screen widgets
│   ├── billing/                 # Play Billing + FreeTierLimits
│   ├── backup/ core/ initializer/ utils/
└── build.gradle.kts             # Flavors: standard, fdroid
parser-core/                     # Bank SMS parsers (Kotlin Multiplatform)
shared/                          # KMP code shared with iOS
iosApp/                          # Swift iOS app
pennywise-web/                   # Web deployment (Cloudflare Worker + server/)
```

## Data Flow Example
```
User opens app
    ↓
HomeScreen observes HomeViewModel.uiState
    ↓
HomeViewModel collects flows from TransactionRepository
    ↓
Repository queries TransactionDao
    ↓
Data flows back up as StateFlow
    ↓
UI recomposes with new state
```

## Key Technologies

### Dependency Injection
- **Hilt** for compile-time DI
- Scoped components (@Singleton, @ViewModelScoped)
- Module-based provision

### Asynchronous Programming
- **Kotlin Coroutines** for async operations
- **Flow** for reactive streams
- **StateFlow** for UI state

### State Management
```kotlin
data class HomeUiState(
    val transactions: List<Transaction> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class HomeViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}
```

The real `HomeUiState` in `presentation/home/HomeViewModel.kt` carries much more
(filters, balances, budget summaries); this trimmed version shows the shape.
[state-management.md](state-management.md) has the full patterns.

### Navigation
- **Navigation 3** (`NavDisplay`) with `@Serializable` `NavKey` back stacks; MainScreen keeps one back stack per bottom tab
- Single Activity architecture
- Deep linking support

## Testing Strategy

### Unit Tests
- ViewModels with mock repositories
- Use cases with mock data sources
- Repository logic testing

### UI Tests
- Compose testing framework
- Screenshot tests
- Navigation tests

### Integration Tests
- Room database migrations
- SMS parsing accuracy
- AI categorization

## Performance Considerations

### Memory Management
- LazyColumn for large lists
- Image loading with Coil
- Proper coroutine scope management

### Database Optimization
- Indexed queries
- Batch operations
- Background processing with WorkManager

### UI Performance
- Recomposition optimization
- State hoisting
- Derivable state calculations

## Security & Privacy

### Data Protection
- Transaction parsing and AI processing run on-device
- No network calls without consent
- Encrypted preferences with DataStore

### Permissions
- Runtime permission requests
- Minimal permission scope
- Clear permission rationale

## Best Practices

### Code Organization
- Feature-based packaging
- Clear layer boundaries
- Interface-based dependencies

### Error Handling
- Sealed classes for results
- Graceful degradation
- User-friendly error messages

### Code Style
- Kotlin coding conventions
- Consistent naming patterns
- Immutable data structures

## F8 Features — System Prompt Additions & Custom HF Model

### System Prompt Additions (F8-T1)
Semantic blocks appended to the on-device model's system prompt, separate from user-defined `customInstructions`.

| Component | File | Role |
|---|---|---|
| Preference key | `UserPreferencesRepository.kt:206` | `SYSTEM_PROMPT_ADDITIONS` DataStore key |
| Flow + setter | `UserPreferencesRepository.kt:1181-1193` | `systemPromptAdditions` flow + `setSystemPromptAdditions()` |
| System prompt injection | `LlmRepository.kt:552, 607, 632-635` | Reads pref, adds `--- System prompt additions (semantic) ---` block |
| SettingsViewModel | `SettingsViewModel.kt:154, 496-500` | Exposes flow + setter to UI |
| Settings UI | `SettingsScreen.kt:1072-1105` | Multiline field (4 lines, ~200 tokens hint) under Experimental |

Budget: Qwen 2560 token context, ~2000 budget, ~200 reserved for additions. Tests: `ChatPromptBudgetTest` (3 new tests).

### Custom HuggingFace Model — Experimental (F8-T2)
User-configurable `.litertlm` model from HuggingFace with pre-download validation.

| Component | File | Role |
|---|---|---|
| Config data class | `Constants.kt:171-195` | `CustomModelConfig` (repoId, fileName, sha256, sizeBytes, contextTokens, minRamBytes, displayName) + JSON serialize |
| Validation | `SettingsViewModel.kt:533-594` | `validateCustomModel()` → HEAD to HF API, checks Content-Length ±5%, X-Huggingface-Sha256 |
| State machine | `SettingsViewModel.kt:165-173` | `CustomModelValidationState` enum (IDLE→PENDING→VALIDATED→DOWNLOADING→READY/ERROR) |
| Persistence | `SettingsViewModel.kt:596-615` | `saveCustomModelConfig()` / `clearCustomModelConfig()` → JSON in prefs |
| Model catalog | `Constants.kt:320-335` | `ChatModels.allWithCustom(customJson)` includes custom model |
| RAM gate | `ModelRepository.kt:93-98` | `supportedModelsWithCustom()` filters by device RAM |
| Settings UI | `SettingsScreen.kt:983-1335` | Experimental section with 7 fields + Validate/Save/Clear buttons |

Fallback: validation fail or RAM gate fail → built-in Qwen 1.5B.

## On-Device AI Chat (LiteRT-LM) — Current Capabilities

### Model Catalog (`core/Constants.kt:135-170`, `ChatModels` object)
| Model | ID | Context | Min RAM | Size | Capabilities |
|---|---|---|---|---|---|
| Qwen 2.5 1.5B | `qwen25-1.5b` | 4096 | 0 GB (fallback) | 1.5 GB | Text, tools |
| Gemma 4 E2B | `gemma4-e2b` | 8192 | 6 GB | 2.6 GB | Text, tools, thinking |
| Gemma 3n E2B | `gemma3n-e2b` | 8192 | 4 GB | 3.7 GB | Text, vision, audio, tools |
| Whisper Base | `whisper-base` | 0 (ASR only) | 2 GB | 42 MB | Audio transcription |
| Custom HF | `custom-*` | user-defined | user-defined | user-defined | Text, tools (if supports FC) |

**RAM gate** (`ModelRepository.kt:56-68`): `deviceSupports(spec)` filters catalog at runtime; fallback to Qwen 1.5B with warning log.

**Integrity** (`ModelRepository.kt:93-118`): SHA-256 streaming verification on every load; `.verified` marker is UI-only, never short-circuits load path.

### Tools (10 total, `PennyWiseTools.kt`)
| Tool | Type | Params |
|---|---|---|
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

**Key invariant** (`LlmServiceImpl.kt:86`): `automaticToolCalling = false` — mutations never auto-execute; user confirms via `PendingChatAction` card in chat.

### Multimodal (Gemma 3n E2B)
- Vision: `sendMessageWithImage()` → `Content.ImageFile(path)` + `visionBackend = Backend.CPU()`
- Audio: `sendMessageWithAudio()` → `Content.AudioFile(path)` + `audioBackend`
- Both require active model to be Gemma 3n E2B; UI gates attach/mic buttons by `activeSpec()`

### Thinking (Gemma 4 E2B, developer mode only)
- `ThinkingConfig` in `ConversationConfig` (litertlm 0.17.1+)
- Budget tokens from prefs (`thinking_budget_tokens`, default 1024)
- Raw tokens visible only in developer mode toggle

### Token Budget (`TokenUtils.kt`)
- Qwen: 4096 context - 1536 reserved = **2560** chat budget
- Gemma: 8192 context - 1536 reserved = **6640** chat budget
- `fitsChatBudget()` throws "Chat memory is full" if exceeded
- `chatBudget(spec)` dynamic per model

## Migration & Evolution

### Database Migrations
- Room auto-migrations
- Fallback strategies
- Data integrity checks

### Feature Flags
- Gradual rollout support
- A/B testing capability
- Remote configuration ready

## Monitoring & Analytics

### Performance Monitoring
- App startup time
- Frame rendering metrics
- Memory usage tracking

### Error Tracking
- Crash reporting (opt-in)
- Non-fatal error logging
- User feedback integration