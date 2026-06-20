# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**NAVIX Finance** is a salary-linked, single-repayment lending platform. The product: a borrower draws a short-term advance capped at **25% of monthly salary**, pays a **10% processing fee + 18% GST**, accrues **1%/day interest**, and repays in a **single installment on salary day**. Late penalty is **2%/day capped at 30 days**. Risk categories **A/B/C/D** affect limit and required checks, not price. Strict **maker-checker** separation of duties applies.

This is a monorepo containing:
- **Frontend:** Next.js 15 (App Router, `src/` dir structure) — two route groups: `(borrower)` for applicants, `(staff)` for internal staff (Credit Executive, Credit Head, Disbursement Head, Accountant).
- **Backend:** Spring Boot 3.4 (Java 21) — Maven multi-module project under `com.navix`.

---

## Quick Start

### 1. Environment setup
```bash
# Copy env template and fill in secrets (Fintrix, DigiLocker, DB, auth token secret)
cp backend/.env.example backend/.env  # or set env vars
```

**Key environment variables:**
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — PostgreSQL connection
- `NEXT_PUBLIC_API_BASE_URL` / `BACKEND_BASE_URL` — API URLs (default `http://localhost:8080`)
- `AUTH_SECRET` — signing secret for auth tokens
- `FINTRIX_BASE_URL`, `FINTRIX_CLIENT_ID`, `FINTRIX_CLIENT_SECRET` — Fintrix salary verification API
- `DIGILOCKER_CLIENT_ID`, `DIGILOCKER_CLIENT_SECRET` — DigiLocker KYC integration

### 2. Start the database
```bash
docker compose up -d
# Postgres 16 on localhost:5432, database name "navix"
# Adminer UI on http://localhost:8081
```

**MCP Docker Profile:** `docker.io/shubham0742/profile:latest` — used for development tooling via Claude's Docker MCP.

### 3. Backend (Spring Boot)
```bash
cd backend
./mvnw spring-boot:run -pl navix-app
```
Runs on `http://localhost:8080`, OpenAPI/Swagger at `http://localhost:8080/swagger-ui.html`

### 4. Frontend (Next.js)
```bash
cd frontend
npm install
npm run dev
```
Runs on `http://localhost:3000`

---

## Backend Architecture

### Build & run commands

```bash
# Build everything
cd backend && ./mvnw clean install

# Run bootable app (only navix-app is bootable)
./mvnw -pl navix-app spring-boot:run

# Run tests for a specific module
./mvnw test -pl navix-common

# Skip tests
./mvnw -DskipTests clean install
```

### Module structure

The Spring Boot backend is organized as a Maven multi-module project under `com.navix`:

| Module | Purpose | Status |
|--------|---------|--------|
| `navix-common` | Shared domain primitives, DTOs, errors, fee/money math, base config | Partial impl |
| `navix-iam` | Authentication, users, roles, separation-of-duties (maker-checker) enforcement | Scaffolding |
| `navix-onboarding` | Applicant onboarding and application intake | Scaffolding |
| `navix-kyc` | KYC / DigiLocker integration | Scaffolding |
| `navix-verification` | Employment, salary verification (Fintrix) | Scaffolding |
| `navix-income-risk` | Salary analysis, risk categorisation (A/B/C/D), limit computation | Scaffolding |
| `navix-loan` | Loan offer, fee/interest calculation, approval workflow, repayment/prepayment | Scaffolding |
| `navix-disbursement` | Disbursement release and accountant bank-transfer confirmation | Scaffolding |
| `navix-collections` | Late penalty accrual and collections handoff | Scaffolding |
| `navix-storage` | S3 / file storage abstraction (AWS Spring Cloud integration) | Scaffolding |
| `navix-app` | Bootable Spring Boot entrypoint (the only runnable JAR) | Scaffolding |

- Business modules (all except `navix-app`) are plain JARs.
- Only `navix-app` has `spring-boot-maven-plugin` and depends on the other 9 modules.
- All business modules depend on `navix-common`.
- Currently at scaffolding stage: module tree and configuration only; business logic is stubbed with `TODO`s.

### Key dependencies

- **Spring Boot 3.4.1** — parent BOM manages spring-boot-starter-* versions
- **Spring Cloud AWS 3.3.0** — S3, SSM Parameter Store, AWS SDK v2
- **springdoc-openapi 2.7.0** — pinned in parent `<dependencyManagement>`, Swagger UI at `/swagger-ui.html`
- **PostgreSQL driver** — managed by Spring Boot BOM
- **Flyway** — database migrations (version managed by Spring Boot BOM)
- **Lombok** — code generation (version managed by Spring Boot BOM)

---

## Frontend Architecture

### Route groups

The Next.js App Router uses two route groups (in parentheses) to separate audiences without affecting URL paths:

- `src/app/(borrower)` — borrower-facing public flows: landing page, login, signup, loan application, repayment
- `src/app/(staff)` — internal back-office flows for maker-checker roles (Credit Executive, Credit Head, Disbursement Head, Accountant)

Each group can have its own layout.

### Build & run commands

```bash
cd frontend

# Development server
npm run dev                 # http://localhost:3000

# Production build and start
npm run build
npm start

# Linting
npm lint
```

### Stack & dependencies

- **Next.js 15** (App Router)
- **React 19** 
- **TypeScript 5.7**
- **Tailwind CSS 3.4** (styling)
- **React Hook Form 7.54** (form state)
- **TanStack React Query 5.62** (server state)
- **Zustand 5.0** (client state)
- **Zod 3.24** (validation)
- **Lucide React 0.469** (icons)

### Middleware & role gating

`src/middleware.ts` guards the `(staff)` routes:
- Reads session cookie
- Redirects unauthenticated users to `/login`
- Redirects authenticated users whose role doesn't permit a staff route back to borrower area

---

## External integrations

### Fintrix (salary verification)

- **Base URL:** `https://admin.fintrix.tech/__api/api/v1/`
- **Auth:** HTTP Basic using `base64(FINTRIX_CLIENT_ID:FINTRIX_CLIENT_SECRET)`
- **Purpose:** Employment and salary verification for risk categorization

### DigiLocker (KYC / document verification)

- **Auth:** Headers `X-Client-ID` and `X-Client-Secret` from environment variables
- **Purpose:** Document verification and identity checks

See `Digilocker_API_Guide.md` and `NAVIX_Fintrix_Integration_Flow.md` for integration details.

---

## Development workflow

### Adding a new API endpoint

1. Add domain logic to a business module's `service` package (e.g., `navix-loan`)
2. Add REST controller to `navix-app` referencing the service
3. Rely on `navix-common` for shared DTOs, error handling, and utilities
4. Leverage Flyway for schema changes (migrations in `navix-common/src/main/resources/db/migration/`)
5. Test against local PostgreSQL (via Docker Compose)

### Making backend-only changes

Only `navix-app` is bootable; run:
```bash
./mvnw -pl navix-app spring-boot:run
```

To test a specific module's logic without launching the full app, write unit tests and run:
```bash
./mvnw test -pl navix-<module-name>
```

### Making frontend-only changes

```bash
cd frontend && npm run dev
```

The frontend talks to the backend via `NEXT_PUBLIC_API_BASE_URL`.

---

## Project structure at a glance

```
Navix-Finance/
├── backend/                    # Spring Boot Maven multi-module
│   ├── navix-common/          # Shared types, DTOs, config
│   ├── navix-iam/             # Auth & roles
│   ├── navix-onboarding/      # Applicant intake
│   ├── navix-kyc/             # DigiLocker integration
│   ├── navix-verification/    # Fintrix integration
│   ├── navix-income-risk/     # Risk categorization
│   ├── navix-loan/            # Loan workflow
│   ├── navix-disbursement/    # Disbursement & activation
│   ├── navix-collections/     # Collections & overdue
│   ├── navix-storage/         # S3 abstraction
│   ├── navix-app/             # Bootable app (only this is runnable)
│   ├── pom.xml                # Parent BOM
│   └── mvnw                   # Maven Wrapper
├── frontend/                   # Next.js 15 (App Router)
│   ├── src/
│   │   ├── app/
│   │   │   ├── (borrower)/   # Borrower-facing routes
│   │   │   ├── (staff)/      # Staff maker-checker routes
│   │   │   ├── middleware.ts # Route gating
│   │   ├── components/       # React components
│   │   ├── lib/              # Utilities
│   │   ├── hooks/            # Custom React hooks
│   │   ├── stores/           # Zustand state
│   └── package.json
├── docker-compose.yml         # PostgreSQL 16 + Adminer
├── README.md                  # Product overview
├── NAVIX_Finance_Product_Flow.md
├── NAVIX_Fintrix_Integration_Flow.md
└── Digilocker_API_Guide.md
```

---

## Notes for future development

- **Status:** Scaffolding stage. Module tree and configuration are in place; business logic is stubbed with `TODO`s and will be filled in module by module.
- **Maker-checker:** Separation of duties is a hard architectural requirement. Any approval endpoint must check user roles (Credit Executive, Credit Head, Disbursement Head, Accountant).
- **Configuration:** Never commit secrets to the repo. Use environment variables or `application-local.yml` (gitignored).
- **Migrations:** Use Flyway (in `navix-common/src/main/resources/db/migration/`) for schema changes.
- **OpenAPI:** Swagger UI is generated via springdoc; check `http://localhost:8080/swagger-ui.html` after launch.
