# Deep-dive 22 — Import de estados de cuenta × IA: cómo la IA está (y NO está) involucrada, sus límites, y cómo la IA local podría mejorar el flujo

> Alcance: el subsistema de import de statements (doc 14) examinado desde la perspectiva de la IA — qué partes usan IA hoy (respuesta: ninguna del pipeline de import), dónde termina el chat local (text-only), los límites estructurales, y un análisis de viabilidad de las extensiones propuestas: foto de ticket/estado de cuenta, y registro de gastos desde texto pegado en el chat. Verificado contra código el 2026-10-25. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

El import de estados de cuenta es **100% determinista — la IA no participa en absolutamente nada del pipeline**. Los parsers PDF son regex/heurística (`PdfParserFactory` + 5 parsers por banco), el xlsx/csv es mapeo explícito de columnas, y el preview→commit es validación manual del usuario. El chat IA es un subsistema **totalmente separado** con dos límites duros verificados en código: (1) su interfaz es **text-only** (`LlmService.kt:25-26` — `sendMessage(message: String)`, sin ninguna vía de imagen/documento), y (2) sus tools de mutación operan **una transacción a la vez** con confirmación explícita. No hay OCR en la app (`build.gradle.kts` sin mlkit/tesseract/vision). Aun así, la IA local **sí puede mejorar el flujo hoy** en el único punto donde el texto ya es legible: el **chat como extractor de texto pegado** — y hay una ruta viable (con nombre: OCR local + parser existente) para la foto de estado de cuenta.

---

## 2. El pipeline de import, pieza por pieza — dónde NO hay IA

| Etapa | Implementación | ¿IA? |
|---|---|---|
| Extracción de texto PDF | `PdfTextExtractor` (PDFBox — el PDF ya trae su texto embebido) | ❌ Determinista |
| Identificación de banco | `PdfParserFactory.getParser(text)` — content-aware por patrones de texto | ❌ Regex/heurística |
| Parseo a filas | 5 parsers dedicados (GPay, PhonePe, Paytm, slice, ICICI) | ❌ Regex |
| Ruta universal xlsx/csv | `XlsxReader` + `StatementColumnMapping` persistido por firma de header | ❌ Mapeo explícito |
| Preview | `StatementImportProcessor.plan(parsed)` — matchea idéntico a commit, sin escribir | ❌ |
| Commit | `database.withTransaction` + re-matching + `recomputeManualBalance` | ❌ |
| Balance check | `balanceCheck` — closing del statement vs saldo PennyWise | ❌ |
| Enrichment | `StatementTransactionEnricher` — **auto-categorización** vía `MerchantMapping`/`SharedCategoryMapping` (doc 15) | ❌ keyword mapping (la "IA" de categorías del repo es una tabla estática + aprendizaje por uso) |

**Dato clave**: la única "inteligencia" del pipeline es la **auto-categorización por merchant** (doc 15) — que tampoco es LLM: es keyword matching (`SharedCategoryMapping`, 941 líneas de sets curados) + mapping aprendido del usuario. Un statement importado queda categorizado igual que un SMS.

## 3. El chat IA: los dos límites duros (verificados en código)

### 3.1 Text-only — sin excepción

`domain\service\LlmService.kt:12-30` — la interfaz completa es:
```
initialize(modelPath) · createConversation(...) · sendMessage(message: String)
sendMessageEvents(message: String) · hasActiveConversation · closeConversation · reset · isInitialized
```
No existe sobrecarga ni método con `image`, `photo`, `bitmap`, `uri`. El motor LiteRT corre **Qwen 2.5 1.5B** — un modelo de texto. No hay vía para pasar un PDF o una foto a la conversación. (El `LlmServiceImpl` es Engine CPU + Conversation con system prompt — doc 01 §6.)

### 3.2 Una transacción por confirmación

Las 3 tools de mutación (`add/update/delete_transaction`, doc 01 §3) construyen UN `PendingChatAction` a la vez — la tarjeta de confirmación muestra una transacción. Un estado de cuenta de 40 filas implicaría 40 rondas "propuesta→tap" — usable como fallback manual, inusable como flujo de import masivo. Además el presupuesto de contexto es de **2560 tokens** (doc 01 §4.2) — pegar un estado de cuenta completo en un solo mensaje excede el presupuesto y dispara *"Chat memory is full"*.

## 4. El receipt flow existente — la foto se guarda, no se lee

Verificado (`data\receipt\ReceiptManager.kt:25`, `presentation\add\TransactionTabContent.kt:738`, `AddViewModel.kt:480-494`):
- `TakePicture()` contract → `receiptManager.saveReceipt(uri)` → copia a `filesDir/receipts/` → guarda `receiptPath` en la transacción.
- **Nadie extrae nada de la foto** — es evidencia visual adjunta (doc 09 §5: viaja en backup FULL como Base64). El usuario escribe monto/merchant manualmente.

**Conclusión estructural**: hoy no existe ningún punto del código donde una imagen se convierta en datos — la cadena foto→texto simplemente no está construida.

## 5. Cómo la IA local SÍ puede mejorar el flujo (análisis de viabilidad)

### Opción A — Texto pegado en el chat: funciona HOY con guardas (cero código)

El chat ya puede registrar desde texto si el usuario pega el contenido de un statement/ticket:

| Ventaja | Límite |
|---|---|
| `add_transaction` ya parsea español, montos con basura, categorías por merchant (doc 01 §5.1) | **Una transacción por mensaje** — el modelo 1.5B no procesa listas de forma confiable |
| Sin infraestructura nueva | Presupuesto 2560 tokens — pegar de 2-4 filas por mensaje, no 40 |
| Confirmación explícita por fila (garantía #170 intacta) | Moneda: la tool asume la del contexto — verificar que el system prompt del mes indique MXN |

**Flujo práctico**: abrir el PDF → copiar 3-4 líneas → pegar en el chat → confirmar → repetir. Para 5-10 movimientos es razonable; para un statement completo no.

### Opción B — Foto de ticket/estado de cuenta: requiere un eslabón nuevo (OCR), pero la arquitectura ya tiene donde enchufarlo

El gap es exactamente UNA pieza: **foto→texto**. A partir del texto, el pipeline existente hace el resto. Dos rutas:

| Ruta | Cómo | Costo/riesgo |
|---|---|---|
| **B1 — ML Kit Text Recognition (on-device)** | `com.google.mlkit:text-recognition` (gratis, offline, ~20 MB, latencia <1s, español soportado) — interpondría `OcrExtractor` entre `TakePicture` y el parseo. Del texto OCR: (a) tickets → `LlmRepository`/`add_transaction` con el texto como mensaje del usuario (el modelo ya sanitiza), o (b) statements → `StatementImportProcessor` reusando preview→commit y dedup. **Nota F-Droid**: ML Kit es una librería de Google — revisar policy F-Droid (vision-common es usable vía microG en algunos casos; alternativa: Tesseract, más lento pero 100% FOSS) | +1 dependencia +1 servicio (~200 líneas). El OCR de estados de cuenta (tablas, columnas) es MÁS difícil que tickets — esperar filas rotas |
| **B2 — OCR + LLM híbrido** | OCR entrega texto crudo → el modelo local **re-estructura** (prompt: "extrae las transacciones de este texto") → N `PendingChatAction` en secuencia. Requiere extender el chat con una **tool batch** (ver §5.1) | El modelo 1.5B con texto OCR ruidoso alucina montos — la confirmación por fila es innegociable |

### 5.1 El cambio de fondo que ambas rutas necesitan: tool de import por lotes

La mejora estructural es una **`import_transactions` tool** (o un flujo paralelo al chat):

```
Texto (pegado u OCR) → extractor determinista (StatementImportProcessor extendido
a texto libre) → lista de drafts → pantalla de preview (igual que statement import:
tickedas por fila) → commit en una transacción Room + dedup
```

- **No** pasa por la confirmación-una-por-una del chat — reusa el patrón preview→commit que ya existe (doc 14 §3).
- La IA local queda como **fallback conversacional** (Opción A) y opcionalmente como **normalizador** de texto OCR ruidoso (B2), no como el path principal — coherente con el diseño del repo: lo determinista es determinista, el LLM propone y el humano confirma.
- La garantía #170 se mantiene: nada se escribe sin preview/confirmación visible.

### 5.2 Lo que NO recomiendo

- **Multimodal en el chat local**: cambiar Qwen 2.5 1.5B por un modelo vision-language (ej. Qwen2-VL) — el presupuesto 4096 tokens no da para imagen + contexto; la latencia en CPU sería de decenas de segundos por foto; y `LlmService` tendría que romper su interfaz. OCR dedicado >> VLM en este device factor.
- **OCR en la nube** (Google Vision API): rompe el principio on-device — el receipt contiene merchant, monto, últimos 4 dígitos. El relay AI Access existe porque hay demanda de cloud read-only; un OCR cloud sería una superficie nueva de fuga sin el diseño de consentimiento equivalente.

## 6. Matriz resumen — estado y esfuerzo

| Funcionalidad pedida | Estado hoy | Vía | Esfuerzo estimado |
|---|---|---|---|
| Pegar texto en el chat → registrar gasto | ✅ **Ya funciona** (1-4 transacciones por mensaje, con confirmación) | Ninguno — documentar | 0 |
| PDF de statement → import | ✅ Ya existe para 5 bancos; **xlsx/csv universal para AMEX/BBVA MX** | Descarga xlsx/csv del portal | 0 |
| Foto de ticket → registrar | ❌ No existe | OCR (ML Kit/Tesseract) → texto → add_transaction o parser | Medio — nuevo servicio OCR + UI (~1-2 semanas de trabajo casual) |
| Foto de estado de cuenta → import por lotes | ❌ No existe | OCR + extractor de tablas + preview→commit existente | Alto — OCR de tablas es el punto difícil |
| PDF AMEX MX / BBVA MX nativo | ❌ No existe (solo 5 bancos) | Parser nuevo por banco (doc 14 §7) con tus statements reales como insumo | Bajo-Medio — el patrón está documentado y tus PDFs reales son el fixture perfecto |

## 7. Recomendación priorizada (si se propone upstream o se implementa local)

1. **Documentar la Opción A** (texto pegado con límites) — costo cero, valor inmediato.
2. **Parser PDF AMEX MX/BBVA MX** — es el gap de MAYOR valor/menor riesgo para tu caso: tus estados reales de BBVA/Amex MX como samples → parsers deterministas siguiendo el patrón doc 14 §7. Sin OCR, sin IA nueva, entra directo al preview→commit que ya funciona.
3. **OCR de tickets (B1)** como feature separada — empieza con ML Kit + el flujo `TakePicture` que ya existe en Add Transaction ("adjuntar y extraer"), con Tesseract como variante F-Droid.
4. **Import por lotes desde texto** (§5.1) como evolución natural de 3.
5. Descartar multimodal en el chat local y OCR cloud (§5.2).

*Este doc complementa: doc 14 (import statements), doc 01 (chat IA), doc 15 (categorización), doc 06 (tools IA local vs cloud).*
