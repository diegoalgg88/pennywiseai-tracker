# Deep-dive 16 — Room: schema, migraciones y DAOs

> Alcance: la base de datos Room de la app — el catálogo de entidades, la versión de schema, el sistema de migraciones (auto vs manual, la regla `ALL_MIGRATIONS`, compatibilidad SQLite 3.18), los DAOs, y el testing de migraciones. Basado en `docs\database-migrations.md` (contrato oficial) verificado contra `PennyWiseDatabase.kt` y `DatabaseModule.kt`. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

La DB está en **schema v58** (constante `SCHEMA_VERSION`, 23 entidades registradas en `@Database`). **Toda migración — auto o manual — vive en un solo archivo** (`PennyWiseDatabase.kt`) y las manuales se registran en **un único array `ALL_MIGRATIONS`** consumido por los DOS builders (Hilt + fallback) — la regla existe por un crash real: el builder de Hilt llevaba su propia lista, omitió `MIGRATION_47_48`, y los installs en v47 crasheaban al actualizar a v48. **No hay `fallbackToDestructiveMigration()`** — una migración faltante falla ruidosamente en dev en vez de borrar silenciosamente el historial del usuario. La trampa operativa más grande: **las migraciones corren en el SQLite del teléfono** (minSdk 26 = SQLite 3.18), no en el de la JVM — sintaxis válida en todos los tests puede tumbar cada launch en Android ≤13 (#922).

---

## 2. Catálogo de entidades (verificado contra los imports de `PennyWiseDatabase.kt:36-69`)

23 tablas registradas en `@Database(entities = [...])`, `data\database\entity\`:

| Grupo | Entidades |
|---|---|
| Núcleo financiero | `TransactionEntity`, `AccountBalanceEntity`, `TransactionSplitEntity`, `TransactionGroupEntity` |
| Presupuestos | `BudgetEntity`, `BudgetCategoryEntity`, `BudgetMonthSnapshotEntity`, `BudgetCategoryMonthSnapshotEntity` |
| Metas/préstamos | `GoalEntity`, `GoalContributionEntity`, `LoanEntity`, `LoanShareEntity` |
| Recurrencia | `SubscriptionEntity`, `RecurringTransactionEntity` |
| Organización | `CategoryEntity`, `TagEntity`, `TransactionTagCrossRef` (many-to-many), `MerchantMappingEntity`, `MerchantAliasEntity`, `ProfileEntity` |
| Reglas | `RuleEntity`, `RuleApplicationEntity` |
| Entrada/IA | `UnrecognizedSmsEntity`, `BankNotificationEntity`, `ChatMessage` |
| Web | `WebhookProfileEntity`, `WebhookLogEntity`, `WebhookCursorEntity`, `WebhookDeliveredTransactionEntity` |
| Tipo | `ExchangeRateEntity`, `CardEntity` |

(Nota: el `@Database` registra las tablas Room; `MerchantAliasEntity` y `MerchantMappingEntity` llegaron por auto-migration v55→56 y columnas posteriores — el doc oficial dice "23 entities" contando las que migran activamente; los imports listan más porque incluye relations. La fuente de verdad del conteo es el schema JSON exportado.)

**DAOs** (`data\database\dao\`, provistos por `di\DatabaseModule.kt:18-42`): `TransactionDao`, `AccountBalanceDao`, `CategoryDao`, `ProfileDao`, `BankNotificationDao`, `BudgetDao`, `BudgetSnapshotDao`, `CardDao`, `ChatDao`, `ExchangeRateDao`, `LoanDao`, `TransactionGroupDao`, `MerchantMappingDao`, `MerchantAliasDao`, `RuleApplicationDao`, `RecurringTransactionDao`, `RuleDao`, `SubscriptionDao`, `TagDao`, `TransactionSplitDao`, `UnrecognizedSmsDao`, `WebhookProfileDao`, `WebhookLogDao`, `WebhookCursorDao`, `GoalDao`. Cada DAO es `@Provides` sobre el singleton de `PennyWiseDatabase`.

## 3. La regla `ALL_MIGRATIONS`

```kotlin
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_12_14, MIGRATION_13_14, /* ... */ MIGRATION_57_58
)
```

- Consumida por: el provider Hilt (`di\DatabaseModule.kt` `providePennyWiseDatabase`) y el `getInstance(context)` fallback para callers no-Hilt. **Registrar una vez, ambos paths la reciben.**
- **Nunca** pasar una lista hand-picked a `.addMigrations()` en otro sitio — es la lección del crash v47→v48.
- `SCHEMA_VERSION` es constante **top-level** para que código no-Room (p.ej. `BackupExporter` stamping backups) use el mismo número.

## 4. Las dos rutas de un bump de versión

| Ruta | Cuándo | Cómo |
|---|---|---|
| **Auto-migration** | Cambio puramente aditivo | Bump `SCHEMA_VERSION` + `AutoMigration(from, to)` en `@Database` + commit del JSON exportado por KSP (`app/schemas/...PennyWiseDatabase/<version>.json`) |
| **Manual** | Nullability flips, backfills, data moves, guards de drift | Objeto `Migration(from, to)` **+ registro en `ALL_MIGRATIONS`** |

Auto-migrations con **spec** cuando Room no infiere la intención: `Migration4To5` (delete column, `@DeleteColumn`), `Migration35To36` (drop table, `@DeleteTable`), `Migration7To8` (seed categorías en `onPostMigrate`), `Migration10To11` (backfill de balances desde transacciones), `Migration27To28` (derivar `account_type` de `is_credit_card`), `Migration43To44` (insertar profiles Personal/Business).

Patrones del archivo: `MIGRATION_13_14` es el **house pattern** — chequear `PRAGMA table_info` antes de ALTER (guards contra drift); algunos saltan versiones intermedias (`12→14`) para que dispositivos rezagados salten directo; `MIGRATION_1_2` queda como ejemplo vivo no registrado (v1→2 es auto).

## 5. Compatibilidad SQLite — la regla de oro del repo

**Las migraciones corren en el SQLite del teléfono** (verificado `docs\database-migrations.md`): minSdk 26 → SQLite **3.18.2**; Android 13 → 3.32.2; 3.39+ solo Android 14+. La JVM y los emuladores API 36+ corren SQLite nuevos — **todo test puede pasar y aun así crashear cada launch en teléfonos viejos**.

- **Incidente real (#922)**: 2.22.0 envió `HAVING` sin `GROUP BY` (requiere 3.39) en `MIGRATION_63_64`... [sic — la numeración del doc; el incidente es `HAVING` en un contexto pre-3.39] → crash en cada launch de Android ≤13, Room hacia rollback de la migración → crash de nuevo en el siguiente launch.
- **Prohibido en SQL de migración**: `HAVING` sin `GROUP BY` (3.39), `RENAME COLUMN` (3.25), `DROP COLUMN` (3.35), `UPSERT` (3.24), window functions (3.25), `RETURNING` (3.35). `MigrationSqlCompatibilityTest` guarda solo el caso `HAVING`.
- **Procedimiento de verificación** (obligatorio antes de merge/release de una migración): AVD `Api26_Migration` (Android 8.0, SQLite 3.18.2) — quick check con `sqlite3 :memory:`, upgrade real instalando el último release y encima el nuevo, pass = sin `FATAL` en logcat y `PRAGMA user_version` devuelve el nuevo `SCHEMA_VERSION`. `Api33_Migration` opcional.
- **NOT NULL**: patrón de 3 pasos — columna nullable → `UPDATE` backfill → rebuild de tabla con constraint.

## 6. Schema JSONs como contrato

- `app/schemas/` — un JSON generado por versión shipeada; se commitea SIEMPRE (KSP `room.schemaLocation` en `app\build.gradle.kts`).
- Los diffs de estos JSONs se revisan en PR con el mismo rigor que el código: definen exactamente lo que Room espera en disco tras cada hop.
- Gap conocido: `48.json` nunca se commiteó (la historia corre 47→49) — nada migra contra él, pero no es precedente.

## 7. Troubleshooting (del doc oficial)

| Error | Causa usual |
|---|---|
| "Migration didn't properly handle" | Drift: default faltante o índice que tu SQL no creó — diff contra el JSON de la versión target |
| "Cannot find the schema file" | `exportSchema = true` faltante o el arg KSP sin configurar |
| "Cannot add a NOT NULL column" | Patrones §5 — nullable → backfill → rebuild |

## 8. Procedimiento: añadir una migración (checklist consolidada)

1. Bump `SCHEMA_VERSION` (top-level).
2. Cambios de entity (recordar: todo campo backed-up con default — doc 09 §3).
3. Aditivo → `AutoMigration` (+spec si ambiguo); si no → `Migration` + **registro en `ALL_MIGRATIONS`**.
4. Build una vez → commit del JSON de schema exportado.
5. Test de upgrade contra una DB real pre-cambio (WorkManager/db regresión) + fresh install (las migraciones solo corren en DBs existentes).
6. **AVD `Api26_Migration`** — el paso que los tests JVM no pueden reemplazar (§5).
7. Gate: `./init.sh app`.
