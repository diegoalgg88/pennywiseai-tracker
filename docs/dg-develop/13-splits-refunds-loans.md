# Deep-dive 13 — Splits de transacción, refunds y loan-linked: la semántica de `forTotals()`

> Alcance: cómo una transacción se descompone en partes que cuentan distinto — category splits (`TransactionSplitEntity`/`TransactionWithSplits`), loans y sus contributions (`LoanEntity`/`LoanShareEntity`, `loanId`/`loanContribution`/`lentSharesTotal`), refunds (`DEDUCT_SPENT`/`ADD_TO_LIMIT`), y cómo todo converge en `countedAmount`/`forTotals()` — la matemática financiera más fácil de romper en un refactor. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Una transacción puede tener **tres overlays financieros** sobre su monto bruto: (1) estar **linked a un loan** (parcial o totalmente — `loanContribution` es la parte prestada), (2) estar **split entre personas** (#346 — `lentSharesTotal` es lo que otros te deben, sus shares son a su vez `LoanShareEntity`), y (3) ser un **refund** (INCOME con `budgetImpactType` que devuelve dinero a un `budgetCategory`). `countedAmount()` (`utils\TransactionFilters.kt`) es la única fórmula que decide qué parte es TU gasto; `forTotals()` la aplica a toda entidad. Splits de categoría (`TransactionSplitEntity`) son un eje **ortogonal**: reparten el gasto contado entre categorías para budgets/analytics.

---

## 2. Los overlays

### 2.1 Loan-linked (`LoanEntity` + columnas de la transacción)

**Modelo**: `LoanEntity` (personName, direction LENT/BORROWED, originalAmount, remainingAmount, currency, status ACTIVE/SETTLED). En la transacción: `loanId` (link), `loanContribution` (**override** de la parte que es loan — null = todo el monto).

**Creación y merge** (`data\repository\LoanRepository.kt`):
- `createLoan(...)` desde una transacción: inserta el loan con principal = amount, `linkTransaction`, y `persistContributionOverride` — que **solo escribe la columna si difiere del monto** (*"Equal contributions are treated as 'use the full amount' and left null so legacy data and the common case stay unchanged"*).
- `addToExistingLoan(loanId, contribution, ...)`: sube principal y remaining, requiere `requireNotSplit` — **"A split bill's shares are already its loans (#346); it can't also be marked as one"** (check que lanza si la transacción tiene shares).
- `keepNote` (#876): una nota dada al unir/repagar va a la **descripción de esa transacción** (append con " · "), no al loan — *"the loan already has its note (from the transaction that opened it)"*; nunca sobrescribe.

**Repayment con cap (#831)** — `recordRepayment(loanId, transactionId, contribution?, note)` bajo `repaymentLock`:
- `wanted = contribution ?: txn.amount`; si la transacción ya estaba linked al mismo loan, su contribution previa se **suma de vuelta al owed** (re-link no doble-cuenta).
- **`capped`**: si `wanted > owedNow(loan)`, se recorta a lo adeudado — *"₹500 back on a ₹130 loan repays ₹130, and the other ₹370 stays ordinary income in every total instead of vanishing into a settled loan"*. Cap a **cero incluido** (loan reabierto ya pagado): el pago cuenta completo como income/gasto ordinario.
- `recalculateRemaining(loanId)` al final; `settleLoan` también bajo el lock.

### 2.2 Split bills (#346) — `LoanShareEntity` + `lentSharesTotal`

- Un share = la parte de **una persona** en una cuenta: dinero que te debe bajo el `loanId` indicado, **en la moneda del bill**. `lentSharesTotal` (columna de la transacción) = suma de los shares del bill.
- **Invariante de counts**: los shares de un bill **son** sus loans — por eso `requireNotSplit` prohíbe además marcar el bill como loan, y el dedup del pipeline conserva `loanId/loanContribution/lentSharesTotal` del row viejo al reemplazar (*"links belong to the row, not the SMS"*, doc 07 §5).
- **Límite de 5 personas** por bill; unique index `(transaction_id, loan_id)`.
- **Backup** (doc 09 §6.4): solo se restauran shares sobre loans insertados por el mismo import, con validaciones (gasto unlinked, moneda del loan, cupo en el monto, sin duplicado, <5) — *"a restore can't erase money someone owes"*.

### 2.3 Category splits — `TransactionSplitEntity` + `TransactionWithSplits`

`TransactionWithSplits` (`data\database\entity\TransactionWithSplits.kt`) — relation Room embedded:
- `getAmountByCategory()`: con splits → monto por categoría (groupBy + sum, duplicados tolerados); sin splits → todo bajo la categoría de la transacción.
- `getSplitsTotal()` y `areSplitsValid(tolerance = 0.01)`: los splits deben sumar el monto.
- **`forTotals()` de splits** (doc 10 §5): cuando el bill está loan-linked o split, cada category-split se **escala proporcionalmente** al monto contado con running totals redondeados — sin negativos, suman exacto.

### 2.4 Refunds — `budgetCategory` + `budgetImpactType`

Un INCOME que devuelve dinero lleva: `budgetCategory` (la categoría a la que devuelve) y `budgetImpactType`:
- **`DEDUCT_SPENT`** (refund): resta del gasto de esa categoría (floored a cero, también del padre #374) — en budgets (`aggregateBudgetCategorySpending`, doc 11 §4.1), Analytics y el MCP `spending_summary` (`AiToolLogic`: *"refunds (INCOME + DEDUCT_SPENT) come off spending instead of counting as income"*).
- **`ADD_TO_LIMIT`**: suma a `categoryLimitBoosts` — **aumenta el techo**, no reduce el gasto.

## 3. La fórmula central — `countedAmount()` / `forTotals()`

`utils\TransactionFilters.kt` (KDoc: *"Single source of truth for 'how much of this transaction counts toward a total?' (Home, Analytics, Budgets, widgets, AI summaries, webhooks)"*):

```kotlin
counted = (amount − loanPart − sharesPart).coerceAtLeast(0)
// loanPart = loanContribution ?: amount  si loanId != null
// sharesPart = lentSharesTotal ?: 0
// excludedFromAnalytics → 0
```

`forTotals()` devuelve la entity con monto cortado (o null si 0) y **dropea los overlays** (loanId/loanContribution/lentSharesTotal = null) — idempotencia documentada. Regla dura de uso: **solo para totales** — *"never display the result, its amount isn't what the user paid"*.

**Consumidores**: `aggregateBudgetCategorySpending` (budgets/home/history/webhooks, doc 11), `webhookSummary` (doc 02), `AiToolLogic.spendingSummary` con `forTotals()` (doc 06), Analytics.

## 4. Interacción cruzada (matriz)

| Overlay | En totals (`forTotals`) | En budgets por categoría | En loans UI | En dedup/cleanup |
|---|---|---|---|---|
| Loan completo | cuenta 0 | no aporta | principal del loan | — |
| Loan parcial | cuenta `amount − loanContribution` | idem, por categoría | contribution al principal | `deleteDuplicate` mueve nada — los links viven en el row (doc 07 §5) |
| Split shares | cuenta `amount − lentSharesTotal` | idem | cada share es un loan del participante | duplicate con shares inamovible **no se borra** (#346); goal contributions se mueven al keeper (#914) |
| Refund | cuenta como INCOME normal | **resta** del `budgetCategory` (DEDUCT_SPENT) o **boost** (ADD_TO_LIMIT) | — | — |
| excludedFromAnalytics | 0 | 0 | sigue en loans | — |

## 5. Procedimiento: refactorizar sin romper

1. **Toda suma nueva** sobre transacciones pasa por `forTotals()` ANTES de agregar — un `sum` directo sobre `amount` re-rompe #800/#831/#346 a la vez.
2. **No mover los overlays fuera de la entity**: `loanId`/`loanContribution`/`lentSharesTotal` en la fila es lo que hace idempotente a `forTotals` y lo que el dedup del pipeline conserva al reemplazar.
3. **Cambiar el cap de repayment** (#831) exige pensar el caso "reabierto y ya pagado" — el cap a cero es lo que evita que un pago desaparezca de los totales ordinarios.
4. **Refunds**: un cambio en `DEDUCT_SPENT` debe aplicarse en los TRES sitios que lo interpretan (budgets, Analytics, MCP) — hoy comparten la intención pero no el código; divergir aquí es el bug de la tabla 43k-vs-8k (doc 11 §4.2) en versión income.
5. Tests: los repos tienen tests unitarios de estas reglas; `./init.sh app` tras tocar `TransactionFilters.kt` o `LoanRepository`.
