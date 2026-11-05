# Deep-dive 09 — Backup/Restore: contrato de formato, niveles de privacidad y estrategias de import

> Alcance: el archivo `.pennywisebackup` — schema y compatibilidad (la regla del default), wire-format, niveles FULL/MASKED/ANONYMOUS, recibos embebidos, las 3 estrategias de import con sus dedups, el restore como transferencia de licencia, y el auto-backup programado. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

El backup es **un solo archivo JSON** (`"PennyWise Backup v1.3"`; se importa cualquier `v1.x`) que empaqueta la base de datos completa (~27 tablas), preferencias y — opcionalmente — recibos en Base64. La compatibilidad eterna descansa en **una regla**: *todo campo serializado debe tener default Kotlin*, doblemente custodiada por `BackupSchemaGuardTest` (wrapper models + baseline de campos requeridos de entidades Room). El bug histórico #414 (Gson ignoraba los defaults → un backup viejo crasheaba el restore entero) es la razón de existir de todo el diseño. Hay **3 niveles de privacidad** en export (MASKED redacta SMS y cuentas, ANONYMOUS borra merchant/descripción) y **3 estrategias** de import (REPLACE_ALL / MERGE / SELECTIVE) con dedup por uid/nombre/banco y remap de foreign keys fila por fila.

---

## 2. Anatomía de los archivos (`data\backup\`)

| Archivo | Rol (verbatim de `docs\backup-format.md`) |
|---|---|
| `BackupModels.kt` | Wrapper schema (`PennyWiseBackup`, `DatabaseSnapshot`, `PreferencesSnapshot`, …) — todos `@Serializable`, todos defaulted |
| `BackupSerializers.kt` | Instancia `backupJson` + serializers custom de `BigDecimal`/`LocalDate`/`LocalDateTime` — la única fuente de verdad de serialización |
| `BackupExporter.kt` | Lee DB → construye `PennyWiseBackup` → `backupJson.encodeToString` |
| `BackupImporter.kt` | `decodeFromString` → inserta (MERGE o REPLACE_ALL), remapea foreign keys, salta filas malas |
| `BackupReceipts.kt` | Recibos: colección (export), path-safety check + restore (import) |
| `data\database\entity\*.kt` | Las entidades Room se serializan **directamente** (cada una `@Serializable`) |

## 3. El contrato de compatibilidad

```kotlin
// BackupSerializers.kt
backupJson = Json {
    ignoreUnknownKeys = true   // forward: keys nuevas → apps viejas las tiran
    coerceInputValues = true   // null en campo defaulted → default
    encodeDefaults = true
}
```

- **Backward (backup viejo → app nueva)**: key ausente → kotlinx llena el **default** en vez de lanzar.
- **Forward (backup nuevo → app vieja)**: key desconocida → `ignoreUnknownKeys` la tira.
- **#414 documentado**: Gson construía objetos con `Unsafe.allocateInstance` e **ignoraba los defaults de Kotlin**; un backup viejo sin `subscription.billingCycle` deserializaba `null` bajo tipo non-null y el insert crasheaba **abortando el restore completo**. kotlinx honra defaults.

### 3.1 Wire-format (no cambiar sin bump de versión)

| Tipo | JSON |
|---|---|
| `BigDecimal` | string decimal plano (`"499.00"`, `toPlainString()`) |
| `LocalDateTime` / `LocalDate` | ISO (`"2024-01-02T10:15:30"` / `"2024-01-02"`) |
| enums | nombre de la constante (`"EXPENSE"`) |
| keys de entity | property Kotlin (camelCase, `merchantName`) |
| keys de wrapper | `@SerialName` explícito (snake_case, `merchant_mappings`) |

Los serializers custom emiten **byte-identical** al Gson legacy — los backups cruzan el switch Gson→kotlinx en ambas direcciones.

### 3.2 El guard de CI (`BackupSchemaGuardTest`) — dos capas

1. **Wrapper models**: todo campo con default.
2. **Entidades**: el conjunto de campos *required* (sin default) está **pinneado a un baseline** (`KNOWN_REQUIRED_ENTITY_FIELDS`). Añadir un campo non-defaulted a una entity backed-up **hace fallar el build** con instrucción de añadir default (o, rara vez, actualizar el baseline para un campo garantizado en todo backup existente).

**Los NO negociables** (de `docs\backup-format.md`): nunca quitar un default; nunca renombrar property de entity sin `@SerialName("oldKey")` (un rename **silenciosamente dropea** el dato de backups viejos); nunca cambiar la representación wire de BigDecimal/fechas/enums; nunca campo non-defaulted en wrapper.

## 4. Niveles de privacidad (export)

`ExportPrivacy` enum (`BackupModels.kt:432`): `FULL`, `MASKED` (*"Mask sensitive data like account numbers"*), `ANONYMOUS` (*"Remove merchant names and descriptions"*). Evidencia de `BackupExporter.kt`:

- **MASKED** (líneas 139-141): `smsBody = "[REDACTED]"` + ocultación de cuentas.
- **ANONYMOUS** (líneas 143-146): `smsBody = "[REDACTED]"` + merchant/descripción reemplazados.
- **Merchant aliases fuera en MASKED/ANONYMOUS** (línea 170): los aliases son labels custom del usuario — *"exactly what MASKED/ANONYMOUS is meant to hide (#583)"*.
- **Solo FULL exporta** (líneas 114-115): license key (Dodo), ignored accounts, loans, recurring templates, goals (#914), split shares (#346), `upi_id` — todo lo que es texto del usuario o apunta a ids.

## 5. Recibos (v1.3, #839)

```json
"receipts": [ { "path": "receipts/receipt_1727.jpg", "data_base64": "/9j/4AAQ..." } ]
```

- `path` = el `receiptPath` de la transacción (relativo a `filesDir`); `data_base64` = bytes Base64. Ambos defaulted a `""`.
- **Export**: solo con opt-in y **solo FULL**. Dos toggles separados: manual (`export_include_receipts`, default **on**) y Automatic Folder Backup (`folder_backup_include_receipts`, default **off** — corre diario). Archivos faltantes se saltan con log.
- **Import trata `path` como untrusted**: solo `receipts/<name>` que resuelva canónicamente **dentro** de `filesDir/receipts`. Paths absolutos, `..`, directorios anidados y backslashes se rechazan (path traversal). Un recibo se escribe **solo si una transacción en la DB post-import apunta a ese path** (un merge restaura exactamente las fotos de los rows que conservó) y un archivo existente **nunca se sobreescribe**. Corre **después** del commit de la transacción de DB.
- Una transacción cuyo archivo falta muestra "Receipt photo not included in this backup" en su detalle.

## 6. Estrategias de import

### 6.1 REPLACE_ALL

Borra y re-inserta con los ids del backup (padres antes que hijos — foreign-key ordering). Goals: clear cascading + re-insert; una contribution cuyo `transactionId` no restauró **conserva el dinero pero pierde el link**.

### 6.2 MERGE — dedups idempotentes

| Entidad | Key de dedup |
|---|---|
| Goals / contributions | `uid` (local con mismo uid **gana**) — repetir el merge es no-op |
| Budgets | nombre |
| Cards | bank + cardLast4 |
| Subscriptions | merchant + amount |
| Merchant aliases/mappings | merchant_name |
| Profiles | id → **remap map** que usan transacciones y balances |

Remaps de FK: `goalId` → goal local; `transactionId` por el mismo mapa old→local de tag cross-refs (rows nuevos + duplicados por hash); link sin mapeo → **se limpia** (no se adivina). Shares de cada transacción tocada se **capan a su monto**. Un goal local uid-matched que ahora tiene **otra moneda** → sus contributions del backup se saltan y se cuentan — *"amounts are never converted implicitly"*.

### 6.3 SELECTIVE

Subconjunto de tablas elegido por el usuario (mismo motor de MERGE).

### 6.4 Split bills (#346) — la import más defensiva

- Toda transacción se inserta con `lentSharesTotal = null`; los shares se restauran **solo sobre un loan insertado por este import** (ese loan dimensionó su principal con el share incluido).
- El bill debe seguir siendo: gasto unlinked, en la moneda del loan, con cupo en su monto, sin split previo con esa persona, bajo el límite de 5 personas. Cualquier otro share se salta y cuenta.
- **Los loans jamás se tocan en import** — un share saltado deja la deuda en el loan restaurado: *"a restore can't erase money someone owes"*. Totales reconstruidos de sus rows después.

### 6.5 Resiliencia

`insertEachCounting` **salta y cuenta** cada fila individual que falle (aplicado a TODOS los loops de insert en ambas estrategias) — el usuario ve "N rows could not be imported". Batch inserts de snapshots envueltos en try/catch por tabla. **Excepción deliberada**: `importProfilesAndBuildMap` es estricto — construye el remap de profiles del que dependen transacciones/balances; un fallo ahí **debe** salir a la luz.

### 6.6 Preferencias

Se importan con las reglas ya vistas: license key local nunca sobrescrita, `upi_id` solo se llena si falta, ignored accounts aditivo.

## 7. Backup automático (Pro)

- `ScheduledFolderBackupWorker` (doc 00 §3.4): export periódico a **carpeta SAF** (tree URI) elegida por el usuario vía `folder/FolderBackupWriter.kt`; guarda timestamp del último backup; reintenta si falla la escritura. Gate Pro (`EntitlementGate`).
- **Android no participa**: `android:allowBackup="false"` + `dataExtractionRules` — el backup es 100% de la app.
- **Webhooks NO viajan** en el backup (perfiles + headers con credenciales quedan fuera por diseño — `docs\webhooks.md`): recrear tras restore.

## 8. Restore como transferencia de licencia

`LicenseManager.restore(key, previousInstanceId)` (doc 04 §4): el importer escribe la license **sincrónicamente dentro de la transacción Room** (con `instanceId = null` → no otorga nada aún), y el trabajo de red corre después en application scope: intenta activar **primero**; solo si Dodo responde limit-reached desactiva la instancia del backup — dos restores del mismo backup no pueden mantener Pro ambos, y un fallo transitorio no deja al teléfono viejo revocado sin nada activado acá. Un ticker de 15 min reintenta la adopción pendiente.

## 9. Procedimiento: cambiar el formato con seguridad

1. **Columna nueva en entity**: default Kotlin + `@ColumnInfo(defaultValue=…)` + migración Room. `BigDecimal`/`LocalDate`/`LocalDateTime` → `@Contextual`; enum → `@Serializable`. Cero cambios de importer.
2. **Tabla nueva**: entity `@Serializable` → `List<NewEntity> = emptyList()` con `@SerialName` en `DatabaseSnapshot` → poblar en `BackupExporter` + insertar en `BackupImporter` (FK ordering) → contador en `BackupStatistics` (defaulted a 0).
3. **Campo nuevo en wrapper**: siempre con default (el guard lo exige).
4. **Test de regresión obligatorio**: decodificar un **fixture JSON viejo-shape** (string crudo sin tu key nueva) y asertar el default aplicado — *"that's the only thing that protects real users' old files"*.
5. Correr: `./gradlew :app:testStandardDebugUnitTest --tests "com.pennywiseai.tracker.data.backup.*"` (o `./init.sh app`).

Antes de tocar entities o `data/backup/`, usar el subagent **backup-maintainer** (convención de `CLAUDE.md`).
