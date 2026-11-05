# Deep-dive 03 — AI Access: relay MCP (Claude/ChatGPT/dashboard → tu teléfono)

> Alcance: el subsistema completo de AI Access — relay Cloudflare Worker + Durable Object, protocolo MCP, OAuth 2.1 con pairing, sesión AI en el teléfono, ejecución de tools read-only y modelo de privacidad. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

AI Access permite que **apps de IA externas** (Claude, ChatGPT) y un **dashboard web** consulten los datos financieros del usuario — con el teléfono como única fuente de verdad. Un **Cloudflare Worker** en `ai.pennywise.zynth.dev` actúa de relay: durante una **AI session** iniciada por el usuario, el teléfono mantiene un **WebSocket outbound** hacia el relay; cada `tools/call` de la app IA viaja al Worker, este lo reenvía por el WebSocket al teléfono, el teléfono responde desde su Room, y la respuesta regresa por el mismo camino. **El relay nunca almacena ni loguea nada** de dinero. Requiere **Pro**. Diseño: ADR aceptado `docs\adr\0002-ai-access-via-worker-relay.md` (supersede el 0001).

**La afirmación de privacidad es precisa y honesta** (del ADR): *"we never store or log your data"*, **no** *"we cannot see it"*. El ADR 0001 (relay TLS-opaco que no podía leer tráfico) fue descartado porque ningún servicio de Cloudflare en el plan gratis forwardea TLS sin terminarlo (Spectrum TCP es Enterprise) y el costo de un server aparte no justificaba la ganancia marginal — el provider de la IA (Anthropic/OpenAI) ve las respuestas en cualquier diseño.

---

## 2. Topología

```
Claude / ChatGPT / Dashboard (browser)
        │  MCP Streamable HTTP (POST /mcp) + OAuth 2.1 Bearer
        ▼
Cloudflare Worker (relay\src\index.ts)  ── OAuth provider (@cloudflare/workers-oauth-provider)
        │  env.PHONE.getByName(deviceId)
        ▼
Durable Object "Phone" (relay\src\phone.ts) — uno por teléfono
        │  WebSocket outbound (mantenido por el teléfono)
        ▼
App Android (aiaccess\): AiSessionService ◄── AiTools/AiToolLogic (Room, read-only)
```

| Componente | Archivo | Rol |
|---|---|---|
| Worker principal + MCP handler + OAuth | `relay\src\index.ts` | Rutas, rate limits, consent, pairing |
| Durable Object | `relay\src\phone.ts` | Estado por teléfono (secret hash, pair code), WebSocket, dispatch de tool calls con timeout |
| Definición de tools | `relay\src\tools.ts` | 4 tools read-only (schema que se anuncia vía `tools/list`) |
| Páginas HTML (authorize/errores) | `relay\src\pages.ts` | Consent UI |
| Lado app: registro/pareo/WS | `app\...\aiaccess\AiRelayClient.kt` | Ktor + WebSockets, secret cifrado en Keystore |
| Lado app: sesión foreground | `app\...\aiaccess\AiSessionService.kt` | 15/30/60 min, reconexión, notificación |
| Lado app: ejecución | `app\...\aiaccess\AiTools.kt` + `AiToolLogic.kt` | Responde cada tool call desde Room |
| Lado app: tile + estado | `AiSessionTileService.kt`, `AiSessionState.kt` | Quick Settings + estado compartido |
| Lado app: UI | `ui\screens\settings\aiaccess\AiAccessScreen.kt` + `AiAccessViewModel.kt` | Sesión, pairing, conexiones, relay propio |
| Docs | `docs\ai-access.md`, `relay\README.md` | Guía de usuario + self-hosting |

---

## 3. Protocolo MCP (lado Worker — `mcpHandler` en `index.ts`)

- **Transport**: Streamable HTTP, JSON-RPC stateless, **solo `POST /mcp`** (405 para el resto). Cada request llega ya autenticado con token OAuth (el provider lo valida antes de despachar).
- **Métodos**: `initialize` (negocia `protocolVersion` de `["2025-06-18", "2025-03-26"]`, responde capabilities + `serverInfo {name: "pennywise"}`), `ping`, `tools/list` (devuelve `TOOLS`), `tools/call` (despacha al DO). Notificaciones JSON-RPC (sin `id`) → `202` silencioso.
- **`instructions`** que se envían al cliente IA en `initialize` (texto literal del código): acceso read-only respondido en vivo por el teléfono; montos siempre con currency code — *"never add or compare amounts in different currencies as one number"*; fechas YYYY-MM-DD locales; si una tool dice que PennyWise no está conectado, guiar al usuario y parar.
- **Rate limit**: `MCP_LIMIT` por `deviceId` → HTTP 429 con error JSON-RPC `-32000`.

## 4. Registro y autenticación del teléfono

1. **`POST /device`** (rate-limited por IP con `REGISTER_LIMIT`): genera `deviceId = randomHex(16)` y `secret = randomBase64Url(32)`; llama `Phone.init(sha256(secret))` — **el DO solo guarda el SHA-256 del secret**. Devuelve `{deviceId, secret}` al teléfono (una vez).
2. **Header de auth del teléfono** en todo lo demás: `Authorization: Device <deviceId(32 hex)>.<secret(43 chars)>` — regex estricta en `authenticateDevice`; verifica con `timingSafeEqual` contra el hash almacenado (comparación timing-safe implementada a mano en `phone.ts`).
3. **Lado app** (`DeviceSecretBox.kt`): el secret se cifra con **AES-GCM en Android Keystore** (clave no exportable) — nunca en plaintext ni en backup.
4. **`DELETE /device`**: revoca todos los grants OAuth (`revokeGrant` por cada uno) + `phone.wipe()` (cierra WebSockets con code 4001 y `deleteAll()` del storage del DO) — "olvídate de este teléfono".

## 5. Pairing (conectar una app IA)

Flujo completo (verificado en `authorize()` de `index.ts`):

1. El teléfono pide **`POST /device/pair`** (auth Device) → `newPairingCode()`: genera código de **6 dígitos** aleatorio (con padding de ceros), loop hasta que no exista en KV; guarda `pair:<code> → deviceId` en `OAUTH_KV` con **TTL 300s** y llama `Phone.issuePairCode(code, expiresAt)` (el DO borra el código anterior — **solo un código vivo por teléfono**).
2. El usuario abre el connector en Claude/ChatGPT con la URL `https://ai.pennywise.zynth.dev/mcp` → el provider redirige a `/authorize`.
3. `GET /authorize`: `parseAuthRequest` + `describeConsent` + `beginConsent` → página de consent HTML. **Caso especial**: si el cliente es `"PennyWise dashboard"` Y el redirectUri es el propio origen `/dashboard/` → `firstParty = true` (consent automático sin código).
4. `POST /authorize` con el código: valida 6 dígitos → lookup en KV → **`consumePairCode` en el DO** (decisión de diseño comentada en el código: *"KV can't do check-and-delete, so the phone's object decides which request gets the code"* — el DO corre una call a la vez, el `DELETE … RETURNING` es el check-and-consume atómico; **uso único real**) → borra la entrada KV → `approveConsent` + `completeAuthorization` con `userId = deviceId`, `props: {deviceId}` y scope.
5. UX: la página hace submit por background (`X-PennyWise-Submit`) y luego se reemplaza con el redirect — la página de login no queda en el historial del browser. Código inválido/expirado → la misma página reintenta ("Codes last 5 minutes and work once").
6. **`revokeExistingGrants`**: solo para el dashboard — un nuevo sign-in del dashboard reemplaza el grant viejo; **la misma app IA en web y teléfono son conexiones separadas**.
7. Gestión: `GET /device/connections` (lista grants con clientName), `DELETE /device/connections/:id` (revocación individual). **`PAIR_LIMIT`** por IP en el POST del código.

## 6. La línea del teléfono (WebSocket)

- **`GET /device/connect`** (auth Device) → `phone.fetch(request)`: upgrade a WebSocket. Si ya había una conexión, **la vieja se cierra con code 4000 "replaced by a newer connection"** (una línea viva por teléfono).
- **Keepalive barato**: `ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"))` — el DO responde pings **sin despertar el objeto** (no consume request del DO; importante en el modelo de pricing/limits de Cloudflare).
- **Lado app**: `AiSessionService` (foreground service, tipo `connectedDevice`) mantiene el WebSocket con reconexión automática y backoff; la sesión dura 15/30/60 min con notificación persistente con acciones **+15 min** y **Stop**. `AiSessionTileService` inicia/detiene una sesión de 30 min desde Quick Settings.
- **Fuera de sesión**: el WebSocket no existe → `callTool` responde el texto `NOT_CONNECTED` como **tool result isError** (no un error de conexión): *"PennyWise isn't connected right now. On your phone, open PennyWise → Settings → Connect Claude / ChatGPT and start an AI session… then ask again."* — decisión explícita del ADR: la IA puede explicarle al usuario qué hacer en vez de romperse.

## 7. Dispatch de un tool call (`phone.ts`)

- `callTool(name, args)`: toma el WebSocket vivo; genera `id = crypto.randomUUID()`; envía `{type: "call", id, name, arguments}`.
- **Timeout de 15s** (`CALL_TIMEOUT_MS`): si el teléfono no respondió → *"PennyWise didn't answer in time…"*. Un `send` que lanza (socket muerto) → `NOT_CONNECTED`.
- Respuesta del teléfono: `{type: "result", id, result}` → `webSocketMessage` resuelve el pending; valida la forma con `isToolResult` (guard de type: contenido ilegible → *"PennyWise sent an answer the relay couldn't read."*).
- **Lo único que persiste el DO**: dos tablas SQLite internas — `device (secret_hash)` y `pair (code, expires_at)`. Cero datos financieros.

## 8. Las 4 tools (relay anuncia, teléfono ejecuta)

Definidas en `relay\src\tools.ts` — el Worker **solo las anuncia** (`tools/list`); la ejecución es 100% del teléfono (`AiTools.kt` — *"The Worker advertises them; only the phone runs them"*). Todas llevan `annotations: {readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false}`.

| Tool | Params | Qué hace (en el teléfono) |
|---|---|---|
| `search_transactions` | `from`/`to` (YYYY-MM-DD, **requeridos**), `query`, `category`, `type` (EXPENSE/INCOME/TRANSFER/CREDIT/INVESTMENT), `account` ("HDFC ••1234" exacto), `sort` (date_desc default / amount_desc…), `limit` (1-200, default 50), `offset` | Busca en el rango con filtros; paginación |
| `spending_summary` | `from`/`to`, `group_by` (category/merchant/month/day/account/month_category), `type` (EXPENSE default), `account` | Totales agrupados; **por moneda, nunca mezcla** |
| `list_accounts` | — | Cuentas y tarjetas con último balance; números enmascarados |
| `list_subscriptions` | — | Recurrentes con monto, moneda y próxima fecha |

**Gate Pro dentro de la tool misma** (`AiTools.call`): si `!entitlementGate.isProEntitled` → resultado de error amigable: *"AI access is part of PennyWise Pro, which isn't active on this phone. Renew Pro…"*. Validaciones del lado app: fechas requeridas y parseables (`LocalDate.parse`), `to >= from`, tool desconocida → *"PennyWise doesn't know the tool X."*, excepciones → *"PennyWise couldn't read that just now. Try again."* (los errores internos no filtran detalles).

**Qué puede leer / qué nunca sale** (`docs\ai-access.md`, verificado contra `AiToolLogic.kt`):
- ✅ Transacciones (fecha, merchant, categoría, tipo, monto+moneda, banco y last-4), balances y dues de tarjetas, suscripciones activas — **toda la historia, todos los perfiles**.
- ❌ Nunca: texto SMS, números de cuenta completos, UPI/references, notas, recibos, presupuestos, goals, préstamos, settings.
- 🔒 Nada se puede modificar. Montos siempre con moneda; nunca se suman monedas (misma invariante global del repo, empujada hasta el schema y las `instructions` del relay).

## 9. Dashboard

- `GET /dashboard/` → assets estáticos servidos con **CSP estricta** (`default-src 'none'; script-src 'self'; frame-ancestors 'none'…`), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `nosniff` (comentado en el código: el dashboard muestra datos del teléfono → política dura).
- Cliente OAuth de primera parte: nombre exacto `"PennyWise dashboard"`, consent automático, redirect al propio origen; sign-in reemplaza grants previos.
- Muestra: gasto en el tiempo, categoría×mes, comparaciones con período previo, gasto por cuenta y día de semana, historial completo con filtros y **descarga CSV**, cuentas y suscripciones. Requiere sesión AI activa igual que cualquier app.

## 10. OAuth provider (config en `index.ts`)

- `@cloudflare/workers-oauth-provider` con `apiRoute: "/mcp"`, endpoints `/authorize`, `/oauth/token`, `/oauth/register` (client registration dinámico + metadata document).
- Scopes: `read` (requerido) y `offline_access`. **Access token TTL 1h; refresh token 1 año; refresh idle 90 días** — signin y refresh funcionan con el teléfono offline (el OAuth vive en el Worker).
- Grants mapean token → `deviceId` (userId) + `props.deviceId` — así el MCP handler sabe a qué DO despachar.

## 11. Self-hosting y variables

- El relay es open source; cualquier cuenta de Cloudflare puede correrlo (`relay\README.md#self-hosting`: `wrangler login`, crear el KV namespace `OAUTH_KV`, `npm run deploy`). Plan gratuito suficiente.
- **Relay propio**: en la app, "Use your own relay" permite apuntar a otra URL `https://…`. Cambiar de relay **desconecta todas las apps IA** (el deviceId/secret son por relay) — hay que reconectarlas con la nueva dirección.

## 12. Modelo de amenazas — qué protege cada pieza

| Riesgo | Mitigación |
|---|---|
| Relay lee datos financieros | Puede (termina TLS) — pero **no los guarda ni loguea**; claim verificable por código abierto. Los datos viven solo en el teléfono |
| Secret del teléfono filtrado | Se guarda **hash SHA-256** en el DO; en el teléfono va **cifrado en Keystore** (AES-GCM, no exportable); regex estricta + `timingSafeEqual` |
| Fuerza bruta del pairing code | 6 dígitos con TTL de 5 min + `PAIR_LIMIT` (por IP) + **uso único atomic** (check-and-delete en el DO) |
| Spam de registro | `REGISTER_LIMIT` por IP |
| Abuso del MCP | `MCP_LIMIT` por deviceId |
| Tool mutación | No existen — todas `readOnlyHint`, y `AiToolLogic` solo lee repos |
| Datos fuera de sesión | Sin WebSocket no hay respuestas; `NOT_CONNECTED` como texto, no error crudo |
| Pro revocado mid-session | `AiTools.call` gatea **en cada llamada** → error amigable en cada tool |
| Session zombie | Sesiones timed (15/30/60 min) + notificación con Stop; conexión reemplazada cierra la vieja (code 4000) |
| Clickjacking/XSS en dashboard | CSP estricta + DENY framing + no-referrer |
