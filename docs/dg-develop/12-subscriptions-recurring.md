# Deep-dive 12 — Subscriptions y recurring: detección desde SMS, mandates, phantom income y templates

> Alcance: el sistema completo de pagos recurrentes — detección automática desde e-mandates/UPI mandates del SMS (`MandateInfo` en parser-core → `SubscriptionRepository`), el match cargo↔suscripción, el adelanto de `nextPaymentDate` cycle-aware, el **phantom income autopay** (#371), las plantillas recurrentes manuales (#706) y sus cuotas free. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Hay **tres orígenes** de recurrencia: (1) **mandates bancarios** detectados en el SMS (HDFC e-mandate/future-debit, SBI UPI mandate, PNB, Federal, IndianBank, IndusInd balance) que crean/actualizan `SubscriptionEntity`; (2) **suscripciones manuales** desde Add Transaction (pestaña Suscripción, con `direction` EXPENSE/INCOME); (3) **plantillas recurrentes manuales** (#706) para gastos sin SMS detrás. Los cargos reales se detectan con `matchTransactionToSubscription` (merchant + tolerancia de monto 5%) y adelantan la fecha con aritmética de calendario **cycle-aware**. Las suscripciones de **ingreso** pueden materializar transacciones **phantom** automáticas cuando la cuenta nunca envía SMS (#371).

---

## 2. Detección desde SMS — mandates (`MandateInfo`)

### 2.1 El contrato en parser-core

Interfaz `parser-core\src\main\kotlin\com\pennywiseai\parser\core\MandateInfo.kt`: `amount`, `nextDeductionDate`, `merchant`, `umn`, `accountLast4`, `dateFormat` — implementada por cada parser con mandates (`HDFCBankParser.EMandateInfo`, `SBIBankParser.UPIMandateInfo`, `FederalBankParser.EMandateInfo`, `IndianBankParser.IndianMandateInfo`, …). Los métodos `parseEMandateSubscription`/`parseUPIMandateSubscription`/`parseFutureDebit` son **parse puro, cero DB** (doc 07 §2.1).

### 2.2 El dispatch en el worker (`OptimizedSmsReaderWorker.checkSubscriptionOrBalance`)

Cada banco con special-case devuelve un `ParseResult.SpecialNotification` cuyo `onSave` captura la llamada tipada del repository. Reglas de recencia verificadas en código:

| Parser | Gate | onSave |
|---|---|---|
| **SBI** | `isUPIMandateNotification && isRecent` (≤30 días) | `createOrUpdateFromSBIMandate(info, bank, body)` |
| **PNB** | igual SBI | `createOrUpdateFromMandate` |
| **Federal** | mandate creation **sin gate de recencia**; future-debit: `isRecent \|\| paymentStillUpcoming` — *"a mandate from 45 days ago with next-debit next week must still create the subscription"* (parsea `nextDeductionDate` con el `dateFormat` del mandate y compara contra hoy) | `createOrUpdateFromFederalBankMandate` |
| **HDFC** | `isBalanceUpdateNotification` → balance update; `isEMandateNotification && isRecent` → suscripción; `isFutureDebitNotification && isRecent` → suscripción; **múltiples acciones acumuladas** en una sola `SpecialNotification` | una por una, con try/catch individual |
| **IndianBank** | `isMandateNotification && isRecent` | `createOrUpdateFromIndianBankMandate` |

### 2.3 `createOrUpdateFromMandate` (`SubscriptionRepository.kt:197`)

- `nextPaymentDate`: parsea `nextDeductionDate` con el `dateFormat` del mandate; **fallback +30 días** si falla el parse o no viene.
- **UMN como identidad**: si el mandate trae `umn` (Unique Mandate Number — HDFC/SBI/Federal lo dan), se busca `getSubscriptionByUmn` — el UMN es el dedup natural del mandate; sin UMN cae a la búsqueda por merchant.
- `smsBody` se guarda en el row (traza del origen).

## 3. El match cargo↔suscripción y el adelanto de fecha

### 3.1 `matchTransactionToSubscription(merchantName, amount)` (línea 99)

`getActiveSubscriptionByMerchant(merchantName)` + `areAmountsEqual` — **tolerancia de 5%** (`diff <= amount1 * 0.05`, documentado: *"Allow for small variations"*). Llamado desde el pipeline SMS tras aplicar reglas (doc 07 §5): si matchea, la transacción se marca `isRecurring = true` y se adelanta la suscripción.

### 3.2 `updateNextPaymentDateAfterCharge` (línea 120) + `advance` 

- **Cycle-aware** (#371): antes hard-codeaba `+30 días` ignorando el ciclo elegido del usuario. Ahora avanza por `billingCycle` con **aritmética de calendario real** — *"Monthly lands on the same day-of-month next month, not strictly +30 days"*:
  - WEEKLY `plusWeeks(1)` · MONTHLY `plusMonths(1)` · QUARTERLY `+3M` · SEMI-ANNUAL (3 grafías toleradas) `+6M` · ANNUAL/YEARLY `+1Y`.
  - **Fallback a monthly** para ciclos desconocidos — *"a stale or hand-inserted row never silently advances by 0 days"*.
- `reverse = true` resta (para `MarkAsPaidSheet` que puede retroceder una fecha).

### 3.3 Estados y `markPaid`

`SubscriptionEntity.state`: ACTIVE/HIDDEN/ENDED + `lastPaidAt` + `nextPaymentDate` + `billingCycle` + `direction` (EXPENSE/INCOME) + `currency` + `accountLast4`/`bankName`. `markPaid(subId, paidAt, nextPaymentDate)` es el passthrough del DAO para el sheet "Mark as paid" — fija `lastPaidAt` y la próxima fecha (adelantada con `advance`).

## 4. Phantom income autopay (#371) — `domain\usecase\GenerateIncomeAutopayUseCase.kt`

El KDoc es la especificación completa: algunas cuentas (wallets, allowances, top-ups internos) **no envían SMS al llegar dinero**; el usuario declara una vez "₹X esperado el día N de cada mes" como suscripción INCOME, y el use case **materializa la transacción cuando vence**.

| Propiedad | Implementación |
|---|---|
| **Disparo** | Al final de CADA SMS scan (`OptimizedSmsReaderWorker.cleanUpAndFinalize`) — corre tras el proceso de SMS para que un INCOME SMS real de la misma cantidad+merchant pueda dedup contra el hash del autopay |
| **Idempotencia** | `transactionHash = "autopay-<subId>-<scheduledDate>"` — segunda pasada el mismo día = dedup por `getTransactionByHash` |
| **Catch-up multi-ciclo** | `while (!scheduled.isAfter(today))` — materializa **todos** los ciclos perdidos (*"a user who didn't open the app for two months should see both months' phantom income"*), avanzando con `advance(scheduled, billingCycle)` |
| **Atomicidad (#570)** | `insertTransactionWithBalance` — phantom + crédito de la cuenta de funding **atómicos**; *"a crash between the two can't leave the row present (its hash then blocking every retry) with the balance un-credited"*. No-op del lado balance para suscripciones sin cuenta linked |
| **Row** | `transactionType = INCOME`, `isRecurring = true`, `description = "Scheduled income (autopay)"`, moneda y categoría de la suscripción (fallback "Income") |
| **Post-estado** | `updateNextPaymentDate(sub.id, scheduled)` — persiste la fecha avanzada final para no replayear |

## 5. Plantillas recurrentes manuales (#706)

- **`RecurringTransactionEntity`** (doc 00 §3.2): merchant, amount, currency, category, type, frequency DAILY/WEEKLY/MONTHLY, `dayOfMonth`/`dayOfWeek`, `nextDueDate`, isActive, cuenta opcional.
- **`RecurringTransactionWorker`**: periódico **diario** (`ExistingPeriodicWorkPolicy.KEEP` — *"KEEP so re-enqueuing on every app start doesn't reset the schedule"*) + **one-shot catch-up al app start** (`REPLACE`) — *"a device that was off still materialises anything that came due while it was down"*.
- `materializeDue(LocalDate.now())` vive en `RecurringTransactionRepository` — *"All the double-insertion safety lives in [materializeDue]"*; tras insertar refresca widgets.
- **Cuota free**: `FreeTierLimits.MAX_RECURRING_TEMPLATES = 1` (doc 04 §6) — un lapso de Pro nunca borra las materializadas.

## 6. Interacción con el resto del sistema

| Consumidor | Efecto |
|---|---|
| **Pipeline SMS** | Match tras reglas → `isRecurring = true` + `updateNextPaymentDateAfterCharge` (doc 07 §5) |
| **Home** | "Suscripciones próximas" — orden por `nextPaymentDate` |
| **Subscriptions screen** | Lista + `MarkAsPaidSheet` + states |
| **AI Access MCP** | `list_subscriptions` — activas con monto/moneda/next payment (doc 06 §3.2) |
| **Webhooks** | `subscriptionPayloads` — snapshot de suscripciones en la moneda del perfil (doc 02 §6.2) |
| **Backup** | Suscripciones en `DatabaseSnapshot`; dedup de MERGE por merchant+amount (doc 09 §6.2) |
| **Presupuestos** | Una suscripción materializada cuenta como EXPENSE normal en ventanas (vía `forTotals`) |

## 7. Procedimiento: extender

1. **Nuevo banco con mandates**: implementar `MandateInfo` + `parse*Mandate` en el parser (parse puro) → caso en `checkSubscriptionOrBalance` con el gate de recencia adecuado (¿el mandate viejo sigue siendo actionable? seguir el patrón Federal `paymentStillUpcoming`) → rama en `createOrUpdateFromMandate` ya no hace falta: el método unificado consume la interfaz.
2. **Nuevo billingCycle**: extender el `when` de `advance` (mantener el fallback monthly) + su test.
3. **Cambiar la tolerancia del match**: tocar `areAmountsEqual` — pensar en falsos positivos (misma cadena "Amazon" con montos distintos ±5%).
4. **Phantom expenses**: NO existen hoy — solo INCOME (`getDueIncomeSubscriptions`); introducirlos replicaría decisiones de #570 (atomicidad) y el hash `"autopay-<id>-<date>"`.
5. Gate: `./init.sh app`.
