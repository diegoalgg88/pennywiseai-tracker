# Deep-dive 18 — `pennywise-web`: tester de parseo, long-tail SEO y captura de feedback

> Alcance: el sitio web de PennyWise (`pennywise.zynth.dev`) — el tester público de parsing SMS (HTMX + el mismo `parser-core` de la app), las landing pages long-tail por banco/país generadas del catálogo, el hub de guías, la página Pro, el pipeline de reportes de parsing (Postgres) y el deploy en Cloudflare Containers. Evidencia: `pennywise-web\server\src\main\kotlin\Routing.kt`, `SupportedBanks.kt`, `SmsReportService.kt`, `feedback\*` y la exploración inicial. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

`pennywise-web` es la superficie de **adquisición + soporte** del producto: (a) un **tester de parseo público** que corre el MISMO `parser-core` que la app — el usuario pega un SMS y ve qué extraería PennyWise, sin instalar nada; (b) **landing pages SEO por banco y por país** (168 páginas de banco + 26 de país) generadas del catálogo `supported-banks.json` — *"we can never publish a page for a bank we don't actually parse"*; (c) el destino del flujo "Report a parsing problem" de la app (los SMS fallidos se envían a `/api/report`); (d) guías de uso y la página Pro (checkout Dodo). Stack: **Ktor + kotlinx.html + HTMX** (server-rendered, cero SPA framework), desplegado como **Cloudflare Container** detrás de un Worker con Hono.

---

## 2. Arquitectura

| Pieza | Archivo | Rol |
|---|---|---|
| Servidor | `pennywise-web\server\src\main\kotlin\Application.kt` | Boot Ktor (Netty), Java 21 |
| Rutas | `Routing.kt` (124 líneas) | Todas las páginas + endpoint HTMX |
| Vistas server-rendered | `ui\ParseViews.kt`, `ui\BankViews.kt`, `ui\GuideViews.kt`, `ui\ProViews.kt`, `ui\RoadmapViews.kt`, `ui\FeedbackViews.kt`, `ui\SharedComponents.kt` | kotlinx.html builders |
| Catálogo | `SupportedBanks.kt` | Carga `supported-banks.json` del classpath + samples |
| Reportes | `SmsReportService.kt` + `SmsReportModels.kt` | Persistencia de SMS reportados |
| Feedback | `feedback\FeedbackService.kt`, `FingerprintService.kt`, `FeedbackRouting.kt` | Feedback general con fingerprint anti-spam |
| Parse API | `ParseModels.kt` | Modelos del tester |
| DB | `Databases.kt` | Exposed: PostgreSQL en prod, H2 en dev |
| Edge | `src\worker.js` + `wrangler.jsonc` | Hono + `@cloudflare/containers`, Durable Object `PENNYWISE_CONTAINER`, ruta `pennywise.zynth.dev` |
| Imagen | `Dockerfile` | Multistage: Gradle build → Temurin JRE 21 Alpine |
| Deploy | `scripts\deploy.sh` | `bun install` + `wrangler deploy` |

## 3. Rutas (`Routing.kt:58-101`, verificado)

| Ruta | Qué sirve |
|---|---|
| `GET /` y `/tools/parse` | Página del tester (`respondParsePage`) |
| `POST /htmx/parse` | **El corazón**: recibe `sender` + `smsBody` + `timestamp` (form-encoded), parsea y devuelve un **snippet HTML** (`renderParseResult`) — HTMX hace swap inline, sin JS custom |
| `GET /pro` | Página de licencias (precios Dodo, doc 04 §8) |
| `GET /guide` + `/guide/{slug}` | Hub de guías + guía individual (onboarding, home, accounts, budgets, subscriptions, analytics, chat IA, export CSV…) |
| `GET /banks` | Índice de bancos soportados |
| `GET /banks/country/{slug}` | Landing por país (`respondCountryPage`) |
| `GET /banks/{slug}` | Landing por banco (`respondBankPage`) — 404 "Unknown bank" si el slug no está en el catálogo |
| `GET /sitemap.xml` + `/robots.txt` | Generados de la lista de bancos/países (`BankViews.buildSitemap/buildRobots`) |
| `GET /static/*` | CSS/fuentes propias (Archivo, IBM Plex Mono) |
| CORS | `anyHost()` + Content-Type — el tester es embebible |

### 3.1 El registry del tester — curado, NO la factory completa

`Routing.kt:24-56` construye un **`BankParserRegistry` manual** con ~50 parsers (HDFC, SBI, ICICI, Axis, Kotak, bancos indios menores, wallets, y los añadidos con su issue al comentario: `PluxeeBankParser` #685, `NDBBankParser` #658 Sri Lanka, los 4 etíopes #716-720, los 3 de El Salvador #755-757, los 4 de Jordania #955). **Patrón documentado**: *"the web tester advertises a Pluxee sample, so the /htmx/parse registry must be able to parse it too"* — cada banco con sample en el tester necesita su parser en este registry; un banco del catálogo sin sample/parser en el tester simplemente no es testeable ahí (las páginas long-tail igual se generan del catálogo completo).

### 3.2 El flujo del reporte (loop de mejora)

1. En la app: SMS no reconocido → "Report a parsing problem" → `POST /api/report` (anonymizado).
2. En el sitio: tester muestra un parseo incorrecto → mismo endpoint vía `FeedbackRouting`.
3. `SmsReportService` persiste en Postgres (`DATABASE_URL`/`USER`/`PASSWORD`; H2 en dev) — es la cola de trabajo para **nuevos parsers y fixes** (docs 05 §1: los issues `[BANK]` nacen de estos reportes).
4. `FingerprintService` — dedup anti-spam de reportes repetidos.

## 4. Long-tail SEO — el generador de páginas (`SupportedBanks.kt`)

KDoc central del archivo: *"supported-banks.json is GENERATED from the live BankParserFactory registry by parser-core's SupportedBanksDocTest (run scripts/update-supported-banks.sh), which writes both docs/supported-banks.json and this module's resource copy and fails CI if either drifts. So every bank/country page below is guaranteed to describe a parser that actually exists."*

| Mecanismo | Detalle |
|---|---|
| **Catálogo** | `BankCatalogue` (totalBanks, totalCountries, `countries[]` con flag/emoji, currency, symbol, bankCount, banks[]) — lazy-load del classpath; falta el JSON → error de arranque con instrucción de correr el script |
| **Samples genuinos** | `BankSample` generado por `BankSamplesDocTest` — *"the landing pages can't demo an extraction the app wouldn't actually make"*: cada sample muestra un SMS real (anonimizado) + lo que el parser vivo extrajo (amount, type, merchant, last4, balance) |
| **Slugs** | `slugify("United States") → "united-states"`; `BankPage` aplana banco+país para `/banks/{slug}` |
| **Orden estable** | bancos sorteados por nombre — índice y sitemap deterministas |
| **Sitemap/robots** | construidos de la misma lista — una página nueva aparece en el sitemap el día que se regenera el catálogo |

**El circuito completo**: parser nuevo en `parser-core` → `update-supported-banks.sh` → JSON regenerado (docs + resource del server) → CI (`SupportedBanksDocTest`) bloquea el drift → deploy del sitio (`--web` en release, doc 17) → 1 landing page nueva indexable + sample en el tester. **Un banco nuevo escala adquisición automáticamente** (doc 05 §6).

## 5. Deploy (Cloudflare Containers)

- `worker.js` (Hono) recibe el tráfico en `pennywise.zynth.dev` y orquesta el contenedor `PennyWiseContainer` vía `@cloudflare/containers` + Durable Object — el contenedor corre el JAR Ktor (puerto 8080).
- `Dockerfile` multistage: build con Gradle → runtime Temurin JRE 21 Alpine.
- **Cuándo se deploya**: NO en releases rutinarios — solo con `--web` (doc 17 §3 paso 8); `bun install` requerido en `pennywise-web/` (un worktree fresh no tiene `node_modules`).
- Independencia total de la app: el sitio no habla con teléfonos ni con el relay — es marketing + tooling sobre `parser-core`.

## 6. Procedimiento: extender

1. **Nueva página de contenido** (p. ej. comparativa, FAQ SEO): view nueva en `ui\` + ruta en `Routing.kt` + entrada en sitemap si es indexable.
2. **Hacer testeable un banco nuevo en el sitio**: añadir el parser al registry manual de `Routing.kt` (con comentario del issue) Y su sample al `bank-samples.json` (regenerado por `BankSamplesDocTest`) — el tester solo anuncia lo que puede parsear.
3. **Cambiar el contrato del reporte**: `SmsReportModels.kt` es compartido con el cliente de la app — coordinar los dos lados.
4. **DB**: `Databases.kt` decide Postgres vs H2 por env (`KTOR_ENV=production`) — migraciones de Exposed al tocar el schema de reportes.
5. Gate: el server compila con `:pennywise-web` Gradle (no está en `./init.sh` — su verificación es el propio build del deploy + smoke del tester).
