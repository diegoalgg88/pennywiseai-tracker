# Deep-dive 19 — iOS: módulo `shared` (KMP), la fachada y qué NO comparte con Android

> Alcance: la app iOS — el módulo `shared` (Kotlin Multiplatform) que la alimenta, la `PennyWiseSharedFacade` (API pública hacia Swift), los statement parsers propios, las pantallas SwiftUI, el pipeline de release (fastlane) y — lo más importante para mantener — **la frontera exacta de lo que iOS NO hereda de Android**. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

La app iOS es un **producto real en el App Store** (SwiftUI, `com.pennywiseai.iosApp`, TestFlight + review vía fastlane), no un experimento. Su arquitectura es **un paralelo deliberado, no un shared-core**: el módulo `shared` duplica la capa de datos (13 entidades `Shared*`, DAOs, repos, use cases, evaluador de reglas) en Kotlin Multiplatform con Room multiplataforma, y expone todo a Swift a través de **una fachada singleton** (`PennyWiseSharedFacade`, ~900 líneas, 48 métodos, `runBlocking` sobre coroutines). La decisión estructural clave: **iOS no tiene acceso a SMS** — su entrada de datos es la importación de PDFs, y todo el pipeline SMS (168 parsers, receiver, dedup multicapa, workers) es territorio exclusivo de Android.

---

## 2. El módulo `shared` — anatomía

Config: `shared\build.gradle.kts` — targets Android (compileSdk 36, minSdk 26) + iOS (`iosX64`, `iosArm64`, `iosSimulatorArm64` — **solo en hosts macOS**; en Windows solo compila el target Android). Room multiplataforma (runtime + SQLite bundled, KSP por target). Framework estático **`Shared`**.

| Paquete (`shared\src\`) | Contenido |
|---|---|
| `commonMain\...\data\local\entity\` | 13 entidades: `SharedTransactionEntity`, `SharedAccountBalanceEntity`, `SharedSubscriptionEntity`, `SharedBudgetEntity`, `SharedBudgetCategoryEntity`, `SharedCategoryEntity`, `SharedCardEntity`, `SharedRuleEntity`, `SharedRuleApplicationEntity`, `SharedMerchantMappingEntity`, `SharedExchangeRateEntity`, `SharedUnrecognizedSmsEntity`, `SharedTransactionSplitEntity` |
| `commonMain\...\data\local\dao\` | Un DAO por entidad + `SharedCategoryBudgetLimitDao` |
| `commonMain\...\data\local\` | `SharedDatabase.kt`, `SharedDatabaseFactory.kt` (versions android/ios separadas — **la DB de iOS tiene su propio schema versioning**) |
| `commonMain\...\data\repository\` | `SharedTransactionRepository`, `SharedAccountRepository`, `SharedSubscriptionRepository`, `SharedBudgetRepository`, `SharedCategoryRepository`, `SharedRuleRepository`, `SharedMerchantMappingRepository`, `SharedExchangeRateRepository`, `SharedUnrecognizedSmsRepository`, `SharedSplitRepository` (+ impls `RoomShared*`) |
| `commonMain\...\data\statement\` | 3 parsers (GPay, PhonePe, Slice) + `SharedStatementParserFactory` + `SharedPdfTextExtractor` (expect/actual) + `SharedParserUtils` |
| `commonMain\...\domain\` | Use cases CRUD (Create/UpdateManualTransaction, ImportStatement, Manage*), `SharedRuleEvaluator`, `SharedCategoryMapping`, `SharedDataGraph`/`SharedDataInitializer` |
| `androidMain\` / `iosMain\` | Platform: TimeProvider, PDF extractor (PDFBox en Android, nativo en iOS), `SharedDatabaseFactory`, Platform |
| `commonTest\` | Tests compartidos |

## 3. La fachada — `PennyWiseSharedFacade.kt` (verificado, 48 métodos)

Patrón: **objeto Kotlin expuesto a Swift, métodos síncronos** con `safeSnapshot { ... }` (runBlocking + catch → snapshot vacío en error). El nombre de cada método es autoexplicativo del contrato:

| Grupo | Métodos (evidencia: grep de líneas 132-890) |
|---|---|
| **Home** | `initializeAndLoadHome(): SharedHomeSnapshot` (L132) — el estado completo del home en un solo objeto |
| **Transacciones** | `addExpenseAndLoadHome` (L139), `addTransactionAndLoadHome` (L159), `updateExpenseAndLoadHome` (L193), `updateTransactionAndLoadHome` (L215), `importStatementTextAndLoadHome` (L251), `getAllTransactions` (L276), `getTransactionById` (L286), `deleteTransaction` (L296), `searchTransactions` (L310), `getTransactionsForPeriod` (L660) |
| **Categorías** | `getAllCategories`, `createCategory`, `updateCategory`, `deleteCategory` (L327-391) |
| **Presupuestos** | `getAllBudgets` (L391), `getBudgetDetail`, `createBudget` (L433), `updateBudget` (L478), `deleteBudget` (L523) |
| **Cuentas/tarjetas** | `getAllAccounts` (L536), `createAccount` (L546), `renameAccount` (L575), `deleteAccount` (L586), `getAccountTransactions` (L597), `getAllCards` (L611), `createCard` (L621), `deleteCard` (L647) |
| **Suscripciones** | `addSubscriptionAndLoadHome` (L679), `getAllSubscriptions` (L703), `createSubscription` (L713), `updateSubscription` (L739), `deleteSubscription` (L767) |
| **Reglas** | `getAllRules` (L780), `createRule` (L790), `updateRule` (L820), `toggleRule` (L851), `deleteRule` (L869) |

**Convención de diseño**: cada mutación devuelve el **snapshot recargado** (`*AndLoadHome`) — el ViewModels Swift nunca arma estado a mano; un solo call post-acción re-renderiza el home. Los mappers privados (`toRuleItem`, `toSubscriptionItem`, …) traducen entity → item de UI en la frontera.

## 4. La app iOS (`iosApp\`)

| Pieza | Contenido (verificado en exploración inicial) |
|---|---|
| `iosAppApp.swift` | Entry point |
| `App\MainTabView.swift` | Tabs: Home, Transactions, Analytics, Settings |
| `Screens\` | Home (saldo, resumen mensual, carruseles de budgets/accounts/subs, recientes, pull-to-refresh), Transactions (lista/detalle/edición, CSV export, PDF import), Analytics (categorías, tendencias, top merchants), Budgets, Accounts, Subscriptions, Settings (reglas, categorías, apariencia, app lock, divisas, export/import, FAQ), Onboarding (welcome, perfil, importación) |
| `ViewModels\` | 8 VMs Swift (Home, Analytics, Budget, Categories, Accounts, Subscriptions, Onboarding, Rules) — consumen la fachada |
| `Components\` | 12 componentes (rows, carruseles, skeleton, lock screen…) |
| `Extensions\StatementImportService.swift` | El puente del PDF import (usa `SharedPdfTextExtractor` del framework) |
| Tema | Claro/oscuro + AMOLED, colores/tipografía propios |
| Deep link | `pennywise://add` (mismo scheme que Android) |
| **Lock screen widgets** | Widgets de bloqueo iOS (no hay App Widgets tipo Android) |

## 5. La frontera: qué NO comparte con Android (mapa de divergencia)

| Subsistema | Android | iOS | Motivo |
|---|---|---|---|
| **SMS parsing (168 bancos)** | ✅ `parser-core` + pipeline completo (docs 05, 07) | ❌ **No existe** | iOS no da acceso al inbox SMS — arquitectura, no backlog |
| **Entrada de datos** | SMS/RCS tiempo real + histórico | **PDF import** (3 parsers shared) + manual | — |
| **DB / migraciones** | `PennyWiseDatabase` v58, `ALL_MIGRATIONS` (doc 16) | `SharedDatabase` con **schema versioning propio** | DBs independientes — NO hay sync entre plataformas |
| **IA** | LiteRT-LM chat on-device + AI Access relay | ❌ Ninguno | El chat IA (doc 01) y el relay (doc 03) son Android-only |
| **Dedup multicapa, balances por SMS, subscriptions desde mandates, phantom autopay** | ✅ (docs 07, 12) | ❌ — solo lo que el usuario importa/crea manualmente | Dependen del pipeline SMS |
| **Backup formato `.pennywisebackup`** | ✅ completo (doc 09) | ❌ Formato propio de export/import de `shared` | No hay contrato de backup compartido — un backup Android NO restaura en iOS |
| **Pro/Billing** | Play Billing + Dodo + F-Droid | **No monetizado** — sin gate en la fachada | La fachada no tiene EntitlementGate |
| **Reglas** | Motor + reglas del sistema | `SharedRuleEvaluator` — mismas conditions/actions en JSON | **Sí compartido en semántica** — las reglas exportadas de Android son importables en iOS |
| **Categorización** | `SharedCategoryMapping` + learning | Solo el fallback estático (`SharedCategoryMapping`) — sin `LearnCategoryUseCase` | El learning depende de notificaciones SMS |
| **Statement parsers** | 5 PDF (app) | 3 (shared: GPay/PhonePe/Slice) | Doc 14 §5 — Paytm/ICICI no portados |

**Conclusión estructural**: `shared` NO es un "core común" — es una **re-implementación paralela de la capa de datos** para las features que iOS puede soportar sin SMS. Cambios de semántica financiera (p.ej. `forTotals`, refunds) que tocan Android deben **replicarse a mano** en `shared` si aplican a iOS, o divergirán silenciosamente.

## 6. Release iOS (`iosApp\fastlane\`)

| Lane | Acción |
|---|---|
| `create_app` | Registro en App Store Connect |
| `beta` | Archive Release + upload a TestFlight |
| `release` | `submit_for_review` (entrega a revisión) |
| `metadata` | Metadatos + screenshots sin binario |
| `asc_status` / `builds` | Estado de ASC / builds subidos |
| `cancel_rejected` / `cancel_drafts` / `delete_empty_drafts` | Gestión de borradores y revisiones |
| `resubmit_metadata` / `submit_version` | Reenvíos |

`Appfile`: `com.pennywiseai.iosApp`, Team `F9FY44V4RR`. **No está integrado con `scripts\release.sh`** — el release Android (doc 17) no toca iOS; TestFlight corre por su cuenta.

## 7. Procedimiento: extender el lado iOS

1. **Feature de datos nueva en ambas plataformas**: implementar en `shared` (entity + DAO + repo + use case) → exponer en la fachada → consumir en el VM Swift → UI. Recordar que la entity de `shared` es INDEPENDIENTE de la de Android (duplicación deliberada) — decidir si la feature aplica sin SMS antes de duplicarla.
2. **Feature solo-Android que quiere llegar a iOS**: evaluar primero si tiene sentido sin pipeline SMS (el learning de categorías no lo tiene; las reglas sí).
3. **Parser de statement para iOS**: duplicar en `shared\...\data\statement\` + factory (doc 14 §7).
4. **Migración de DB iOS**: `SharedDatabaseFactory` versioning propio — no aplica el checklist de `ALL_MIGRATIONS` pero sí la disciplina de defaults.
5. **Build**: solo en macOS (targets iOS); en Windows `./init.sh` compila solo el slice Android de `shared`.
