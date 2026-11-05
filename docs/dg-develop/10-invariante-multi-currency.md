# Deep-dive 10 — El invariante multi-currency end-to-end: Money, forTotals y las conversiones

> Alcance: la regla transversal más importante del repo — "el dinero siempre lleva moneda y nunca se suman monedas distintas" — desde su tipado (`Money`), los totales (`sumByCurrency`), qué cuenta en un total (`countedAmount`/`forTotals`), el formateo (`CurrencyFormatter`), la conversión (`CurrencyConversionService`) y cómo la invariante se propaga a cada consumidor (webhooks, MCP, chat IA, parser, balances). Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

La invariante es **hard constraint 1 y 2 de `CLAUDE.md`** y está custodiada por 4 mecanismos concretos: (1) el tipo `Money` cuyo `plus` **lanza excepción** si las monedas difieren; (2) `sumByCurrency` que bucker por moneda antes de sumar; (3) los overloads currency-less de `formatCurrency` marcados `@Deprecated(level = ERROR)` — **no compilan**; y (4) `CurrencyConversionService.convertAmountOrNull` que devuelve **null** cuando no hay tasa, con la regla documentada: *"summing a face-value foreign amount into another currency's total is never right (#670)"*. Todo consumidor externo (webhooks, relay MCP, dashboard) hereda la invariante por contrato — está hasta en las `instructions` textuales que el relay le da a Claude/ChatGPT.

---

## 2. El tipo: `utils\Money.kt`

```kotlin
data class Money(val amount: BigDecimal, val currency: String) {
    operator fun plus(other: Money): Money {
        require(currency.equals(other.currency, ignoreCase = true)) {
            "Cannot add Money in different currencies ($currency + ${other.currency}); total per-currency instead"
        }
        return Money(amount + other.amount, currency)
    }
}
```

KDoc verbatim: *"it refuses to add two different currencies. '₹500 + $10' is not '510' of anything, so a blind cross-currency total throws loudly (in dev/tests) instead of silently producing a wrong number with a wrong symbol — the bug that kept recurring in group / subscription totals"*. La excepción es a propósito: que reviente en dev/tests y no produzca un número silenciosamente equivocado en producción.

## 3. Los totales: `utils\MoneyTotals.kt`

```kotlin
inline fun <T> Iterable<T>.sumByCurrency(
    currencySelector: (T) -> String, amountSelector: (T) -> BigDecimal
): Map<String, Money> = groupBy(currencySelector)
    .mapValues { (_, items) -> items.fold(Money.zero(currency)) { acc, item -> acc + Money(amount, currency) } }
```

Group-by primero, fold después — **solo se suma like-for-like**, devolviendo `Map<currency, Money>`. La alternativa (un BigDecimal total + una moneda única) es documentada como el bug: *"silently produces a wrong number the moment currencies mix"*.

## 4. El render: `utils\CurrencyFormatter.kt`

- **`formatCurrency(amount, currencyCode)`** — la única vía no deprecada. El overload sin moneda está `@Deprecated(DeprecationLevel.ERROR)` con mensaje que explica el bug que previene: *"the '₹600 for a $600 group' bug"* (default INR mislabeling).
- **`formatByCurrency(totalsByCurrency, signPrefix, fallbackCurrency)`**: set uniforme → una cifra; mixto → `"₹1,250 · $600"` (subtotales por moneda, ordenados por magnitud). Set que cancela a cero → **cero en la moneda del input** (un cargo $300 + reembolso $300 muestra $0, no ₹0). `signPrefix` aplica a la cifra completa con magnitud absoluta — un subtotal neto negativo por reembolsos nunca renderiza `"--€200"`.
- **Grouping por moneda + preferencia**: `NumberFormatStyle` (INDIAN/INTERNATIONAL/AUTO) empujado a un campo `@Volatile` desde `PennyWiseApplication` al startup (un stateless object no puede leer DataStore). AUTO = agrupación nativa: **lakh/crore solo para INR/NPR/PKR**, internacional para el resto; abreviaturas L/Cr vs K/M en ejes de gráficas igual.
- **Detalles finos codificados**:
  - `THREE_DECIMAL_CURRENCIES` (JOD/KWD/BHD/OMR) — minor unit ISO 3 de 3 decimales, para no truncar.
  - `EXPLICIT_SYMBOL_CURRENCIES` (PKR, LKR) — símbolos que colisionan ("Rs") y harían indistinguible una lista mixta de rupias: siempre render explícito del mapa.
  - Pesos suramericanos prefijados (`AR$`, `CL$`, `$U`) — *"a peso figure can never be misread as USD in a mixed-currency list"* (#654).
  - **RTL**: `ltr()` envuelve el monto en isolates LRI…PDI bajo locales ar/fa/he para que "-JD5" lea left-to-right dentro de texto RTL sin voltear el párrafo; `signed()` mantiene el signo a la izquierda. No-op en inglés — tests de string exacto intactos.
  - `resolveAccountCurrency`: cuentas MANUALES confían en la moneda guardada; cuentas SMS caen a la del parser del banco (`getBankBaseCurrency` via `BankParserFactory.getParserByName`) — porque su valor guardado puede ser el default INR aunque el banco no sea indio.

## 5. Qué cuenta en un total: `utils\TransactionFilters.kt`

**Fuente única de verdad** — el KDoc lo declara: *"Single source of truth for 'how much of this transaction counts toward a total?' (Home, Analytics, Budgets, widgets, AI summaries, webhooks)"*.

```kotlin
fun TransactionEntity.countedAmount(): BigDecimal {
    if (excludedFromAnalytics) return BigDecimal.ZERO          // opt-out deliberado (#451)
    val loanPart = if (loanId == null) ZERO else loanContribution ?: amount
    val sharesPart = lentSharesTotal ?: ZERO                   // lo que otros te deben (#346)
    return (amount - loanPart - sharesPart).coerceAtLeast(ZERO)
}
```

| Caso | Cuenta | Ejemplo (del KDoc) |
|---|---|---|
| Excluida de analytics | 0 | — |
| Sin loan, sin splits | todo | — |
| Loan-linked completa | 0 | prestar ₹1,000 completos |
| Loan-linked parcial | solo tu parte | cena ₹1,000 con ₹400 prestados → cuenta ₹600; ₹500 de vuelta en un loan de ₹130 → ₹370 (#831 — antes el row entero caía y ese resto se perdía) |
| Split bill | solo tu parte | los shares son deuda bajo Loans, no tu gasto |

**`forTotals()`** (entity y `TransactionWithSplits`): devuelve la entity con el monto **cortado a `countedAmount`** o null si nada cuenta. La copia **dropea loanId/loanContribution/lentSharesTotal** — lo contado no es ni loan ni split, y además lo hace **idempotente** (re-pasar un row ya procesado no resta dos veces). Con splits: cada split se escala proporcionalmente con **running totals redondeados** — ningún split negativo y suman exacto al contado. **Regla de uso**: solo para computar totales, *"never display the result, its amount isn't what the user paid"*.

## 6. La conversión: `data\currency\CurrencyConversionService.kt`

### 6.1 El contrato de null

| Método | Sin tasa conocida | Uso correcto |
|---|---|---|
| `convertAmount` | **Devuelve el monto SIN convertir** | Solo display single-value junto a un label explícito de moneda |
| `convertAmountOrNull` | **Devuelve null** | Agregaciones: **skip en vez de sumar face-value** (#670, hard constraint 2) |

### 6.2 Cascada de tasas (`getExchangeRate`)

1. Cache en memoria (válido 1h).
2. DB fresca (`expires_at > now`).
3. **Expirada hasta 24h** si las tasas generales no están stale — y dispara refresh de fondo para la próxima.
4. Fetch API (open.er-api.com, base USD, cross-rates `usdToTo / fromToUsd` con `MathContext(10)`).
5. **Last resort**: la tasa más nueva JAMÁS guardada, sin importar edad — *"a stale conversion beats dropping the amount or counting it at face value"*. Null solo si el par jamás tuvo tasa.

### 6.3 Robustez operativa

- **`fetchMutex`**: colapsa el thundering herd del cold-start (muchos collectors convirtiendo a la vez) a UNA llamada API — el primero fetchea, el resto re-checkea bajo el lock y encuentra la tasa.
- **Cooldown de 5 min** tras un fallo — offline o con internet bloqueado (#881), el refresh de widgets post-SMS no martillea la red por cada row extranjera.
- **`unsupportedCurrencies`** (in-memory): códigos que el proveedor no retornó — excluidos del freshness gate para no re-trigger refresh eterno; se limpian al volver a aparecer.
- **Freshness gate pair-aware** (`hasAllRequiredPairsFresh`): revisa **todos los pares cruzados** del set del usuario, no solo "¿USD global está fresco?" — un banco/moneda nueva (MZN) dispara un bulk refresh en vez de N lazy fetches. Códigos upper-cased antes de comparar.
- **Custom rates** (`isCustomRate`): el usuario fija una tasa con expiry +100 años; **la API jamás sobreescribe** una custom; `clearCustomRate`/`clearAllCustomRates` devuelven el control a la API.

## 7. La invariante por consumidor (dónde se materializa)

| Consumidor | Cómo la respeta | Evidencia |
|---|---|---|
| **Parser (parser-core)** | `ParsedTransaction.currency` + `accountCurrency` (gasto USD en tarjeta INR) — el parser de UAE extrae la moneda del cuerpo | doc 05 §2.3 |
| **Balances** | `BalanceCalculator.balanceAfter` con `transactionCurrency` vs `accountCurrency`: *"a foreign-currency spend isn't added to it"* — la cuenta **conserva su moneda** | doc 07 §6 |
| **Reglas** | `DuplicateKey` de dedup incluye `currency`; `isSameCharge` (cross-canal): *"Equal numbers in different currencies are different charges (a multi-currency provider can post £0.24 and €0.24 together)"* | `TransactionDeduplication.kt` |
| **Chat IA local** | `describe()` usa la moneda **propia** del row (*"never relabelled with the base one"*); `monthSummary`/`spendingByCategory` se formatean con `formatCurrency(amount, baseCurrency)` del contexto | doc 01 §5.3 |
| **AI Access MCP** | `AiToolLogic` con `sumByCurrency` en totales; sort por monto **agrupa por moneda primero**; la regla vive en las `instructions` del relay: *"never add or compare amounts in different currencies as one number"* | doc 06 §3.2 |
| **Webhooks** | Montos como **strings decimales con moneda** en el contrato; un perfil exporta **una sola moneda**; cambio de moneda de una transacción → deletion al perfil viejo + upsert al nuevo (nunca conversión implícita) | doc 02 §6.2 |
| **Backup** | `originalAmount`/`originalCurrency` en goal contributions (pre-conversión preservada); goal uid-matched con moneda distinta → contributions skippeadas, *"amounts are never converted implicitly"* | doc 09 §6.2 |
| **Unified currency mode** | Convierte a moneda display; **sin tasa → esa moneda se omite del total** (#545/#670) — nunca se suma a valor nominal | doc 00 §3.7 |

## 8. Procedimiento: tocar código que mueve dinero

1. **Nunca** `fold`/`sum` BigDecimal crudos sobre rows que pueden diferir en moneda — `sumByCurrency` + `formatByCurrency`, o filtrar/convertir a UNA moneda primero (con `convertAmountOrNull` + skip en null).
2. **Nunca** llamar `formatCurrency(amount)` sin moneda — no compila; y si alguien "arregla" el build pasando `"INR"`, revisar si el dato realmente es INR.
3. Todo total nuevo sobre transacciones pasa por `forTotals()` primero (loan/split/excluded), y todo display muestra la **moneda del row**, no la base.
4. Nueva moneda soportada: verificar símbolo en `CURRENCY_SYMBOLS`, locale en `CURRENCY_LOCALES`, casos de 3 decimales/colisión de símbolo/agrupación india.
5. Toda conversión nueva: `convertAmountOrNull` para agregaciones, `convertAmount` solo para display etiquetado.
6. Guardes: `CurrencyFormatterTest` (custodia las reglas 1 y 2 según `CLAUDE.md`) + `./init.sh app`.
