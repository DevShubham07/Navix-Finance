# NAVIX Finance — Project Status & Audit
> Branch: `dev/project-audit` | Date: 2026-06-24

---

## 1. What the product is

NAVIX Finance is a **digital short-term lending platform** for salaried individuals.

| Rule | Value |
|---|---|
| Loan limit | Up to **25% of monthly salary** |
| Processing fee | **10%** of loan (deducted upfront) |
| GST | **18%** on processing fee |
| Interest | **1% / day** on principal |
| Late penalty | **2% / day**, capped at 30 days |
| Repayment | Single payment on salary date (max ~40 days) |
| Prepayment | Anytime, no penalty |

The full product spec lives in `NAVIX_Finance_Product_Flow.md`.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Frontend | Next.js 15, React 19, TypeScript, TailwindCSS, Zustand, React Query |
| Backend | Spring Boot (Java 21), Maven multi-module |
| Database | PostgreSQL 16 (Flyway migrations) |
| Storage | AWS S3 (presigned URLs via `navix-storage`) |
| External APIs | Fintrix (KYC/PAN/DigiLocker/bureau), DigiLocker OAuth |
| Infrastructure | Docker (Colima locally), Dockerfile.backend, AWS (SSM param store) |
| API Docs | Springdoc OpenAPI → `/swagger-ui.html` |

---

## 3. Repository Structure

```
navix_final/
├── backend/                    # Spring Boot multi-module Maven project
│   ├── navix-app/              # Main boot module (configs, Flyway, security)
│   ├── navix-common/           # Shared: BaseAuditEntity, ApiResponse, Masking
│   ├── navix-iam/              # Staff identity & access management
│   ├── navix-onboarding/       # Borrower signup flow
│   ├── navix-kyc/              # KYC case tracking
│   ├── navix-verification/     # External API clients (Fintrix, DigiLocker, bureau)
│   ├── navix-income-risk/      # Risk scoring, limit calculation
│   ├── navix-loan/             # Loan lifecycle + repayments
│   ├── navix-disbursement/     # Maker-checker disbursement approval chain
│   ├── navix-collections/      # Overdue case management
│   └── navix-storage/          # S3 presigned URL service
├── frontend/                   # Next.js app
│   └── src/
│       ├── app/
│       │   ├── (borrower)/     # All borrower-facing pages (signup, KYC, loan, repay)
│       │   ├── (marketing)/    # Public landing page
│       │   ├── staff/          # All staff portal pages
│       │   └── api/            # Next.js BFF route handlers
│       ├── components/         # UI components (borrower, staff, site, ui primitives)
│       ├── lib/
│       │   ├── mock/           # Full in-memory mock data layer (Zustand + localStorage)
│       │   ├── calc/           # Loan math, risk calculation
│       │   ├── domain/         # TypeScript domain types
│       │   ├── api/            # Real API client (Fintrix, DigiLocker)
│       │   └── auth/           # Session management, RBAC
│       └── stores/             # Zustand application store
├── docker-compose.yml          # PostgreSQL + Adminer
└── Dockerfile.backend          # Backend container
```

---

## 4. What Has Been Built

### 4.1 Backend — 112 Java source files across 10 modules

#### ✅ Fully scaffolded (entities + repositories + service skeletons + controllers)

| Module | Entities | Controller Routes |
|---|---|---|
| `navix-iam` | `StaffUser`, `StaffInvite`, `BlocklistEntry` | `GET/PUT/DELETE /api/staff`, `POST /api/staff/invites`, `POST /api/staff/invites/accept`, `GET/POST/DELETE /api/admin/blocklist` |
| `navix-onboarding` | `Borrower`, `CoApplicant`, `SignupApplication` | `GET /api/borrower/{id}` |
| `navix-kyc` | `KycCase`, `KycCheck`, `DigiLockerSession` | `GET /api/kyc/case/{borrowerId}` |
| `navix-income-risk` | `IncomeProfile`, `RiskAssessment` | `GET /api/income/{applicantId}` |
| `navix-loan` | `Loan`, `LoanApplication`, `LoanDocument`, `Payment` | `POST /api/loan/applications`, `GET /api/loan/{loanId}`, `POST/GET /api/loan/{loanId}/repayments` |
| `navix-disbursement` | `DisbursementRequest`, `ApprovalStep` | Base `@RequestMapping("/api/disbursement")` — no handlers yet |
| `navix-collections` | `CollectionCase`, `InteractionLog`, `RepaymentPlan`, `Settlement` | Base `@RequestMapping("/api/collections")` — no handlers yet |
| `navix-storage` | — | `POST /api/storage/presign-upload`, `GET /api/storage/presign-download` |
| `navix-verification` | — (client-only) | No controllers — pure integration clients |
| `navix-common` | `BaseAuditEntity` | `ApiResponse<T>` wrapper, `Masking` util |

#### ✅ Database
- **21 tables** created via Flyway `V1__init.sql` + `V2__core_schema.sql`
- All tables + indexes are live in Postgres; **0 rows of data** (empty DB, no seed)
- Schema perfectly mirrors JPA entity mappings; Hibernate validates on startup

#### ✅ Infrastructure
- `SecurityConfig`: 3 filter chains defined (borrower, staff, default) — all currently `permitAll` (auth not yet enforced)
- `OpenApiConfig`: Swagger UI live at `http://localhost:8080/swagger-ui.html`
- `WebConfig`: CORS configured for frontend origin
- AWS SSM param store integration (`optional:` prefix — app boots without AWS locally)
- `StorageProperties`, `FintrixProperties`, `DigiLockerProperties` — all wired via `application.yml`

#### ⚠️ Service layer — skeletons only (logic not implemented)
All service classes exist with method signatures and `// TODO: implement` bodies:

| Service | What's missing |
|---|---|
| `OtpService` | OTP generation, persistence, dispatch |
| `BorrowerService` | Create borrower + SignupApplication, trigger OTP |
| `BureauService` | Aggregate CRIF/Experian signals |
| `RiskScoringService` | Full risk scoring algorithm |
| `LimitCalculator` | 25% salary cap + risk-adjusted limit |
| `InviteService` | Send invite email, accept flow |
| `ApprovalChainService` | Maker-checker state machine for disbursement |
| `PennyDropGate` | Bank account verification before disbursement |
| `AccountantConfirmationService` | Manual transfer confirmation |
| `CollectionsService` | DPD bucket assignment, officer workflows |
| `SettlementService` | Propose/approve settlement |

#### ⚠️ External API clients — stub implementations
All 7 Fintrix/DigiLocker clients in `navix-verification` are scaffolded with HTTP client wiring but return placeholder/empty responses:

- `PanComprehensiveClient` — PAN validation + Aadhaar link check
- `DigiLockerClient` — OAuth flow, Aadhaar fetch
- `ExperianClient` + `CrifClient` — credit bureau pulls
- `PennyDropClient` — bank account verification
- `EmailVerificationClient` — official email domain check
- `AddressVerificationClient` — address proof check

---

### 4.2 Frontend — 114 TypeScript/TSX files

#### ✅ Fully built UI (all screens render with mock data)

**Borrower portal** (`/` → borrower login/signup → dashboard):
- Landing/marketing page with EMI calculator
- Login page
- 12-step signup wizard: Mobile OTP → Personal email → Official email → PAN → Employment → Salary → Bank → Address proof → Co-applicant → Financials → Review
- KYC flow: DigiLocker OAuth redirect + callback, selfie capture
- Loan application: amount chooser with live cost breakdown, document upload, bank verification
- Loan status tracker (APPLIED → DISBURSING → ACTIVE → REPAID)
- Repayment page
- Re-loan fast track
- Borrower profile

**Staff portal** (`/staff/login` → role-based dashboard):
- Staff login + invite activation
- KYC approvals queue
- Credit queue + per-application review (`/staff/credit/[applicationId]`)
- Disbursement release page
- Accounting / transfer confirmation page
- Collections: DPD bucket view, case detail (`/staff/collections/[loanId]`), settlement management
- Admin: staff management, invite management, blocklist management
- Role-based navigation (different nav per role)

**Shared components:**
- Full UI primitive library: `Button`, `Card`, `Input`, `Select`, `Badge`, `Table`, `Dialog`
- `maker-checker-bar`, `approval-trail`, `dpd-badge`, `staff-role-bar`
- `step-progress`, `wizard-actions`, `otp-input`, `loan-cost-breakdown`, `amount-chooser`

#### ✅ Mock data layer (fully functional, localStorage-persisted)
The entire app runs **end-to-end in demo mode** with a Zustand store (`useMockDb`) backed by `localStorage`. All approval workflows (KYC → credit → disbursement → accountant → active loan → collections) work through the mock layer. Seeded with sample applications, staff, borrowers, and collection cases.

#### ✅ Real API wiring (partial)
- `lib/api/client.ts` — typed fetch wrapper for the Spring Boot backend
- `lib/api/fintrix.ts` — Fintrix API integration
- `lib/api/digilocker.ts` — DigiLocker OAuth
- Next.js BFF route handlers: `/api/auth`, `/api/kyc/pan`, `/api/kyc/digilocker`, `/api/loan`, `/api/webhooks/fintrix`
- `NEXT_PUBLIC_DEMO_MODE=false` env var switches from mock → real backend (toggle not yet production-ready)

#### ✅ Auth & RBAC
- `lib/auth/session.ts` — session management
- `lib/auth/rbac.ts` — role-based access control types
- `middleware.ts` — route protection
- `use-session.ts` hook

#### ✅ Loan math
- `lib/calc/loan-math.ts` — fee, GST, interest, due date, total repayable
- `lib/calc/risk.ts` — risk category helpers

---

## 5. What's NOT Done (Gaps)

### Backend
| Gap | Where |
|---|---|
| Authentication / JWT | `SecurityConfig` — all routes are `permitAll` |
| All service method bodies | Every service class is a TODO skeleton |
| Disbursement controller endpoints | `DisbursementController` has no `@PostMapping` handlers |
| Collections controller endpoints | `CollectionsController` has no `@GetMapping/@PostMapping` handlers |
| Borrower controller write path | `BorrowerController` only has GET — no POST to create borrower |
| External API real calls | All 7 verification clients return stubs |
| OTP sending | `OtpService.sendOtp()` not implemented |
| Separation-of-duties enforcement | `SeparationOfDutiesGuard.enforce()` not implemented |
| Tests | **Zero test files** exist |
| Seed data | DB is empty — no dev seed data |

### Frontend
| Gap | Where |
|---|---|
| Real API integration | `NEXT_PUBLIC_DEMO_MODE` still defaults to `true` |
| BFF route handlers incomplete | Route handlers exist but don't fully proxy to backend |
| No E2E tests | Playwright dependency installed but no test files |

### Infrastructure
| Gap | Where |
|---|---|
| AWS credentials | SSM param store not configured locally (app uses defaults) |
| S3 bucket | Not created — storage calls will fail |
| Fintrix credentials | `FINTRIX_CLIENT_ID/SECRET` not set |
| DigiLocker credentials | `DIGILOCKER_CLIENT_ID/SECRET` not set |

---

## 6. Running Locally

### Prerequisites
- Colima (Docker runtime): `colima start`
- Java 21, Maven (via `./mvnw`)
- Node.js 24, npm

### Start services

```bash
# 1. Start Postgres + Adminer
docker compose up -d db adminer

# 2. Build all Maven modules (first time only)
cd backend && ./mvnw install -DskipTests -q

# 3. Start backend
./mvnw spring-boot:run -pl navix-app

# 4. Start frontend (separate terminal)
cd frontend && npm install && npm run dev
```

### Service URLs

| Service | URL | Credentials |
|---|---|---|
| Frontend | http://localhost:3000 | — |
| Backend health | http://localhost:8080/actuator/health | — |
| Swagger UI | http://localhost:8080/swagger-ui.html | — |
| Adminer (DB UI) | http://localhost:8081 | Server: `db`, User: `navix`, Pass: `navix`, DB: `navix` |

> The frontend runs in **demo mode** by default (all data is mocked in-browser). Set `NEXT_PUBLIC_DEMO_MODE=false` in `frontend/.env.local` to route through the real backend.

---

## 7. Git History Summary

| Commit | What happened |
|---|---|
| `7684f40` | Initial project setup — full scaffold of all modules |
| `8536bc4` | Fix frontend build failures (Next.js config) |
| `cb28081` | Fix backend build + startup failures |
| `4e13f51` | Add `V2__core_schema.sql` — all 21 tables |
| `d88c6ea` | Latest commit (minor updates) |

---

## 8. Overall Assessment

**The project is a well-structured scaffold at ~30% completion.**

- ✅ Architecture is sound: clean module separation, correct domain model, proper maker-checker flow design
- ✅ UI is production-quality: every screen exists, works end-to-end in demo mode
- ✅ Database schema is complete and live
- ❌ Backend has no working business logic — all services are stubs
- ❌ No authentication enforced anywhere
- ❌ No tests at all
- ❌ External integrations (Fintrix, DigiLocker, S3) are not connected

The next logical build order:
1. Implement service bodies (start with IAM + onboarding)
2. Wire authentication (JWT for staff, borrower token)
3. Connect external API clients (PAN, DigiLocker)
4. Implement disbursement + collections controller endpoints
5. Switch frontend out of demo mode
6. Add tests
