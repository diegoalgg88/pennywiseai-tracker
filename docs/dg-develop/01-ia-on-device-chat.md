# Deep-dive 01 — IA on-device: chat local (LiteRT-LM + Qwen 2.5 / Gemma 4 / Gemma 3n / Whisper / Custom HF)

> Alcance: motor de IA que corre **dentro del teléfono**, la pantalla Chat, las tools que el modelo puede llamar, el ciclo de vida del modelo y las garantías de privacidad/confirmación. Fecha de análisis: 2026-11-03. Todos los paths citados están bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

El chat IA de PennyWise corre **múltiples modelos** sobre **Google AI Edge LiteRT-LM** (`com.google.ai.edge.litertlm`), 100% en el dispositivo:

| Modelo | ID | Contexto | RAM mínima | Tamaño | Capacidades |
|---|---|---|---|---|---|
| Qwen 2.5 1.5B | `qwen25-1.5b` | 4096 | 0 GB (fallback) | ~1.5 GB | Texto, tools |
| Gemma 4 E2B | `gemma4-e2b` | 8192 | 6 GB | ~2.6 GB | Texto, tools, thinking |
| Gemma 3n E2B | `gemma3n-e2b` | 8192 | 4 GB | ~3.7 GB | Texto, visión, audio, tools |
| Whisper Tiny | `whisper-base` | 0 (ASR) | 2 GB | ~42 MB | Audio transcripción |
| Custom HF | `custom-*` | User-defined | User-defined | User-defined | Texto, tools (si soporta FC) |

El modelo solo puede actuar sobre los datos mediante **10 tools** cuyo cuerpo Kotlin devuelve `""` — el runtime nunca las ejecuta (`automaticToolCalling = false`); la app intercepta cada `ToolCall`, construye un draft, y **nada se escribe en Room sin un tap explícito del usuario** (garantía del issue #170). Los modelos son archivos descargados one-time desde Cloudflare R2 / HuggingFace con **SHA-256 pinned** y re-hash en cada carga.

---

## 2. Arquitectura por capas

```
ChatScreen (Compose)  →  ChatViewModel  →  LlmRepository  →  LlmService (interfaz)
                                                       ↓
                                              LlmServiceImpl (LiteRT-LM Engine/Conversation)
                                                       ↓
                              PennyWiseTools (solo schema) — tool calls interceptados
                                                       ↓
                              PendingChatAction → tarjeta de confirmación → AddTransactionUseCase
```

| Capa | Archivo | Responsabilidad |
|---|---|---|
| UI | `app\src\main\java\com\pennywiseai\tracker\ui\screens\chat\ChatScreen.kt` | Lista de mensajes, input, tarjeta de confirmación de acción pendiente, botones attach/mic (gated por modelo activo) |
| ViewModel | `...\ui\screens\chat\ChatViewModel.kt` | Expone mensajes (`ChatDao`), acción pendiente (`LlmRepository.pendingAction`), confirma/rechaza, chat stats (tokens) |
| Dominio | `...\domain\service\LlmService.kt` + `LlmEvent.kt` | Interfaz + eventos `Text(delta)` / `Thinking(delta)` / `ToolCall(name, args)` |
| Implementación | `...\data\service\LlmServiceImpl.kt` | Engine LiteRT, conversación, streaming, visión, audio |
| Orquestación | `...\data\repository\LlmRepository.kt` | Historial en Room, system prompt, dispatch de tool calls, drafts |
| Tools (schema) | `...\data\service\PennyWiseTools.kt` | Definiciones `@Tool` que el modelo ve |
| Modelo | `...\data\repository\ModelRepository.kt` + `...\core\Constants.kt` | Descarga, SHA-256, estados, RAM gate, catálogo |
| **System Prompt Additions (F8)** | `...\data\preferences\UserPreferencesRepository.kt` + `...\ui\screens\settings\SettingsViewModel.kt` + `...\ui\screens\settings\SettingsScreen.kt` | Clave `SYSTEM_PROMPT_ADDITIONS` en DataStore, flow + setter, UI en Settings (Experimental) |
| **Custom HF Model (F8)** | `...\core\Constants.kt` (`CustomModelConfig`) + `...\ui\screens\settings\SettingsViewModel.kt` + `...\ui\screens\settings\SettingsScreen.kt` | Validación pre-download HEAD + SHA-256 pinning, estados IDLE→VALIDATED→READY, UI Experimental |

DI: `...\di\LlmModule.kt` bindea `LlmServiceImpl` → `LlmService` como `@Singleton`.

---

## 3. Las 10 tools del modelo local (`PennyWiseTools.kt`)

Clase `PennyWiseTools : ToolSet`. **Cuerpos que devuelven `""`** — solo existen para que LiteRT-LM derive el JSON schema que se inyecta en el system turn. La anotación de clase lo dice literalmente: *"conversations are created with automaticToolCalling = false, so the bodies below never execute — the app intercepts the call and asks the user before writing anything"* (issues #170/#767).

| Tool (const) | Firma | Efecto real |
|---|---|---|
| `add_transaction` | `amount: Double, merchant: String, category: String, type: String ("EXPENSE"/"INCOME"), account: String` | → `TransactionDraft.fromToolArgs(...)` → `PendingChatAction.Add` → tarjeta "Here's what I'll add — confirm below" |
| `delete_transaction` | `merchant: String, amount: Double, amount 0 = no mencionado, daysAgo: Int (0=hoy, -1=no mencionado)` | Busca en últimos 30 días → `PendingChatAction.Delete(tx)` → tarjeta "Delete this one? Confirm below" |
| `update_transaction` | `merchant, amount, daysAgo, newCategory, newMerchant` | Busca → `PendingChatAction.Update(tx, newCategory?, newMerchant?)` → tarjeta con diff "→ merchant X, category Y" |
| `month_summary` | — | **Read-only**: resuelto en la app desde `AiContextRepository.getChatContext().monthSummary`; se responde directamente con línea "This month: X spent, Y received, N transactions" |
| `spending_by_category` | `category: String` | **Read-only**: `AiContextRepository.getCategorySpending(category)`; "Nothing spent on X this month" si es null |
| `budget_status` | — | **Read-only**: `BudgetRepository.getActiveBudgets()` + spend por budget → lista formateada |
| `category_breakdown` | `limit: Int (max 10, default 5)` | **Read-only**: `AiContextRepository.getChatContext().topCategories.take(limit)` |
| `recent_transactions` | `limit: Int (max 20, default 5), daysBack: Int (max 30, default 7)` | **Read-only**: filtra `AiContextRepository.getChatContext().recentTransactions` por fecha |
| `account_balance` | — | **Read-only**: `AccountBalanceRepository.getAllLatestBalancesOnce()` filtrando ignored accounts |
| `spending_forecast` | — | **Read-only**: proyección lineal basada en gasto diario actual del mes |

Convención de nombres: LiteRT-LM **snake_casea** los parámetros (`daysAgo` → `days_ago`); `LlmRepository` acepta ambas formas vía helper `Map.arg(camel)` (`LlmRepository.kt`, método `arg`).

### 3.1 Por qué la UI existe separada del dispatch

Las 7 tools de lectura (`month_summary`, `spending_by_category`, `budget_status`, `category_breakdown`, `recent_transactions`, `account_balance`, `spending_forecast`) se responden **inline** con texto al chat. Las 3 de mutación **nunca ejecutan**: setean `_pendingAction` (`MutableStateFlow<PendingChatAction?>`) y el texto de la respuesta le dice al usuario que confirme abajo. `clearPendingAction()` existe para descartar.

---

## 4. Flujo de un mensaje completo (`LlmRepository.sendMessageStream`)

**Orden corregido (W1 2026-11-04):**

1. **Chat nuevo** → se reconstruye SIEMPRE el system prompt (nunca cacheado: un prompt cacheado trae fecha/cifras stale y, si era muy grande, bloqueaba todo chat nuevo — bug #959) y se inserta como `ChatMessage(isSystemPrompt = true)`.
2. **Presupuesto de tokens** (`utils\TokenUtils.kt`): `chatBudget(spec)` dinámico por modelo activo (Qwen 2560, Gemma 6656). `requireFitsChatBudget()` lanza *"Chat memory is full. Please clear the chat to continue."* si excede. Los 1536 reservados cubren los schemas de tools, chat-template tokens y la respuesta.
3. **Chequeo de modelo descargando**: `requireModelNotDownloading()` lanza *"Model is currently downloading"*.
4. **Se guarda el mensaje del usuario** en `ChatDao` (Room, tabla `ChatMessage`: id UUID, message, isUser, timestamp, isSystemPrompt).
5. `ensureConversation(excludeMessageId)`: el mensaje que se está enviando se **excluye** del historial pasado a `createConversation` para evitar turno duplicado (W1).
6. Streaming: `llmService.sendMessageEvents()` emite `LlmEvent.Text` (se emiten al UI en vivo), `LlmEvent.Thinking` (solo Gemma 4 con thinking on), y acumula `LlmEvent.ToolCall` en una lista — **no se interrumpe el stream por una tool**.
7. Al terminar el stream, cada tool call se resuelve con `handleToolCallInner` y su línea de respuesta se emite.
8. **Tool narrada como texto** (`LlmRepository.kt:261`): si NO hubo tool calls y la respuesta completa es un único identificador (regex `NARRATED_TOOL = ^[A-Za-z_][A-Za-z0-9_]*$`, p.ej. el modelo escribe `monthSummary`) y coincide con una de las 10 tools (camelCase o snake_case), se descarta el texto y se sintetiza un `ToolCall(name, emptyMap())`. Útil solo para tools sin parámetros; las de mutación con args vacíos caen en sus mensajes de "couldn't work out…".
9. **Anti-alucinación** (`LlmRepository.kt:285`): si NO hubo tool calls pero la respuesta matchea el regex `CLAIMS_A_CHANGE` ("I've added / was deleted / has been updated…"), se agrega la nota *"(Nothing was changed — I can only add, update or delete through a confirmation card…)"* — un modelo 1.5B a veces narra un cambio en vez de llamar la tool, y el usuario se iría creyendo que pasó.

**Invariantes W1:**
- **INV-1**: mensajes no-sistema en Room alternan `user`/`assistant` y, sin generación en curso, terminan en `assistant`.
- **INV-2**: una conversación creada/reemplazada **nunca** incluye en `initialMessages` el mensaje que se está enviando.
- **INV-3**: ante fallo o cancelación, se cierra la conversación del engine para que el siguiente envío la reconstruya desde Room.

---

## 5. Las 3 mutaciones: cómo se construye cada acción pendiente

### 5.1 `TransactionDraft.fromToolArgs` (`data\model\TransactionDraft.kt`)

Defensas codificadas contra los fallos típicos de un modelo pequeño:

| Problema del modelo | Defensa en código |
|---|---|
| Flippea EXPENSE→INCOME ~1 de cada 6 veces en gastos normales | El **texto del usuario decide primero**: regex `SPEND_WORDS` (spent/paid/bought/rent/…) y `INCOME_WORDS` (salary/refund/cashback/…) sobre `sourceText`; solo si ninguna matchea se confía en el `type` del modelo, y únicamente cuando también nombró una categoría exclusivamente de ingreso |
| Categoría inexistente | Cascada: match exacto → `fallbackCategory(merchant)` vía `SharedCategoryMapping` (auto-categorización por merchant) → "Others" → primera visible |
| Cuenta ambigua ("hdfc" con 2 cuentas HDFC) | Se vincula cuenta **solo si las palabras eligen exactamente una**; last-4 decide primero (acotado por banco), luego alias, luego banco solo. Empate → label "No account (say which — last 4 digits)"; nada de adivinar |
| Monto como string con basura ("₹1,200.50") | `replace(Regex("[^0-9.]"), "")` + `signum() > 0`, si no → null → respuesta "I couldn't work out the amount" |
| `account` = "cash"/"null"/"none" | Se ignora → "Cash / manual" |

### 5.2 `TransactionFinder` (`data\model\PendingChatAction.kt`, mismo archivo)

Para delete/update: tokeniza las palabras del usuario (≥3 chars, stopwords), y trata **amount y daysAgo como restricciones duras, no como tie-breakers** — "una fila equivocada en una tarjeta de delete es peor que 'not found'". Búsqueda con **fallback progresivo**: exacto → sin día → sin monto (marcado `loosened: true`, y la respuesta avisa *"this is the closest one"*) → sin ambos. Rango: últimos 30 días (`transactionRepository.getTransactionsBetweenDates(now-30d, now)`).

### 5.3 `PendingChatAction` (sealed class)

`Add(draft)` · `Delete(transaction)` · `Update(transaction, newCategory?, newMerchant?)`. La confirmación en el `ChatViewModel` ejecuta el caso de uso real (p.ej. `AddTransactionUseCase.execute` con el draft ya validado). Al describir una transacción existente se usa **su propia moneda** (`CurrencyFormatter.formatCurrency(tx.amount, tx.currency)`), nunca la base — verificado en `describe()`.

---

## 6. Ciclo de vida del engine y la conversación (`LlmServiceImpl.kt`)

- `EngineConfig(modelPath, Backend.CPU(), cacheDir)` — **solo CPU** en la implementación actual (la mención de OpenCL/GPU del README es de la capa LiteRT, no de esta config).
- `ConversationConfig`: `systemInstruction`, `initialMessages` (historial replayado desde Room), `tools = [tool(PennyWiseTools())]` solo si `withTools`, **`automaticToolCalling = false`**, `SamplerConfig(topK=10, topP=0.95, temperature=0.8)`.
- **Multimodal**: `initializeVisionModel()` (usado para Gemma 3n E2B) delega en el mismo `initializeLocked(path, isVisionModel = true)`; el flag **solo afecta el log** — el `EngineConfig` (`LlmServiceImpl.kt:60`) es idéntico y **no** declara `visionBackend`/`audioBackend`, solo `backend = Backend.CPU()`. Imagen y audio viajan como `Content.ImageFile` / `Content.AudioFile` (`sendMessageWithImage` / `sendMessageWithAudio`).
- **Thinking**: para Gemma 4 E2B con developer mode on, `LlmRepository.ensureConversation()` arma `ThinkingConfig(enabled=true, budgetTokens=prefs.thinkingBudgetTokens, showRaw=prefs.thinkingShowRaw)`; `LlmServiceImpl.createConversation` lo traduce a `LiteRtThinkingConfig(true, budgetTokens)` (`:97`); la validación `ThinkingConfig.validate(spec.contextTokens)` (budget ≤ 50% del contexto del modelo activo) se hace en `LlmRepository.ensureConversation()` — si falla, thinking se desactiva con `Log.w`. **`showRaw` no se propaga a LiteRT**. Los tokens de thinking se detectan por reflexión (`it::class.simpleName == "Thinking"`, `:150`) porque litertlm 0.17.1 no expone la clase tipada.
- **Mutex `lifecycle`**: create/close/reset corren desde distintos callers en `Dispatchers.IO`; sin el mutex, dos closes podían correr sobre la misma `Conversation` y LiteRT lanzaba `IllegalStateException` (crash real de 2.21.0). Al cerrar se hace `takeConversation()` **antes** de cerrar, para que un close fallido no deje una conversación muerta que el siguiente caller intente cerrar otra vez.
- `closeQuietly()`: un close que LiteRT rechaza (ya cerrada, mid-generation) se traga con log — cleanup no debe crashear la app.
- `reset()`: cierra conversación + engine y anula la referencia (usada p.ej. al borrar el chat).
- El historial **no lo persiste LiteRT**: la conversación se recrea desde `ChatDao.getAllMessagesForContext()` cada vez que no hay conversación activa.
- `ensureConversation()` en `LlmRepository`: si el path del modelo cambió (`llmService.initializedModelPath() != modelFile.absolutePath`) → `reset()` antes de inicializar el nuevo (evita 2 engines en RAM: 1.5 GB + 2.6 GB).

---

## 7. Modelo: descarga, integridad y estados (`ModelRepository.kt`)

- **Archivo**: `getExternalFilesDir(DIRECTORY_DOWNLOADS)/<fileName del modelo activo>`; "descargado" (`isModelDownloaded`, `ModelRepository.kt:112`) = existe y pesa ≥ `min(95% del esperado, 2 GB)`.
- **Sin SHA pinned**: si el spec activo trae `sha256` vacío (posible en Custom HF), `verifyModelIntegrity` (`:139`) devuelve `true` con un `Log.w` "verification is DISABLED" y `isModelVerified` (`:209`) cuenta cualquier archivo descargado como verificado.
- **RAM baja en runtime**: `isLowMemoryForActiveModel()` (`:64`) solo aplica a modelos con `minRamBytes ≥ 4 GB` y avisa si `availMem < 1.5 GB`.
- **SHA-256 streaming en cada carga** (`verifyModelIntegrity`): el comentario es explícito — este es el *load-time security boundary* y **no puede atajarse** con proxies baratos (tamaño, mtime o un flag "ya verificado") porque el modelo tiene longitud fija y un reemplazo same-length pasaría sin leerse. El marker `.verified` que escribe solo alimenta **UI state** (`isModelVerified`), jamás la ruta de carga.
- `ensureConversation()` en `LlmRepository`: si el archivo no existe → *"Model not downloaded"*; si el hash no matchea → **borra el archivo** y lanza *"AI model failed its integrity check and was removed"*.
- `ModelState` (enum): `NOT_DOWNLOADED → DOWNLOADING → (LOADING) → READY | ERROR`. `refreshModelState()` al abrir la app: archivo previamente verificado → READY directo; presente pero sin marker → re-hash antes de confiar, delete si falla. Nunca reporta READY para un archivo no verificado.
- `finalizeDownloadedModel()`: verifica el download recién terminado; promueve a READY o borra.
- URL del modelo y hash pinned: `...\core\Constants.kt` → `Constants.MODEL_URL` / `MODEL_SHA256` / `MODEL_SIZE_BYTES` (descarga one-time ~1.5 GB desde Cloudflare R2; tras eso, IA 100% offline).
- **RAM gate** (`deviceSupports`, `ModelRepository.kt:49`; `refreshActiveModel`, `:87`): filtra catálogo por `ActivityManager.MemoryInfo.totalMem` vs `ChatModelSpec.minRamBytes`. Fallback a Qwen con warning log.

---

## 8. System prompt (`LlmRepository.composeSystemPrompt`)

Diseñado **thin** a propósito (#170): el prompt viejo embebía transacciones recientes y el modelo 1.5B las **parrotaba de vuelta dentro de los args de las tools**. `buildSystemPrompt` (`LlmRepository.kt:545`) reúne los datos (moneda base, categorías visibles, cuentas, `customInstructions`, `systemPromptAdditions`, locale) y delega en `composeSystemPrompt` (`:611`, `internal` en el companion, testeable sin Android). Contenido actual:

1. Identidad + fecha de hoy + línea del mes (`monthSummary` snapshot, con nota de que es solo snapshot).
2. Instrucciones de routing: `addTransaction` para cosas que el usuario cuenta; `spendingByCategory`/`monthSummary`/`budgetStatus`/`categoryBreakdown`/`recentTransactions`/`accountBalance`/`spendingForecast` para preguntas; `update/deleteTransaction` para referencias a algo ya registrado ("the tea from today", "was actually", "delete"); **"Never call addTransaction for those"**.
3. Anti-invención: *"Never invent transactions or figures, and never say you added, changed or deleted anything yourself — only the tools do that, and the user confirms each one."*
4. Listas acotadas: categorías expense (≤1200 chars), income (≤400), cuentas (≤500) vía `promptList()` (los que no caben se cuentan como "and N more") — un chat nuevo siempre cabe en el presupuesto (#959).
5. Cuentas: solo alias o "Banco 1234", **excluyendo las cuentas en `IgnoredAccountsStore`**.
6. Formato: *"Plain text only: no markdown, asterisks or backticks."*
6b. Idioma: *"Respond in the user's language: <locale tag>…"* — `responseLanguage` sale de `resources.configuration.locales[0].toLanguageTag()`.
6c. Seguimiento: si el modelo sugiere una pregunta de follow-up debe ser específica, accionable y profesional.
6d. Bloques condicionales: instrucción de recibo/imagen si el modelo activo es Gemma 3n; instrucción de nota de voz si es Gemma 3n (`supportsAudio`).
7. `updateSystemPrompt()` (W2/W10): actualiza la fila `isSystemPrompt=1` en Room → `chatDao.updateSystemPromptMessage()` → si `generating==false` cierra la conversación (`llmService.closeConversation()`) para que se recree con datos frescos; si `generating==true` **difiere** el cierre hasta que termine la generación (W10). Lo llama `OptimizedSmsReaderWorker` tras procesar SMS.

---

## 9. System Prompt Additions (F8-T1)

**Persistencia:** `UserPreferencesRepository` clave `SYSTEM_PROMPT_ADDITIONS` (DataStore). Flow `systemPromptAdditions` + setter `setSystemPromptAdditions(String)` en `UserPreferencesRepository.kt:1181-1192` (clave en `:206`) y `SettingsViewModel.kt:518-522`.

**Inyección en system prompt:** `LlmRepository.buildSystemPrompt()` (`:545`) lee `systemPromptAdditions` (`:556`) y `composeSystemPrompt()` (`:611`) lo añade como bloque **después** de `customInstructions` con etiqueta semántica distinta:

```
--- Additional user instructions ---
<customInstructions>

--- System prompt additions (semantic) ---
<systemPromptAdditions>
```

**UI:** Settings → Experimental → "System Prompt Additions" (campo multilínea 4 líneas, hint ~200 tokens). Strings: `settings_ai_system_prompt_additions_*` en `strings_settings.xml` (en/es).

**Budget:** Qwen 2560 token context, ~2000 budget, additions reserved ~200 tokens. Tests en `ChatPromptBudgetTest.kt`: `system prompt additions appended with semantic block`, `empty system prompt additions not appended`, `both custom instructions and system prompt additions appended in order`.

**Live update (W2 2026-11-04):** `LlmRepository.updateSystemPrompt()` actualiza la fila `isSystemPrompt=1` en Room → `chatDao.updateSystemPromptMessage()` → `llmService.closeConversation()` → la conversación se recrea con el prompt nuevo en el siguiente mensaje, **sin requerir "Clear chat"**. Settings llama a `llmRepository.updateSystemPrompt()` tras escribir en DataStore.

---

## 10. Custom HF Model — Experimental (F8-T2)

**Configuración:** `CustomModelConfig` data class en `Constants.kt:171-196` (el catálogo `ChatModels` vive en el mismo archivo, `:202`; no existe `ChatModels.kt`) (repoId, fileName, sha256, sizeBytes, contextTokens, minRamBytes, displayName) con JSON serialize/deserialize.

**Validación pre-download:** `SettingsViewModel.validateCustomModel()` hace `HEAD` a `https://huggingface.co/api/models/{repoId}/resolve/main/{fileName}`. Verifica:
- `Content-Length` coincide ±5% con `sizeBytes` provisto
- `X-Huggingface-Sha256` header coincide con `sha256` provisto (si el servidor lo expone)

**Estados (`CustomModelValidationState`):** `IDLE` → `PENDING_VALIDATION` → `VALIDATED` → `DOWNLOADING` → `READY` / `ERROR`. UI refleja cada estado con iconos/spinners.

**Persistencia:** `saveCustomModelConfig()` guarda JSON en `CUSTOM_MODEL_CONFIG` pref. `ChatModels.allWithCustom(customJson)` incluye el modelo custom en el catálogo. `ModelRepository.supportedModelsWithCustom()` filtra por RAM gate.

**UI Experimental:** Settings → Experimental → "Custom HuggingFace Model" con 7 campos editables (repoId, fileName, sha256, sizeBytes, contextTokens, minRamBytes, displayName) + botones Validate / Save / Clear. Strings: `settings_custom_model_*` + `settings_experimental_section` en `strings_settings.xml` (en/es).

**Fallback:** Si validación falla o modelo no pasa RAM gate → built-in Qwen 1.5B.

**Custom HF funcional (W3 2026-11-04):** `ChatModels.byId(id, customConfigJson)` resuelve el modelo custom correctamente; `ModelRepository.refreshActiveModel()` usa `ChatModels.byId(wanted, customConfigJson)`; selector de Settings muestra el modelo custom y se mantiene seleccionado.

---

## 11. Multimodal — Visión y Audio (Gemma 3n E2B)

### Visión (Foto → Transacción)
- **Entrada**: `LlmRepository.sendMessageWithImage(userMessage, imageUri)` → `materializeMedia()` → `LlmService.sendMessageWithImage()` → `Content.ImageFile(path)` (el `EngineConfig` no declara `visionBackend`; ver §6)
- **URIs `content://`**: LiteRT-LM abre el medio por `open(path)`. El selector de galería (`GetContent`) y la cámara (`TakePicture` vía `FileProvider.getUriForFile`) devuelven `content://`, cuyo `Uri.path` no es un archivo real → `NOT_FOUND: open() failed`. `materializeMedia` usa tal cual los URIs `file://` y copia el resto a `filesDir/chat_media/` (extensión por MIME), borrando la copia en `finally`.
- **UI**: Botón attach (cámara) en `ChatScreen` → `ActivityResultContracts.TakePicture` con `FileProvider.getUriForFile` → guarda en `context.filesDir/chat_media/` (scoped storage) → preview chip en el input
- **Gate**: Solo visible si `modelRepository.activeSpec().id == ChatModels.GEMMA_3N_E2B.id`
- **System prompt**: bloque condicional de visión ("If the user attaches an image of a receipt, extract: merchant, amount, date, category. Omit currency if not visible. Emit ONLY the addTransaction tool call…")
- **Latencia**: ~33s en CPU para imagen 512KB → loading indicator "Analizando imagen..." + compresión opcional (downscale ~1024px)

### Audio (Voz → Transacción)
- **Entrada**: `LlmRepository.sendMessageWithAudio(userMessage, audioUri)` → `LlmService.sendMessageWithAudio()` → `Content.AudioFile(path)` (sin `audioBackend` en `EngineConfig`; ver §6)
- **UI**: Botón micrófono (hold-to-record) → `MediaRecorder` (OPUS/.m4a) → archivo temporal en sandbox
- **Pipeline**: Transcripción → `add_transaction` tool call (mismo flujo que texto)
- **Gate**: Solo con Gemma 3n E2B activo (Whisper Base es ASR-only y no es modelo de chat)
- **Latencia**: ~4s para nota de voz típica (OPUS 16-bit, 16kHz, 32kbps)

---

## 12. Thinking Mode (Gemma 4 E2B, Developer Mode)

- **Config**: `ThinkingConfig` en `ConversationConfig` (litertlm 0.17.1+)
- **Activación**: `developer_mode_enabled` + `thinking_enabled` en prefs
- **Budget**: `thinking_budget_tokens` (default 1024, máx 50% del context window)
- **UI**: Colapso "Pensando..." con tokens de razonamiento (solo developer mode)
- **Tokens**: Viajan en `LlmEvent.Thinking(delta)` separado del output final

**Thinking funcional (W0 2026-11-04):** `LlmRepository.respond()` emite `LlmEvent.Thinking` al UI; `ThinkingPanel` muestra tokens en vivo; contador `chat_thinking_tokens` > 0 durante la generación.

---

## 13. Presupuesto de tokens (`TokenUtils.kt`)

| Modelo | Context | Reserved | Chat Budget |
|---|---|---|---|
| Qwen 2.5 1.5B | 4096 | 1536 | **2560** |
| Gemma 4 E2B | 8192 | 1536 | **6656** |
| Gemma 3n E2B | 8192 | 1536 | **6656** |

- `CHAT_TOKEN_BUDGET` (const) = 2560 (Qwen baseline)
- `chatBudget(spec)` dinámico por modelo activo
- `fitsChatBudget(context, msg, spec)` lanza "Chat memory is full" si excede
- `ChatViewModel.chatStats` usa `chatBudget(activeSpec)` para barra "Uso del contexto"
- `sendMessageStream`, `sendMessageWithImage` y `sendMessageWithAudio` comparten `requireFitsChatBudget` y `requireModelNotDownloading` (`LlmRepository.kt`); en imagen/audio corren antes de `ensureConversation()`. El presupuesto solo estima el texto, no los tokens de la imagen/audio.

---

## 14. Privacidad — qué sale del teléfono

**Nada.** El motor corre on-device; el único tráfico relacionado es:

1. La **descarga one-time** del modelo (R2 / HuggingFace, SHA-256 pinned).
2. El historial de chat vive en Room (`ChatMessage`), incluido en el **backup** local (sujeto a niveles FULL/MASKED/ANONYMOUS) — nunca en un servidor.
3. Cero telemetría/crash reporting en el build (verificable en `app\build.gradle.kts`).

La separación con el modo **cloud** (AI Access/relay) es total: ese es otro subsistema (ver `03-ai-access-relay-mcp.md`), con sus propias tools read-only.

---

## 15. Cómo añadir una tool nueva al chat local (procedimiento)

1. Añadir el método `@Tool` a `PennyWiseTools.kt` con `@ToolParam` descriptivos (el modelo solo ve las descripciones) + constante en el `companion object` (snake_case, LiteRT-LM la genera así).
2. Presupuesto: cada tool agrega schema al system turn — se paga de `RESERVED_TOKENS` (1536); muchas tools grandes pueden desatar el #959 de nuevo. Mantener parámetros pocos y con descripciones cortas.
3. Mutación → setear `_pendingAction` (nuevo subtipo de `PendingChatAction`) + texto "confirm below"; nunca escribir Room directo desde el dispatch. Lectura → resolver desde Room (`AiContextRepository` o el repo que sea) y devolver línea de texto.
4. Manejo en `handleToolCallInner` (`when` por `call.name`), soportando args camelCase y snake_case vía `arg()`.
5. Probar el regex `CLAIMS_A_CHANGE` si la tool puede ser "narrada" por el modelo.
6. Gate: `./init.sh app` (compile + unit tests). Verificación en dispositivo recomendada (emulator-verify) para el flujo de confirmación.