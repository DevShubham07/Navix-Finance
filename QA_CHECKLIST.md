# NAVIX Finance — Manual QA Checklist

> **Purpose.** A hands-on, click-by-click / curl-by-curl walkthrough of **every** backend endpoint and
> **every** UI page. A QA engineer with the stack running can execute each row top-to-bottom and tick
> Pass/Fail.
>
> **Scope.** This is the *concrete* companion to [`TESTING_PLAN.md`](TESTING_PLAN.md) (which is the
> automated test-engineering **strategy** — unit/Testcontainers/Playwright). This doc does **not**
> repeat that; it is the manual smoke + regression pass.
>
> **How to read a row.** `ID | Precondition | Steps | Expected result | Pass/Fail`. Leave the last
> column blank and fill `P`/`F` (+ a note) as you go. IDs are stable — cite them in bug reports.
>
> **Verified against code (2026-06-27):** every path/role below was read from the controllers and
> `frontend/src/lib/auth/rbac.ts`, not from memory. Deviations from the original brief are footnoted
> in §G.

---

## Conventions — response & status codes (read once)

Every backend JSON response uses the envelope:

```json
{ "success": true,  "data": { ... }, "error": null }
{ "success": false, "data": null,    "error": { "code": "SOME_CODE", "message": "...", "path": "/api/...", "fieldErrors": {} } }
```

Status-code mapping (from `GlobalExceptionHandler` + `SecurityConfig`):

| Situation | HTTP | Body | `error.code` |
|---|---|---|---|
| Success | 200 | envelope, `success:true` | — |
| **Missing / garbage / expired / wrong-audience Bearer** on a protected route | **401** | **empty** (Spring Security entry point — *no* envelope) | — |
| Business / authz / state-machine failure (incl. **wrong role**) | **422** | envelope, `success:false` | `FORBIDDEN_ROLE`, `FORBIDDEN_OWNER`, `SOD_VIOLATION`, `ILLEGAL_TRANSITION`, `KYC_INCOMPLETE`, `INVALID_CREDENTIALS`, `INVALID_OTP`, `INACTIVE_STAFF`, `INVALID_MOBILE`, … |
| Bean-validation failure (`@Valid`, missing/blank field) | **400** | envelope, `fieldErrors` populated | `VALIDATION_ERROR` |
| Entity not found | **404** | envelope | `NOT_FOUND` |
| Unhandled | **500** | envelope | `INTERNAL_ERROR` |

> **Gotcha to expect:** an **invalid login password is `422 INVALID_CREDENTIALS`, not 401** (login is a
> permit-all route, so the failure is a normal business exception). A `401` only appears on a
> *protected* route reached with no/garbage token.

**Open (no-token) routes:** `/api/auth/**`, `/api/storage/**`, `/actuator/**`, `/swagger-ui/**`,
`/v3/api-docs/**`. **Everything else requires a valid JWT.**

---

## §A. Setup & test data

### A.1 Bring the stack up

**Option 1 — local Postgres (offline, recommended for QA):**

```bash
# DB
docker compose up -d                       # Postgres 16 on :5432 (db/user/pass: navix), Adminer :8081

# Backend (:8080), Java 21
cd backend
./mvnw install -DskipTests                 # FIRST run only — builds sibling jars into ~/.m2
./mvnw -pl navix-app spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.datasource.url=jdbc:postgresql://localhost:5432/navix \
     -Dspring.datasource.username=navix -Dspring.datasource.password=navix \
     -DNAVIX_SMS_DEV_ECHO=true"            # dev-echo so OTP codes come back in the response
```

**Option 2 — AWS RDS (dev):**

```bash
cd backend
AWS_PROFILE=navix-dev NAVIX_ENV=dev NAVIX_SMS_DEV_ECHO=true ./mvnw -pl navix-app spring-boot:run
```

**Frontend (:3000):**

```bash
cd frontend
npm install
npm run dev                                # BFF → BACKEND_BASE_URL (default http://localhost:8080)
```

Flyway applies **V1–V18** on boot. Swagger UI: **http://localhost:8080/swagger-ui.html**.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| SET-01 | Docker running | `docker compose up -d` | Postgres `:5432` + Adminer `:8081` up | |
| SET-02 | Backend booting | Watch logs | `Flyway ... Successfully applied ... V1..V18`; app on `:8080`, no stack traces | |
| SET-03 | Backend up | Open `http://localhost:8080/swagger-ui.html` | Swagger UI lists all controllers (Application, Loan, Collections, Customers, Staff, …) | |
| SET-04 | Backend up | Open `http://localhost:8080/actuator/health` (no token) | `200 {"status":"UP"}` — confirms actuator is permit-all | |
| SET-05 | Frontend up | Open `http://localhost:3000` | Marketing landing renders | |

### A.2 Get a **staff** token

Staff login maps a **role → a seeded `*.navix.example` account** (Flyway **V10**) with the shared
password **`Admin@12345`** (BCrypt hash set in **V17**).

| Role | Seeded email |
|---|---|
| `KYC_APPROVER` | `ananya.rao@navix.example` |
| `CREDIT_EXECUTIVE` | `rahul.mehta@navix.example` (also `kabir.singh@`, `neha.gupta@`) |
| `CREDIT_HEAD` | `priya.nair@navix.example` |
| `DISBURSEMENT_HEAD` | `vikram.shah@navix.example` |
| `ACCOUNTANT` | `deepa.iyer@navix.example` |
| `COLLECTION_HEAD` | `arjun.patel@navix.example` |
| `COLLECTION_EXECUTIVE` | `sana.khan@navix.example` |
| `ADMIN` | `meera.krishnan@navix.example` |
| `DEVELOPER` | `dev.ops@navix.example` |

```bash
# Helper: grab a staff JWT for any seeded email
staff_token () {
  curl -s -X POST http://localhost:8080/api/auth/staff/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"Admin@12345\"}" | jq -r '.data.token'
}
ADMIN=$(staff_token meera.krishnan@navix.example)
KYC=$(staff_token ananya.rao@navix.example)
CEXEC=$(staff_token rahul.mehta@navix.example)
CHEAD=$(staff_token priya.nair@navix.example)
DHEAD=$(staff_token vikram.shah@navix.example)
ACCT=$(staff_token deepa.iyer@navix.example)
CHEAD_COLL=$(staff_token arjun.patel@navix.example)
echo "$ADMIN"     # should be a JWT (three dot-separated segments)
```

### A.3 Get a **borrower** token (with dev-echo OTP)

```bash
# 1) Request an OTP — with NAVIX_SMS_DEV_ECHO=true the code is returned as data.devCode
DEV=$(curl -s -X POST http://localhost:8080/api/auth/borrower/otp/request \
  -H 'Content-Type: application/json' -d '{"mobile":"9819000001"}')
echo "$DEV"                                  # {"success":true,"data":{"sent":...,"ttlSeconds":300,"devCode":"NNNNNN"}}
OTP=$(echo "$DEV" | jq -r '.data.devCode')

# 2) Login with the code → borrower JWT (carries applicantId)
BORROWER=$(curl -s -X POST http://localhost:8080/api/auth/borrower/login \
  -H 'Content-Type: application/json' \
  -d "{\"mobile\":\"9819000001\",\"otp\":\"$OTP\"}" | jq -r '.data.token')
echo "$BORROWER"
```

> `applicantId` is derived from the **last 7 digits** of the mobile (`9819000001` → `9000001`) unless an
> explicit `applicantId` is passed in the login body.

### A.4 Seed stage data

```bash
# Seeds one application at EVERY lifecycle stage + a primary borrower (mobile 9819000001, history+current).
# Run with the stack up. (PowerShell driver — pwsh on macOS, or run on Windows.)
pwsh ./scripts/populate-demo-data.ps1        # repo-root scripts/
```

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| SET-06 | Backend up | Run `staff_token meera.krishnan@navix.example` | A JWT string returned | |
| SET-07 | Backend up, dev-echo on | Run A.3 steps | `devCode` present (6 digits); login returns a token + `applicantId:9000001` | |
| SET-08 | dev-echo **off** | Repeat A.3 step 1 | `devCode` is `null`; OTP only deliverable by real SMS (see §G — BLOCKED) | |
| SET-09 | Stack up | `pwsh ./scripts/populate-demo-data.ps1` | Script completes; staff queues + borrower history now have rows | |

---

## §B. Backend API matrix

> For each row: have `$ADMIN`, `$BORROWER`, etc. exported (§A). The "no/garbage token" case sends
> `-H 'Authorization: Bearer garbage'` (or omits the header) and expects **401, empty body**.

### B.1 `AuthController` — `/api/auth/*` (open, no token)

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-AUTH-01 | seeded staff | `POST /api/auth/staff/login {email:"meera.krishnan@navix.example",password:"Admin@12345"}` | `200`, `data.token` JWT, `data.role:"ADMIN"` | |
| BE-AUTH-02 | seeded staff | same email, `password:"wrong"` | `422` `INVALID_CREDENTIALS` | |
| BE-AUTH-03 | — | login with unknown email | `422` `INVALID_CREDENTIALS` (no user-enumeration difference vs bad pw) | |
| BE-AUTH-04 | inactive staff exists | login as a disabled account | `422` `INACTIVE_STAFF` | |
| BE-AUTH-05 | — | `POST /api/auth/staff/login {}` (no fields) | `400` `VALIDATION_ERROR`, `fieldErrors` on email/password | |
| BE-AUTH-06 | dev-echo on | `POST /api/auth/borrower/otp/request {mobile:"9819000001"}` | `200`, `data.ttlSeconds:300`, `data.devCode` 6 digits | |
| BE-AUTH-07 | — | otp/request `{mobile:"12"}` (not 10 digits) | `422` `INVALID_MOBILE` | |
| BE-AUTH-08 | OTP requested | `POST /api/auth/borrower/login {mobile,otp:<devCode>}` | `200`, `data.token`, `data.role:"BORROWER"`, `data.applicantId:9000001` | |
| BE-AUTH-09 | OTP requested | borrower/login with **wrong** otp | `422` `INVALID_OTP` | |
| BE-AUTH-10 | OTP consumed (BE-AUTH-08) | re-login with the **same** otp | `422` `INVALID_OTP` (single-use) | |
| BE-AUTH-11 | OTP requested | wait > 300 s, then login | `422` `INVALID_OTP` (TTL expiry) | |
| BE-AUTH-12 | OTP requested | submit wrong otp **5×**, then the correct one | 6th attempt `422 INVALID_OTP` (attempt cap = 5, code invalidated) | |

### B.2 `ApplicationController` — `/api/applications` (lifecycle aggregate)

Role gating (verified in `ApplicationFlowService.requireRole`; **ADMIN bypasses every role + the credit
SoD**):

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-APP-01 | `$BORROWER` | `POST /api/applications {applicantId:9000001}` | `200`, new app `status:"DRAFT"`; note its `id` | |
| BE-APP-02 | — | same POST, **no token** | `401` empty | |
| BE-APP-03 | `$KYC` (non-borrower) | `POST /api/applications {applicantId:1}` | `422` `FORBIDDEN_ROLE` (create needs BORROWER) | |
| BE-APP-04 | `$BORROWER` | `POST /api/applications {}` (no applicantId) | `400` `VALIDATION_ERROR` | |
| BE-APP-05 | `$BORROWER`, has profile+loan history | `POST /api/applications/reborrow` | `200`, `PRE_APPROVED` (clean) or `REVIEW_PENDING` (past overdue) | |
| BE-APP-06 | `$BORROWER` w/ a live app/loan | `POST /api/applications/reborrow` | `422` (reborrow blocked while a live application/loan exists) | |
| BE-APP-07 | `$KYC` | `GET /api/applications?status=KYC_PENDING` | `200`, list of apps in that state | |
| BE-APP-08 | `$KYC` | `GET /api/applications?status=BOGUS` | `400`/`500` (enum bind failure) — not a `200` | |
| BE-APP-09 | `$CHEAD` | `GET /api/applications/credit-queue` | `200`, KYC-approved **applied** apps | |
| BE-APP-10 | `$BORROWER` | `GET /api/applications/mine` | `200`, only the caller's own apps, newest-first | |
| BE-APP-11 | `$ADMIN`, app id from BE-APP-01 | `GET /api/applications/{id}` and `…/events` | `200`; events is append-only audit trail | |
| BE-APP-12 | any token | `GET /api/applications/999999` | `404` `NOT_FOUND` | |
| BE-APP-13 | `$ADMIN` | `GET /api/applications/{id}/verifications` | `200`, per-step `StepResult` summary | |
| BE-APP-14 | DRAFT app, all checks PASS/REVIEW + agreement | `POST /api/applications/{id}/submit-kyc` (`$BORROWER`) | `200`, `KYC_PENDING` | |
| BE-APP-15 | DRAFT app, checks incomplete | `POST …/submit-kyc` | `422` `KYC_INCOMPLETE` | |
| BE-APP-16 | KYC_PENDING app, `$KYC` | `POST …/kyc-decision {decision:"APPROVE"}` | `200`, `KYC_APPROVED` | |
| BE-APP-17 | KYC_PENDING app, `$CEXEC` | `POST …/kyc-decision {decision:"APPROVE"}` | `422` `FORBIDDEN_ROLE` (needs KYC_APPROVER) | |
| BE-APP-18 | DRAFT app, `$KYC` | `POST …/kyc-decision` (illegal from DRAFT) | `422` `ILLEGAL_TRANSITION` | |
| BE-APP-19 | REVIEW_PENDING app, `$KYC` | `POST …/review-decision {decision:"APPROVE"}` | `200`, `PRE_APPROVED` | |
| BE-APP-20 | KYC_APPROVED app, `$BORROWER` | `POST …/apply {amountPaise:1000000,purpose:"x",salaryCreditDay:30}` | `200`, stays `KYC_APPROVED` (now "applied") | |
| BE-APP-21 | KYC_APPROVED app, `$BORROWER` | `POST …/apply {amountPaise:50000,…}` (< ₹1,000) | `422` (below `MIN_LOAN_PAISE` / over eligible limit) | |
| BE-APP-22 | PRE_APPROVED app, `$BORROWER` | `POST …/apply {…}` | `200`, **`DISBURSEMENT_PENDING`** (skips credit) | |
| BE-APP-23 | applied app, `$CHEAD` | `POST …/assign {executiveId:<id>}` | `200`, `CREDIT_EXEC_PENDING` | |
| BE-APP-24 | CREDIT_EXEC_PENDING, `$CEXEC` | `POST …/exec-decision {decision:"APPROVE"}` | `200`, `CREDIT_EXEC_APPROVED` → auto `CREDIT_HEAD_PENDING` | |
| BE-APP-25 | CREDIT_HEAD_PENDING, **same actor who recommended** | `POST …/head-decision {decision:"APPROVE"}` | `422` `SOD_VIOLATION` | |
| BE-APP-26 | CREDIT_HEAD_PENDING, a **different** `$CHEAD` | `POST …/head-decision {decision:"APPROVE",approvedAmountPaise:1000000}` | `200`, `CREDIT_HEAD_APPROVED` → auto `DISBURSEMENT_PENDING` | |
| BE-APP-27 | DISBURSEMENT_PENDING, `$DHEAD` | `POST …/disbursement-decision {decision:"APPROVE",txnRef:"TXN123"}` | `200`, **fast-path** `DISBURSED`→`ACTIVE` (mints loan, records `disbursal_txn_ref`) | |
| BE-APP-28 | DISBURSEMENT_PENDING, `$DHEAD` | `POST …/disbursement-decision {decision:"APPROVE"}` (no txnRef) | `200`, `ACCOUNTANT_PENDING` (routes to accountant) | |
| BE-APP-29 | ACCOUNTANT_PENDING, `$ACCT` | `POST …/accountant-validate {decision:"SUCCESS",txnRef:"TXN9"}` | `200`, `DISBURSED`→`ACTIVE` | |
| BE-APP-30 | ACCOUNTANT_PENDING, `$ACCT` | `POST …/accountant-validate {decision:"FAIL"}` | `200`, `DISBURSEMENT_FAILED` | |
| BE-APP-31 | DISBURSEMENT_FAILED, `$DHEAD` | `POST …/retry-disbursement` | `200`, `ACCOUNTANT_PENDING` | |
| BE-APP-32 | pre-disbursement app, `$BORROWER` (owner) | `POST …/cancel` | `200`, `CANCELLED` | |
| BE-APP-33 | another borrower's app, `$BORROWER` | `POST …/cancel` | `422` `FORBIDDEN` (can only cancel own) | |
| BE-APP-34 | app id, `$BORROWER` (owner) | `PUT …/profile {fullName,pan,…}` then `GET …/profile` | `200`; on read **PAN/Aadhaar masked** | |
| BE-APP-35 | app id, `$BORROWER` | `POST …/documents {fileName,contentType,base64}` then `GET …/documents` | `200`, metadata list (no bytes in list) | |
| BE-APP-36 | doc uploaded inline | `GET …/documents/{docId}` | `200`, base64 content (legacy/demo inline path) | |
| BE-APP-37 | S3-backed doc, `$KYC` | `GET …/documents/{docId}/url` | `200`, short-lived presigned GET URL (no bytes through backend) | |

### B.3 `ApplicationVerificationController` — `/api/applications/{id}/verify/*`

**Borrower-only + ownership** (a BORROWER may act only on **their own** app; **ADMIN** on any). Wrong
role → `FORBIDDEN_ROLE`; borrower acting on someone else's app → `FORBIDDEN_OWNER`.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-VRF-01 | own DRAFT app, `$BORROWER` | `POST …/verify/pan {pan:"QVEPS0901K"}` | `200`, `StepResult` `PASS` (Fintrix sandbox PAN with real data) | |
| BE-VRF-02 | same | re-POST `…/verify/pan` with same PAN | `200`, **same stored result, provider NOT re-called** (idempotent) | |
| BE-VRF-03 | `$KYC` (staff) | `POST …/verify/pan {pan:…}` | `422` `FORBIDDEN_ROLE` | |
| BE-VRF-04 | borrower B, app of borrower A | `POST …/verify/pan` | `422` `FORBIDDEN_OWNER` | |
| BE-VRF-05 | own app | `POST …/verify/pan {}` (no pan) | `400` `VALIDATION_ERROR` | |
| BE-VRF-06 | own app | `POST …/verify/email {officialEmail:"x@co.com"}` | `200`, `PASS`/`REVIEW` | |
| BE-VRF-07 | own app | `POST …/verify/address {latitude,longitude}` | `200`; out-of-India coords → `REVIEW` | |
| BE-VRF-08 | own app | `POST …/verify/address {manualAddress:"…"}` (no lat/long) | `200`, `REVIEW` (manual recorded) | |
| BE-VRF-09 | own app | `POST …/verify/digilocker/init {redirectUrl}` → `GET …/verify/digilocker/status` → `POST …/verify/digilocker/complete` | each `200`; complete cross-matches Aadhaar | |
| BE-VRF-10 | own app | `POST …/verify/bureau` | `200`; sandbox bureau is **thin-file** (expected — `REVIEW`/low, not a hard fail) | |
| BE-VRF-11 | own app | `POST …/verify/salary {monthlySalaryPaise:5000000,slipObjectKey}` | `200`; sets eligible-limit basis | |
| BE-VRF-12 | own app | `POST …/verify/penny-drop {accountNumber,ifsc}` | `200`; name-match ≥ 0.60 → `PASS`, **< 0.60 → `REVIEW`** | |
| BE-VRF-13 | own app | `POST …/verify/selfie {selfieObjectKey}` | `200`, `PASS`/`REVIEW` | |
| BE-VRF-14 | own app | `POST …/verify/agreement {versions:{...}}` | `200`; agreement consent recorded (gates submit-kyc) | |
| BE-VRF-15 | own app | `GET …/verify/summary` | `200`, list of all step results | |
| BE-VRF-16 | own app | `POST …/verify/presign-upload {docType,fileName,contentType}` | `200`, app-scoped presigned **PUT** target | |

### B.4 `LoanController` — `/api/loan/*` (read ledger)

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-LOAN-01 | ACTIVE loan id, any token | `GET /api/loan/{loanId}` | `200`, `LoanView` w/ **penalty/prepayment-aware** owed + effective status | |
| BE-LOAN-02 | — | `GET /api/loan/{loanId}` no token | `401` empty | |
| BE-LOAN-03 | any token | `GET /api/loan/999999` | `404` `NOT_FOUND` | |
| BE-LOAN-04 | `$ACCT` | `GET /api/loan/pending-repayments` | `200`, company-wide queue of PENDING_VERIFICATION repayments | |
| BE-LOAN-05 | `$ACCT` | `GET /api/loan/transactions?direction=INCOMING&q=<name>` | `200`, filtered ledger (OUTGOING disbursals + INCOMING repayments) | |
| BE-LOAN-06 | ACTIVE loan, any token | `GET /api/loan/{loanId}/outstanding?asOf=2026-07-01` | `200`, interest only to `asOf` (prepayment-aware); `settledAmountPaise` if a settlement approved | |

> Note: `pending-repayments` / `transactions` are **literal** paths and rank above `/{loanId}` — confirm
> they are not swallowed by the `{loanId}` capture (a regression magnet).

### B.5 `RepaymentController` — `/api/loan/{loanId}/repayments`

> RBAC is **not** enforced server-side here (deferred); the **BFF** restricts record→borrower,
> verify→accountant. So a raw curl with any valid token will pass the role check — flag if you expect a
> hard role gate.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-REP-01 | ACTIVE loan, `$BORROWER` | `POST …/repayments {amountPaise,method:"UPI",txnRef,proofUrl,paidOn}` | `200`, payment `PENDING_VERIFICATION` | |
| BE-REP-02 | — | `POST …/repayments` no token | `401` empty | |
| BE-REP-03 | loan id | `GET …/repayments` | `200`, list of payments for the loan | |
| BE-REP-04 | pending payment id, `$ACCT` | `POST …/repayments/{pid}/verify` | `200`, `VERIFIED`; outstanding reduced; at zero → loan + application `CLOSED` | |
| BE-REP-05 | `$BORROWER` | `POST …/repayments {amountPaise:-1}` | `400` `VALIDATION_ERROR` | |

### B.6 `CustomerController` — `/api/customers`

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-CUST-01 | any staff token | `GET /api/customers?q=` | `200`, distinct applicants w/ rolled-up counts + total outstanding | |
| BE-CUST-02 | — | same, no token | `401` empty | |
| BE-CUST-03 | staff token | `GET /api/customers/9000001` | `200`, full history (profile + applications + loans + payments) | |
| BE-CUST-04 | `$ADMIN` | `PUT /api/customers/9000001/profile {fullName:"New"}` | `200`, non-identity field updated | |
| BE-CUST-05 | `$KYC` (non-admin) | `PUT /api/customers/9000001/profile {…}` | `422` `FORBIDDEN_ROLE` (ADMIN-only in service) | |
| BE-CUST-06 | `$ADMIN` | `PUT …/profile {pan:"NEWPAN1234"}` | PAN/Aadhaar/mobile stay **locked** (identity fields rejected/ignored) | |

### B.7 `CollectionsController` — `/api/collections/*`

> RBAC deferred (any authenticated staff token works) **except** settlement **approve** enforces SoD
> (proposer ≠ approver) via `ActorContext`. So the negative for most rows is validation/not-found, not
> `FORBIDDEN_ROLE`.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-COL-01 | staff token | `GET /api/collections/loans?dueBy=2026-07-01` | `200`, ACTIVE/OVERDUE loans due ≤ date | |
| BE-COL-02 | staff token | `GET /api/collections/officers` | `200`, ACTIVE collection officers (real staff) | |
| BE-COL-03 | collectible loan id | `POST /api/collections/cases {loanId:<bigint>}` | `200`, case opened; loan flips `ACTIVE/OVERDUE → IN_COLLECTIONS` | |
| BE-COL-04 | — | `POST /api/collections/cases {loanId:999999}` | `422`/`404` (loan validation fails) | |
| BE-COL-05 | — | `GET /api/collections/cases` no token | `401` empty | |
| BE-COL-06 | case id (UUID) | `GET /api/collections/cases/{caseId}` | `200`, case detail | |
| BE-COL-07 | case id | `POST …/cases/{id}/assign {officerId:<staffId>}` | `200`, officer assigned | |
| BE-COL-08 | case id | `POST …/cases/{id}/interactions {type,outcome:"PAID"}` **without** proofRef | `422`/`400` (PAID requires a proof reference) | |
| BE-COL-09 | case id | `GET …/cases/{id}/interactions` | `200`, interaction log | |
| BE-COL-10 | case id, `$CHEAD_COLL` | `POST …/cases/{id}/settlements {settlementAmountPaise:N}` | `200`, settlement `PENDING` | |
| BE-COL-11 | — | `GET /api/collections/settlements` | `200`, all settlements | |
| BE-COL-12 | settlement, **same** actor who proposed | `POST …/settlements/{id}/approve` | `422` `SOD_VIOLATION` (proposer ≠ approver) | |
| BE-COL-13 | settlement, **different** head | `POST …/settlements/{id}/approve` | `200`, `APPROVED`; caps borrower outstanding | |
| BE-COL-14 | staff token | `GET /api/collections/dpd?dueDate=2026-06-01&asOf=2026-07-01` | `200`, `dpd:30` + bucket | |

### B.8 IAM — `StaffController` / `InviteController` / `BlocklistController`

> RBAC ADMIN-only is **deferred** on these (any authenticated staff token works today). Negative cases
> are validation/not-found + the 401 no-token.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-STF-01 | staff token | `GET /api/staff` | `200`, roster (incl. seeded V10 rows) | |
| BE-STF-02 | — | `GET /api/staff` no token | `401` empty | |
| BE-STF-03 | staff id | `GET /api/staff/{id}` | `200`, one staff | |
| BE-STF-04 | staff id | `PUT /api/staff/{id} {role:"ACCOUNTANT"}` | `200`, role updated | |
| BE-STF-05 | staff id | `DELETE /api/staff/{id}` | `200`, status → DISABLED | |
| BE-STF-06 | bad id | `GET /api/staff/999999` | `404` `NOT_FOUND` | |
| BE-INV-01 | staff token | `GET /api/staff/invites` | `200`, invite list | |
| BE-INV-02 | staff token | `POST /api/staff/invites {email,role}` | `200`, invite w/ one-time token | |
| BE-INV-03 | invite token | `POST /api/staff/invites/accept {token,...}` | `200`, staff activated | |
| BE-INV-04 | — | `POST /api/staff/invites {}` | `400` `VALIDATION_ERROR` | |
| BE-BLK-01 | staff token | `GET /api/admin/blocklist` | `200`, active entries | |
| BE-BLK-02 | staff token | `POST /api/admin/blocklist {type:"PAN",value:"ABCDE1234F",reason}` | `200`, entry added | |
| BE-BLK-03 | entry id | `DELETE /api/admin/blocklist/{id}` | `200`, deactivated | |
| BE-BLK-04 | — | `POST /api/admin/blocklist {}` | `400` `VALIDATION_ERROR` | |

### B.9 `PaymentSettingsController` — `/api/payment-settings`

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-PAY-01 | `$BORROWER` | `GET /api/payment-settings` | `200`, payee (UPI/account); presigned GET URLs for QR + account-info PDF if uploaded | |
| BE-PAY-02 | — | `GET /api/payment-settings` no token | `401` empty | |
| BE-PAY-03 | `$ADMIN` | `PUT /api/payment-settings {upiId:"x@bank",accountName,...}` | `200`, updated; singleton row (no new row) | |
| BE-PAY-04 | `$KYC` (non-admin) | `PUT /api/payment-settings {…}` | `422` `FORBIDDEN_ROLE` (ADMIN-only in service) | |

### B.10 `StorageController` — `/api/storage/*` (open, no token per plan decision 6)

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-STO-01 | — | `POST /api/storage/presign-upload {category,filename,contentType}` (no token) | `200`, `{key,url,method:"PUT",ttl}` — **no envelope** (raw DTO) | |
| BE-STO-02 | key from STO-01 | `GET /api/storage/presign-download?key=<key>` | `200`, presigned GET URL | |
| BE-STO-03 | — | `POST /api/storage/presign-upload {}` | `400` `VALIDATION_ERROR` | |
| BE-STO-04 | — | (security note) GET with a **guessed** key for another borrower | Currently succeeds — **flag**: download authz is a known TODO | |

### B.11 Legacy / dormant controllers (still mapped; require a token)

> These predate the single-aggregate flow and are **superseded** by `/api/applications`. They still
> respond. Confirm they exist but are not part of the live path. Note the onboarding `BorrowerController`
> OTP echoes the code in the response (demo) and is distinct from the real `/api/auth/borrower/otp/*`.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| BE-LEG-01 | staff token | `GET /api/income/{applicantId}` (`IncomeController` — **legacy**) | `200` or `404`; not used by the live flow | |
| BE-LEG-02 | staff token | `POST /api/kyc/case {borrowerId}` (`KycController` — **legacy**) | `200`; superseded by application kyc-decision | |
| BE-LEG-03 | staff token | `GET /api/disbursement/requests/{uuid}` (`DisbursementController` — **dormant UUID chain**) | `200`/`404`; superseded by the aggregate | |
| BE-LEG-04 | borrower/staff token | `POST /api/borrower {…}` + `/api/borrower/otp/request` (onboarding **legacy**) | `200`; OTP code echoed in response (demo, not the real auth OTP) | |
| BE-LEG-05 | — | any legacy endpoint, no token | `401` empty (all are behind `.authenticated()`) | |

---

## §C. Invariant spot-checks (numeric & state-machine)

Worked example loan = **₹10,000** (`1,000,000` paise). Use `GET /api/loan/{id}` after activation, or
Swagger, to read the minted figures.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| INV-01 | ₹10,000 loan minted | Inspect fee | Processing fee = **₹1,000** (`100,000` paise) = 10% | |
| INV-02 | same | Inspect GST | GST = **₹180** (`18,000` paise) = 18% **of the fee** | |
| INV-03 | same | Inspect net disbursed | Net = **₹8,820** (`882,000` paise) = principal − fee − GST | |
| INV-04 | ACTIVE loan, tenure T days | `GET …/outstanding` | Interest = 1%/day × principal × days held (capped at tenure) | |
| INV-05 | OVERDUE loan, D days late | `GET …/outstanding` | Penalty = 2%/day × principal × **min(D,30)** (cap 30 days); grace 1 day | |
| INV-06 | salary 80,000/mo | Eligible limit | = 25% floored to ₹100 = **₹20,000** (`2,000,000` paise) | |
| INV-07 | salary 8,150/mo edge | Eligible limit | 25% = ₹2,037.50 → floored to **₹2,000** (multiple of ₹100) | |
| INV-08 | disbursed 2026-06-24, salary day 30 | Due date | next salary credit ≤ 40 days = **2026-07-30** (36 days) | |
| INV-09 | disbursed 2026-06-03, salary day 30 | Due date | **2026-06-30** (27 days); total = **₹12,700** (`1,270,000` paise) | |
| INV-10 | DRAFT app | `POST …/kyc-decision` (skip submit) | `422` `ILLEGAL_TRANSITION` (no stage-skipping) | |
| INV-11 | checks incomplete | `POST …/submit-kyc` | `422` `KYC_INCOMPLETE` until all required PASS/REVIEW **and** agreement accepted | |
| INV-12 | recommender = approver | `POST …/head-decision` | `422` `SOD_VIOLATION` | |
| INV-13 | a PASSed verify step | re-POST it | Stored result returned, **provider not re-called** (idempotency) | |
| INV-14 | penny-drop name mismatch | `POST …/verify/penny-drop` w/ a name that scores < 0.60 | step = **`REVIEW`** (not hard FAIL) | |
| INV-15 | ACTIVE loan, full payment verified | accountant `…/verify` | loan + application → **`CLOSED`**; collection case (if any) drops off worklist | |
| INV-16 | approved partial settlement | `GET …/outstanding` on the loan | owed = `min(formulaOwed, settlement − verified)`; repay page shows "Settlement — full & final" | |
| INV-17 | past-due ACTIVE loan (no case yet) | `GET /api/loan/{id}` | reads as **`OVERDUE`** (compute-on-read `effectiveStatus`), stored column unchanged | |
| INV-18 | overdue loan, pay only no-penalty total | accountant verify | loan does **not** close — accrued late penalty stays owed (penalty-aware closure) | |
| INV-19 | OTP requested | (B.1 BE-AUTH-10/11/12) | single-use, 300 s TTL, 5-attempt cap all hold | |

---

## §D. UI matrix

> Borrower journey uses the `navix_borrower` cookie; staff uses `navix_staff`. They never share. Open a
> private window per persona to avoid cross-session bleed.

### D.1 Marketing — `/`

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-MKT-01 | logged out | Open `/` | Landing renders; primary CTA visible | |
| UI-MKT-02 | `/` | Click "Apply now" / sign-up CTA | Routes to `/signup/mobile-otp` (starts the live wizard) | |
| UI-MKT-03 | `/` | Click "Log in" | Routes to `/login` | |

### D.2 Borrower login — `/login`

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-LOGIN-01 | dev-echo on | Enter mobile `9819000001`, click Send OTP | "Dev code: NNNNNN" hint shown (from `data.devCode`) | |
| UI-LOGIN-02 | OTP shown | Enter the code, Verify | Lands on `/dashboard`; `navix_borrower` cookie set | |
| UI-LOGIN-03 | — | Enter wrong OTP | Inline error (INVALID_OTP); stays on `/login` | |
| UI-LOGIN-04 | dev-echo **off** | Send OTP | No dev-code hint; relies on real SMS (see §G — BLOCKED) | |

### D.3 Signup wizard — `/signup/*`

For **each** step: perform the action, observe the **PASS / REVIEW / FAIL** banner, confirm **back-nav
preserves entered data**, and confirm **bureau score / risk category are NEVER shown to the borrower**.

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-WIZ-01 | from CTA | `/signup/mobile-otp` — request + verify OTP | Establishes borrower session early; advances | |
| UI-WIZ-02 | in wizard | `/signup/email` — enter official email | PASS/REVIEW banner; advances | |
| UI-WIZ-03 | in wizard | `/signup/address` — capture geo or manual | Banner reflects within-India PASS vs manual REVIEW | |
| UI-WIZ-04 | in wizard | `/signup/digilocker` — init → callback | DigiLocker session animates; completion cross-matches | |
| UI-WIZ-05 | in wizard | `/signup/pan` — enter `QVEPS0901K` | PASS banner (sandbox PAN w/ real data) | |
| UI-WIZ-06 | in wizard | `/signup/bureau` — pull | Banner shows completion **without** a numeric score/grade to the borrower (thin-file ok) | |
| UI-WIZ-07 | in wizard | `/signup/salary` — enter salary + upload slip | Slip uploads via presigned PUT; PASS/REVIEW | |
| UI-WIZ-08 | in wizard | `/signup/penny-drop` — enter account+IFSC | Name-match < 0.60 → REVIEW banner; ≥ 0.60 → PASS | |
| UI-WIZ-09 | in wizard | `/signup/selfie` — capture | PASS/REVIEW; selfie uploaded | |
| UI-WIZ-10 | in wizard | `/signup/agreement` — **do not** accept, try to continue | Submit is **blocked** until the agreement is accepted | |
| UI-WIZ-11 | all steps done, agreement accepted | `/signup/review` → Submit | Runs create→profile→documents→submit-kyc; lands in KYC wait | |
| UI-WIZ-12 | a step still REVIEW/incomplete | Submit on `/review` | UI surfaces `KYC_INCOMPLETE` (cannot enter the queue) | |
| UI-WIZ-13 | any step | Fill, go Back, return | Entered values **persist** (no data loss) | |
| UI-WIZ-14 | — | `/signup/co-applicant` (cosmetic) | Animates; does not drive real state (mock layer) | |

### D.4 Borrower app pages

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-DASH-01 | ACTIVE loan | `/dashboard` | Shows net disbursed, salary-linked due date, total repayable | |
| UI-DASH-02 | no active loan | `/dashboard` | Empty/CTA state; offers apply | |
| UI-REPAY-01 | ACTIVE loan | `/repay` | Shows **payment-settings payee** (UPI QR / account from `GET /api/payment-settings`) + prepayment-aware "pay today" | |
| UI-REPAY-02 | `/repay` | Record a repayment (amount + proof) | Payment goes PENDING_VERIFICATION; confirmation shown | |
| UI-REPAY-03 | admin changed payee (UI-ADMIN-09) | reload `/repay` | New UPI/QR/account reflected | |
| UI-LOANS-01 | borrower w/ history | `/loans` | Past + current loans (from `GET /api/applications/mine` + per-loan reads) | |
| UI-TXN-01 | borrower w/ payments | `/transactions` | Past transactions list | |
| UI-RELOAN-01 | clean-history borrower | `/reloan` → Borrow again | Pre-approved; lands on `/loan/apply`; choosing amount → straight to Disbursement Head | |
| UI-RELOAN-02 | borrower w/ a past overdue | `/reloan` → Borrow again | Routed to KYC review (`REVIEW_PENDING`) — not auto pre-approved | |
| UI-PROF-01 | logged in | `/profile` | Profile renders (masked identity) | |
| UI-SET-01 | logged in | `/settings` | Account settings render | |
| UI-SUP-01 | logged in | `/support` | Help & FAQ / support content | |
| UI-MENU-01 | logged in | Account menu → Sign out | `navix_borrower` cleared + stored app id; routes to `/login` | |

### D.5 Staff login + middleware guard

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-SLOGIN-01 | logged out | Open `/staff/login` | Role grid (9 roles) with persona names | |
| UI-SLOGIN-02 | `/staff/login` | Click a role | BFF logs in (role→seeded email + `Admin@12345`); lands on `/staff/dashboard`; `navix_staff` set | |
| UI-SGUARD-01 | no staff cookie | Open `/staff/dashboard` directly | Redirect to `/staff/login?redirect=…` (middleware) | |
| UI-SGUARD-02 | no staff cookie | Open `/staff/login` / `/staff/activate` | Reachable (public staff paths) | |
| UI-SGUARD-03 | borrower cookie only | Open `/staff/applications` | Redirect to `/staff/login` (borrower cannot reach staff) | |

### D.6 Staff console pages

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-STF-DASH | any role | `/staff/dashboard` | Live counts/queues per role; ⓘ tooltips on cards; ADMIN sees transactions summary | |
| UI-STF-KYC | KYC_APPROVER | `/staff/kyc-approvals` | Queue + verification cards + ApplicantReview with **presigned doc links** | |
| UI-STF-REV | KYC_APPROVER | `/staff/kyc-review` | Reborrow review queue (REVIEW_PENDING) → clear/reject | |
| UI-STF-CQ | CREDIT_HEAD | `/staff/credit/queue` | Applied apps; "Assign to me" (ADMIN); assign→exec→head maker-checker | |
| UI-STF-CD | CREDIT_HEAD | `/staff/credit/{applicationId}` (detail) | Per-app detail + actions; loan-history block | |
| UI-STF-SOD | recommender = approver | On credit detail, attempt Approve as the recommender | Approve button **disabled** with SoD reason (and backend 422 if forced) | |
| UI-STF-DISB | DISBURSEMENT_HEAD | `/staff/disbursement` | Queue; accept (→ accountant) or finalize w/ txn id (fast-path); fast-track section for pre-approved | |
| UI-STF-ACC | ACCOUNTANT | `/staff/accounting` | Disbursement validate + **repayment-verify queue** | |
| UI-STF-TXN | ACCOUNTANT | `/staff/accounting/transactions` | Searchable company-wide ledger | |
| UI-STF-CB | COLLECTION_* | `/staff/collections/buckets` | DPD buckets, live DPD, collectible loans | |
| UI-STF-CS | COLLECTION_HEAD | `/staff/collections/settlements` | Settlement worklist; approve disabled for the proposer (SoD) | |
| UI-STF-CL | COLLECTION_* | `/staff/collections/{loanId}` | Case detail, interactions, settlement propose | |
| UI-STF-CUST | any role | `/staff/customers` (+ `/staff/customers/{applicantId}`) | Borrower roll-up incl. PII; ADMIN can correct KYC / take actions | |

### D.7 Admin pages

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-ADMIN-01 | ADMIN | `/staff/admin/staff` | Roster; edit role / disable | |
| UI-ADMIN-02 | ADMIN | `/staff/admin/invites` | List + create invite (one-time token) | |
| UI-ADMIN-03 | ADMIN | `/staff/admin/blocklist` | List + add + remove fraud entries | |
| UI-ADMIN-04 | ADMIN | `/staff/admin/payment-settings` | Edit UPI/account fields | |
| UI-ADMIN-08 | ADMIN | payment-settings → upload **QR image** + **account-info PDF** | Uploads persist (S3 keys) | |
| UI-ADMIN-09 | ADMIN | Save payee → open borrower `/repay` | `/repay` reflects the new payee + QR + account (ties to UI-REPAY-03) | |
| UI-ADMIN-10 | ADMIN | `/staff/activate` | Invite-accept / activation screen reachable | |

### D.8 Export menu (cross-cutting)

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| UI-EXP-01 | on DPD buckets / settlements / admin staff/invites/blocklist / transactions / customers | Click **Export ▾ → CSV** | CSV downloads with the table rows | |
| UI-EXP-02 | same | **Export ▾ → PDF** | NAVIX-branded PDF: wordmark, table, "Downloaded by {name} · {role} · {timestamp}", confidential footer | |

---

## §E. RBAC negative matrix (from `lib/auth/rbac.ts`)

One **denied page** + one **denied action** per role. Permission map: KYC_APPROVER=`kyc:approve`,
CREDIT_EXECUTIVE=`loan:review`, CREDIT_HEAD=`loan:approve`, DISBURSEMENT_HEAD=`loan:disburse`,
ACCOUNTANT=`loan:activate`, COLLECTION_HEAD=`collections:manage`+`interact`,
COLLECTION_EXECUTIVE=`collections:interact`, DEVELOPER=`customer:view` only, ADMIN=all. Every staff role
also has `customer:view`; only ADMIN has `staff:manage` + `customer:manage`.

| ID | Role | Denied PAGE (expect blocked/empty/no-action) | Denied ACTION (expect disabled or 422 `FORBIDDEN_ROLE`) | P/F |
|---|---|---|---|---|
| RBAC-01 | KYC_APPROVER | `/staff/credit/queue` (no approve controls) | `POST …/head-decision` → `422 FORBIDDEN_ROLE` | |
| RBAC-02 | CREDIT_EXECUTIVE | `/staff/disbursement` (no release) | No **Approve** (head) button; `POST …/head-decision` → 422 | |
| RBAC-03 | CREDIT_HEAD | `/staff/accounting` (no verify) | `POST …/accountant-validate` → `422 FORBIDDEN_ROLE` | |
| RBAC-04 | DISBURSEMENT_HEAD | `/staff/kyc-approvals` (no kyc decision) | `POST …/kyc-decision` → `422 FORBIDDEN_ROLE` | |
| RBAC-05 | ACCOUNTANT | `/staff/credit/queue` (no assign/approve) | `POST …/assign` → `422 FORBIDDEN_ROLE` | |
| RBAC-06 | COLLECTION_HEAD | `/staff/admin/staff` (no `staff:manage`) | `PUT /api/payment-settings` → `422 FORBIDDEN_ROLE` (ADMIN-only) | |
| RBAC-07 | COLLECTION_EXECUTIVE | `/staff/collections/settlements` approve hidden (no `collections:manage`) | Settlement **approve** unavailable | |
| RBAC-08 | DEVELOPER | All staff action pages read-only (only `customer:view`) | No business action available anywhere | |
| RBAC-09 | ADMIN | (control) all pages reachable | Walks KYC→ACTIVE solo, per-step; **exempt** from credit SoD + assign-exec requirement | |
| RBAC-10 | BORROWER (no staff cookie) | any `/staff/*` | Redirect to `/staff/login`; backend `FORBIDDEN_ROLE`/`401` | |

---

## §F. Responsive / cross-cutting

| ID | Precondition | Steps | Expected result | P/F |
|---|---|---|---|---|
| RES-01 | frontend up | From `frontend/`: `node scripts/mobile-overflow-audit.mjs` | 390 px sweep reports **no horizontal overflow** on the audited routes | |
| RES-02 | — | Resize browser to 390 px and walk borrower wizard + `/dashboard` + `/repay` | No clipped content / horizontal scroll | |
| RES-03 | — | Resize to 390 px and open staff `/staff/dashboard`, a queue, a DPD bucket | Tables/cards reflow; ⓘ tooltips reachable | |
| RES-04 | — | `cd frontend && npx tsc --noEmit && npx eslint .` | Both clean — **use these, not `npm run build`** | |
| RES-05 | — | (Do **not** rely on) `npm run build` | Known static-prerender failure at `/staff/admin/staff` ("React Client Manifest", Next 15.1.3) — environmental, not app code (§G) | |

---

## §G. Pass/Fail summary & known issues

### Roll-up

| Section | Area | Approx cases | Pass | Fail |
|---|---|---|---|---|
| §A | Setup & test data | 9 | | |
| §B | Backend API matrix | 86 | | |
| §C | Invariant spot-checks | 19 | | |
| §D | UI matrix | 56 | | |
| §E | RBAC negative matrix | 10 | | |
| §F | Responsive / cross-cutting | 5 | | |
| **Total** | | **~185** | | |

### Known issues / caveats (verify, don't fail blindly)

| KI | Item | Status | Workaround |
|---|---|---|---|
| KI-01 | **Real-SMS OTP delivery** (UltronSMS / DLT) | **BLOCKED — DLT template registration pending** | Test OTP via **`NAVIX_SMS_DEV_ECHO=true`** (code returned in `data.devCode` and shown as "Dev code:" in the UI) | 
| KI-02 | Frontend `npm run build` static-prerender at `/staff/admin/staff` | Known Next 15.1.3 env bug (reproduces on clean checkout) | Verify FE with `npm run dev` + `tsc --noEmit` + `eslint` (RES-04) |
| KI-03 | RBAC on Collections / Staff / Invite / Blocklist controllers | **Deferred** — any authenticated staff token passes | Only SoD (settlement approve) + ADMIN (payment-settings PUT, customer profile PUT) are enforced server-side today |
| KI-04 | `RepaymentController` record/verify role gate | Not enforced server-side (BFF restricts) | Treat the BFF as the gate; raw curl bypasses role |
| KI-05 | `StorageController` presign-download authz | Open route, key not ownership-checked (TODO) | BE-STO-04 |
| KI-06 | `IncomeController` / `KycController` / `DisbursementController` / onboarding `BorrowerController` | **Legacy/dormant**, superseded by `/api/applications` | Not part of the live path |
| KI-07 | Bureau / penny-drop in Fintrix **sandbox** | Bureau thin-file; PAN `QVEPS0901K` returns real data | Expected REVIEW, not a defect |

---

### Reference

- Strategy/automation: [`TESTING_PLAN.md`](TESTING_PLAN.md)
- Onboarding & architecture: [`CLAUDE.md`](CLAUDE.md)
- Stage-data seeding: [`populateDummyData.md`](populateDummyData.md) (`scripts/populate-demo-data.ps1`)
- State machine & roles: [`dfd.md`](dfd.md)
</content>
</invoke>
