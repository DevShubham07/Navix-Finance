# Digio eMandate — platform knowledge base

Evidence-backed reference for integrating **Digio DigiCollect (NACH / eNACH eMandate)** into
DhanBoost/NAVIX. **Discovery output, not an implementation.** No Navix application code was changed
and no mandate was created, amended or cancelled while producing this.

Read [`11-open-questions-and-assumptions.md`](11-open-questions-and-assumptions.md) **before writing any
integration code** — three BLOCKER-class unknowns are still open.

## What was built from this

The first slice (registration only, at the end of the offer journey, behind the `digio-mandate` flag,
shipped disabled) was designed and planned in:

- [`../superpowers/specs/2026-10-16-digio-emandate-offer-journey-design.md`](../superpowers/specs/2026-10-16-digio-emandate-offer-journey-design.md) — the approved design
- [`../superpowers/plans/2026-10-16-digio-emandate-offer-journey.md`](../superpowers/plans/2026-10-16-digio-emandate-offer-journey.md) — the implementation plan

Debit presentation, webhooks, and mandate amendment/cancellation are explicit non-goals of that build.

Debit presentation was built afterwards, behind the separate `digio-debit` flag (V85, **shipped disabled**):
- Scheduled at **DPD 1, 3, 5, 9** by `MandateDebitScheduler` (`NAVIX_DIGIO_DEBIT_CRON`, default 10:00 Asia/Kolkata) plus a manual `POST /api/collections/loans/{loanId}/debit` for collections staff (`mandate:debit`; an executive may debit only their own cases).
- A successful debit is credited through `LoanDirectory.creditCollectionPayment` and auto-verifies — no Accountant step.
- **There is still no webhook receiver.** Outcomes come from a polling reconcile pass capped at 50 rows per run (a deliberate stopgap).
- Still not built: UPI AutoPay debit, mandate amendment/cancellation, consolidated settlement reconciliation.
- **Enabling this in production: [`14-production-enablement-runbook.md`](14-production-enablement-runbook.md)** — the ordered steps, and which of them is the one that cannot be skipped (presenting one real debit by hand).
- **Re-presentation is a terminal denylist, not an allowlist.** `DebitService.TERMINAL_MARKERS` lists the reasons another attempt cannot help (account closed/blocked/invalid, mandate cancelled/expired/not found, payer deceased, duplicate reference) and matches them against the failure code **and** the failure reason, so a numeric NPCI code still resolves through its text. Everything else — insufficient funds, a technical decline, a code we have never seen — is re-presented. This replaced an allowlist of three guessed strings, which failed closed: against numeric codes it matched nothing, so the DPD 3/5/9 presentations never fired and a loan was silently never collected after its first attempt. `MAX_ATTEMPTS` (4), `MAX_PRESENTATIONS_PER_ROW` (3) and the usable-mandate check bound the downside.
- **Still unverified:** no debit has ever been presented, so Digio's real `failure_code` vocabulary remains unobserved. Review the denylist against the first live failure. Also unverified: that Digio dedupes on a repeated `unique_key` (the recovery path's double-charge backstop) and the `by_crn` 404 shape.
- **UNVERIFIED (unchanged):** there is no webhook receiver; outcomes are read by the polling reconcile sweep, capped at 50 rows per pass.

## Contents

| File | Covers |
|---|---|
| [`00-environment-and-navix-overview.md`](00-environment-and-navix-overview.md) | Tool inventory, exploration limits, Navix architecture + where a mandate would land |
| [`01-digio-platform-map.md`](01-digio-platform-map.md) | The enterprise console, what our account has, navigation inventory |
| [`02-emandate-business-workflows.md`](02-emandate-business-workflows.md) | Mandate methods, end-to-end lifecycle, registration vs debit vs settlement |
| [`03-digio-api-catalog.md`](03-digio-api-catalog.md) | All 19 endpoints, one record each |
| [`04-authentication-and-environments.md`](04-authentication-and-environments.md) | HTTP Basic, hosts, IPs, config template |
| [`05-webhooks-and-asynchronous-processing.md`](05-webhooks-and-asynchronous-processing.md) | Event catalog, signature, retries, the receiver Navix must build |
| [`06-errors-retries-and-edge-cases.md`](06-errors-retries-and-edge-cases.md) | Failure taxonomy, retry safety, NPCI reject codes |
| [`07-security-and-data-governance.md`](07-security-and-data-governance.md) | Credentials, PII, consent, audit, compliance flags |
| [`08-navix-integration-architecture.md`](08-navix-integration-architecture.md) | Proposed adapter, schema, endpoints, jobs — file by file |
| [`09-state-mapping.md`](09-state-mapping.md) | Digio state → Navix state, with confidence |
| [`10-testing-and-sandbox-plan.md`](10-testing-and-sandbox-plan.md) | Test matrix; nothing that touches a live mandate |
| [`11-open-questions-and-assumptions.md`](11-open-questions-and-assumptions.md) | Prioritised unknowns + readiness checklist |
| [`12-integration-implementation-plan.md`](12-integration-implementation-plan.md) | Phased plan, rollout, rollback, observability |
| [`13-live-verification-log.md`](13-live-verification-log.md) | **Calls actually executed against production** — and where the live API contradicts the spec |
| [`workflows/mandate-lifecycle.md`](workflows/mandate-lifecycle.md) | Sequence + state diagrams |
| [`api-reference/`](api-reference/README.md) | Digio's own OpenAPI 3.0.1 specs, pruned, with provenance |
| [`evidence/source-register.md`](evidence/source-register.md) | Every source, with date and confidence |
| [`glossary.md`](glossary.md) | NACH/NPCI/Digio terms, as the vendor uses them |

## Evidence labels

Every non-obvious claim carries one. They are not decoration — a `VERIFIED` endpoint can be coded
against, an `INFERRED` one cannot.

| Label | Means |
|---|---|
| **VERIFIED** | From Digio's own OpenAPI spec, its official documentation, our own authenticated console, or — strongest — an executed live call logged in [`13-`](13-live-verification-log.md) |
| **OBSERVED** | Seen in the console UI or network traffic, not confirmed by documentation |
| **INFERRED** | Reasoned from the above; plausible, unconfirmed |
| **UNKNOWN** | Not established. Says how to find out |
| **CONFLICT** | Sources disagree. Both recorded |

## Which documentation is authoritative

1. **`api-reference/schemas/*.json`** — Digio's published OpenAPI 3.0.1 specs. Highest authority for
   paths, fields, types, constraints and enums.
2. **https://documentation.digio.in/** — authoritative for prose: flows, webhooks, environments, error codes.
3. **The enterprise console** (`enterprise.digio.in`) — authoritative for *our account's* configuration
   only, never for what the API supports in general.
4. Everything else (third-party API catalogues, support-desk articles) — background only, never cited
   as VERIFIED here.

The specs' own `servers[]` block shipped `http://localhost:8080`, a build placeholder. We replaced it
with the hosts from Digio's environments page and recorded that substitution in each file's
`info.x-provenance`.

## For a coding agent picking this up

1. Read `11-` first. If a BLOCKER is still open, stop and ask — don't guess a credential model or a
   mandate shape.
2. **Read [`13-`](13-live-verification-log.md) first — it corrects the rest of this knowledge base.** Six spec contradictions found by executing two endpoints (field casing, required-ness, enum vocabulary, PII masking, undocumented fields), **and one outright error of ours**: §10 withdraws the "registration takes days" claim that `02-`, `08-` and `09-` were built on. An `auth_mode: API` mandate issues its UMRN in minutes. Treat no timing or shape claim as settled unless `13-` confirms it.
3. Treat `03-` + `api-reference/schemas/` as the contract. Do not invent an endpoint because REST
   usually has one; Digio has no list-mandates API and no idempotency key.
4. `08-` and `09-` are proposals, reviewed by nobody yet. They are the starting point for a design
   discussion, not a spec to implement verbatim.
5. Nothing here authorises calling a mutating Digio endpoint. Every `create_form`,
   `registration_cancel` and `nach_debit` call on production moves real money or files a real mandate
   with NPCI.

## Maintaining this

Digio versions by path (`/v3/`), publishes no changelog we found, and adds response fields without
notice. When you touch the integration: re-fetch the specs (`documentation.digio.in/swagger/<name>.json`,
names listed in `api-reference/README.md`), diff them, and update `03-` and `09-` together — a new
state enum value is the change most likely to break us silently.
