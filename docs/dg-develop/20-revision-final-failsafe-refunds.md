# Deep-dive 20 — Revisión final: el patrón "fail-safe + issue #" y el mapa real de refunds (`DEDUCT_SPENT`)

> Cierre de la serie 07–19: dos hallazgos transversales que merecen su propio documento — (1) el patrón de diseño dominante del codebase (comentario que documenta el fail-safe Y el issue que lo motivó) con su catálogo de instancias, y (2) el mapa completo de los refunds `DEDUCT_SPENT`, que resultó ser **más amplio que los 3 sitios** estimados en el doc 13. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## Parte A — El patrón "fail-safe + issue #"

### A.1 Descripción del patrón

En todo el codebase, una decisión defensiva **no-objetiva** (elegir un fail-safe sobre otro) va acompañada de un comentario que cumple 3 funciones:

1. **Qué hace el código** (el fail-safe elegido).
2. **Qué evita** (el modo de fallo concreto, no abstracto).
3. **La referencia**: el issue/PR que lo motivó, o el trade-off rechazado.

El patrón es verificable por grep de `#<número>` en comentarios — es la convención de facturacion del repo: sin el issue, un fail-safe parece paranoia y el primer refactor lo elimina.

### A.2 Catálogo de instancias (por subsistema, con su issue)

| Subsistema | Instancia del patrón | Issue |
|---|---|---|
| **Dedup SMS** | SMS body como identidad estable en vez del hash (un fix de parser cambia el hash y duplicaría); corrección in-place de parse artifacts | #886, #888, #703 (doc 07 §5 capa 4) |
| **Dedup SMS** | `isBookedByOtherChannel` exige canal distinto — dos SMS iguales a 1 min son dos compras reales | #401, #456 (doc 07 §5 capa 3) |
| **Re-scan** | `deleteRebuildable*` preserva manual/importadas/notification rows — *"nothing would bring them back"* | #401 (doc 07 §4) |
| **Re-scan** | Snapshot + restore de `accountType` — *"a user-chosen type lives only on those rows"* | #792 (doc 07 §4) |
| **Dedup GPay** | Borrado atómico de cuentas fantasma — *"a concurrent real-time balance insert can't be erased between the check and the delete"* | #456 (doc 07 §5.1) |
| **Licencias** | Offline: Play fallido conserva el cache — *"false positives are far less harmful than false negatives"*; restore: activar-antes-de-desactivar — *"a transient failure never leaves the old phone revoked with nothing activated here"* | doc 04 §3-4 |
| **Entitlement** | `resolvedProEntitlement = null` mientras carga — *"background schedules do not treat startup as revocation"* | doc 04 §2 |
| **Chat IA** | Regex `CLAIMS_A_CHANGE` + nota "Nothing was changed" — un 1.5B narra cambios que no hizo | #170, #767 (doc 01 §4) |
| **Chat IA** | System prompt thin — el modelo parrotaba transacciones embebidas dentro de args de tools | #170 (doc 01 §8) |
| **Chat IA** | `verifyModelIntegrity` re-hash en cada carga, sin atajos — *"this load-time security boundary must actually read the bytes"* | doc 01 §7 |
| **Backup** | Defaults en TODO campo serializado — Gson ignoraba defaults y el restore entero crasheaba | #414 (doc 09 §3) |
| **Backup receipts** | Path traversal check + nunca sobreescribir + solo si una transacción lo referencia | #839 (doc 09 §5) |
| **Backup** | License adoption: escribe con `instanceId=null` que no otorga nada hasta activar en Dodo | doc 09 §8 |
| **Backup splits** | Solo restaurar shares sobre loans de este import; loans jamás tocados — *"a restore can't erase money someone owes"* | #346 (doc 09 §6.4) |
| **Multi-moneda** | `Money.plus` lanza; `convertAmountOrNull` skip — *"summing a face-value foreign amount is never right"* | #670, #545 (doc 10 §2, §6.1) |
| **Totales** | `forTotals()` dropea overlays para ser idempotente; el resto #831 del repayment cap — *"₹370 stays ordinary income instead of vanishing into a settled loan"* | #831, #346 (doc 13 §2-3) |
| **Presupuestos** | Converters inyectados + `?: continue`; el shape viejo usaba un sum sin forTotals — *"43k row vs 8k breakdown mismatch"* | #670 (doc 11 §4.2) |
| **Presupuestos** | Ventana vacía → `(0,0)` day counts, jamás clamp `1..0` — crash 2.21.0 | doc 11 §3.3 |
| **Subscriptions** | Phantom autopay atómico insert+balance — *"a crash between the two can't leave the row present blocking every retry"* | #570 (doc 12 §4) |
| **Reglas** | `supportedOperators`/`supportedActionTypes` + `RuleActionExecutabilityTest` — *"anything outside these sets is silently dead"* | doc 08 §2-3 |
| **Worker SMS** | `completionData()` como output — WorkManager borra progress en SUCCEEDED y el caller veía ceros | doc 07 §8 |
| **Categorización** | `PLACEHOLDER_NAMES` por shape — un canal ("UPI Payment") no es un lugar; picking para uno files solo ese row | doc 15 §4.1 |
| **SEO web** | Catálogo generado de la factory viva con CI guard — *"we can never publish a page for a bank we don't actually parse"* | doc 18 §4 |

### A.3 Cómo aplicar el patrón (regla de contribución)

1. Todo fail-safe nuevo lleva comentario con las 3 partes (§A.1) — y el issue real, no un número decorativo.
2. Al leer un fail-safe ajeno, **el issue citado es la especificación** — no "simplificarlo" sin entender el bug.
3. Los guardes de CI (BackupSchemaGuardTest, RuleActionExecutabilityTest, SupportedBanksDocTest, MigrationSqlCompatibilityTest, CurrencyFormatterTest) son la versión ejecutable del patrón: fallan el build cuando el comentario no alcanzó.

---

## Parte B — El mapa real de refunds (`DEDUCT_SPENT`)

### B.1 Corrección al doc 13

El doc 13 (§5) estimó "3 sitios que interpretan refunds (budgets/Analytics/MCP)". El grep del 2026-10-24 (`DEDUCT_SPENT`, 40 matches) revela **6 consumidores**, pero con una estructura más sana de lo que parecía: **la mayoría delega en el aggregator canónico o replica una semántica documentada con el mismo issue ancla**.

### B.2 Mapa completo de sitios (evidencia grep + lectura)

| # | Sitio | Rol | Mecanismo | Issue ancla |
|---|---|---|---|---|
| 1 | `BudgetGroupRepository.aggregateBudgetCategorySpending` (:1115, rama `DEDUCT_SPENT` :1158) | **Canónico** — budgets, Home, History | Resta del `categoryAmounts[budgetCategory]` con floor a cero, también del padre | #374 |
| 2 | `BudgetWidgetUpdateWorker` (:96-140, :228) | Widget de presupuesto | **Reutiliza el aggregator**: *"Reuse the same aggregator the Budgets screen uses so the widget applies Refund (DEDUCT_SPENT) and Extra budget (ADD_TO_LIMIT) identically"* + caso **orphaned** (§B.3) | #670 |
| 3 | `AnalyticsViewModel` (:50-130, `analyticsRowsFor` + `RefundNetting` + `netOf`) | Analytics | Implementación propia pero **documentada como espejo**: la vista Income dropea refunds (*"counting them as income too would double-count them. Mirrors the Home card (#629)"*); `RefundNetting` los resta de CADA superficie de gasto (total, categoría, cuenta, merchant, día) con floor a cero; el total headline lleva el refund COMPLETO aunque los buckets flooreen — *"an over-refund or orphaned refund can leave the total below the sum of visible rows, exactly as on the Home card (#704)"* | #629, #704 |
| 4 | `AiToolLogic` (:80, :121 `isRefund()`) | MCP `spending_summary` | Propia: refunds salen del spending en vez de contar como income; categoría del refund = `budgetCategory` al que devuelve | doc 06 §3.2 |
| 5 | `DailySummaryWorker` (:187) | Notificación vespertina | One-liner: `Money(tx.amount.negate(), ...)` — resta del gasto del día | — |
| 6 | `RecentTransactionsWidgetUpdateWorker` (:150-154) | Widget de transacciones recientes | **Filtra** refunds de la lista de recientes: *"A 'Refund' (INCOME + DEDUCT_SPENT) reverses a previous expense, so it..."* (no es netting, es exclusión de display) | — |

### B.3 El caso "orphaned refund" — semántica que solo existe en 2 sitios

Un `DEDUCT_SPENT` **sin `budgetCategory`** (huérfano — p. ej. la categoría se renombró/borró) se interpreta distinto:

- `BudgetWidgetUpdateWorker:126-129`: **NO se excluye del income** — *"An 'orphaned' DEDUCT_SPENT with no category isn't subtracted from spend, so dropping it from income too would understate netSavings"*.
- `BudgetGroupRepository:512`: el filtro de income del aggregator exige `DEDUCT_SPENT && budgetCategory != null` para el mismo trato.

**Consistente entre sí** — pero es un contrato implícito entre dos archivos: si alguien "simplifica" uno de los dos checks, el orphaned se doble-cuenta o desaparece de netSavings.

### B.4 Evaluación de riesgo (divergencia futura)

| Riesgo | Severidad | Mitigación existente |
|---|---|---|
| Cambiar la semántica en el aggregator (sitio 1) y no en Analytics (sitio 3) | **Alta** — son las dos superficies que el usuario compara visualmente (widget vs Analytics) | El comentario #629/#704 ancla la intención; los tests del aggregator |
| Cambiar el tratamiento del orphaned (§B.3) en un solo lado | Media — netSavings erróneo solo con refunds huérfanos | Contrato documentado en ambos comentarios |
| `DailySummaryWorker` (sitio 5) es un one-liner sin floor a cero | Baja — scope de un día, monto visible | Ninguna — candidato natural a un helper compartido |
| MCP (sitio 4) divergiendo de Analytics | Baja (consumidor externo, no comparación visual) | Las `instructions` del relay fijan la expectativa del cliente IA |

**Recomendación de refactor (si el mantenedor lo pide)**: extraer un `RefundInterpreter` (o extender `TransactionFilters.kt`, que ya se declara *"single source of truth"*) con las 3 operaciones — `isRefund`, `netsFromCategory(cat)`, `orphanedHandling` — y que los 6 sitios lo consuman. Hoy la invariante sobrevive por comentarios con issues (#629, #704, #670), no por código compartido; el patrón A funcionó hasta ahora, pero refunds es el subsistema donde el costo de una divergencia es visible para el usuario en dos pantallas a la vez.

---

*Cierre de la serie: docs 00–19 + esta revisión (20) componen el mapa completo del repo en `docs\dg-develop\`.*
