# NAVIX Finance — Backend

Spring Boot (3.4.1) Maven multi-module backend for **NAVIX Finance**, a salary-linked
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
| Verification          | `navix-verification` | Employment, salary and document verification (Fintrix integration). |
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
| `FINTRIX_BASE_URL`        | Fintrix API base, e.g. `https://admin.fintrix.tech/__api/api/v1/`. |
| `FINTRIX_CLIENT_ID`       | Fintrix client id (HTTP Basic `base64(client_id:client_secret)`). |
| `FINTRIX_CLIENT_SECRET`   | Fintrix client secret. |
| `DIGILOCKER_CLIENT_ID`    | DigiLocker client id (sent as `X-Client-ID` header). |
| `DIGILOCKER_CLIENT_SECRET`| DigiLocker client secret (sent as `X-Client-Secret` header). |

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
