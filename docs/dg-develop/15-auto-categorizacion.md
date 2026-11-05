# Deep-dive 15 — Auto-categorización: keyword mapping, aprendizaje de merchant y aliases

> Alcance: el pipeline completo que asigna categoría a una transacción sin que el usuario la toque — el **fallback estático** por keywords (`SharedCategoryMapping`, 941 líneas de sets), el **aprendizaje** por feedback (`LearnCategoryUseCase` con Undo), la persistencia (`MerchantMappingEntity`/`MerchantAliasEntity`), los quick picks de notificación que lo alimentan, y el consumo desde parser→chat→MCP. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

La categoría de una transacción se resuelve por **cascada de 4 fuentes**, de más específica a más general: (1) lo que el **usuario ya enseñó** para ese merchant (`MerchantMappingEntity` — persistido, con timestamp), (2) el **fallback estático** por keywords (`SharedCategoryMapping` — sin estado, compartido KMP), (3) "Others" como piso, (4) en el chat IA, la primera categoría visible si todo falla. El **aprendizaje** es una lección, no un one-off: al escoger categoría en una notificación, el merchant se memoriza **y sus filas anteriores en "Others" del mismo tipo se mueven con él** — con Undo que solo revierte mientras sigue siendo la última lección de ese merchant. Los merchants placeholder ("UPI Payment", "ATM Withdrawal") **nunca se aprenden**.

---

## 2. Las 4 fuentes (cascada)

```
addTransaction (manual/IA)  →  reglas (block/SET CATEGORY)  →  merchantMapping[merchant]
        →  SharedCategoryMapping (keywords)  →  "Others"
```

| Orden | Fuente | Estado | Archivo |
|---|---|---|---|
| 1 | Usuario/regla (SET CATEGORY action, doc 08 §2.3) | por fila | `RuleEngine` |
| 2 | **Mapping aprendido** | persistido | `data\database\entity\` `MerchantMappingEntity` (PK merchantName, category, **updatedAt**) |
| 3 | **Keyword fallback** | sin estado | `shared\...\domain\mapping\SharedCategoryMapping.kt` |
| 4 | "Others" / primera visible | — | `BuiltinCategoryNames.current("Others")` |

El pipeline SMS aplica la cascada en `saveTransaction` (worker) y `SmsTransactionProcessor` (receiver en vivo) con el `merchantMappingCache` (1 lectura de DB por scan — doc 07 §2); el chat IA la aplica en `TransactionDraft.fallbackCategory(merchant)` via `SharedCategoryMapping` (doc 01 §5.1); iOS via el mismo objeto `shared`.

## 3. El fallback estático — `SharedCategoryMapping` (shared KMP)

- **Objeto singleton sin dependencias** — corre en Android e iOS por igual.
- Helper `matches(merchantRaw, anyOf, noneOf)`: lower-case; keywords de una palabra matchean con `\b` word-boundary (con escape de `.`), multi-palabra con `contains`; `noneOf` tiene veto.
- **Keyword sets curados por categoría** (~15 sets: FOOD, transporte, entretenimiento, compras, factas, fuel, salud, …). El set FOOD ejemplifica el criterio de curaduría: empezó brand-heavy (swiggy, zomato, kfc…) y #678 añadió **palabras genéricas** — el comentario lo documenta: *"The list above is brand-heavy, so everyday Indian eateries fell through to 'Others' (#678) — Play reviewers called out canteens and bakeries specifically"*. Incluye marcas internacionales (talabat, deliveroo, jollibee, chipotle) — la cobertura es multi-país como los parsers.
- **Mantenimiento**: cualquier adición aquí es cambio de comportamiento global de categorización en ambas plataformas — testear contra los suites existentes.

## 4. El aprendizaje — `domain\usecase\LearnCategoryUseCase.kt`

KDoc maestro: *"A category picked from a transaction alert is a lesson, not a one-off: the merchant is remembered so its next SMS is filed on arrival, and its earlier transactions of the same kind still sitting in 'Others' move along. Transactions the user already filed elsewhere are left alone, and so are loan-linked and split rows."*

### 4.1 `invoke(transactionId, category, announce)` → `Learned`

Corre entero **dentro de `database.withTransaction`** y bajo un **`Mutex`** — *"Two quick picks (or a pick and an undo) for one merchant must not interleave their read-then-write of the mapping"*. `announce` corre con el lock aún tomado para que las notas "Saved as" posteen en el mismo orden que las lecciones.

1. Actualiza la categoría de la transacción.
2. **No aprende** si: `category == "Others"`, merchant nulo, o **`isPlaceholderMerchant`** — `PLACEHOLDER_NAMES` matched *por shape* ("unknown merchant", "transfer", "transaction alert"): *"A parser placeholder ('UPI Payment', 'ATM Withdrawal') names a channel, not a place: picking for one files only that row"* (parser-core no tiene constantes compartidas para estos, por eso el match es heurístico).
3. Si aprende: `setMapping(merchant, category, mappedAt)` + **mueve las filas previas** — `getMovableMerchantRowIds(merchant, others, sameKind(type))`: solo rows en "Others", **del mismo tipo** de transacción, y el repositorio excluye loan-linked/split (`movedIds`).
4. Devuelve `Learned(transactionId, category, previousCategory, merchant?, previousMapping?, mappedAt?, movedIds)`.

### 4.2 `undo(learned)` — condicionado a ser la última lección

- Verifica que el mapping actual del merchant siga siendo **exactamente el que esta lección escribió** (`category` + `mappedAt`): *"undoing an older alert can neither erase a newer pick nor bring an undone one back"*.
- Restaura `previousMapping` (o remueve el mapping si no había), **devuelve los `movedIds` a "Others" solo si siguen en esa categoría** (*"a later edit stands"*), y la fila original a `previousCategory`. Todo en transacción.
- Esto empalma con la notificación "learned" del receiver (doc 07 §7): el Undo de la nota (slot 8, 15s) llama a este `undo`; la nota más reciente del merchant gana (postLearned cancela las viejas del mismo merchant).

### 4.3 La entrada de feedback — quick picks de notificación

El `SmsBroadcastReceiver` (doc 07 §7) es el embudo principal: transacción sin categorizar → 2 quick picks (`quickPicks`: categorías más usadas del tipo, topped-up con las ofrecidas, "Others" excluido como respuesta) + picker completo → un tap = `LearnCategoryUseCase` → nota "saved — and learned (+N moved)" con Undo. **El categoryRequestCode `(txId*10+slot)`** existe para que la respuesta llegue a la transacción correcta.

## 5. Persistencia y backup

| Entidad | Campos | Rol |
|---|---|---|
| `MerchantMappingEntity` | merchantName (PK), category, **updatedAt** | La lección aprendida — `updatedAt` truncado a millis es lo que valida el undo |
| `MerchantAliasEntity` | merchantName → alias | Display name custom (#583) — usado en búsqueda y render; **excluido de exports MASKED/ANONYMOUS** (doc 09 §4) |

- **Backup**: mappings y aliases viajan en el snapshot (dedup de MERGE por merchant_name, doc 09 §6.2).
- **Reglas interacción**: `withCategoryRenamed` (doc 08 §2.1) — renombrar una categoría re-escribe las reglas que la nombran; los **mappings aprendidos NO se re-escriben en el rename** (apuntan al nombre viejo), por lo que el rename de categorías es una operación que deja huérfanos de mapping — detectado como limitación documentada del flujo.

## 6. Consumidores de la cascada

| Consumidor | Uso |
|---|---|
| Pipeline SMS (worker + processor) | Categoriza al insertar (cache por scan) |
| Chat IA (`TransactionDraft`) | `fallbackCategory(merchant)` — cascada + primera visible (doc 01 §5.1) |
| Import statements | `StatementTransactionEnricher` — enriquece rows del plan (doc 14 §3.5) |
| Quick picks de notificación | Ranking de uso (`getCategoriesByUsage`) para sugerir (doc 07 §7) |
| iOS | Mismo `SharedCategoryMapping` via framework `Shared` |
| AI Access MCP | No categoriza — expone la categoría existente en `search_transactions`/`spending_summary` |

## 7. Procedimiento: extender

1. **Nueva keyword/categoría en el fallback**: editar el set en `SharedCategoryMapping.kt` — recordar que es **single source of truth** compartido con iOS; añadir con `noneOf` veto cuando la palabra es ambigua (patrón `matches`).
2. **Cambiar qué filas se mueven al aprender**: `getMovableMerchantRowIds` (repository) + `sameKind(type)` — el contrato es *"user-filed rows elsewhere are left alone"*; relajarlo rompe la promesa del Undo parcial.
3. **Nuevo placeholder de parser**: si parser-core empieza a generar un nombre-channel nuevo, añadirlo al match por shape de `PLACEHOLDER_NAMES` o toda esa cadena "aprenderá" canales como merchants.
4. **Alias**: cualquier display name nuevo pasa por `MerchantAliasEntity` — nunca reescribir `merchantName` (es identity de dedup, reglas y mappings).
5. Gate: `./init.sh app` (los tests del use case y del mapping viven en unit tests).
