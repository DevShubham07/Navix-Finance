# DhanBoost UI performance investigation — the 15 heaviest pages

**Date:** 2026-09-16 · **Branch:** `claude/bold-johnson-5fbhje` · **Scope:** investigation and recommendations only. No production code, database, AWS resource or application data was modified. The only live actions were read-only GETs against `dhanboost.com` and the ALB with the admin login supplied for this exercise (forced login revoked the other session as instructed; the session was logged out at the end of each pass).

---

## 0. Executive summary

The console is not uniformly slow, and the database is not the primary problem. The evidence points at four things, in this order:

1. **Two pathological endpoints.** `GET /api/applications/telecalling` takes **13–15 s** on the backend and returns **9,658 rows / 2.1 MB**; the `/staff/telecalling` page polls it **every 15 s**, so one open telecaller tab keeps the backend continuously busy, and two tabs make each request take **27–33 s** (measured). The legacy `GET /api/customers` (full book) takes **4.4–6.8 s** and returns **9,748 rows / 7.5 MB**; the staff dashboard refetches it **every 60 s for every role** and blocks its first paint on it, and `/staff/collections` fetches it on every mount for an export button. Both are algorithmic (per-row query loops and unbounded `findAll()` + serialization), not data-volume or index problems. The same per-row loop sits behind the ADMIN "All applications" register.
2. **Geography.** Every BFF call runs in Vercel's default region **iad1 (US-East)** (`x-vercel-id: iad1::iad1::…` on every response, no `vercel.json`, no `preferredRegion` in any of the 41 route handlers) while the ALB/ECS/RDS are in **ap-south-1 (Mumbai)**. Measured from a US client the cheapest backend call is 238 ms end-to-end via the ALB and ~300 ms via the BFF. For a user in India the same call pays user→US-East plus US-East→Mumbai: an estimated **~0.45–0.5 s of pure network per call**, on pages that make 10–16 calls on mount and 30–75 polls per minute.
3. **Polling cadence on large payloads.** 8 s polls on a 123 KB `status=ACTIVE` list (4 roles), 15 s on the 418 KB verifications overview, 15 s on the 2.1 MB telecalling list, 10 s on six dashboard queries. An ADMIN with the dashboard open moves **~782 KB/min**, 89 % of it the customer book.
4. **One latent N+1 shared by six list endpoints.** `RepaymentService.outstandingForAll` batches the verified-payment sum but calls `SettlementDirectory.approvedSettlementAmount` **per loan** (one `collection_case` lookup, plus one `settlement` lookup where a case exists). Empirically: `/api/loans` = 366 SQL statements for 300 loans; `/api/customers` = 380 for 300 loans; `/api/collections/cases` = 126 for 60 cases. Harmless at today's 113 live loans, and the reason every "fine" list endpoint still scales with the loan book.

Everything else measured is already efficient on the backend: the status queues, credit queue, stats, decisions, trends, leads, notifications and single-record reads are **2–70 ms of backend time** with fixed, batched query counts (verified both by reading the code and by counting statements on a seeded local instance).

**What is *not* the problem, so it is not in the plan:** missing indexes on the hot paths (the schema has 152 indexes; every predicate that matters today is covered — several collector claims of "missing index" were wrong and are corrected in §2.7), response compression (already on, verified end-to-end), BFF keep-alive (Node default, on), HTTP caching (correctly absent for per-user data), schema shape (no table changes needed), and the size of the loan/payment tables (low hundreds of rows).

**Recommended order of work (detail in §5):**

| Phase | Change | Expected effect |
|---|---|---|
| 0 · config | Pin Vercel Serverless Functions to `bom1` | −0.4–0.5 s on every API call for Indian users; page loads with a 2-deep waterfall ≈ −1 s; polls become cheap |
| 1 · frontend only | Stop the collections page fetching the customer book on mount; stop the dashboard gating first paint on it and refetching it every 60 s; un-couple the 15 s telecalling poll from its 13 s response; lengthen the ACTIVE/OVERDUE and verifications polls; dedupe the customer-detail verification queries | Dashboard first paint 5–7 s → ~1 s; collections mount −700 KB and −4–7 s of background load; ADMIN dashboard bytes/min −85 %; applications console bytes/min −80 % |
| 2 · backend, contract-preserving | Batch the per-application verification lookup in telecalling / all-applications; batch the settlement lookup in `outstandingForAll`; push the telecalling status filter into SQL | Telecalling 13–15 s → ~1 s (same rows); all-applications likewise; every loan-bearing list endpoint stops scaling with loan count |
| 3 · contract additions | Aggregate endpoint for dashboard tiles/salary panel; by-ids customer lookup for the collections export; multi-date `outstanding` for `/repay`; server-side paging/default filter for the verifications overview; then retire the legacy full customer list | Removes the last whole-book fetches; `/repay` critical path −2 backend calls |
| 4 · product decisions | Telecalling scope (8,575 REJECTED rows are in the "queue"); retention of abandoned DRAFT intakes (1,063 and growing, drives the verifications overview payload) | Telecalling ~1 s → ~0.2 s; overview payload bounded |
| C · database | Three cheap forward-looking indexes, no schema change | No measurable change today; headroom as `application_event`, `loan`, `payment` grow |

---

## 1. Method and evidence

**Pages chosen (usage × data weight):** staff `dashboard`, `applications` (the one stage console), `customers`, `customers/[id]`, `verifications`, `loans`, `collections`, `collections/[loanId]`, `telecalling`, `leads`, `accounting/transactions`, `performance`, `my-decisions`; borrower `dashboard`, `repay`; plus an addendum for borrower `loan/status` because it shares the polling hook.

**Evidence sources, in order of trust:**

1. **Live measurements (2026-09-16, ADMIN session).** Every endpoint each page calls was timed via `https://dhanboost.com` (BFF) and directly via the ALB with keep-alive, two samples, gzip on; payload sizes decoded and on the wire; Vercel region header captured. Status distribution from `/api/applications/stats`. A bounded contention probe (tiny endpoints timed while 1 and then 2 telecalling reads were in flight). Table in Appendix A.
2. **Authoritative schema.** All 70 Flyway migrations were applied to a local Postgres 16 and the real index catalog dumped from `pg_indexes` (152 indexes). Used to correct the static-analysis claims.
3. **Local statement counting.** The backend (built from this branch) ran against the local database seeded with 550 applications / 300 loans / 5,395 verification rows / 2,000 leads, with `log_min_duration_statement = 0`; every endpoint was called and its SQL statements counted and grouped by shape. Table in Appendix B.
4. **Static tracing.** Seventeen page-level traces (UI → BFF route → controller → service → repository → SQL), then two deeper analyses (backend/DB and UI/BFF/infra) that re-verified every claim against the code. File:line references below are from the current checkout.

**Limits (stated so the numbers are read correctly):**

- The AWS credentials present in this environment are invalid (`InvalidClientTokenId`), so CloudWatch, RDS and ECS metrics could not be inspected; the actuator exposes only `health` and `info`, so there are no server-side latency or pool metrics. Two CloudWatch checks are listed in §5-D as the first validation step.
- The live client sits in the US. Absolute BFF numbers here are *better* than an Indian user sees; the India-side estimates are derived (RTT India↔US-East ≈ 200–250 ms) and marked as such.
- Borrower pages could not be logged into live (real OTP delivery), so their endpoints were measured through the staff proxies that hit the same backend services (`/api/loan/{id}`, `/outstanding`, `/repayments`, `/applications/{id}`, `/events`) and counted locally.
- The live database is one snapshot: 9,748 applications (DRAFT 1,063; REJECTED 8,575; ACTIVE 106; CREDIT_EXEC_PENDING 14; CLOSED 7; KYC_PENDING 6; SANCTIONED 2; DISBURSEMENT_PENDING 1), 113 loans, 121 ledger rows, 73 leads, 17 collection cases.

---

## 2. System-level findings

### 2.1 Where the time goes on a "normal" page

| Layer | Measured | Meaning |
|---|---|---|
| Backend processing, typical endpoint | 2–70 ms (ALB ttfb − 238 ms baseline; local runs 7–45 ms) | Not the bottleneck for 13 of 15 pages |
| US-East ↔ Mumbai round trip (BFF→ALB hop) | ≈ 238 ms (the cheapest ALB call from a US client with keep-alive) | Paid by every BFF call because functions run in iad1 |
| Vercel function overhead | ≈ 50–100 ms (BFF ttfb − ALB ttfb) | Normal |
| India user ↔ iad1 (estimate) | ≈ 200–250 ms RTT | Paid by every BFF call today; would be ≈ 20–40 ms to bom1 |
| Per-call total for an Indian user (estimate) | ≈ 0.5–0.6 s + backend | With bom1: ≈ 0.1–0.15 s + backend |

A staff page mounts with a two-level waterfall (`useStaffMe` → panels), so first paint ≈ 2 × per-call cost + slowest panel ≈ **1.2–1.5 s in India even when the backend spends 50 ms**. Moving the function region is therefore the one change that improves all 15 pages at once.

### 2.2 The two pathological endpoints

**`GET /api/applications/telecalling`** — `ApplicationController.telecalling` → `AdminApplicationService.listForTelecalling` (`backend/navix-loan/src/main/java/com/navix/loan/service/AdminApplicationService.java:137-176`).

- `applicationRepository.findAll()` (`:139`) loads the whole `loan_application` table (9,748 JPA entities) and filters in memory on `!REACHED_SANCTIONED.contains(status)` (`:57-61`). That set excludes REJECTED / CANCELLED / KYC_REJECTED, so **1,063 DRAFT + 8,575 REJECTED + 6 + 14 = 9,658 rows** land in the "queue" — exactly the live row count.
- Three batched lookups (`:146,149,153`), then **per application** `verification.requiredPassedCount(a.getId())` (`:160`) → `ApplicationVerificationService.requiredPassedCount` (`ApplicationVerificationService.java:2722-2733`) → `statusesWithCarriedEvidence` (`:2844-2856`): one `application_verification` query per application, plus one query and one entity load per reborrow hop (up to 5). The redundant `applicationRepo.findById` is absorbed by the persistence context.
- **Measured formula:** local 185 rows → 195 statements (4 + 185 + 6 hops); admin all-applications 550 rows → 619 statements. **Live: ≈ 4 + 9,658 ≈ 9.7k statements per request**, each a ~0.6–1 ms round trip from ECS to RDS plus hydration and 2.1 MB of JSON → 13.1 s (ALB) / 15.3 s (BFF).
- **Contention:** tiny endpoints were unaffected while 1–2 telecalling reads were in flight (median 235–250 ms vs 249 ms baseline), but the telecalling requests themselves took **14.2 s alone, 27.1 s and 33.3 s when two ran together** — one request already saturates a shared backend resource (the 1-vCPU task or the micro RDS; CloudWatch decides which). With the page polling every 15 s and React Query deduping in-flight fetches, each open telecaller tab is a permanent back-to-back request train.
- The same loop runs in `listAll` (`AdminApplicationService.java:94`) behind `/staff/admin/all-applications`.

**`GET /api/customers`** (legacy full book) — `CustomerService.list` (`CustomerService.java:290-318`) → `buildRows` (`:420-571`).

- `applicationRepository.findAll()` (`:301`), `loanRepository.findAll()` (`:305`), `ownerRepository.findAll()` (`:308`), then well-batched enrichment (profiles, bureau state chunked at 1,000, verification failures, `findCurrentStatusEnteredAt`, actor directory, officer names) and `outstandingForAll` with the per-loan settlement lookup (§2.4).
- Local: 380 statements for 400 customers/300 loans, 228 ms; **live: 9,748 rows → 7.52 MB decoded / 700 KB on the wire, 4.4 s backend, 6.8–7.1 s via the BFF.** The cost is hydrating ~9.7k entities and serializing ~9.7k 32-field DTOs, not query count.
- Its two live callers need almost none of it: `frontend/src/app/staff/dashboard/page.tsx:313-320` (tiles, segment strip, salary-days panel, "mine" filter; refetch every 60 s; part of the first-paint gate at `:429-433`) and `frontend/src/app/staff/collections/page.tsx:130-138` (used only inside the export menu, `:206-268`). The paged twin `GET /api/customers/page` (`CustomerBookQuery`, one SQL CTE) answers a 25-row page in **50 ms / 19 KB**, and `GET /api/customers/summary` answers the segment counts in **20 ms / 267 B**.

### 2.3 Polling cadence (verified constants)

| Surface | Interval | Live payload | Who |
|---|---|---|---|
| Dashboard actionable queue + performance + windowed decisions + stats (`dashboard/page.tsx:54`) | 10 s | 10 KB + 1.4 KB + 0.8 KB + 0.2 KB | all roles / ADMIN |
| Dashboard customer book, decisions, collections, trends, ledger (`:56`) | 60 s | **700 KB** + small | all roles |
| Status queues on `/staff/applications` (`components/staff/pipeline/status-queue.tsx:62,98,122,127`) | 8 s | 0.6–2.2 KB; **ACTIVE 16.8 KB gz / 123 KB** | per role |
| Repayment verify queue, sidebar collections worklist (`staff-shell.tsx:262`) | 8 s | 0.1–1.9 KB | ACCOUNTANT; collection roles + ADMIN |
| Collection payment queues (`collection-payments.tsx:187,224`) | 10 s | 0.1–0.4 KB | ADMIN, ACCOUNTANT, COLLECTION_HEAD |
| `/staff/verifications` overview + KYC_PENDING (`verifications/page.tsx:95,101`) | 15 s ×2 | **33 KB gz / 418 KB** + 1.2 KB | credit roles |
| `/staff/telecalling` (`telecalling/page.tsx:28`) | 15 s | **396 KB gz / 2.1 MB**, 13–15 s | TELECALLER, ADMIN |
| `/staff/accounting/transactions` (`transactions/page.tsx:95`) | 10 s | 7.3 KB gz / 39 KB | ACCOUNTANT, ADMIN |
| Notification bell unread count (`lib/api/notifications.ts:22`) | 20 s | 0.1 KB | everyone |
| Borrower application poll (`lib/api/live-journey.ts:34`) | 4 s until ACTIVE/terminal | ~1 KB | borrowers |

Two collector claims were wrong and are dropped: feature flags and the executive picker are `staleTime: 60_000` with **no** interval (fetched once per session). `refetchIntervalInBackground` is never set, so every poll already pauses when the tab is hidden; the figures assume the realistic case of a foregrounded console.

Steady state per open tab (from Appendix A sizes): ADMIN dashboard ≈ 66 calls/min, **782 KB/min** (89 % the customer book); ADMIN applications console ≈ 65 calls/min, ≈ 172 KB/min (73 % the ACTIVE list); COLLECTION_HEAD applications console ≈ 128 KB/min; telecalling ≈ 4 calls/min, **1.6 MB/min** and never idle; verifications ≈ 8 calls/min, 274 KB/min.

### 2.4 The shared settlement N+1

`RepaymentService.outstandingForAll` (`RepaymentService.java:339-369`) is the batched twin of `outstandingAsOf`: one `sumAmountByLoanIdInAndStatus` for all loans, then **per loan** `settlementDirectory.approvedSettlementAmount(loan.getId())` (`:364`) → `SettlementDirectoryAdapter` (`navix-collections/.../SettlementDirectoryAdapter.java:29-38`): `caseRepository.findByLoanId` and, if a case exists, `settlementRepository.findByCollectionCaseId`.

Empirical statement counts (local, 300 loans / 60 cases): `/api/loans` 366 (300 + 60 + 6); `/api/customers` 380; `/api/collections/worklist` 162 (89 rows); `/api/collections/cases` 126; `/api/collections/payments` 66; `/api/customers/page` 51 (30 of them this lookup for the 25-row page). Live loan count is 113, so today this is ~120 sub-millisecond queries per call (the register's ~100 ms backend time is mostly this); it is the one reason these endpoints are O(loans) instead of O(1) in query count.

### 2.5 Verified as already fine (no action)

Status queues (`ApplicationController.enrich`, ~6–13 fixed statements, 20–45 ms for 60–120 rows), credit queue, stats (one `GROUP BY`), decisions/summary/inspectable (1–6 statements), dashboard trends (3), leads (2–4, chunked attribution), notifications (paged, partial index), single-loan and single-application reads (1–8), `customers/page` and `customers/summary`, `server.compression` (on; identical wire bytes via ALB and BFF), BFF keep-alive (undici default), `cache: "no-store"` on the BFF (correct for per-user data), React Query deduplication of shared keys (`collections-worklist`, `my-apps`, the two `DISBURSEMENT_PENDING` panels).

### 2.6 What the local counts say about growth

Endpoints that return whole tables without a bound and will need paging before the book is 10× larger: `/api/loan/transactions` (4 statements, but every loan + every payment; 191 KB at 300/196 rows), `/api/leads` (4 statements, 832 KB at 1,803 rows — a bulk import of 10k leads would be ~4.5 MB per fetch), `/api/applications/verifications/overview` (3 statements, 108 KB at 420 rows locally, 418 KB at ~1,069 live because DRAFT abandons never leave the scope), `/api/applications/all` and `/telecalling` (after the loop fix they remain full-table serializations).

### 2.7 Index reality check

Authoritative catalog (152 indexes) versus the static traces:

| Claimed missing | Reality |
|---|---|
| `loan_application(customer_id)` | exists (`idx_loan_application_customer_id`) |
| `customer_profile(application_id)` | exists (`uq_customer_profile_application`, unique) |
| `application_event(application_id)` | exists (`idx_application_event_application_id`) |
| `application_verification(application_id)` | exists (`ix_application_verification_application` + unique `(application_id, check_type)`) |
| `payment(decided_by, decided_at)` | exists (`idx_payment_decided_by_at`) |
| `customer_owner(customer_id)` | it is the primary key |
| `loan_application(status)`, `(status, created_at)` | exist |

Genuinely absent, and worth adding only as cheap insurance (no measurable effect at today's row counts):

```sql
CREATE INDEX idx_application_event_action_at ON application_event (action, at);       -- dashboard trends: findByActionAndAtGreaterThanEqual('CREATE', since)
CREATE INDEX idx_loan_status_due_date        ON loan (status, due_date);               -- collections worklist / upcoming
CREATE INDEX idx_payment_status_paid_on      ON payment (status, paid_on);             -- dashboard trends verified payments
CREATE INDEX idx_loan_application_loan_id    ON loan_application (loan_id) WHERE loan_id IS NOT NULL;  -- findByLoanIdIn (register, worklist, ledger) on a 9.7k-row table
```

Not recommended: indexes on `LIKE '%q%'` search columns (name, mobile, pan) — a plain b-tree cannot serve a contains-search; only a trigram index would, and search is not among the measured hot paths.

---

## 3. Per-page findings

Latency figures are live medians from the US client: **BFF** = via `dhanboost.com`, **ALB** = direct; "backend ≈" subtracts the 238 ms network baseline. "India est." applies the §2.1 per-call estimate. Local SQL counts are from the seeded instance (Appendix B).

### 3.1 `/staff/dashboard`

- **Rendering flow.** Client page (`frontend/src/app/staff/dashboard/page.tsx`). `useMounted` → role → `SECTIONS[role]` (`:84-93`) decides the tiles. Up to 13 `useQuery`s fire in parallel on mount; the "Today" queue waits on `queueQuery ∧ customersQuery ∧ settlementsQuery (Head/ADMIN) ∧ casesQuery (Collection Exec)` (`:429-433`), so **first paint waits for the 7.5 MB customer book for every role**.
- **APIs (ADMIN).** `applications?status=` ×4 + `credit-queue` + `stats` + `loan/pending-repayments` + `collections/payments` + `referral` inside `fetchRoleQueue` (10 s); `decisions/summary`, `decisions?from&to` (10 s); `decisions`, `customers`, `collections/cases|settlements|payments`, `dashboard/trends`, `loan/transactions` (60 s); shell: `collections/worklist` (8 s), `notifications/unread-count` (20 s).
- **DB.** Queues 5–13 batched statements each; `stats` 1; decisions 1–6; `customers` 3 × `findAll` + ~10 batched + per-loan settlement (§2.4) + hydration of 9,748 entities; `trends` 3; `transactions` 4.
- **Tables.** `loan_application`, `customer_profile`, `loan`, `payment`, `application_event`, `staff_user`, `collection_case`, `settlement`, `collection_payment`, `customer_owner`, bureau/verification-failure tables, referral tables.
- **Latency.** `customers` **6,808 ms BFF / 4,382 ALB, 7.52 MB** (700 KB wire); every other call 290–490 ms BFF, backend ≈ 2–50 ms. India est. first paint ≈ 5–8 s (gated on the book); steady state ≈ 66 calls / 782 KB per minute.
- **Bottlenecks.** (1) whole-book fetch gating first paint and repeating every 60 s; (2) 10 s cadence on six queries; (3) `stats` fetched twice (`:369` and inside `fetchRoleQueue`); (4) all sections fetched regardless of viewport.
- **Evidence.** `page.tsx:313-320, 429-433, 54-56`; live 7.52 MB / 6.8 s; `CustomerService.java:290-318`.
- **Root cause.** Four derived views (borrower tiles, segment strip, salary-days panel, "mine" filter) computed client-side from the entire book instead of asked for as aggregates; `summary()` (267 B, 20 ms) already exists and is unused here.
- **Recommendation.** Interim (frontend): remove `customersQuery` from `queueLoading`, set its interval to 5 min, keep 10 s only for the actionable queue, 30–60 s for decisions. Target: tiles + segment strip from `customersApi.summary()`; a small aggregate for the salary-days panel (count by `salary_credit_day`, scoped like `mineCustomerIds`); delete the book fetch.
- **Expected impact.** First paint 5–7 s → ≈1 s (India ≈1.2 s); bytes/min −85 %; the 4.4 s backend job disappears from every staff session's minute.
- **Complexity.** S (interim) / M (aggregate endpoint).
- **Functional/data risks.** Tile counts must keep `mineCustomerIds` semantics (`CustomerService.java:238-252`; `bookFilter` at `:378-394` uses the same function). Salary-days needs per-customer `salaryCreditDay` → needs the aggregate (contract addition). No authorization change (`rejectDsa` / `scope()` carried over).
- **Dependencies.** Phase 1 (interval/gate) has none; Phase 3 needs the aggregate endpoint (§5-B).

### 3.2 `/staff/applications` (stage console)

- **Rendering flow.** `useStaffMe()` gates render (`applications/page.tsx:45,63`), then role-conditional panels fire in parallel: `CreditWorkbench` (credit-queue + `CREDIT_EXEC_PENDING` + executives), status queues (`SANCTIONED`, `DISBURSEMENT_PENDING` fast-track/standard sharing one key, `DISBURSEMENT_FAILED`), `AwaitingRepaymentPanel` (`ACTIVE` + `OVERDUE`), `RepaymentVerifyQueue`, two collection-payment queues, lazy `ClosedPanel` (`enabled: open`, `:370-379`).
- **APIs.** `applications?status=…` (8 s), `applications/credit-queue` (8 s), `applications/credit-executives` (once), `loan/pending-repayments` (8 s), `collections/payments?status=PENDING_HEAD|PENDING_ACCOUNTANT` (10 s), detail dialog `applications/{id}` (8 s while open). ADMIN ≈ 8–10 polls concurrently.
- **DB.** `byStatus` = one Specification query on `idx_loan_application_status_created_at` + `enrich()` (`ApplicationController.java:478-509`): profiles, loans, executive names, `findLatestEventAt` aggregate, actor directory (2), officer names → **~6–13 statements independent of N** (local: ACTIVE 120 rows = 12; CREDIT_EXEC_PENDING 60 = 6). `credit-queue` = 2 status reads + enrich. `pending-repayments` 4. `collections/payments` 1 + per-loan settlement (§2.4).
- **Tables.** `loan_application`, `customer_profile`, `loan`, `application_event`, `staff_user`, `collection_case`, `payment`, `collection_payment`, `settlement`.
- **Latency.** `ACTIVE` 437 BFF / 283 ALB (backend ≈ 45 ms), **123 KB / 16.8 KB wire**; other queues 284–490 BFF, backend ≤ 25 ms. India est. first paint ≈ 1.2–1.5 s (two-deep waterfall); ADMIN ≈ 65 calls / 172 KB per minute, COLLECTION_HEAD ≈ 128 KB/min.
- **Bottlenecks.** (1) 8 s poll on the 106-row ACTIVE list for four roles; (2) per-call network cost × 8–10 concurrent polls; (3) `enrich()` computes credit score/rating/stage times/officer names for every row on every poll.
- **Evidence.** `status-queue.tsx:62,98,122,127`; `applications/page.tsx:150-155, 218`; live sizes.
- **Root cause.** Pipeline-queue cadence (8 s) applied to a book-summary panel whose membership changes only on disbursement/repayment actions.
- **Recommendation.** ACTIVE/OVERDUE panel → 60 s with `invalidateQueries` on disbursement-accept and repayment-verify `onSuccess`; keep 8 s on the small maker-checker queues; region fix (§5-D).
- **Expected impact.** −110 KB/min per viewer; console mount ≈ −1 s in India after the region move.
- **Complexity.** S (interval) / M (mutation-driven invalidation).
- **Functional/data risks.** None to maker-checker: actionable queues keep their cadence; ACTIVE/OVERDUE is a reference view. Money figures stay compute-on-read.
- **Dependencies.** None.

### 3.3 `/staff/customers`

- **Rendering flow.** Client page in Suspense; `listQ` (`customers/page?page&size&filters`) blocks first paint, `summaryQ` (30 s stale) does not; search debounced 300 ms; all filtering/sorting/paging server-side; dialogs lazy.
- **APIs.** `customers/page`, `customers/summary` on mount/filter change; `customers/export` on demand (cap 50k); `customers/{id}` and `/failure` on click.
- **DB.** `CustomerBookQuery` CTE (`latest_app`, `latest_loan`, `prof`, `stage`, `book`): 1 count + 1 page-ids query, then hydration of ≤ size ids with ~10 batched lookups + per-loan settlement (§2.4). Local: **51 statements / 44 ms for 25 rows**; live 50 ms / 19 KB. Summary: 1 grouped query.
- **Tables.** `loan_application`, `loan`, `customer_profile`, `customer_owner`, `application_event`, `collection_case`, `settlement`, `staff_user`, bureau/verification-failure tables.
- **Latency.** page 356 BFF / 288 ALB; summary 315 / 258. India est. ≈ 0.6–0.7 s first paint.
- **Bottlenecks.** None material. The `stage` CTE scans `application_event` filtered on `to_status`/`from_status` (no index) — fine at today's size.
- **Evidence.** `CustomerBookQuery.java:51-100`, `CustomerService.java:330-340, 397-413`; commit `fa7473b`.
- **Root cause.** n/a — this is the reference implementation the other lists should copy.
- **Recommendation.** Nothing beyond the shared settlement batching and the region move.
- **Expected impact / complexity / risks / dependencies.** Marginal / — / none / §5-B.2.

### 3.4 `/staff/customers/[customerId]`

- **Rendering flow.** One primary query `["customer", id]` blocks first paint; nine tabs render lazily; Personal/Employment/Bank/Credit each fetch `applications/{appId}/verifications` under **four different query keys** (`customer-tabs.tsx:185-189, 408-411, 494-498, 599-603`); Loans tab fetches `loan/{id}/outstanding` **per loan card** (`:817-835`); Documents/Calls/Audit fetch on open.
- **APIs.** `customers/{id}` (mount); `applications/{appId}/verifications` (×4 keys); `loan/{id}/outstanding` (×loans); `customers/{id}/documents`, `/call-logs`, `/activity` (on tab).
- **DB.** `CustomerService.detail` (`:612-678`): apps, loans, profiles, events (batched) + per loan `outstandingAsOf` (3) + `paymentRepository.findByLoanId` (1) + owner + staff names. Local: 23 statements for a 3-loan customer (formula ≈ 7 + K + 4M). `activity` 17, `documents` 2, `call-logs` 1, `verifications` 1.
- **Tables.** `loan_application`, `loan`, `customer_profile`, `application_event`, `payment`, `customer_owner`, `staff_user`, `application_verification`, `collection_case`, `settlement`, `customer_call_log`, document tables.
- **Latency.** detail 424 BFF / 264 ALB (backend ≈ 26 ms, 28.6 KB); activity 427 / 309; documents 331 / 254; call-logs 308 / 238. India est. ≈ 0.6 s first paint, +0.5 s per tab that fetches.
- **Bottlenecks.** Duplicate verification fetches; per-loan outstanding fetches although the detail already carries every loan; `activity` fetched only when opened (good).
- **Evidence.** file:lines above; local counts.
- **Root cause.** Query-key fragmentation and a per-row hook in a list.
- **Recommendation.** One shared `["customer-verifications", appId]` key; either include `outstanding` per loan in the detail payload (already computed server-side in `detail`) or one batched call.
- **Expected impact.** Up to 3 + (loans − 1) fewer round trips per full visit (≈ −0.5 s each in India).
- **Complexity.** S (key) / S–M (batch).
- **Functional/data risks.** None (same endpoints, same data). If `outstanding` is embedded, it must be the same `outstandingBreakdownAsOf` result the card shows today.
- **Dependencies.** None.

### 3.5 `/staff/verifications`

- **Rendering flow.** Two parallel queries block the skeleton: `applications/verifications/overview?q` and `applications?status=KYC_PENDING`, both **15 s** (`verifications/page.tsx:95,101`); client-side grouping/search; card click fetches `verifications` + `verification-progress` in parallel.
- **APIs.** overview (15 s), KYC_PENDING queue (15 s), per-dialog `applications/{id}/verifications`, `/verification-progress`.
- **DB.** `overview` (`ApplicationVerificationService.java:3026-3084`): `findByStatusIn(DRAFT, KYC_PENDING, REVIEW_PENDING)` + `findByApplicationIdIn` + profiles = **3 statements** (already fixed once from 3 × `findAll`, see its comment at `:3019-3023`); returns every verification row for ≈1,069 undecided applications. `progress` = 2 + 2 × reborrow hops (local: 2 for a plain app, 6 for a 2-hop chain).
- **Tables.** `loan_application`, `application_verification`, `customer_profile`.
- **Latency.** overview 511 BFF / 306 ALB (backend ≈ 70 ms), **418 KB / 33 KB wire**; queue 490 / 249. India est. ≈ 0.8 s first paint; 274 KB/min steady.
- **Bottlenecks.** Payload shape: ~7 check rows × ~1,069 apps, dominated by 1,063 abandoned DRAFT intakes; refetched whole every 15 s; tallies are computed server-side over the full scope regardless of filters (`:3037-3060`), rows are filtered after (`:3065-3082`).
- **Evidence.** file:lines; live 418 KB; status distribution.
- **Root cause.** Unbounded scope (DRAFT never ages out) plus blind polling.
- **Recommendation.** Now: interval 15 s → 45 s and a refresh button. Backend: keep tallies over the full scope but return rows paged/filtered by default (`status IN (FAIL, PENDING)` or "needs attention"), and age-bound DRAFT (product decision, §5-Phase 4).
- **Expected impact.** −50 % steady bytes immediately; payload bounded after the backend change.
- **Complexity.** S / S–M.
- **Functional/data risks.** Contract change when rows become paged: the page's grouping must page through or request the narrow default; the five tally cards must keep their full-scope numbers (the current code already computes them before filtering — keep that invariant).
- **Dependencies.** Product decision on DRAFT retention for the full fix.

### 3.6 `/staff/loans` (register)

- **Rendering flow.** One query `loans?q&from&to` blocks; segment chips, sort, grouping and paging are client-side over the full register; dialog opens six queries at once (`loan/{id}`, `customers/{id}`, `outstanding`, `repayments`, `applications/{id}/events`, `collections/cases/by-loan`).
- **APIs.** `loans` (mount, search debounced); dialog ×6 on open.
- **DB.** `LoanRegisterService.list` (`LoanRegisterService.java:77-157`): `findAllForRegister(from,to)` + `findByLoanIdIn` + profiles + `outstandingForAll` + officer directory → **5 + 2 × loans** (local: 366 statements / 163 ms for 300 loans; live 113 loans ≈ 100 ms).
- **Tables.** `loan`, `loan_application`, `customer_profile`, `payment`, `collection_case`, `settlement`, `staff_user`.
- **Latency.** 499 BFF / 338 ALB (backend ≈ 100 ms), 59 KB / 7.9 KB wire. India est. ≈ 0.7 s first paint; dialog +0.5 s.
- **Bottlenecks.** Per-loan settlement lookup (§2.4); no server paging (fine at 113, needs a bound before thousands); dialog eager-fetches tabs not shown.
- **Evidence.** local statement shapes (300 × `collection_case`, 60 × `settlement`); `RepaymentService.java:364`.
- **Root cause.** Batching stops one level short in `outstandingForAll`.
- **Recommendation.** Batch settlement lookup (§5-B.2); dialog tabs `enabled: tab === …`; add `page/size` when the register exceeds a few hundred loans (contract addition).
- **Expected impact.** Backend ≈ 100 ms → ≈ 10 ms now; O(1) statements as the book grows.
- **Complexity.** S–M.
- **Functional/data risks.** "Most recently approved settlement wins" must be preserved in the batched query (`SettlementDirectoryAdapter.java:33-37`); no money-math change.
- **Dependencies.** None.

### 3.7 `/staff/collections` (DPD register)

- **Rendering flow.** `collections/worklist` (8 s, shared key with the sidebar badge, so no duplicate request) blocks; `customers` (full book) fetched in parallel on every mount but used **only** inside `<ExportMenu>` callbacks (`collections/page.tsx:130-138, 206-268`); bucket filter and paging client-side.
- **APIs.** `collections/worklist` (8 s), `customers` (mount), `auth/staff/me`; `collections/officers` per row picker (cached); assign/case dialogs on action.
- **DB.** worklist (`CollectionsService.java:193-226` → `LoanDirectoryAdapter.listCollectible` + `enrich`): loans by status/due date, apps, profiles, `outstandingForAll` (per-loan settlement), cases, officer names, actor directory → 4 + 2 × collectible loans (local 162 for 89 rows; live 11 rows ≈ 20 ms). `customers` as §2.2.
- **Tables.** `loan`, `loan_application`, `customer_profile`, `payment`, `collection_case`, `settlement`, `staff_user`, `application_event`.
- **Latency.** worklist 326 BFF / 257 ALB (backend ≈ 19 ms); **`customers` 6.8 s / 7.5 MB on every visit**. India est. ≈ 0.7 s to table, with a 7 s background download and 4.4 s of backend work each visit.
- **Bottlenecks.** The book fetch; 8 s poll duplicated across every staff page by the sidebar badge for collection roles + ADMIN.
- **Evidence.** file:lines; live sizes.
- **Root cause.** Export-only data fetched eagerly.
- **Recommendation.** `enabled: false` and `refetch()` from the export click (or a `customers/by-ids` lookup for the ≤ 17 worklist customers); sidebar badge 8 s → 30 s.
- **Expected impact.** −700 KB and −4.4 s backend per visit; badge load −75 %.
- **Complexity.** S.
- **Functional/data risks.** Export gains a short loading state; nothing visible changes. `loan(status, due_date)` index is insurance only.
- **Dependencies.** None (by-ids endpoint optional, §5-B.4).

### 3.8 `/staff/collections/[loanId]` (case detail)

- **Rendering flow.** `collections/cases/by-loan/{loanId}` (falls back to `POST openCase` on 404) → then in parallel `interactions`, `customers/{id}`, `call-logs`, `officers`, `case payments`; admin log-payment dialog fetches `loan/{id}` and `outstanding?asOf` on every date change.
- **APIs.** 6 GETs on mount (depth 2); mutations on action.
- **DB.** by-loan 8 statements (case, loan, app, profile, names, outstanding); interactions 1; payments 1; customer detail 23 (§3.4); `loan/{id}` 4–5; `outstanding` 3–4.
- **Tables.** `collection_case`, `interaction_log`, `collection_payment`, `loan`, `loan_application`, `customer_profile`, `payment`, `settlement`, `staff_user`, `customer_call_log`.
- **Latency.** All calls 300–450 ms BFF, backend ≤ 30 ms (live `by-loan/120` 404 because that loan has no case; local 12 ms). India est. ≈ 1.2–1.5 s to fully populated (two-deep waterfall).
- **Bottlenecks.** Waterfall depth 2 × network; `customers/{id}` fetched even when the credit badge is gated away; undebounced outstanding on date change.
- **Evidence.** `collections/[loanId]/page.tsx:42-46, 165-169`; `admin-log-payment.tsx:87-91`.
- **Root cause.** Network cost per call; minor over-fetch.
- **Recommendation.** Region move; gate `customers/{id}` on permission; debounce the date picker; keep the rest.
- **Expected impact.** ≈ −1 s in India; one fewer 26 ms detail call for non-privileged roles.
- **Complexity.** S.
- **Functional/data risks.** None.
- **Dependencies.** §5-D region.

### 3.9 `/staff/telecalling`

- **Rendering flow.** `useStaffMe` → `leads:manage` gate; `applications/telecalling` (**15 s**, `telecalling/page.tsx:28`) blocks the skeleton; rows split client-side into "Unallocated" and "My customers" by `ownerStaffId`, paginated client-side; each row renders a `CustomerOwnerPicker` (`credit-executives?role=` cached 60 s per role); the info dialog chains `applications/{id}` → `profile`, `credit-brief`, `loan/{id}`, `verifications`.
- **APIs.** telecalling (15 s); credit-executives (once per role); dialog ×5 on click; `customers/{id}` assign and `send-reminder` mutations invalidate the list.
- **DB.** `findAll()` over `loan_application` + 3 batched lookups + **one `application_verification` read per application** (+ one per reborrow hop) — §2.2. Local: 195 statements / 185 rows; live ≈ 9.7k statements / 9,658 rows.
- **Tables.** `loan_application`, `customer_profile`, `application_event`, `customer_owner`, `application_verification`, `staff_user`.
- **Latency.** **15,298 ms BFF / 13,055 ALB** (total 15,404 / 14,001), 2.13 MB / 396 KB wire; two concurrent requests 27.1 s and 33.3 s. India est. ≈ 16 s, and because the poll (15 s) is shorter than the response the page is never idle: ≈ 1.6 MB/min per open tab and a permanent backend job.
- **Bottlenecks.** (1) the per-application loop; (2) the scope: 8,575 REJECTED + 1,063 DRAFT rows treated as "pre-sanction"; (3) a poll shorter than the response; (4) 2.1 MB parsed client-side four times a minute; (5) the identical loop behind ADMIN `/staff/admin/all-applications` (`listAll`, `AdminApplicationService.java:94`).
- **Evidence.** `AdminApplicationService.java:57-61, 139, 160`; `ApplicationVerificationService.java:2722-2733, 2844-2862`; Appendix A/B; contention probe.
- **Root cause.** A per-row service call inside a stream over a full-table load, and a status set defined as a complement so terminal (rejected/cancelled) rows count as "still pre-sanction".
- **Recommendation.** Backend, contract-preserving: a batched `requiredPassedCounts(Collection<Long> appIds, Map<Long, LoanApplication> byId)` — one `findByApplicationIdIn` for the page, then resolve each reborrow hop level for all pending sources in one `findAllById` + one `findByApplicationIdIn` (≤ 5 levels), preserving "newer row wins / `INHERITABLE_CHECKS` only / cycle-safe"; reuse in `listAll`. Push the status filter into SQL (`findByStatusNotIn(REACHED_SANCTIONED)` → same rows via `idx_loan_application_status`). Frontend: replace the 15 s poll with manual refresh (+ 120 s background) and invalidate after assign/reminder. Product (Phase 4): an explicit allow-list `{DRAFT, KYC_PENDING, KYC_APPROVED, CREDIT_EXEC_PENDING, PRE_APPROVED}` — or, if rejected leads are meant to be re-chased after their 30-day cooling-off, that rule stated explicitly and age-bounded — which drops the list from 9,658 to ≈ 1,083 rows; optional server paging afterwards.
- **Expected impact.** 13–15 s → ≈ 1 s with the same rows (serialization-bound: the 7.5 MB customer list takes 4.4 s, this payload is 2.1 MB), → ≈ 0.2 s after the scope change; the concurrent-request pile-up and the 1.6 MB/min stream disappear; the ADMIN all-applications register gets the same fix for free.
- **Complexity.** M (batch) · S (SQL filter, poll) · S + product decision (scope).
- **Functional/data risks.** The batched resolver must reproduce `statusesWithCarriedEvidence` exactly — run the existing reborrow-chain unit tests against it and diff `completed` per application on the local dataset before switching. The scope change **removes rows visible today** (contract/behaviour change): the page has no REJECTED-specific handling, but it needs product sign-off. No authorization change (`requireTelecallingRole` untouched).
- **Dependencies.** None for the batch/filter/poll changes; the scope change needs the product decision.

### 3.10 `/staff/leads`

- **Rendering flow.** Role check via `useStaffMe`; `leads?q&callStatus` blocks first paint; client-side paging (25); search re-fetches per keystroke (`queryKey` includes `q`, no debounce found); create / disposition / outcome mutations each invalidate and refetch the whole list.
- **APIs.** `leads` (mount + filter change); mutations on action.
- **DB.** `LeadService.list` (`LeadService.java:83-145`): one Specification query (unbounded) + chunked PAN attribution join (`DsaAttributionService`, one query per 1,000 PANs) + staff names → local **4 statements**, 1,803 rows / 832 KB / 37 ms; live 73 rows / 36 KB / backend ≈ 29 ms.
- **Tables.** `lead`, `customer_profile`, `loan_application`, `staff_user`.
- **Latency.** 442 BFF / 267 ALB. India est. ≈ 0.6 s.
- **Bottlenecks.** Unbounded list with client paging (fine at 73; ≈ 4.5 MB per fetch at 10k leads after a bulk import); per-keystroke refetch; double invalidation per row edit.
- **Evidence.** local counts; `leads/page.tsx` query key.
- **Root cause.** Client-side paging design; no debounce.
- **Recommendation.** Debounce search (the customers page pattern); add `page/size` before the next bulk import (contract addition; `idx_lead_created_at`, `idx_lead_call_status` already exist).
- **Expected impact.** Small today; prevents the next telecalling-shaped regression.
- **Complexity.** S / M. **Risks.** None (additive). **Dependencies.** None.

### 3.11 `/staff/accounting/transactions`

- **Rendering flow.** `loan:activate` gate; one query `loan/transactions?q&direction&from&to` (**10 s**, `transactions/page.tsx:95`) blocks; summary cards, tabs and paging computed client-side from the returned rows.
- **APIs.** `loan/transactions` (10 s).
- **DB.** `TransactionService` (`TransactionService.java:60-138`): `loanRepository.findAll()` + `paymentRepository.findAll()` + 2 batched profile lookups → **4 statements**; period, direction and search applied in memory; one presign per proof (local HMAC). Local 496 rows / 191 KB / 177 ms; live 121 rows / 39 KB / backend ≈ 44 ms.
- **Tables.** `loan`, `payment`, `loan_application`, `customer_profile`.
- **Latency.** 327 BFF / 282 ALB; ≈ 44 KB/min steady.
- **Bottlenecks.** Whole ledger every 10 s; filters after fetch; unbounded.
- **Evidence.** file:lines; local counts.
- **Root cause.** In-memory filtering plus a queue-style poll on a ledger.
- **Recommendation.** Poll 10 s → 60 s with invalidation on verify/record; push `from/to/direction` into SQL and page (contract addition; add `payment(status, paid_on)`).
- **Expected impact.** −80 % bytes/min; cost O(period) instead of O(book).
- **Complexity.** S / M.
- **Functional/data risks.** Summary totals must be computed over the same filtered set as today (currently from the returned rows) — return totals server-side with the page; presigned proof URLs unchanged.
- **Dependencies.** None.

### 3.12 `/staff/performance`

- **Rendering flow.** One query `decisions/summary?from&to&staffId` blocks; period picker refetches; tiles/charts client-side.
- **APIs.** `decisions/summary`.
- **DB.** `DecisionHistoryService.summary` (`:221-273`): roster + `findForActorsInWindow` (`idx_application_event_actor_at`) + `countGroupByAssignedExecutive` + call-log and interaction counts (indexed) + `findDecidedByInWindow` (indexed) → **6 statements** (local 23 ms).
- **Tables.** `staff_user`, `application_event`, `loan_application`, `customer_call_log`, `interaction_log`, `payment`.
- **Latency.** 343 BFF / 287 ALB (backend ≈ 49 ms, 12 KB). India est. ≈ 0.6 s.
- **Bottlenecks.** None material; full `Payment` entities loaded for a count/sum; window events bucketed in memory.
- **Root cause / Recommendation.** n/a — leave as is; a projection for the payment tally is a later nicety.
- **Impact / complexity / risks / dependencies.** — / — / none / region only.

### 3.13 `/staff/my-decisions`

- **Rendering flow.** Three parallel queries: `decisions?staffId&from&to` (table), `decisions/summary` (stat cards), `decisions/inspectable` (team switcher, rendered only for Heads/ADMIN); client paging.
- **APIs.** 3 on mount.
- **DB.** `decisions()` = events in window + profiles + apps (batched) + per-distinct-assignee name (cached) → 3 + K; `summary` 6; `inspectable` 0–4. Local: 1 / 6 / 4 statements, 7–23 ms.
- **Tables.** `application_event`, `customer_profile`, `loan_application`, `staff_user`, `payment`, `customer_call_log`, `interaction_log`.
- **Latency.** 292 / 241; 343 / 287; 298 / 242 (backend 3–49 ms). India est. ≈ 0.6 s.
- **Bottlenecks.** Roster-wide summary fetched for single-staff roles; `inspectable` fetched for roles that never render it.
- **Recommendation.** Gate both with `enabled` by role.
- **Expected impact.** −1–2 calls on mount (≈ −0.5 s in India). **Complexity.** S. **Risks.** None. **Dependencies.** None.

### 3.14 Borrower `/dashboard`

- **Rendering flow.** `useLiveApplication` (`lib/api/live-journey.ts`): stored appId → `applications/{id}` (**4 s** until ACTIVE/terminal) and `applications/mine` (15 s while the pointer may be stale) → `loan/{loanId}` once ACTIVE; page adds `applications/{id}/profile`, `loan/{id}/repayments`, `referral/me`; shell adds `notifications/unread-count` (20 s) and shares `mine`.
- **APIs.** 6–7 on mount, depth 3 (mine/app → loan → repayments).
- **DB.** `get` 4 (app, limit override, latest event, staff name); `mine` 1 (`idx_loan_application_customer_id`); `profile` 2; `loan/{id}` 4 (loan ×2, verified sum, case); `repayments` 1 + presign per row; unread count 1 (partial index). Local 7–11 ms each.
- **Tables.** `loan_application`, `customer_limit_override`, `application_event`, `customer_profile`, `loan`, `payment`, `collection_case`, `settlement`, `notification`, referral tables.
- **Latency (same services via the staff proxies).** app 386 / 253; loan 454 / 254; outstanding 371 / 244; repayments 318 / 247 — backend ≤ 20 ms. India est. ≈ 1.5–2 s to a fully populated dashboard.
- **Bottlenecks.** Waterfall depth × per-call cost; 4 s polling during credit review; per-row presign; unread poll.
- **Evidence.** `live-journey.ts:34,42,158-162`; local counts.
- **Root cause.** Network cost per call multiplied by a three-deep chain; the hook fetches for every consumer.
- **Recommendation.** Region move (dominant). Status-aware polling (4 s only while a transition is expected, 15–30 s otherwise). Optional: one borrower-home aggregate (application + loan + outstanding + last payments) as a contract addition.
- **Expected impact.** ≈ −1.2 s in India from the region alone; −2 round trips with the aggregate.
- **Complexity.** S (poll) / M (aggregate).
- **Functional/data risks.** Any aggregate must keep ownership checks and the stripping of staff-only fields (credit score, rating); borrower-visible money figures unchanged.
- **Dependencies.** §5-D region; optional §5-B.

### 3.15 Borrower `/repay`

- **Rendering flow.** `useLiveApplication` → loanId → in parallel `loan/{id}`, `outstanding?asOf=today`, `repayments`, `payment-settings`; then `outstanding?asOf=dueDate` and `?asOf=grace` once `loan.dueDate` is known (depth 3). Pay → `storage/presign-upload` + `POST loan/{id}/repayments`.
- **APIs.** 7 GETs on mount; three are the same outstanding computation at three dates (`repay/page.tsx:58-88`).
- **DB.** loan 4; each outstanding 3–4 (×3 = 9–12); repayments 1 + presign per row; settings 1 + 2 presigns.
- **Tables.** `loan`, `payment`, `collection_case`, `settlement`, `payment_settings`, `loan_application`.
- **Latency.** Each call 318–454 ms BFF, backend ≤ 16 ms. India est. ≈ 2 s until the "pay today / on salary day / day after" cards settle.
- **Bottlenecks.** Triple outstanding; depth 3; per-row presign.
- **Evidence.** `repay/page.tsx:53-88`; `RepaymentService.outstandingBreakdownAsOf`.
- **Root cause.** Three dates fetched as three calls.
- **Recommendation.** One call returning the three canonical amounts (e.g. `outstanding/schedule` or `outstanding?asOf=a,b,c`), keeping the single-date endpoint; region move.
- **Expected impact.** −2 round trips on the critical path (≈ −1 s in India); −6–8 statements.
- **Complexity.** M.
- **Functional/data risks.** Money-facing: the three amounts must be byte-identical to today's — diff-test the new endpoint against the three calls on the local dataset; the interest-to-day-paid rule stays untouched.
- **Dependencies.** Backend endpoint (§5-B.5).

### 3.16 Addendum — borrower `/loan/status`

`applications/{id}` polled every 4 s until ACTIVE/terminal (4 statements per poll: application, limit override, latest event, staff name), `mine` every 15 s while the pointer may be stale, `events` on mount (4 statements) even though it renders inside a collapsed `<details>`, and `loan/{id}` fetched once ACTIVE but never rendered on this route (`live-journey.ts:158-162`). Recommendation: a `needsLoan=false` flag (or a lighter `useApplicationStatus`), lazy events, status-aware poll. Zero behaviour change.

---

## 4. Cross-page analysis

**Problems common to several pages**

| Problem | Pages | Evidence |
|---|---|---|
| Every API call crosses US-East ↔ Mumbai because the BFF runs in iad1 | all 15 | `x-vercel-id: iad1::iad1`; 238 ms floor per call; no `vercel.json` / `preferredRegion` |
| Whole-table endpoints paged client-side | dashboard + collections (customer book), telecalling + admin all-applications, ledger, leads, verifications overview | 7.5 MB / 2.1 MB / 418 KB live payloads; `findAll()` in `CustomerService.list`, `AdminApplicationService`, `TransactionService`, `LeadService` |
| Poll cadence faster than the data changes | applications console (ACTIVE 8 s), dashboard (10 s ×6, 60 s book), verifications (15 s), telecalling (15 s vs 13 s response), ledger (10 s), sidebar badge (8 s) | Appendix C; 782 KB/min ADMIN dashboard; 1.6 MB/min telecalling |
| Per-loan settlement lookup inside `outstandingForAll` | customers (list + page), loans, collections register/cases/payments, dashboard | Appendix B: 300 × `collection_case` + 60 × `settlement` per call |
| Per-application verification lookup | telecalling, admin all-applications | Appendix B: 195 / 619 statements |
| Eager fetch of export-/dialog-only data | collections (customer book), loans dialog (6 queries), verifications dialog, my-decisions (`inspectable`, `summary`), loan/status (`loan`) | §3 |
| Fragmented query keys / per-row hooks | customer detail (4 verification keys, per-loan outstanding), telecalling row pickers, `/repay` ×3 outstanding | §3.4, §3.15 |
| First-paint gates that include slow non-essential queries | dashboard (`customersQuery` in `queueLoading`) | `dashboard/page.tsx:429-433` |
| Dead applications accumulate with no retention rule | telecalling, verifications overview, admin all-applications, customer book | 8,575 REJECTED + 1,063 DRAFT of 9,748 |

**Changes that improve several pages at once:** the function region (all 15 pages and every poll); batching the settlement lookup (six endpoints, seven pages); removing the two customer-book fetches (dashboard for every role, collections); batching `requiredPassedCount` (telecalling and the ADMIN register); recalibrating polls (applications, dashboard, verifications, telecalling, ledger, sidebar).

**Redundant backend patterns:** two implementations of "build customer rows" (`CustomerService.list` versus `page`/`export` over `CustomerBookQuery`); batched port methods that exist but are bypassed by per-item siblings (`SettlementDirectory.approvedSettlementAmount`, `StaffDirectory.findStaff` inside loops); `requiredPassedCount` reloading the application it is handed; `listForTelecalling`/`listAll` re-implementing the enrichment that `ApplicationController.enrich()` already batches.

**Common database bottlenecks:** none caused by indexes; the per-loan settlement lookups; unbounded `findAll()` on `loan_application` (telecalling, all-applications, customer list) and on `loan`/`payment` (ledger).

**Common API bottlenecks:** list contracts without `page/size` or default filters (telecalling, all-applications, ledger, leads, verifications overview, legacy customers); the verifications overview returning every check row.

**Common UI fetching problems:** eager fetching of data that only a click needs; `refetchInterval` overrides shorter than the data's change rate; query-key fragmentation; first-paint gates on slow non-essential queries; a poll interval shorter than the endpoint's response time.

---

## 5. Implementation plan (final decision)

Ordering principle: the biggest measured wins that change no behaviour first; contract additions next; behaviour/product changes last and only with sign-off. Nothing here requires a table change.

### A. UI changes (frontend only, no contract change)

| # | Change | Evidence | Expected benefit | Complexity | Risk |
|---|---|---|---|---|---|
| A1 | `/staff/collections`: set `enabled: false` on the customer-directory query and `refetch()` from the export click (or defer until the export menu opens) | `collections/page.tsx:130-138, 206-268`; 7.5 MB / 4.4–6.8 s per visit | −700 KB and −4.4 s backend per visit for everyone who never exports | S | Export shows a short loading state; nothing else changes |
| A2 | `/staff/dashboard`: remove `customersQuery` from `queueLoading`; set its interval to 5 min; keep 10 s only on the actionable queue; 30–60 s on decisions/performance | `dashboard/page.tsx:313-320, 429-433, 54-56`; 782 KB/min | First paint 5–7 s → ≈ 1 s for every role; −85 % bytes/min | S | Tiles refresh every 5 min instead of 60 s; a "Refresh" button already exists (`:459-471`) |
| A3 | `/staff/telecalling`: replace the 15 s interval with manual refresh + 120 s background; invalidate after assign / send-reminder | `telecalling/page.tsx:28`; 13–15 s response | Ends the permanent request train; −1.6 MB/min per tab | S | Staleness is harmless here (no lifecycle authority); mutations already invalidate |
| A4 | `/staff/applications`: ACTIVE/OVERDUE panel 8 s → 60 s plus `invalidateQueries` on disbursement-accept and repayment-verify success; sidebar worklist badge 8 s → 30 s | `applications/page.tsx:218`; `staff-shell.tsx:262`; 123 KB every 8 s | −110 KB/min per viewer; −75 % badge traffic | S–M | Actionable queues keep 8 s; reference panel refreshes on the actions that change it |
| A5 | `/staff/verifications`: 15 s → 45 s with a refresh button; `/staff/accounting/transactions`: 10 s → 60 s with invalidation on verify/record | `verifications/page.tsx:95,101`; `transactions/page.tsx:95` | −50 % / −80 % bytes/min on those pages | S | Review/ledger views, no maker-checker step depends on the interval |
| A6 | `/staff/customers/[id]`: one shared verification query key; per-loan outstanding from the detail payload or one batched call; `/staff/loans` dialog and verifications dialog: `enabled` per active tab | `customer-tabs.tsx:185-189, 408-411, 494-498, 599-603, 817-835`; `loan-detail-dialog.tsx` | Up to 3 + (loans − 1) fewer round trips per visit; 3–4 fewer per dialog open | S | Same endpoints, same data |
| A7 | `/staff/my-decisions`: gate `summary` and `inspectable` by role; `/staff/leads`: debounce search; `/loan/status`: `needsLoan=false`, lazy events; borrower polls status-aware | §3.13, §3.10, §3.16, §3.14 | 1–2 fewer calls per mount each (≈ −0.5 s per call in India) | S | None |

### B. API / backend changes

| # | Change | Evidence | Expected benefit | Complexity | Risk / safety |
|---|---|---|---|---|---|
| B1 | Batch the per-application verification lookup: `requiredPassedCounts(appIds, appById)` with level-wise reborrow-chain resolution; use it in `listForTelecalling` and `listAll`; move the status filter into SQL (`findByStatusNotIn(REACHED_SANCTIONED)`) | `AdminApplicationService.java:139,160,94`; `ApplicationVerificationService.java:2722-2862`; 9.7k statements live | Telecalling and all-applications 13–15 s → ≈ 1 s with identical rows; backend saturation gone | M | Contract-preserving. Must reproduce carried-evidence semantics exactly (newer row wins, inheritable checks only, ≤ 5 hops, cycle-safe); verify with the existing chain tests and a per-application diff on the local dataset |
| B2 | Batch the settlement lookup: `SettlementDirectory.approvedSettlementAmounts(loanIds)` (one `findByLoanIdIn`, one `findByCollectionCaseIdIn`, reduce to "latest approved per case"); use it in `outstandingForAll` | `RepaymentService.java:339-369`; `SettlementDirectoryAdapter.java:29-38`; Appendix B | Six list endpoints become O(1) in statements; loans register backend ≈ 100 → ≈ 10 ms today; future-proofs the book | S–M | Contract-preserving; must keep "most recently approved wins" (`approved_at` ordering) and the existing `min(formulaOwed, settled − verified)` capping untouched; cross-module port change (`navix-common` interface + `navix-collections` adapter) |
| B3 | Aggregates for the dashboard: reuse `GET /api/customers/summary` for tiles/segment strip; add a small salary-day histogram scoped exactly like `mineCustomerIds` | `CustomerService.java:351-376, 238-252, 378-394` | Removes the last dashboard dependency on the whole book | M | Contract addition; counts must match today's client-side derivation (same scoping function) |
| B4 | `GET /api/customers/by-ids` (or `page` with an `ids` filter) reusing `CustomerService.hydrate` for the collections export enrichment | `CustomerService.java:397-413` | Export needs ≤ 17 rows, not 9,748 | S | Contract addition; carry `rejectDsa()` / `scope()` identically |
| B5 | One outstanding call for several dates (`outstanding/schedule` or `asOf=a,b,c`) for `/repay` | `repay/page.tsx:53-88` | −2 round trips on the borrower's critical path | M | Money-facing: amounts must be byte-identical; diff-test against the three calls; keep the single-date endpoint |
| B6 | Verifications overview: keep the five tallies over the full scope, return rows paged/filtered by default ("needs attention"); ledger and leads: SQL-side `from/to/direction` and `page/size` (server-side totals) | `ApplicationVerificationService.java:3037-3082`; `TransactionService.java:60-138`; `LeadService.java:83-145` | Payloads bounded as the book grows | S–M | Contract change for the overview rows (page must page or request the default) and additive for ledger/leads; totals must be computed on the same filtered set |
| B7 | Retire the legacy full `GET /api/customers` once A1/A2/B3/B4 remove its callers (or delegate it to the `export()` path with the same role gate audited) | `CustomerService.java:290-318, 344-348` | One implementation of "customer rows"; no 7.5 MB endpoint left to call by accident | S | `export()` is ADMIN-only while `list()` is not — audit before delegating; response shape unchanged |
| B8 | Product decision, then code: telecalling scope allow-list (or explicit "rejected, cooling-off over, no newer application" rule); retention/archival rule for abandoned DRAFT intakes | 8,575 REJECTED + 1,063 DRAFT in the queue and overview | Telecalling ≈ 1 s → ≈ 0.2 s; overview bounded | S + decision | Behaviour change: rows disappear from two staff views — needs sign-off; no data deletion (a status/age filter, not a purge) |

### C. Database / query / index changes

- **No schema or table changes.** Every hot predicate is already indexed; the problems are query counts and payload sizes.
- **Query rewrites** are B1, B2, B6 above (batching and SQL-side filtering) — no stored-column shortcuts: `outstanding` stays compute-on-read (`CLAUDE.md` §9/§12).
- **Indexes (cheap insurance, no measurable effect today; add in one Flyway migration, `CONCURRENTLY` in production):** `application_event(action, at)`, `loan(status, due_date)`, `payment(status, paid_on)`, `loan_application(loan_id) WHERE loan_id IS NOT NULL` (DDL in §2.7). Do **not** add b-tree indexes for the `LIKE '%q%'` search columns.

### D. Infrastructure / configuration changes

| # | Change | Evidence | Expected benefit | Risk |
|---|---|---|---|---|
| D1 | Pin Vercel Serverless Functions to **`bom1`** (`frontend/vercel.json` → `{"regions": ["bom1"]}`; the CLI deploys from `frontend/`, so that is the project root) or `export const preferredRegion = "bom1"` in the route handlers | `x-vercel-id: iad1::iad1` on every call; 238 ms floor | ≈ −0.4–0.5 s per API call for Indian users; page loads −0.8–1.2 s; polls cheap | Plan-tier support for region selection must be confirmed; roll out to a preview deployment first; middleware (Edge) and static marketing pages unaffected; cookies unaffected; users outside India get slightly slower calls |
| D2 | First validation step (read-only): CloudWatch `ECS CPUUtilization` for `navix-backend` and RDS `CPUUtilization` / `CPUCreditBalance` for `navix-finance-dev`, correlated with telecalling page usage | Two concurrent telecalling reads doubled each other's time; a burstable `db.t4g.micro` can exhaust credits under a permanent 9.7k-statement job | Tells which resource saturates and whether credit exhaustion explains "everything is slow" episodes | None |
| D3 | Expose `management.endpoints.web.exposure.include=health,info,metrics` (or Prometheus) behind the existing actuator allow-rule, and record `http.server.requests` and `hikaricp.*` | Only `health,info` exposed today; no server-side latency data exists | Ongoing per-endpoint latency and pool saturation evidence | Actuator is `permitAll` in `SecurityConfig` — restrict metrics to the VPC/ALB or a token before exposing |
| D4 | Leave ECS task count, Hikari pool (10) and RDS class as they are until after B1/B2 land; re-measure then | All non-pathological endpoints are ≤ 70 ms; pool not saturated in the probe | Avoids paying for capacity that a code fix removes | If CloudWatch (D2) shows credit exhaustion, upsizing RDS is the fallback, not the first move |

### E. Changes that should NOT be made

- **No HTTP caching on `/api/*`** (Cache-Control / ETag / shared cache): every payload is role- and identity-scoped or money-facing; the correct lever is client-side query cadence and payload shape.
- **No stored `outstanding` column and no change to money math**: compute-on-read with the penalty-aware, settlement-capped balance is a design invariant and is not a measured cost.
- **No deletion or purge of the 8,575 REJECTED / 1,063 DRAFT rows** to make lists faster: fix scope and paging; the rejection register and the cooling-off rule depend on those rows.
- **No default pagination or longer polling on the maker-checker queues** (`KYC_PENDING`, `CREDIT_EXEC_PENDING`, `SANCTIONED`, `DISBURSEMENT_*`, repayment verification): they are small by throughput, and completeness plus promptness are the point.
- **No relaxing of authorization to speed anything up**: `rejectDsa()`, `requireRole`, ownership checks and SoD replay stay exactly where they are; new endpoints copy the gates of the endpoints they replace.
- **No browser → ALB direct calls** to skip the BFF hop: the ALB is HTTP-only, cookies/JWT handling lives in the BFF, and the region fix removes the hop's cost anyway.
- **No PgBouncer / Redis / second ECS task / larger RDS as a first move**: nothing measured is capacity-bound except the two pathological endpoints, which are code fixes.
- **No b-tree indexes on `LIKE '%q%'` columns**, no speculative composite indexes beyond the four listed.
- **No rewrite of `CustomerService.list` before its callers are moved** (A1/A2/B3/B4): optimizing an endpoint nothing should call is wasted work.
- **Do not disable compression, keep-alive or `cache: "no-store"`** on the BFF: all three are correct as they are.

**Phasing:** D1 + D2 (config, days) → A1–A7 (one frontend release) → B1 + B2 + C (one backend release, with the tests below) → B3–B7 (contract additions, frontend follows) → B8 after product sign-off.

---

## 6. Validation and safety checklist

1. **Before/after on the same data.** Re-run the local statement-count harness (Appendix B) after B1/B2: expect `telecalling` and `all` ≈ 4 + hops (not 4 + N), `loans` / `customers/page` / `collections/*` constant in the loan count.
2. **Semantic diff.** For B1: for every application in the local dataset, `requiredPassedCount(id)` (old) must equal the batched value (new), including the 61 reborrow chains. For B2: `outstandingForAll` results must equal the per-loan `outstandingAsOf` results for all 300 loans, including the 15 settled cases. For B5: the three `/repay` amounts must be byte-identical.
3. **Existing suites.** `./mvnw test` (unit) and `./mvnw -pl navix-app -Pit test` (Testcontainers flow test); `npx tsc --noEmit` + ESLint + `vitest` for the frontend; the Playwright suite for the staff console and borrower journey.
4. **Authorization regression.** Call every new/changed endpoint as BORROWER, DSA, TELECALLER and an unrelated staff role and expect the same 401/403/422 codes as the endpoint it replaces.
5. **Live re-measurement.** Repeat the Appendix A pass after each phase (same script, same endpoints); the targets are: telecalling < 1.5 s, dashboard first paint < 1.5 s, no endpoint > 1 MB, ADMIN dashboard < 100 KB/min, and `x-vercel-id` showing `bom1` after D1.
6. **Rollout.** D1 on a preview deployment first (cookie, login, BFF proxy and a borrower OTP flow smoke-tested), then production; backend releases via the documented SHA-tagged task-definition path (`aws.md` §8), one at a time, with the health-check window in mind.

---

## Appendix A — Live measurements (2026-09-16)

Client in US-East via proxy; ADMIN session; keep-alive; median of 2 samples (1 for the full customer list). BFF = `https://dhanboost.com` (functions in iad1); ALB = direct `http://navix-alb…` (ap-south-1). Times in ms; size = decoded JSON bytes; wire = gzip bytes. Backend ≈ ALB ttfb − 238 ms (the cheapest call's round trip).

| Page group | Endpoint (BFF path) | BFF ttfb | BFF total | ALB ttfb | ALB total | backend ≈ | size | wire | status BFF/ALB |
|---|---|---|---|---|---|---|---|---|---|
| shell | `/api/auth/staff/me` | 349 | 349 | 394 | 394 | ~156 | 61 | 88 | 200/404 |
| shell | `/api/feature-flags` | 308 | 308 | 258 | 258 | ~20 | 163 | 161 | 200/200 |
| shell | `/api/staff/notifications/unread-count` | 304 | 304 | 238 | 238 | ~0 | 99 | 122 | 200/200 |
| shell | `/api/staff/notifications?page=0&size=20` | 331 | 331 | 238 | 239 | ~0 | 5,458 | 790 | 200/200 |
| shell | `/api/staff/collections/worklist` | 326 | 327 | 257 | 258 | ~19 | 7,981 | 1,864 | 200/200 |
| dashboard | `/api/staff/applications?status=KYC_PENDING` | 490 | 490 | 249 | 249 | ~11 | 6,282 | 1,162 | 200/200 |
| dashboard | `/api/staff/applications?status=CREDIT_EXEC_PENDING` | 303 | 303 | 262 | 262 | ~24 | 15,123 | 2,155 | 200/200 |
| dashboard | `/api/staff/applications?status=SANCTIONED` | 301 | 301 | 243 | 243 | ~5 | 2,247 | 769 | 200/200 |
| dashboard | `/api/staff/applications?status=DISBURSEMENT_PENDING` | 371 | 371 | 242 | 242 | ~4 | 1,221 | 651 | 200/200 |
| dashboard | `/api/staff/applications?status=DISBURSEMENT_FAILED` | 309 | 309 | 237 | 237 | ~0 | 99 | 124 | 200/200 |
| dashboard | `/api/staff/applications?status=ACTIVE` | 437 | 441 | 283 | 398 | ~45 | 123,157 | 16,838 | 200/200 |
| dashboard | `/api/staff/applications?status=OVERDUE` | 284 | 284 | 238 | 238 | ~0 | 99 | 123 | 200/200 |
| dashboard | `/api/staff/applications?status=CLOSED` | 296 | 297 | 251 | 252 | ~13 | 8,232 | 1,825 | 200/200 |
| dashboard | `/api/staff/applications/credit-queue` | 408 | 408 | 244 | 244 | ~6 | 6,282 | 1,161 | 200/200 |
| dashboard | `/api/staff/applications/stats` | 304 | 304 | 240 | 240 | ~2 | 232 | 217 | 200/200 |
| dashboard | `/api/staff/decisions/summary?from=2026-08-17&to=2026-09-16` | 343 | 343 | 287 | 287 | ~49 | 12,107 | 1,437 | 200/200 |
| dashboard | `/api/staff/decisions?from=2026-08-17&to=2026-09-16` | 292 | 292 | 241 | 241 | ~3 | 3,196 | 779 | 200/200 |
| dashboard | `/api/staff/decisions` | 293 | 294 | 241 | 241 | ~3 | 3,196 | 777 | 200/200 |
| dashboard | `/api/staff/collections/cases` | 430 | 431 | 260 | 260 | ~22 | 5,143 | 1,350 | 200/200 |
| dashboard | `/api/staff/collections/settlements` | 336 | 336 | 241 | 241 | ~3 | 99 | 123 | 200/200 |
| dashboard | `/api/staff/collections/payments` | 291 | 291 | 243 | 243 | ~5 | 609 | 431 | 200/200 |
| dashboard | `/api/staff/dashboard/trends?days=30` | 324 | 324 | 271 | 271 | ~33 | 2,226 | 452 | 200/200 |
| dashboard | `/api/staff/loan/transactions` | 327 | 327 | 282 | 282 | ~44 | 38,938 | 7,293 | 200/200 |
| applications | `/api/staff/applications/credit-executives?role=CREDIT_EXECUTIVE` | 295 | 295 | 244 | 244 | ~6 | 315 | 215 | 200/200 |
| applications | `/api/staff/loan/pending-repayments` | 284 | 285 | 238 | 238 | ~0 | 99 | 123 | 200/200 |
| applications | `/api/staff/collections/payments?status=PENDING_HEAD` | 284 | 284 | 236 | 236 | ~0 | 99 | 123 | 200/200 |
| applications | `/api/staff/collections/payments?status=PENDING_ACCOUNTANT` | 291 | 292 | 242 | 242 | ~4 | 609 | 431 | 200/200 |
| customers | `/api/staff/customers/page?page=0&size=25` | 356 | 357 | 288 | 288 | ~50 | 19,396 | 2,661 | 200/200 |
| customers | `/api/staff/customers/summary` | 315 | 315 | 258 | 258 | ~20 | 267 | 228 | 200/200 |
| verifications | `/api/staff/applications/verifications/overview` | 511 | 527 | 306 | 309 | ~68 | 418,145 | 33,177 | 200/200 |
| loans | `/api/staff/loans` | 499 | 500 | 338 | 338 | ~100 | 59,035 | 7,944 | 200/200 |
| collections | `/api/staff/collections/officers` | 341 | 341 | 268 | 268 | ~30 | 404 | 232 | 200/200 |
| telecalling | `/api/staff/applications/telecalling` | 15298 | 15404 | 13055 | 14001 | ~12817 | 2,128,799 | 395,885 | 200/200 |
| leads | `/api/staff/leads` | 442 | 442 | 267 | 267 | ~29 | 35,669 | 3,304 | 200/200 |
| my-decisions | `/api/staff/decisions/inspectable` | 298 | 298 | 242 | 242 | ~4 | 883 | 342 | 200/200 |
| customers-full-list | `/api/staff/customers` | 6808 | 7077 | 4382 | 5026 | ~4144 | 7,524,049 | 699,546 | 200/200 |
| loan-detail | `/api/staff/loan/120` | 454 | 456 | 254 | 254 | ~16 | 426 | 303 | 200/200 |
| loan-detail | `/api/staff/loan/120/outstanding` | 371 | 371 | 244 | 244 | ~6 | 272 | 210 | 200/200 |
| loan-detail | `/api/staff/loan/120/repayments` | 318 | 318 | 247 | 247 | ~9 | 99 | 124 | 200/200 |
| collections-loan | `/api/staff/collections/cases/by-loan/120` | 311 | 311 | 288 | 288 | ~50 | 275 | 207 | 404/404 |
| customer-detail | `/api/staff/customers/1472458` | 424 | 424 | 264 | 264 | ~26 | 28,619 | 5,588 | 200/200 |
| customer-detail | `/api/staff/customers/1472458/documents` | 331 | 331 | 254 | 254 | ~16 | 3,001 | 788 | 200/200 |
| customer-detail | `/api/staff/customers/1472458/call-logs` | 308 | 309 | 238 | 238 | ~0 | 318 | 272 | 200/200 |
| customer-detail | `/api/staff/customers/1472458/activity` | 427 | 427 | 309 | 309 | ~71 | 9,958 | 2,301 | 200/200 |
| app-detail | `/api/staff/applications/9752` | 386 | 386 | 253 | 253 | ~15 | 1,158 | 609 | 200/200 |
| app-detail | `/api/staff/applications/9752/events` | 338 | 338 | 282 | 283 | ~44 | 1,946 | 670 | 200/200 |
| app-detail | `/api/staff/applications/9752/verifications` | 288 | 288 | 284 | 284 | ~46 | 6,821 | 2,326 | 200/200 |
| app-detail | `/api/staff/applications/9752/verification-progress` | 331 | 331 | 319 | 319 | ~81 | 162 | 159 | 200/200 |
| app-detail | `/api/staff/applications/9752/profile` | 330 | 331 | 288 | 288 | ~50 | 1,706 | 993 | 200/200 |

Additional live probes: `applications?status=DRAFT` → 1,063 rows, 1.04 MB, 870 ms BFF; `applications?status=REJECTED` → 8,575 rows, 8.67 MB, 2,828 ms BFF; `applications/stats` → DRAFT 1063 · KYC_PENDING 6 · CREDIT_EXEC_PENDING 14 · SANCTIONED 2 · DISBURSEMENT_PENDING 1 · ACTIVE 106 · CLOSED 7 · REJECTED 8575.

Contention probe (ALB direct, keep-alive): tiny endpoints (`feature-flags`, `applications/stats`, `notifications/unread-count`) median 249.5 ms alone; **235 ms** with one telecalling read in flight (that read took 14,233 ms); **249 ms** with two in flight (those reads took 27,123 ms and 33,291 ms).

Region evidence: every BFF response carried `x-vercel-id: iad1::iad1::…`; `frontend/vercel.json` does not exist; no route handler exports `preferredRegion`.


## Appendix B — Local SQL statement counts (seeded instance)

Backend built from this branch, local Postgres 16 with all 70 migrations, `log_min_duration_statement = 0`, dataset: 550 applications (DRAFT 40 · KYC_PENDING 60 · CREDIT_EXEC_PENDING 60 · SANCTIONED 40 · DISBURSEMENT_PENDING 20 · DISBURSEMENT_FAILED 5 · ACTIVE 120 · OVERDUE 60 · CLOSED 120 · REJECTED 25; 61 reborrow chains), 300 loans, 196 payments, 3,020 events, 5,395 verification rows, 60 collection cases, 15 settlements, 2,000 leads. Each endpoint was warmed once, then measured once; `SQL` = statements Postgres logged for that request (BEGIN/COMMIT/SET excluded); `db ms` = sum of server-side statement durations.

| Endpoint (backend path) | HTTP ms | bytes | rows | SQL statements | db ms | dominant statement shape |
|---|---|---|---|---|---|---|
| `/api/feature-flags` | 8 | 163 | – | **1** | 0.0 | ×1 `feature_flag` |
| `/api/notifications/unread-count` | 9 | 95 | – | **1** | 0.0 | ×1 `notification` |
| `/api/notifications?page=0&size=20` | 9 | 2,335 | 8 | **1** | 0.1 | ×1 `select n1_0.id,n1_0.actor_id,n1_0.actor_` |
| `/api/collections/worklist` | 104 | 59,918 | 89 | **162** | 3.6 | ×89 `collection_case` |
| `/api/applications/telecalling` | 177 | 43,480 | 185 | **195** | 7.7 | ×191 `select av1_0.id,av1_0.application_id,av1` |
| `/api/applications/all` | 439 | 464,145 | 550 | **619** | 23.2 | ×612 `select av1_0.id,av1_0.application_id,av1` |
| `/api/customers` | 228 | 310,099 | 400 | **380** | 13.6 | ×300 `collection_case` |
| `/api/customers/page?page=0&size=25` | 44 | 19,460 | 25 | **51** | 3.1 | ×30 `collection_case` |
| `/api/customers/summary` | 10 | 267 | – | **1** | 0.5 | ×1 `with latest_app as (` |
| `/api/customers/5000001` | 22 | 7,395 | – | **23** | 0.5 | ×4 `select su1_0.id,su1_0.active_session_at,` |
| `/api/customers/5000001/activity` | 21 | 11,406 | 72 | **17** | 0.4 | ×4 `application_event` |
| `/api/customers/5000001/documents` | 10 | 361 | 4 | **2** | 0.0 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/customers/5000001/call-logs` | 9 | 835 | 5 | **1** | 0.1 | ×1 `select ccl1_0.id,ccl1_0.call_type,ccl1_0` |
| `/api/applications?status=ACTIVE` | 31 | 132,809 | 120 | **12** | 2.7 | ×5 `select su1_0.id,su1_0.active_session_at,` |
| `/api/applications?status=CREDIT_EXEC_PENDING` | 21 | 61,801 | 60 | **6** | 1.0 | ×2 `application_event` |
| `/api/applications?status=KYC_PENDING` | 20 | 60,722 | 60 | **5** | 0.9 | ×2 `application_event` |
| `/api/applications?status=OVERDUE` | 25 | 67,248 | 60 | **13** | 1.5 | ×6 `select su1_0.id,su1_0.active_session_at,` |
| `/api/applications/credit-queue` | 17 | 60,722 | 60 | **6** | 0.8 | ×2 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/stats` | 7 | 270 | – | **1** | 0.2 | ×1 `loan_application` |
| `/api/applications/credit-executives?role=CREDIT_EXECUTIVE` | 9 | 376 | 4 | **1** | 0.1 | ×1 `select su1_0.id,su1_0.active_session_at,` |
| `/api/applications/verifications/overview` | 15 | 107,794 | 420 | **3** | 1.5 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/loans` | 163 | 154,832 | 300 | **366** | 6.2 | ×300 `collection_case` |
| `/api/collections/cases` | 53 | 17,202 | 60 | **126** | 1.9 | ×60 `collection_case` |
| `/api/collections/settlements` | 9 | 5,627 | 15 | **2** | 0.1 | ×1 `select s1_0.id,s1_0.approved_at,s1_0.app` |
| `/api/collections/payments` | 38 | 17,577 | 30 | **66** | 1.4 | ×30 `collection_case` |
| `/api/collections/officers` | 7 | 170 | 1 | **1** | 0.0 | ×1 `select su1_0.id,su1_0.active_session_at,` |
| `/api/loan/transactions` | 177 | 191,508 | 496 | **4** | 2.2 | ×1 `select l1_0.id,l1_0.closed_on,l1_0.creat` |
| `/api/loan/pending-repayments` | 42 | 25,430 | 40 | **4** | 0.7 | ×1 `select p1_0.id,p1_0.amount,p1_0.created_` |
| `/api/staff/decisions/summary?from=2026-06-01&to=2026-09-16` | 23 | 8,584 | 14 | **6** | 2.4 | ×1 `select su1_0.id,su1_0.active_session_at,` |
| `/api/staff/decisions?from=2026-06-01&to=2026-09-16` | 8 | 99 | 0 | **1** | 0.2 | ×1 `application_event` |
| `/api/staff/decisions` | 7 | 99 | 0 | **1** | 0.1 | ×1 `application_event` |
| `/api/staff/decisions/inspectable` | 9 | 581 | 7 | **4** | 0.1 | ×4 `select su1_0.id,su1_0.active_session_at,` |
| `/api/dashboard/trends?days=30` | 13 | 2,169 | – | **3** | 0.8 | ×1 `application_event` |
| `/api/leads` | 37 | 831,880 | 1803 | **4** | 3.6 | ×2 `select su1_0.id,su1_0.active_session_at,` |
| `/api/loan/3` | 10 | 425 | – | **4** | 0.1 | ×2 `select l1_0.id,l1_0.closed_on,l1_0.creat` |
| `/api/loan/3/outstanding` | 10 | 274 | – | **3** | 0.1 | ×1 `select l1_0.id,l1_0.closed_on,l1_0.creat` |
| `/api/loan/3/repayments` | 8 | 99 | 0 | **1** | 0.1 | ×1 `select p1_0.id,p1_0.amount,p1_0.created_` |
| `/api/loan/7` | 10 | 433 | – | **5** | 0.1 | ×2 `select l1_0.id,l1_0.closed_on,l1_0.creat` |
| `/api/loan/7/outstanding` | 9 | 280 | – | **4** | 0.1 | ×1 `select l1_0.id,l1_0.closed_on,l1_0.creat` |
| `/api/applications/144` | 11 | 1,080 | – | **4** | 0.1 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/144/events` | 10 | 667 | 3 | **4** | 0.1 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/144/verifications` | 7 | 1,630 | 7 | **1** | 0.1 | ×1 `select av1_0.id,av1_0.application_id,av1` |
| `/api/applications/144/verification-progress` | 8 | 162 | – | **2** | 0.1 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/144/profile` | 9 | 1,241 | – | **2** | 0.1 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/3` | 11 | 1,123 | – | **4** | 0.1 | ×1 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/applications/3/verification-progress` | 11 | 161 | – | **6** | 0.2 | ×3 `select la1_0.id,la1_0.amount_requested,l` |
| `/api/collections/cases/by-loan/7` | 12 | 701 | – | **8** | 0.2 | ×1 `collection_case` |
| `/api/collections/cases/4b0625b3-8bee-4806-af66-c47d8a9e9a51` | 12 | 701 | – | **8** | 0.2 | ×1 `collection_case` |
| `/api/collections/cases/4b0625b3-8bee-4806-af66-c47d8a9e9a51/interactions` | 8 | 956 | 4 | **1** | 0.1 | ×1 `intera` |
| `/api/collections/cases/4b0625b3-8bee-4806-af66-c47d8a9e9a51/payments` | 10 | 99 | 0 | **1** | 0.0 | ×1 `select cp1_0.id,cp1_0.amount_paise,cp1_0` |

Reading the heavy rows: `telecalling` = 4 fixed + one `application_verification` read per application (+ one per reborrow hop); `all` = the same loop over every application; `customers`, `loans`, `collections/worklist|cases|payments` and `customers/page` = fixed batched reads + one `collection_case` read per loan and one `settlement` read per case (`outstandingForAll`); `customers/{id}` = 7 + distinct staff + 4 × loans.


## Appendix C — Polling constants (verified)

| Constant | Value | Location |
|---|---|---|
| Dashboard `REFRESH_MS` / `SLOW_MS` | 10 s / 60 s | `frontend/src/app/staff/dashboard/page.tsx:54,56` |
| Status queues, credit workbench | 8 s | `frontend/src/components/staff/pipeline/status-queue.tsx:62,98,122,127` |
| Sidebar collections worklist | 8 s | `frontend/src/components/staff/staff-shell.tsx:262` |
| Repayment verify queue | 8 s | `frontend/src/components/staff/repayment-verify-queue.tsx:39` |
| Collection payment queues | 10 s | `frontend/src/components/staff/collection-payments.tsx:187,224` |
| Verifications overview + KYC_PENDING | 15 s | `frontend/src/app/staff/verifications/page.tsx:95,101` |
| Telecalling | 15 s | `frontend/src/app/staff/telecalling/page.tsx:28` |
| Transactions ledger | 10 s | `frontend/src/app/staff/accounting/transactions/page.tsx:95` |
| Notification bell | 20 s | `frontend/src/lib/api/notifications.ts:22` |
| Borrower application / mine | 4 s / 15 s | `frontend/src/lib/api/live-journey.ts:34,42` |
| React Query defaults | staleTime 60 s, refetchOnWindowFocus false, retry 1, background polling paused (default) | `frontend/src/lib/query-client.ts:11-17` |


## Appendix D — Evidence files

Working files from this investigation (scratchpad of the session, not committed): 17 page traces, two consolidated analyses, `schema-indexes.txt` / `schema-columns.txt` (authoritative catalog), `live/measurements.json|md`, `local/sqlcounts.json`, the seed generator and SQL. The report above is the only committed artifact.
