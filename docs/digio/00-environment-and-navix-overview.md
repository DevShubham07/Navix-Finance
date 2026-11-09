# 00 — Environment, tooling and the Navix side

## 1. Tool inventory (what the exploration actually had)

| Tool | Capability used | Chrome? | Network? | Files? | Mutating? | Limitation found |
|---|---|---|---|---|---|---|
| `chrome-devtools-mcp` | Drove the **authenticated** Digio console; snapshots, DOM reads, network request list | yes | **yes** | no | can click | The only path to the logged-in session |
| `claude-in-chrome` | Separate browser profile | yes | yes | no | can click | **Not authenticated to Digio** — different profile; abandoned |
| `Bash` (curl/python) | Fetched Digio's OpenAPI specs and doc pages directly | no | n/a | yes | read-only here | Fastest and cheapest source by far |
| `WebFetch` | Attempted doc pages | no | no | no | no | **Useless on documentation.digio.in** — JS SPA, returns only `<title>` |
| `WebSearch` | Located the docs host and spec paths | no | no | no | no | Did not surface endpoint detail; third-party only |
| `code-review-graph` MCP | — | — | — | — | — | **Failed to connect** (`ENOENT`, Windows path in a macOS session). Repo mapping fell back to grep/find, per CLAUDE.md's stated fallback |

**The capability that mattered:** Digio's documentation site loads its API reference from
`https://documentation.digio.in/swagger/<name>.json` — public, unauthenticated, real OpenAPI 3.0.1.
That turned API discovery from UI archaeology into reading the vendor's own machine-readable contract.
Browser network inspection was used to *discover* those URLs, not to reverse-engineer private endpoints.

**No private dashboard endpoint is documented here as an integration API.** The console's own XHRs were
read only to navigate; every endpoint in `03-` comes from a published spec.

## 2. Session constraints (important for reproducing this)

- **VERIFIED** The Digio console session is **tab-scoped** (sessionStorage, not cookies). Opening
  `enterprise.digio.in` in a new tab lands on `#/authenticate` even in the same browser profile. Any
  repeat exploration must drive the operator's existing tab, or log in again.
- **VERIFIED** The console we explored is **production** (`enterprise.digio.in` / `api.digio.in`), signed
  in as `info@dhanboost.com`. There is no sandbox session on this machine.
- Consequence: **nothing was executed against the API.** Every endpoint claim is from the spec, never
  from a live call. See `10-` for how to change that safely.

## 3. Navix repository — architecture relevant to a mandate

Inspected read-only; nothing modified.

| Concern | Where it lives | Note |
|---|---|---|
| Build | Spring Boot 3.4.1 / Java 21, Maven multi-module `com.navix`, 12 modules | `navix-app` is the only bootable module |
| Money | **integer paise (`long`)**, HALF_UP, `navix-loan/.../LoanMath.java` | Digio takes rupee decimals on registration and paise on debit — see `08-` §4 |
| The aggregate | `loan_application.status`, `ApplicationFlowService`, audit in `application_event` | One row, server-validated transitions |
| Loan + repayment | `navix-loan`: `Loan.java`, `LoanService`, `RepaymentService`, `RepaymentController` | Outstanding is **computed on read** (`RepaymentService.outstandingAsOf`), never trusted from a stored column |
| Provider seam | `navix-common/.../verification/VerificationPort.java` + `RoutingVerificationPort` (`@Primary`) | Chain of providers, each opting out by throwing. **eSign deliberately sits outside it** in its own `EsignPort` — "a legal act rather than a check" |
| Provider callback precedent | `navix-loan/.../controller/SignzyContractWebhookController.java` | **The pattern to copy.** Unauthenticated at the filter chain, authenticates itself on a shared secret, always answers 200, and **re-reads state from the provider instead of trusting the callback body** |
| Security | `navix-app/.../config/SecurityConfig.java` — `/api/**` authenticated; provider callbacks explicitly `permitAll` | A Digio webhook path must be added to that list |
| Scheduled work | `navix-app/.../scheduler/` — `PaymentReminderScheduler`, `EmploymentRetryScheduler`; cron from env | Precedent for a mandate-reconciliation sweep |
| Notifications | `navix-notification` — domain publishes a Spring event, `@TransactionalEventListener(AFTER_COMMIT) @Async` delivers | **Business logic must never depend on a delivery succeeding** |
| Migrations | `backend/navix-app/src/main/resources/db/migration/`, **V1..V80** (head is `V80__bureau_phone.sql`) | Historical migrations immutable. *CLAUDE.md still says V78 — stale* |
| Feature flags | `feature_flag` table, SQL-only, `FeatureFlagService.isEnabled(key)` | How a mandate rollout should be gated |
| Config/secrets | env + SSM SecureString `/navix/<env>/…`; `NAVIX_*` env vars | Where Digio credentials belong |
| Frontend | Next.js 15 + BFF route handlers under `src/app/api/*`; browser never calls Spring directly | A borrower mandate screen goes through the BFF |

### What already exists for mandates: nothing

**VERIFIED** A repo-wide search for `digio|emandate|enach|nach|autopay|mandate` returns **no
implementation**. The only mandate artefact is a deliberate placeholder:

`frontend/src/components/staff/customer-360/mandate-tab.tsx`
> "UPI Autopay / NACH mandates are not available yet." — hint: "Repayment is manual." Disabled in the
> tab strip, no setup button.

The remaining hits are incidental uses of the English word "mandates" (e.g. a comment that Digitap's
API *mandates* an OTP) and marketing copy. **This is greenfield**, with a staff surface already
reserved for it.

### The product constraint this must fit

Navix is **single-repayment**, salary-linked: the borrower repays **once**, on a due date **≤ 40 days**
after disbursal, and the amount owed is penalty-aware and only final on the day it is paid. Today
repayment is **manual** — the borrower uploads a screenshot, an Accountant verifies it
(`PENDING_VERIFICATION` → verified), and the loan closes when the computed outstanding reaches zero.

A NACH mandate is built for *recurring, known-in-advance* EMIs on a file-based T+1 cycle. The mismatch
between that and a one-shot, amount-varies-to-the-day advance is the central design problem of this
integration, not a detail. It is carried through `02-` §5, `08-` §2 and `11-` Q2.
