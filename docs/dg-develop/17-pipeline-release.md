# Deep-dive 17 — Pipeline de release: `release.sh`, fastlane, changelogs y deploys hermanos

> Alcance: cómo sale una versión de PennyWise — el script propietario `scripts\release.sh` (única fuente de verdad de versión/changelogs), la generación de release notes con Claude Agent SDK, los artifacts por canal (APKs firmados, F-Droid, AAB a Play), el flujo conditional del relay y web, y las verificaciones previas. Basado en `docs\RELEASE.md` (contrato oficial) + exploración previa de `scripts\`. Fecha de análisis: 2026-10-24. Paths bajo `C:\Users\DiegoGzz\Documents\Programas\My-Projects\PennyWiseAI-tracker\`.

---

## 1. Resumen ejecutivo

Un release es **un solo comando** que replica localmente `.github/workflows/release.yml`: `./scripts/release.sh patch --yes` (preview con `--dry-run` primero). El script bump de versión, genera release notes **con IA** (Claude Agent SDK sobre los commits desde el último tag, con fallback a lista de commits), escribe changelogs de store + GitHub, build-ea los APKs de ambos flavors con SHA256, **commitea `chore(release): bump version to X [skip ci]`, taggea `vX` y pushea `main`**, y corta el GitHub release con los artefactos adjuntos. Hard constraint del repo: **nadie edita `versionCode`/`versionName` ni `changelogs/*` a mano** — el script es dueño de todo eso y un edit manual conflictea con el próximo release.

---

## 2. Precondiciones (el script NO las guarda por ti)

| Requisito | Detalle |
|---|---|
| **Branch correcto** | `main` actualizado y **working tree limpio** — el script commitea el bump a tu branch **actual**, taggea ese commit y pushea `main`; en otro branch (o un main stale/sucio) **taggea el commit equivocado y pushea un `main` sin el release**. Verificación: `git checkout main && git pull --ff-only && git status --porcelain` (esperado: sin output) |
| **Signing** | Keystore + credenciales wireadas igual que `release.yml` consume sus secrets de GitHub; APK unsigned = config mal montada |
| **Conventional commits** | Las release notes se generan de los mensajes de commit (`feat:`, `fix:`, `docs:`, `chore:`…) — mejores mensajes → mejor changelog automático |
| **`--play`** | Credenciales service-account de Play Console para `scripts\upload-play.sh` |
| **`--web`** | Toolchain de deploy de `pennywise-web` (bun, auth Cloudflare); corre `pennywise-web/scripts/deploy.sh` |
| **Relay** | Node 22.12+ y `npx wrangler login` una vez; deploy solo si `relay/` cambió desde el último tag |

## 3. Qué hace el script en una corrida (`scripts\release.sh`)

| Paso | Acción |
|---|---|
| 1 | Bump `versionName` (semver por keyword: `patch`/`minor`/`major`) + incrementa `versionCode` en `app\build.gradle.kts` |
| 2 | Release notes desde commits desde el último tag vía **Claude Agent SDK** (`scripts\release-notes\index.mjs`, salida JSON estructurada `{summary, highlights[]}`; fallback a commit-list en fallo o con `--no-claude`) |
| 3 | De las mismas notas escribe: `fastlane\metadata\android\en-US\changelogs\<versionCode>.txt` + `default.txt` (store) y `RELEASE_NOTES.md` (GitHub) |
| 4 | Build + rename + **SHA256** de los APKs: `assembleStandardRelease` (firmado) y `assembleFdroidRelease` (**unsigned** para IzzyOnDroid, según policy F-Droid) |
| 5 | Commit `chore(release): bump version to X [skip ci]` → tag `vX` → **push a `main`** |
| 6 | GitHub release con `gh release create`: APKs **universal + arm64-v8a + armeabi-v7a + F-Droid** + sus `.sha256` (x86/x86_64 se producen y hashean pero quedan locales) |
| 7 (opt `--play`) | `bundleStandardRelease` → `.aab` subida a Play Console como **draft** vía `scripts\upload-play.sh` |
| 8 (opt `--web`) | Deploy de `pennywise-web` a Cloudflare — **sin el flag, un release rutinario NUNCA re-despliega el sitio** |
| 9 (default) | **Relay deploy conditional**: si `relay/` cambió desde el último tag → corre los tests del relay y `npm run deploy` en `relay/` (`ai.pennywise.zynth.dev`); `--no-relay` para saltarlo |

**Seguridad del script**: un **trap** revierte el bump de versión y los cambios de changelog si la corrida se interrumpe — quedas en estado limpio.

## 4. Flags completos

| Flag | Efecto |
|---|---|
| `-y`, `--yes` | Auto-confirmar todo (no interactivo / CI) |
| `--dry-run` | Imprime plan + release notes, **cambia nada** — correr siempre primero |
| `--no-claude` | Saltar generación con IA; commit-list fallback |
| `--play` | Además: AAB + upload a Play Console como draft |
| `--web` | Además: deploy de `pennywise-web` |
| `--no-relay` | Saltar el deploy conditional del relay |

## 5. Los canales y sus diferencias

| Canal | Artefacto | Firma | Billing | Verificación extra |
|---|---|---|---|---|
| **GitHub release** | APK universal + por ABI + sha256 | Firmado (standard) | Play Billing activo | — |
| **F-Droid / IzzyOnDroid** | APK fdroid (solo ARM: `arm64-v8a`, `armeabi-v7a`) | **Unsigned** | Sin Google — Pro desbloqueado (`IS_FDROID_BUILD=true`, tip-jar) | El APK se firma downstream |
| **Google Play** | `.aab` (draft) | Firmado | Play Billing + In-App Updates/Reviews | `upload-play.sh release` (AAB) o `listing` (metadatos/screenshots vía fastlane supply) |

**F-Droid metadata**: `fastlane\metadata\android\en-US\` (título "PennyWise: SMS Expense Tracker" 30 chars, short/full description, screenshots phone/7"/10", feature graphic) + localización `es-419` etc. Los changelogs numerados por versionCode (10→107) son los que consume el **diálogo What's New** in-app: la task `copyChangelog` de `app\build.gradle.kts` copia `changelogs/<versionCode>.txt` a assets generados como `whats_new.txt`. Solo `default.txt` (rolling) se localiza via Crowdin; los numerados van en inglés.

## 6. Versioning y `versionCode`

- **SemVer**: `major` = breaking/overhaul, `minor` = features, `patch` = fixes. Estado actual: `versionName "2.23.0"`, `versionCode 107` (`app\build.gradle.kts`).
- `versionCode` es **monotónico por canal** — el F-Droid APK y el standard comparten numeración para que IzzyOnDroid detecte updates.
- La regla de hand-off: el release notes generator clasifica commits `feat:` → highlights, `fix:` → fixes; un commit mal tipado degrada el changelog de esa versión (no hay corrección manual posible sin pisar el trabajo del script).

## 7. Procedimiento del mantenedor (TL;DR operativo)

```bash
git checkout main && git pull --ff-only
git status --porcelain           # limpio
./scripts/release.sh patch --dry-run   # revisar plan + notes
./scripts/release.sh patch --yes        # (añadir --play / --web según toque)
```

Después: monitorear el GitHub release, y si hubo migración de DB en el ciclo → checklist del AVD `Api26_Migration` (doc 16 §5) **antes** de dejar rodar el update.

## 8. Procedimiento: cambiar el pipeline

1. **Nuevo canal de distribución** (p. ej. otra store): extender `release.sh` con su artifact — mantener el patrón "un comando, todo o nada" (el TL;DR de un solo comando es parte del contrato del docs).
2. **Cambiar el formato de changelog**: tocar `scripts\release-notes\index.mjs` (tiene su propio `package.json`) — el JSON schema `{summary, highlights[]}` es lo que consumen AMBOS outputs (store + GitHub); divergirlos exige separar el schema.
3. **Nueva verificación de release** (p. ej. smoke test del APK): insertarla entre el build y el commit del tag — nunca después del push (el tag ya viajó).
4. Gate de la app previo al release: `./init.sh` (parser + app) — el mismo que CI; el release script **asume** que main ya pasó el gate.
