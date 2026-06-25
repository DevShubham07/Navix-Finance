# NAVIX Finance — Detailed Progress Handoff
> Purpose: hand this to the Claude that holds the full end-to-end build spec so it can plan the next steps.
> Generated: 2026-06-24 · Branch audited: `main` (working branch `dev/project-audit`)
> Verification method: every status below was confirmed by reading source, not inferred.

---

## 0. TL;DR for the planning Claude

- **Frontend: ~90% UI-complete, 100% in *demo mode*.** Every screen exists and works end-to-end against an in-browser Zustand mock store. It is **not** wired to the backend yet.
- **Backend: scaffolding 100%, business logic ~5%.** All 10 Maven modules, entities, repositories, DTOs, controllers, and Spring config exist and compile. **But nearly every service method throws `UnsupportedOperationException("Not implemented yet")`.** Controllers are wired to these stubs, so **calling any business endpoint currently returns HTTP 500.**
- **The only genuinely working backend logic** is `DocumentStorageService` (S3 presigned URLs) — and it needs real AWS creds + an S3 bucket to function.
- **Database:** 21 tables live in Postgres (Flyway), schema matches entities, **0 rows of data**.
- **No authentication** is enforced anywhere (all Spring Security chains are `permitAll`).
- **No tests** exist (backend or frontend).
- **No external integration is live** (Fintrix, DigiLocker, S3, OTP, email all stubbed/unconfigured).

**Net: this is a high-quality scaffold + a fully-clickable mock UI. The entire backend business layer and the frontend↔backend wiring are the remaining work.**

---

## 1. Status legend

| Symbol | Meaning |
|---|---|
| ✅ DONE | Implemented and functional |
| 🟡 STUB | Exists, compiles, but body is `TODO` / throws `UnsupportedOperationException` / returns placeholder |
| ⛔ MISSING | Does not exist at all |

---

## 2. Backend status — module by module

Counts are `(java files / files containing a TODO)`.

### `navix-common` (8 / 8)
| Item | Status | Notes |
|---|---|---|
| `BaseAuditEntity` | ✅ DONE | createdAt/by, updatedAt/by audit fields |
| `ApiResponse<T>` | ✅ DONE | standard response wrapper |
| `Masking` util | 🟡 STUB | PII masking helpers — TODO bodies |

### `navix-iam` (17 / 16) — staff identity & access
| Item | Status |
|---|---|
| Entities: `StaffUser`, `StaffInvite`, `BlocklistEntry` | ✅ DONE (JPA mapped, validated against schema) |
| Repositories (3) | ✅ DONE (Spring Data interfaces) |
| `StaffDtos` | ✅ DONE (request/response records) |
| `StaffService`, `InviteService`, `BlocklistService` | 🟡 STUB (all methods throw `UnsupportedOperationException`) |
| `SeparationOfDutiesGuard` | 🟡 STUB (maker-checker enforcement not written) |
| Controllers: `StaffController`, `InviteController`, `BlocklistController` | 🟡 STUB (routes wired → call stub services → 500) |

**Endpoints (all 🟡 — return 500 today):**
`GET /api/staff` · `GET /api/staff/{id}` · `PUT /api/staff/{id}` · `DELETE /api/staff/{id}` · `POST /api/staff/invites` · `POST /api/staff/invites/accept` · `GET/POST/DELETE /api/admin/blocklist`

### `navix-onboarding` (10 / 9) — borrower signup
| Item | Status |
|---|---|
| Entities: `Borrower`, `CoApplicant`, `SignupApplication` | ✅ DONE |
| Repositories (3) | ✅ DONE |
| `BorrowerService` | 🟡 STUB (create borrower + signup app + trigger OTP — all TODO) |
| `OtpService` | 🟡 STUB (generate/persist/dispatch OTP — TODO) |
| `BorrowerController` | 🟡 STUB — **only `GET /api/borrower/{id}` exists; no POST to create a borrower** |

### `navix-kyc` (10 / 9)
| Item | Status |
|---|---|
| Entities: `KycCase`, `KycCheck`, `DigiLockerSession` | ✅ DONE |
| Repositories | ✅ DONE |
| `KycService` / DigiLocker session service | 🟡 STUB |
| `KycController` | 🟡 STUB — only `GET /api/kyc/case/{borrowerId}` |

### `navix-verification` (13 / 9) — external API clients
| Item | Status |
|---|---|
| `FintrixClientConfig`, `FintrixProperties`, `DigiLockerProperties` | ✅ DONE (RestClient beans, Basic-auth wiring, base URLs from `application.yml`) |
| `FintrixDtos`, `DigiLockerDtos` | ✅ DONE (envelope records) |
| `PanComprehensiveClient` | 🟡 STUB (throws — needs real Fintrix POST + mapping) |
| `DigiLockerClient` | 🟡 STUB (OAuth + Aadhaar fetch) |
| `ExperianClient`, `CrifClient` | 🟡 STUB (bureau pulls) |
| `PennyDropClient` | 🟡 STUB (bank account verify) |
| `EmailVerificationClient`, `AddressVerificationClient` | 🟡 STUB |
| `BureauService` | 🟡 STUB (aggregate signals) |
| **No controllers** | by design — these are integration clients consumed by other services |

> The HTTP plumbing (auth, base URL, DTO shapes) is ready; only the actual call + response mapping is missing in each client.

### `navix-income-risk` (8 / 8)
| Item | Status |
|---|---|
| Entities: `IncomeProfile`, `RiskAssessment` | ✅ DONE |
| Repositories | ✅ DONE |
| `RiskScoringService` | 🟡 STUB (scoring algorithm TODO) |
| `LimitCalculator` | 🟡 STUB (25% salary cap + risk adjustment TODO) |
| `RiskCategory` enum (A/B/C/D) | ✅ DONE |
| `IncomeController` | 🟡 STUB — `GET /api/income/{applicantId}` |

### `navix-loan` (17 / 13)
| Item | Status |
|---|---|
| Entities: `Loan`, `LoanApplication`, `LoanDocument`, `Payment` | ✅ DONE |
| Repositories | ✅ DONE |
| `LoanService`, `RepaymentService` | 🟡 STUB (apply, fee/gst/net/dueDate calc, repayment recording — TODO) |
| `LoanController` | 🟡 STUB — `POST /api/loan/applications`, `GET /api/loan/{loanId}` |
| `RepaymentController` | 🟡 STUB — `POST/GET /api/loan/{loanId}/repayments` |

> NOTE: the loan math (fee 10%, GST 18%, interest 1%/day) is **fully implemented on the frontend** (`frontend/src/lib/calc/loan-math.ts`) but **not yet on the backend** — it will need porting/duplicating server-side.

### `navix-disbursement` (9 / 8) — maker-checker
| Item | Status |
|---|---|
| Entities: `DisbursementRequest`, `ApprovalStep` (UUID PKs) | ✅ DONE |
| Repositories | ✅ DONE |
| `ApprovalChainService` | 🟡 STUB (state machine: PENDING_CREDIT_REVIEW → CREDIT_RECOMMENDED → CREDIT_APPROVED → RELEASE_AUTHORISED → TRANSFER_CONFIRMED) |
| `PennyDropGate` | 🟡 STUB (verify bank before release) |
| `AccountantConfirmationService` | 🟡 STUB (manual transfer confirm) |
| `DisbursementController` | 🟡 STUB — **base mapping only, NO endpoint handlers exist** (`@PostMapping`s are TODO) |

### `navix-collections` (11 / 10)
| Item | Status |
|---|---|
| Entities: `CollectionCase`, `InteractionLog`, `RepaymentPlan`, `Settlement` | ✅ DONE |
| `DpdBucket` enum | ✅ DONE |
| `DpdCalculator` | 🟡 STUB (days-past-due → bucket) |
| `CollectionsService`, `SettlementService` | 🟡 STUB |
| `CollectionsController` | 🟡 STUB — **base mapping only, NO endpoint handlers** |

### `navix-storage` (5 / 1) — **the one working service**
| Item | Status |
|---|---|
| `DocumentStorageService` | ✅ DONE (real S3 putObject, presignUpload, presignDownload, headObject, delete) |
| `StorageProperties`, `StorageCategory` | ✅ DONE |
| `StorageController` | ✅ DONE — `POST /api/storage/presign-upload`, `GET /api/storage/presign-download` |
| Caveat | needs real AWS creds + the `navix-finance-documents-dev` S3 bucket to actually work |

### `navix-app` — boot module
| Item | Status |
|---|---|
| `NavixApplication` main | ✅ DONE |
| `SecurityConfig` | 🟡 STUB-by-design — 3 filter chains (borrower / staff / default), **all `permitAll`** (auth TODO) |
| `OpenApiConfig` | ✅ DONE (Swagger at `/swagger-ui.html`) |
| `WebConfig` | ✅ DONE (CORS) |
| Flyway `V1__init.sql`, `V2__core_schema.sql` | ✅ DONE (21 tables + indexes) |
| AWS SSM param store import | ✅ DONE (`optional:` → boots without AWS) |

---

## 3. Database status

- **Engine:** PostgreSQL 16 in Docker (`navix-postgres`), data in volume `navix_final_navix-pgdata`.
- **Connection:** `jdbc:postgresql://localhost:5432/navix`, user/pass `navix`/`navix`.
- **Schema:** ✅ 21 tables created & validated by Hibernate on boot.
- **Data:** ⛔ all 21 tables hold **0 rows** — no seed/fixtures.
- Tables: `staff_user, staff_invite, blocklist_entry, borrower, co_applicant, signup_application, kyc_case, kyc_check, digilocker_session, income_profile, risk_assessment, loan, loan_application, loan_document, payment, disbursement_request, approval_step, collection_case, interaction_log, repayment_plan, settlement` (+ `flyway_schema_history`).

---

## 4. Frontend status

### ✅ DONE — UI (114 TS/TSX files)
**Borrower journey** — all render & navigate, driven by mock store:
- Marketing landing (`(marketing)/page.tsx`) with EMI calculator
- Login
- 12-step signup wizard: `mobile-otp → email → pan → employment → salary → financials → bank → address-proof → co-applicant → review` (each a real page under `(borrower)/signup/`)
- KYC: `kyc/page`, `kyc/digilocker` + `kyc/digilocker/callback`, `kyc/selfie`
- Loan: `loan/apply` (live cost breakdown), `loan/documents`, `loan/bank-verify`, `loan/status`
- `repay`, `reloan`, `dashboard`, `profile`

**Staff portal** — all render, role-aware nav:
- `staff/login`, `staff/activate`, `staff/dashboard`
- `staff/kyc-approvals`
- `staff/credit/queue`, `staff/credit/[applicationId]`
- `staff/disbursement`, `staff/accounting`
- `staff/collections/buckets`, `staff/collections/[loanId]`, `staff/collections/settlements`
- `staff/admin/staff`, `staff/admin/invites`, `staff/admin/blocklist`

**Component library:** `ui/` primitives (Button, Card, Input, Select, Badge, Table, Dialog) + borrower/staff/site component sets. ✅ DONE.

### ✅ DONE — logic that lives only on the frontend
- `lib/calc/loan-math.ts` — full product math (fee, GST, net disbursed, daily interest, total repayable, due-date-from-salary, late penalty). **Correct per spec.**
- `lib/calc/risk.ts` — risk helpers.
- `lib/mock/*` — complete in-memory data layer (`useMockDb` Zustand store, localStorage-persisted) with seeded applications/staff/borrowers/collections and **all approval workflow mutators** (decideKyc, assignExecutive, recommend, decideCredit, releaseDisbursement, confirmTransfer, collections + admin actions). This is what makes the whole app clickable without a backend.
- `lib/auth/session.ts`, `lib/auth/rbac.ts`, `middleware.ts` — session + RBAC scaffolding.

### 🟡 STUB — the frontend↔backend bridge
- `lib/config.ts` → `demoMode` defaults to **`true`** (mock). Real mode = set `NEXT_PUBLIC_DEMO_MODE=false`.
- `lib/api/client.ts`, `lib/api/fintrix.ts`, `lib/api/digilocker.ts` — typed fetch wrappers exist.
- **BFF route handlers are stubs** that return `501` / placeholder JSON:
  - `api/loan/route.ts` → "loan GET/POST not implemented"
  - `api/kyc/pan/route.ts` → 501
  - `api/kyc/digilocker/route.ts`, `api/auth/route.ts`, `api/webhooks/fintrix/route.ts` → stub

So even with `demoMode=false`, the app cannot reach real data until both the BFF routes **and** the backend services are implemented.

### ⛔ MISSING — frontend
- No Playwright/unit tests (dependency present, zero test files).

---

## 5. Cross-cutting gaps (whole system)

| Concern | Status | Detail |
|---|---|---|
| Authentication | ⛔ | No JWT/session/token logic; Spring chains are `permitAll`; staff login + borrower OTP are UI-only |
| Authorization / RBAC enforcement | ⛔ | `SeparationOfDutiesGuard` is a stub; roles enforced only in mock UI |
| OTP delivery | ⛔ | `OtpService` stub; no SMS provider |
| Email verification | ⛔ | `EmailVerificationClient` stub |
| KYC (PAN/Aadhaar/DigiLocker/selfie) | ⛔ | All Fintrix/DigiLocker clients stubbed; no creds |
| Credit bureau | ⛔ | Experian/CRIF clients stubbed |
| Penny-drop / bank verify | ⛔ | `PennyDropClient` stub |
| S3 document storage | 🟡 | Service implemented but bucket + AWS creds not provisioned |
| Risk scoring & limit | ⛔ | `RiskScoringService` + `LimitCalculator` stubs |
| Disbursement workflow | ⛔ | `ApprovalChainService` stub + no controller handlers |
| Collections workflow | ⛔ | services stub + no controller handlers |
| Backend loan math | ⛔ | exists on FE only; needs server-side port |
| Tests | ⛔ | none anywhere |
| Seed data | ⛔ | empty DB |
| CI/CD | ⛔ | `Dockerfile.backend` + `deploy/` exist; no pipeline verified |

---

## 6. Suggested build order (for the planning Claude to refine against the spec)

1. **Backend foundation:** implement `navix-common` masking + a shared error handler; turn on a real auth scheme (staff JWT + borrower OTP-token).
2. **IAM first** (smallest, unblocks staff portal): implement `StaffService`, `InviteService`, `BlocklistService`, `SeparationOfDutiesGuard`; seed an initial ADMIN.
3. **Onboarding + OTP:** `BorrowerService` create-flow, `OtpService` (pick SMS provider), add the missing `POST /api/borrower`.
4. **Verification clients:** wire `PanComprehensiveClient` + `DigiLockerClient` against Fintrix (creds needed), then bureau + penny-drop.
5. **Income/risk:** `LimitCalculator` (25% cap) + `RiskScoringService`.
6. **Loan:** port `loan-math` to backend; implement `LoanService.apply` + `RepaymentService`.
7. **Disbursement:** build `ApprovalChainService` state machine + `DisbursementController` handlers + `PennyDropGate` + `AccountantConfirmationService`.
8. **Collections:** `DpdCalculator`, `CollectionsService`, `SettlementService` + controller handlers.
9. **Frontend bridge:** implement BFF route handlers; flip `NEXT_PUBLIC_DEMO_MODE=false`; replace mock-store reads with API calls behind a feature flag.
10. **Provision infra:** S3 bucket, AWS creds/SSM params, Fintrix/DigiLocker secrets.
11. **Seed data + tests** throughout.

---

## 7. How to run (verified working today)

```bash
colima start                                   # Docker runtime
docker compose up -d db adminer                # Postgres + Adminer
cd backend && ./mvnw install -DskipTests -q    # build all modules (first time)
./mvnw spring-boot:run -pl navix-app           # backend → :8080
cd ../frontend && npm install && npm run dev   # frontend → :3000 (demo mode)
```

| Service | URL |
|---|---|
| Frontend (demo mode) | http://localhost:3000 |
| Backend health | http://localhost:8080/actuator/health → `{"status":"UP"}` |
| Swagger | http://localhost:8080/swagger-ui.html |
| Adminer | http://localhost:8081 (server `db`, `navix`/`navix`, db `navix`) |

> Reminder: backend **boots fine**, but every business endpoint returns **500** because the services are stubs. Only `/actuator/health`, Swagger, and (with AWS) `/api/storage/*` respond successfully.
