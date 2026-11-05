# Deep-dive 04 — Cuota gratuita vs Pro: el sistema de entitlement completo

> Alcance: cómo funciona la monetización de PennyWise — el `EntitlementGate`, las dos vías de Pro (Play Billing y license key Dodo), las cuotas del free tier, el comportamiento por flavor (standard/fdroid) y cada punto exacto donde algo está gateado. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Pro se resuelve con un **OR de dos rieles independientes** — compra en Google Play (`PurchaseGateway` por flavor) **o** license key vendida por web vía Dodo (`LicenseManager`). `EntitlementGate` es la **única fuente de verdad** que consultan los feature gates: *"Feature gates depend on this gate, not on either source, so adding a rail didn't touch a single gate site"* (comentario en `EntitlementGate.kt`). El free tier tiene cuotas explícitas centralizadas en `FreeTierLimits`. F-Droid es **Pro gratis para todos** (la librería Play Billing es propietaria y F-Droid la prohíbe).

---

## 2. El gate central (`billing\EntitlementGate.kt`)

```kotlin
val resolvedProEntitlement: StateFlow<Boolean?> =
    combine(entitlementSource.isPro, entitlementSource.isInitialized,
            licenseManager.resolvedLicenseEntitlement) { play, initialized, license ->
        when {
            play || license == true -> true          // cualquier riel da Pro
            initialized && license != null -> false // Play cargó y la license no es válida → free
            else -> null                            // aún cargando (no revocar nada)
        }
    }
```

| Flujo | Significado |
|---|---|
| `isProEntitled: StateFlow<Boolean>` | `resolvedProEntitlement == true` — lo que consultan todos los gates |
| `resolvedProEntitlement: StateFlow<Boolean?>` | `null` = estado sin resolver (caches cargando) — **crítico para background jobs**: *"Null while caches load, so background schedules do not treat startup as revocation"* (p.ej. el scheduler de webhooks conserva la cadencia con `null`, cancela con `false`) |
| Valor inicial | `isPro.value \|\| licenseManager.isLicensed.value` — sin flicker `false→true` al cold start |

**Decisión de diseño documentada** (comentario del archivo): se descartó un "legacy grandfather" que auto-desbloqueara Pro para installs previos al lanzamiento de Pro, en favor del **founder SKU** (lifetime con descuento one-time): mejor trato para usuarios existentes Y la línea de ingresos queda honesta.

### 2.1 Arquitectura de capas

```
EntitlementGate (OR de ambos rieles)
        ├── EntitlementSource (interfaz) ← PurchaseGateway (interfaz)
        │       ├── standard: PlayBillingGateway (Play Billing 9)
        │       └── fdroid:   FdroidBillingGateway (stub: todo Pro)
        └── LicenseManager (license key Dodo — funciona igual en ambos flavors)
```

DI por flavor: `app\src\standard\java\...\di\BillingModule.kt` vs `app\src\fdroid\java\...\di\BillingModule.kt` — cada flavor bindea su gateway; el resto del código es agnóstico al flavor.

---

## 3. Riel 1 — Play Billing (`app\src\standard\java\...\billing\PlayBillingGateway.kt`)

Implementación **Billing 9**, single `BillingClient` por proceso (requisito de Google). Detalles verificados en código:

| Tema | Comportamiento |
|---|---|
| **Cold start sin flicker** | En construcción publica el `isPro` cacheado de DataStore antes de que resuelva la primera query real |
| **Offline policy** | Un check de Play fallido **conserva el entitlement cacheado** (nunca demota por `SERVICE_UNAVAILABLE`/`NETWORK_ERROR`): *"false positives (briefly seeing Pro after revoke) are far less harmful than false negatives"* — trade-off documentado |
| **Acknowledge** | Toda compra `PURCHASED` sin acknowledge se ack-ea de inmediato (a los 3 días sin ack Play auto-reembolsa) |
| **PENDING** | UPI Collect/efectivo: **no** otorga entitlement hasta que Play dispare el update de pago completado |
| **Catálogo** | `ProSku.SUBSCRIPTIONS` (subs) + `ProSku.ONE_TIME` (lifetime). Una suscripción con varios base plans expande a **N entradas del paywall** (flatMap); el lifetime con Discount offer activo muestra precio activo + strikethrough original (Save X%) |
| **Estado actual** | README: *"in-app purchase is currently unavailable (payments-profile verification on Google's side). Existing Play purchases keep working; new buyers use the website key"* — el gateway sigue activo para compras existentes (restore) |

## 4. Riel 2 — License key web Dodo (`billing\license\LicenseManager.kt`)

License comprada en `pennywise.zynth.dev/pro` (₹2,000 lifetime / ₹699 año / ₹149 mes), pegada en **Settings → PennyWise Pro → Have a license key?**. Independiente de Play; funciona igual en ambos flavors (en F-Droid el UI simplemente nunca ofrece el campo).

| Mecanismo | Detalle (verbatim del código) |
|---|---|
| **Límite de activación** | 1 dispositivo por key (configurado en el producto Dodo) |
| **`ActivationOutcome`** | `Activated` / `InvalidKey` / **`ActiveElsewhere`** (la UI ofrece "Move to this device") / `Offline` |
| **Mover de teléfono (2 rutas)** | (a) **Backup restore transfiere** la activación: primero activa aquí y solo si Dodo responde limit-reached desactiva la instancia que venía en el backup — *"so two restores of one backup can't both hold Pro"* y *"a transient failure never leaves the old phone revoked with nothing activated here"*; (b) **`moveHere(key, email)`**: llama al Worker `tools\license-move-worker\worker.js` que valida el email del propietario y desactiva todas las instancias de la key |
| **Adopción pendiente** | Un restore escribe la license en DataStore con `instanceId = null` → **no otorga nada** hasta que el ticker la adopte activándola en Dodo |
| **Revalidación** | `LicensePolicy.isDue(validatedAt, now)` con **ticker de 15 min** (`TICK_MS`) + re-evalúa en cada cambio de DataStore — un proceso vivo cruzando el boundary de 30/60 días igual lapsea. `Valid` → refresca `validatedAt`; `Invalid` → borra la license; `Error` → conserva cache (`LicensePolicy` maneja expiry) |
| **Threading** | Mutex declarado **antes** del StateFlow eagerly-started: el ticker llama `revalidate()` en otro thread mientras el constructor aún corría → NPE real de 2.21.0 si el orden se invierte (comentario documentado) |
| **Backup** | La key viaja **solo en backup FULL** (MASKED/ANONYMOUS la excluyen); import: *"never overwrites an existing local license"* |

`tools\license-move-worker\worker.js` (Cloudflare Worker): endpoint POST que recibe `{license_key, email}`, verifica email vs propietario en Dodo, desactiva todas las instancias (`/licenses/deactivate`) para liberar el slot. Secret `DODO_API_KEY` via env de wrangler, nunca en el repo.

## 5. F-Droid (`app\src\fdroid\java\...\billing\FdroidBillingGateway.kt`)

```kotlin
override val isPro: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
```

Todo usuario F-Droid es Pro, siempre. Justificación en el comentario: los criterios de inclusión de F-Droid prohíben Play Billing (propietaria), y su audiencia se auto-seleccionó fuera del IAP — *"making them all 'Pro' is the only sane behavior and aligns with AGPL's spirit"*. Respeta Liskov devolviendo `PurchaseResult.Unsupported` de los métodos de escritura. Consecuencia: **todas las features Pro ya vienen desbloqueadas en F-Droid/IzzyOnDroid**; comprar la key ahí es puro apoyo (y la key también funciona en el build de Play — README).

---

## 6. Cuotas del free tier (`billing\FreeTierLimits.kt`)

Archivo explícitamente diseñado para auditoría: *"Keeping these in a single file makes it cheap to audit 'what does the free tier actually offer'"*. Constantes puras; los feature modules las combinan con `EntitlementGate.isProEntitled`.

| Constante | Límite free | Pro |
|---|---|---|
| `MAX_RULES` | **3 reglas** | Ilimitado |
| `MAX_STATEMENT_IMPORTS_PER_MONTH` | **1 import de PDF/CSV por mes calendario** | Ilimitado |
| `MAX_CSV_EXPORT_ROWS_PER_MONTH` | **100 filas por mes calendario** | Ilimitado |
| `MAX_RECURRING_TEMPLATES` | **1 plantilla recurrente** (#706) | Ilimitado |
| `MAX_ACTIVE_GOALS` | **1 goal activa** (no Archived/Reached) al crear una nueva; un lapse **nunca oculta ni bloquea** goals existentes (#914) | Ilimitado |

### 6.1 Features Pro sin cuota free (todo-o-nada)

| Feature | Dónde se gatea (verificado) |
|---|---|
| **AI Access (relay MCP)** | `aiaccess\AiTools.kt` → cada tool call: sin Pro → error amigable; UI de Settings visible para todos con upgrade prompt |
| **Webhooks completos** | Crear/configurar/Test/Sync/scheduling: `WebhookSyncScheduler` (no encola), `WebhookSyncManager` (al entrar y por batch); `docs\webhooks.md` como contrato |
| **Merge de cuentas duplicadas** | ManageAccounts (Pro) |
| **Scheduled backups** (carpeta SAF) | `ScheduledFolderBackupWorker` |
| **Pro Home Card** | One-time tras 14 días + 30 transacciones (elegibilidad, no bloqueo) |

Lo que **siempre es gratis** en cualquier flavor: parsing SMS de 168 bancos, transacciones ilimitadas, analytics, presupuestos, multi-moneda, chat IA on-device (incl. descarga del modelo), backup/restore manual, widgets, reglas hasta 3, etc. — el tracking central nunca se monetiza.

---

## 7. Mapa de puntos de gateo (evidencia por feature)

| Feature | Sitio de gate | Comportamiento free |
|---|---|---|
| Crear regla 4+ | `FreeTierLimits.MAX_RULES` + `isProEntitled` en RulesViewModel/UI | Upgrade prompt |
| Import statement 2+ al mes | `MAX_STATEMENT_IMPORTS_PER_MONTH` en `presentation\statement\` | Upgrade prompt |
| CSV export > 100 filas/mes | `MAX_CSV_EXPORT_ROWS_PER_MONTH` en export flow | Upgrade prompt |
| Goal activa 2+ | `MAX_ACTIVE_GOALS` en Goals flow | Upgrade prompt; lapse no toca goals existentes |
| Webhook sync | Scheduler + Manager (§ doc 02-9) | Entrada visible, nada encola/envía |
| AI Access | `AiTools.call` por tool call + UI | Error amigable por tool; upgrade prompt |
| Fondo `aiaccess`/dashboard | Requiere sesión + Pro en cada call | — |

---

## 8. Flujos de compra (UX)

| Vía | Flujo |
|---|---|
| **Web (activa)** | `pennywise.zynth.dev/pro` → pago Dodo (cards/UPI, GST incluido) → key por email → Settings → PennyWise Pro → "Have a license key?" → paste → `activate()` (o `moveHere()` si está activa en otro dispositivo) |
| **Play (pausada para nuevos buyers)** | Paywall con `products: StateFlow<List<ProProduct>>` (SUBSCRIPTION_MONTHLY/ANNUAL + LIFETIME con precios de Play); `launchPurchase()` → BillingFlow; compras existentes se restauran solas con `refresh()` |
| **F-Droid** | No hay flujo de compra: todo desbloqueado |

---

## 9. Extender el sistema (procedimiento)

1. **Nueva feature Pro**: leer `isProEntitled` (o `resolvedProEntitlement` si el feature tiene trabajo en background donde `null` importa) + nueva constante en `FreeTierLimits` si tiene cuota. **No** acoplarse a `PurchaseGateway` ni `LicenseManager` directamente — esa es la razón de existir del gate.
2. **Nuevo riel de compra** (p.ej. otro processor): implementar `PurchaseGateway` en un source set nuevo + `BillingModule` — ningún gate site cambia (así se añadió Dodo).
3. **Ajustar cuota**: tocar solo `FreeTierLimits.kt`.
4. **Nueva SKU**: `ProSku` + configuración en Play Console (el gateway loguea warnings diagnósticos si un SKU no existe o está inactivo — *"check Play Console product ID + active state"*).
5. Gate de verificación: `./init.sh app`.
