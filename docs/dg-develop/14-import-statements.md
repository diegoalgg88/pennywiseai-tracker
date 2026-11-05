# Deep-dive 14 — Import de statements: PDF, XLSX/CSV, preview/commit y dedup contra SMS

> Alcance: la importación de estados de cuenta como fuente alternativa al SMS — parsers PDF específicos por banco, la ruta universal xlsx/csv con column mapping, el flujo preview→commit, el dedup contra transacciones ya existentes, el balance check, la cuota free (1/mes) y la duplicación KMP (shared) para iOS. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

La import de statements es la entrada de datos para bancos cuyos SMS no alcanzan (o en iOS, donde no hay SMS): **PDFs** de bancos/fintechs soportados (GPay, PhonePe, Paytm, slice, ICICI) y **cualquier banco** vía `.xlsx`/`.csv` con mapeo de columnas. El flujo es **tres pasos** (KDoc de `ImportStatementUseCase`): *read → preview (sin escribir nada) → commit (solo las filas que el usuario dejó tickeadas) + balance check del closing balance contra la cuenta*. El commit re-corre el matching **dentro de una transacción de DB** con las mismas defensas que las escrituras SMS — una fila que llegó por SMS mientras el preview estaba abierto no se duplica ni sobrescribe. Cuota free: **1 import por mes calendario** (Pro ilimitado).

---

## 2. Arquitectura — dos capas

| Capa | Ubicación | Consumidor |
|---|---|---|
| **App** (`app\...\data\statement\`, 15 archivos) | `PdfTextExtractor` + `PdfParserFactory` (5 parsers PDF) + `StatementTable` (xlsx/csv) + `StatementImportProcessor` + `ImportStatementUseCase` | Android (import desde Settings) |
| **Shared KMP** (`shared\...\data\statement\`, 9 archivos) | `SharedStatementParserFactory` con 3 parsers (GPay, PhonePe, slice) + `SharedPdfTextExtractor` (expect/actual por plataforma) | iOS (via `PennyWiseSharedFacade`) |

El parser PDF reutiliza `ParsedTransaction` de **parser-core** (misma forma que un SMS parseado) — los rows importados entran al resto del sistema idénticos a los de SMS.

### 2.1 Parsers PDF (app)

`PdfParserFactory.getParser(text)` — content-aware por texto extraído. Soportados (mensaje de error textual del use case): **Google Pay, PhonePe, Paytm, slice, ICICI Bank**; cualquier otro banco → *"import its Excel (.xlsx) or CSV statement"*. `PdfTextExtractor` (PDFBox) maneja **PDFs con password**: `PdfRead.PasswordRequired(wrongPassword)` → la UI pide la clave y reintenta (`ImportStatementScreen` estado `PasswordRequired`).

### 2.2 Ruta universal xlsx/csv (`StatementTable` + `XlsxReader`)

- `readTable(uri)`: lee el archivo con **tope de bytes** (`MAX_FILE_BYTES`, `readNBytesCompat(MAX+1)` + require) y captura `OutOfMemoryError` → *"A hostile or enormous file: tell the user rather than crash the app"*. Archivo sin filas → error de usuario.
- **Column mapping persistido**: `StatementColumnMapping` guardado en preferencias **keyed por signature del header** (`StatementTableParser.signature(headerRow)`) — la próxima importación del mismo banco reconoce su formato y pre-mapea. Los estados de UI: `Mapping` (elegir columnas) → `Preview`.
- `currencyOf(account)` usa `CurrencyFormatter.resolveAccountCurrency` — *"never the stored INR default for an SMS-tracked foreign account"* (doc 10 §4).

## 3. El flujo (3 pasos, `ImportStatementUseCase.kt`)

### 3.1 Preview — `preview(parsed): StatementImportPlan`

`StatementImportProcessor(repositoryStore()).plan(parsed)` — **matchea exactamente como `commit` hará, sin escribir nada**. El plan clasifica cada row: nueva vs ya-existente (dedup) vs coincidencia, y la UI de `Preview` deja tickear solo las nuevas (`plan.newIndices`).

### 3.2 Commit — `commit(parsed, keep: Set<Int>)`

```
database.withTransaction {
    plan = processor.plan(parsed)        // re-match: el mundo pudo cambiar desde el preview
    excluded = rows − keep
    ensureManualOpening(bank, last4)     // por cada cuenta tocada
    result = processor.apply(plan, excluded)
    recomputeManualBalance(bank, last4)
}
```

Decisiones documentadas en el KDoc:
- **Re-matching en la transacción**: *"Matching runs again inside one database transaction — the protection SMS writes use — so an SMS saved or edited while the preview was open is matched as it is now, never overwritten with the preview's copy or duplicated"*.
- **Cuentas manuales**: `ensureManualOpening` pinea el opening balance ANTES de insertar los rows — *"so the recompute below adds them instead of absorbing them into the opening"*; `recomputeManualBalance` después recalcula el balance de la cuenta desde sus filas.

### 3.3 Dedup (StatementImportProcessor)

Misma filosofía del pipeline SMS (doc 07 §5): una row del statement que ya existe (por hash/matching contra transacciones SMS o de imports previas) se marca como duplicada y se excluye de `newIndices` — el resultado final reporta `imported / skippedDuplicates / totalParsed` (`SharedStatementImportResult.Success`), y la UI muestra *"N duplicates skipped"* (`ImportStatementScreen:789`).

### 3.4 Balance check — `balanceCheck(parsed, account)`

Compara el **closing balance del statement** contra el de PennyWise al final del último día del statement. Casos que retornan null (nada que comparar), documentados: statement sin balance, sin cuenta resuelta, **tarjeta de crédito** (*"its statement balance is what's owed, not held"*), o statement en otra moneda. `resolveAccount(parsed)`: la cuenta del PDF = banco único (+last4 si el statement lo imprime); **ambiguo o desconocido → null** (mismo principio de no adivinar del chat IA, doc 01 §5.1).

### 3.5 StatementTransactionEnricher

Enriquecimiento post-parse de rows (categoría por merchant mapping, account linking) antes del plan.

## 4. Cuota y monetización

| Regla | Valor | Dónde |
|---|---|---|
| Free | **1 import por mes calendario** | `FreeTierLimits.MAX_STATEMENT_IMPORTS_PER_MONTH = 1` (doc 04 §6) |
| Pro | Ilimitado | `EntitlementGate` |
| F-Droid | Todo libre | `FdroidBillingGateway` |

## 5. El lado iOS (shared KMP)

- `SharedStatementParserFactory`: 3 parsers (`GPaySharedStatementParser`, `PhonePeSharedStatementParser`, `SliceSharedStatementParser`) — **subconjunto** de los 5 de Android (Paytm/ICICI no están en shared).
- `SharedPdfTextExtractor`: `expect/actual` — Android usa PDFBox, iOS su implementación nativa.
- `ImportStatementUseCase` de `shared\domain\` — el iOS usa este flujo vía `PennyWiseSharedFacade` (doc 00 §6): iOS **no tiene SMS**, la import de PDF es su entrada de datos principal.
- `SharedStatementImportResult.Success(imported, skippedDuplicates, totalParsed)` — misma forma que Android.

## 6. Interacción con el resto del sistema

| Consumidor | Efecto |
|---|---|
| **Pipeline SMS** | Los rows importados viven como `TransactionEntity` normales — el dedup SMS los respeta (un SMS posterior del mismo cargo matchea y no duplica) |
| **Balances** | Cuentas manuales: opening + recompute; cuentas SMS-tracked: el balance check es informativo |
| **Reglas** | Se aplican igual que a SMS (mismo `saveTransaction` path en los processors) |
| **Backup** | Las transacciones importadas viajan; los **mappings de columnas** viven en preferencias (se restauran con el resto de prefs) |
| **Webhooks** | Exportadas como cualquier transacción (`sourceType` distinto en balances manuales) |

## 7. Procedimiento: extender

1. **Nuevo parser PDF de banco**: implementar en `app\...\data\statement\XxxPdfParser.kt` (parse de texto → `ParsedTransaction`) → registrar en `PdfParserFactory` → **duplicar en `shared\`** si iOS lo necesita (GPay/PhonePe/Slice siguen ese patrón) → actualizar el mensaje "Currently supported" del use case.
2. **Nuevo formato de spreadsheet**: extender `StatementTableParser`/`XlsxReader`.
3. **Cambiar dedup**: tocar `StatementImportProcessor.plan` — mantener la promesa de que preview y commit matchean idéntico.
4. Gate: `./init.sh app` (+ `:shared` compila dentro del gate de app por dependencia).
