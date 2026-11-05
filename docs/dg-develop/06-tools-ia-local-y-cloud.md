# Deep-dive 06 — Creación/modificación de tools a las que la IA tiene acceso (local y cloud)

> Alcance: las dos superficies de tools de IA del proyecto — las **10 tools del chat on-device** (LiteRT-LM) y las 4 tools **MCP cloud** (AI Access relay) — con sus contratos, diferencias estructurales, procedimientos de extensión y las invariantes que toda tool nueva debe respetar. Fecha de análisis: 2026-11-03. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

PennyWise tiene **dos conjuntos de tools completamente independientes** que comparten filosofía pero no código:

| | **Local (chat on-device)** | **Cloud (AI Access MCP)** |
|---|---|---|
| Motor | Qwen 2.5 1.5B / Gemma 4 E2B / Gemma 3n E2B / Whisper / Custom HF vía LiteRT-LM, en el teléfono | Claude/ChatGPT vía MCP Streamable HTTP + relay |
| Definición | `app\...\data\service\PennyWiseTools.kt` (anotaciones `@Tool` de `com.google.ai.edge.litertlm`) | `relay\src\tools.ts` (JSON schema export `TOOLS`) |
| Quién ejecuta | **La app** intercepta el `ToolCall` (`automaticToolCalling = false`) | **El teléfono** (`aiaccess\AiTools.kt` + `AiToolLogic.kt`) — el Worker solo anuncia |
| Tools | **10**: 3 de mutación (con confirmación) + 7 de lectura | 4, todas **read-only** |
| Consumidor | ChatScreen (usuario final) | Apps IA externas con OAuth (sesión Pro) |
| Gate | Ninguno (gratis, siempre) | **Pro por tool call** (`EntitlementGate`) |

**Invariantes que aplican a AMBOS lados** (violables solo con buena razón y actualizando tests):
1. **Dinero siempre `{amount, currency}`** — nunca sumar monedas (en cloud está hasta en las `instructions` del relay: *"never add or compare amounts in different currencies as one number"*).
2. **Cuentas enmascaradas** a banco + last-4 (cloud); nada de números completos.
3. **Cero SMS/número completo/notas/recibos** hacia afuera (cloud) / hacia el contexto del modelo salvo lo que el usuario escribe (local).
4. **Nada se muta sin confirmación humana** (local: tarjeta del chat #170; cloud: ni siquiera existen tools de mutación).

---

## 2. Lado LOCAL — el contrato de tools de LiteRT-LM

### 2.1 Definición (`PennyWiseTools.kt`)

```kotlin
class PennyWiseTools : ToolSet {
    @Tool(description = "Record a transaction the user is telling you about…")
    fun addTransaction(
        @ToolParam(description = "Amount of money as a number, without currency symbol") amount: Double,
        @ToolParam(description = "The shop, service or person…") merchant: String,
        @ToolParam(description = "One of the known category names") category: String,
        @ToolParam(description = "EXPENSE for money spent…, INCOME only for money received…") type: String,
        @ToolParam(description = "The bank, card or cash the user mentioned…") account: String
    ): String = ""
    // … deleteTransaction, updateTransaction, monthSummary, spendingByCategory,
    //   budgetStatus, categoryBreakdown, recentTransactions, accountBalance, spendingForecast
}
```

**Puntos críticos del contrato**:

- **Los cuerpos nunca ejecutan** — devuelven `""`. La clase existe solo para que LiteRT derive el JSON schema de las anotaciones. La ejecución es 100% de `LlmRepository.handleToolCallInner`.
- **Las `description` son el prompt**: un modelo 1.5B se guía por ellas (p. ej. *"Never a bank or card"* en merchant; *"0 for today, 1 for yesterday, -1 if not mentioned"* en daysAgo). Cambiar una descripción es cambiar el comportamiento del modelo.
- **Snake_case automático**: LiteRT-LM convierte `daysAgo` → `days_ago`; el dispatch acepta ambos vía `Map.arg(camel)`.
- **Constantes** en el `companion object` (`ADD_TRANSACTION = "add_transaction"`, etc.) — el `when` del dispatch las usa como llave.

### 2.2 Tools actuales (10 total)

| Tool | Tipo | Params | Efecto |
|------|------|--------|--------|
| `add_transaction` | Mutación | amount, merchant, category, type (EXPENSE/INCOME), account | `PendingChatAction.Add` → tarjeta confirmación |
| `delete_transaction` | Mutación | merchant, amount, daysAgo | `PendingChatAction.Delete` → tarjeta confirmación |
| `update_transaction` | Mutación | merchant, amount, daysAgo, newCategory, newMerchant | `PendingChatAction.Update` → tarjeta confirmación |
| `month_summary` | Lectura | — | `AiContextRepository.getChatContext().monthSummary` |
| `spending_by_category` | Lectura | category | `AiContextRepository.getCategorySpending(category)` |
| `budget_status` | Lectura | — | `BudgetRepository.getActiveBudgets()` + spend por budget |
| `category_breakdown` | Lectura | limit (max 10) | `AiContextRepository.getChatContext().topCategories.take(limit)` |
| `recent_transactions` | Lectura | limit (max 20), daysBack (max 30) | Filtra `recentTransactions` por fecha |
| `account_balance` | Lectura | — | `AccountBalanceRepository.getAllLatestBalancesOnce()` filtrando ignored |
| `spending_forecast` | Lectura | — | Proyección lineal basada en gasto diario actual |

### 2.3 Dispatch (`LlmRepository.handleToolCallInner`)

- **Mutaciones** (`add/delete/update_transaction`): construyen `TransactionDraft` / `TransactionFinder` → `PendingChatAction` → **tarjeta de confirmación en ChatScreen**; nunca escriben directo.
- **Lecturas** (7 tools): resuelven desde `AiContextRepository` / repositorios → devuelven `String` inline en el chat.
- **Args defensivos**: `Map.arg(camel)` normaliza snake/camel; `TransactionDraft` sanitiza strings, regex de verbos del usuario gana al modelo, ambigüedad → no adivinar.

**Presupuesto de contexto** (`utils\TokenUtils.kt`):
- Qwen: KV cache 4096 tokens, `CHAT_TOKEN_BUDGET = 2560`
- Gemma: KV cache 8192 tokens, `chatBudget(spec) = 6640`
- `RESERVED_TOKENS = 1536` (schemas de tools + chat-template + reply) — cada tool nueva consume presupuesto; el bug #959 (prompt grande bloqueaba TODO chat) es la lección.
- `fitsChatBudget(context, msg, spec)` lanza "Chat memory is full" si excede.

### 2.4 Procedimiento: añadir/modificar una tool local

1. **Método `@Tool`** en `PennyWiseTools.kt` + constante snake_case en el companion. Parámetros pocos y con descripciones precisas — es lo único que el modelo ve.
2. **Presupuesto**: si agregas schema significativo, revisa `RESERVED_TOKENS` / límites de `promptList` en `composeSystemPrompt` (MAX_EXPENSE_CATEGORY_CHARS=1200 etc.) — el #959 puede volver.
3. **Dispatch** en `handleToolCallInner`: mutación → nuevo subtipo de `PendingChatAction` + texto "confirm below" (la UI de `ChatScreen`/`ChatViewModel` debe renderizarlo); lectura → resolver desde Room y devolver `String`.
4. **Args defensivos**: asumir que el modelo alucina — seguir los patrones de `TransactionDraft` (regex de verbos del usuario ganan al modelo; strings con basura se sanitizan; ambigüedad → no adivinar).
5. **`CLAIMS_A_CHANGE`**: si la tool puede ser "narrada" ("I've added…") sin ser llamada, el regex anti-alucinación ya cubre; probar que la nota de "Nothing was changed" siga aplicando.
6. **System prompt**: actualizar `composeSystemPrompt` si la tool cambia el routing de intenciones.
7. **Gate**: `./init.sh app`; verificación en dispositivo para el flujo de confirmación (skill emulator-verify).

**Modificar una tool existente** = cambiar descripción/params → mismo pipeline: el dispatch ya existe, pero re-verificar el regex de flip EXPENSE/INCOME si se toca `addTransaction`.

---

## 3. Lado CLOUD — el contrato de tools MCP

### 3.1 Definición (`relay\src\tools.ts`)

JSON schema puro, exportado como `TOOLS`:

```ts
export const TOOLS = [
  { name: "search_transactions", inputSchema: { …, required: ["from","to"] },
    annotations: readOnly },  // { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  { name: "spending_summary", … },
  { name: "list_accounts", … },
  { name: "list_subscriptions", … },
];
export const TOOL_NAMES = new Set(TOOLS.map(t => t.name));
```

- **El Worker solo anuncia** (`tools/list` → `TOOLS`); **solo el teléfono ejecuta** (comentario verbatim en `tools.ts`: *"The tools the phone answers. The Worker advertises; only the phone runs them"*).
- Las `annotations` son hints MCP que los clientes (Claude/ChatGPT) usan para UX — toda tool nueva del relay **debe** llevarlas.
- `NOT_CONNECTED` y `textResult()` son los resultados estándar de error/texto.

### 3.2 Ejecución (`aiaccess\AiTools.kt` + `AiToolLogic.kt`)

- `AiTools.call(name, args)` — el entry point cuando el mensaje `{type:"call"}` llega por WebSocket: gate Pro → parse de fechas (`LocalDate.parse`, `to >= from`) → `AiToolLogic` → JSON result con `content[{type:"text"}]` + `isError`.
- `AiToolLogic` son **funciones puras sobre entidades** (objeto sin DI): reciben `List<TransactionEntity>` ya filtrado por rango y aplican filtros/grupos/orden. Reglas codificadas (comentario del archivo, ADR 0002): dinero siempre `{amount, currency}`, nunca sumado entre monedas; cuentas enmascaradas a banco+last-4 (`accountOf`); **ningún texto SMS sale del teléfono**.
- Semánticas verificadas en código:
  - `search_transactions`: `limit` acotado a `MAX_ROWS = 200`; sort por monto **agrupa por moneda primero** (comentario: *"Amount order only makes sense within one currency"*); devuelve `categories`/`accounts` de los matches pre-filtro para que la IA pueda encadenar queries con pickers.
  - `spending_summary`: `EXPENSE` incluye CREDIT (compras con tarjeta); income excluye refunds; **refunds (INCOME + DEDUCT_SPENT) se restan del spending** y su categoría es el `budgetCategory` al que devuelven el dinero — igual que Analytics; totales con `forTotals()` (loan-linked/splits cuentan solo la parte del usuario) y `sumByCurrency` (Map por moneda, nunca mezcla).

### 3.3 Procedimiento: añadir una tool cloud

1. **`relay\src\tools.ts`**: entrada en `TOOLS` (name, title, description con la regla de monedas si aplica, `inputSchema` con `required`, `annotations: readOnly`) — se registra sola en `TOOL_NAMES`.
2. **`aiaccess\AiToolLogic.kt`**: función pura sobre entidades que produzca el JsonObject — respetando máscara de cuentas, moneda-tagged, sin SMS/notes/receipts/complete numbers.
3. **`aiaccess\AiTools.kt`**: rama en el `when (name)` con validación de args (fechas, enums) y errores amigables (nunca filtrar stack/internals).
4. **Documentar alcance de datos** en `docs\ai-access.md` (la lista can-read/never-get es contrato público con el usuario).
5. **Deploy relay**: `relay/` se despliega en releases solo si cambió desde el último tag (`scripts\release.sh`); los clientes IA reciben la tool nueva en el siguiente `tools/list` (`listChanged: false` en capabilities — no hay push).
6. **Gate**: tests Kotlin (`AiTools`/`AiToolLogic` tienen tests en `app\src\test\...\aiaccess\`) + deploy manual del relay para probar self-hosted.
7. **Privacidad review**: cualquier tool nueva que exponga un tipo de dato no listado en `docs\ai-access.md` **requiere actualizar ese doc y justificarlo** — es la promesa del producto.

**Importante — asimetría de riesgo**: una tool local mal hecha como mucho crea una tarjeta de confirmación errónea que el usuario rechaza. Una tool cloud mal hecha **filtra datos a un tercero** (el provider de la IA). El estándar de review del lado cloud debe ser estrictamente mayor.

---

## 4. Comparativa de extensión (tabla de decisión)

| Pregunta | Local | Cloud |
|---|---|---|
| ¿Dónde se define el schema? | Anotaciones Kotlin `@Tool` | JSON en `tools.ts` |
| ¿Quién llama? | El modelo on-device (Qwen/Gemma/Whisper/Custom) | Claude/ChatGPT vía relay |
| ¿Puede mutar? | Sí — vía `PendingChatAction` + tap humano | **No — no existen tools de escritura, por diseño** |
| Costo de contexto | Se come del KV cache 4096/8192 (⚠️ #959) | Gratis (el schema vive en el relay, no en un modelo pequeño) |
| Riesgo de alucinación | Alto (modelos edge) → args defensivos obligatorios | Bajo (modelos frontier) → validar shapes |
| Gate monetario | Gratis | Pro por call |
| Visibilidad para el usuario | Pantalla Chat | `docs\ai-access.md` + consent OAuth por app |
| Duplicación | Ni una línea compartida: si quieres una capability en ambos lados, se implementa dos veces (p. ej. "gasto por categoría" existe como tool local `spending_by_category` Y como MCP `spending_summary` con semánticas distintas) |

**Última fila = trampa deliberada**: la summary de cloud replica las reglas de Analytics (refunds, forTotals, credit-as-expense) mientras la local usa `AiContextRepository.getCategorySpending` del mes actual. Si se "unifica", hay que decidir cuál semántica gana — hoy son intencionalmente distintas porque sirven a consumidores distintos.

---

## 5. Checklist maestro para toda tool nueva (ambos lados)

- [ ] Dinero siempre `{amount, currency}`; sin sumas cross-currency (`sumByCurrency` en cloud, `CurrencyFormatter.formatCurrency` en local).
- [ ] Cuentas/bancos enmascarados; sin números completos, SMS, notes, receipts.
- [ ] Cloud: `annotations: readOnly` + sin mutación; Local: mutación solo vía `PendingChatAction` + confirmación.
- [ ] Descripciones pensadas como prompt (el modelo solo ve eso).
- [ ] Args validados/sanitizados en el dispatch (camel/snake, basura en strings, valores default).
- [ ] Errores amigables que no filtren internals (patrón `"PennyWise couldn't read that just now. Try again."`).
- [ ] Cloud: actualizar `docs\ai-access.md` si cambia el perímetro de datos legibles.
- [ ] Local: verificar presupuesto de tokens (no desatar #959).
- [ ] Tests: `app\src\test\...\aiaccess\` (cloud) / flujo de chat (local); gate `./init.sh app`; relay con deploy manual si aplica.