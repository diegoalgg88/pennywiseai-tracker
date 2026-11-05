# Chat IA on-device — Análisis verificado y plan de implementación (v2)

> Verificado el 2026-11-04 contra el **working tree** de `C:\Users\DiegoGzz\Documents\Programas\My-Projects\pennywiseai-tracker`
> (HEAD `6fe9fc65` + ~170 archivos modificados sin commit; `git status`). Reemplaza el análisis v1.
> Método: lectura de código (repomix + Filesystem) y `Select-String` para números de línea reales.
> **No se ejecutó compilación ni tests**: todo hallazgo marcado `[ESTÁTICO]` debe confirmarse con `./init.sh app` o en dispositivo.
> Los números de línea corresponden al working tree de esta fecha; **al implementar, ancla por símbolo** (los números derivan al editar).

---

## 0. Reglas para el agente que implemente (obligatorias)

### 0.1 Línea base
- El código del chat **no está en HEAD**: `LlmRepository.kt`, `ChatViewModel.kt`, `ChatScreen.kt`, `LlmServiceImpl.kt`, `LlmService.kt`, `ModelRepository.kt`, `Constants.kt`, `TokenUtils.kt` son trabajo del usuario sin commit. **Construye sobre el working tree. Nunca reviertas ni `git checkout`/`git stash`** (regla del usuario: no `stash` con agentes en paralelo).
- Finales de línea: `ChatViewModel.kt` = **CRLF**; `LlmRepository.kt`, `ChatScreen.kt`, `LlmServiceImpl.kt` = **LF**. Respétalos al editar (git avisa "LF will be replaced by CRLF").
- Repomix con `compress`/comment-stripping **corrompe** literales como `"image/*"` (el `/*` se interpreta como comentario). Para leer código exacto usa `Filesystem:read_text_file` / `Get-Content`, no el volcado de repomix.

### 0.2 Hard constraints de `CLAUDE.md` que tocan este trabajo
| # | Regla | Aplicación aquí |
|---|---|---|
| 1 | Dinero siempre con moneda: `CurrencyFormatter.formatCurrency(amount, code)` | Todo texto nuevo con montos recibe el monto **ya formateado** como argumento de `%1$s`; nunca formatear dentro del XML ni concatenar símbolo. |
| 2 | Nunca sumar entre monedas | No se introduce ninguna suma nueva. `AiContextRepository` ya convierte a moneda base con `convertAmountOrNull` (líneas 58-250) — no tocar. |
| 3 | Campos serializados en backup con default | **`ChatMessage` es entidad de backup** (`@Serializable`, `ChatMessage.kt`). **Prohibido añadir/quitar columnas** en este plan. Solo se añaden métodos `@Query` a `ChatDao` (no cambian el esquema). |
| 4 | Sin PII | Tests y logs con datos sintéticos ("Test Bank 1111"). No loguear texto del usuario completo (los `Log.d` existentes usan `take(50)`; mantener). |
| 5 | No editar versión ni changelogs | Aplica sobre la regla global de CHANGELOG del usuario: **en este repo gana `CLAUDE.md`** (`scripts/release.sh` es dueño). |
| 8 | Migraciones SQL | No hay migración en este plan. Verificar `git diff --stat -- app/schemas` **vacío**. |

### 0.3 Verificación (Definition of Done)
1. `./init.sh app` (compile `:app:compileStandardDebugKotlin` + `:app:testStandardDebugUnitTest`, ver `init.sh:87-88`). Nunca `./gradlew build`/`lint`.
2. Runtime/UI: skill **emulator-verify** + build F-Droid (`AGENTS.md`):
   `.\gradlew.bat :app:assembleFdroidDebug --no-daemon --console=plain` → `adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` → `adb shell am start -n com.pennywiseai.tracker.debug/com.pennywiseai.tracker.MainActivity`.
3. Revisar luz **y** oscuro en toda UI tocada.

### 0.4 Git (reglas del usuario)
- Un commit atómico por ítem `Wn`, Conventional Commits (`fix(chat): …`, subject = el *por qué*). Autor `Diego Gonzalez <diegoalgg88@gmail.com>`.
- **Stagear rutas explícitas** (`git add <path>`), jamás `git add .`. Antes: `git status --porcelain` y separar lo ajeno de lo propio.
- Nunca force-push. **Push/PR solo con confirmación explícita del usuario.**

### 0.5 Herramientas de test verificadas (`app/build.gradle.kts:310-349`, `gradle/libs.versions.toml`)
- Disponibles: `junit`, `mockk` 1.14.9, `kotlin("test")`, `ktor-client-mock`. `mockk-android` en androidTest.
- **No** hay `kotlinx-coroutines-test` ni `turbine` → usar `kotlinx.coroutines.runBlocking` y `flow.toList()`. No añadir dependencias sin pedir permiso.
- Tests JVM que toquen `android.util.Log`: verificar `unitTests.isReturnDefaultValues` en `app/build.gradle.kts` (~l.169); si no está, **no** cambiarlo — aislar la lógica en funciones puras (ver W0/W6).
- El test `app/src/androidTest/.../LlmRepositoryMaterializeMediaTest.kt:146` construye `LlmRepository(llmService = mockk(relaxed = true), …)`: **no cambiar la firma del constructor** de `LlmRepository`; si hace falta una dependencia nueva, reutilizar las existentes.

---

## 1. Veredicto sobre el análisis v1 (claim por claim)

Leyenda: **CONFIRMADO** · **PARCIAL** · **FALSO** · **NO APLICA**. "Real" = línea verificada hoy.

### 1.1 Flujo de respuestas
| Claim v1 | Veredicto | Evidencia / corrección |
|---|---|---|
| "Doble consumo del stream → race conditions, tool calls duplicados" | **FALSO** | `LlmRepository.sendMessageStream` colapsa `llmService.sendMessageEvents` **una sola vez** (`:145`) y `ChatViewModel` colapsa el flow del repo **una sola vez** (`:263`). No hay doble consumo ni race. |
| "Estado inconsistente si hay error post-stream" | **PARCIAL** | El riesgo real es **cancelación/fallo durante el stream** (la respuesta parcial no se persiste y el mensaje de usuario queda huérfano) → **W1**. |
| "Budget validado antes de `ensureConversation` (`:116` vs `:430`)" | **PARCIAL** | Líneas reales `:118` y `:356`. El orden es cierto pero el impacto es bajo (`activeSpec()` solo queda viejo si la preferencia cambia fuera de `ModelRepository.selectModel`). El **orden problemático real** es otro: el mensaje de usuario se inserta *antes* de `ensureConversation` → turno duplicado (**W1**). |
| Diagrama §8 "Recomendada": repo guarda / VM solo UI | **YA ES ASÍ**, y su orden de pasos es **incorrecto** | En HEAD y en working tree el repo ya persiste y el VM solo pinta. El paso "2. Save user message → 3. ensureConversation" del diagrama reproduce el bug de turno duplicado. Ver flujo correcto en W1. |
| *(no estaba en v1)* | **NUEVO P0** | El working tree **no compila**: `ChatViewModel` espera `LlmEvent`, el repo emite `String` → **W0**. |

### 1.2 Markdown
| Claim v1 | Veredicto | Evidencia |
|---|---|---|
| Render plano, prompt prohíbe markdown | **CONFIRMADO** | `ChatScreen.kt:962` `Text(text = message.message …)`; prompt `LlmRepository.kt:719` `Plain text only: no markdown, asterisks or backticks.` |
| Propuesta "markdown rendering opcional" | **DIFERIDO** | Requiere dependencia no verificada; contradice el prompt. Ver §4. |

### 1.3 Tools
| Claim v1 | Veredicto | Evidencia |
|---|---|---|
| 10 tools, 3 mutaciones con confirmación | **CONFIRMADO** | `PennyWiseTools.kt` (10 `@Tool`), mutaciones setean `_pendingAction` en `LlmRepository.handleToolCallInner` (`:471`). Invariante `automaticToolCalling = false` en `LlmServiceImpl.kt:106`. |
| `NARRATED_TOOL` "matchea OK/Yes/Hola → falso positivo" | **FALSO** | `LlmRepository.kt:271-286`: tras `matchEntire` el nombre debe ser **igual a una de las 10 constantes** (camelCase o snake_case). "OK", "Hola" no pasan. **No cambiar el regex** a "prefijo `tool:`": rompería la recuperación de `monthSummary` narrado. |
| `CLAIMS_A_CHANGE` no detecta "I've recorded" | **FALSO** (parcial) | Regex `:734` ya incluye `recorded` y `I(?:'ve\| have)?`. Sí faltan: "It's been added", "it was added" y **todo el español** → **W6c**. La nota `:296` además está en inglés fijo → **W9b**. |
| "Sin visibilidad de tool calls en UI" | **PARCIAL, bajo valor** | Las mutaciones ya muestran args en `PendingActionCard` (`ChatScreen.kt:1130`); las lecturas muestran su resultado. Mostrar nombre/args crudos no aporta. **Rechazado** (§4). |
| "Audio/Vision fuera de `PennyWiseTools`, no unificado" | **NO APLICA** | Son modalidades del mensaje (`Content.ImageFile/AudioFile`), no tools. |
| "PendingActionCard no editable" | **CONFIRMADO** | Solo `onConfirm/onDismiss` (`ChatScreen.kt:1130-1200`). **DIFERIDO** por reformar el flujo de confirmación (#170) → §4. |

### 1.4 UI/UX
| Claim v1 | Veredicto | Evidencia / corrección |
|---|---|---|
| Max width fijo 280 dp | **CONFIRMADO** | `ChatScreen.kt:953` (burbuja) y `:889` (typing) → **W12** |
| Sin copy / long-press | **CONFIRMADO** | Sin `SelectionContainer`/`combinedClickable` en `ChatScreen.kt` → **W12** |
| Timestamp `h:mm a` fijo | **CONFIRMADO** | `ChatScreen.kt:945` ignora la preferencia 12/24 h del sistema → **W12** |
| "Sin distinción visual de system prompt" | **NO APLICA** | `ChatDao.getAllMessages()` ya filtra `isSystemPrompt = 0`. |
| TypingIndicator con status aleatorio | **CONFIRMADO, sin acción** | `ChatScreen.kt:454`. Funciona como diseñado (comentario `:451-453`). |
| ThinkingPanel sin highlight/copy/búsqueda | **IRRELEVANTE**; el panel **nunca recibe datos** | Repo descarta `LlmEvent.Thinking` (`LlmRepository.kt:149,193,233`; solo `Log.d` en `:263-264`) → **W0**. |
| Mic hold-to-record sin feedback | **CONFIRMADO y subestimado** | Hay bugs funcionales (permiso, cancelación, grabación huérfana) → **W5** |
| AudioRecorder "sin path de cancelación" | **PARCIAL** | `tryAwaitRelease()` **sí** devuelve `false` al cancelar, pero el código **ignora el resultado** (`ChatScreen.kt:598-603`) y envía igual → **W5** |
| Imagen sin thumbnail antes de enviar | **CONFIRMADO** | `galleryLauncher` → `viewModel.sendImage(it)` directo (`ChatScreen.kt:~531-533`) → **W13** (opcional) |
| "Clear chat solo aparece ≥90 %" | **FALSO** | Hay un botón "Clear chat" **permanente** cuando `messages.isNotEmpty()` (`ChatScreen.kt:356-385`). El botón del `TokenLimitWarning` (≥90 %, `:723`) es adicional. |
| Warning umbrales 80/90/95 | **CONFIRMADO** | `ChatScreen.kt:344` (≥80 visible), `:673-690`. |
| Sin truncation/summarization | **CONFIRMADO → DIFERIDO** | §4. |

### 1.5 Arquitectura
| # v1 | Claim | Veredicto | Real |
|---|---|---|---|
| 1 | Cast `(llmService as LlmServiceImpl)` `:188` | **PARCIAL** | **Dos** casts: `LlmRepository.kt:188` (innecesario: `LlmService.sendMessageWithImage` **ya existe**, `LlmService.kt:39`) y `:383` (`initializeVisionModel`). La solución v1 "añadir `sendMessageWithImage` a la interfaz" es **inútil**; la real: **W7**. |
| 2 | Race por no chequear `LOADING` | **PARCIAL, bajo** | La UI oculta input en `LOADING` (`ChatScreen.kt:306-321`). Sin acción. |
| 3 | Thinking auto-disabled sin UI | **CONFIRMADO, bajo** | `LlmRepository.kt:421` solo `Log.w`. Sin acción (prioridad real: **W0**, que el thinking nunca llega a UI). |
| 4 | Prompt hardcoded en sendImage/Audio | **CONFIRMADO y peor** | Contamina `notes` de la transacción → **W6** |
| 5 | Error mapping por substring | **CONFIRMADO** | `ChatViewModel.kt:306-312` → **W9** |
| 6 | Sin summarization (replay completo) | **CONFIRMADO → DIFERIDO** | `LlmRepository.kt:393-409` |
| 7 | `updateSystemPrompt` "bajo" | **CONFIRMADO, severidad subestimada → ALTA** | **W2** |

### 1.6 "Bugs confirmados" v1
| # | Veredicto |
|---|---|
| 1 cast | PARCIAL (ver 1.5-#1) |
| 2 NARRATED_TOOL | **FALSO** |
| 3 `clearPendingAction` silencioso | **NO ES BUG**: es diseño (`ChatViewModel.kt:~251` "A new message supersedes any card still waiting"). |
| 4 prompt hardcoded | CONFIRMADO (W6) |
| 5 "tools SIEMPRE se pasan; `withTools` default=true" | **FALSO**: el default de la interfaz es `false` (`LlmService.kt:28`); el repo pasa `true` **explícitamente** (`LlmRepository.kt:427`). Intencional. |

### 1.7 Tabla de números de línea (v1 → real)
`LlmRepository`: 116→**118**, 188→**188/383**, 261→**271 (uso) / 740 (decl.)**, 430→**356**, 460-480→**~410-425**, 675→**640**, 705→**719**, 720→**734**.
`ChatViewModel`: 287→**~251**, 345→**306**, 380→**350**, 414→**397**. `LlmServiceImpl`: 114→**105-106**.

---

## 2. Resumen de work items

| ID | Prio | Tipo | Origen | Commit sugerido |
|---|---|---|---|---|
| **W0** | P0 | Compilación | Nuevo | `fix(chat): restore Flow<LlmEvent> contract between LlmRepository and ChatViewModel` |
| **W1** | P1 | Correctitud de datos | Nuevo | `fix(chat): never replay the in-flight user message and keep history alternating` |
| **W2** | P1 | Correctitud | v1#7 ↑ | `fix(chat): apply prompt/custom-instruction changes to the live conversation` |
| **W3** | P1 | Correctitud | Nuevo | `fix(chat): resolve custom HF models and hide ASR-only Whisper from the chat selector` |
| **W4** | P1 | Crash `[ESTÁTICO]` | Nuevo | `fix(chat): hand the camera a FileProvider uri instead of file://` |
| **W5** | P1 | Correctitud | v1 ↑ | `fix(chat): make hold-to-record permission-safe and cancellable` |
| **W6** | P1 | Correctitud/i18n | v1#4 ↑ | `fix(chat): stop leaking hardcoded media prompts into transaction notes` |
| **W7** | P2 | Arquitectura | v1#1 | `refactor(chat): drop LlmServiceImpl casts and dead initializeVisionModel` |
| **W8** | P2 | DRY | Nuevo | `refactor(chat): collapse triplicated stream handling in service and viewmodel` |
| **W9** | P2 | Robustez/i18n | v1#5 | `refactor(chat): typed ChatError and localized canned replies` |
| **W10** | P2 | Concurrencia | Nuevo | `fix(chat): never close the conversation while a reply is generating` |
| **W11** | P2 | UI | Nuevo | `fix(chat): stable list keys and correct auto-scroll while streaming` |
| **W12** | P3 | UX | v1 | `feat(chat): selectable text, adaptive bubble width, system 12/24h clock` |
| **W13** | P3 | UX (opcional) | v1 | `feat(chat): preview the attached photo before sending` |
| **W14** | P3 | Limpieza | Nuevo | `chore(chat): remove unused LlmRepository.sendMessage path` |
| **W15** | P2 | Tests | v1 | `test(chat): cover token budgets, model catalog and reply heuristics` |
| **W16** | P2 | Docs | Nuevo | `docs(chat): correct token budget, camera and audio claims` |

**Orden obligatorio**: W0 → W1 → W2 → W3 → (W4, W5, W6 en cualquier orden) → W7 → W8 → W9 → W10 → W11 → W15 → W12 → W13 → W14 → W16.
W7/W8/W9 dependen de W0/W1 porque reescriben las mismas funciones; **no los adelantes**.

---

## 3. Work items

### W0 — Restaurar el contrato `Flow<LlmEvent>` Repositorio ↔ ViewModel  `[P0 · ESTÁTICO]`

**Evidencia**
```kotlin
// LlmRepository.kt:96, :168, :215  (working tree)
fun sendMessageStream(userMessage: String): Flow<String> = flow { … emit(event.delta) … }
suspend fun sendMessageWithImage(userMessage: String, imageUri: Uri): Flow<String> = flow { … }
suspend fun sendMessageWithAudio(userMessage: String, audioUri: Uri): Flow<String> = flow { … }

// ChatViewModel.kt:263-275  (idéntico en :350-362 y :397-409)
llmRepository.sendMessageStream(message)
    .catch { … }
    .collect { event ->
        when (event) {
            is LlmEvent.Text     -> _currentResponse.value += event.delta
            is LlmEvent.Thinking -> _uiState.value = _uiState.value.copy(thinkingTokens = …)
        }
    }
```
`event: String` frente a `is LlmEvent.Text` ⇒ error de compilación "incompatible types". En HEAD el VM hacía `.collect { partialResponse -> …}` (HEAD `ChatViewModel.kt:271`): el VM fue migrado a eventos y el repo no. Consecuencia colateral: `LlmEvent.Thinking` solo se bufferiza y se loguea (`LlmRepository.kt:149/193/233`, `:263-264`), así que `ThinkingPanel` (`ChatScreen.kt:499-503`) **nunca muestra nada** — contradice `CLAUDE.md` ("Raw tokens visible only in developer mode").

**Cambio**
1. `LlmRepository`: las tres funciones pasan a devolver `Flow<LlmEvent>`. Quitar `suspend` de `sendMessageWithImage/Audio` (el `flow { }` no lo necesita).
2. Extraer **un** helper privado (elimina el triple bloque `:140-162`, `:178-209`, `:221-249`):
   ```kotlin
   private fun respond(heuristicsText: String, upstream: Flow<LlmEvent>, logLabel: String): Flow<LlmEvent> = flow {
       val text = StringBuilder(); val thinking = StringBuilder()
       val toolCalls = mutableListOf<LlmEvent.ToolCall>()
       upstream.collect { e ->
           when (e) {
               is LlmEvent.Text     -> { text.append(e.delta); emit(e) }
               is LlmEvent.Thinking -> { thinking.append(e.delta); emit(e) }
               is LlmEvent.ToolCall -> toolCalls.add(e)          // se resuelve internamente, NO se emite
           }
       }
       val final = handleResponseAndEmit(text, toolCalls, thinking, heuristicsText)
       chatDao.insertMessage(ChatMessage(message = final, isUser = false))
       Log.d(TAG, "AI response saved ($logLabel)")
   }
   ```
3. `ChatViewModel`: añadir `is LlmEvent.ToolCall -> Unit // resuelto en LlmRepository` para que el `when` sea exhaustivo (Kotlin ≥1.7 exige exhaustividad en `when` sobre sealed).
4. El thinking **no** se persiste en `ChatMessage` (solo UI efímera, `uiState.thinkingTokens`).

**Resultado esperado**
- `./init.sh app` compila sin errores de tipos.
- Con Gemma 4 E2B + developer mode + thinking on, el `ThinkingPanel` muestra tokens y el contador `chat_thinking_tokens` > 0 durante la generación.
- Mensaje del asistente persistido **una sola vez** por turno; sin tokens de thinking en `chat_messages`.

**Tests nuevos** (`app/src/test/.../data/repository/LlmRepositoryStreamTest.kt`, mockk + `runBlocking`)
- `stream emits text and thinking in order and never tool calls`
- `assistant message is inserted exactly once after the stream completes`
- `thinking tokens are not persisted`
Mocks: `ChatDao`, `LlmService` (`sendMessageEvents` → `flowOf(...)`), `ModelRepository` (`getModelFile()` → archivo temporal existente, `verifyModelIntegrity()` → `true`, `activeSpec()` → `ChatModels.QWEN_1_5B`, `modelState` → `flowOf(READY)`), el resto `relaxed`.

---

### W1 — Turno de usuario duplicado, huérfanos y alternancia del historial  `[P1]`

**Evidencia (turno duplicado)**
```kotlin
// LlmRepository.kt  sendMessageStream
118: requireFitsChatBudget(currentMessages, userMessage)
126: chatDao.insertMessage(userChatMessage)     // el mensaje YA está en Room
129: requireModelNotDownloading()
132: ensureConversation()
// ensureConversation
393: if (!llmService.hasActiveConversation()) {
394:     val allMessages = chatDao.getAllMessagesForContext()   // incluye el mensaje de :126
406:     .filter { !it.isSystemPrompt }.map { it.message to it.isUser }  // → initialMessages
// :145  llmService.sendMessageEvents(userMessage)            // lo envía OTRA VEZ
```
Cada vez que la conversación se (re)crea (primer mensaje de un chat nuevo, reinicio de app, cambio de modelo, `closeConversation` tras escaneo de SMS) el modelo recibe **el último mensaje del usuario dos veces seguidas**. Se corrobora con el log `Conversation created with N history messages` (`:431`): N es impar en el primer envío.

**Evidencia (huérfanos)**
- `:126` inserta al usuario *antes* de `requireModelNotDownloading()`/`ensureConversation()`: si lanzan (`Model not downloaded`, integridad, init) queda un mensaje de usuario sin respuesta; el siguiente replay envía dos turnos `user` consecutivos (los templates Gemma/Qwen esperan alternancia) `[ESTÁTICO]`.
- `sendMessageWithImage` (`:168-212`) y `sendMessageWithAudio` (`:215-250`) **nunca insertan mensaje de usuario**, solo el del asistente (`:207`, `:247`): el usuario no ve su foto/nota en el chat y el historial queda con `assistant` sin `user` previo.
- Si el flow se cancela (el VM se destruye → `viewModelScope` cancelado) o falla el engine, no se persiste respuesta parcial ni se limpia el estado del engine.

**Invariantes a imponer**
- **INV-1**: los mensajes no-sistema en Room alternan `user`/`assistant` y, sin generación en curso, terminan en `assistant`.
- **INV-2**: una conversación creada/reemplazada **nunca** incluye en `initialMessages` el mensaje que se está enviando.
- **INV-3**: ante fallo o cancelación, se cierra la conversación del engine para que el siguiente envío la reconstruya desde Room.

**Flujo correcto** (reemplaza el diagrama §8 de v1)
```
sendMessageStream(userMessage):
  1. modelRepository.refreshActiveModel()                // budget contra el spec vigente
  2. ensureSystemPromptForNewChat()                      // lógica actual :98-114
  3. requireModelNotDownloading(); requireFitsChatBudget(history, userMessage)   // ANTES de escribir al usuario
  4. userRow = ChatMessage(message=userMessage, isUser=true); chatDao.insertMessage(userRow)   // UX: aparece al instante
  5. try { ensureConversation(excludeMessageId = userRow.id)   // INV-2
           emitAll(respond(userMessage, llmService.sendMessageEvents(userMessage), "text")) }
     catch (t: Throwable) { withContext(NonCancellable) {
           if (parcial.isNotBlank()) chatDao.insertMessage(assistant(parcial))   // conserva alternancia
           else chatDao.deleteById(userRow.id)                                   // INV-1
           llmService.closeConversation() }                                      // INV-3
         throw t }
```
**Cambios concretos**
1. `ensureConversation(excludeMessageId: String? = null)`; `history = allMessages.filter { !it.isSystemPrompt && it.id != excludeMessageId }…`.
2. `ChatDao`: añadir (solo `@Query`, **sin tocar entidad ni esquema**):
   ```kotlin
   @Query("DELETE FROM chat_messages WHERE id = :id") suspend fun deleteById(id: String)
   ```
3. `respond()` (W0) acumula `text` fuera del `flow {}` (p. ej. recibe un `StringBuilder` compartido) para que el `catch` del paso 5 conozca el parcial.
4. Media: insertar mensaje de usuario **marcador localizado** antes de `ensureConversation` (`chat_user_photo_message` = "📷 Photo" / es "📷 Foto"; `chat_user_voice_message` = "🎤 Voice note" / es "🎤 Nota de voz") vía `context.getString`. **No** guardar rutas de media (sin cambio de esquema).
5. Mantener `requireModelNotDownloading()`/`requireFitsChatBudget` **antes** del insert en los tres puntos de entrada.

**Resultado esperado**
- Chat nuevo, primer mensaje → log `Conversation created with 0 history messages`.
- Reabrir app (matar proceso) y enviar → `N` = nº de mensajes previos **par** (pares user/assistant).
- Enviar sin modelo descargado → mensaje de error en UI y **0 filas nuevas** en `chat_messages` (el usuario no queda huérfano).
- Foto y nota de voz aparecen como burbuja del usuario con su marcador.
- Cancelar a mitad de stream (salir de la pestaña) → en Room queda el parcial como mensaje del asistente (o se borra el del usuario si no hubo texto); el siguiente envío no falla.

**Comprobación en BD** (build debug):
`adb shell run-as com.pennywiseai.tracker.debug sqlite3 databases/pennywise_database "select isUser,substr(message,1,30) from chat_messages order by timestamp;"` → la columna `isUser` alterna 1/0.

**Tests nuevos** (`LlmRepositoryConversationTest.kt`)
- `history passed to createConversation excludes the message being sent` (capturar `createConversation(systemPrompt, history, …)` con `slot`).
- `user row is deleted when ensureConversation throws`
- `partial reply is persisted and conversation closed when the stream fails midway`
- `budget is checked before the user row is written` (`coVerify(exactly = 0) { chatDao.insertMessage(match { it.isUser }) }` cuando `requireFitsChatBudget` falla).
- `media entry points write a user marker row`

---

### W2 — `updateSystemPrompt()` no actualiza la conversación y Settings nunca la dispara  `[P1]`

**Evidencia**
```kotlin
// LlmRepository.kt:640-647
suspend fun updateSystemPrompt() {
    val newPrompt = buildSystemPrompt(aiContextRepository.getChatContext())
    userPreferencesRepository.updateSystemPrompt(newPrompt)   // solo DataStore
    llmService.closeConversation()                            // …y la recrea con:
}
// ensureConversation :397-400  → toma el system prompt de la FILA ANTIGUA en Room
val systemPrompt = allMessages.firstOrNull { it.isSystemPrompt }?.message ?: buildSystemPrompt(…)
```
- El prompt nuevo se escribe en prefs pero la conversación se reconstruye con la fila `isSystemPrompt = 1` **del inicio del chat** → el "refresco" (llamado desde `HomeViewModel.kt:1150-1153` y `OptimizedSmsReaderWorker.kt:1113`) no tiene efecto en chats existentes.
- `SettingsViewModel.setCustomInstructions` (`:511`) y `setSystemPromptAdditions` (`:518`) solo escriben prefs; `SettingsViewModel` **no referencia `LlmRepository`** (grep). Resultado: *System Prompt Additions (F8-T1)* y *custom instructions* **solo aplican tras "Clear chat"**, contra lo que documenta `docs/dg-develop/01-ia-on-device-chat.md §9`.

**Cambio**
1. `ChatDao`: `@Query("UPDATE chat_messages SET message = :message WHERE isSystemPrompt = 1") suspend fun updateSystemPromptMessage(message: String)`.
2. `LlmRepository.updateSystemPrompt()`: tras `buildSystemPrompt`, llamar `chatDao.updateSystemPromptMessage(newPrompt)` **antes** de `closeConversation()` (respetando W10). Conservar `userPreferencesRepository.updateSystemPrompt(newPrompt)` (lo consume `BackupImporter.kt:996`).
3. Inyectar `LlmRepository` (Singleton Hilt) en `SettingsViewModel` y llamar `llmRepository.updateSystemPrompt()` en `setCustomInstructions` y `setSystemPromptAdditions`, **después** del write a prefs, dentro de `viewModelScope.launch`.
4. Los topes de `promptList()` (`:~700`, `MAX_*_CHARS`) garantizan que el prompt no crece: sin cambios.

**Resultado esperado**
- Editar "System Prompt Additions" en Settings → en el siguiente mensaje del chat **existente** el log muestra `Conversation created with N history messages` y `DeveloperInfoCard` refleja otro `systemPromptTokens`.
- Tras un escaneo de SMS, el siguiente mensaje usa la línea del mes actualizada.
- Sin filas `isSystemPrompt` duplicadas (`select count(*) from chat_messages where isSystemPrompt=1` ≤ 1).

**Tests** (`LlmRepositoryUpdatePromptTest.kt`): `updateSystemPrompt rewrites the stored system row before closing the conversation` (`coVerifyOrder { chatDao.updateSystemPromptMessage(any()); llmService.closeConversation() }`).

---

### W3 — Catálogo de modelos: custom HF inalcanzable y Whisper seleccionable como modelo de chat  `[P1]`

**Evidencia**
```kotlin
// Constants.kt:282-287
val ALL = listOf(QWEN_1_5B, GEMMA_4_E2B, GEMMA_3N_E2B, WHISPER_BASE)
fun byId(id: String?): ChatModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT    // ignora custom
// ModelRepository.kt:87-95
val spec = ChatModels.byId(wanted)            // un id "custom-…" nunca se resuelve
// SettingsViewModel.kt:354-358  → _selectedModelId = modelRepository.activeSpec().id  (vuelve a Qwen)
```
- Seleccionar el modelo custom en Settings **silenciosamente revierte a Qwen** (la UI lo lista vía `supportedModelsWithCustom`, `SettingsViewModel.kt:336`). F8-T2 no es funcional para el chat.
- `WHISPER_BASE` (`Constants.kt:246-253`, `contextTokens = 0`) está en `ALL` y por tanto en el selector. `TokenUtils.chatBudget` = `0 - 1536 = -1536` (`TokenUtils.kt:35`) ⇒ `fitsChatBudget` es siempre `false` ⇒ "Chat memory is full" en el primer mensaje; además `ensureConversation` intentaría crear una conversación con tools sobre un modelo ASR. Whisper solo se referencia en `LlmRepository.kt:618`, `ChatScreen.kt:578`, `SettingsScreen.kt:2262` y no existe pipeline ASR separado. `[ESTÁTICO: confirmar en dispositivo que seleccionar Whisper rompe el chat]`

**Cambio**
1. `ChatModels.byId(id: String?, customConfigJson: String? = null)` busca en `allWithCustom(customConfigJson)`; sin match → `DEFAULT`. Un solo punto de resolución (DRY). `LlmProbeReceiver.kt:93` (debug) puede seguir usando la firma de 1 argumento.
2. `ModelRepository.refreshActiveModel()`: `ChatModels.byId(wanted, userPreferencesRepository.getCustomModelConfigSync())` (ya usado en `:74-76`).
3. `TokenUtils.isChatCapable(spec: ChatModelSpec): Boolean = chatBudget(spec) > 0`. `ModelRepository.refreshActiveModel()` cae a `ChatModels.DEFAULT` (con `Log.w`) si `!isChatCapable(spec) || !deviceSupports(spec)` — cubre selecciones previamente persistidas de Whisper.
4. `supportedModels()` y `supportedModelsWithCustom()` filtran además por `isChatCapable`.
5. Gating de audio solo para Gemma 3n: quitar `WHISPER_BASE` de `ChatScreen.kt:578-579` y `LlmRepository.kt:618`. **No** borrar `WHISPER_BASE` del objeto (decisión de producto del dueño).
6. `sendMessageWithAudio` valida modelo como ya hace visión (`:169-172`): `activeSpec().id == GEMMA_3N_E2B.id`, si no → `ChatError.AudioRequiresGemma3n` (ver W9).

**Resultado esperado**
- Settings: el selector muestra solo modelos aptos para chat; elegir el custom (con config válida) mantiene el radio en el custom y `ModelRepository.activeSpec().id == "custom-…"`.
- Chat con Whisper persistido en prefs arranca con Qwen y log de fallback.
- Botón de micrófono solo con Gemma 3n activo.

**Tests** (`ChatModelsTest.kt`, JVM puro — `CustomModelConfig.toJson()` ya existe):
- `byId resolves the custom spec when its config json is supplied`
- `byId falls back to DEFAULT for unknown ids`
- `whisper is not chat capable and every other built-in model is`
- `chat budget of gemma models is 6656` (8192 − 1536)

---

### W4 — Cámara: `Uri.fromFile` con `TakePicture`  `[P1 · ESTÁTICO]`

**Evidencia**
```kotlin
// ChatScreen.kt:558-561
val file = createImageFile(context)                    // getExternalFilesDir(DIRECTORY_PICTURES), :1203
file?.let { pendingCameraUri = Uri.fromFile(it); cameraLauncher.launch(pendingCameraUri!!) }
```
`TakePicture` pone el URI en `EXTRA_OUTPUT`; con `targetSdk ≥ 24` un `file://` expuesto lanza `FileUriExposedException` al lanzar el intent. `res/xml/file_paths.xml` ya declara `camera_temp` (cache) **sin usar** y no declara `external-files-path`. `it.deleteOnExit()` (`:1213`) no hace nada útil en Android. `docs/dg-develop/01-ia-on-device-chat.md §11` afirma "guarda en `filesDir/chat_media/` + preview chip": **ninguna de las dos cosas es cierta**.

**Cambio**
```kotlin
private fun createCameraTarget(context: Context): Pair<File, Uri>? = runCatching {
    val dir = File(context.cacheDir, "camera_temp").apply { mkdirs() }
    val file = File.createTempFile("IMG_", ".jpg", dir)
    file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()
```
- Guardar `Pair<File, Uri>` en el `remember`; en el callback: `success` → `viewModel.sendImage(uri)`; `!success` → `file.delete()`.
- `LlmRepository.materializeMedia(uri, prefix, defaultExtension)`: tras copiar a `filesDir/chat_media`, si `uri.authority == "$packageName.fileprovider"` y `uri.pathSegments.firstOrNull() in setOf("camera_temp", "voice_notes")` → `runCatching { context.contentResolver.delete(uri, null, null) }` (FileProvider borra el archivo). **Nunca** borrar URIs de galería. Eliminar `createImageFile` (`:1203-1216`).
- Mantener firma `internal suspend fun materializeMedia(...)` (la usa el androidTest).

**Resultado esperado**: capturar con cámara (API ≥ 24) no lanza excepción, la imagen llega a `Content.ImageFile` vía copia en `chat_media`, y `cache/camera_temp/` queda vacío tras enviar o cancelar. Verificar en emulador (emulator-verify) con cámara virtual.

---

### W5 — Micrófono hold-to-record  `[P1]`

**Evidencia** (`ChatScreen.kt:582-603`, `AudioRecorder.kt`)
```kotlin
onPress = {
    permissionLauncher.launch(RECORD_AUDIO)     // en CADA pulsación
    tryAwaitRelease()                           // resultado ignorado
    scope.launch { AudioRecorder.stop(context)?.let { viewModel.sendAudio(it) } }
    isRecording = false
}
// callback del permiso → AudioRecorder.start(context)
```
1. Primera vez: el diálogo de permiso cancela el gesto → `stop()` devuelve `null` (aún no hay recorder) → *después* el callback concede y llama `start()`: **grabación sin dueño** que nadie detiene. `MAX_DURATION_MS` (`AudioRecorder.kt`) está declarada y **no se aplica** (`start()` no llama `setMaxDuration`).
2. `tryAwaitRelease() == false` (gesto cancelado/arrastre fuera) se trata como envío.
3. Sin indicador de tiempo; el archivo `voice_*.m4a` en `cacheDir` no se borra tras enviar.
4. Real vs doc: `docs/dg-develop/01 §11` dice WAV/AAC-3GP; el código graba **OPUS en .m4a** (`AudioRecorder.start`).

**Cambio**
- `onPress`: si `checkSelfPermission(RECORD_AUDIO) == GRANTED` → `isRecording = AudioRecorder.start(context)`; si no → `permissionLauncher.launch(...)` y `return@onPress` (el callback de permiso **no** inicia grabación; solo deja listo para la próxima pulsación).
- `val released = tryAwaitRelease()`; si `released` → `stop()` + `sendAudio`; si no → `AudioRecorder.cancel()` (nuevo: detiene, libera y borra el archivo).
- `AudioRecorder.start`: `r.setMaxDuration(MAX_DURATION_MS.toInt())`. `stop()` ya envuelve `r.stop()` en `runCatching` (soporta recorder ya detenido por límite).
- UI: mientras `isRecording`, mostrar duración `m:ss` (un `LaunchedEffect(isRecording)` que incrementa cada segundo) junto al botón.
- El archivo de voz se borra vía `materializeMedia` (ver regla `voice_notes` en W4).

**Resultado esperado**: (a) primera pulsación → diálogo de permiso, **ningún** recorder activo después; (b) pulsar <0,5 s → no envía ni muestra error (`MIN_DURATION_MS`); (c) arrastrar fuera del botón → se cancela sin enviar; (d) 60 s máx.; (e) `cache/` sin `voice_*.m4a` residuales tras enviar. Manual (MediaRecorder no es testeable en JVM).

---

### W6 — Prompts de media hardcodeados, `sourceText`/`notes` contaminados, heurísticas solo en inglés  `[P1]`

**Evidencia**
```kotlin
// ChatViewModel.kt:350, :397
llmRepository.sendMessageWithImage("Extract transaction from this receipt", imageUri)
llmRepository.sendMessageWithAudio("Transcribe this voice note and extract the transaction", audioUri)
// → handleResponseAndEmit(…, userMessage) → TransactionDraft.sourceText = ese texto
// ChatViewModel.kt:230
notes = "Added from chat: \"${draft.sourceText}\"",   // guarda "Added from chat: "Extract transaction from this receipt""
```
`TransactionDraft.SPEND_WORDS/INCOME_WORDS` (`TransactionDraft.kt`) son **solo inglés** y usan `\b…\b`; para el usuario es-MX ("gasté", "pagué", "recibí", "nómina") nunca disparan y el tipo depende del modelo (que "flippea EXPENSE→INCOME ~1 de 6", comentario del propio archivo). Además el prompt de visión pide `currency` que `addTransaction` **no tiene** (`PennyWiseTools.kt`) → se descarta y la transacción se guarda en moneda base (`ChatViewModel.kt:~226 currency = currency`).

**Cambio**
- **W6a** `LlmRepository.sendMessageWithImage(imageUri)` / `sendMessageWithAudio(audioUri)` sin parámetro de texto; los prompts viven como constantes `IMAGE_PROMPT`/`AUDIO_PROMPT` en el `companion` (inglés, idioma del system prompt). `handleResponseAndEmit(..., heuristicsText = "")` para media. El VM deja de conocer los prompts.
- **W6b** `confirmPendingAction`: `notes = draft.sourceText.takeIf { it.isNotBlank() }?.let { "Added from chat: \"$it\"" } ?: "Added from chat"`.
- **W6c** Heurísticas ES. En `TransactionDraft` y `LlmRepository.CLAIMS_A_CHANGE`:
  - **No usar `\b` con letras acentuadas** (en Java/Kotlin `\b` no trata `é` como palabra ⇒ `\bgasté\b` falla). Usar lookarounds: `(?<![\p{L}])(gast[ée]|pagu[ée]|compr[ée]|envi[ée]|renta|recarga|suscripci[oó]n|donaci[oó]n)(?![\p{L}])`.
  - Ingreso: `recib[ií]|cobr[ée]|sueldo|salario|n[oó]mina|reembolso|devoluci[oó]n|dep[oó]sito|ingreso|gan[ée]|bono|intereses|cashback`.
  - `CLAIMS_A_CHANGE`: añadir `it'?s been|it was` y verbos ES (`he (agregado|añadido|registrado|eliminado|actualizado|borrado)`, `se (ha )?(agreg|añadi|registr|elimin|actualiz)[óo]`).
  - Mantener el comportamiento actual en inglés (los tests existentes deben seguir verdes).
- **W6d** Quitar la palabra `currency` de los bloques de visión/audio del system prompt (`composeSystemPrompt`, `:~705-715`): pedir un dato que el tool no recibe solo gasta tokens.

**Resultado esperado**: notas de transacciones desde foto/voz = `Added from chat`; "gasté 120 en el súper" resuelve `EXPENSE` y "recibí mi nómina" `INCOME` aunque el modelo se equivoque; los tests EN existentes no regresan.

**Tests** (extender `app/src/test/.../data/model/TransactionDraftTest.kt`): casos ES con acentos (`gasté`, `pagué`, `recibí`, `nómina`), y `media draft has blank sourceText`. Para `CLAIMS_A_CHANGE`/`NARRATED_TOOL` extraer funciones puras (ver W15).

---

### W7 — Quitar casts a `LlmServiceImpl` y `initializeVisionModel`  `[P2]`

**Evidencia**: `LlmRepository.kt:187-188` y `:382-386`; `LlmServiceImpl.kt:53-56,57,68`. `initializeLocked(path, isVisionModel)` usa el flag **solo en un `Log.d`** (`:68`): `initializeVisionModel` ≡ `initialize`. `initializeVisionModel` solo se llama en `LlmRepository.kt:383` (grep en `app/src`).

**Cambio**: `:188` → `llmService.sendMessageWithImage(...)`; `:382-386` → `llmService.initialize(modelFile.absolutePath)`; eliminar `initializeVisionModel` y el parámetro `isVisionModel`.

**Resultado esperado**: `Select-String -Path app\src -Pattern 'as com.pennywiseai.tracker.data.service.LlmServiceImpl|initializeVisionModel' -Recurse` → 0 resultados; `LlmServiceImpl` solo referenciado desde `di/LlmModule.kt` y su archivo. `CLAUDE.md` sigue válido (`EngineConfig` no declara `visionBackend/audioBackend`; **no** tocar — latencias medidas en `docs/dg-develop/01 §11`).

---

### W8 — DRY: servicio y ViewModel  `[P2]`

**Evidencia**
- `LlmServiceImpl.kt`: `sendMessageEvents` (`:128-160`), `sendMessageWithImage` (`:162-197`), `sendMessageWithAudio` (`:199-233`) repiten el bloque `catch → transform(text, thinking por reflexión, toolCalls) → flowOn`. Los errores de URI inválida se emiten **como texto del asistente** (`:169`, `:206` `Error: Invalid image URI`), que luego se persiste como respuesta.
- `ChatViewModel.kt`: `sendMessage` (`:248-291`), `sendImage` (`:337-378`), `sendAudio` (`:384-425`) repiten ~40 líneas idénticas; además `catch (e: Exception)` traga `CancellationException`.

**Cambio**
1. `LlmServiceImpl`: `private fun Flow<Message>.toLlmEvents(label: String): Flow<LlmEvent>` (catch+log, `transform`, `flowOn(IO)`); `private fun requireConversation(): Conversation`; las tres funciones públicas solo construyen el `Flow<Message>` (`sendMessageAsync(String)` o `sendMessageAsync(Contents)`) y llaman `toLlmEvents`. `conversation` y `engine` pasan a `@Volatile` (se leen sin mutex en `:129,163,200,236`). URI inválida → `throw ChatError.MediaUnreadable()` (W9) en lugar de emitir texto.
   Nota: la detección de thinking por reflexión (`:149-153`) sobrevive a R8 porque `proguard-rules.pro:24` hace `-keep class com.google.ai.edge.litertlm.** { *; }`; centralizarla en una función `Message.thinkingText()` y dejar `// TODO` de reemplazo por la clase tipada cuando litertlm la exponga.
2. `ChatViewModel`: un único
   ```kotlin
   private fun runGeneration(source: suspend () -> Flow<LlmEvent>) {
       if (_uiState.value.isLoading) return
       llmRepository.clearPendingAction()
       viewModelScope.launch {
           _uiState.update { it.copy(isLoading = true, error = null, thinkingTokens = "") }
           _currentResponse.value = ""
           try { source().collect { e -> when (e) { /* Text, Thinking, ToolCall -> Unit */ } } }
           catch (c: CancellationException) { throw c }
           catch (e: Exception) { _uiState.update { it.copy(error = e.toChatUiText(R.string.chat_error_send_failed)) } }
           finally { _uiState.update { it.copy(isLoading = false) }; _currentResponse.value = "" }
       }
   }
   ```
   y `sendMessage`/`sendImage`/`sendAudio` quedan en ≤4 líneas cada una (`sendMessage` conserva `if (message.isBlank()) return`). Importar `kotlinx.coroutines.flow.update`.

**Resultado esperado**: comportamiento idéntico; `ChatViewModel.kt` −~90 líneas; `LlmServiceImpl.kt` −~60; un solo punto para cambiar el manejo de eventos. Salir de la pantalla durante la generación no deja `isLoading=true` colgado.

---

### W9 — Errores tipados y respuestas enlatadas localizadas  `[P2]`

**W9a — `ChatError`**
Evidencia: `LlmRepository` lanza `Exception("…")` en inglés (`:171,309,316,334,370,377,388,429`) y `ChatViewModel.sendErrorText` (`:306-312`) decide con `error.message?.contains("memory is full"|"downloading"|"not downloaded")`. Cualquier otro mensaje (`integrity check`, `Vision requires…`, `Could not read…`) se muestra **crudo en inglés** vía `UiText.Plain`.

Cambio — nuevo `domain/service/ChatError.kt` (junto a `LlmService`, para que `LlmServiceImpl` pueda usarlo sin invertir capas):
```kotlin
sealed class ChatError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MemoryFull : ChatError("Chat memory is full")
    class ModelDownloading : ChatError("Model is downloading")
    class ModelNotDownloaded : ChatError("Model not downloaded")
    class ModelIntegrityFailed : ChatError("Model failed its integrity check")
    class VisionRequiresGemma3n : ChatError("Vision requires Gemma 3n E2B")
    class AudioRequiresGemma3n : ChatError("Audio requires Gemma 3n E2B")
    class MediaUnreadable(cause: Throwable? = null) : ChatError("Could not read the selected media", cause)
}
```
- Sustituir los `throw Exception(...)` correspondientes. Errores de init del engine siguen propagando la causa original.
- `internal fun Throwable.toChatUiText(@StringRes fallback: Int): UiText` (archivo `ui/screens/chat/ChatErrors.kt`) con `when (this) { is ChatError.MemoryFull -> UiText.Res(R.string.chat_error_memory_full) … else -> message?.let(UiText::Plain) ?: UiText.Res(fallback) }`.
- Reutilizar strings existentes (`chat_error_memory_full`, `chat_error_model_downloading`, `chat_error_model_not_downloaded`, `chat_error_integrity_failed` — **verificar su texto**: hoy lo usa el fallo de *descarga*, `ChatViewModel.kt:~152`); crear `chat_error_vision_requires_gemma3n`, `chat_error_audio_requires_gemma3n`, `chat_error_media_unreadable`.
- Strings viven en `app/src/main/res/values/strings_rules.xml` (y `values-<loc>/strings_rules.xml`; `chat_error_memory_full` en `:300`). Añadir EN + **es obligatorio**; los demás locales caen al default (revisar `update_locales.py` en la raíz antes de generar traducciones masivas — no asumir su uso).

**W9b — Respuestas enlatadas en inglés**: el system prompt le pide al modelo responder en el idioma del usuario (`composeSystemPrompt` `:~722`), pero las líneas que **la app** escribe están fijas en inglés y se persisten tal cual:
`LlmRepository.kt` `:296` (nota "Nothing was changed…"), `:483`, `:487`, `:495`, `:497`, `:508`, `:519`, `:526-527`, `:532`, `:548`, `:560`, `:570`, `:583`; `ChatViewModel.kt` `:204`, `:216`, `:237` ("Deleted/Updated/Added …").
Cambio: cada literal → string resource con placeholders; el repo usa su `@ApplicationContext context` (`context.getString(R.string.…, formattedAmount, …)`). **Los montos se pasan ya formateados con `CurrencyFormatter.formatCurrency(amount, code)`** (reglas 1-2); las líneas de `describe(tx)` conservan `tx.currency`. Convención de nombres: `chat_reply_*`.
Aviso conocido: con per-app locale en API < 33 el `Application` context puede no reflejar el idioma elegido (el prompt ya usa el mismo contexto, `:~617`); aceptar y documentar, no cambiar.

**Resultado esperado**: `Select-String 'message\?\.contains\(' ChatViewModel.kt` → 0; en un dispositivo en español, "Here's what I'll add" y "Nothing was changed" aparecen en español; ningún texto de error en inglés crudo.

**Tests** (`ChatErrorMappingTest.kt`, JVM; `UiText` es `data class`, comparable por igualdad): un caso por subclase + `unknown exception falls back to the given string` + `plain message is kept`.

---

### W10 — `closeConversation()` mientras se genera  `[P2 · razonado, no reproducido]`

**Evidencia**: `OptimizedSmsReaderWorker.kt:1113` y `HomeViewModel.kt:1153` llaman `LlmRepository.updateSystemPrompt()` → `llmService.closeConversation()` (`LlmRepository.kt:645`) **sin coordinación** con una generación en curso; `closeQuietly` (`LlmServiceImpl.kt`) traga el error pero la respuesta del usuario puede truncarse o fallar. `deleteAllMessages()` (`:436`) igual (el botón Clear está habilitado durante la generación: `ChatScreen.kt:375` no depende de `isLoading`).

**Cambio**: en `LlmRepository`, `@Volatile private var generating = false` + `@Volatile private var conversationStale = false`. `respond()` marca `generating = true/false` (en `finally`). `updateSystemPrompt()` escribe prompt (W2) y **si `generating`** solo marca `conversationStale = true`; `ensureConversation()` al inicio: `if (conversationStale) { llmService.closeConversation(); conversationStale = false }`. `deleteAllMessages()` durante generación: deshabilitar el botón Clear en UI mientras `uiState.isLoading` (`ChatScreen.kt:375` y `:350`).

**Resultado esperado**: lanzar un escaneo de SMS durante una respuesta no corta ni trunca la generación; el prompt nuevo se aplica en el siguiente mensaje.

**Test**: `updateSystemPrompt during generation defers closeConversation` (`coVerify(exactly = 0) { llmService.closeConversation() }` mientras el flow está suspendido).

---

### W11 — Lista del chat: keys y auto-scroll  `[P2]`

**Evidencia** (`ChatScreen.kt`)
- `items(messages) { … }` en `:215` y `:420` **sin `key`** ⇒ recomposición innecesaria y pérdida de estado al insertar.
- `:94-101`: `LaunchedEffect(messages.size, currentResponse)` lanza `scope.launch { animateScrollToItem(...) }` **por cada delta** de streaming (decenas/segundo, animaciones que se cancelan unas a otras) y calcula el índice como `messages.size` / `messages.size - 1`, ignorando el item `PendingActionCard` (`:425`), el `TypingIndicator` (`:454`) y el `ChatEmptyState` (`:412`) → con tarjeta pendiente o indicador de escritura el scroll no llega al último item.

**Cambio**
- `items(messages, key = { it.id }) { … }` en ambos lugares.
- Reemplazar el efecto: `LaunchedEffect(messages.size, pendingAction, uiState.isLoading) { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }` para cambios discretos, y para streaming `LaunchedEffect(Unit) { snapshotFlow { currentResponse.length }.sample(100).collect { if (it > 0) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1) } }` (`import kotlinx.coroutines.flow.sample`, `@OptIn(FlowPreview::class)`).

**Resultado esperado**: ≤10 llamadas de scroll/s durante streaming; el último item (respuesta, tarjeta o typing) siempre visible; sin parpadeo al llegar el mensaje persistido.

---

### W12 — Texto seleccionable, ancho adaptable, reloj del sistema  `[P3]`

Cambios en `ChatScreen.kt` (sin dependencias nuevas):
- Envolver el `Text(message.message)` (`:962`) en `SelectionContainer { … }` → copiar vía toolbar del sistema (también para el item en streaming).
- Ancho: sustituir `widthIn(max = 280.dp)` (`:953`, `:889`) por `val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.85f).dp.coerceIn(280.dp, 560.dp)`.
- Hora: `remember { android.text.format.DateFormat.getTimeFormat(context) }` en lugar de `SimpleDateFormat("h:mm a", …)` (`:945`) → respeta 12/24 h.

**Resultado esperado**: mantener pulsado un mensaje permite seleccionar/copiar; en tablet/landscape la burbuja usa hasta 560 dp; con el reloj del sistema en 24 h se ve `14:05`.

---

### W13 — Preview de imagen antes de enviar  `[P3 · opcional]`

`ChatViewModel`: `private val _attachedImage = MutableStateFlow<Uri?>(null)`; `attachImage(uri)`, `clearAttachment()`, `sendAttachedImage()` (→ `runGeneration { sendMessageWithImage(uri) }`). `ChatScreen`: galería y cámara llaman `attachImage`; mostrar un chip con miniatura sobre el `OutlinedTextField` con botones Enviar/Quitar.
**Antes de implementar, verificar si hay librería de imágenes** (`libs.coil*`/Glide en `gradle/libs.versions.toml`); si no, decodificar con `BitmapFactory` + `inSampleSize` en `produceState` (no añadir dependencia sin permiso). Sin cambios de esquema: la miniatura es efímera.
**Resultado esperado**: tras elegir foto no se envía automáticamente; el usuario ve la miniatura y decide. Documentar en `docs/dg-develop/01 §11` solo cuando exista (hoy el "preview chip" que describe **no existe**).

---

### W14 — Limpieza de código muerto  `[P3]`

Antes de borrar, `Select-String` de referencias en `app/src` (incl. `debug/` y `androidTest/`). Hoy sin llamadores externos:
- `LlmRepository.sendMessage` (`:67-93`) — camino no streaming **sin tools**; solo usa `llmService.sendMessage` (`:79`).
- `LlmService.sendMessage(String)` (`:32`) y su impl (`LlmServiceImpl.kt:125`).
- `LlmRepository.deleteOldMessages` (`:441`), `getMessageCount` (`:447`) y el bloque marcado `// DEAD CODE (2026-11-04)` (`:649-~668`).
- `createImageFile` (tras W4).
**Resultado esperado**: `./init.sh app` verde; −~50 líneas.

---

### W15 — Tests faltantes  `[P2]`

Cobertura actual del chat: `ChatPromptBudgetTest` (solo `composeSystemPrompt` + `TokenUtils`), `TransactionDraftTest`, `LlmRepositoryMaterializeMediaTest` (androidTest). **Sin tests** de: heurísticas de respuesta, mapeo de errores, catálogo, presupuesto por modelo, flujo del repositorio.

Para hacer testeable lo privado **sin Android**, extraer a `data/repository/ChatResponseHeuristics.kt`:
```kotlin
internal object ChatResponseHeuristics {
    val KNOWN_TOOLS: List<String>   // las 10 constantes de PennyWiseTools
    fun resolveNarratedTool(text: String): String?     // lógica de LlmRepository.kt:271-286
    fun claimsUnconfirmedChange(text: CharSequence): Boolean   // CLAIMS_A_CHANGE
}
```
`handleResponseAndEmit` delega en ella. Tests (`ChatResponseHeuristicsTest.kt`): `"monthSummary"` → `month_summary`; `"month_summary"` → igual; `"OK"`, `"Hola"`, `"Sí"` → `null`; `"I've recorded it"` y `"It's been added"` → `true`; `"he registrado el gasto"` → `true` (W6c); `"Spent 120 at Starbucks"` → `false`.
Añadir a `ChatPromptBudgetTest`: `chat budget per model` (Qwen 2560, Gemma 4/3n 6656) y renombrar el test `budget leaves headroom inside the model's 4096-token context` si se generaliza.

**Cobertura objetivo**: ≥80 % de las clases nuevas (`ChatResponseHeuristics`, `ChatError`/`toChatUiText`, `ChatModels.byId`) y cada rama de W0/W1/W2/W10 con al menos un test.

---

### W16 — Corrección de docs existentes  `[P2]`

Verificar contra código y corregir (no asumir):
| Archivo | Afirmación incorrecta | Real |
|---|---|---|
| `CLAUDE.md` (sección Token Budget) y `docs/dg-develop/01-ia-on-device-chat.md §4.2 y §13` | Gemma **6640** | `8192 − 1536 = ` **6656** (`TokenUtils.chatBudget`, `TokenUtils.kt:35`) |
| `CLAUDE.md` Multimodal | "Both require Gemma 3n"; Whisper "audio transcription" | Tras W3, audio solo con Gemma 3n; Whisper no es modelo de chat |
| `docs/dg-develop/01 §11` | Cámara guarda en `filesDir/chat_media/` + preview chip; audio WAV/AAC-3GP | Hoy: `getExternalFilesDir(PICTURES)`+`file://` (bug W4); no hay preview chip; el audio es OPUS/.m4a |
| `docs/dg-develop/01 §12` | "Thinking … UI Colapso 'Pensando...'" | Hasta W0 el panel nunca recibía datos |
| `docs/dg-develop/01 §9/§10` | System Prompt Additions y Custom HF funcionan | Hasta W2/W3 solo aplicaban tras "Clear chat" / no resolvían el modelo |
| `docs/dg-develop/01 §4` pasos 3-5 | Orden "insertar usuario → ensureConversation" | Nuevo orden y invariantes INV-1..3 (W1) |
Actualizar también números de línea citados (`:261`, `:285`, etc.) o, preferible, citar solo símbolos.

---

## 3.1 Hallazgos adicionales (verificación working tree 2026-11-04)

Hallazgos no cubiertos en el análisis v1/v2, confirmados leyendo el working tree actual:

| Hallazgo | Archivo:Línea (aprox) | Severidad | Work Item relacionado |
|---|---|---|---|
| **Budget usa constante Qwen aunque modelo sea Gemma** | `LlmRepository.kt:118` `requireFitsChatBudget(history, userMessage)` sin `spec` | 🔴 P1 | W0.5 (nuevo) |
| **`sendMessageWithImage/Audio` no validan `modelState == LOADING`** | `LlmRepository.kt:174,222` solo `requireModelNotDownloading()` | 🟡 P2 | — |
| **`updateSystemPrompt` no llama `refreshActiveModel()`** | `LlmRepository.kt:640` | 🟡 P2 | W3.5 (nuevo) |
| **`TransactionDraft.fromToolArgs` no valida `currency` del tool** | `TransactionDraft.kt` | 🟢 P3 | W6 |
| **`AudioRecorder.start` no aplica `MAX_DURATION_MS`** | `AudioRecorder.kt` | 🟡 P2 | W5 |
| **`pendingCameraUri` race condition** | `ChatScreen.kt:527` `var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }` — si usuario dispara cámara dos veces rápido, la segunda sobrescribe antes del callback de la primera | 🟡 P2 | W4 |
| **`THINKING_LINES` hardcoded en inglés** | `ChatScreen.kt:880` | 🟢 P3 | W12 |
| **`TokenUtils.CHAT_TOKEN_BUDGET = 2560` hardcodeado para Qwen** | `TokenUtils.kt:25` — `fitsChatBudget` sin spec usa esta constante; con spec usa `chatBudget(spec)` (Gemma = 6656) | 🔴 P1 | W0.5 |
| **`WHISPER_BASE.contextTokens = 0` → `chatBudget = -1536`** | `Constants.kt:253` + `TokenUtils.kt:35` → `fitsChatBudget` siempre `false` | 🔴 P1 | W3 |
| **Prompt de visión pide `currency` que `addTransaction` no recibe** | `LlmRepository.kt:~705-715` `composeSystemPrompt` | 🟢 P3 | W6d |
| **`SPEND_WORDS/INCOME_WORDS` regex `\b` falla con acentos** | `TransactionDraft.kt` — `\bgasté\b` no matchea porque `\b` no trata `é` como word boundary | 🔴 P1 | W6c |
| **`CLAIMS_A_CHANGE` faltan patrones ES y "It's been/It was"** | `LlmRepository.kt:734` | 🟡 P2 | W6c |
| **`NARRATED_TOOL` solo resuelve si nombre == una de 10 constantes** | `LlmRepository.kt:271-286` — "OK"/"Hola"/"Sí" → `null` (correcto) | ✅ OK | — |
| **`ModelRepository.isModelDownloaded` usa `min(95% esperado, 2 GB)`** | `ModelRepository.kt:128-129` — umbral de "descargado" | ✅ Documentado | — |
| **`ModelRepository.isLowMemoryForActiveModel` threshold 1.5 GB** | `ModelRepository.kt:167` `LOW_MEMORY_THRESHOLD_BYTES` | ✅ Documentado | — |

### 3.1.1 Ajustes al plan de work items

| Nuevo ID | Prio | Descripción | Depende de |
|---|---|---|---|
| **W0.5** | P0 | Fix `requireFitsChatBudget` para pasar `modelRepository.activeSpec()` y usar `chatBudget(spec)` en lugar de constante `CHAT_TOKEN_BUDGET` | W0 |
| **W3.5** | P1 | `ModelRepository.refreshActiveModel()` debe llamar `refreshModelState()` si el spec cambió (re-verificar integridad del modelo nuevo) | W3 |
| **W7** | P2 | Adelantar antes de W4/W5/W6 — refactor puro, no toca UI ni lógica de media/mic | W0, W1 |

### 3.1.2 Prioridad real ajustada

| P0 (Bloquean compilación/función core) | P1 (Correctitud de datos/UX rota) | P2 (Arquitectura/robustez) | P3 (Pulido) |
|---|---|---|---|
| W0, W0.5 | W1, W2, W3, W3.5, W4, W5, W6 | W7, W8, W9, W10, W11 | W12, W13, W14, W15, W16 |

---

## 4. Diferido o rechazado (con motivo)

| Ítem v1 | Decisión | Motivo |
|---|---|---|
| Resumen/summarization de conversación (v1 "Alto") | **Diferido** | Costoso y frágil con un modelo 1.5B; reforma el comportamiento "Chat memory is full". Alternativa futura de bajo riesgo: replay en ventana deslizante + ajustar el chequeo de presupuesto (requiere decisión de producto). |
| Renderizado markdown | **Diferido** | El prompt lo prohíbe (`:719`); sin evidencia de asteriscos en pruebas reales; requeriría dependencia no verificada. Si aparece, un `stripMarkdown()` solo de presentación (no persistido) es más barato. |
| PendingActionCard editable | **Diferido** | Reforma la garantía de confirmación (#170) y añade formulario/validación de monedas; necesita decisión de producto. |
| Mostrar tool + args en UI / status "Checking budget…" | **Rechazado** | Las lecturas se resuelven en ms *después* del stream; el status parpadearía. Las mutaciones ya muestran sus args en la tarjeta. |
| Cambiar `NARRATED_TOOL` a prefijo `tool:` | **Rechazado** | Rompe la recuperación existente y el supuesto falso positivo no existe. |
| "Añadir `sendMessageWithImage` a `LlmService`" | **Rechazado** | Ya existe (`LlmService.kt:39`). |
| Waveform de audio | **Rechazado** | Un contador de tiempo (W5) cubre la necesidad real. |
| Model badge en header, búsqueda/export de chat, `contentDescription` | **Rechazado** | Sin queja real; `DeveloperInfoCard` ya muestra el modelo en modo dev. |
| `ChatViewModel.activeModelSpec` posiblemente desactualizado <5 s | **Rechazado** | Ventana marginal (`WhileSubscribed(5000)`). |

---

## 5. Verificación global

### 5.1 Gate
`./init.sh app` verde tras **cada** `Wn`. Antes de empezar, ejecutarlo una vez para **confirmar el fallo de W0** (compilación) y registrar el error exacto en el mensaje del commit de W0.

### 5.2 Checklist en emulador (build F-Droid, §0.3)
1. Chat nuevo, "coffee 120 at Starbucks" → tarjeta Add; log `Conversation created with 0 history messages` (W1).
2. Matar app, reabrir, enviar → `history` par; sin turnos duplicados (W1).
3. Sin modelo descargado, enviar → error localizado, `chat_messages` sin filas nuevas (W1/W9).
4. Settings → editar *System Prompt Additions* → siguiente mensaje recrea la conversación, tokens del sistema cambian (W2).
5. Seleccionar modelo custom → permanece seleccionado; Whisper ausente del selector (W3).
6. Con Gemma 3n: cámara → captura y análisis sin crash; `cache/camera_temp` vacío (W4).
7. Micrófono: 1ª vez permiso sin grabación fantasma; tap corto no envía; arrastrar fuera cancela; contador visible (W5).
8. Foto/voz → burbuja del usuario con marcador; nota de la transacción confirmada = `Added from chat` (W1/W6).
9. "gasté 80 en el súper" → draft `EXPENSE`; "recibí mi nómina" → `INCOME` (W6).
10. Con thinking activo (Gemma 4, developer mode) el `ThinkingPanel` muestra tokens (W0).
11. Escaneo de SMS durante una respuesta no la trunca (W10).
12. Modo claro y oscuro; 12 h y 24 h; tablet/landscape (W12).
13. `git diff --stat -- app/schemas` vacío; `ChatMessage.kt` sin cambios (regla 3).

### 5.3 Criterios de cierre
- 17 ítems cerrados o explícitamente diferidos con nota en el PR.
- Cada commit toca solo rutas propias; ninguna ruta ajena modificada.
- Nada de push sin confirmación del usuario.
