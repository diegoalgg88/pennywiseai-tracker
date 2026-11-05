# Agent Guide → see CLAUDE.md

The full, authoritative guide for coding agents lives in
**[CLAUDE.md](CLAUDE.md)** at the repository root. **Read it before making
changes** — it covers the hard constraints, the verification gate (`./init.sh`),
and links to every topic doc.

This file exists so agents that follow the `AGENTS.md` convention find the
guide. It is intentionally a thin pointer and a *real file* (not a symlink), so
it resolves the same on every checkout mode — Windows without symlink support,
sparse checkouts, the GitHub web UI, and archive/zip exports. `CLAUDE.md` is the
single source of truth; keep guidance there, not here.

## Build Instructions

**Always build the F-Droid flavor** for development/testing — it includes the free billing gateway (`FdroidBillingGateway.isPro = true`) and is the canonical build for device testing.

```bash
# Build F-Droid debug APK
.\gradlew.bat :app:assembleFdroidDebug --no-daemon --console=plain

# Install on device
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk

# Launch
adb shell am start -n com.pennywiseai.tracker.debug/com.pennywiseai.tracker.MainActivity
```

Do NOT build `:app:assembleStandardDebug` — it requires Play Billing setup and lacks the Pro entitlement gate for AI Access features.


<system-reminder>
--- Rule from: ../../../../.qwen/rules/docs-tecnicas.md ---
- Toda doc técnica requiere: code excerpts reales + file paths + line numbers (no descripciones genéricas).
- Estructura de proyecto/feature: verificado contra el código vigente, no contra memoria de sesiones previas.
- CHANGELOG: entrada por versión con Added/Fixed/Changed + por qué (no solo qué).
- No asumir librerías — verificar en manifests/imports/vecinos antes de documentar su uso.
--- End of Rule from: ../../../../.qwen/rules/docs-tecnicas.md ---

--- Rule from: ../../../../.qwen/rules/git-commits.md ---
- Conventional Commits: `feat:|fix:|docs:|refactor:|chore:` — subject enfocado en el por qué, body con el qué.
- Git user: `Diego Gonzalez <diegoalgg88@gmail.com>`.
- **NUNCA** force push. **NUNCA** broad staging (`git add .` ❌) — stagear paths explícitos.
- **NUNCA `git stash`** en repos donde corran agentes en paralelo — muta el worktree compartido y rompe ediciones concurrentes; usar `isolation: "worktree"` para aislarse.
- Pre-commit: gate del repo (ruff/eslint/tests según proyecto) — suite verde antes de commit.
- Push/PR/deploy: SIEMPRE confirmación explícita del usuario — el silencio NO autoriza.
- Verificar `git status --porcelain` antes de stagear: separar trabajo user-owned del propio; nunca revertir cambios ajenos sin pedido.
--- End of Rule from: ../../../../.qwen/rules/git-commits.md ---
</system-reminder>