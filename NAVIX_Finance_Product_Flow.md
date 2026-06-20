# NAVIX Finance — Product & User-Flow (Project Reference)

> **Source:** `NAVIX_Finance_Product_User_Flow.pdf` (26 pages, v1.0, June 2026) — Confidential.
> **Status:** ⚠️ **IN DEVELOPMENT — nothing is live.** All figures, rules and thresholds are *working values* to be finalised during build, credit policy and compliance review.

This file is a structured summary of the product spec so I (and the team) can reason about the build without re-reading the PDF. It mirrors the document's own structure.

---

## 1. What NAVIX Finance is

A digital lending product offering **short-term, salary-linked personal loans to salaried individuals**, repaid in a **single payment** aligned to the customer's salary date.

The product in one line: *A verified, eligible salaried customer borrows up to **25% of monthly salary**, receives the amount minus a **10% fee + 18% GST on the fee**, and repays once on their **salary day** (within ~40 days) with **1%/day interest**, free to prepay and save interest.*

### Headline numbers

| Rule | Value |
|---|---|
| Loan limit cap | **25% of monthly salary** (firm cap) |
| Processing fee | **10%** of loan amount (deducted up front) |
| GST | **18%** on the processing fee (deducted up front) |
| Interest | **1% / day** on the loan amount, calculated daily |
| Prepayment | Anytime, no penalty — interest only to the day of repayment |
| Late penalty | **2% / day** after due date, **capped at 30 days** |
| Pricing across A/B/C/D | **One price for everyone** — risk affects limit & checks, not cost |

### The ₹10,000 worked example

| Item | Amount |
|---|---|
| Loan amount | ₹10,000 |
| − Processing fee (10%) | −₹1,000 |
| − GST (18% of fee) | −₹180 |
| **Net received** | **₹8,820** |
| Interest if due in 27 days (salary-linked) | +₹2,700 → repay **₹12,700** |
| Interest if prepaid on day 10 | +₹1,000 → repay **₹11,000** |

---

## 2. Roles (who's involved)

Two sides: the **borrower** (public app) and the **NAVIX team** (staff tools — kept entirely separate; customers never see staff screens).

| Role | Responsibility |
|---|---|
| **Borrower** | Signs up, verifies, applies, repays. |
| **KYC Approver** | Approves identity & eligibility documents. |
| **Credit Executive** | Reviews an assigned application; recommends a decision. |
| **Credit Head** | Assigns applications; gives **final** credit approval. |
| **Disbursement Head** | Authorises **releasing** the money (after credit approval). |
| **Accountant** | **Manually confirms** whether the bank transfer succeeded/failed → loan becomes active. |
| **Collections Head** | Oversees overdue loans; assigns officers; **approves** settlements & revised plans. |
| **Collection Officer** | Contacts overdue customers; logs every interaction with proof; proposes settlements/plans. |
| **Admin** | Manages staff accounts/roles; sends invites; oversees portfolio. |

**Core control — Separation of duties (maker–checker):** credit review, final approval, and fund release are done by **three different people**. The reviewer ≠ the approver; the approver ≠ the person who releases funds. Every action is logged with who + when.

**Staff onboarding:** staff don't self-register. Admin invites by email → one-time secure link → activate account. Only **activated** staff can be assigned work.

---

## 3. The borrower journey (end-to-end)

```
Sign up & verify → Income & financial check → Eligibility & limit →
Take loan (3 steps) → Money to bank → Repay on salary day → Closed
```
Branches: missed payment → reminders & collections. Good-history returning customers → faster re-loan route.

| # | Stage | Section |
|---|---|---|
| 1 | Sign-up & authentication | 3.1 |
| 2 | Identity verification (KYC) | 3.2 |
| 3 | Income & financial verification | 3.3 |
| 4 | Eligibility, risk & limit | 4 |
| 5 | Taking the loan (3 steps) | 5 |
| 6 | Approval & release | 6 |
| 7 | Repayment | 7 |
| 8 | If late — collections | 8 |

### 3.1 Step 1 — Sign-up & authentication `[IN BUILD]`

Sign-up flow (12 steps):
```
Log in → Added to lead list → Enter PAN → Enter mobile → OTP (with resend) →
Employment status + UAN → Salary details → Email (personal + official) →
Connect salary bank → Account Aggregator: fetch financials →
Application under review → Upload address proof (additional)
```
Establishes three things before any money is considered: (1) person is real & reachable (PAN, mobile+OTP, emails, address proof); (2) genuinely salaried (employment status, UAN, official email, salary bank); (3) finances support a loan (Account Aggregator data). Salary drives both the **limit** (25% cap) and the **due date** (salary credit date).

### 3.2 Step 2 — Identity verification (KYC) `[IN BUILD]`

| Check | Confirms |
|---|---|
| PAN validation | PAN valid & matches name |
| Aadhaar via DigiLocker | Official Aadhaar identity (with consent) |
| Aadhaar–PAN linkage | Same person, linked |
| Selfie / liveness | Live selfie matched to Aadhaar photo (anti-impersonation) |
| Address proof | Current address supported by document |

**Privacy:** full Aadhaar number is **not stored** — only a verified reference. Sensitive IDs masked where not needed.
**Build-phase aid:** individual verification steps can be temporarily skipped during dev/testing — switched off for live.

### 3.3 Step 3 — Income & financial verification `[IN BUILD]`

| Source | Purpose |
|---|---|
| Employment & UAN | Confirms salaried employment; tenure/stability |
| Salary & salary bank | Monthly salary (→ limit) and salary-credit date (→ due date) |
| Bank-statement analysis | Salary credits, balances, inflows/outflows, bounces — via Account Aggregator / net-banking / PDF |
| Credit bureau (CIBIL / Experian / CRIF) | Score + active loans / existing obligations |

---

## 4. Eligibility, risk scoring & loan limit `[IN BUILD]`

- **Limit:** risk-assessed but **capped at 25% of monthly salary** (e.g. ₹40,000 salary → max ₹10,000).
- **Risk score** from: bureau score/history & active loans; income stability (salary, employment, UAN tenure); banking behaviour (credits, balances, bounces); prior NAVIX repayment history.
- **Categories (A/B/C/D)** — *score bands TBD with credit policy*. Affect limit-within-cap, level of manual review, and secondary-applicant need — **not price**.

| Cat | Profile | Treatment |
|---|---|---|
| **A** Low | Strong bureau, stable salary, clean banking | Up to full cap; smoothest review; no co-applicant |
| **B** Mod-low | Good with minor gaps | At/near cap; standard review |
| **C** Mod-high | Thin history, higher obligations, banking irregularities | Reduced limit; closer review; may need co-applicant |
| **D** High | Weak score / clear risk signals | Much reduced or decline; co-applicant required; enhanced scrutiny |

- **Secondary applicant (co-applicant):** verified, shares repayment responsibility — typically C/D.
- **Fraud/blocklist screening** before approval (see §9) — a match stops the application.

---

## 5. Taking the loan — a 3-step process `[IN BUILD]`

```
Step 1 — Amount & sign documents → Step 2 — Penny-drop bank verification → Step 3 — Money transferred (manual)
```

1. **Choose amount & sign documents** — up to eligible limit. Three docs accepted: **Loan agreement**, **Sanction letter**, **Key-Fact Statement (KFS)**. Full cost breakdown shown **before** signing (loan amount, fee, GST, net amount, daily interest, salary-linked due date, total repayable).
2. **Penny-drop bank verification** — tiny deposit confirms account is valid and **name matches**.
3. **Money transferred (manual)** — net amount sent to the customer's bank. In this build the transfer is **manual** and an **Accountant manually confirms** success → loan becomes active, repayment clock starts.

---

## 6. Approval & operating model `[IN BUILD]`

```
Customer applies → KYC Approver (identity OK) → Credit Head (assigns) →
Credit Executive (reviews) → Credit Head (final approval) →
Disbursement Head (release) → Accountant (confirms transfer) → Loan active
```

Three credit/disbursement roles = **three different people**. Bank-transfer success/failure is **confirmed manually by the Accountant** (no auto-reconciliation this phase). Every action recorded → complete maker–checker / approval trail.

---

## 7. Repayment `[IN BUILD]`

- **Single repayment**, salary-linked. Due date = the **last salary credit date within ~40 days** of disbursement (salary day, or day after).
  - *Edge case TBD:* when "day after" would fall just outside the 40-day window.
  - Examples (salary on 30th): disbursed 3 Jun → due **30 Jun** (~27d); disbursed 25 Jun → due **30 Jul** (later salary, fuller cycle).
- **Interest:** 1%/day on loan amount, daily. **Prepay anytime** → interest only to day of repayment, no penalty.
- **Methods:** Manual (UPI / bank transfer + transaction ID and/or screenshot proof) `[IN BUILD]`; **Auto-debit / NACH** `[FUTURE]` (after NBFC/NACH). **Partial payments allowed** — balance stays outstanding until cleared; loan closes at zero.
- **Late:** 2%/day penalty, **capped at 30 days** → account moves to collections.

---

## 8. Collections — if a payment is missed `[IN BUILD]`

- **DPD buckets** (calculated live from due date, never stored): `Upcoming` · `T0–T7` · `T8–T30` · `T30–T60` · `T60–T90` · `T90+`.
- **Assignment & follow-up:** Collections Head assigns overdue customers (by bucket) to activated Collection Officers. Officers log every interaction (type, outcome, promise-to-pay).
- **Proof of contact:** `Contact customer → Record outcome → Attach proof (screenshot / text / transaction ID)`. A "paid" outcome **requires** a transaction ID or screenshot.
- **Concessions (Head-approved, maker–checker):** **Partial settlement** and **Revised repayment plan (hardship)** — officer proposes, **Collections Head approves**.
- **T90+** → dedicated **recovery / legal queue** (external agency referral; agency TBD — commercial decision).
- **Principle:** fair, respectful collections — help the customer repay, not harass.

---

## 9. Other capabilities

- **Returning customers `[IN BUILD]`:** instant re-loan **without full KYC** (`Returning customer → light re-checks (bank, bureau, OTP) → eligibility/limit refresh → take loan (3 steps) → money to bank`); **pre-approved offers** for good repayment history.
- **Fraud prevention `[IN BUILD]`:** blocklist of fraud / chargeback / fake-KYC identifiers (PAN, Aadhaar ref, phone, device, bank). Screened at sign-up **and** before approval; match stops the application.
- **Reminders `[FUTURE]`:** WhatsApp / SMS / Email / IVR, before & after due date — once messaging providers + NBFC are in place.
- **Compliance:** maker–checker history & approval trails `[IN BUILD]`; data protection (masking, no full Aadhaar stored, consent-based AA fetch); **NBFC reporting — overdue classification & provisioning** `[FUTURE]`. Responsible-lending principles: transparency, affordability, no surprises, strong verification, separation of duties, data protection, respectful collections.

---

## 10. Customer-facing status journey

```
Applied → Under review → Approved (offer) → Documents signed → Bank verified →
Money on the way → Active (due on salary day) → Repaid & closed
```
Branches: *more info needed*, *declined*, or *overdue → in collections → repaid / settled closure*.

---

## 11. Status summary

| `[IN BUILD]` (this phase) | `[FUTURE]` (needs NBFC / NACH / providers) |
|---|---|
| Full borrower journey, KYC, income verification | Auto-debit (NACH) at repayment |
| Risk limit (25% cap), A/B/C/D categories, secondary applicant | WhatsApp/SMS/Email/IVR reminders |
| 3-step loan, penny-drop, manual transfer + Accountant confirm | NBFC reporting — overdue classification & provisioning |
| Separation of duties, maker–checker logs | |
| Manual repayment, collections, settlements/hardship, T90+ recovery | |
| Instant re-loan, pre-approved offers, fraud blocklist | |

## 12. Items to confirm (open decisions)

| Item | To confirm |
|---|---|
| Salary-due edge case | Handling when "day after salary" falls just outside ~40-day window |
| Risk-category bands | Exact score ranges for A/B/C/D (with credit policy) |
| Recovery / legal partner | Agency for 90+ DPD cases |
| NBFC partner | Unlocks overdue classification/provisioning, NACH auto-debit, reminders |
| Messaging & payment providers | Providers/timing for reminders and NACH |

---

## Glossary (quick)

**Net amount disbursed** loan − fee − GST · **DPD** days past due · **Bucket** live DPD group · **UAN** employment/PF link · **Account Aggregator** consent-based financial-data fetch · **Penny-drop** account+name verification · **KFS** Key-Fact Statement · **Maker–checker** one acts, another approves · **NBFC** intended lending partner for the live product.
