# 24 — IA Local: Tools, Tool Calling y Funcionalidades del Modelo

> Deep dive de las funcionalidades del modelo de chat local (LiteRT-LM), con foco en el listado de tools, el mecanismo de tool calling y las capacidades del runtime. Base del plan de adaptación a gemma-4-E2B (doc 25).
>
> **Estado**: wiring multi-modelo ejecutado 2026-10-27 (litertlm 0.17.1, `ChatModels` catálogo, RAM gate). El chat productivo sigue en Qwen 2.5 1.5B como default; gemma-4-E2B y gemma-3n-E2B seleccionables. **F2 Visión, F3 Audio, F4 Tools, F1 Thinking, F8 System Prompt Additions y Custom HF Model — TODOS IMPLEMENTADOS (2026-10-31 a 2026-11-02).**

---

## 1. Arquitectura de la IA local

```
ChatScreen (UI)
   ↓
ChatViewModel
   ↓
LlmRepository.sendMessageStream()          ← LlmRepository.kt:94
   ├─ buildSystemPrompt()                  ← :342  (fecha, month summary, categorías, cuentas)
   ├─ composeSystemPrompt()                ← :391  (companion, testeable)
   ├─ TokenUtils.fitsChatBudget(...)       ← presupuesto del contexto
   ├─ ensureConversation()                 ← :170  (refreshActiveModel + reset si cambió path)
   │     └─ LlmService.initialize(modelFile.absolutePath)
   │     └─ LlmService.createConversation(systemPrompt, history, withTools=true)
   ├─ llmService.sendMessageEvents(msg)    ← Flow<LlmEvent>
   │     └─ LlmServiceImpl.sendMessageAsync(...)  ← Flow<Message> de LiteRT-LM
   └─ handleToolCall(call, userMessage)    ← :256  (por cada LlmEvent.ToolCall)
         └─ PendingChatAction → confirmación del usuario → Room
```

**Runtime**: `com.google.ai.edge.litertlm:litertlm-android` 0.17.1 (`gradle/libs.versions.toml:47`), backend CPU, KV cache por modelo (Qwen 4096, Gemma 8192), coroutines pinneadas 1.11.0 (litertlm >= 0.14 requiere statics jvm-default, ver comentario toml).

**Catálogo de modelos** (`core/Constants.kt:135-170`): `ChatModelSpec(id, fileName, url, sha256, sizeBytes, contextTokens, minRamBytes)`. QWEN_1_5B (default, sin gate), GEMMA_4_E2B (minRam 6 GB), GEMMA_3N_E2B (minRam 4 GB, multimodal), WHISPER_BASE (ASR). Selección persistida en DataStore (`selected_chat_model`), resuelta por `ModelRepository.refreshActiveModel()` con fallback a DEFAULT si el device no pasa el RAM gate.

---

## 2. System prompt (thin por diseño)

Construido en `LlmRepository.buildSystemPrompt()` (línea 342) y `composeSystemPrompt()` (línea 391):

- **Identidad**: "You are PennyWise AI, a friendly assistant inside an expense tracker. Today is $currentDate."
- **Mes en curso**: gasto/ingreso/transactionCount en la currency base del usuario.
- **Reglas de tools**: cuándo usar addTransaction / spendingByCategory / monthSummary / updateTransaction / deleteTransaction / budgetStatus / categoryBreakdown / recentTransactions / accountBalance / spendingForecast (ver §3).
- **Anti-parroting** (crítico): "never say you added, changed or deleted anything yourself — only the tools do that, and the user confirms each one". Un modelo pequeño (1.5B) tiende a narrar la acción en vez de llamar el tool; el prompt lo prohíbe y el código lo refuerza (ver §4.4).
- **Listas acotadas** (`promptList()`, línea ~405): categorías expense ≤1200 chars, income ≤400, accounts ≤500 — el prompt siempre cabe en el budget (#959).
- **Formato**: "Plain text only: no markdown, asterisks or backticks."

El prompt **no embebe transacciones recientes** — decisión deliberada (comentario línea ~337): el modelo pequeño las parrot-eaba en los argumentos de tools.

---

## 3. Tools — listado completo (10 tools)

Definidas en `PennyWiseTools.kt` (ToolSet de LiteRT-LM, solo schema — nunca se ejecutan):

| Tool | Tipo | Params | Efecto en el app |
|------|------|--------|------------------|
| `add_transaction` | Mutación | amount (Double), merchant (String), category, type (EXPENSE/INCOME), account | `PendingChatAction.Add(draft)` → confirmación → Room + balance |
| `delete_transaction` | Mutación | merchant, amount, daysAgo | `PendingChatAction.Delete(tx)` → confirmación |
| `update_transaction` | Mutación | merchant, amount, daysAgo, newCategory, newMerchant | `PendingChatAction.Update(...)` → confirmación |
| `month_summary` | Read-only | — | Respuesta textual desde `AiContextRepository.getChatContext().monthSummary` |
| `spending_by_category` | Read-only | category | Respuesta textual desde `AiContextRepository.getCategorySpending()` |
| `budget_status` | Read-only | — | `BudgetRepository.getActiveBudgets()` + spend por budget → lista formateada |
| `category_breakdown` | Read-only | limit (max 10, default 5) | `AiContextRepository.getChatContext().topCategories.take(limit)` |
| `recent_transactions` | Read-only | limit (max 20, default 5), daysBack (max 30, default 7) | Filtra `recentTransactions` por fecha |
| `account_balance` | Read-only | — | `AccountBalanceRepository.getAllLatestBalancesOnce()` filtrando ignored |
| `spending_forecast` | Read-only | — | Proyección lineal basada en gasto diario actual |

**Notas de diseño**:
- `automaticToolCalling = false` SIEMPRE (`LlmServiceImpl.createConversation`, línea ~86) — "tool calls are surfaced, never auto-executed (#170)". Ninguna mutación ocurre sin tap del usuario.
- Las descriptions de los params son instrucciones de llenado (ej: "Never a bank or card", "-1 if not mentioned") — el modelo las usa para decidir valores.
- `LlmRepository.handleToolCall()` (línea 256) mapea el snake_case de LiteRT-LM (`daysAgo → days_ago`, `arg()` helper línea ~266).

---

## 4. Mecanismo de tool calling (end-to-end)

### 4.1 Registro
`LlmServiceImpl.createConversation(withTools=true)` → `ConversationConfig(tools = listOf(tool(PennyWiseTools())))`. LiteRT-LM inyecta los schemas JSON al system turn (cuenta en los ~1536 RESERVED_TOKENS de `TokenUtils`).

### 4.2 Emisión del model → dispatch del app
```kotlin
// LlmServiceImpl.sendMessageEvents (línea ~118)
activeConversation.sendMessageAsync(message)
    .transform { chunk ->
        chunk.contents → LlmEvent.Text(delta)   // streaming
        chunk.toolCalls → LlmEvent.ToolCall(name, arguments)  // batch al final
    }
```
`LlmRepository.sendMessageStream()` recolecta ambos: el texto se emite en streaming; los tool calls se acumulan y procesan después (líneas ~117-133).

### 4.3 Resolución (LlmRepository.handleToolCallInner, línea ~270)
- `add_transaction` → `TransactionDraft.fromToolArgs(...)` con fallback de categoría (`BuiltinCategoryNames.current(SharedCategoryMapping.getCategory(merchant))`) y match tolerante de cuentas (`arg()` snake/camel).
- `delete/update` → `TransactionFinder.findWithFallback()` sobre los últimos 30 días (relaja amount/daysAgo si no hay match exacto, reporta "closest one").
- `month_summary` / `spending_by_category` / `budget_status` / `category_breakdown` / `recent_transactions` / `account_balance` / `spending_forecast` → datos frescos de `AiContextRepository` (nunca del prompt).
- Tool desconocido → `null` (silencioso).

### 4.4 Salvaguarda anti-narración
Regex `CLAIMS_A_CHANGE` (línea ~415): si la respuesta del modelo no generó tool calls pero dice "I've added/updated/deleted..." sin haberlo hecho, el app anexa: "(Nothing was changed — I can only add, update or delete through a confirmation card...)". Esto cierra el gap del modelo 1.5B que narra en vez de actuar.

### 4.5 Confirmación del usuario
`PendingChatAction` (StateFlow en LlmRepository, línea ~45) → `ChatScreen` renderiza card de confirmación → el tap ejecuta la mutación real (Room + balance via `AccountBalanceRepository.applyTransactionToBalance`).

---

## 5. Presupuesto de contexto y stats

- `TokenUtils.MODEL_CONTEXT_TOKENS = 4096` (Qwen), `RESERVED_TOKENS = 1536` (schemas + template + reply) → `CHAT_TOKEN_BUDGET = 2560`.
- Con wiring multi-modelo: `TokenUtils.chatBudget(spec)` — Gemma 8192-1536 = **6640**.
- `fitsChatBudget(context, msg, spec)` (línea 111 de LlmRepository) — lanza "Chat memory is full" si el historial + mensaje excede el budget.
- **ChatStats** (`ChatViewModel:105-135`): estimatedTokens/maxTokens para la barra "Uso del contexto" — **FIXED**: ahora usa `chatBudget(modelRepository.activeSpec())` dinámico. Con Gemma muestra "335 / 6.6k tokens".

---

## 6. Descarga, integridad y lifecycle del modelo

- **Descarga**: `ModelDownloader` (ResumableFetch con Range requests, redirects HF 302, headroom 2x, no roaming) → `.part` → rename → verificación.
- **Integridad**: SHA-256 streaming contra el hash pineado del spec (`ModelRepository.verifyModelIntegrity()`) — boundary de seguridad en load-time, nunca short-circuit (ni el marker `.verified` lo sustituye en el path de carga).
- **Estados**: `ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, LOADING, ERROR }` — `_modelState` StateFlow compartido Chat/Settings.
- **Switch de modelo**: `ensureConversation()` compara `llmService.initializedModelPath()` contra el archivo activo → `reset()` si difiere (evita 2 engines en RAM: 1.5 GB + 2.4 GB).

---

## 7. Multimodal (probes verificados 2026-10-27, IMPLEMENTADOS 2026-10-31)

El chat productivo ahora soporta visión y audio con Gemma 3n E2B:

| Modo | Entrada | Implementación |
|------|---------|----------------|
| `mode=image` (Visión) | `Content.ImageFile(path)` + `visionBackend = Backend.CPU()` | `sendMessageWithImage()` → botón attach en ChatScreen → `ActivityResultContracts.TakePicture` → `filesDir/chat_media/` |
| `mode=audio` (Audio) | `Content.AudioFile(path)` + `audioBackend` | `sendMessageWithAudio()` → botón micrófono ��� `MediaRecorder` (AAC/3GP) → archivo temporal |

**Latencia verificada en device (SM-S908E, CPU)**:
- Imagen 514 KB PNG: ~33.8s → loading indicator "Analizando imagen..." + compresión opcional downscale ~1024px
- Audio WAV TTS 184 KB: ~3.9s → viable para UX interactiva

---

## 8. Thinking Mode (Gemma 4 E2B, Developer Mode) — IMPLEMENTADO 2026-10-31

- `ThinkingConfig` en `ConversationConfig` (litertlm 0.17.1+)
- Activación: `developer_mode_enabled` + `thinking_enabled` en prefs
- Budget: `thinking_budget_tokens` (default 1024, máx 50% del context window)
- UI: Colapso "Pensando..." con tokens de razonamiento (solo developer mode)
- Tokens viajan en `LlmEvent.Thinking(delta)` separado del output final

---

## 9. Caso de estudio: "el chat muestra Qwen pero corre Gemma" (2026-10-28) — **FIXED**

**Reporte**: con Gemma seleccionado en Settings, el chat mostraba "Qwen 2.5 1.5B • 2 mensajes" y "335 / 2,6k tokens".

**Fixes aplicados**:
1. **Header dinámico** (`strings_rules.xml:251` + 13 locales): `chat_stats_model_messages` → "%2$s • %1$d mensajes"; caller pasa `modelRepository.activeSpec().displayName`.
2. **Budget dinámico en ChatStats**: `ChatViewModel:122` usa `TokenUtils.chatBudget(modelRepository.activeSpec())`.
3. **Respuesta texto-post-tool-call**: verificación del flujo con Gemma — el modelo emite tool call + texto de respuesta; el app ahora guarda correctamente la respuesta del tool.

---

## 10. Referencias rápidas

| Pieza | Archivo:línea |
|-------|---------------|
| Catálogo de modelos | `core/Constants.kt:135-170` (ChatModelSpec, ChatModels) |
| Tools (schema) | `data/service/PennyWiseTools.kt` (todo el archivo) |
| System prompt | `data/repository/LlmRepository.kt:342` (build) `:391` (template) |
| Tool dispatch | `LlmRepository.kt:256` (handleToolCall) `:270` (inner) |
| Anti-narración | `LlmRepository.kt:415` (CLAIMS_A_CHANGE) |
| Engine lifecycle | `data/service/LlmServiceImpl.kt` (Mutex lifecycle, initializedModelPath) |
| Selección modelo | `data/preferences/UserPreferencesRepository.kt:81` (key) |
| RAM gate | `data/repository/ModelRepository.kt:56-68` (deviceSupports, refreshActiveModel) |
| Descarga | `data/manager/ModelDownloader.kt` + `ResumableFetch.kt` (redirects) |
| Presupuesto tokens | `utils/TokenUtils.kt` (chatBudget(spec) dinámico) |
| Probes multimodales | `app/src/debug/.../LlmProbeReceiver.kt` (mode=image/audio) |
| Test del prompt | `app/src/test/.../ChatPromptBudgetTest.kt` (budget #959 + F8 tests) |

---

*Doc 24 de la serie dg-develop — 2026-11-03. Evidencia verificada contra código vigente (litertlm 0.17.1 + wiring multi-modelo + F1-F4 + F8 implementados). Continúa en doc 25 (plan de adaptación gemma-4-E2B — mayormente ejecutado).*