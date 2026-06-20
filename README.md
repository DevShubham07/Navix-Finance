# NAVIX Finance

Salary-linked, single-repayment lending platform. A borrower can draw a short-term
advance capped at **25% of monthly salary**, repaid in a **single installment on the
next salary day**.

This is a monorepo containing:

- **`frontend/`** — Next.js 15 (App Router, `src/` dir) borrower + staff web app.
- **`backend/`** — Spring Boot 3.4 multi-module Maven project (Java 21).
- **Product docs** (repo root):
  - [`NAVIX_Finance_Product_Flow.md`](./NAVIX_Finance_Product_Flow.md) — end-to-end product flow.
  - [`NAVIX_Fintrix_Integration_Flow.md`](./NAVIX_Fintrix_Integration_Flow.md) — Fintrix integration.
  - [`Digilocker_API_Guide.md`](./Digilocker_API_Guide.md) — DigiLocker API reference.
  - [`NAVIX.postman_collection.json`](./NAVIX.postman_collection.json) — API collection.

---

## Product at a glance

- **Eligibility & limit:** loan cap = 25% of monthly salary.
- **Pricing:** up-front **10% processing fee** + **18% GST** on that fee; **1%/day interest**
  (prepay anytime, no penalty).
- **Repayment:** single repayment on salary day — the last salary credit must fall within
  ~40 days of disbursement.
- **Late handling:** penalty **2%/day capped at 30 days**, then moves to collections.
- **Risk categories A/B/C/D:** affect the limit and the checks required, **not** the price.
- **Maker-checker (separation of duties):**
  Credit Executive (review) ≠ Credit Head (final approve) ≠ Disbursement Head (release);
  the Accountant manually confirms the bank transfer to activate the loan.

---

## Architecture

```
Browser ──> Frontend (Next.js, http://localhost:3000)
              │
              ▼
        Backend (Spring Boot, http://localhost:8080)
              │
              ├─> Fintrix     https://admin.fintrix.tech/__api/api/v1/
              ├─> DigiLocker
              └─> PostgreSQL 16
```

### Frontend route groups

The App Router uses route groups to separate the two audiences:

- `(borrower)` — applicant-facing onboarding, KYC, loan application, repayment.
- `(staff)` — internal maker-checker console (Credit Executive, Credit Head,
  Disbursement Head, Accountant).

### Backend modules

Maven multi-module under `com.navix`:

| Module | Responsibility |
| --- | --- |
| `navix-common` | shared types, utilities, base config (plain jar) |
| `navix-iam` | identity, auth, roles, separation-of-duties |
| `navix-onboarding` | applicant onboarding |
| `navix-kyc` | KYC / DigiLocker |
| `navix-verification` | document & data verification |
| `navix-income-risk` | income analysis + risk category A/B/C/D |
| `navix-loan` | loan application, pricing, approval lifecycle |
| `navix-disbursement` | release & bank transfer confirmation |
| `navix-collections` | overdue handling & collections |
| `navix-app` | bootable Spring Boot app, depends on all 9 modules |

Only `navix-app` is bootable; the other modules are plain jars depending on `navix-common`.

---

## Prerequisites

- **Node.js 20+** (frontend)
- **Java 21** (backend)
- **Docker** (Postgres + Adminer via Docker Compose)

---

## Getting started

### 1. Environment

Copy the example env values and fill in secrets (see `.env`). Key variables:

```
NEXT_PUBLIC_API_BASE_URL   # frontend -> backend base URL
BACKEND_BASE_URL
AUTH_SECRET
FINTRIX_BASE_URL           # https://admin.fintrix.tech/__api/api/v1/
FINTRIX_CLIENT_ID
FINTRIX_CLIENT_SECRET
DIGILOCKER_CLIENT_ID
DIGILOCKER_CLIENT_SECRET
DB_URL                     # jdbc:postgresql://localhost:5432/navix
DB_USERNAME
DB_PASSWORD
```

### 2. Database (Docker)

```bash
docker compose up -d
```

- Postgres 16 on `localhost:5432` (database `navix`).
- Adminer UI on http://localhost:8081 (server: `db`).

### 3. Backend (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run -pl navix-app
```

Runs on http://localhost:8080. OpenAPI/Swagger UI via springdoc.

### 4. Frontend (Next.js)

```bash
cd frontend
npm install
npm run dev
```

Runs on http://localhost:3000.

---

## Integrations

- **Fintrix** — base URL `https://admin.fintrix.tech/__api/api/v1/`.
  Auth: HTTP Basic `base64(client_id:client_secret)` from
  `FINTRIX_CLIENT_ID` / `FINTRIX_CLIENT_SECRET`.
- **DigiLocker** — auth via `X-Client-ID` / `X-Client-Secret` headers from
  `DIGILOCKER_CLIENT_ID` / `DIGILOCKER_CLIENT_SECRET`.

See the product docs linked above for full integration details.
