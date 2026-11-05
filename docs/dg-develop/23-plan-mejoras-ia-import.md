# 📋 PLAN: Mejoras de IA para import de documentos — OCR/vision para recibos y estados de cuenta
> **Plan ID:** `PLAN-20261025-IA-IMPORT-DOCS`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `Feature`
> **Estado:** `Creado`
> **Restricción activa:** NUNCA hacer commit ni push durante la implementación (instrucción del usuario, 2026-10-24, vigente).

## 🎯 Resumen y Arquitectura
Añadir a PennyWise la capacidad de **registrar transacciones desde una foto** (recibo/ticket) y de **importar estados de cuenta desde imagen**, cerrando el gap documentado en el deep-dive `22-import-statements-x-ia.md` (la cadena foto→texto no existe; el chat es text-only por interfaz `LlmService.kt:12-30` y por modelo). La arquitectura es **híbrida de dos motores**, siguiendo la intuición del usuario y el patrón verificado en Agora: (1) **OCR determinista** con ML Kit Text Recognition bundled (flavor standard; ~20 MB, offline, es-MX soportado) como camino rápido y gratis, con **Tesseract** como variante FOSS para el flavor fdroid; (2) **transcripción por modelo vision opt-in** — **Gemma 3n E2B int4** (del allowlist de Google AI Edge Gallery, `llmSupportImage: true`, 3.14 GB) vía **el mismo SDK LiteRT-LM que la app ya usa** — usando `Content.ImageFile(path)` (API verificada en `Agora\...\LiteRtRequestMapper.kt:214` con el namespace `com.google.ai.edge.litertlm` idéntico al de PennyWise) y la detección de capacidades `Capabilities(modelPath).inputModalities()` (patrón `Kai\...\LiteRTInferenceEngine.kt`). **El modelo vision es un slot separado del chat** — no toca el presupuesto de 4096 tokens del chat (#959) ni sus tools. Todo resultado pasa por **preview/confirmación humana obligatoria** (garantía #170 + patrón universal de los 6 proyectos OSS de finanzas investigados: extracción → validación determinista → review humano → save; ninguno confía el monto al LLM solo). El parseo de campos es determinista (`ReceiptParser` con heurísticas) sobre el texto extraído, reutilizando `SharedCategoryMapping` para categorías y el flujo `StatementImportProcessor` preview→commit para estados.

## 🔍 Alcance (Scope)
- **DENTRO (In):**
  - `parser-core` NO se toca. App: nuevo paquete `data/receiptscan/` (OCR extractor + ReceiptParser), `domain/service/VisionTranscriptionService.kt` (interfaz) + `data/service/VisionTranscriptionServiceImpl.kt` (LiteRT), extensión de `data/repository/ModelRepository.kt` (segundo slot de modelo con el mismo patrón SHA-256), UI (entry point en Add screen + review pre-filled + settings de vision model), `FreeTierLimits` (cuota nueva), DI (`LlmModule` o módulo propio).
  - Verificación de viabilidad del SDK (spike F1) antes de comprometer el camino vision.
  - Fixtures de texto OCR para tests (recibos es-MX sintéticos).
- **FUERA (Out):**
  - Cambiar el modelo del **chat** ni sus tools (Qwen 2.5 sigue; su presupuesto intacto).
  - Multimodal EN el chat (pegar foto en la conversación) — descartado en doc 22 §5.2.
  - OCR cloud de cualquier tipo (rompe el principio on-device).
  - Parsers PDF nativos nuevos para AMEX/BBVA MX (feature separada, doc 14 §7).
  - Cambios en `BankParserFactory` o el pipeline SMS.
  - Modificar `LlmService` existente — la transcripción vision vive en SU propia interfaz/servicio.

## 🗺️ Fases de Implementación

### Fase 1: Spikes de verificación (cero compromiso de arquitectura hasta validar)
- [ ] **[F1-T1]** Spike: LiteRT-LM 0.16.1 ¿expone `Content.ImageFile`?
  - **Archivos/Módulos:** sin cambios — spike en scratch (test temporal o app de prueba en `D:\TEMP`)
  - **Acción:** Inspeccionar el AAR/JAR de `com.google.ai.edge.litertlm:litertlm-android:0.16.1` (caché Gradle) buscando la clase `Content` con `ImageFile`. Si NO existe: identificar la versión mínima que la trae (Agora la usa — revisar su `libs.versions.toml` y su `LiteRtRequestMapper` compila contra esa versión) y evaluar el bump en `gradle\libs.versions.toml:44`. Documentar la brecha (`litertlm >= 0.14` JVM-default nota en `libs.versions.toml:37` indica que bumps ya movieron el API — verificar compatibilidad del contrato actual de `LlmServiceImpl`).
  - **Verificación:** Respuesta binaria documentada + si aplica bump, `:app:compileStandardDebugKotlin` verde con el chat funcionando (tests de `LlmRepository` intactos).
- [ ] **[F1-T2]** Spike: bundle .litertlm de Gemma 3n E2B con vision — ¿existe y qué declara?
  - **Archivos/Módulos:** sin cambios — investigación de artefacto
  - **Acción:** El allowlist del Gallery usa `.task` (MediaPipe LLM Inference) — PennyWise usa `.litertlm` (bundles LiteRT-LM, pattern Kai). Localizar el bundle Gemma 3n E2B int4 en formato `.litertlm` con metadata multimodal (candidatos: `litert-community` en HuggingFace, artifacts de Kaggle/AI Edge). Verificar con el pattern `Capabilities(path).inputModalities()` (o leyendo el metadata del archivo descargado) que declara `vision: true`. Confirmar licencia (Gemma Terms) y URL estable + SHA-256 para el patrón de `Constants.kt`. Si solo existe `.task`: decisión — (a) añadir MediaPipe LLM Inference como segundo runtime (dependencia nueva), o (b) mantener solo OCR determinista en v1 y vision como fase futura. Documentar en el ADR de F1-T3.
  - **Verificación:** Artifact localizado + hash + licencia documentados, o decisión (b) tomada con evidencia.
- [ ] **[F1-T3]** ADR de arquitectura + política F-Droid de OCR
  - **Archivos/Módulos:** `docs\adr\0003-receipt-scanning-ia.md` (nuevo), `docs\dg-develop\23-plan-mejoras-ia-import.md` (este plan, referenciado)
  - **Acción:** Documentar: arquitectura híbrida elegida (OCR determinista primary + vision opt-in), las influencias verificadas (Agora `TranscriptionManager.kt:271` para streaming/prompt/fail-closed; Kai `Capabilities` para detección; 6 proyectos OSS para el patrón review-obligatorio; grocery-receipts para needs-review), el análisis F-Droid (ML Kit = binario propietario standalone → **solo flavor standard**; fdroid: Tesseract o vision model), y la decisión de cada alternativa con su trade-off.
  - **Verificación:** ADR completo; decisiones binarias cerradas (no "evaluar" — decidido).
- [ ] **[F1-T4]** Verificar `.gitignore` del repo cubre `.project/`
  - **Archivos/Módulos:** `.gitignore` (solo si falta la entrada)
  - **Acción:** El estado de ejecución de planes vive en `.project/` y NO debe commitearse (contrato del skill). Verificar que exista la entrada; añadirla si falta.
  - **Verificación:** `git check-ignore .project/.execution_state.json` retorna el path.

### Fase 2: Capa de extracción (OCR + parseo determinista)
- [ ] **[F2-T1]** Interfaz `ReceiptOcrExtractor` + implementación ML Kit (standard)
  - **Archivos/Módulos:** `app\src\main\java\com\pennywiseai\tracker\data\receiptscan\ReceiptOcrExtractor.kt` (interfaz), `...\receiptscan\MlKitOcrExtractor.kt`, `app\build.gradle.kts` (dependencia `com.google.mlkit:text-recognition:16.0.1` **solo en el source set/flavor standard** — gating como Play Billing), DI binding condicional.
  - **Acción:** Interfaz `suspend fun extractText(imageUri: Uri): OcrResult` (texto + confidence + boundingBoxes para futuro). ML Kit bundled (sin Play Services requirement): `TextRecognition.getClient(TextRecognizerOptions...)` con latencia <1s. Comentarios en es-MX.
  - **Verificación:** Test instrumental o scratch en device `R5CT422CGKH` con foto de recibo real → texto legible.
- [ ] **[F2-T2]** `ReceiptParser` determinista (texto OCR → campos)
  - **Archivos/Módulos:** `...\data\receiptscan\ReceiptParser.kt` + `app\src\test\java\...\receiptscan\ReceiptParserTest.kt`
  - **Acción:** Heurísticas sobre el texto OCR (patrones de los proyectos OSS investigados): **total** = candidato monetario más frecuente/grande con cross-check de IVA 16% MX cuando los items suman cerca (pattern grocery-receipts: items vs total → si no cuadra ±tolerancia, flag `needsReview`, JAMÁS adivinar); **merchant** = primeras 1-3 líneas de header descartando direcciones/teléfonos/RFC (pattern ocr-expense-tracker: "intelligent heuristic scans top header lines"); **fecha** = regex dd/mm/yyyy ± hoy; **categoría** = `SharedCategoryMapping` existente (cero lógica nueva — doc 15). Moneda **MXN-tagged siempre** (hard constraint 1: `Money` desde el nacimiento del dato). Salida: `ScannedReceipt(fields, confidence, needsReview flags, rawText)`.
  - **Verificación:** Suite unit con ≥8 fixtures de texto OCR es-MX (OXXO, farmacia, restaurante, ticket con IVA desglosado, ticket roto/needs-review, ticket sin fecha, ticket USD de tienda internacional) — todo sintético anónimo, cero PII.
- [ ] **[F2-T3]** Variante F-Droid del extractor
  - **Archivos/Módulos:** `app\src\fdroid\java\...\receiptscan\TesseractOcrExtractor.kt` (si se decide Tesseract en F1-T3) o gating que deriva a vision-model (si F1-T3 decide eso)
  - **Acción:** Implementar según la decisión del ADR. Tesseract: `tesseract-ocr` via JNI o `tesseract4android` (FOSS) + tessdata spa; reconocer que es más lento (~2-5s) — la UI debe reflejarlo.
  - **Verificación:** `assembleFdroidDebug` compila con el extractor del flavor; test del parser (F2-T2) es agnóstico al extractor.

### Fase 3: Transcripción por modelo vision (opt-in, slot separado)
- [ ] **[F3-T1]** `VisionTranscriptionService` + slot de modelo en `ModelRepository`
  - **Archivos/Módulos:** `domain\service\VisionTranscriptionService.kt` (interfaz), `data\service\VisionTranscriptionServiceImpl.kt`, `data\repository\ModelRepository.kt` (extendido), `core\Constants.kt` (URL/SHA-256 del bundle vision del F1-T2)
  - **Acción:** Motor LiteRT dedicado (`Engine` + `Conversation` propia, mutex propio — pattern `LlmServiceImpl.kt`) que recibe la imagen como `Content.ImageFile(path)` + prompt de transcripción (default en es: "Transcribe todo el texto de este recibo/estado de cuenta, preservando montos exactos, sin resumir"). Slot de modelo SEPARADO del chat: `ModelRepository` gana `visionModelState: StateFlow<VisionModelState>` reusando el patrón exacto de descarga/verificación SHA-256/streaming de progreso (doc 01 §7) — el usuario decide descargar los 3.14 GB. Detección de capacidades del bundle al cargar (pattern Kai) — fail-closed si el bundle no declara vision.
  - **Verificación:** Con bundle descargado en device: foto → texto transcrito con montos correctos (probar con recibo real); sin bundle → estado NOT_DOWNLOADED, sin crash.
- [ ] **[F3-T2]** Orquestador de escaneo con fallback
  - **Archivos/Módulos:** `...\data\receiptscan\ReceiptScanOrchestrator.kt`
  - **Acción:** Política de extracción por settings (pattern Agora `imageTranscriptionModel`): (a) `OCR_FAST` (default): ML Kit/Tesseract → ReceiptParser; (b) `VISION_QUALITY`: modelo vision → ReceiptParser sobre el texto transcrito; (c) `OCR_THEN_VISION_ON_LOW_CONFIDENCE`: OCR primero y si `confidence < umbral` re-intenta con vision. El texto resultante SIEMPRE pasa por el mismo `ReceiptParser` determinista — el modelo nunca entrega campos finales, solo texto (anti-alucinación de montos: pattern AlexITC "Backend validation computes deterministic warnings instead of trusting the LLM"). Streaming de progreso al UI (pattern `TranscriptionManager`: announce inmediato + línea terminal, cancelable).
  - **Verificación:** Test de orquestador con extractores fake (fast/vision/low-confidence path) — sin modelo real en unit tests.

### Fase 4: Flujos de UI y datos
- [ ] **[F4-T1]** Entry point "Escanear recibo" + review pre-filled
  - **Archivos/Módulos:** `presentation\add\AddScreen.kt` + `AddViewModel.kt` (nuevo estado `ScannedReceipt?` pre-poblando el formulario), reuso de `ActivityResultContracts.TakePicture()` existente (`TransactionTabContent.kt:738`) y `ReceiptManager.saveReceipt` (la foto queda adjunta — hoy ya funciona, doc 22 §4)
  - **Acción:** Botón junto al attach de recibo actual: "Escanear y extraer". Foto → orquestador → **formulario Add PRE-POBLADO** con amount/merchant/date/categoría sugerida y la foto ya adjunta — el usuario revisa y salva (garantía #170: el flujo termina en el MISMO tap de Save que existe hoy; cero escritura sin confirmación). Si `needsReview` (items no cuadran): banner en el formulario "El total no coincide con los items — verifica el monto". Si el usuario cancela: nada se guarda, la foto no se persiste.
  - **Verificación:** E2E en device: foto de recibo real → campos correctos → Save → transacción con MXN + receiptPath + categoría; cancelar → cero filas nuevas.
- [ ] **[F4-T2]** Import de estado de cuenta desde foto (batch)
  - **Archivos/Módulos:** `presentation\statement\` (nueva fuente de entrada de imagen que alimenta el pipeline existente)
  - **Acción:** Foto de statement → OCR/vision → **texto plano → `StatementImportProcessor.plan()` existente** (doc 14 §3: el processor ya clasifica new/duplicate y la UI ya tiene preview con checkboxes → commit en transacción Room con re-matching). NO se construye un segundo pipeline batch — el foto-import es solo una nueva fuente de texto para el que ya existe. Balance check del closing balance ya implementado reusado.
  - **Verificación:** E2E: foto de statement real (o print) → preview con N filas → commit → dedup contra transacciones push existentes correcto.
- [ ] **[F4-T3]** Settings de visión + gestión del modelo
  - **Archivos/Módulos:** `ui\screens\settings\` (nueva sección "Receipt scanning" / extensión de la sección del modelo IA), `FreeTierLimits.kt`
  - **Acción:** Política de extracción (radio OCR_FAST/VISION/HYBRID según F3-T2), gestor del modelo vision (descarga 3.14 GB con aviso de tamaño + RAM mínima ~6 GB + borrar), prompt de transcripción editable (pattern Agora `imageTranscriptionPrompt`), y **cuota free**: nueva constante `MAX_RECEIPT_SCANS_PER_MONTH` en `FreeTierLimits.kt` (archivo diseñado para auditar cuotas, doc 04 §6) — recomendación: 5 escaneos/mes free, ilimitado Pro (F-Droid: todo libre por gateway). El entry point de escaneo respeta el gate con upgrade prompt, igual que statement imports.
  - **Verificación:** `./init.sh app` verde; `FreeTierLimits` documentado en el propio archivo; UI de settings visible en ambos flavors (ML Kit solo funcional en standard).

### Fase 5: Tests, gate y E2E
- [ ] **[F5-T1]** Suite unit de la capa nueva
  - **Archivos/Módulos:** `app\src\test\java\...\receiptscan\` (ReceiptParserTest de F2-T2 + OrchestratorTest de F3-T2 + fixtures)
  - **Acción:** Fixtures de texto OCR es-MX sintéticos (anónimos); tests del parser por cada caso de edge; tests del orquestador con fakes. Regla del repo: montos siempre comparados como BigDecimal con moneda.
  - **Verificación:** `gradlew.bat :app:testStandardDebugUnitTest --tests "com.pennywiseai.tracker.data.receiptscan.*"` verde.
- [ ] **[F5-T2]** Gate completo + regresión del chat
  - **Archivos/Módulos:** n/a — verificación
  - **Acción:** `./init.sh app` (compile + unit tests). Regresión específica del chat: el modelo vision es un engine SEPARADO — verificar que `LlmServiceImpl` mutex/lifecycle intactos, presupuesto de tokens del chat intacto (#959), y que descargar el modelo vision NO rompe el estado del modelo del chat (`ModelRepository` con dos slots independientes). Verificar memoria: chat + vision engine simultáneos en un device de 8 GB RAM (SM-S908E tiene 12 GB — probar el peor caso razonable).
  - **Verificación:** `./init.sh app` verde + prueba manual de chat en device con modelo vision descargado.
- [ ] **[F5-T3]** E2E en device `R5CT422CGKH`
  - **Archivos/Módulos:** n/a — verificación manual
  - **Acción:** Con APK debug instalado: (1) escanear 3 recibos reales distintos (supermercado, restaurante, farmacia) → verificar extracción + confirmación + adjunto de foto; (2) un recibo intencionalmente borroso → needsReview activado, no crash; (3) import de statement por foto → preview→commit; (4) sin modelo vision descargado → OCR_FAST funciona igual; (5) chat sigue funcionando con el modelo vision descargado.
  - **Verificación:** Checklist E2E documentado con resultados; capturas como evidencia.

## 🧪 Estrategia de Verificación Global
- **Gate del repo:** `./init.sh app` (compile + unit). Sin `./gradlew build` (no es del gate).
- **Suites dirigidas:** `:app:testStandardDebugUnitTest --tests "...receiptscan.*"` y `--tests "...ModelRepository*"`.
- **Manual en device:** `adb install` del debug en `R5CT422CGKH` + checklist de F5-T3 con recibos reales del usuario (el device es el fixture — misma lección que el plan de bancos MX: samples reales > sintéticos).
- **Regresión dura:** chat (#959, mutex 2.21.0) y pipeline SMS intactos — solo lectura de sus archivos en este plan, cero modificación.

## 🔄 Plan de Rollback y Contingencias
- **Estrategia de reversión:** Todo el feature es paquetes/archivos nuevos + extensiones aditivas (`ModelRepository` gana un slot, no muta el existente). `git revert` del commit (cuando el USUARIO decida commitear) es limpio. Sin migraciones de DB en v1 — `ScannedReceipt` es efímero (no se persiste; el resultado es una transacción normal o nada).
- **Contingencia SDK (F1-T1 falla):** si litertlm no soporta `Content.ImageFile` en ninguna versión factible de bump → Fase 3 se recorta: solo OCR determinista (F2 completa + F4 completa funcionan sin vision), y la decisión queda documentada en el ADR como fase futura. El plan está diseñado para que F2+F4 sea un MVP completo por sí solo.
- **Contingencia bundle (F1-T2 falla):** idem — sin bundle .litertlm vision disponible, Fase 3 se pospone sin bloquear nada.
- **Garantías de datos:** cero escritura sin confirmación (preview pre-filled / commit con checkboxes); montos currency-tagged desde la extracción; sin PII en fixtures de tests.

## ❓ Preguntas Abiertas y Riesgos
1. **⚠️ Riesgo principal — versión del SDK**: `Content.ImageFile` existe en el SDK que Agora usa, pero la versión de PennyWise es 0.16.1 — el spike F1-T1 es LA primera tarea y su resultado decide el alcance de Fase 3. La nota de `libs.versions.toml:37` (`litertlm >= 0.14` cambia JVM-defaults) confirma que el API ya se movió entre versiones — el bump hay que gatearlo con la suite del chat completa.
2. **⚠️ Peso del modelo vision**: 3.14 GB descarga + ~5.9 GB RAM pico (dato del allowlist) — en devices de 6 GB RAM puede OOM. Mitigación: opt-in con advertencia explícita + detección de RAM; el OCR determinista es el default y no requiere nada.
3. **❓ Decisión de producto — cuota free del escaneo**: propongo `MAX_RECEIPT_SCANS_PER_MONTH = 5` free / Pro ilimitado (siguiendo el espíritu de statement imports: 1/mes). ¿Apruebas ese número o prefieres otro (o free ilimitado del todo, como el tracking SMS)?
4. **❓ Decisión de producto — Tesseract vs vision-only en F-Droid**: Tesseract añade un binario FOSS pesado (~15 MB + tessdata) al build fdroid; la alternativa es que en fdroid el OCR de calidad sea el modelo vision (AGPL-friendly, sin binarios) y ML Kit quede como acelerador del flavor standard. Mi recomendación es la segunda (menos superficie), pero requiere el bundle vision disponible. Se decide en F1-T3 con los resultados de ambos spikes.
5. **⚠️ Accuracy OCR en recibos térmicos**: papel térmico arrugado es el peor caso documentado en los proyectos OSS ("optical noise"); el flujo `needsReview` + edición manual en el formulario es el escape designado — la extracción es propuesta, nunca verdad.
6. **❓ Upstream**: este feature (receipt scanning) está en la categoría "nice-to-have" no pedida por la comunidad (no está en `docs\planned-features.md` ni en issues conocidos) — si el objetivo es contribuir upstream, conviene abrir el issue/discusión ANTES de implementar (el mantenedor tiene la última palabra en features de monetización/cuotas). Si el objetivo es fork personal, la ruta es directa.
