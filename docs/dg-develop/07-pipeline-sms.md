# Deep-dive 07 — Pipeline SMS: recepción, parsing, dedup, balances y re-scan

> Alcance: el camino completo de un SMS bancario desde el inbox del teléfono hasta una fila en Room con balance — `SmsBroadcastReceiver` (tiempo real), `OptimizedSmsReaderWorker` (scan histórico + re-scan), `TransactionDeduplication`, `SmsScanParamsCalculator`, dedup cross-canal (notificaciones bancarias), limpieza de duplicados GPay, y la interacción con reglas/subscriptions. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Hay **dos entradas** al mismo pipeline: el **receiver en tiempo real** (`SmsBroadcastReceiver`, un SMS a la vez, vía `SmsTransactionProcessor`) y el **worker histórico** (`OptimizedSmsReaderWorker`, miles de mensajes en pipeline paralelo). Ambos comparten la misma cadena de validaciones: parseo (`BankParserFactory`) → gates de cuentas ignoradas → **dedup multicapa** (hash único, SMS body, UPI reference, cross-canal notificación) → **reglas** (block/modify) → match de suscripción → insert + **balance** (en una cuenta que puede ser wallet, tarjeta o préstamo). El dedup es el artefacto más maduro del repo: cada capa existe por un bug real documentado (#401, #456, #703, #886, #888).

---

## 2. Arquitectura del worker (`worker\OptimizedSmsReaderWorker.kt`)

```
readSmsMessages() ──► [feed Channel(512)]
                          │ (N coroutines, Dispatchers.Default — N = cores−1)
                          ▼
                  [parse en paralelo] ──► [results Channel(512)]
                                              │ (1 coroutine, Dispatchers.IO)
                                              ▼
                              [save secuencial + balance updates concurrentes]
```

Verbatim del KDoc del archivo: *"parallel parse → sequential save pipeline"*. El save es **secuencial a propósito** (no hay race de balances entre transacciones del mismo banco), pero las actualizaciones de balance se despachan a un **consumer dedicado** (`balanceUpdates` Channel) para que no bloqueen al saver — ambas bajo el `BankAccountMergeStore.mutationMutex` global.

**Optimización de hot path** (cada una comentada en el código con el costo que elimina):
- `parserCache: ConcurrentHashMap<sender, List<BankParser>>` — la factory corre **una vez por sender único**, no por SMS.
- `merchantMappingCache` — 1 lectura de DB por scan en vez de 1 por transacción.
- `ruleCache: Map<TransactionType, List<TransactionRule>>` — 4 lecturas (una por TransactionType) en vez de 1 por transacción.
- `thirtyDaysAgoMillis` y `systemZone` cacheados — eliminan system calls por SMS.
- Cuentas previas al query con `COUNT(*)` (cero bodies leídos) → progreso y ETA desde t=0.

### 2.1 El resultado del parse: sealed class (estados imposibles irrepresentables)

| `ParseResult` | Causa | Destino |
|---|---|---|
| `Discard` | Sender `-P`/`-G` (DLT promo/gov), sin parser y no candidato, o parse null | Se descarta silencioso |
| `StoreUnrecognized` | Sender `-T`/`-S` (DLT transaccional indio) sin parser, **o** sender alfanumérico extranjero con monto+moneda en el body (`isUnrecognizedCandidate` — regex `[€$£₹]` o código ISO 4217 real) | Tabla `unrecognized_sms` en batches de 50 — es el flujo de "reportar mi banco" |
| `SpecialNotification` | Mandate de suscripción (SBI/PNB/Federal/HDFC/IndianBank/IndusInd) o balance update (HDFC/IndusInd) | `onSave: suspend () -> Unit` capturado **en parse time** con los tipos ya resueltos, ejecutado en el saver |
| `Transaction` | Débito/crédito normal | `saveTransaction` |

**Detalle fino de `checkSubscriptionOrBalance`**: Federal Bank procesa un future-debit viejo si la fecha de próximo débito **sigue en el futuro** (`paymentStillUpcoming`) — un mandate de hace 45 días con débito la semana próxima igual crea la suscripción. Solo bancos con type-specific special-case usan el parser primario (los M-Pesa de sender compartido no se special-casean).

### 2.2 RCS además de SMS

`streamRcsToChannel` lee `content://mms` con `tr_id LIKE 'proto:%'` (RCS business messages de Google), decodifica el sender del base64 del tr_id (regex `agent@rbm.goog`), extrae el texto de las parts (`mms/part`), y hasta texto embebido en JSON de RCS (extractor recursivo con skip de `media/suggestions/postback`). Mismo pipeline que SMS.

---

## 3. Ventanas de scan (`data\manager\SmsScanParamsCalculator.kt`)

- **Incremental**: `scanStartTime = max(min(lastScanTimestamp, now−3d), periodLimit)` — relee hasta 3 días atrás siempre (SMS que llegaron tarde al inbox), limitado por el período elegido.
- **needsFullScan** = forceResync, primer scan, o **toggle** de período (ampliar meses, all-time on/off, custom date on/off) — comparando contra `lastScanPeriod` persistido.
- El período límite: `scanMonths` meses atrás **a inicio de día local**, o custom date, o 0 (all-time). `normalizePickerDateToLocalStartOfDay` corrige el offset UTC del date picker.
- **Orden crítico documentado**: la ventana se computa **antes** de cualquier delete — *"a rebuild may only drop rows it will re-read"*.

## 4. Force resync / re-scan — qué sobrevive

| Modo | Borrar | Conservar |
|---|---|---|
| `INPUT_RESCAN_ALL` | **Nada** — relee la ventana entera sin borrar; agrega lo que un scan anterior perdió y respeta toda edición | Todo |
| `INPUT_FORCE_RESYNC` | `deleteRebuildableSmsTransactions(scanStart)` + `deleteRebuildableBalances()` | Loans, groups, filas soft-deleted, manuales, importadas, de notificación — *"nothing would bring them back"* (fix #401) |

- El re-parse no duplica: `transaction_hash` es **UNIQUE** + `OnConflictStrategy.IGNORE` — las filas que sobrevivieron colisionan y se saltan.
- **Orden del borrado documentado**: transacciones PRIMERO, balances después — si no, `deleteAllBalances()` borraría balances de transacciones preservadas y el re-parse jamás los regeneraría (hash collision short-circuita `processBalanceUpdate`), dejando huecos en la serie de balances.
- **Preservación de tipos de cuenta (#792)**: `accountsBeforeRebuild` snapshot de las cuentas con `accountType != null` antes del wipe; tras el rebuild, `restoreAccountType` re-etiqueta. El comentario lo dice: *"A user-chosen type lives only on those rows"*. Una fila typed existente gana sobre el snapshot (tipo cambiado mid-run se respeta); el **math de balance** parte del histórico rebuild (`existing`), nunca del balance pre-resync.
- Consecuencia para webhooks (doc 02): el re-parse **no genera tombstones** — el receiver externo se reconcilia aparte (`docs\webhooks.md`).

---

## 5. Dedup — las 5 capas (orden de `saveTransaction`)

Cada capa existe por un bug real:

| # | Capa | Mecanismo | Bug que previene |
|---|---|---|---|
| 1 | **Hash único** | `transactionHash` (md5 sender+monto+body) UNIQUE + IGNORE; lookup `getTransactionByHash` antes del insert | Doble importación del mismo SMS |
| 2 | **Cuentas ignoradas** | `ignoredAccountsStore.isIgnored(bank, last4, linkedAccount)` — incluye la cuenta detrás de una debit card (#826) — un rescan no re-importa lo excluido | Re-import de cuentas excluidas |
| 3 | **Cross-canal notificación** | Si el banco está en `BankNotificationConfig.notificationBankNames`: busca por monto ±2 min **incluyendo soft-deleted**; `TransactionDeduplication.isBookedByOtherChannel` exige que el row previo sea del **otro canal** (dos SMS iguales a 1 min son dos compras reales) y cuentas coincidentes | El mismo cargo via SMS + push de la app bancaria se book-ando dos veces; un row que el usuario borró tras su notificación no resucita por el SMS |
| 4 | **SMS body como identidad estable** | `getAnyBySms(body, sender)`: si ya existe, NO se inserta — y si el stored es un **parse artifact** (`isParseArtifact`: decimales caídos "Rs.11348.7"→11348 #886, o el límite de tarjeta guardado como monto #888), se **corrige in-place** (`correctParsedAmount`); un row soft-deleted no resucita (#703) | El hash incluye el monto parseado — un fix de parser cambia el hash y duplicaría el SMS; el body crudo es la identidad |
| 5 | **Reglas Block** | `ruleEngine.shouldBlockTransaction(mapped, sms.body, activeRules)` — contado en `stats.blocked`, no guardado | Reglas de bloqueo respetadas en rescan |

Después: `evaluateRules` aplica modificaciones, `matchTransactionToSubscription` marca recurrente y adelanta `nextPaymentDate`, y `findPotentialDuplicates` decide **reemplazar o saltar**:
- `shouldReplaceWithIncoming` (UPI): mismo reference de 12 dígitos + tipo + monto + moneda + cuenta en ventana de 24h (`UPI_DUPLICATE_WINDOW` — carrier delays). **Precedencia GPay**: un row del partner bank ("State Bank of India") es reemplazado por el del banco real; el incoming partner-bank no reemplaza al real. Tie-breaker: el que trae `balanceAfter` gana. Si reemplaza: conserva `createdAt`, `isRecurring` OR, y **loanId/loanContribution/lentSharesTotal del row viejo** (*"links belong to the row, not the SMS"*, #346), borra balances del viejo, re-escribe rule applications.
- El **keeper quality** en `duplicatesToKeepers` (usado por cleanup): prioriza banco real > tiene balance > más reciente > id menor.

### 5.1 Limpieza post-run (`cleanUpAndFinalize`)

- `cleanupSmsDuplicates`: agrupa filas activas por `(smsBody, sender)`; conserva la **más vieja** (lleva categoría/notas/tags del usuario), repara su monto si es parse artifact, y borra el resto con `deleteDuplicate(dup.id, keep.id)` — que **mueve goal contributions al keeper (#914)** y se niega a borrar un duplicate con split shares que no puede mover (#346). Todo en `database.withTransaction`.
- `cleanupExistingGPayDuplicates` + `prunePhantomGPayAccounts` (#456): los P2P de GPay se atribuyen al partner bank; tras el dedup quedan **balance rows sin transacción** (transaction_id NULL) que sobreviven como "cuenta fantasma" con balance inflado. `deletePhantomGPayAccountIfQualifies` califica y borra **atómicamente en una transacción** — *"a concurrent real-time balance insert can't be erased between the check and the delete"*.
- `unrecognizedSmsRepository.cleanupOldEntries()`.
- Si hubo saves: `llmRepository.updateSystemPrompt()` — el chat IA se entera de las cifras nuevas.
- `GenerateIncomeAutopayUseCase.execute()` (#371): materializa **phantom income** de suscripciones de ingreso (autopay), corre tras el SMS para que un INCOME SMS real de la misma cantidad pueda dedup por el hash del autopay.

## 6. Actualización de balance (`processBalanceUpdate`)

Cada transacción con balance/características de cuenta genera un row nuevo en `AccountBalanceEntity` (serie de tiempo, no un valor mutable):

1. **Re-resuelve la cuenta al consumo** — el row pudo moverse desde el enqueue (merge incluido): `input.forSavedTransaction(...)`.
2. **Tarjetas**: si `isFromCard`, upsert del `CardEntity` + `updateCardBalance`; el balance va a la cuenta de la **tarjeta**; si es DEBIT con cuenta linked, el balance va a la **cuenta** (`card.accountLast4`).
3. **Wallets móviles** (eMola, M-Pesa Mozambique): sin número de cuenta → `upsertWalletBalance` con `WALLET_ACCOUNT_MARKER` — *"the whole wallet is one account"*.
4. **Math**: `BalanceCalculator.balanceAfter(explicitBalance, isCreditCard||isLoan, type, amount, txnCurrency, accountCurrency, current)` — el SMS trae el balance explícito la mayoría de las veces; si no, se deriva. **Moneda de la cuenta inmutable**: un gasto en divisa extranjera no toca el balance de la cuenta (`accountCurrency` gana).
5. **Límite de tarjeta de crédito (#486)**: los SMS reportan el límite **disponible**; la UI deriva `available = creditLimit − balance`, así que se guarda `total = available + outstanding` (`creditLimit + newBalance`).
6. **isLoan (#792)**: un pago a cuenta de préstamo baja el adeudo — se trata como credit card en el math.
7. `insertBalance` con `smsSource.take(500)`, `sourceType = "TRANSACTION"`, preservando alias/lowBalanceThreshold/profileId del row existente.

## 7. Recepción en tiempo real (`receiver\SmsBroadcastReceiver.kt`)

- `SMS_RECEIVED` con `goAsync()`: agrupa **multi-part SMS por sender** (timestamp = el más temprano), procesa cada sender en paralelo (`joinAll`), `finish()` al final.
- Delega en `SmsTransactionProcessor.processAndSaveTransaction` — la misma cadena de gates que el worker (hash, ignored accounts, reglas, dedup).
- Si la app está en foreground: **no notifica** (la UI ya muestra el row).
- **Notificación de transacción**:
  - Título con monto+merchant (`CurrencyFormatter.formatCurrency(amount, currency)`), lock-screen con emoji por tipo.
  - **Sin categorizar → quick picks (#303)**: máximo 3 actions (Android colapsa más) — 2 categorías más usadas del tipo (`quickPicks`: ranking de uso topped-up con las ofrecidas, excluyendo "Others") + el picker translúcido completo (`QuickCategoryPickerActivity`).
  - `categoryRequestCode(txId, slot) = txId*10+slot` — request codes únicos por transacción, sin alias entre notificaciones.
  - **Balance discrepancy (#734)**: `DetectBalanceDiscrepancyUseCase` compara ledger vs balance reportado; si discrepan, la notificación lleva la línea y `VISIBILITY_PRIVATE` + **public version sin cifras** (balances fuera del lock screen).
  - **Stacked alerts**: `GROUP_KEY` + summary con `InboxStyle` y `GROUP_ALERT_CHILDREN`; el header se re-cuenta asincrónicamente con un `postDelayed(1s)` — *"two at once each read a shade that showed neither yet"* (settling).
  - **Learned notification**: un quick pick enseña el merchant (`LearnCategoryUseCase`), reemplaza el alert con nota silenciosa "saved — and learned" con **Undo** (slot 8, 15s timeout); solo la lección más reciente del merchant es undoable; moves de filas anteriores se cuentan en el texto.

## 8. Métricas y UX del scan

- `ProcessingStats` con **ring buffer 8192** para msg/s — el comentario documenta el bug del ETA: un ring de 200 a 500 msg/s wrapeaba en 0.4s y medía 5× inflado; ahora bounds-check + warmup fallback (promedio total si <10 samples) + ETA clampeado a 0.
- Progreso vía `setProgress` cada 250ms; `foregroundInfo()` con notificación silenciosa IMPORTANCE_LOW; `Process.setThreadPriority(THREAD_PRIORITY_FOREGROUND)` en feed/parsers/saver.
- **Bug del result documentado**: WorkManager borra `progress` al terminar → un caller leyendo en SUCCEEDED veía ceros ("No transactions found" tras importar todo el inbox) → `completionData()` devuelve los conteos como **output data**.
- Final: `setLastScanTimestamp(now)` solo tras éxito de todas las etapas; `lastScanPeriod` actualizado solo si `needsFullScan`.

## 9. Extender el pipeline (procedimiento)

1. **Nuevo gate de transacción**: insertarlo en `saveTransaction` **dentro** del `mutationMutex` + `coroutineScope`, siguiendo el patrón early-return `SaveOutcome.SKIPPED` — y replicarlo en `SmsTransactionProcessor` (la entrada en vivo usa el processor, no el worker).
2. **Nuevo tipo de special notification**: extender el `when` de `checkSubscriptionOrBalance` (parse puro, cero DB) devolviendo `SpecialNotification` con el `onSave` capturado.
3. **Nueva fuente de mensajes** (p. ej. otro content provider): seguir `streamSmsToChannel`/`streamRcsToChannel` + su `count*` correspondiente antes de tocar el pipeline.
4. **Cambiar dedup**: toda capa nueva va ANTES del insert y con su bug-# documentado en comentario — es la convención del archivo; tocar `TransactionDeduplication` exige pensar el caso cross-worker (receiver en vivo + scan simultáneo: por eso el `mutationMutex` global).
5. Gate: `./init.sh app`. Verificación runtime: skill emulator-verify con SMS de prueba (el `SmsProbeReceiver` debug-only puede inyectar SMS sintéticos por la ruta real).
