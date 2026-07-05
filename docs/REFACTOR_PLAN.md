# NAVIX — Refactor Plan

Tailored to the actual repo (scanned Jul 2026). Drive this from Claude Code, one phase per PR.
Backend = Spring Boot multi-module Maven. Frontend = Next.js 15 App Router.

---

## 1. Diagnosis (evidence, not vibes)

The structure is **already decent** — backend is split by domain (`navix-loan`, `navix-kyc`,
`navix-iam`, …) and one module already exposes `controller / service / domain / dto / entity /
repository` + **Ports** (`VerificationPort`, `DocumentStoragePort`, `RiskPort`). So hexagonal seams
partly exist. The pain is **god classes + a type-dumping frontend + scattered business constants**,
not the module layout.

| Signal | Finding | Why it hurts |
|---|---|---|
| God service | `navix-loan/.../service/ApplicationVerificationService.java` — **1011 lines, 10 deps, ~30 methods** | 8 unrelated checks (PAN, email, address, DigiLocker, bureau, salary, penny-drop, selfie) + reporting + doc handling in one class. Untestable, unscalable. |
| God service | `service/ApplicationFlowService.java` (~700 lines) | Orchestration + state transitions fused. |
| God API file | `frontend/src/lib/api/applications.ts` — **1465 lines, 14 api objects** | `borrowerApi, staffApi, customersApi, dashboardApi, adminApi, referralApi, collectionsApi…` all dumped in one file, grouped by *type* not *feature*. |
| God component | `components/staff/live-pipeline.tsx` (45 KB), `customer-detail-dialog.tsx` (23 KB) | Data-fetching + state + rendering fused; no container/presenter split. |
| Fat pages | `dashboard/page.tsx` 21 KB, `repay/page.tsx` 19 KB | Business logic living in route components. |
| Scattered constants | Fee 10%, GST 18%, interest 1%/day, penalty 2%/day cap 30d, ≤40d, ₹10L cap, ₹1k min | Financial rules as magic numbers across BE + FE → drift risk, the "hardcoded" feeling. |
| Config not externalized | Only **7 `@Value`** across 344 java files; literals like `allowedOrigins("http://localhost:3000")`, Resend endpoint, `https://navix.demo/...` | Env-specific values baked into code. |
| Context bloat | `CLAUDE.md` = **813 lines** | Over the ~500-line budget; slows every Claude Code session. |
| Repo hygiene | `icon.png` 5.8 MB, `NAVIX Website (offline).html` 974 KB, `samplepan.json`, PDFs, JPEG at repo root | Noise; belongs in `docs/`/`assets/` or `.gitignore`. |

**Verdict:** ~70% "extract god classes + centralize constants + colocate by feature", 20% "externalize
config", 10% "repo hygiene + slim CLAUDE.md". Do **not** rewrite — the bones are fine.

---

## 2. Target architecture

### Backend (keep hexagonal, finish the job)
```
navix-<domain>/
  domain/      # entities + value objects + business rules (NO Spring, NO JPA annotations leaking out)
  application/ # use-cases (one class per use-case) + Port interfaces
  adapter/
    in/web/    # controllers = thin: parse → call use-case → map response
    out/...    # JPA repos, HTTP clients, storage — implement Ports
  config/
```
- **Strategy pattern** for the verification god class: one `VerificationStep` interface, one class per
  check (`PanVerificationStep`, `SalaryVerificationStep`, …), a registry/coordinator that runs them.
- **Split reporting out**: `overview / progress / summary` → `VerificationReportingService` (read model).
- Controllers stay thin: no business logic in `controller/`.
- Domain classes must not import Spring/JPA — enforce with ArchUnit (see guardrails).

### Frontend (feature-based, not type-based)
```
src/
  features/
    loan-application/  { api.ts, hooks/, components/, types.ts }
    verification/      { api.ts, hooks/, components/ }
    collections/  dashboard/  referral/  ...
  lib/            # cross-cutting only: api client, money, format, auth
  app/            # routes = thin; compose from features/
  components/ui/  # dumb, reusable presentational
```
- **One-way deps:** `app → features → lib`. `lib` never imports `features`; features never import each
  other's internals (only their public `index.ts`). Enforce with ESLint `import/no-restricted-paths`.
- **Container/presenter:** pages/dialogs fetch + orchestrate; rendering moves to dumb components.
- Split `lib/api/applications.ts` → per-feature `features/*/api.ts` over a shared `lib/api/client.ts`.

### Shared business rules (kills the "hardcoded" smell)
- BE: one `PricingPolicy` / `LendingTerms` config bean (fee, GST, interest, penalty, caps, tenure) —
  values from `application.yml`, injected, not literals.
- FE: mirror read-only display constants in `lib/pricing.ts` (or fetch from a `/config` endpoint so
  there's a single source of truth).

---

## 3. Guardrails — add these FIRST (before any refactor)

These make the refactor safe and stop backsliding.

1. **Characterization tests** on the hotspots you'll touch (verification service, application flow,
   money math). They pin *current* behavior so you can refactor without changing outputs.
2. **ArchUnit test** (backend): domain must not depend on Spring/JPA; controllers must not depend on
   repositories directly (only use-cases).
3. **ESLint boundaries** (frontend): `import/no-restricted-paths` for `app → features → lib` and
   no cross-feature deep imports.
4. **Slim `CLAUDE.md`** to < 300 lines of durable truth (domain money-rules table, run commands,
   invariants). Move the rest into `frontend/CLAUDE.md`, `backend/CLAUDE.md`, and lean on `dfd.md`.
5. **CI gate:** `mvn verify` + `next build` + `lint` green on every phase PR.

---

## 4. Phased roadmap (small, reviewable, ordered by risk↓ payoff↑)

| Phase | Scope | Risk | Payoff |
|---|---|---|---|
| **0. Guardrails** | Characterization tests + ArchUnit + ESLint boundaries + slim CLAUDE.md + move root junk to `docs/`/`assets/` | Low | Safety net + faster sessions |
| **1. Centralize constants** | Extract `PricingPolicy` (BE) + `lib/pricing.ts` (FE); replace scattered fee/GST/interest/penalty literals | Low | Removes #1 "hardcoded" complaint |
| **2. Externalize config** | Move URLs/CORS/endpoints to `application.yml` + env; add `@Value`/`@ConfigurationProperties` | Low | Deploy-safe, no code edits per env |
| **3. Split the verification god class** | Strategy pattern: `VerificationStep` per check + coordinator + `VerificationReportingService` | Med | Biggest readability win |
| **4. Split `ApplicationFlowService`** | Separate orchestration from state-machine transitions | Med | Core flow becomes legible |
| **5. Frontend feature slices** | Break `lib/api/applications.ts` → `features/*/api.ts`; shared `lib/api/client.ts` | Med | Scalable FE structure |
| **6. Container/presenter** | Extract logic out of `live-pipeline.tsx`, `dashboard/page.tsx`, `repay/page.tsx`, big dialogs | Med | Testable, reusable UI |
| **7. Enforce + document** | Turn guardrails to CI-blocking; write ADRs for the new patterns | Low | Prevents regression |

Ship **one phase per PR**. Never combine a behavior change with a move — moves must be pure.

---

## 5. Hotspot recipes (concrete)

**A. `ApplicationVerificationService` (1011 lines) → Strategy**
- Define `interface VerificationStep { String checkType(); StepResult run(VerificationContext ctx); }`
- One implementation per method group: `PanVerificationStep`, `EmailVerificationStep`,
  `AddressVerificationStep`, `DigiLockerVerificationStep`, `BureauVerificationStep`,
  `SalaryVerificationStep`, `PennyDropVerificationStep`, `SelfieVerificationStep`.
- `VerificationCoordinator` injects `List<VerificationStep>` (Spring auto-wires all), routes by
  `checkType`, keeps `allRequiredPassed / requiredPassedCount`.
- Move `overview / progress / summary / manualDecision` → `VerificationReportingService`.
- Keep the existing Ports (`VerificationPort`, `RiskPort`, `DocumentStoragePort`) — reuse, don't rebuild.
- Guard with `ApplicationVerificationServiceTest` (already 12.9 KB) — outputs must match before/after.

**B. `lib/api/applications.ts` (1465 lines, 14 apis) → feature slices**
- Create `lib/api/client.ts` (base fetch, auth headers, error handling) once.
- Move each `xxxApi` object to `features/<domain>/api.ts` importing the shared client.
- Move `rupeesToPaise / paiseToINR / statusLabel / fileToBase64 / openDocument` → `lib/money.ts` +
  `lib/files.ts`. Re-export from old path temporarily to avoid a big-bang import churn, then delete.

**C. Fat pages/components → container/presenter**
- `live-pipeline.tsx`: extract `useLivePipeline()` hook (fetch+state) + dumb `<PipelineTable>`.
- `dashboard/page.tsx`, `repay/page.tsx`: page becomes layout + data hook; move sections to
  `features/dashboard/components/*`.

---

## 6. Paste-ready Claude Code prompts (run in your repo)

> Phase 0 — safety net
```
Read CLAUDE.md and dfd.md. Write characterization tests for ApplicationVerificationService and the
money math (fee/GST/interest/penalty) that assert CURRENT outputs. Do not change production code.
Add an ArchUnit test: com.navix..domain must not depend on Spring or jakarta.persistence.
```

> Phase 1 — centralize constants
```
Find every literal for fee(10%), GST(18%), interest(1%/day), late penalty(2%/day, cap 30d),
max tenure(40d), eligible cap(₹10,00,000), min loan(₹1,000) across backend + frontend. Introduce a
PricingPolicy config bean (values from application.yml) and lib/pricing.ts. Replace all literals.
Run the Phase 0 tests — outputs must be identical.
```

> Phase 3 — split the god class (use a subagent to review)
```
Refactor ApplicationVerificationService into a VerificationStep strategy (one class per check) plus a
VerificationCoordinator and a VerificationReportingService. Preserve public behavior; keep existing
Ports. After refactor, run ApplicationVerificationServiceTest and a diff of StepResult outputs for a
fixed fixture. Then have a second review pass check for behavior drift.
```

Repeat the pattern per phase: **gather context → change → run tests → review → commit.**

---

## 7. Tooling for the job

- **`engineering` plugin** (install): `tech-debt`, `code-review`, `architecture` (ADRs),
  `system-design`, `testing-strategy` — plus a bundled GitHub connector.
- **Built-in:** `/init` (regenerate a slim CLAUDE.md), `/code-review`, `/security-review`,
  subagents via `/agents` (one refactors, one reviews), `skill-creator` to build a repo-specific
  `navix-refactor` skill that encodes these recipes.
- **Already in repo:** `.mcp.json` wires a `code-review-graph` MCP — use it for impact analysis
  before splitting classes.

---

### The one rule
Never big-bang. Every phase: **pure move OR behavior change, never both**, guarded by a test that
proves outputs are identical. That is how a 344-file backend + 232-file frontend gets refactored
without breaking a lending platform.
