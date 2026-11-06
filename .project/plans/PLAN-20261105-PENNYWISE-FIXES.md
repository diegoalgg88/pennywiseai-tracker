# 📋 PLAN: Fixes and Features for PennyWise AI Chat & Parsers
> **Plan ID:** `PLAN-20261105-PENNYWISE-FIXES`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `BugFix` + `Feature`
> **Estado:** `Creado`

## 🎯 Resumen y Arquitectura

Este plan aborda 4 tareas críticas derivadas de la investigación de código y capturas de pantalla:

1. **Tarea 1 (CRÍTICO)**: Bug de envío de imágenes — `visionBackend` en `EngineConfig` usa `backend` (GPU) en lugar de `Backend.CPU()` fijo, causando `INVALID_ARGUMENT: Provided more images than expected` al procesar `Content.ImageFile` con Gemma 3n E2B.

2. **Tarea 2**: Parsers México — 3 SMS de TELCEL no reconocidos (screen05.png). Crear `TelcelMexicoParser` extendiendo `BaseMexicoBankParser`.

3. **Tarea 3**: Flujo conversacional desde imagen — El system prompt no instruye a la IA a preguntar campos faltantes (categoría, cuenta, tipo, fecha) antes de emitir `add_transaction`.

4. **Tarea 4**: Ampliar toolset — 6 nuevas tools (3 lectura + 3 mutación) siguiendo patrón `PennyWiseTools.kt` + `handleToolCallInner` con anti-hallucination (`CLAIMS_A_CHANGE`/`NARRATED_TOOL`) y `arg()` camel+snake.

**Decisiones arquitectónicas:**
- Fix Tarea 1 es de 1 línea en `LlmServiceImpl.kt:302` — bajo riesgo, alto impacto.
- Tarea 2 sigue patrón existente (`BaseMexicoBankParser`, `BankParserFactory`).
- Tarea 3 es cambio de prompt en `LlmRepository.buildSystemPrompt` — no toca lógica.
- Tarea 4 requiere confirmación previa de tools; solo implemento tras aprobación.

## 🔍 Alcance (Scope)

### DENTRO (In):
- `app/src/main/java/com/pennywiseai/tracker/data/service/LlmServiceImpl.kt` (línea 302)
- `parser-core/src/main/kotlin/com/pennywiseai/parser/core/bank/TelcelMexicoParser.kt` (nuevo)
- `parser-core/src/main/kotlin/com/pennywiseai/parser/core/bank/BankParserFactory.kt` (registro)
- `parser-core/src/test/kotlin/com/pennywiseai/parser/core/bank/TelcelMexicoParserTest.kt` (nuevo)
- `app/src/main/java/com/pennywiseai/tracker/data/repository/LlmRepository.kt` (system prompt)
- `app/src/main/java/com/pennywiseai/tracker/data/service/PennyWiseTools.kt` (nuevas tools)
- `app/src/main/java/com/pennywiseai/tracker/data/repository/LlmRepository.kt` (handleToolCallInner)

### FUERA (Out):
- Cambios en `ChatScreen.kt`, `ChatViewModel.kt` (UI/estado adjunto)
- Nuevos modelos en `ChatModelSpec` / `ModelRepository`
- Migraciones de BD (no tocan entidades)
- Whisper / Custom HF / Thinking config

## 🗺️ Fases de Implementación

### Fase 1: Fix Crítico — Vision Backend (Tarea 1)
- [ ] **[F1-T1]** Fix `visionBackend` en `EngineConfig`
  - **Archivos/Módulos:** `app/src/main/java/com/pennywiseai/tracker/data/service/LlmServiceImpl.kt:302`
  - **Acción:** Cambiar `visionBackend = if (multimodal) backend else null` → `visionBackend = if (multimodal) Backend.CPU() else null`. El backend de visión DEBE ser CPU para Gemma 3n; GPU no tiene delegado de visión.
  - **Verificación:** `./init.sh app` compila; prueba on-device con Gemma 3n + imagen (skill `emulator-verify`).

### Fase 2: Parsers México — TELCEL (Tarea 2)
- [ ] **[F2-T1]** Crear `TelcelMexicoParser` extendiendo `BaseMexicoBankParser`
  - **Archivos/Módulos:** `parser-core/src/main/kotlin/com/pennywiseai/parser/core/bank/TelcelMexicoParser.kt`
  - **Acción:** Implementar `getBankName()` = "Telcel México", `canHandle(sender)` para sender "TELCEL". Sobrescribir `extractAmount()`/`extractMerchant()` para formatos: "saldo vencido por $XXX.XX", "cargo de factura... por $XXX.XX", "paga $XXX.XX antes del DD/MM/YY". Moneda: MXN. Tipo: `BALANCE_UPDATE` para recordatorios, `EXPENSE` para cargos fallidos.
  - **Verificación:** `./init.sh parser` pasa tests nuevos.

- [ ] **[F2-T2]** Registrar parser en `BankParserFactory.parsers` **arriba** de matchers genéricos
  - **Archivos/Módulos:** `parser-core/src/main/kotlin/com/pennywiseai/parser/core/bank/BankParserFactory.kt`
  - **Acción:** Insertar `TelcelMexicoParser()` antes de `BaseMexicoBankParser` o matchers amplios que capturen "TELCEL".
  - **Verificación:** `SupportedBanksDocTest` pasa; `./scripts/update-supported-banks.sh` regenera JSON/README.

- [ ] **[F2-T3]** Tests con `ParserTestUtils` (`@TestFactory` + `DynamicTest`)
  - **Archivos/Módulos:** `parser-core/src/test/kotlin/com/pennywiseai/parser/core/bank/TelcelMexicoParserTest.kt`
  - **Acción:** 3 `ParserTestCase` (uno por SMS de screen05.png) + 1 `SimpleTestCase` para factory. Datos sintéticos, cero PII. Usar `ExpectedTransaction` con amount, currency="MXN", type, merchant="Telcel".
  - **Verificación:** `./init.sh parser` — 2092+ tests pasan.

### Fase 3: Flujo Conversacional desde Imagen (Tarea 3)
- [ ] **[F3-T1]** Actualizar system prompt en `LlmRepository.buildSystemPrompt`
  - **Archivos/Módulos:** `app/src/main/java/com/pennywiseai/tracker/data/repository/LlmRepository.kt` (método `buildSystemPrompt`)
  - **Acción:** Agregar directiva: "Si la imagen/documento no declara categoría, cuenta, tipo (gasto/ingreso) o fecha, DEBES preguntar al usuario los campos faltantes ANTES de llamar a add_transaction. Propón defaults razonables de Room (cuentas, categorías) y pide confirmación explícita. Nunca asumas moneda — usa CurrencyFormatter.formatCurrency(amount, code)."
  - **Verificación:** `./init.sh app`; prueba on-device: enviar recibo sin categoría → IA pregunta "¿En qué categoría va este gasto? Tienes: Alimentación, Transporte, Ocio...".

### Fase 4: Ampliar Toolset (Tarea 4) — **Pendiente confirmación**
- [ ] **[F4-T1]** Propuesta de 6 nuevas tools para revisión/confirmación
  - **Archivos/Módulos:** Documento de propuesta (este plan)
  - **Acción:** Presentar tabla de tools con params y justificación. Esperar confirmación explícita antes de implementar.
  - **Verificación:** Usuario aprueba/rechaza cada tool.

- [ ] **[F4-T2]** Implementar tools aprobadas en `PennyWiseTools.kt` + `handleToolCallInner`
  - **Archivos/Módulos:** `app/src/main/java/com/pennywiseai/tracker/data/service/PennyWiseTools.kt`, `app/src/main/java/com/pennywiseai/tracker/data/repository/LlmRepository.kt`
  - **Acción:** Para cada tool aprobada: (1) `@Tool` + `@ToolParam` en `PennyWiseTools` (cuerpo `""`); (2) case en `handleToolCallInner` resolviendo desde Room (`AiContextRepository` o repos específicos); (3) mutaciones → `PendingChatAction` + confirmación usuario; (4) anti-hallucination regex aplicable.
  - **Verificación:** `./init.sh app`; prueba on-device por tool.

- [ ] **[F4-T3]** Tests unitarios de dispatch de tools
  - **Archivos/Módulos:** `app/src/test/.../LlmRepositoryToolDispatchTest.kt` (nuevo)
  - **Acción:** Tests que simulan `LlmEvent.ToolCall` y verifican respuesta correcta / `PendingChatAction` creado.
  - **Verificación:** `./init.sh app` tests pasan.

## 🧪 Estrategia de Verificación Global

- **Linter & Formateador:** `./gradlew :app:lintStandardDebug --no-daemon` + `./gradlew :parser-core:lint --no-daemon`
- **Suite de Pruebas:** `./init.sh` (gate CI completo: parser + app compile + app unit tests)
- **Prueba Manual (on-device):**
  1. Abrir chat → seleccionar Gemma 3n E2B → adjuntar recibo → enviar → verificar procesamiento sin error `INVALID_ARGUMENT`
  2. Enviar SMS TELCEL de prueba → verificar parsing en "SMS no reconocidos" → reportar → verificar parser creado
  3. Enviar imagen sin categoría → verificar IA pregunta categoría/cuenta/tipo/fecha con defaults
  4. Probar cada tool nueva aprobada end-to-end

## 🔄 Plan de Rollback y Contingencias

- **Estrategia de Reversión:** Cada fase es un commit atómico. `git revert <commit>` revierte sin afectar otras fases. `LlmServiceImpl.kt` fix es 1 línea — revert trivial.
- **Garantías de Datos:** No hay migraciones de BD ni cambios de esquema. Backup `BackupSchemaGuardTest` no afectado.
- **Contingencia Tarea 1:** Si fix CPU causa regresión en texto (improbable), fallback: `visionBackend = if (multimodal) Backend.CPU() else null` + log warning; investigar delegado GPU visión en LiteRT 0.17.1.

## ❓ Preguntas Abiertas y Riesgos

| Riesgo | Mitigación |
|--------|------------|
| **T1:** `Backend.CPU()` para visión sea más lento que GPU | Aceptable: Gemma 3n visión en CPU es ~1-2s por imagen; UX tolerable. GPU no funciona (sin delegado visión). |
| **T2:** Sender "TELCEL" colisiona con otros parsers | Registrar `TelcelMexicoParser()` **primero** en `BankParserFactory.parsers`; `canHandle` exacto "TELCEL". |
| **T3:** System prompt muy largo → excede token budget | Directiva ~150 tokens; budget Qwen 2560 / Gemma 6640 — margen amplio. |
| **T4:** Scope creep si se aprueban muchas tools | Regla: máx 6 tools en este plan; nuevas tools → nuevo plan. |
| **T4:** Mutaciones sin `PendingChatAction` → escritura directa Room | Patrones `PennyWiseTools` + `handleToolCallInner` blindan esto; revisión de código obligatoria. |

---

**Próximo paso:** Confirmar tools de Tarea 4 (F4-T1) y autorizar inicio de Fase 1 (F1-T1).