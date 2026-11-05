# Deep-dive 08 — Motor de reglas: condiciones, acciones, evaluación y persistencia

> Alcance: `RuleEntity` → `TransactionRule`/`RuleCondition`/`RuleAction` (dominio), el `RuleEngine` (evaluación), el pre-filtro por tipo, la semántica AND/OR, la aplicación en batch retroactiva con dry-run, TAGS como acción especial, import/export JSON y la cuota free (3 reglas). Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Las reglas son el sistema de automatización del usuario: **condiciones** sobre 14 campos de la transacción combinadas con AND/OR left-to-right, y **acciones** que setean/appendean/limpian campos, agregan/quitan tags, o **bloquean** la transacción. El motor es **puro** (sin DI de datos): recibe entidades y reglas ya cargadas, devuelve `(entity modificada, applications)`. La observabilidad es de primera clase: cada aplicación queda en `RuleApplicationEntity` con el diff campo por campo (`FieldModification` oldValue/newValue). La pureza tiene precio — **TAGS no vive en la entity**, así que el motor solo registra la modificación y el sitio que persiste la aplica via `TagRepository`. La defensa contra "reglas muertas" está pinneada en tests (`RuleActionExecutabilityTest`).

---

## 2. Modelo de dominio (`domain\model\rule\`)

### 2.1 `TransactionRule`

```kotlin
data class TransactionRule(
    val id: String = UUID.randomUUID().toString(),
    val priority: Int = 100,          // menor número = mayor prioridad
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>,
    val isActive: Boolean = true,
    val isSystemTemplate: Boolean = false,  // no borrables
)
```

- **Persistencia**: `RuleEntity` (`data\database\entity\RuleEntity.kt`) guarda `conditions` y `actions` como **JSON** serializado con kotlinx.serialization (`RuleEngine.serializeConditions/Actions`, con `ignoreUnknownKeys + isLenient` — reglas de versiones viejas con campos nuevos degradan a `emptyList()` en fallo, no crashean).
- `withCategoryRenamed(old, new)`: cuando el usuario renombra una categoría, las reglas que la nombran (condición CATEGORY comparada case-insensitive, acción SET) se re-escriben — el rename de categoría no mata reglas.

### 2.2 Campos de condición (`TransactionField` — 14 valores)

| Campo | Fuente del valor (`RuleEngine.getFieldValue`) | Operadores soportados (`supportedOperators`) |
|---|---|---|
| `AMOUNT` | `amount.toString()` | `<`, `>`, `=` (numéricos) |
| `TYPE` | `transactionType.name` | `=`, `≠` |
| `CATEGORY`, `MERCHANT`, `NARRATION`, `SMS_TEXT`, `BANK_NAME` | campo de la entity / `smsText` | `contains`, `=`, `starts with` |
| `TRANSACTION_HOUR` (00-23) / `TRANSACTION_TIME` (HH:mm) | dateTime | `<`, `>`, `≥`, `≤`, `=` |
| `TRANSACTION_DAY_OF_WEEK` (1=Lun..7=Dom) / `DAY_OF_MONTH` (01-31) | dateTime | `is`, `is any of`, `≠`, `<`, `>` |
| `TRANSACTION_DATE` (yyyy-MM-dd) | `toLocalDate().toString()` (ISO) | `is`, `before`, `after` |
| `ACCOUNT` | composite `"BankName\|\|Last4"` (takeLast(4)) | `=`, `≠` — vacío si bank/account null |
| `TAGS` | **`""` siempre** — action-only | `emptyList()` — *"the pure engine has no tag value to compare"* |

**`supportedOperators` es la única fuente de verdad del pairing campo↔operador**: el editor construye su picker desde ahí, y el **importer valida archivos arbitrarios contra él** — un `MERCHANT > 500` hand-editado no puede producirse por UI pero persistiría como regla que nunca dispara. `validate()` en `RuleCondition` hace el mismo trabajo para los valores (monto parseable como BigDecimal, hora 0-23, día 1-7/1-31, fecha ISO, ACCOUNT con las dos partes no-blank del split `"||"`).

### 2.3 Acciones (`RuleAction` + `supportedActionTypes`)

| Campo | Acciones ejecutables |
|---|---|
| `CATEGORY` | SET, CLEAR |
| `MERCHANT`, `NARRATION` | SET, APPEND, PREPEND, CLEAR |
| `TYPE` | SET (parsea a TransactionType; si el valor no parsea, **fall back al tipo existente** — la regla silenciosamente no hace nada, por eso `validate()` lo rechaza al crear) |
| `BANK_NAME` | SET, CLEAR (el banco identifica la cuenta a la que pertenece la transacción) |
| `TAGS` | ADD_TAG, REMOVE_TAG (vía `TagRepository`, ver §4) |
| `BLOCK` | válido en cualquier campo — *"it never modifies a transaction (the engine skips it in `applyActions` and handles it in `shouldBlockTransaction`)"* |

`supportedActionTypes` **refleja exactamente** el `else` branch de `applyAction`: *"anything outside these sets… is silently dead"*. `RuleActionExecutabilityTest` pinnea ambas para que no diverjan.

---

## 3. Evaluación (`domain\service\RuleEngine.kt`)

### 3.1 Orden y cascada

`evaluateRules(transaction, smsText, rules)`:
1. Filtra `isActive`, ordena por `priority` ascendente.
2. Por cada regla que matchea: `applyActions` sobre la **versión ya modificada** — las reglas se **encadenan** (una regla puede re-categorizar y la siguiente ver la categoría nueva).
3. Solo registra `RuleApplication` si hubo `fieldMods.isNotEmpty()`.
4. Devuelve `(modifiedTransaction, applications)` — el caller decide persistir.

`evaluateRulesForType` + `isRuleApplicableToTransactionType` (en `RuleCondition.kt`): **pre-filtro conservador** por TYPE — solo para operadores `{=, ≠, IN, NOT_IN}` y **solo si no hay OR lógico** (un TYPE fallido puede matchear por un hermano OR). Cualquier duda → pasa a evaluación completa. Es la optimización que usa `OptimizedSmsReaderWorker` con su `ruleCache` (4 lecturas por scan en vez de 1 por transacción).

### 3.2 Semántica AND/OR — left-to-right, sin precedencia

Verbatim: *"Evaluation is left-to-right with no precedence — 'A AND B OR C' reads as '(A AND B) OR C' — which matches the most common user intent of 'either these together, or that'"*. Cada condición (tras la primera) lleva su `logicalOperator` que combina con el resultado **acumulado**.

### 3.3 Comparaciones — el detalle que era un bug

- **AMOUNT es numérico, no texto**: `amountEquality` detecta el caso y usa `BigDecimal.compareTo` — el comentario documenta el bug: `"100" vs "100.0"` nunca string-matcheaban, así que el `=` de monto del editor *"simply never fired"*. `<`/`>` también van por `compareNumeric` (fallback a string en NumberFormatException).
- Texto: `equals/contains/startsWith/endsWith` todos `ignoreCase = true`; `IN/NOT_IN` splitean por coma con trim.
- `IS_EMPTY`/`IS_NOT_EMPTY` sobre el valor crudo (blank).
- **`ACCOUNT ≠` con valor vacío**: `if (fieldValue.isBlank()) false` — una transacción sin cuenta NUNCA matchea `ACCOUNT ≠ X` (sin esto, todas las transacciones sin banco matchearían cualquier "no es esta cuenta").
- `REGEX_MATCHES` sobre cualquier campo (operador expuesto aunque no está en los pickers defaults de texto).

### 3.4 BLOCK (`shouldBlockTransaction`)

Separado de la cascada: filtra reglas con acción BLOCK, ordena por prioridad, devuelve la **primera que matchea**. En `OptimizedSmsReaderWorker.saveTransaction` se llama **antes** del insert → `SaveOutcome.SKIPPED` con `stats.blocked`. En batch retroactivo equivale a no guardar.

---

## 4. TAGS — la excepción a la pureza

Los tags viven en su propia tabla (`TagEntity` + `TransactionTagCrossRef`), no en la fila. El motor **no puede aplicarlos**, así que:

1. `applyActions` detecta `field == TAGS` → registra `FieldModification(TAGS, null, tag, ADD_TAG/REMOVE_TAG)` y **no toca la entity**.
2. El sitio que persiste aplica los cambios: en el worker, `saveRuleApplications` → `ruleApps.tagChanges()` → `tagRepository.applyTagChanges(transactionId, add, remove)` (#748). En batch y en edición manual el mismo patrón.
3. Como condición, `TAGS` siempre devuelve `""` — el importer la **rechaza** y `supportedOperators(TAGS)` es `emptyList()`.

## 5. Persistencia de aplicaciones (`RuleApplicationEntity`)

Cada aplicación guarda: ruleId, ruleName, transactionId, **fieldsModified como JSON** (`FieldModification`: field, oldValue, newValue, actionType), appliedAt. El flujo del worker:
- Insert nuevo → `saveRuleApplications(rowId, apps)`.
- **Reemplazo de duplicate** → `replaceRuleApplications`: borra las del duplicate y re-escribe las nuevas.
- **Borrado de duplicate en cleanup** → `deleteRuleApplicationsForTransaction(dup.id)` (siempre junto al `deleteDuplicate` + `deleteBalancesForTransaction` — trío inseparable en todo el archivo).

Esto alimenta la UI de "esta transacción fue modificada por la regla X" con el diff exacto.

## 6. Batch retroactivo + dry-run + import/export (UI)

Desde `RulesScreen`/`CreateRuleScreen` (verificados en inventario 01):
- **Dry-run**: aplica las reglas a transacciones pasadas en memoria y muestra el preview de cambios sin escribir.
- **Batch apply**: materializa con el mismo `RuleEngine` + persistencia de applications/tags.
- **Export/import JSON**: las reglas salen como el JSON de conditions/actions (`{"conditions":[…],"actions":[…]}` — formato documentado en el README); el importer valida contra `supportedOperators`/`supportedActionTypes`/`validate()` para que un archivo hand-edited no cree reglas muertas.
- **Cuota**: `FreeTierLimits.MAX_RULES = 3` free / Pro ilimitado (doc 04 §6); reglas del sistema (`isSystemTemplate`) no borrables.

## 7. Extender el motor (procedimiento)

1. **Nueva condición de campo**: enum value en `TransactionField` → caso en `getFieldValue` (o el valor será `""` y matcheará solo IS_EMPTY) → operadores en `supportedOperators` → validación de valor en `RuleCondition.validate()` → tests del engine.
2. **Nueva acción**: value en `ActionType` → caso en `applyAction` (o será silenciosamente dead) → reflejar en `supportedActionTypes` → `RuleActionExecutabilityTest` actualizado (pinnea engine↔tabla).
3. **Nueva acción sobre dato no-entity** (como TAGS): seguir el patrón de registro + aplicación en sitio de persistencia; documentar por qué la pureza del motor exige ese paso extra.
4. **Cambiar semántica AND/OR**: cuidado — left-to-right es decisión de UX documentada en el comentario del engine; cambiarla rompe reglas existentes de usuarios.
5. Gate: `./init.sh app`. Las reglas tienen tests unitarios directos (`RuleEngine` es puro — fácil de testear sin Room).
