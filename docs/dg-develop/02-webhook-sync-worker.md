# Deep-dive 02 — WebhookSyncWorker: sincronización de datos financieros a endpoints externos

> Alcance: el subsistema completo de webhooks — worker, manager, scheduler, builder de payloads, delivery service, entidades Room, contrato del receptor y gate Pro. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Los webhooks son la **única excepción opt-in** al procesamiento on-device de PennyWise: exportan datos financieros seleccionados (transacciones, presupuestos, cuentas, suscripciones, summary) a un **endpoint que el usuario controla**, vía JSON POST con schema `1.0`. Todo el subsistema es **Pro-gated** (`EntitlementGate.isProEntitled`), con batching (≤250 registros / ≤1 MiB por request), cursores incrementales por tipo de dato, reintentos en dos niveles (3 HTTP + 3 WorkManager), idempotencia del receptor y una política de redirects estricta con una sola excepción documentada (Google Apps Script).

**Contrato oficial completo del receptor**: `docs\webhooks.md` (fuente de verdad; este doc lo desglosa contra el código).

---

## 2. Arquitectura y archivos

```
WebhookSyncScheduler (observa perfiles + intervalo + entitlement → encola trabajo)
        ↓ WorkManager
WebhookSyncWorker (CoroutineWorker @HiltWorker — solo parsea input y decide retry)
        ↓
WebhookSyncManager (orchestration: gate Pro, mutex por perfil, logs, cursores)
        ↓
WebhookPayloadBuilder (arma batches desde repos)  →  WebhookDeliveryService (HTTP POST + redirects + reintentos)
        ↓
WebhookRepository (Room: perfiles, logs, cursores, delivered-transactions)
```

| Archivo (bajo `app\src\main\java\com\pennywiseai\tracker\`) | Rol |
|---|---|
| `worker\WebhookSyncWorker.kt` | Worker de entrada (~40 líneas: delega todo) |
| `data\webhook\WebhookSyncManager.kt` | Orquestación central, Mutex, gate Pro |
| `data\webhook\WebhookSyncScheduler.kt` | PeriodicWork + one-shot, constraints de red |
| `data\webhook\WebhookPayloadBuilder.kt` | Construye envelopes/batches por tipo de dato |
| `data\webhook\WebhookDeliveryService.kt` | Ktor POST, redirects, 3 intentos con backoff |
| `data\webhook\WebhookPayloadEncoding.kt` | Encode a JSON + límite MAX_BYTES (1 MiB) |
| `data\webhook\WebhookValidation.kt` | Valida URL (HTTPS; HTTP solo localhost/127.0.0.1) |
| `data\webhook\WebhookModels.kt` | Payloads (`WebhookEnvelope`, `WebhookTransactionPayload`, …) |
| `data\webhook\WebhookPreferences.kt` | DataStore: `intervalHours` (1–24h) |
| `data\database\entity\WebhookEntities.kt` | 4 entidades Room |
| `data\repository\WebhookRepository.kt` | DAOs + markSuccess/markFailure/decode |
| UI: `ui\screens\settings\webhooks\WebhooksScreen.kt` + `WebhooksViewModel` | Perfiles, Test, Sync, historial |

---

## 3. El worker (`WebhookSyncWorker.kt`)

Deliberadamente fino — toda la lógica vive en el manager:

- **Input** (`inputData`): `reason` (`WebhookSyncReason`, default `INTERVAL`), `test: Boolean`, `profile_id: String?`.
- **Contrato**: un `test` sin `profileId` → `Result.failure()` inmediato.
- **Despacho**: `profileId != null` → `manager.syncProfile(...)`; null → `manager.syncAll(reason)`.
- **Retry**: `result.anyRetryableFailure && runAttemptCount < MAX_RETRIES(3)` → `Result.retry()`; si no → `Result.success()`. Es decir, WorkManager reintenta el worker entero hasta 3 veces cuando el manager reportó fallo reintentable (fallos de red, HTTP 5xx, 429 — ver §6).

## 4. Scheduler (`WebhookSyncScheduler.kt`)

- **Periódico**: `observeSchedule()` combina `(perfiles con enabled, intervalHours, resolvedProEntitlement)` con `distinctUntilChanged()`:
  - Alguien habilitado **y** Pro resuelto `true` → `enqueueUniquePeriodicWork("pennywise_webhook_periodic_sync", UPDATE, PeriodicWorkRequestBuilder<WebhookSyncWorker>(hours, HOURS))` con `Constraints(NetworkType.CONNECTED)`.
  - `pro == false` o nadie habilitado → `cancelUniqueWork`.
  - **`pro == null`** (entitlement cargando) → **no toca nada**: conserva la cadencia mientras carga el check de Play; el manager gatea cada entrega de todos modos (defensa en profundidad, documentada en `docs\webhooks.md`: *"Queued work cannot send data without entitlement"*).
- **One-shot**: `enqueue(reason, test, profileId)` con nombre único `pennywise_webhook_sync_now_<id|all>_<sync|test>` y `ExistingWorkPolicy.KEEP` (no duplica encolas); **gatea al encolar** (`if (!entitlementGate.isProEntitled.value) return`) — lo usa la UI para "Sync now" y "Test".

## 5. Manager (`WebhookSyncManager.kt`) — orquestación

`syncProfile` corre bajo un **`Mutex`** (un perfil nunca se sincroniza concurrente consigo mismo) y devuelve `WebhookSyncRunResult(anySuccess, anyRetryableFailure)`:

1. **Gate Pro** en dos puntos: al entrar y **dentro del loop de batches** (si el entitlement se revoca a mitad de run, se corta la entrega).
2. Perfil no existe → no-op. Perfil `enabled=false` y no es test → no-op (un perfil deshabilitado **sí** puede recibir Test sintético).
3. `builder.build(profile, types, cursors, test)` — si lanza → `markFailure` + log FAILURE *"Could not prepare webhook data"* y termina **sin** reintentar (error de preparación, no de red).
4. Por cada batch: `delivery.deliver(url, headers, envelope)` → **appendLog** SIEMPRE (SUCCESS/FAILURE, httpStatus, message, batch.count) → si falla: `markFailure`, `retry = attempt.retryable`, `break` (no sigue con batches restantes del perfil).
5. Si todo completó y no es test: `repository.markSuccess(profile, now, cursorUpdates)` — **los cursores solo avanzan tras éxito de TODOS los batches**.
6. **Test**: usa `testPayload` sintético (montos "1200/450/750", merchant "Webhook Test Merchant") — **no lee repos financieros ni avanza cursores** (`repository.recordDelivery` tampoco corre).

`syncAll` itera `repository.enabledProfiles()` y agrega los resultados; también gatea Pro y prohíbe test sin perfil.

### 5.1 Entidades Room (`WebhookEntities.kt`)

| Entidad | PK | Campos clave |
|---|---|---|
| `webhook_profiles` | `id` (UUID) | name, url, enabled (default 1), rangePreset (SINCE_LAST_SUCCESS/TODAY/CURRENT_WEEK/CURRENT_MONTH/PREVIOUS_MONTH/LAST_30_DAYS/CUSTOM), customStart/End, **dataTypes** (default `SUMMARY,TRANSACTIONS,BUDGETS,ACCOUNTS,SUBSCRIPTIONS`), headersJson (default `[]`), **currency** (default INR — un perfil exporta UNA moneda), lastError, consecutiveFailures, lastSyncedAt |
| `webhook_logs` | autoGenerate | profileId (FK CASCADE), profileName, syncReason, status (SUCCESS/FAILURE), message, httpStatus, batchCount — índices por profile_id y created_at |
| `webhook_cursors` | **(profile_id, data_type)** | lastSuccessAt, lastRangeEnd — cursor incremental por tipo de dato |
| `webhook_delivered_transactions` | **(profile_id, transaction_id)** | tracking de transacciones ya entregadas (para deletions cross-currency) |

**Nota de backup**: perfiles y headers **NO viajan en el backup JSON** de PennyWise (`docs\webhooks.md`: *"stored in the app's local database and are not included in PennyWise's JSON backups. Recreate them after a restore"*).

---

## 6. Builder de payloads (`WebhookPayloadBuilder.kt`)

### 6.1 Envelope (schema `1.0`)

```
WebhookEnvelope {
  schema_version, generated_at, app {name, version},
  profile {id, name},
  request {range_preset, start, end, currency, data_types[]},
  batch {id, index (1-based), count},
  summary?            (solo en el batch 1),
  transactions[], budgets[], accounts[], subscriptions[]
}
```

### 6.2 Reglas de construcción

- **Cursor incremental** (SINCE_LAST_SUCCESS): `transactionRange.start = cursors[TRANSACTIONS].lastSuccessAt`; si el cursor es null o está **en el futuro** (reloj del dispositivo retrocedió) → `EPOCH` (replay completo). `now` se trunca a **milisegundos** para matchear la precisión de los timestamps SQL — cursores inclusivos.
- **Cambios por updated-at** (incremental): `transactions.getWebhookChanges(start, end, currency, profileId)` — incluye **ediciones de transacciones viejas y tombstones de soft-delete**. Otros presets exportan por timestamp exacto de la transacción + `getWebhookCurrencyRemovals` (deletions de filas cuya moneda cambió).
- **Deletions**: `isDeleted || currency != profileCurrency` → payload mínimo `{id: "txn_<id>", action: "delete", updated_at}` — sin detalles financieros.
- **Montos**: **strings decimales con moneda** (`asPlainStringSafe()`) — nunca números ni monedas mezcladas (invariante del repo aplicada al contrato externo).
- **Accounts**: `account_${bankName}_${last4}` como ID estable, con `creditLimit`, `isCreditCard`, marcador de wallet (`WALLET_ACCOUNT_MARKER`) e `isLoan`.
- **Summary**: respeta exclusiones de analytics y preferencia `countCreditCardAsExpense`; **excluye loan-linked de los totales**; transfers e investments fuera del expense; **splits** por categoría vía `TransactionSplitDao.getTransactionsWithSplitsFiltered`.
- **Budgets**: ventanas activas (`resolveBudgetWindow` con `budgetCycleStartDay`), spending real (`getBudgetWindowBreakdown`), categorías por presupuesto.

### 6.3 Batching en dos ejes

1. **Por registros**: `chunked(250)` por sección.
2. **Por tamaño**: `splitBySize` — divide binariamente hasta que cada envelope encodee ≤ `MAX_BYTES` (1 MiB UTF-8). Detalle fino: antes de medir, **reserva `index/count = Int.MAX_VALUE`** para que el worst-case del numbering no haga pasar un batch que luego re-numerar por encima del límite. La numeración final (`index+1, count=sized.size`) se re-asigna tras el split; el summary queda solo en el primer sub-batch.
3. Edge cases documentados: un solo registro o el metadata solos que excedan 1 MiB → fallo local **sin retry ni avance de cursor**.
4. **Cursores**: solo el preset SINCE_LAST_SUCCESS genera `cursorUpdates` (un update por data type: `(now, range.end)`); los presets de rango fijo **no tocan** el cursor incremental.

### 6.4 Test payload

Datos 100% sintéticos (IDs `test_txn_1`, `test_budget`, `test_account`, `test_subscription`; cuenta "0000") — sirve para validar URL/headers/receiver sin tocar datos reales.

---

## 7. Delivery (`WebhookDeliveryService.kt`)

- **Ktor** (engine Android), `followRedirects = false` (los redirects se manejan a mano), timeouts 30s request / 15s connect / 30s socket.
- **Pre-checks**: `WebhookValidation.validateUrl` (HTTPS obligatorio; HTTP solo `localhost`/`127.0.0.1`) y tamaño ≤ 1 MiB.
- **3 intentos HTTP** con backoff exponencial `delay(1_000L shl attempt)` (1s, 2s) — solo para fallos **retryable**: excepciones de red, HTTP 5xx y 429. Otros HTTP errors (4xx) se registran **sin reintento inmediato** (el próximo intervalo lo intenta de nuevo). → Combinado con el worker: hasta **3 intentos HTTP × 3 WorkManager retries** por entrega fallida reintentable.
- **Política de redirects** (máx 5 hops):
  - 2xx = éxito (el body del receptor no importa).
  - **301/307/308**: preservan método POST + body **solo mismo origen**; redirect cross-origin con body → rechazado.
  - **302/303 → GET**: genéricamente son **fallo** (*"Redirect did not acknowledge webhook delivery"*) — un GET bodyless no puede confirmar entrega. **Única excepción** (`isAppsScriptResponseRedirect`): `https://script.google.com/macros/s/<id>/exec` → `https://script.script.googleusercontent.com/macros/echo` (flujo documentado de Apps Script Content Service).
  - HTTPS→HTTP y redirects a URL inválida → rechazados (*"Unsafe redirect rejected"*).
  - **Headers custom se omiten en cross-origin** (stripping) para no filtrar credenciales a terceros.
- **Errores sin leaks**: los mensajes de error (`WebhookAttemptResult.message`) **no incluyen URLs, bodies ni header values** (privacidad por diseño).

---

## 8. Semántica de datos (resumen del contrato)

| Tema | Regla |
|---|---|
| Primera ejecución | Exporta TODAS las transacciones retenidas en la moneda del perfil |
| Ediciones | El incremental las captura por `updated_at` (upsert con mismos ID) |
| Deletes | Tombstone `{id, action: "delete", updated_at}` — el receptor borra por `profile.id + txn id` |
| Idempotencia del receptor | Obligatoria: un run reintentado puede duplicar transacciones; el cursor inclusivo puede repetir filas; **no usar batch IDs como key de dedup** (un retry genera batch ID nuevo) |
| Snapshots (budgets/accounts/subs) | Acumular por batch y **reemplazar el snapshot solo al completar el run**; sección vacía en run completo = limpiar; run incompleto no reemplaza |
| Cambio de moneda de transacción | El perfil de la moneda vieja recibe deletion sin detalles; el de la nueva recibe upsert |
| Cambio de endpoint del perfil | Limpia el tracking (el receptor viejo se reconcilia aparte) |
| Reparseo SMS / hard delete | **No genera tombstones** — reconciliar manualmente |
| Fuera de sesión Pro | El trabajo encolado no envía nada; el scheduler cancela el periódico al resolver `pro == false` |
| Historial | Últimos 100 logs globales, ≤50 visibles por perfil (UI) |

---

## 9. Gate Pro — puntos exactos de control

| Capa | Chequeo | Comportamiento sin Pro |
|---|---|---|
| `WebhookSyncScheduler.enqueue` | `isProEntitled.value` al encolar | No encola nada |
| `WebhookSyncScheduler.observeSchedule` | `resolvedProEntitlement == true` para el periódico | `null` (cargando) conserva cadencia; `false` cancela |
| `WebhookSyncManager.syncAll/syncProfile` | `isProEntitled.value` al entrar **y en cada batch** | Run aborta; `WebhookSyncRunResult(false, …)` |
| UI (`docs\webhooks.md`) | Entrada visible en Settings → Developer | Free ve la entrada + upgrade prompt; crear/configurar/test/sync requieren Pro |

F-Droid: `FdroidBillingGateway` (build flavor) reporta Pro siempre → webhooks libres ahí.

---

## 10. Extender el subsistema (procedimiento)

1. **Nuevo data type**: añadir al enum `WebhookDataType` → default string de `dataTypes` en la entidad (**con default de columna**, es campo backed-up-adjacente: migración Room + `@ColumnInfo(defaultValue=…)`) → payload model en `WebhookModels.kt` → sección en `WebhookPayloadBuilder` (chunked(250) + batchCount) → receptor: documentarlo en `docs\webhooks.md` (el contrato dice que el receiver acumula snapshots por sección).
2. **Cambio de schema**: bump `schema_version` y documentar en `docs\webhooks.md`; los receivers keyean por `profile.id + txn id`, no por versión.
3. **Tests**: `buildAt(..., time)` es `internal` con tiempo inyectado just para tests deterministas de cursores; `WebhookDeliveryService` acepta engine + `retryDelay` inyectables.
4. Gate: `./init.sh app`. Migración nueva columna Room → checklist de `docs\database-migrations.md` (SQLite 3.18).
