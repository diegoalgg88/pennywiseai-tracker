# Deep-dive 05 — Implicaciones de agregar soporte a un banco nuevo

> Alcance: todo lo que se toca cuando se añade un parser bancario — desde el issue `[BANK]` hasta el release — incluyendo la traza completa de artefactos, el costo real por capa, las trampas de precedencia y las verificaciones que CI exige. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

"Agregar un banco" parece un archivo nuevo, pero en realidad son **mínimo 6 artefactos sincronizados entre 3 repositorios de código** (`parser-core`, `app`, `pennywise-web`) **+ documentación generada + validación de CI**. La regla de oro del repo: el contrato es **content-aware dispatch** — el orden de registro en `BankParserFactory` es parte de la corrección — y **CI falla** si los docs de bancos no coinciden con la factory (`SupportedBanksDocTest`). El flujo oficial está en `docs\adding-bank-parsers.md` y recomienda el subagent **`parser-author`** para todo lo que no sea un tweak trivial (y `parser-triage` para evaluar un issue `[BANK]` fresco).

---

## 2. Anatomía de un parser — qué hay que escribir

### 2.1 Elección de clase base (decisión #1, no negociable)

| Banco de… | Clase base obligatoria | Heredas |
|---|---|---|
| India | `BaseIndianBankParser` | Lógica centralizada de **e-mandates/upi-mandates** (`MandateInfo`), balance updates, detección de inversión (descarta "credited" como inversión) |
| UAE | `UAEBankParser` | Multi-divisa (AED/USD/EUR), tipos específicos (credit card purchase, ATM, remittance) |
| Irán | `BaseIranianBankParser` | Dígitos persas, formatos iraníes |
| Tailandia | `BaseThailandBankParser` | Formatos tailandeses |
| Resto del mundo | `BankParser` directo | Orquestación `parse()` por defecto |

### 2.2 Contrato a implementar

Ruta: `parser-core\src\main\kotlin\com\pennywiseai\parser\core\bank\`.

| Método | Obligatorio | Qué resuelve |
|---|---|---|
| `getBankName()` | Sí (abstract) | Display name — **se convierte en la identity del banco** en toda la app (`TransactionEntity.bankName`, condición ACCOUNT de reglas `BankName\|\|Last4`, AI Access, webhooks) |
| `canHandle(sender)` | Sí (abstract) | Match de sender ID — determina el despacho |
| `parse(smsBody, sender, timestamp)` | `open` con default | Solo sobrescribir si el banco necesita parsing custom |
| `extractAmount` / `extractTransactionType` / `extractMerchant` / `extractReference` / `extractAccountLast4` / `extractBalance` / `extractAvailableLimit` / `isTransactionMessage` | `open` con default | Sobrescribir lo que el banco escriba distinto (`ICICIBankParser` es el ejemplo trabajado de balance) |

**Reglas de hard constraint del repo que aplican** (de `CLAUDE.md`):
- **#6**: tests con los helpers compartidos `ParserTestUtils` (ver §4).
- **#7**: clase base correcta + registro en `BankParserFactory`.
- **#4**: **cero PII** — los samples de SMS en tests van anonimizados (los scripts `scripts\randomziedata.py` + `scripts\verify_anon_smsxml.py` existen para anonimizar y auditar fixtures: reemplazan cuentas, montos, teléfonos, OTPs).

**Imports**: solo `parser-core` (`com.pennywiseai.parser.core.TransactionType`, `ParsedTransaction`, `java.math.BigDecimal`) — el módulo es Kotlin puro sin dependencias Android. El puente a la app es `com.pennywiseai.tracker.data.mapper.toEntity()` (`ParsedTransaction` → `TransactionEntity`) — un parser nuevo **no requiere tocar la app** para funcionar.

### 2.3 El producto: `ParsedTransaction`

Campos que el parser debe llenar correctamente: amount (BigDecimal), type (INCOME/EXPENSE/CREDIT/TRANSFER/INVESTMENT/BALANCE_UPDATE), merchant, reference, accountLast4, balance, creditLimit (available limit), bankName, currency, accountCurrency (si difiere de la transacción), isFromCard, isMobileWallet, transactionHash (dedup md5 — lo genera la infraestructura). **Cada campo mal extraído tiene blast radius downstream**: el balance alimenta `AccountBalanceEntity` y los widgets de saldo; el accountLast4 distingue cuentas del mismo banco; la currency rompe totales si es incorrecta (invariante de nunca-sumar-monedas); el merchant alimenta aliases, auto-categorización (`MerchantMappingEntity`) y las reglas.

---

## 3. Registro en `BankParserFactory` — el orden ES corrección

`BankParserFactory.kt` mantiene `parsers: List<BankParser>` (~168 instancias). El despacho `parse()` itera **en orden de lista** todos los parsers cuyo `canHandle(sender)` matchea y devuelve el **primer `parse()` no-null** (verificado — comentario en la línea 201 de la factory: *"first non-null parse(). This un-shadows parsers that share a sender ID"*).

**Regla**: colocar el parser **por encima** de cualquier parser más amplio cuyo `canHandle()` pudiera reclamar los mismos senders. Precedencias reales documentadas en el propio archivo (evidencia de grep, 2026-10-24):

```
BankParserFactory.kt:11   HDFCMutualFundParser(),  // must be before HDFCBankParser to avoid interception by HDFC's broad DLT pattern
BankParserFactory.kt:14   NepalSBIBankParser(),    // must be before SBIBankParser to avoid SBI-match on NSBI sender
BankParserFactory.kt:92   NabilBankParser(),       // must be before NMBBankParser (NMB handles NABIL senders broadly)
BankParserFactory.kt:103  MPesaMozambiqueParser(), // must be before Tanzania & Kenya; gates on Portuguese "Confirmado" + "MT"
BankParserFactory.kt:104  MPesaTanzaniaParser(),   // must be before Kenya M-PESA
BankParserFactory.kt:152  ChaseUKParser(),         // before the US Chase parser
```

**Implicación de "agregar un banco"**: revisar si tu sender ID colisiona con `canHandle` de parsers existentes → puede requerir **afinar el `canHandle` de un parser ajeno** (narrowing), no solo añadir el tuyo. El incidente documentado en `fastlane\...\changelogs\default.txt` muestra el modo de fallo real: *"Fixed Tamilnad Mercantile Bank SMS messages being incorrectly captured by Thailand's TTB parser"* — un banco mal ubicado captura SMS de otro.

**Trampa adicional**: tu parser debe gatear por **contenido** además de sender (`isTransactionMessage`): devuelve `null` para OTPs/promos/estados de cuenta, o des-sombra mal a los parsers que comparten sender.

---

## 4. Tests — estándar obligatorio

`docs\parser-test-standards.md` (resumen operativo):

- JUnit 5 `@TestFactory` + `List<DynamicTest>` vía `ParserTestUtils.runTestSuite` (parser) o `runFactoryTestSuite` (despacho por factory — **obligatorio también probar la resolución por sender**, no solo el parser aislado).
- `runTestSuite` acepta `handleCases` para asertar `canHandle()` junto al parsing.
- `ExpectedTransaction`: solo campos que el parser llena; comparación exacta.
- Archivo: `parser-core\src\test\kotlin\com\pennywiseai\parser\core\bank\XxxParserTest.kt` (los viejos `TestXxx` del test root sobreviven; los nuevos van en `bank/`).
- Suite de ~168 archivos `*ParserTest.kt` — cada parser existente tiene su clase; el estándar es "un archivo de test por parser".

## 5. Artefactos generados y sincronización de CI

**`scripts\update-supported-banks.sh`** regenera (verificado en §scripts del inventario):
1. `docs\supported-banks.json` — catálogo máquina (168 bancos / 26 países; fuente de verdad).
2. El bloque `SUPPORTED_BANKS` del README (no editar a mano).
3. Play listing copy.
4. `bank-samples.json` para `pennywise-web` (tester de parseo).

**`SupportedBanksDocTest`** (en `parser-core\src\test\...`) **falla CI** cuando lo generado difiere de la factory. Otro test, `BankSamplesDocTest`, valida `bank-samples.json`. Consecuencia: **un parser sin regenerar docs rompe el gate** — no es opcional.

## 6. Impacto aguas abajo (por qué esto importa más allá del parser)

| Consumidor | Cómo usa el banco nuevo | Acción requerida |
|---|---|---|
| **App Android** (`OptimizedSmsReaderWorker`) | `BankParserFactory.parse()` en tiempo real y en scan histórico | Ninguna (despacho por factory) |
| **Home/Cuentas** | `bankName + accountLast4` crean `AccountBalanceEntity` | Ninguna |
| **Reglas** | Campo `BANK_NAME` y `ACCOUNT` (`BankName\|\|Last4`) funcionan solos | Ninguna |
| **AI Access (MCP)** | `search_transactions`/`list_accounts` exponen banco + last-4 | Ninguna (read-only genérico) |
| **Chat IA on-device** | System prompt lista cuentas (alias/banco+4) | Ninguna |
| **Webhooks** | Exportan transacciones con su banco | Ninguna |
| **Backup** | Todo viaja serializado | Ninguna |
| **pennywise-web** | Página long-tail por banco (`/banks/{slug}`), tester de parseo, samples | **Automático tras `update-supported-banks.sh`** — el tester usa el mismo `parser-core` publicado (`com.pennywiseai:parser-core`) |
| **iOS** | `shared` tiene sus propios statement parsers (no SMS) | Ninguna para SMS |
| **Traducciones** | Los nombres de banco no se traducen | Ninguna |
| **Issue tracker** | `[BANK]` issues triaged con `parser-triage` | Cerrar con evidencia de tests |

**Insight clave**: la arquitectura de despacho por factory + mapeo genérico hace que **un parser bien hecho escape sin tocar nada más** — casi todo el costo está en parser + tests + precedencia + docs regenerados. Los fallos históricos (TTB capturando Tamilnad, HDFC DLT interceptando HDFC Mutual Fund) no fueron de la app: fueron de **precedencia y `canHandle`**.

## 7. Procedimiento paso a paso (consolidado)

1. **Triage**: issue `[BANK]` → subagent `parser-triage` (evaluar samples, país, colisiones de sender).
2. **Elegir base regional** (§2.1) y crear `XxxBankParser.kt` en `parser-core\...\bank\` con samples anonimizados (sin PII).
3. **Registrar en `BankParserFactory`** en la posición correcta, con comentario de precedencia si comparte sender (§3).
4. **Tests** `XxxParserTest.kt` con `ParserTestUtils` — suite del parser + **factory smoke tests** (§4).
5. **Regenerar docs**: `./scripts/update-supported-banks.sh` (§5).
6. **Gate**: `./init.sh parser` (equivale a `:parser-core:test`, que incluye `SupportedBanksDocTest`).
7. **Opcional pero recomendado**: probar samples reales en el tester web (`pennywise-web` local) o reportarlos vía el flujo "Report a parsing problem".
8. **Commit**: convencional `feat(parsers): add <Bank> (<country>)` — ver commits recientes (`db4692d4 feat(parsers): add Suryoday Small Finance Bank credit card (#974)`).

## 8. Costo real y riesgos (checklist de review)

| Riesgo | Mitigación |
|---|---|
| Sombreado de sender (parser nuevo captura SMS ajenos o viceversa) | Factory smoke tests + posición correcta + comentario de precedencia |
| `canHandle` demasiado broad | Gate por contenido (`isTransactionMessage`, patrones DLT etc.) |
| PII en fixtures | Anonimización + `verify_anon_smsxml.py` |
| Docs desincronizados | `update-supported-banks.sh` + `SupportedBanksDocTest` en CI |
| Moneda/currency mal detectada | Test con `ExpectedTransaction.currency` explícito |
| Balance mal parseado → historial de saldos corrupto | `extractBalance` testeado (patrones "Avl bal", "Available Balance") |
| Mandates/subscriptions del banco sin soporte | Heredar de la base correcta (`BaseIndianBankParser.parseEMandateSubscription`) |

**Esfuerzo típico**: 1 parser (50–300 líneas según override) + 1 test suite + 1 línea de factory (con posible re-orden) + 1 regeneración de docs. El ejemplo más reciente del repo (Suryoday, commit `db4692d4`) fue exactamente eso.
