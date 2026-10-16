# 11 — Open questions, assumptions and readiness

**Updated 2026-10-16** after production credentials were issued and a live read-only call succeeded
([`13-`](13-live-verification-log.md)). **Q1, Q2 and Q4 are now CLOSED. Two operational blockers remain
(Q3 sandbox, Q13 webhook).**

## 1. Blockers

### Q1 — How are API credentials provisioned and rotated? · ~~BLOCKER~~ **RESOLVED (partly)**
**Resolved 2026-10-16:** Digio issued production `client_id` / `client_secret` directly by email
("Welcome aboard"), i.e. **provisioning is manual by Digio, not self-serve** — which is why no UI
exists. Verified working: `GET /v3/client/mandate/banks` → **HTTP 200** ([`13-`](13-live-verification-log.md)).
Credentials are **not stored in this repository**; they must go to SSM (`04-` §4).
**Still open:** the **rotation** procedure, and whether **sandbox** credentials exist (→ Q3).

<details><summary>Original entry</summary>

**Why:** no credential, no integration. Nothing can be built or tested.
**Evidence:** auth is HTTP Basic client_id/secret (VERIFIED, `04-`). The enterprise console has **no
API-keys section** — profile offers only Account, Subordinate Users, Service Health Monitor, Merchant
Details, Webhooks, Plan Ledger (OBSERVED). Digio's docs say "use your client id/client secret" without
saying where they come from.
**Investigated:** the whole console navigation; the environments and DigiCollect documentation.
**Resolve via:** Digio support — `support@digio.in` / `080-69489510` / the account manager.
**Ask for:** sandbox and production credentials, the rotation procedure, whether credentials are
per-sub-user, and whether Digio needs our egress IPs allowlisted.
**Status:** CLOSED for production credentials.
</details>

### Q2 — Per-loan mandate or per-customer mandate? · ~~BLOCKER~~ **DECIDED**
**Decision 2026-10-16: per-customer.** One reusable, `MAXIMUM`-type mandate per borrower, with the
exact amount set on each debit presentation. `08-` §2's recommendation stands and is now the chosen
design; `08-` §5's nullable `loan_application_id` is the correct schema shape.
**Still required before Phase 4:** the consent wording (Q14) — a standing customer-level authority
needs a clearer disclosure than a per-loan one.

<details><summary>Original entry</summary>

**Why:** it determines the schema, the journey, the consent text and the reborrow path. Changing it
later is a migration plus a re-consent of every borrower.
**Evidence:** registration takes days (bank file cycle, VERIFIED); Navix tenure is ≤ 40 days and
disbursal is immediate; reborrow deliberately re-asks the borrower almost nothing (CLAUDE.md).
**Recommendation:** per-customer, `MAXIMUM`-type, registered during onboarding (`08-` §2).
**Resolve via:** product + compliance, not engineering.
**Status:** DECIDED — per-customer.
</details>

### Q3 — Do we have sandbox access, and can it simulate bank rejections? · BLOCKER
**Why:** without it, every mutating call is a real mandate. Without rejection simulation, cases
8/15/16/17/21 of `10-` are untestable end to end.
**Evidence:** the sandbox exists (`ext.digio.in:444`, VERIFIED). Our access is unverified; no session
on this machine. Test customers and forced outcomes are undocumented.
**Resolve via:** Digio support, with Q1.
**Status:** OPEN.

### Q13 — Webhook endpoint and secret are not configured · BLOCKER
**Why:** mandate registration and debit are both asynchronous. Without webhooks the integration is
blind, and polling alone will not scale or meet the T-1 cycle.
**Evidence:** profile → Webhooks contains only a "Configure Webhook" button (OBSERVED). Deliberately
not clicked — it is a mutating configuration change.
**Needs:** a public HTTPS endpoint, a strong secret in SSM, inbound allowlisting of `13.126.198.236`
and `52.66.66.81` (prod) / `35.154.20.28` (sandbox), **and the right event families enabled** —
registration and debit are enabled separately (`05-` §1).
**Status:** OPEN — configure only after the receiver exists and returns 200.

## 2. High

### Q4 — Is UPI AutoPay available to us? · **ANSWERED: NO**
**Resolved 2026-10-16 by a live call.** `POST /v3/client/mandate/create_form` with `auth_mode: "upi"`
returned **HTTP 500 `SYSTEM_ERROR` — "Invalid Corporate Config Id"**, with the config id verified
byte-identical from the console DOM. **UPI AutoPay is not provisioned**; `TSE2610…` is a NACH-only
sponsor profile ([`13-`](13-live-verification-log.md) §7).

**Consequence:** the NACH design in `08-` stands, and the per-customer register-early shape (Q2) is
now the *only* workable one rather than merely the preferred one. Enabling UPI is a **commercial
request to Digio**, worth making for real-time registration and non-revocable lending mandates.

<details><summary>Original entry</summary>

Better borrower UX (real-time, no bank-file wait) and Digio documents that **lending mandates cannot
be revoked by the borrower** — a meaningful advantage over NACH. Absent from our console sidebar (OBSERVED) — but **the API exists and its spec is now mirrored**
(`final_post_createMandateForm_upi.json`): same endpoint, `auth_mode: "upi"`, `customer_vpa`, with
**real-time registration** instead of NACH's multi-day bank-file cycle ([`13-`](13-live-verification-log.md) §4).

**This is now a blocker rather than a nice-to-have**, because it invalidates the premise of the
current design. `02-` §5 and `08-` §2 are built around registration latency being incompatible with a
≤40-day, same-day-disbursal product. UPI removes that constraint. **Answering Q4 decides which rail
`08-` should target, so it must be settled before Phase 1** — building the NACH path first and
discovering UPI was available is the expensive order. Entitlement is unverified; only a live call
settles it. **CLOSED — not provisioned.**
</details>

### Q5 — Which management category is our utility code actually approved for?
`L001` (loan instalment) is the obvious fit. The spec's enum carries `T001`/`S001`, lists `F001`
twice, and omits `T002`; the console offers `O001` ("Own retail portfolio mandate- EMI") and a
security variant **that are not in the API enum** — **CONFLICT** (`01-` §4). The create-mandate docs
warn the category must match what NPCI enabled for our utility code. A mismatch fails at the NPCI
gateway (`155`/`156`). **Confirm with Digio and Yes Bank before the first call.** **OPEN.**

### Q6 — Does a mandate need its own Aadhaar eSign?
`auth_mode: ESIGN` would mean a **second** billable, legally binding Aadhaar eSign per borrower, on
top of the one Navix already spends on the loan agreement — and CLAUDE.md is emphatic that there is no
sandbox for eSign. Preferring `auth_mode: API` (net banking / debit card) avoids it for the 39 banks
in `A6`. Needs a cost and a legal answer for the rest. **OPEN.**

### Q7 — Are `collection_amount` and `maximum_amount` both required? · **ANSWERED: NO**
**Resolved 2026-10-16 by a successful live create** with `maximum_amount` only
([`13-`](13-live-verification-log.md) §9). The spec's required-marking on `collection_amount` is wrong.
A MAXIMUM-type mandate — what Navix needs for a penalty-varying amount — is legal. **CLOSED.**

<details><summary>Original entry</summary>

The spec marks both required; the description says "Either of colle…" (truncated) — **CONFLICT**
(`03-` A1). Decides whether a `MAXIMUM`-type mandate can omit the fixed amount. Resolve against the
sandbox or Digio support. **CLOSED — maximum_amount alone suffices.**
</details>

### Q14 — Do mandate terms belong in the Key Fact Statement?
Navix renders a KFS and takes a fresh eSign per advance. A standing debit authority — especially a
customer-level `MAXIMUM` mandate — is a term the borrower is agreeing to. **Compliance decision.**
**OPEN.**

### Q15 — Should a bank-confirmed debit auto-verify, or still pass the Accountant?
Today a borrower uploads a screenshot and an Accountant verifies it. A settled NACH debit is stronger
evidence than a screenshot, so auto-verifying is defensible — but it removes a maker-checker step, and
CLAUDE.md treats those as hard requirements. **The Accountant's and compliance's call**, not
engineering's. **OPEN.**

## 3. Medium

| # | Question | Current evidence | Resolve via |
|---|---|---|---|
| Q8 | Rate limits, request timeouts, retryable statuses, IP allowlisting of *our* egress | **UNKNOWN** — nothing documented | Digio support |
| Q9 | Which mandate states allow cancellation (`A5`)? | Spec states no precondition and marks no field required | Sandbox / support |
| Q10 | What do transaction actions `update` and `approve` do? | Enum values with no documentation; `cancel`/`reattempt` are documented | Support |
| Q11 | Are we a "Digio PA user"? | Gates `settlement_config_profile_id`, `split_settlement_info` and `C6` consolidated settlement | Digio / commercials |
| Q12 | Is there a changelog or deprecation policy for `/v3/`? | None found | Support |
| Q16 | Is `complete` a real mandate state? | Only in the cancel spec's enum — **CONFLICT** (`09-`) | Support / observation |
| Q17 | Data retention and deletion at Digio | **UNKNOWN** (`07-` §4) | Digio + our compliance |
| Q18 | Does the sandbox simulate the T-1/T settlement cycle or resolve instantly? | **UNKNOWN** | Sandbox, after Q3 |

## 4. Low

- Exact `code` values in `400` responses (`06-` §2) — discoverable in sandbox.
- Meaning of `downloaded` and `successful` in `bank_details.state` (`09-`).
- Whether `GET /v3/client/mandate/banks` output is stable enough to cache for a day.
- Whether sub-users are worth using for staff attribution (`07-` §5).

## 5. Assumptions made in this knowledge base

Stated so a reviewer can reject them rather than inherit them silently.

1. The account explored is the one that will be integrated (`info@dhanboost.com`, corporate profile
   `NAVIX FINANCE YBL SPONSOR`). **If a separate production account is created, every account-specific
   fact in `01-` is void.**
2. Digio's published OpenAPI specs match deployed behaviour. Normal, but unverified — nothing was
   called.
3. The console's enabled options (auth modes, categories, banks) reflect real entitlement rather than
   a generic UI. Q5's CONFLICT is direct evidence that this assumption is **partly wrong**.
4. Navix keeps manual repayment as the guaranteed path; a mandate is a convenience and never gates
   disbursal (`08-` §2).
5. `corporate_config_id` is configuration, not a credential — safe to record, still stored in SSM.

## 6. Implementation readiness checklist

| # | Item | Status |
|---|---|---|
| 1 | Exact product and API version identified | **DONE** — DigiCollect NACH, `/v3/` |
| 2 | Official API documentation located | **DONE** — specs mirrored in `api-reference/` |
| 3 | Authentication model verified | **DONE** — HTTP Basic, production credentials issued and **confirmed working by a live 200** (`13-`) |
| 4 | Sandbox and production configuration documented | **DONE** (`04-`) |
| 5 | Mandate creation verified | **Spec-verified; never executed** |
| 6 | Customer authorisation workflow understood | **DONE** (`02-`) |
| 7 | Status retrieval and state transitions documented | **DONE** (`09-`) |
| 8 | Webhook behaviour documented | **DONE** (`05-`) / **not configured** (Q13) |
| 9 | Error handling and retry safety documented | **DONE** (`06-`) |
| 10 | Idempotency established | **DONE — and the answer is "none."** Recovery-by-reference is mandatory (`06-` §3) |
| 11 | Navix architecture inspected | **DONE** (`00-`, `08-`) |
| 12 | State mapping reviewed | **DRAFTED, unreviewed** (`09-`) |
| 13 | Security and data handling documented | **DONE** (`07-`) |
| 14 | Sandbox test plan complete | **DRAFTED**; executable only after Q3 |
| 15 | Critical questions resolved or accepted as risks | **NO — Q3 (sandbox) and Q13 (webhook) open.** Q1, Q2, Q4 closed |
| 16 | Knowledge base internally consistent | **DONE** — links and conflicts cross-checked |

**Verdict: NOT READY, but the design is now settled.** Credentials work, the rail is decided (NACH —
UPI is not provisioned) and the mandate shape is decided (per-customer, MAXIMUM-type, registered
early). **Phase 1 is unblocked.**

Remaining blockers are both operational, not design: **Q3** (sandbox, so testing need not file real
mandates) and **Q13** (webhook endpoint, without which the integration is blind). Neither prevents
writing the adapter; both prevent shipping it.
