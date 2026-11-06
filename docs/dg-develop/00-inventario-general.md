# PennyWise AI — Inventario funcional completo

Mapeé el repo completo con 3 agentes de exploración en paralelo (módulo `app`, `parser-core`+`scripts`+`docs`, y módulos no-Android). Todo lo que sigue está verificado contra el árbol de archivos en `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker`.

---

## 1. Qué es el proyecto

**PennyWise AI** es un expense tracker **privacy-first** que convierte los SMS de tu banco en una línea de tiempo de gastos, con IA 100% on-device. Sin cuentas, sin servidores que reciban tus datos, sin telemetría (verificable en `app\build.gradle.kts` — no hay Firebase/Crashlytics/Sentry). Licencia AGPL v3.

- **Flujo central**: otorgas permiso de SMS (read-only) → la app parsea tus transacciones históricas y en tiempo real → analytics, presupuestos, suscripciones, metas de ahorro, split bills, tags, webhooks y chat IA, todo en el teléfono.
- **Stack**: Kotlin 2.3.21 · Jetpack Compose + Material 3 Expressive · MVVM + Clean Architecture · StateFlow (UDF) · Hilt · Room · WorkManager · Google AI Edge LiteRT-LM (Qwen 2.5, Gemma 4 E2B, Gemma 3n E2B, Whisper, Custom HF on-device).
- **Módulos Gradle** (`settings.gradle.kts`): `:app` (Android, flavors standard/fdroid), `:parser-core` (parsers SMS, Kotlin puro KMP), `:shared` (KMP Android+iOS), `:iosApp`. Fuera de Gradle: `pennywise-web` (Ktor + Cloudflare Containers) y `relay` (Cloudflare Worker MCP).
- **Versión actual**: 2.24.0 (versionCode 108) — schema v67, 27+ entidades, 170+ parsers en 26 países.

---

## 2. Motor de parsing — `parser-core`

Ruta: `...\PennyWiseAI-tracker\parser-core\src\main\kotlin\com\pennywiseai\parser\core\`

### Contrato de todo parser (`bank\BankParser.kt`)

Cada banco extiende una base regional y sobrescribe lo que necesita:

| Clase base | Para quién | Extras que aporta |
|---|---|---|
| `BankParser` | Genérica internacional (default INR) | Orquestación `parse()` completa |
| `BaseIndianBankParser` | Bancos de India | e-mandates/upi-mandates, balance updates, descarte de "credited" como inversión |
| `UAEBankParser` | Emiratos | multi-divisa (AED/USD/EUR), remesas, compras con tarjeta de crédito |
| `BaseIranianBankParser` | Irán | dígitos persas |
| `BaseThailandBankParser` | Tailandia | formatos tailandeses |
| `BaseMexicoBankParser` | México | formatos mexicanos, montos MXN, patrones de sender |

**Qué extrae cada SMS** → `ParsedTransaction` (`ParsedTransaction.kt`): monto (`BigDecimal`), tipo (`TransactionType`: INCOME/EXPENSE/CREDIT/TRANSFER/INVESTMENT/BALANCE_UPDATE), merchant, reference, últimos 4 dígitos de cuenta, balance tras la transacción, límite disponible de tarjeta, banco, moneda de transacción, moneda de cuenta (difieren si gastas USD en tarjeta INR), si es tarjeta, si es wallet móvil (M-Pesa/eMola), y un `transactionHash` (md5 de sender+monto+cuerpo) para deduplicar.

**Despacho** (`bank\BankParserFactory.kt`): lista estática de **170+ parsers**; `getParser(sender)` por coincidencia de sender ID, y `parse()` content-aware itera los parsers que matchean y devuelve el primero que retorne no-null. El orden de registro es crítico (parsers específicos antes que los amplios: `HDFCMutualFundParser` antes de `HDFCBankParser`, `MPesaMozambiqueParser` antes del de Tanzania, `AmexMexicoParser`/`BBVAMexicoParser`/`TelcelMexicoParser` via Spanish content-gate).

**Detección de tipo** (`BankParser.extractTransactionType`): prioridad inversión (NACH/ACH/SIP/Zerodha/Groww...) → gasto (`debited`, `spent`, `charged`...) → ingreso (`credited`, `refund`, `cashback`...).

**Patrones regex centralizados**: `CompiledPatterns.kt` (montos Rs/INR/₹, balances "Avl bal", cuentas "XX1234", merchants "to/from/at/for", fechas, monedas ISO, limpieza de sufijos "PVT LTD").

**Tests**: ~170 archivos `*ParserTest.kt` con JUnit 5 `@TestFactory` + helpers `ParserTestUtils` (estándar obligatorio en `docs\parser-test-standards.md`). `SupportedBanksDocTest` falla CI si `docs\supported-banks.json` no coincide con la factory — se regenera con `scripts\update-supported-banks.sh`.

**Soporte**: 170+ bancos en 26 países (India 57, Nepal 13, Tailandia 11, USA 10, Etiopía 9, Nigeria 9, Saudi 7, Tanzania 7, Irán 6, UAE 6, México 2, y más).

---

## 3. App Android — todas las funciones

Base: `...\app\src\main\java\com\pennywiseai\tracker\`

### 3.1 Pantallas (rutas Compose Navigation en `navigation\PennyWiseDestinations.kt` + `PennyWiseNavHost.kt`)

| Pantalla | Path (bajo `...\tracker\`) | Qué hace |
|---|---|---|
| **Home/Dashboard** | `presentation\home\HomeScreen.kt` | Resumen del mes, saldos por cuenta, presupuestos, suscripciones próximas, heatmap, préstamos, tarjetas de crédito, modo moneda unificada, tarjeta Pro |
| **Transacciones** | `presentation\transactions\TransactionsScreen.kt` | Lista agrupada (hoy/ayer/semana), búsqueda, filtros (categoría, tipo, cuenta, tag, moneda, período custom), totales por moneda, export CSV |
| **Detalle** | `presentation\transactions\TransactionDetailScreen.kt` | Editar/eliminar/duplicar, splits, marcar como préstamo, crear goal, excluir de analytics, recibo |
| **Añadir** | `presentation\add\AddScreen.kt` | Formulario transacción/suscripción: monto, merchant, categoría, fecha, notas, tags, transferencia entre cuentas, moneda, recibo (cámara), recurrencia |
| **Quick Add** | `presentation\add\QuickAddActivity.kt` | Activity translúcida sobre cualquier app (desde widget/notificación/QS tile) |
| **Cuentas** | `presentation\accounts\` (Manage, Detail, AddAccount, BalanceHistory) | Alias, ocultar, ignorar, **merge de cuentas duplicadas**, historial de saldos, alertas de saldo bajo |
| **Presupuestos** | `presentation\budgetgroups\` | Grupos LIMIT/TARGET/EXPECTED, períodos semana/mes/custom, categorías, snapshots mensuales congelados ("Live" vs "Frozen as of…") |
| **Goals (metas de ahorro)** | `presentation\goals\` | Meta, fecha límite, contribuciones/retiros, vínculo con transacciones. ADR: separado de presupuestos |
| **Préstamos** | `presentation\loans\` | Prestado/tomado (LENT/BORROWED), monto restante, split de cuenta compartida (`LoanShareEntity`), liquidar |
| **Suscripciones** | `presentation\subscriptions\` | Detectadas de e-mandates, marcar como pagada, ciclo de facturación, direcciones EXPENSE/INCOME |
| **Recurrentes manuales** | `presentation\recurring\` | Plantillas sin SMS atrás (alquiler, efectivo) que un worker materializa al llegar `nextDueDate` |
| **Analytics** | `ui\screens\analytics\` | Pie por categoría/tag, barras, heatmap, tendencias, top merchants, drill-down filtrado, filtro "Spending" unificado |
| **Chat IA** | `ui\screens\chat\ChatScreen.kt` | Ver §4 |
| **Reglas** | `ui\screens\rules\` | Ver §3.3 |
| **SMS no reconocidos** | `ui\screens\unrecognized\` | SMS financieros que el parser no entendió; reportar/eliminar |
| **Settings** | `ui\screens\settings\` | Hub hacia todo lo demás + bloqueo biometrics, permisos, PennyWise Pro |
| **Apariencia** | `ui\screens\settings\AppearanceScreen.kt` | Tema dark/dynamic/AMOLED, estilos de portada (Aurora/Gradient/Wave), fuente, blur, **pestañas ocultas del nav** |
| **Webhooks** | `ui\screens\settings\webhooks\` | Perfiles de sync a URL externa (rango, tipos de datos, headers, logs) |
| **AI Access** | `ui\screens\settings\aiaccess\AiAccessScreen.kt` | Ver §5 |
| **Importar estado** | `presentation\statement\` | PDF/CSV de bancos (Slice/PhonePe/GPay) vía `shared` |
| **Tipos de cambio** | `presentation\exchangerates\` | Tasas del proveedor + **tasas custom manuales** + expiración |
| **Grupos de transacción** | `presentation\groups\` | Agrupar transacciones con nombre/nota |
| **Categorías** | `presentation\categories\` | Custom con color/emoji, subcategorías, ocultar |
| **Onboarding / Permisos / AppLock / FAQ** | `ui\screens\` | Spotlight tutorial, permiso SMS, biometría/PIN con timeout |

### 3.2 Capa de datos (Room, `data\database\`)

**~30 entidades** en `data\database\entity\`. Las centrales:

- `TransactionEntity` — amount BigDecimal, currency (default INR, **siempre tagged**), merchant, type, smsBody, transactionHash único, `excludedFromAnalytics`, fromAccount/toAccount (transferencias), loanId, receiptPath, budgetImpactType, profileId
- `AccountBalanceEntity` — balance, accountType (SAVINGS/CURRENT/CREDIT/CASH/LOAN), alias, lowBalanceThreshold, `isLiability`/`isLoan` (crédito/préstamo no entran en el total gastable)
- `BudgetEntity` + `BudgetCategoryEntity` + 2 snapshot entities · `GoalEntity` + `GoalContributionEntity` · `LoanEntity` + `LoanShareEntity` · `SubscriptionEntity` · `CategoryEntity` (con parentId, isSystem) · `RecurringTransactionEntity` · `ExchangeRateEntity` (isCustomRate) · `CardEntity` · `ProfileEntity` (Personal/Business) · `TagEntity` + CrossRef · `MerchantMappingEntity`/`MerchantAliasEntity` · `RuleEntity` + `RuleApplicationEntity` · `ChatMessage` · `UnrecognizedSmsEntity` · `BankNotificationEntity` · `TransactionGroupEntity` · `TransactionSplitEntity` · 4 entidades Webhook (Profile/Log/Cursor/Delivered)

**24 DAOs** (`data\database\dao\`) · **27 repositorios** (`data\repository\`) · DB en `PennyWiseDatabase.kt` (schema v58+, migraciones en `docs\database-migrations.md`).

### 3.3 Motor de reglas (Smart Rules)

`RuleEntity` guarda `conditions` y `actions` como JSON. Campos de condición: AMOUNT, TYPE, CATEGORY, MERCHANT, NARRATION, SMS_TEXT, BANK_NAME, TIME/HOUR, DAY_OF_WEEK, DAY_OF_MONTH, DATE, ACCOUNT (composite `BankName||Last4`). Operadores AND/OR. Acciones: Set, Append/Prepend, Clear, **Block** (impedir que se guarde). Extras: prioridad, dry-run + batch apply retroactivo, export/import JSON, cuota gratuita (`FreeTierLimits.MAX_RULES`) vs Pro ilimitado.

### 3.4 Workers (WorkManager, `worker\`)

| Worker | Función |
|---|---|
| `OptimizedSmsReaderWorker` | El corazón: parsea SMS/RCs entrantes en paralelo, guarda transacciones, actualiza balances, deduplica, limpia duplicados GPay |
| `RecurringTransactionWorker` | Materializa recurrentes manuales vencidas (diario + catch-up al boot) |
| `DailySummaryWorker` | Notificación vespertina (9 PM): gasto del día + acciones categorizar/quick add |
| `WebhookSyncWorker` | Sincroniza webhooks (intervalos 1-24h, hasta 3 reintentos) |
| `ScheduledFolderBackupWorker` | Auto-backup a carpeta SAF elegida por el usuario |
| `BankNotificationRetryWorker` | Reintenta notificaciones bancarias fallidas (15 min) |

### 3.5 Entradas del sistema

- **Receivers**: `SmsBroadcastReceiver` (SMS_RECEIVED, prioridad 999 → parseo en tiempo real), `BankNotificationListenerService` (lee notificaciones de apps bancarias), `NotificationActionReceiver` (acciones en notificaciones).
- **Widgets** (`widget\`): Budget, Category Pie, Recent Transactions, Spending Summary (hoy+mes+quick add), Goal 2x2, Goals List 4x2, Add Transaction. Refresco centralizado vía `WidgetRefresher`.
- **QS Tiles**: `QuickAddTileService` y `AiSessionTileService`.
- **Deep links / Share**: `pennywise://add` (Tasker/gestos/shortcuts) y `ACTION_SEND` text/plain ("Share to PennyWise" pre-llena el formulario; nada se guarda sin tap).

### 3.6 Backup/Restore (`data\backup\`)

Un archivo `.pennywisebackup` (JSON, kotlinx.serialization, `"PennyWise Backup v1.3"`). **Regla dura**: todo campo serializado debe tener default en la entidad Room Y en `BackupModels.kt` — `BackupSchemaGuardTest` lo impide (bug histórico #414). 3 niveles de privacidad (FULL / MASKED redacta SMS / ANONYMOUS), recibos embebidos opcionalmente, 3 estrategias de import (REPLACE_ALL / MERGE / SELECTIVE) con dedup por uid/nombre/banco. Android `allowBackup=false` — la app gestiona su propio backup.

### 3.7 Multi-moneda

**Invariante central del repo**: el dinero siempre lleva moneda (`CurrencyFormatter.formatCurrency`); **nunca se suman monedas distintas** — `Iterable.sumByCurrency()` (`utils\MoneyTotals.kt`) devuelve `Map<currency, Money>`; `Money.+` lanza excepción en mismatch. Modo "unified currency" (`docs\prd-unified-currency.md`): convierte a moneda display vía `ExchangeRateEntity`; si no hay tasa, esa moneda se omite en vez de sumarse a nominal (#545/#670). Tasas de `open.er-api.com` (única llamada de red junto a Play Billing, descarga del modelo, y el reporte de parsing — todo lo demás es offline).

---

## 4. IA on-device (chat)

Motor: **LiteRT-LM + Qwen 2.5 1.5B**, descarga one-time de ~1.5 GB con SHA-256 pinned (`Constants.MODEL_URL`), corre offline (CPU/GPU OpenCL).

| Archivo | Rol |
|---|---|
| `data\service\LlmServiceImpl.kt` | Engine LiteRT, streaming, `automaticToolCalling = false`; **vision backend fix (2026-11-05): `visionBackend = Backend.CPU()` para Gemma 3n E2B** |
| `data\service\PennyWiseTools.kt` | ToolSet del modelo: `addTransaction`, `deleteTransaction`, `updateTransaction`, `monthSummary`, `spendingByCategory` — los cuerpos devuelven `""`; la app intercepta |
| `data\repository\LlmRepository.kt` | Orquesta chat, system prompt con contexto del mes, `PendingChatAction` |

**Garantía clave (#170)**: el modelo propone, el usuario confirma con un tap explícito en una tarjeta del chat; nada se guarda solo.

**Vision backend fix (2026-11-05)**: `LlmServiceImpl.kt:302` — `visionBackend = if (multimodal) Backend.CPU() else null`. El bug `INVALID_ARGUMENT: Provided more images than expected in the prompt` ocurría porque Gemma 3n E2B requiere CPU para visión; el GPU backend no tiene delegado de visión.

---

## 5. AI Access — relay MCP (Pro)

Permite que **Claude/ChatGPT consulten tus datos** vía MCP sin que ningún servidor los vea. ADR aceptado: `docs\adr\0002-ai-access-via-worker-relay.md`.

- **Relay** (`relay\src\`): Cloudflare Worker + Durable Object `Phone` (uno por teléfono). El teléfono mantiene un WebSocket **outbound** durante la sesión; el relay solo pasa bytes, no almacena ni loguea. OAuth 2.1 del lado de las apps IA, pareo por código de 6 dígitos (5 min, uso único). Endpoints: `POST /device`, `POST /device/pair`, `GET /device/connect`, `POST /mcp` (Streamable HTTP), dashboard web.
- **App** (`aiaccess\`): `AiRelayClient.kt` (Ktor WS, secret cifrado en Keystore vía `DeviceSecretBox.kt`), `AiSessionService.kt` (foreground, sesiones 15/30/60 min extendibles), tile QS, `AiTools.kt`/`AiToolLogic.kt` (ejecuta los tool calls desde Room).
- **4 herramientas MCP, todas read-only** (`relay\src\tools.ts`): `search_transactions`, `spending_summary`, `list_accounts`, `list_subscriptions`. Montos siempre con moneda, cuentas enmascaradas (últimos 4). **Nunca sale**: SMS, números completos, notas, recibos, presupuestos, metas. Relay self-hosteable; URL configurable en la app.

---

## 6. shared + iOS

- **`shared\`** (KMP): duplica para iOS la capa de datos — 13 entidades `Shared*`, DAOs, repositorios, use cases, evaluador de reglas (`SharedRuleEvaluator`), parsers de PDF (Slice/PhonePe/GPay con extractores por plataforma). Fachada: `PennyWiseSharedFacade.kt`.
- **`iosApp\`** (SwiftUI, **activa, en App Store**): pestañas Home/Transactions/Analytics/Settings; presupuestos, cuentas, suscripciones, reglas, onboarding, lock screen, PDF import, dark/AMOLED, deep link `pennywise://add`. Consume el framework `Shared`. Fastlane en `iosApp\fastlane\` (beta→TestFlight, release→review). **No tiene** SMS parsing (iOS no da acceso) — entra por importación de PDF.

## 7. `pennywise-web` — sitio + tester de parsers

Ktor (Netty) + kotlinx.html + HTMX, desplegado como **Cloudflare Container** (`pennywise.zynth.dev`, `worker.js` con Hono + `@cloudflare/containers`, Dockerfile multistage). Rutas (`Routing.kt`): `/` y `/tools/parse` (tester de parseo SMS con feedback instantáneo usando el mismo `parser-core`), `/banks` y páginas long-tail por banco/país, `/guide` (guía de usuario completa), `/pro` (licencias), `POST /api/report` (reportes de parsing fallido → PostgreSQL). Es el destino del flujo "Report a parsing problem" de la app.

## 8. Infraestructura de build/release

- **Gate**: `init.sh` (JDK 21, `:parser-core:test` + `:app:compileStandardDebugKotlin` + `:app:testStandardDebugUnitTest`). Gate completo: 2 comandos gradlew (build-logic tests + app tests + `verifyKotlinFileSize`).
- **Flavors**: `standard` (Play Billing, In-App Updates/Reviews, todas las ABIs) y `fdroid` (sin Google, Pro desbloqueado gratis, solo ARM). versionCode 108 / versionName 2.24.0 en `app\build.gradle.kts`.
- **`scripts\release.sh`** (única fuente de verdad de versiones — nunca editar a mano): bump semver, release notes generadas con Claude Agent SDK, changelogs fastlane, build de APKs + SHA256, commit+tag+push, GitHub release, opcional AAB a Play y deploy web/relay.
- **Otros scripts**: `update-supported-banks.sh` (regenera JSON+README), `upload-play.sh` (AAB/listing), `verify_anon_smsxml.py` + `randomziedata.py` (anonimización de fixtures), `generate_android_launcher_icons.sh`, `content-mine/`.
- **i18n** (`crowdin.yml`): 11+ idiomas activos (Hindi prioridad ~87% de installs, Árabe, Tailandés, Nepalí, Ruso, Persa, Amhárico, es-419, etc.). Push automático de fuentes, pull semanal vía PR.
- **`tools\license-move-worker\`**: Cloudflare Worker para mover licencias Dodo entre dispositivos.

## 9. Monetización (Pro)

Reglas ilimitadas, PDF imports ilimitados, CSV export ilimitado, merge de cuentas, backups programados, AI Access. Web (`/pro`: ₹2,000 lifetime / ₹699 año / ₹149 mes vía Dodo), Play Billing (in-app pausado), F-Droid todo gratis. `EntitlementGate` controla el acceso; la license key viaja en backup FULL.

## 10. Hard constraints (de `CLAUDE.md` — no negociables)

1. Dinero siempre currency-tagged (`formatCurrency`), nunca el overload deprecado.
2. Nunca sumar entre monedas (`sumByCurrency`).
3. Todo campo backed-up con default Kotlin (entity + `BackupModels.kt`).
4. Cero PII en código/tests.
5. Versión/changelogs solo via `scripts/release.sh`.
6. Tests de parser con `ParserTestUtils`.
7. Parsers nuevos en la base correcta + registrados en `BankParserFactory`.
8. SQL de migración compatible con SQLite 3.18 (Android 8) — probar en AVD `Api26_Migration`.

---

**Cómo navegarlo después**: `docs\` tiene un doc por área — `architecture.md`, `design.md`, `state-management.md`, `backup-format.md`, `adding-bank-parsers.md`, `database-migrations.md`, `ai-access.md`, `planned-features.md` (roadmap: full picture → forecasting → categorías custom → familia/net worth). El backlog vivo está en `docs\issue-tracker.md` y `TODO.md`.

Si quieres, puedo hacer deep-dive en un subsistema específico (p.ej. el flujo completo de un SMS desde `SmsBroadcastReceiver` hasta la transacción en Room, o el motor de reglas).
