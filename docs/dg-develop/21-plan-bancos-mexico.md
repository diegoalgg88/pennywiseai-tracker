# 📋 PLAN: Soporte American Express México + BBVA Bancomer México (país nuevo: México, MXN)
> **Plan ID:** `PLAN-20261024-BANCOS-MX`
> **Versión:** `1.0.0`
> **Tipo de Plan:** `Feature`
> **Estado:** `Creado`

## 🎯 Resumen y Arquitectura
Añadir soporte de parsing SMS para **American Express México** y **BBVA Bancomer México** — el país 27 del catálogo (moneda **MXN**). Decisión arquitectónica: crear **`BaseMexicoBankParser`** siguiendo el precedente de `BaseElSalvadorBankParser` (el único clúster en español del repo): base regional con esqueleto compartido (monto MXN, marcadores de débito/crédito en español, failures en español vía `FinancialMessageSafety`) y subclasses que solo añaden sender match + patrones de cuenta/tarjeta. El punto de riesgo crítico identificado: **el `AMEXBankParser` de India matchea cualquier sender que contiene "AMEX"** (`AMEXBankParser.kt:22`, `normalizedSender.contains("AMEX")`) — un sender `AMEXMX` sería reclamado por el parser de India. La mitigación es doble (precedencia en la factory + content-gating en español), verificada por factory smoke tests obligatorios.

## 🔍 Alcance (Scope)
- **DENTRO (In):**
  - `parser-core\src\main\kotlin\com\pennywiseai\parser\core\bank\BaseMexicoBankParser.kt` (nuevo)
  - `parser-core\...\bank\AmexMexicoParser.kt` (nuevo)
  - `parser-core\...\bank\BBVAMexicoParser.kt` (nuevo)
  - `BankParserFactory.kt` — registro de ambos parsers con comentario de precedencia (AmexMexico **antes** de `AMEXBankParser()`, línea 57 actual)
  - Tests: `AmexMexicoParserTest.kt`, `BBVAMexicoParserTest.kt` (suite + **factory smoke tests**)
  - `scripts\update-supported-banks.sh` — regeneración de `docs\supported-banks.json` + bloque README + bank-samples
  - Verificación puntual de `CurrencyFormatter` para MXN (símbolo `$` colisiona con USD en listas mixtas — precedente AR$/CL$/$U, doc 10 §4)
- **FUERA (Out):**
  - Cambios en la app Android (el despacho es por factory — cero código de app, verificado doc 05 §6)
  - iOS (`shared` no consume SMS parsing)
  - Nuevos bancos mexicanos adicionales (Santander, Banorte, HSBC MX, etc.) — la base queda lista para ellos pero este plan no los implementa
  - Statement import PDF para bancos mexicanos
  - Web tester (`pennywise-web`) — opcional y solo si se aportan samples públicos al `bank-samples.json` (tarea marcada opcional)

## 🗺️ Fases de Implementación

### Fase 1: Samples y análisis de formatos reales
> **Contexto (verificación ADB 2026-10-24, device `R5CT422CGKH` SM-S908E)**: se inspeccionó el inbox SMS completo del device (senders agrupados), todas las carpetas SMS, el content de MMS/RCS y las apps instaladas. **El sender de AMEX MX es `AMEX` — idéntico al de India**, y BBVA **no envía ningún SMS** a este dispositivo (sus avisos llegan como push de la app). Los samples reales disponibles son 2, ambos casos NEGATIVOS (no-transacción); las transacciones positivas siguen siendo sintéticas hasta conseguir device con tráfico activo.

- [ ] **[F1-T1]** Construir corpus de samples anclado en los hechos verificados
  - **Archivos/Módulos:** fixtures inline en los tests de F3 (con `-T` DLT-style nunca aplican; verificaciones por banco abajo)
  - **Acción:** Redactar 3-6 SMS sintéticos **por banco** con estructura documentada, no inventada libremente. Hechos verificados (web 2026-10-24 + **inspección ADB del device `R5CT422CGKH`**, inbox SMS completo, MMS/RCS y apps):
    - **AMEX MX (verificado en device)**: senders reales = **`AMEX`** (alfanumérico — **IDÉNTICO al sender que reclama el parser de India**) y **`642639`** (OTP). Samples reales obtenidos (ambos casos NEGATivos, sin PII — el código OTP se randomiza en el fixture):
      - `AMEX`: "El pago de tu AmEx fue devuelto por el banco. El servicio podria ser interrumpido. Paga en http://m.amex/MXAP o https://m.amex/centrosDePago" → no-transacción (pago devuelto, sin monto) — fixture negativo.
      - `642639`: "NUNCA compartas este codigo de un solo uso, Amex no te llamara para pedirlo: 818916..." → OTP — fixture negativo (gate "codigo" de la base).
      - Copys de transacción POSITIVA (cargo/pago de tarjeta): no hay en el device — siguen sintéticos, modelados sobre la estructura observada (español, "$X.XX", "terminación XXXX") + TODO de re-verificación.
    - **BBVA MX (verificado en device)**: **CERO SMS en el dispositivo** — ni `266845` ni ningún sender BBVA en inbox/todas las carpetas; tampoco RCS bancario (el content MMS solo tiene mensajes personales con tr_id vacío — Samsung no expone RCS business por este provider). Apps instaladas: **`com.bancomer.mbanking`** y **`com.americanexpress.android.acctsvcs.mx`**. Los avisos de BBVA que el usuario recibe son **push de la app**, no SMS. El parser SMS de BBVA queda como best-effort documentado (sender oficial `266845` según antifraude BBVA); el path de valor inmediato para el usuario es el canal de notificaciones (F1-T2c).
    - Cada sample sintético se marca con comentario `// sample sintético — verificar contra SMS real al obtener device` y la suite incluye un TODO de reemplazo.
  - **Verificación:** Revisión cruzada de estructura (monto/moneda/terminación/connectores) contra los docs oficiales citados arriba + los 2 samples reales del device; cero PII por construcción.
- [ ] **[F1-T2]** Documentar el canal real de cada banco (SMS vs RCS vs push de app)
  - **Archivos/Módulos:** sin código — nota en el PR + este doc
  - **Acción:** Delimitar qué canal captura PennyWise por banco hoy: (a) **SMS** — cubierto por parser nuevo; (b) **RCS** — PennyWise YA lee RCS business messages (`streamRcsToChannel`, tr_id `agent@rbm.goog`, doc 07 §2.2): los mensajes RCS de BBVA llegarían con sender derivado del tr_id base64 y el MISMO parser los debe reclamar — verificar qué sender exacto produce el pipeline para un agente RCS de BBVA (probablemente contiene "bbva"/agent id); (c) **push de app** — solo si se habilita `BankNotificationListenerService`: añadir `com.bancomer.mbanking` (BBVA MX) y el package de Amex App MX a `BankNotificationConfig` con alias a los `bankName` nuevos (doc 07 §3.5, cross-channel dedup requiere que `bankName` esté en `notificationBankNames`). El usuario reporta "sí me llegan notificaciones" — identificar de qué canal son (si son push de Amex App, el path del parser SMS es secundario y el notification channel es el principal).
  - **Verificación:** Matriz banco×canal documentada con evidencia; decisión de si F4-T3.5 (notification packages) entra o no al scope.

### Fase 2: Base regional + parsers
- [ ] **[F2-T1]** Crear `BaseMexicoBankParser`
  - **Archivos/Módulos:** `parser-core\src\main\kotlin\com\pennywiseai\parser\core\bank\BaseMexicoBankParser.kt`
  - **Acción:** Base abstracta extendiendo `BankParser` con: `getCurrency() = "MXN"`; extracción de monto para formatos mexicanos (`$1,234.56`, `MXN$1,234.56`, `$ 1,234.56 MXN`, con separador de miles y punto decimal — US format como El Salvador); `extractTransactionType` por marcadores en español ("compra"/"cargo"/"pago"/"retiro"/"dispuesto" → EXPENSE; "abono"/"depósito"/"recibió"/"reembolso" → INCOME); `isTransactionMessage` reutilizando `FinancialMessageSafety` (`isSecurityCode`, `isOperationalOrPromotionalNotice`, `hasExplicitFailure` con lista de failures en español mexicano: "rechazada", "negada", "sin fondos", "no realizada", "código de seguridad", "verifica con tu App"); `accountPatterns` abstract (group 1 = last 4), mismo patrón que El Salvador; merchant con conectores `en`/`a`/`desde` y la lección de El Salvador: en income, el nombre tras "de" suele ser el titular, no el counterparty — solo leer `desde` como pagador.
  - **Verificación:** Compila: `./init.sh parser` (aún sin subclasses registradas no rompe nada).
- [ ] **[F2-T2]** Implementar `AmexMexicoParser`
  - **Archivos/Módulos:** `parser-core\...\bank\AmexMexicoParser.kt`
  - **Acción:** Extiende `BaseMexicoBankParser`. `getBankName() = "American Express Mexico"` (nombre distinto al "American Express" de India — la identity del banco alimenta cuentas, reglas y AI Access). `canHandle`: **sender `AMEX` exacto** (verificado en device — y es IDÉNTICO al que el parser de India reclama con `contains("AMEX")` + match exacto `== "AMEX"`, `AMEXBankParser.kt:22-42`) y opcionalmente `AMEXMX` como variante futura. **NO** reclamar `642639` (es el sender de OTP — unclaimed + sin letras = descarte silencioso, comportamiento correcto). `parse()` con **content-gate en español como MECANISMO PRIMARIO, no solo defensa**: ambos parsers reclaman `AMEX`, así que el despacho content-aware de la factory resuelve por contenido — el gate debe ser estricto y return-null en: ausencia de marcadores españoles ("compra|cargo|pago|abono|MXN|terminación"), presencia de marcadores indios ("INR", "Rs.", "₹"), o montos en formato indio. Con el parser MX registrado ANTES del de India (F2-T4), un SMS en inglés de India matchea el canHandle del MX parser, falla el content-gate (null), y el dispatch continúa al parser de India. Gasto con tarjeta AMEX → `TransactionType.CREDIT` (mismo criterio documentado del parser de India: son tarjetas de crédito sin cuenta bancaria detrás) con `isFromCard = true` y `extractAvailableLimit` si el SMS trae "límite disponible" (doc 07 §6: se guarda `total = available + outstanding`). Patrones "terminación XXXX" para la tarjeta. Fixture negativo obligatorio con el sample real del device ("El pago de tu AmEx fue devuelto por el banco..." — pago devuelto, sin monto → no-transacción).
  - **Verificación:** Test manual del parse contra los samples de F1 (2 reales del device + sintéticos) en un scratch test antes de la suite formal.
- [ ] **[F2-T3]** Implementar `BBVAMexicoParser`
  - **Archivos/Módulos:** `parser-core\...\bank\BBVAMexicoParser.kt`
  - **Acción:** Extiende `BaseMexicoBankParser`. `getBankName() = "BBVA Mexico"`. `canHandle` = match **exacto del corto numérico `266845`** (sender oficial verificado, único según la página antifraude de BBVA MX) — normalizar el sender antes de comparar (los OEM pueden presentar el short code con prefijos `+52`/`521` o espacios; seguir el patrón de `getParsers` que itera `canHandle` raw, y hacer el match tolerante a prefijos de país). **Nota de pipeline verificada**: un sender sin letras NO es candidato a `unrecognized_sms` (`OptimizedSmsReaderWorker.isUnrecognizedCandidate:148` — `if (upper.none { it.isLetter() }) return false`), pero eso solo aplica cuando NINGÚN parser matcheó — con el `canHandle` correcto el parser recibe el SMS normalmente. Distinguir débito vs crédito por contenido: compra con "Tarjeta de Débito" → EXPENSE; compra con tarjeta de crédito → CREDIT + `detectIsCard`; SPEI/transferencia ("Transferencia SPEI") → TRANSFER si el formato expone origen/destino, si no EXPENSE/INCOME según dirección; "pago de tu tarjeta" (abono a la TC) → INCOME hacia la cuenta de crédito. Balance: patrones "Saldo disponible:", "Saldo:" (alimenta la serie histórica de `AccountBalanceEntity`, doc 07 §6). Usar `transactionCurrency`/`accountCurrency` default de la base (MXN).
  - **Verificación:** Parse de samples F1 en scratch test, incluyendo el sender `266845` y variantes con prefijo `+52`.
- [ ] **[F2-T4]** Registrar ambos parsers en `BankParserFactory` con precedencia documentada
  - **Archivos/Módulos:** `parser-core\...\bank\BankParserFactory.kt`
  - **Acción:** Insertar `AmexMexicoParser()` y `BBVAMexicoParser()` en la lista `parsers`, con `AmexMexicoParser` **estrictamente antes** de `AMEXBankParser()` (línea 57 actual) y comentario de precedencia siguiendo la convención del archivo (ej. `// must be before AMEXBankParser: India's canHandle contains("AMEX") and would claim AMEXMX senders`). BBVA no colisiona con nadie existente (verificado por grep — no hay parser BBVA), ubicación indiferente; agrupar con los bancos latinoamericanos (Bancolombia/Cuscatlan, líneas 79-80) por legibilidad.
  - **Verificación:** El despacho content-aware de la factory (`parse()` itera TODOS los canHandle que matchean y devuelve el primer parse() no-null, doc 05 §3) hace que orden + content-gate de F2-T2 sea defensa en profundidad: aunque el sender fuera reclamado por India primero, su `parse()` retorna null en español y el dispatch continúa al de México.

- [ ] **[F2-T5]** (Scope ampliado, aprobado por el usuario) Registrar packages de notificaciones BBVA/Amex MX en `BankNotificationConfig`
  - **Archivos/Módulos:** `app\src\main\java\com\pennywiseai\tracker\receiver\BankNotificationConfig.kt`, `app\src\test\java\com\pennywiseai\tracker\receiver\BankNotificationConfigTest.kt`
  - **Acción:** Añadir al map `allowedPackages` (contract verificado en código): `"com.bancomer.mbanking" to "BBVAMX"` y `"com.americanexpress.android.acctsvcs.mx" to "AMEX"` (packages verificados instalados en device `R5CT422CGKH` vía `pm list packages`). **El alias es el "sender" sintético** que el listener despacha con `BankParserFactory.getParsers(senderAlias).firstNotNullOfOrNull { it.parse(body, alias, ts) }` (`BankNotificationListenerService.kt:79-81`) — por lo tanto:
    - **BBVA**: el `canHandle` de `BBVAMexicoParser` (F2-T3) debe aceptar **también el alias alfanumérico `"BBVAMX"`** además del corto `266845` — mismo patrón que los precedentes (Enpara/Huntington: *"alias must match XxxParser.canHandle()"*, `BankNotificationConfig.kt:19-32`). Con un solo parser matcheando `BBVAMX`, el dispatch de la notificación es directo.
    - **AMEX**: el alias `"AMEX"` matchea AMBOS parsers (MX + India) — el `getParsers` devuelve los dos y el content-gate en español de F2-T2 resuelve: un push de Amex App MX en español parsea con el parser MX; el orden de factory (F2-T4) garantiza que MX se intenta primero. **Dependencia dura**: esta tarea REQUIERE que F2-T2/F2-T3/F2-T4 estén hechos — sin los parsers, el test contract falla.
    - `notificationBankNames` (lazy, se deriva de los aliases vía `getParser`) incluirá "BBVA Mexico" y "American Express Mexico" automáticamente → el **dedup cross-canal** SMS↔push (capa 3 del pipeline, doc 07 §5: `isBookedByOtherChannel`, ±2 min, `isSameCharge` con moneda comparada) queda activo para ambos bancos sin código extra. Los hashes difieren entre canales (sender distinto en el md5), la capa nearby-±2min es la que une.
    - Comentario en es-MX por entrada (convención del repo para comments nuevos; los existentes del archivo están en inglés — solo los nuevos en es-MX).
  - **Verificación:** `./init.sh app` — `BankNotificationConfigTest` (itera `allowedPackages`: `isAllowed(pkg)` true + `getParser(alias)` resuelve por cada entrada) verde con las 2 entradas nuevas; `senderAlias("com.bancomer.mbanking") == "BBVAMX"`.
- [ ] **[F2-T6]** (Junto con F2-T5) Verificación en device del flujo notification→transacción
  - **Archivos/Módulos:** sin código — prueba E2E manual
  - **Acción:** Con build debug instalado en `R5CT422CGKH` y el listener habilitado (Settings → Notificaciones de apps bancarias / BIND_NOTIFICATION_LISTENER_SERVICE): generar una compra real o simular push, y verificar: transacción creada con `bankName` correcto, moneda MXN, dedup cross-canal (si llega SMS + push del mismo cargo, solo una fila), y que las notificaciones promocionales de las apps ("Mes sin Intereses", puntos MR) quedan descartadas por `isTransactionMessage`.
  - **Verificación:** Transacción visible en la app con datos correctos; sin duplicados SMS/push.
- [ ] **[F3-T1]** Suite `AmexMexicoParserTest` con factory smoke tests
  - **Archivos/Módulos:** `parser-core\src\test\kotlin\com\pennywiseai\parser\core\bank\AmexMexicoParserTest.kt`
  - **Acción:** JUnit 5 `@TestFactory` + `ParserTestUtils.runTestSuite` con `ExpectedTransaction` (amount, type CREDIT, currency MXN, merchant, accountLast4) por cada sample de F1, incluyendo casos negativos (OTP, promo, SMS rechazado). **Obligatorio**: `runFactoryTestCase` con el sender supuesto `AMEXMX` (a confirmar — ver Pregunta 2) para probar la resolución por factory (el escenario del shadowing) y un caso de sender compartido en inglés que debe seguir resolviendo al parser de India.
  - **Verificación:** `./init.sh parser` — suite nueva verde sin romper la existente.
- [ ] **[F3-T2]** Suite `BBVAMexicoParserTest` con factory smoke tests
  - **Archivos/Módulos:** `parser-core\src\test\kotlin\com\pennywiseai\parser\core\bank\BBVAMexicoParserTest.kt`
  - **Acción:** Igual patrón: cada variante de F1 (débito, crédito, SPEI, abono TC, saldo), `ExpectedTransaction` explícito por campo que el parser llena (regla doc 05 §4), negativos. **Factory test con el sender `266845`** (el oficial verificado) + variantes con prefijo `+52`/`521`.
  - **Verificación:** `./init.sh parser` verde.

### Fase 4: Catálogos, docs y verificación de moneda
- [ ] **[F4-T1]** Regenerar catálogos de bancos
  - **Archivos/Módulos:** `docs\supported-banks.json`, README (bloque generado), `bank-samples.json`
  - **Acción:** Correr `./scripts/update-supported-banks.sh` — regenera JSON (debe reportar `totalBanks: 170`, `totalCountries: 27`, México MXN con bankCount 2), bloque README y Play listing copy. **No editar a mano** — `SupportedBanksDocTest` falla CI si drifta (doc 05 §5).
  - **Verificación:** `./init.sh parser` de nuevo — `SupportedBanksDocTest` verde con el JSON actualizado.
- [ ] **[F4-T2]** Verificar render de MXN en `CurrencyFormatter`
  - **Archivos/Módulos:** `app\src\main\java\com\pennywiseai\tracker\utils\CurrencyFormatter.kt` (solo si hace falta)
  - **Acción:** Verificar cómo renderiza MXN en una lista mixta con USD — ambos usan "$" (doc 10 §4: el repo ya resolvió esta colisión para AR$/CL$/$U con prefijos; los pesos suramericanos están en `EXPLICIT_SYMBOL_CURRENCIES`). Si MXN no está manejado, añadirlo al mismo mecanismo ("MX$" en listas mixtas) con test en `CurrencyFormatterTest` (el guard de las hard constraints 1-2).
  - **Verificación:** `CurrencyFormatterTest` verde; en modo normal `$` sigue siendo aceptable si el contexto es single-currency.
- [ ] **[F4-T3]** (Opcional) Exponer los bancos en el tester web
  - **Archivos/Módulos:** `pennywise-web\server\src\main\kotlin\Routing.kt` (registry), `bank-samples.json`
  - **Acción:** Solo si se decidió aportar samples públicos: añadir `AmexMexicoParser()`/`BBVAMexicoParser()` al registry curado de `Routing.kt` con comentario del issue (patrón documentado: *"the web tester advertises a X sample, so the /htmx/parse registry must be able to parse it too"*, doc 18 §3.1) y sus samples al `bank-samples.json`.
  - **Verificación:** Tester local de `pennywise-web` parsea los samples.

- [x] **[F4-T4]** Verificación diferida — **REFORMULADA Y CERRADA (2026-10-25)**: la verificación contra SMS reales de transacción es **inaplicable** (confirmado por el usuario y por la inspección del device: BBVA MX envía cero SMS de transacción; AMEX MX solo envió un aviso operativo negativo). El canal real de transacciones de ambos bancos es el **push de las apps**. Cierre:
  - Los 2 SMS reales del device (negativos: pago devuelto, OTP) **ya están integrados** como fixtures en las suites — eso era todo lo que el canal SMS podía aportar.
  - Los samples positivos sintéticos **permanecen** con TODO re-formulado: verificar contra el **texto real de las notificaciones push** cuando llegue la primera transacción. El pipeline ya las registra en la tabla `bank_notifications` (`BankNotificationEntity`: packageName, messageBody, processed) — ahí está la fuente de copys reales, sin necesidad de ADB.
  - Si un push real no parsea → fix del parser + test de regresión con el texto del push (anonimizado).
  - **Estado**: cerrada como "re-formulada a push"; sin trabajo pendiente de código.

## 🧪 Estrategia de Verificación Global
- **Gate del repo:** `./init.sh parser` (equivale a `:parser-core:test`, incluye `SupportedBwaysDocTest` tras F4-T1)
- **Prueba manual E2E:** con la app en emulador, inyectar los SMS de F1 vía el `SmsProbeReceiver` (debug-only, doc 07 §9) o el tester web local → verificar: transacción creada con moneda MXN, categoría auto, balance en `AccountBalanceEntity`, notificación con monto formateado `$X`, y para AMEX: cuenta tipo CREDIT (no cuenta el total gastable, doc 00 §3.2 `isLiability`).
- **Ambos sentidos del shadowing:** enviar un SMS de AMEX India (inglés) y confirmar que lo resuelve el parser de India, no el de México.

## 🔄 Plan de Rollback y Contingencias
- **Estrategia de reversión:** Todos los cambios son archivos nuevos + 2 líneas de factory + JSON regenerado — un `git revert` del commit del feature es limpio y completo. Sin migraciones de DB, sin cambios de app.
- **Contingencia AMEX shadowing:** si en CI o review aparece un caso donde el parser de India reclama un SMS de México a pesar del orden: el fallback correcto es **narrowing del `canHandle` de India** (excluir `AMEXMX` explícitamente) además del content-gate — nunca quitar el content-gate de México.
- **Garantías de datos:** ninguna — parser-core es puro; el peor caso es un SMS no reconocido (cae en `UnrecognizedSmsEntity`, flujo de reporte).

## ❓ Preguntas Abiertas y Riesgos
> **Estado final (web 2026-10-24 + verificación ADB device `R5CT422CGKH`)**: preguntas 1, 2 y 4 resueltas con evidencia directa; la matriz de canales quedó establecida por banco.

1. **✅ RESUELTA (web + device) — Canal de BBVA MX**: **cero SMS de BBVA en el dispositivo** (inbox completo enumerado; ni `266845` ni sender alfanumérico; RCS solo mensajes personales con tr_id vacío — Samsung no expone RCS business por content provider). Los avisos que el usuario recibe son **push de `com.bancomer.mbanking`** (app instalada y con importancia de notificaciones user-set DEFAULT). El sender oficial SMS sigue siendo `266845` (antifraude BBVA) para el parser best-effort; el valor inmediato para este usuario es el canal de notificaciones.
2. **✅ RESUELTA (device) — Sender de AMEX MX**: es el alfanumérico **`AMEX`** — exactamente el mismo sender que reclama el parser de India (`contains("AMEX")` + `== "AMEX"`, `AMEXBankParser.kt:22,42`). Esto ELEVA el content-gate en español de defensa a **mecanismo primario de routing** (F2-T2 actualizado). Segundo sender observado: `642639` (OTP, no-transacción) — no se reclama.
3. **⚠️ PENDIENTE — Copys positivos de transacción**: el device solo contiene 2 SMS reales de AMEX, ambos negativos (aviso de pago devuelto sin monto; OTP). Las transacciones positivas de ambos bancos siguen siendo samples sintéticos con TODO; F4-T4 (re-verificación) obligatorio.
4. **✅ RESUELTA — Nombre canónico**: **"BBVA Mexico"** y **"American Express Mexico"**, desacoplados de los homónimos de India (`bankName` alimenta reglas `BankName||Last4` y AI Access).
5. **✅ RESUELTA (device) — Apps para notification channel**: `com.bancomer.mbanking` (BBVA MX) y `com.americanexpress.android.acctsvcs.mx` (Amex App MX) — ambos instalados con notificaciones habilitadas (importance=DEFAULT, userSet). Insumos listos para `BankNotificationConfig` si se aprueba F1-T2c.
6. **⚠️ RIESGO CONFIRMADO — Sender `AMEX` compartido**: el shadowing con India no es teórico, es el estado actual del device: cualquier SMS de AMEX MX con sender `AMEX` será reclamado por ambos parsers. El orden de factory (MX antes de India) + content-gate estricto + factory smoke test con sender `AMEX` en AMBOS sentidos (español→MX, inglés→India) son obligatorios, no opcionales.
7. **⚠️ RIESGO — Samples sintéticos**: mitigado parcialmente (2 samples reales del device como fixtures negativos); los positivos siguen sintéticos hasta tráfico real en device.
