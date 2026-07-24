# DhanBoost — Backend

Spring Boot (3.4.1) Maven multi-module backend for **DhanBoost**, a salary-linked
single-repayment lending platform.

- **Java:** 21
- **Build:** Maven (multi-module aggregator), Maven Wrapper included (`./mvnw`)
- **groupId / version:** `com.navix` / `0.0.1-SNAPSHOT`
- **HTTP port:** `8080`

The product: a borrower can take a short-term loan capped at **25% of monthly salary**,
pays an up-front **10% processing fee + 18% GST on that fee**, accrues **1%/day interest**
(prepay anytime, no penalty), and makes a **single repayment on salary day**. Late penalty
is **2%/day capped at 30 days**, after which the loan moves to collections. Risk categories
**A/B/C/D** affect limit and required checks — not price. A strict **maker-checker** split
applies: Credit Executive (review) != Credit Head (final approve) != Disbursement Head
(release), and an Accountant manually confirms the bank transfer to activate the loan.

## Modules

| Module                | artifactId           | Purpose |
|-----------------------|----------------------|---------|
| Common                | `navix-common`       | Shared domain primitives, DTOs, errors, money/fee math, base config. Every business module depends on this. |
| IAM                   | `navix-iam`          | Authentication, users, roles & separation-of-duties (maker-checker) enforcement. |
| Onboarding            | `navix-onboarding`   | Applicant onboarding and application intake. |
| KYC                   | `navix-kyc`          | Identity / KYC checks (incl. DigiLocker integration). |
| Verification          | `navix-verification` | Identity/bureau/penny-drop/DigiLocker verification (Signzy primary + Digitap fallback). |
| Income & Risk         | `navix-income-risk`  | Salary/income analysis, risk categorisation (A/B/C/D) and limit computation. |
| Loan                  | `navix-loan`         | Loan offer, fee/interest calculation, approval workflow, repayment & prepayment. |
| Disbursement          | `navix-disbursement` | Disbursement release and accountant bank-transfer confirmation / activation. |
| Collections           | `navix-collections`  | Late penalty accrual and collections handoff. |
| App (bootable)        | `navix-app`          | Spring Boot entrypoint; aggregates all modules. The only runnable artifact. |

Business modules are plain JARs depending on `com.navix:navix-common:0.0.1-SNAPSHOT`.
Only `navix-app` carries the `spring-boot-maven-plugin` and depends on the other 9 modules.

`springdoc-openapi-starter-webmvc-ui` is pinned to **2.7.0** in the parent
`<dependencyManagement>`; spring-boot starters, Lombok, PostgreSQL and Flyway versions are
managed by the Spring Boot parent BOM and are not pinned.

## Prerequisites

- **JDK 21** (`java -version` should report 21).
- **PostgreSQL** — easiest via the repo `docker-compose` (see repo root).
- Internet access on first run so the Maven Wrapper can download Apache Maven 3.9.9.

## Configuration (environment variables)

The backend reads configuration from the environment. Do **not** commit secrets — keep
them in an `application-local.yml` (gitignored) or export them in your shell.

| Variable                  | Description |
|---------------------------|-------------|
| `BACKEND_BASE_URL`        | Public base URL of this backend (default `http://localhost:8080`). |
| `AUTH_SECRET`             | Signing secret for auth tokens. |
| `DB_URL`                  | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/navix`. |
| `DB_USERNAME`             | Database username. |
| `DB_PASSWORD`             | Database password. |
| `SIGNZY_TOKEN`            | Signzy raw opaque `Authorization` token (PRIMARY verification provider). |
| `SIGNZY_CLIENT_UNIQUE_ID`| Signzy account unique id, sent as the `x-client-unique-id` header (e.g. `info@dhanboost.com`). |
| `SIGNZY_BASE_URL`        | Signzy base URL (default preprod `https://api-preproduction.signzy.app`; prod `https://api.signzy.app`). |
| `DIGITAP_CLIENT_ID`      | Digitap client id (FALLBACK provider; HTTP Basic `base64(client_id:client_secret)`). |
| `DIGITAP_CLIENT_SECRET`  | Digitap client secret. |
| `DIGITAP_SVC_BASE_URL`   | Digitap svc host — KYC/Employment/Email (default preprod `https://svcdemo.digitap.work`; prod `https://svc.digitap.ai`). |
| `DIGITAP_API_BASE_URL`   | Digitap api host — Credit/Location/Face-Match/OCR (default preprod `https://apidemo.digitap.work`; prod `https://api.digitap.ai`). |
| `NAVIX_VERIFICATION_CHAIN`| Provider routing order (default `signzy,digitap`). |

> Keys placed in `backend/.env` are auto-loaded (spring-dotenv); alternatively export them or use SSM. Signzy is the primary provider, Digitap the per-capability fallback (see `docs/signzy/`, `docs/digitap/` and `CLAUDE.md` §14).

## Build & Run

Build everything:

```sh
./mvnw clean install
```

Run the application (only `navix-app` is bootable):

```sh
./mvnw -pl navix-app spring-boot:run
```

The API is then available at `http://localhost:8080` and the OpenAPI UI at
`http://localhost:8080/swagger-ui.html`.

> The frontend (Next.js) runs at `http://localhost:3000` and talks to this backend via
> `NEXT_PUBLIC_API_BASE_URL` / `BACKEND_BASE_URL`.

## Status

Scaffolding stage — module tree and configuration only. Business logic is stubbed with
`TODO`s and will be filled in module by module.
