# 25 — Plan de Adaptación a gemma-4-E2B

> Plan para explotar las capacidades de gemma-4-E2B (multimodal, 8K contexto, thinking) en el chat local de PennyWise. Parte del estado actual (doc 24): wiring multi-modelo ejecutado, probes text/tools/image/audio verificados en device, chat productivo aún limitado al paradigma text-only de Qwen 1.5B.
>
> **Estado**: **MAYORMENTE EJECUTADO** (2026-10-31 a 2026-11-02). Fases F0-F4, F7 completadas. F5 (eval rig) y F6 (RAM gate monitoring) pendientes. Fases ordenadas por dependencia y riesgo. Cada fase cierra con gate de build + validación en device.

---

## Contexto y motivación

| Limitación actual (Qwen 1.5B) | Capacidad gemma-4-E2B verificada en device |
|-------------------------------|---------------------------------------------|
| Text-only (`sendMessage(String)`) | Visión (`mode=image`: descripción precisa de screenshot, 33.8s CPU) y audio (`mode=audio`: transcripción exacta WAV TTS, 3.9s) |
| Contexto 4096 (budget 2560) | Contexto 8192 (budget 6640) |
| Sin thinking nativo | `ThinkingConfig` en la API (`ConversationConfig`, javap AAR 0.17.1) |
| Anti-parroting regex como parche | 10x más parami-capacidad; tool calls más fiables (con caveat issues #3851/#2202) |
| Header/budget UI hardcodeados a Qwen | 3 bugs staleness ya diagnosticados (doc 24 §8) |

**Riesgos abiertos** (issues LiteRT-LM): #3851 (audio no-verbal omitido, cronología de imágenes), #2202 (tool-calling inestable en Mali), #4014 (GPU bundles fallan init). Todo el plan asume backend **CPU**; GPU queda out-of-scope hasta que #4014 cierre.

---

## Fase 0 — Fixes del diagnóstico 2026-10-28 (previo a todo lo demás) ✅ **COMPLETADA 2026-10-31**

Los 3 defectos del doc 24 §8; sin estos, el usuario no puede confiar en la UI:

- **[F0-T1] Header dinámico** ✅: plural hardcodeado "Qwen 2.5 1.5B • %1$d mensajes" (`values/strings_rules.xml:251` + 13 locales) → "%2$s • %1$d mensajes"; caller pasa `modelRepository.activeSpec().displayName`.
- **[F0-T2] Budget dinámico en ChatStats** ✅: `ChatViewModel:122` usa `TokenUtils.chatBudget(modelRepository.activeSpec())` dinámico (6640 para Gemma).
- **[F0-T3] Respuesta texto-post-tool-call** ✅: verificado flujo "cuántos gastos llevo hoy" → `monthSummary` call → respuesta guardada correctamente con texto del tool.
- **Gate F0**: build ambos flavors + repro manual del prompt de gastos en device + header muestra Gemma + budget 6.6k ✅.

---

## Fase 1 — Thinking (aprovechar razonamiento nativo de Gemma) ✅ **COMPLETADA 2026-10-31**

- **[F1-T1]** ✅ `ThinkingConfig` del AAR (javap: `sendMessage(..., ThinkingConfig)` — params ya existen en `ConversationConfig`). Política: thinking ON para queries analíticas, OFF para registro simple.
- **[F1-T2]** ✅ `LlmService.sendMessageEvents` extiende `LlmEvent` con `LlmEvent.Thinking(delta)` — reasoning tokens viajan separados del output final.
- **[F1-T3]** ✅ UI: en developer mode (`settings_ai_info` toggle "Mostrar información técnica en el chat"), renderiza colapso "Pensando..." con tokens de razonamiento. Never por defecto — solo developer mode.
- **Riesgo**: thinking consume budget de contexto — validado que `ThinkingConfig.budgetTokens <= maxContextTokens / 2`.
- **Gate F1**: probe `mode=json` con thinking forzado + test unitario del budget + repro en device ✅.

---

## Fase 2 — Visión en el chat (foto → transacción) ✅ **COMPLETADA 2026-10-31**

El caso de uso de mayor valor: **fotografiar un ticket** y que el chat registre el gasto.

- **[F2-T1]** ✅ `LlmService.sendMessage` extendido a `sendMessageWithImage(Contents)` (sobrecarga existe). `ChatMessage` (Room entity) agregar columna `attachedImagePath: String?` (migración).
- **[F2-T2]** ✅ UI: botón attach (icono cámara) en `ChatScreen` → `ActivityResultContracts.TakePicture` → guardar en `context.filesDir/chat_media/` (scoped storage) → preview chip en el input.
- **[F2-T3]** ✅ `LlmServiceImpl.initialize`: cuando `activeSpec()` sea multimodal (Gemma 3n E2B), pasar `visionBackend = Backend.CPU()` (+ `audioBackend`). Agregar `isMultimodal: Boolean` a `ChatModelSpec`. La UI de attach se muestra solo si el spec activo es multimodal.
- **[F2-T4]** ✅ System prompt: instrucción de visión ("If the user attaches a receipt photo, read the merchant, amount and date, then call addTransaction with them"). Revisar contra issue #3851 (cronología) — una imagen por mensaje, no más.
- **[F2-T5]** ✅ UX de latencia: 33.8s por imagen en CPU → loading indicator específico ("Analizando imagen...") + compresión opcional (downscale a ~1024px antes de enviar, reduce tokens de imagen).
- **Gate F2**: foto de ticket real → draft de transacción con merchant/amount correctos → confirmación → Room. E2E en device ✅.

---

## Fase 3 — Audio input (voz → transacción) ✅ **COMPLETADA 2026-10-31**

- **[F3-T1]** ✅ UI: botón micrófono → `MediaRecorder` (AAC/3GP) → archivo temporal en sandbox → `Contents.of(Content.AudioFile(path), Content.Text(prompt))`.
- **[F3-T2]** ✅ Reuso del pipeline de tools: transcripción de "gasté 150 en la panadería" → `add_transaction` tool call — sin cambios en `handleToolCall` (el flujo es idéntico al de texto).
- **[F3-T3]** Consideración Pro: el dictado por voz es candidato natural a feature Pro — evaluar con `EntitlementGate` (doc 04) — **PENDIENTE DE DECISIÓN**.
- **Gate F3**: nota de voz real → tool call con amount/merchant → confirmación. Audio prefill 3.9s es viable para UX ✅.

---

## Fase 4 — Tools ampliadas (aprovechar 8K de contexto) ✅ **COMPLETADA 2026-10-31**

Con 6640 tokens de budget (vs 2560), se agregaron 5 tools que antes no cabían:

- **[F4-T1]** ✅ `budget_status`: "¿cuánto me queda de presupuesto?" — `resolveBudgetWindow` + `aggregateBudgetCategorySpending` ya existen (doc 11).
- **[F4-T2]** ✅ `category_breakdown`: top-N categorías del mes con montos (más útil que el spending_by_category de una sola categoría).
- **[F4-T3]** ✅ `recent_transactions`: listar las últimas N transacciones — **pull-on-demand** en vez de embed permanente. Mantener el anti-parroting (confirmación obligatoria).
- **[F4-T4]** ✅ `account_balance`: saldo de cada cuenta. `spending_forecast`: proyección lineal del gasto mensual.
- **[F4-T5]** ✅ Relax de cotas de listas en `composeSystemPrompt` (1200/400/500 chars): con 6640 hay margen para 2-3x. Ajustar `ChatPromptBudgetTest` en conjunto.
- **Gate F4**: suite de tests actualizada + eval manual de 10 prompts típicos con ambos modelos (Qwen debe seguir pasando — las tools nuevas no pueden romper el presupuesto de 2560) ✅.

---

## Fase 5 — Calidad de tool calling (mitigar issues #3851/#2202) ⏳ **PENDIENTE**

- **[F5-T1]** Eval rig: extender `LlmProbeReceiver` con `mode=eval` que corra N prompts de transacción (mínimo 30 propios en es-MX) y reporte tasa de tool-call correcto por modelo. Correr antes/después de cada fase para detectar regresiones.
- **[F5-T2]** Snake/camel args: `arg()` helper ya normaliza; agregar test con args que Gemma emita con tipos raros (`amount` como string "150.00" — validar Double-as-String en `TransactionDraft.fromToolArgs`).
- **[F5-T3]** Fallback de currency: el tool call del probe devolvió `currency=PHP` (default del tool probe) — el chat productivo no pasa currency como param. Con es-MX el usuario dirá "pesos": el system prompt ya usa la base currency del usuario; verificar que `TransactionDraft` siempre resuelva currency desde `userPreferencesRepository.baseCurrency`, nunca desde el arg del modelo.

---

## Fase 6 — Cierre de riesgo y release ⏳ **PENDIENTE**

- **[F6-T1]** RAM gate en install/runtime: monitor de `ActivityManager.MemoryInfo` — si el device entra en presión de memoria con Gemma cargado (RSS 2.63 GB), ofrecer fallback a Qwen con diálogo (no crash silencioso del lmkd).
- **[F6-T2]** Speculative decoding (opcional): litertlm 0.17.1 lo soporta (`--speculative-decoding` en CLI); verificar API Kotlin — si existe, ~2x decode speed en CPU.
- **[F6-T3]** Release notes + docs: doc 01 (ia-on-device-chat) actualizado con el paradigma multimodal; CHANGELOG por fase.
- **Gate F6**: F-Droid build `assembleFdroidRelease` + AAB `bundlePlayRelease` (doc 17 pipeline).

---

## Fase 7 — Features de configuración avanzada (post-F2/F3) ✅ **COMPLETADA 2026-11-02**

Agregada 2026-10-28 tras double-check del usuario. Ambas features fueron evaluadas contra el código vigente; los diseños reflejan las restricciones encontradas.

- **[F7-T1] Instrucciones adicionales del system prompt (F8-T1)** ✅ — append-semántico, NO reemplazo. Pref `custom_instructions` (String, máx ~500 chars) en DataStore → `buildSystemPrompt` la appendea tras las listas → TextField en sección AI de Settings con costo en tokens visible → test en `ChatPromptBudgetTest` (el append debe caber en el budget de Qwen 2560).
- **[F7-T2] Custom model vía HuggingFace (F8-T2)** ✅ — sección "Experimental" con validación pre-download. Repo ID → validar `.litertlm` via HF tree API → validar tamaño/bundle pre-download (HEAD request + SHA-256) → spec runtime `isCustom=true` sin RAM gate con disclaimer → gate por developer mode o Pro. API key solo para repos privados (litert-community es público).
- **Gate F7**: F7-T1 con suite de budget verde en ambos modelos; F7-T2 con un repo público litert-community de prueba end-to-end (download → load → probe mode=text) ✅.

---

## Orden de ejecución ejecutado

```
F0 (bugs UI, inmediato) ✅ 2026-10-31
  → F1 (thinking — developer mode) ✅ 2026-10-31
    → F2 (visión — la feature de mayor valor, más UI) ✅ 2026-10-31
      → F3 (audio — reusa infraestructura de F2) ✅ 2026-10-31
        → F4 (tools ampliadas — solo sistema, sin UI nueva) ✅ 2026-10-31
          → F7 (configuración avanzada — F8) ✅ 2026-11-02
            → F5 (eval rig — pendiente)
              → F6 (cierre de riesgo — pendiente)
```

**Estimación de esfuerzo relativo**: F0 (S) → F1 (M) → F2 (L) → F3 (M) → F4 (M) → F7 (M) → F5 (S) → F6 (S).

---

## Dependencias externas a vigilar

| Item | Dónde | Impacto si cambia |
|------|-------|-------------------|
| litertlm 0.18.1 (última en Google Maven, 2026-10-20) | bump pendiente de evaluar | Puede traer fixes de #3851/#2202 — re-evaluar tras cada release |
| Issue #4014 (GPU init) | LiteRT-LM GitHub | Desbloquearía 3x-10x velocidad multimodal |
| Dagger Kotlin 2.4 metadata (#5177) | ya workarounded con ksp force | Si Dagger 2.61 lo soporta nativo, remover el force |

---

*Doc 25 de la serie dg-develop — 2026-11-03. Basado en el doc 24 y las validaciones en device del wiring multi-modelo (SM-S908E, fdroidDebug, litertlm 0.17.1). Los IDs [F#-T#] siguen la convención de la serie para ejecución con `executing-plans`. Fases F0-F4, F7 completadas; F5-F6 pendientes.*