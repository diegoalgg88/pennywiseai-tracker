# Deep-dive 11 — Presupuestos: budget groups, ventanas, cycle start day y la matemática del gasto

> Alcance: `BudgetEntity`/`BudgetCategoryEntity`/snapshots, la resolución de ventanas (`resolveBudgetWindow`/`windowsForMonth`), el cycle start day, la agregación de gasto (`aggregateBudgetCategorySpending`) con refunds e income handling, group types LIMIT/TARGET/EXPECTED, y los consumidores (History, webhooks, chat). Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Un presupuesto ("budget group") es un **grupo** con un límite total, períodos (WEEKLY/MONTHLY/CUSTOM), y **categorías con asignación propia** (o `matchType` para tracking por tipo). Las ventanas se **resuelven en read-time** desde el tipo de período y el **anchor** del presupuesto — no se persisten: las columnas `startDate/endDate` del row son un *"last computed" cache* y el resolver es lo que toda lectura usa. El gasto usa **una sola función de agregación** (`aggregateBudgetCategorySpending`) que aplica las reglas universales: `forTotals()` (loan/split/excluded), INCOME/TRANSFER fuera, subcategorías acreditadas al padre (#374), refunds restando del `budgetCategory`, conversiones que **fallan a skip** (#670), y type-tracking ruteado a buckets separados.

---

## 2. Modelo de datos

De `data\database\entity\` (doc 00 §3.2):

- **`BudgetEntity`**: `limitAmount`, `periodType` (WEEKLY/MONTHLY/CUSTOM), `startDate`/`endDate` (cache), **`weekStartDay`**/**`monthStartDay`** (anchors), `currency`, `isActive`, `includeAllCategories`, `color`, **`groupType`** (LIMIT/TARGET/EXPECTED), `displayOrder`.
- **`BudgetCategoryEntity`**: `budgetId`, `categoryName`, `budgetAmount`, **`matchType`** — una fila de categoría puede en vez trackear un **tipo de transacción** (INVESTMENT etc.), ruteando su gasto a `typeAmounts` y nunca a categorías.
- **Snapshots** (`BudgetMonthSnapshotEntity` + `BudgetCategoryMonthSnapshotEntity`): copia congelada de límites/colores/groupType/categorías por (budget, year, month) — lo que hace el History "Frozen as of…" inmune a ediciones posteriores del presupuesto.

**Group types**: LIMIT (techo duro), TARGET (meta de gasto), EXPECTED (estimación) — mismo math, distinta lectura en UI.

## 3. Ventanas — la fuente única de verdad (`data\repository\BudgetWindow.kt`)

### 3.1 `resolveBudgetWindow(budget, reference, globalStartDay)` — la ventana actual

| periodType | Ventana |
|---|---|
| **WEEKLY** | anchor `weekStartDay` (1-7, fallback Monday para rows legacy) → `reference.with(previousOrSame(dow))` + 6 días → siempre 7 días |
| **MONTHLY** | anchor `monthStartDay` clamped (`BudgetCycle.clampStartDay`), fallback `globalStartDay` → `BudgetCycle.currentCycle(reference, day)` — el ciclo que **rueda solo**, ej. anchor 25 → 25-sep..24-oct |
| **CUSTOM** | literal `[startDate, endDate]` del row, sin rolling |

KDoc clave: *"The window is resolved at read time… so a recurring budget always shows the current window without needing DB writes"*. El **cycle start day global** (`userPreferencesRepository.getBudgetCycleStartDay()`, default 1 = día 1 del mes) es la preferencia que heredan los MONTHLY sin anchor propio.

### 3.2 `windowsForMonth(budget, year, month)` — la vista histórica

- **WEEKLY**: una ventana por semana ISO que intersecta el mes (4-5), **clipped a los bordes del mes** — *"so the spend query doesn't pull transactions from the adjacent month"*. Esto alimenta el sub-list "per week" de la tarjeta expandida.
- **MONTHLY**: 0 o 1 ciclo — el que contiene el día más reciente del mes. **Decisión de display documentada**: el ciclo se muestra **completo** aunque cruce el mes (anchor 25 visto en octubre = sept-25..oct-24, *"the October page is the right place to show it because the cycle's end falls in October"*); el query de gasto se clipea aparte si hace falta.
- **CUSTOM**: el rango literal clipeado al mes (un one-time nov-5..dic-4 aparece en noviembre y diciembre).

### 3.3 Helpers

- `dayCounts(today, isCurrentMonth)` → (elapsed, remaining) para el math de daily-allowance/days-left. **Guard de crash 2.21.0**: ventana vacía (`days = 0`, placeholder de presupuesto sin ventana este mes) → `(0, 0)`, jamás clamp `1..0`.
- `overlaps(other)` — filtro "Overlap" de la pantalla BudgetGroups.

## 4. El math del gasto (`BudgetGroupRepository.kt`)

### 4.1 `aggregateBudgetCategorySpending` (línea 1115) — top-level por diseño

Firma con **converters inyectables** (*"Same-currency callers pass identity lambdas; the unified-currency call site supplies CurrencyConversionService-backed suspending converters"*):

```kotlin
suspend fun aggregateBudgetCategorySpending(
    transactions, convertSplit, convertIncome, parentOf
): CategoryAggregation   // (categoryAmounts, categoryLimitBoosts, typeAmounts)
```

Pipeline por transacción:
1. `forTotals()` primero (loan/split/excluded — doc 10 §5) — null → skip.
2. `INCOME` y `TRANSFER` fuera del gasto.
3. **Type-tracking**: si el tipo cae en `BUDGET_TYPE_BUCKETS`, el monto completo va a `typeAmounts[type]` **ignorando categoría** — *"it counts only toward a type-tracking budget and never contributes to a category (Spending) budget"*. El converter null → **`?: continue`** — la fila se omite, jamás cuenta face-value (#670).
4. **Categorías**: `getAmountByCategory()` (los **splits** reparten el gasto por categoría); categoría vacía → "Others"; y `parentOf[categoryName]` **acredita también al padre** (#374).
5. **Segunda pasada por INCOME con impacto presupuestario** (`budgetCategory` + `budgetImpactType`):
   - `DEDUCT_SPENT` (**refunds**): `categoryAmounts[cat] = (current - amount).coerceAtLeast(0)` — resta del gasto de esa categoría, floored a cero, también del padre. Es la misma semántica que Analytics y el MCP `spending_summary` (doc 06 §3.2).
   - `ADD_TO_LIMIT`: suma a `categoryLimitBoosts[cat]` — **aumenta el techo** efectivo (`effectiveBudget = budgetAmount + boost`).

### 4.2 `getBudgetWindowBreakdown(budget, window, currency)` (línea 816)

Breakdown por categoría para UNA ventana: transacciones del rango (splits incluidos) → `forTotals()` → `aggregateBudgetCategorySpending` con **identity converters** → por categoría del grupo: `actual` (de `typeAmounts` si `matchType != null`, si no `categoryAmounts`), `effectiveBudget = budgetAmount + boost`, `pctUsed`, `dailySpend = actual / window.days`. Presupuesto "tracking all" (sin categorías) → lista vacía + total desde el fallback no filtrado. **Bug documentado que fijó esta shape**: el shape anterior usaba `sumExpensesForWindow` que contaba TODO EXPENSE del rango — *"a 43k row vs 8k breakdown mismatch"*.

### 4.3 History (`getBudgetHistoryForMonth`, línea ~761)

Una entrada por ventana del mes con **cap date**: mes actual → la semana que contiene hoy es **Live** (cap = today, la ventana se recorta a hoy); toda otra semana **Frozen as of** month-end. Mes histórico → todo frozen. El gasto por fila usa el **mismo** `getBudgetWindowFilteredSpend` del breakdown — consistencia fila vs bottom sheet.

## 5. Consumidores

| Consumidor | Uso |
|---|---|
| **Home/BudgetGroups screens** | `resolveBudgetWindow` + summary de ventanas; daily allowance con `dayCounts` |
| **Budget History** | `getBudgetHistoryForMonth` + snapshots congelados |
| **Webhooks** (`WebhookPayloadBuilder.budgetPayloads`) | `resolveBudgetWindow` + `getBudgetWindowBreakdown(...).totalActual` + categorías (doc 02 §6.2) |
| **Chat IA** | `monthSummary`/`spendingByCategory` desde `AiContextRepository` (semántica propia del mes actual, no por ventana) |
| **Unified currency mode** | Misma agregación con converters reales; pares sin tasa → fila fuera |

## 6. Procedimiento: extender presupuestos

1. **Nueva regla de conteo**: va en `aggregateBudgetCategorySpending` — es la única función que Home/History/webhooks/unified comparten; un cambio local en un caller recrea el mismatch 43k-vs-8k.
2. **Nuevo período/anchor**: extender el `when` de `resolveBudgetWindow` **y** `windowsForMonth` (ambos deben coincidir — uno alimenta "hoy", otro el historial).
3. **Columna nueva**: defaults (backup constraint, doc 09 §3) + snapshot entity si el dato debe congelarse en History.
4. **Moneda**: toda suma nueva pasa por los converters inyectados con `?: continue` en null — nunca face-value.
5. Gate: `./init.sh app`.
