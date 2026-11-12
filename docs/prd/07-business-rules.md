# 07 — Business Rules Catalog (cross-cutting)

This file lists the exact conditions every DhanBoost screen relies on: formulas, thresholds, guards, caps and the error codes they raise.
It does **not** cover request/response payloads (see `docs/API_SURFACE.md`) or screen flows (files 01–06 in this folder).
Every rule was read from the code as of 2026-11-13. **Where code and prose disagree, the code wins.** The section at the end lists every disagreement found.
All money is integer **paise** (`long`), and every "₹" figure below is that paise value ÷ 100. All dates are Indian calendar dates (IST) unless a rule says otherwise.
Rule ids are stable: `BR-<AREA>-nn`. Other PRD files cite them; never renumber a rule, only append new ones.

## Table of contents

1. [Money & pricing (BR-MNY)](#1-money--pricing-br-mny) + [worked examples](#worked-examples)
2. [Eligibility & limits (BR-ELG)](#2-eligibility--limits-br-elg)
3. [Lifecycle (BR-LC)](#3-lifecycle-br-lc)
4. [Rejections & blocks (BR-REJ)](#4-rejections--blocks-br-rej)
5. [Identity & fraud (BR-ID)](#5-identity--fraud-br-id)
6. [Verification (BR-VER)](#6-verification-br-ver)
7. [Credit rating & BRE (BR-CR)](#7-credit-rating--bre-br-cr)
8. [Collections & settlements (BR-COL)](#8-collections--settlements-br-col)
9. [Mandate & NACH (BR-MAN)](#9-mandate--nach-br-man)
10. [Notifications (BR-NOT)](#10-notifications-br-not)
11. [Auth & sessions (BR-AUTH)](#11-auth--sessions-br-auth)
12. [DSA, referral, discounts, reapply allowance (BR-GRW)](#12-dsa-referral-discounts-reapply-allowance-br-grw)
13. [Schedulers (BR-SCH)](#13-schedulers-br-sch)
14. [Feature flags (BR-FF)](#14-feature-flags-br-ff)
15. [Contradictions found](#15-contradictions-found)

**How to read a rule.** Each rule has five fields:
- **Rule**: one sentence.
- **Condition**: the exact predicate or formula.
- **Enforced**: `Class.method`. Packages are omitted. The class names are unique in the repo.
- **Violation**: the `BusinessException` code (HTTP 422 inside the `ApiResponse` envelope) or the outcome.
- **Example**: a concrete case.

**Role gates.** `requireRole` / `requireAnyRole` / `requireOneOf` always let **ADMIN** through unless the rule says *ADMIN only* or *ADMIN excluded*. Authorization always uses the **real JWT role**. The working role (`X-Acting-Role`) only scopes lists and the "own cases / own files" checks that read `effectiveRole()` (BR-AUTH-10).

---

## 1. Money & pricing (BR-MNY)

Source of truth: `LoanMath` (backend), mirrored in `frontend/src/lib/calc/loan-math.ts` (rupee-based, display only).

### BR-MNY-01 — Integer paise, HALF_UP
- **Rule:** All money is a `long` count of paise; every fractional paisa is rounded HALF_UP. The exceptions are listed in the Example.
- **Condition:** `roundPaise(x) = x.setScale(0, HALF_UP)`; rates are `BigDecimal` (`0.10`, `0.18`, `0.01`, `0.02`).
- **Enforced:** `LoanMath.roundPaise`; DB columns are `BIGINT` paise.
- **Violation:** n/a. A float anywhere in money code is a defect.
- **Example:** Exceptions to HALF_UP: the eligible limit uses **FLOOR** (BR-ELG-01), the PayU card fee uses **CEIL** (BR-MNY-27), the APR is HALF_UP to 1 decimal place (BR-MNY-25), and DSA commission is HALF_UP (BR-GRW-03).

### BR-MNY-02 — Processing fee
- **Rule:** A processing fee of 10% of principal is taken up front, less any pinned repay-now discount.
- **Condition:** `fee = max(0, round(P × 0.10) − feeDiscount(P, pct))`.
- **Enforced:** `LoanMath.processingFeePaise(P, pct)`; persisted at disbursal by `LoanService.disburse`.
- **Violation:** n/a.
- **Example:** P = ₹10,000 → fee ₹1,000; P = 1,000,005 paise → 100,000.5 → **100,001** (`LoanMathTest.halfUpRounding`).

### BR-MNY-03 — Fee discount (repay-now offer)
- **Rule:** A pinned discount percent comes off the fee, never off interest or penalty. It is computed on the principal, not on the fee.
- **Condition:** `feeDiscount = pct ≤ 0 ? 0 : round(P × pct / 100)`; `pct` = `loan_application.fee_discount_percent` (1–7, BR-GRW-08).
- **Enforced:** `LoanMath.feeDiscountPaise`. The same column is read by `OfferService.summary`, `OfferService.agreementFacts` (KFS) and `LoanService.disburse`, so all three agree.
- **Violation:** n/a. A double use of one offer is refused at disbursal with `DISCOUNT_OFFER_USED` (BR-GRW-09).
- **Example:** P ₹10,000 at 5% → discount ₹500 → fee ₹500 (an effective 5% fee); at 7% → fee ₹300.

### BR-MNY-04 — GST on the fee
- **Rule:** 18% GST is charged on the (discounted) fee, up front.
- **Condition:** `gst = round(fee × 0.18)`.
- **Enforced:** `LoanMath.gstPaise(P, pct)`.
- **Violation:** n/a.
- **Example:** fee ₹1,000 → GST ₹180; fee ₹500 → GST ₹90.

### BR-MNY-05 — Net disbursed
- **Rule:** The borrower receives principal minus fee minus GST.
- **Condition:** `net = P − fee − gst`.
- **Enforced:** `LoanMath.netDisbursedPaise`; stored in `loan.net_disbursed`.
- **Violation:** n/a.
- **Example:** ₹10,000 → ₹8,820 (`LoanMathTest.workedExample`).

### BR-MNY-06 — Interest
- **Rule:** Interest accrues at 1% of principal per day held. Negative day counts clamp to 0.
- **Condition:** `interest(d) = round(P × 0.01 × max(0, d))`.
- **Enforced:** `LoanMath.interestPaise`.
- **Violation:** n/a.
- **Example:** ₹10,000 × 27 days = ₹2,700 (`LoanMathTest.interest`).

### BR-MNY-07 — Total repayable
- **Rule:** Total repayable is principal plus accrued interest. Fee and GST are not part of it.
- **Condition:** `total(d) = P + interest(d)`. At disbursal `loan.total_repayable = loan.outstanding = total(tenureDays)`, where `tenureDays = days(disbursedOn, dueDate)`.
- **Enforced:** `LoanMath.totalRepayablePaise`; `LoanService.disburse`.
- **Violation:** n/a.
- **Example:** 27-day tenure → ₹12,700.

### BR-MNY-08 — Late penalty, capped
- **Rule:** The late penalty is 2% of principal per penalty day, for at most 30 days.
- **Condition:** `penalty(dl) = round(P × 0.02 × min(max(0, dl), 30))`.
- **Enforced:** `LoanMath.latePenaltyPaise`; `LATE_PENALTY_CAP_DAYS = 30`.
- **Violation:** n/a. After 30 days the balance stops growing and only collections continue.
- **Example:** 5 days → ₹1,000; 40 days → ₹6,000 (`LoanMathTest.cappedAtThirtyDays`).

### BR-MNY-09 — Due-date selection (salary-linked)
- **Rule:** The due date is the **latest** salary-credit date strictly after disbursal and no more than 40 days after it.
- **Condition:**
  - `windowEnd = disbursedOn + 40`.
  - Walk each month from the disbursal month to the windowEnd month and take `salaryDate = clamp(day, 1..lengthOfMonth)`.
  - Keep the maximum date with `salaryDate > disbursedOn && salaryDate ≤ windowEnd`.
  - Fallback, which cannot happen for a monthly salary: the first salary date after disbursal.
- **Enforced:** `LoanMath.dueDateFromSalary`; `MAX_TERM_DAYS = 40`.
- **Violation:** n/a.
- **Example:** Disbursed 2026-06-03, day 30 → 06-30 (27 d). Disbursed 2026-06-10, day 15 → **07-15** (35 d), not 06-15.

### BR-MNY-10 — Salary-day month clamping
- **Rule:** When a month is too short for the salary day, the salary falls on that month's last day.
- **Condition:** `day = min(max(1, salaryDay), month.lengthOfMonth())`.
- **Enforced:** `LoanMath.salaryDateInMonth`.
- **Violation:** n/a.
- **Example:** Day 31, disbursed 2026-01-25 → 2026-02-28. Leap year 2028 → 02-29. Disbursed 2026-03-25 → 04-30 (`LoanMathTest.clamps*`).

### BR-MNY-11 — Salary day when none was captured
- **Rule:** If the application has no salary-credit day at disbursal, day 1 is used.
- **Condition:** `salaryDay = app.salaryCreditDay ?: 1`.
- **Enforced:** `LoanService.disburse` (`DEFAULT_SALARY_DAY = 1`).
- **Violation:** n/a.
- **Example:** A legacy file with no day, disbursed 2026-06-01 → due 2026-07-01.

### BR-MNY-12 — The loan is booked on the Credit Executive's approved repayment date
- **Rule:** At disbursal the loan's due date is the `approved_repayment_date` set at sanction — the
  date the offer summary and the signed KFS quote. Only when that date is not after the payout day (a
  sanction never expires) is it recomputed from the payout day and the salary day.
- **Condition:** `loan.due_date = approved > payoutDay ? approved : dueDateFromSalary(payoutDay, app.salaryCreditDay)` at `DISBURSED → ACTIVE`.
- **Enforced:** `ApplicationFlowService.finalizeDisbursal` → `LoanService.disburse` (fixed 2026-11-13; C-01).
- **Violation:** n/a.
- **Example:** Sanctioned 2026-06-03 (approved date 06-30), disbursed 2026-06-21 → the loan is due **06-30** (9 days, ₹10,900 on ₹10,000), matching the KFS. An approved 06-30 paid out on 06-30 falls back to 07-30.

### BR-MNY-13 — Grace is per loan
- **Rule:** Each loan stores its own penalty-free grace days. Every loan disbursed since V91 gets 0.
- **Condition:** `loan.grace_days = LoanMath.SALARY_GRACE_DAYS` (= 0) stamped at disbursal. Pricing reads `loan.grace_days`, never the constant.
- **Enforced:** `LoanService.disburse`; `RepaymentService.breakdown`.
- **Violation:** n/a. Changing the constant never re-prices an existing loan.
- **Example:** Loans already due or closed on 2026-10-24 keep grace 1.

### BR-MNY-14 — Interest days in the outstanding
- **Rule:** Interest accrues for the days actually held (so prepaying costs less) and stops at the due date plus grace.
- **Condition:**
  - `at = asOf`, frozen to `closedOn` once the loan has closed.
  - `daysToAsOf = max(0, days(disbursedOn, at))`.
  - `interestDays = min(daysToAsOf, tenureDays + graceDays)`.
- **Enforced:** `RepaymentService.breakdown`.
- **Violation:** n/a.
- **Example:** Paid on day 7 → 7 interest days → ₹10,700.

### BR-MNY-15 — Penalty days in the outstanding
- **Rule:** Penalty days are the days past due beyond the loan's grace, capped at 30.
- **Condition:** `rawDpd = max(0, days(dueDate, at))`; `penaltyDays = max(0, rawDpd − graceDays)`. The reported `penaltyDays` is `min(penaltyDays, 30)`.
- **Enforced:** `RepaymentService.breakdown`; `LoanMath.daysPastDue`.
- **Violation:** n/a.
- **Example:** Grace 0, due + 1 → 1 penalty day (`RepaymentServiceTest.noGraceChargesPenaltyFromTheDayAfterDue`).

### BR-MNY-16 — Only VERIFIED payments reduce the balance
- **Rule:** The outstanding subtracts the sum of VERIFIED payments only. PENDING_VERIFICATION and REJECTED payments are ignored.
- **Condition:** `owed = max(0, P + interest + penalty − Σ VERIFIED)`.
- **Enforced:** `RepaymentService.outstandingBreakdownAsOf` (`sumAmountByLoanIdAndStatus(…, VERIFIED)`).
- **Violation:** n/a. A rejected payment leaves accrual running, so the days cost the borrower.
- **Example:** A ₹5,000 screenshot awaiting the Accountant does not change the ₹12,700 shown.

### BR-MNY-17 — Approved settlement caps the payable
- **Rule:** An approved full-and-final settlement can only lower what is owed, never raise it.
- **Condition:**
  - `owed = settled == null ? formulaOwed : min(formulaOwed, max(0, settled − verified))`.
  - `settled` is the **most recently approved** settlement on the loan's case (BR-COL-11).
- **Enforced:** `RepaymentService.breakdown`; `SettlementDirectoryAdapter.approvedSettlementAmount`.
- **Violation:** n/a.
- **Example:** Settlement ₹15,000 against a ₹12,700 balance → owed stays ₹12,700 (`RepaymentServiceTest.settlementNeverIncreasesPayable`).

### BR-MNY-18 — One outstanding figure everywhere
- **Rule:** The repay page, dashboard, collections, reports and debit sizing all read the same compute-on-read figure. `loan.outstanding` is only a cache.
- **Condition:** Every caller goes through `outstandingAsOf` / `outstandingBreakdownsForAll`, which share the private `breakdown`. The batch path resolves "today" once.
- **Enforced:** `RepaymentService.outstandingForAll`, `RepaymentService.outstandingBreakdownsForAll`.
- **Violation:** A second implementation is a defect.
- **Example:** A 300-row register is priced with 2 batch queries, not ~900.

### BR-MNY-19 — Loan closes when the balance at the last payment date is zero
- **Rule:** The close decision is made **as of the latest VERIFIED payment's `paidOn`**, not the verification date.
- **Condition:**
  - `settledOn = max(paidOn of VERIFIED) ?: today`.
  - If `outstandingAsOf(settledOn) == 0`: `status = CLOSED`, `outstanding = 0`, `closedOn = settledOn`, then `closeForLoan` and `DsaCommissionService.onLoanClosed`.
  - Otherwise `outstanding = outstandingAsOf(today)`.
- **Enforced:** `RepaymentService.recomputeOutstanding`. It is called by `verifyPayment`, settlement approval and the collection/PayU/NACH credits.
- **Violation:** n/a.
- **Example:** Paid in full on the 5th and verified on the 7th → closes with `closedOn = 5th` (`RepaymentServiceTest.verifyClosesAsOfThePaymentDateNotTheVerificationDate`).

### BR-MNY-20 — Closed balance is frozen
- **Rule:** A closed loan's breakdown is evaluated at `closedOn`, so it never re-accrues.
- **Condition:** `if closedOn != null && asOf > closedOn then asOf = closedOn`.
- **Enforced:** `RepaymentService.breakdown`.
- **Violation:** n/a.
- **Example:** A loan closed months ago shows ₹0 today (`RepaymentServiceTest.closedLoanReportsZeroOutstanding…`).

### BR-MNY-21 — Recording a repayment
- **Rule:** A repayment is recorded as PENDING_VERIFICATION and flagged partial if it is less than the penalty-aware balance on its date.
- **Condition:**
  - Refused with `LOAN_SETTLED` if the loan is CLOSED or REPAID.
  - `amount > 0`.
  - Idempotent on `(loanId, txnRef)`: a repeat returns the existing row.
  - `partial = amount < outstandingAsOf(paidOn)`.
- **Enforced:** `RepaymentService.recordPayment`. The callers are BORROWER (own loan) and ADMIN (`RepaymentController.record`).
- **Violation:** `LOAN_SETTLED`, `INVALID_AMOUNT`, `FORBIDDEN` (not your loan), `PAYMENT_PROOF_REQUIRED` (a BORROWER without a screenshot key).
- **Example:** An Accountant, collections or telecaller **cannot** record (`FORBIDDEN_ROLE`). Collections use BR-COL-12.

### BR-MNY-22 — Payment date validity
- **Rule:** A payment cannot be dated in the future (one day of handset skew is clamped to today) or before disbursal. A borrower may backdate by at most 3 days.
- **Condition:**
  - `paidOn > today + 1` → reject.
  - `paidOn > today` → clamp to today.
  - `paidOn < disbursedOn` → reject.
  - Borrower only: `paidOn < today − 3` → reject.
- **Enforced:** `RepaymentService.requireSanePaidOn` (`BORROWER_BACKDATE_DAYS = 3`).
- **Violation:** `INVALID_PAID_ON`.
- **Example:** A borrower posting 10 days back is refused; ADMIN may (`RepaymentServiceTest.adminMayBackdateBeyondTheBorrowerWindow`).

### BR-MNY-23 — Verifying / rejecting a repayment
- **Rule:** Only an Accountant (or ADMIN) verifies or rejects a repayment. A reject needs a picklist reason, and a VERIFIED payment cannot be rejected.
- **Condition:**
  - Reasons are `WRONG_REFERENCE | AMOUNT_MISMATCH | NOT_RECEIVED | UNREADABLE_PROOF | OTHER`.
  - Verify stamps `decided_by` / `decided_at` and recomputes the balance (BR-MNY-19).
- **Enforced:** `RepaymentController.verify/reject` (`requireRole("ACCOUNTANT","ADMIN")`); `RepaymentService.verifyPayment/rejectPayment`.
- **Violation:** `REJECTION_REASON_REQUIRED`, `INVALID_REJECTION_REASON`, `PAYMENT_ALREADY_VERIFIED`.
- **Example:** ADMIN `POST …/admin-record` records **and** verifies in one call. This is the only path where one person is both maker and checker (C-03).

### BR-MNY-24 — Offer summary / KFS tenure
- **Rule:** The summary screen and the Key Fact Statement price interest from **today** to the sanctioned repayment date, never less than 1 day.
- **Condition:**
  - `tenure = max(1, days(todayIST, approvedRepaymentDate))`.
  - When there is no approved date: `sanctionTenureDays ?: 30`.
  - Principal = chosen drawdown, else the sanctioned ceiling.
- **Enforced:** `OfferService.tenureDays`, `OfferService.summary`, `OfferService.agreementFacts`.
- **Violation:** n/a. See C-01 and C-05.
- **Example:** Sanctioned 06-03 with date 06-30, viewed on 06-21 → the KFS tenure is 9 days.

### BR-MNY-25 — APR printed on the KFS
- **Rule:** APR = (interest + fee) annualised over the money received before GST. GST is excluded.
- **Condition:**
  - `netOfFee = net + gst` (= `P − fee`).
  - `APR% = round1( (total − netOfFee) × 36500 / (netOfFee × tenure) )`, HALF_UP to 1 decimal place.
  - Prints `—` if `netOfFee ≤ 0` or `tenure ≤ 0`.
- **Enforced:** `SanctionLetterPdfRenderer.apr`.
- **Violation:** n/a.
- **Example:** ₹10,000, 27 days → **555.8%**; 36 days → 518.2%; 27 days with a 5% discount → 455.4%.

### BR-MNY-26 — Expected disbursal date shown
- **Rule:** The money is promised today if the borrower confirms before 18:00 IST, else tomorrow. These are calendar days with no weekend or holiday roll.
- **Condition:** `nowIST.time < 18:00 ? today : today + 1`.
- **Enforced:** `OfferService.expectedDisbursalDate`.
- **Violation:** n/a.
- **Example:** Confirmed at 18:05 on a Saturday → Sunday is shown.

### BR-MNY-27 — PayU credit-card fee
- **Rule:** The card fee is a flat percentage per card mode, rounded **up** to the paisa and added through `additional_charges`. DC and NB carry no fee.
- **Condition:**
  - `fee = bps ≤ 0 ? 0 : ceil(base × bps / 10000)`.
  - Default config is `CC:236,AMEX:236,RUPAYCC:236,DINR:236` (`NAVIX_PAYU_CARD_FEE_BPS`).
  - The display shows the maximum across modes.
- **Enforced:** `PayuCardFee.fee/displayFeePaise/additionalCharges`.
- **Violation:** n/a.
- **Example:** ₹13,700 → ₹323.32; ₹1 → ₹0.03.

### BR-MNY-28 — PayU order creation
- **Rule:** A borrower may open a PayU order for 1 rupee up to the current balance. The gateway must be enabled, and no manual payment may be pending.
- **Condition:**
  - `enabled() = flag payu-repayment && credentialsSet && hostsAllowed`.
  - `amount ≥ 100` paise.
  - No PENDING_VERIFICATION payment.
  - Every open order is re-read first: SUCCESS → refuse; PENDING younger than 15 min → refuse.
  - `amount ≤ outstandingAsOf(todayIST)`.
- **Enforced:** `PayuService.createOrder`.
- **Violation:** `PAYU_DISABLED`, `LOAN_SETTLED`, `INVALID_AMOUNT`, `MANUAL_PAYMENT_PENDING`, `PAYU_PAYMENT_RECEIVED`, `PAYU_IN_PROGRESS`, `AMOUNT_EXCEEDS_OUTSTANDING`.
- **Example:** txnid = `DB` + 20 random hex characters; `udf1` = loanId.

### BR-MNY-29 — PayU crediting
- **Rule:** Only a re-verified `success`+`captured` result credits the loan. The credited amount must equal the ordered amount exactly.
- **Condition:**
  - Callbacks check only the reverse hash and the txnid, then re-read with `verify`.
  - Credit `transaction_amount` as a VERIFIED `PAYU` payment (txnRef = `mihpayid`, recorded as SYSTEM).
  - Review reasons, each a review-queue row: `AMOUNT_MISMATCH` (not credited), `NO_PROVIDER_ID` (not credited), `FEE_MISMATCH` (credited), `OVERPAID` (credited), `LOAN_SETTLED` (overpayment flag, not credited).
  - Open orders expire after 24 h.
  - Status is never downgraded from SUCCESS, and FAILED/EXPIRED is never reopened.
- **Enforced:** `PayuService.onCallback/reconcile/apply/succeed/credit`.
- **Violation:** Anomalies go to the Accountant's review queue; resolution there is record-only.
- **Example:** Reconcile schedule after creation: +5 min, then +15 min, then hourly (`nextCheckAt`).

### BR-MNY-30 — Manual repayment while PayU is on
- **Rule:** With PayU enabled, the manual screenshot flow opens only after the borrower's newest PayU attempt failed, expired, or sat INITIATED for more than 30 minutes.
- **Condition:**
  - `manualAllowed = !enabled() || (no live open order && latest.status ∈ {FAILED, EXPIRED, INITIATED})`.
  - INITIATED counts as live only for the newest order and only within 30 min of creation.
  - Stale INITIATED rows (checked > 10 s ago) are re-read first.
- **Enforced:** `PayuService.manualAllowed`; `RepaymentController.record`.
- **Violation:** `MANUAL_PAYMENT_NOT_AVAILABLE`; `INVALID_METHOD` (a borrower posting method PAYU).
- **Example:** A borrower who closed the PayU tab can upload a screenshot 30 minutes later.

### BR-MNY-31 — Day boundary is IST
- **Rule:** Every money date ("today", DPD, paidOn) is computed in `Asia/Kolkata`.
- **Condition:** `LocalDate.now(IST)` in RepaymentService, OfferService, DebitService, PayuService, CollectionsService and SettlementService.
- **Enforced:** the `IST` constant in each class.
- **Violation:** Some lifecycle code uses the JVM default zone instead (C-02).
- **Example:** At 02:00 IST on the 10th, an IST-aware path says the 10th; a UTC JVM says the 9th.

### Worked examples

"G" is the loan's `grace_days`. Rows that match a unit test cite it. Everything else was computed from the formulas above with HALF_UP. P = ₹10,000 unless stated.

| # | Scenario | Inputs | Result | Verified by |
|---|---|---|---|---|
| E1 | Up-front economics | P ₹10,000 | fee ₹1,000 · GST ₹180 · net **₹8,820** | `LoanMathTest.workedExample` |
| E2 | Minimum loan | P ₹1,000 | fee ₹100 · GST ₹18 · net ₹882 | formula |
| E3 | Maximum formula loan | P ₹10,00,000 | fee ₹1,00,000 · GST ₹18,000 · net ₹8,82,000 | formula |
| E4 | HALF_UP on odd paise | P ₹12,345.67 | fee ₹1,234.57 (123,456.7→123,457) · GST ₹222.22 · net ₹10,888.88 | formula |
| E5 | HALF_UP boundary | P 1,000,005 paise / 1,000,001 paise | fee 100,001 / 100,000 paise | `LoanMathTest.halfUpRounding` |
| E6 | 5% repay-now discount | P ₹10,000, pct 5 | discount ₹500 · fee ₹500 · GST ₹90 · net ₹9,410 | formula |
| E7 | 7% (maximum) discount | P ₹10,000, pct 7 | fee ₹300 · GST ₹54 · net ₹9,646 | formula |
| E8 | Due date, single salary | disbursed 2026-06-03, day 30 | due 06-30 (27 d) · total ₹12,700 | `LoanMathTest.singleSalaryInWindow` |
| E9 | Due date, latest in window | disbursed 2026-06-24, day 30 | due 07-30 (36 d) · total ₹13,600 | formula (cf. `picksLatestWithinWindow`) |
| E10 | Latest, not first | disbursed 2026-06-10, day 15 | due 07-15 (35 d) · total ₹13,500 | formula |
| E11 | Clamp to short month | disbursed 2026-01-25, day 31 | due 2026-02-28 (34 d) | `LoanMathTest.clampsShortMonth` |
| E12 | Clamp, leap year | disbursed 2028-01-25, day 31 | due 2028-02-29 (35 d) | `LoanMathTest.clampsLeapFeb` |
| E13 | Short tenure | disbursed 2026-12-20, day 5 | due 2027-01-05 (16 d) · total ₹11,600 | formula |
| E14 | Prepayment | E8 loan paid on day 7 (06-10) | interest ₹700 → **₹10,700** | formula / CLAUDE.md §9 |
| E15 | Partial payment | E8 loan, ₹5,000 VERIFIED, as of due | ₹7,700 | `LoanMathTest.beforeDue` (770,000) |
| E16 | Day after due, G=0 | E8 loan, 2026-07-01 | 27 d interest + 1 penalty day = **₹12,900** | `RepaymentServiceTest.noGraceChargesPenaltyFromTheDayAfterDue` |
| E17 | 5 days late, G=0 | E8 loan, 2026-07-05 | ₹2,700 + ₹1,000 = **₹13,700** | same test (1,370,000) |
| E18 | Legacy grace, G=1 | E8 loan at due+1 / due+2 | ₹12,800 (28 interest days, 0 penalty) / ₹13,000 (28 d + 1 penalty day) | `RepaymentServiceTest.graceDayAddsOneDayOfInterestWithoutPenalty` |
| E19 | Penalty cap | E8 loan, 40 days late, G=0 | ₹10,000 + ₹2,700 + ₹6,000 (30-day cap) = ₹18,700; 90 days late → same | `RepaymentServiceTest.breakdownReportsCappedPenaltyDays` |
| E20 | Settlement cap | E8 loan, approved ₹7,000, ₹2,000 VERIFIED, due+10 | min(₹12,700+₹2,000−₹2,000, ₹7,000−₹2,000) = **₹5,000** | formula (`approvedSettlementCapsOutstanding`) |
| E21 | Settlement above balance | approved ₹15,000, at due | ₹12,700 (never increases) | `RepaymentServiceTest.settlementNeverIncreasesPayable` |
| E22 | Eligible limit floor | salary ₹41,234 | 25% = ₹10,308.50 → FLOOR → ₹10,300 | `LoanMathTest.flooredToHundredRupees` |
| E23 | Eligible limit cap | salary ₹4,20,00,000 | ₹10,00,000 | `LoanMathTest.cappedAtInstantCeiling` |
| E24 | Mandate ceiling | salary ₹50,000 (limit ₹12,500), sanctioned ₹15,000 | 2 × max(12,500, 15,000) = **₹30,000** | formula (BR-MAN-03) |
| E25 | KFS APR | ₹10,000 at 27 d / 36 d / 27 d with 5% discount | 555.8% / 518.2% / 455.4% | formula (BR-MNY-25) |
| E26 | KFS APR, stale sanction | approved date already passed → tenure floored to 1 | 4461.1% printed | formula (C-05) |
| E27 | KFS vs booked due date | sanctioned 06-03 (day 30 → 06-30); KFS viewed and signed 06-21; disbursed 06-21 | KFS: 9 d, ₹10,900. Booked loan: due 06-30, 9 d, ₹10,900 (same) | `LoanServiceTest.disbursementBooksTheCreditExecutivesApprovedRepaymentDate` |
| E28 | PayU card fee | ₹13,700 at 236 bps / ₹1 | ₹323.32 / ₹0.03 (ceil) | formula (BR-MNY-27) |
| E29 | DSA commission | net disbursed ₹8,820 | 3.5% = ₹308.70 | formula (BR-GRW-03) |

---

## 2. Eligibility & limits (BR-ELG)

### BR-ELG-01 — 25%-of-salary limit
- **Rule:** The eligible limit is 25% of monthly salary, floored to ₹100 and capped at ₹10,00,000.
- **Condition:** `limit = min(floor100(floor(salary × 0.25)), 100,000,000)`.
- **Enforced:** `LoanMath.eligibleLimitPaise` (also `LimitCalculator.eligibleLimitPaise`, which the `RiskPort` uses; the two are identical).
- **Violation:** n/a.
- **Example:** ₹50,000 → ₹12,500 (`LoanMathTest.quarterOfSalary`); salary 0 → 0.

### BR-ELG-02 — ADMIN limit override wins everywhere
- **Rule:** An ADMIN may set a per-customer limit that replaces the salary rule on every resolution of the limit.
- **Condition:**
  - `effectiveLimit = overrideOf(customerId) ?: risk.eligibleLimitPaise(salary)`.
  - Override ≥ ₹1,000, with **no upper cap**: it may exceed ₹10,00,000.
  - `null` clears the override.
  - Writes `profile_change_log`, pushes the value to every not-yet-disbursed application, and publishes `LoanLimitRevisedEvent` only when the limit **rises**.
- **Enforced:** `CustomerService.setLimitOverride` (ADMIN only); `EligibilityService.effectiveLimitPaise/overrideOf/recomputeForCustomer`.
- **Violation:** `FORBIDDEN_ROLE`, `INVALID_CUSTOMER`, `AMOUNT_TOO_LOW`.
- **Example:** Override ₹40,000 for a ₹50,000 salary → every live application shows ₹40,000, and a reborrow carries it (BR-LC-26).

### BR-ELG-03 — What recomputes the limit
- **Rule:** The stored `eligible_limit` is recomputed on salary verification, a salary edit, an override change and every reborrow. It always goes through the override-aware resolver.
- **Condition:** The callers are:
  - `ApplicationVerificationService.verifySalary` (this application only).
  - `CustomerReviewService.editOwnProfile` when salary changed.
  - `CustomerService.updateProfile` and `CustomerService.setLimitOverride` (both via `recomputeForCustomer`, which skips applications with a `loanId`).
  - `ApplicationFlowService.reborrow` (`overrideOf ?: eligibleLimitPaise(prior salary)`).
- **Enforced:** `EligibilityService.recomputeForCustomer`.
- **Violation:** n/a. A disbursed loan's limit is historical and never changes.
- **Example:** A borrower lowers their salary on the profile page → the limit on their open application drops at once.

### BR-ELG-04 — Borrower cannot raise salary after DRAFT
- **Rule:** Once the file has left DRAFT, a borrower may lower their declared salary but not raise it (staff must verify a raise).
- **Condition:**
  - BORROWER and `status ≠ DRAFT` and (`requested > stored` or `stored == null`) → refuse.
  - `verifySalary` refuses any borrower call outside DRAFT.
- **Enforced:** `CustomerReviewService.refuseSalaryRaise` (from `saveProfile`, `editOwnProfile`); `ApplicationVerificationService.verifySalary`.
- **Violation:** `SALARY_INCREASE_NEEDS_REVIEW`; `INVALID_SALARY` (salary ≤ 0).
- **Example:** Declared ₹40,000, then edited to ₹60,000 after submission → refused.

### BR-ELG-05 — Minimum loan ₹1,000
- **Rule:** No sanction, drawdown or application may be below ₹1,000.
- **Condition:** `amount < 100,000` paise → refuse.
- **Enforced:** `ApplicationFlowService.apply/sanction/acceptOffer`; `OfferService.chooseAmount`; `CustomerService.validateSanctionedAmount`; `CustomerService.setLimitOverride`.
- **Violation:** `AMOUNT_TOO_LOW` (`AMOUNT_INVALID` on `mandateOverride`).
- **Example:** A ₹999 drawdown is refused.

### BR-ELG-06 — Sanction is not capped by the limit
- **Rule:** The Credit Executive types the sanctioned amount. The 25% limit is on-screen guidance only, and the code sets no upper bound.
- **Condition:** `sanction()` checks only `amount ≥ ₹1,000` and `1 ≤ salaryDay ≤ 31`.
- **Enforced:** `ApplicationFlowService.sanction` (revamp decision 33).
- **Violation:** n/a. See C-07.
- **Example:** Salary ₹40,000 (limit ₹10,000); the executive may sanction ₹25,000.

### BR-ELG-07 — Drawdown ≤ sanctioned ceiling, locked at eSign
- **Rule:** In the offer journey the borrower picks between ₹1,000 and the sanctioned ceiling. Once signing has started (ESIGN row PENDING or PASS) the amount is frozen.
- **Condition:** `1,000 ≤ amount ≤ sanctionedAmountPaise`; refuse if ESIGN ∈ {PENDING, PASS}.
- **Enforced:** `OfferService.chooseAmount/requireNotSigned`; `ApplicationFlowService.acceptOffer` (uses the **stored** amount and ignores the client's).
- **Violation:** `AMOUNT_TOO_LOW`, `LIMIT_EXCEEDED`, `TERMS_LOCKED`, `NOT_APPLICABLE` (not SANCTIONED).
- **Example:** A borrower who opens the eSign tab and comes back cannot change ₹15,000 to ₹20,000.

### BR-ELG-08 — ADMIN revises a sanctioned amount
- **Rule:** An ADMIN may change the ceiling of a SANCTIONED, not-yet-disbursed application, but not below ₹1,000 or below the amount already drawn.
- **Condition:**
  - Status SANCTIONED, `loanId == null`, `sanctionedAmountPaise != null`.
  - `new ≥ ₹1,000`; `new ≥ amountRequested`.
  - Publishes `SanctionedAmountRevisedEvent`. The status does not change.
- **Enforced:** `CustomerService.changeSanctionedAmount/validateSanctionedAmount`.
- **Violation:** `NO_SANCTIONED_APPLICATION`, `APPLICATION_ID_REQUIRED`, `AMOUNT_TOO_LOW`, `BELOW_DRAWN_AMOUNT`.
- **Example:** Sanctioned ₹20,000, drawn ₹15,000 → may lower to ₹15,000, not ₹14,000.

### BR-ELG-09 — `apply()` ceiling is the stored limit
- **Rule:** On the legacy `apply` path the ceiling is the application's stored `eligible_limit`. The value the client posts is ignored.
- **Condition:** If `storedLimit != null`: require `0 < amount ≤ storedLimit`.
- **Enforced:** `ApplicationFlowService.apply`; `EligibilityService.isEligible`.
- **Violation:** `LIMIT_EXCEEDED`, `AMOUNT_TOO_LOW`, `NOT_APPLICABLE` (status not KYC_APPROVED/PRE_APPROVED), `NOT_ELIGIBLE` (PRE_APPROVED without a repaid loan).
- **Example:** A client posting `eligibleLimitPaise = ₹1,00,000` changes nothing.

### BR-ELG-10 — Risk category is staff-only and price-neutral
- **Rule:** A/B/C/D is shown to staff only and never changes pricing or the limit used.
- **Condition:**
  - `score = bureauPts + tenurePts + salaryPts`, clamped 0–100:
    - `bureauPts = clamp((bureau − 300) × 50 / 600, 0, 50)`; a missing bureau score defaults to 700.
    - `tenurePts = clamp(months × 25 / 24, 0, 25)`.
    - `salaryPts = clamp(salaryRupees × 25 / 100,000, 0, 25)`.
  - Bands: A ≥ 75, B ≥ 55, C ≥ 35, else D.
  - The live call passes `employmentMonths = null`, so tenure scores 0 and the maximum is 75.
- **Enforced:** `RiskAdapter.grade`, `RiskScoringService.scoreFrom/categoryForScore`; called from `ApplicationVerificationService.finishBureauPull/reuseBureauPass`.
- **Violation:** n/a. `LimitCalculator.limitForCategory` (×1/0.8/0.5/0) exists but is not used for pricing.
- **Example:** Bureau 780, salary ₹1,00,000 → 40 + 0 + 25 = 65 → **B**.

---

## 3. Lifecycle (BR-LC)

Legend for "who": **B** = borrower (own file), **CH** = Credit Head, **CE** = Credit Executive, **DH** = Disbursement Head, **ACC** = Accountant, **A** = ADMIN, **SYS** = system/scheduler/callback.

| From → To | Action (`application_event.action`) | Who | Method |
|---|---|---|---|
| (new) → DRAFT | `CREATE` | B (A for any customer) | `createDraft`, `reborrow` |
| DRAFT → KYC_PENDING | `SUBMIT_KYC` | B, staff via controller | `submitKyc` |
| DRAFT → REJECTED | `AUTO_REJECT_<code>` / `AUTO_REJECT_IDENTITY` / `REBORROW` | B or SYS | `autoReject`, `autoRejectSystem`, `reborrow` |
| DRAFT → PRE_APPROVED → SANCTIONED | `REBORROW` then `REAPPLY_SANCTION` | B | `reborrow` |
| DRAFT → KYC_PENDING (allowance) | `REBORROW_ALLOWED` | B | `reborrow` |
| KYC_PENDING → KYC_APPROVED / KYC_REJECTED | `KYC_APPROVE` / `KYC_REJECT` | CH, CE | `decideKyc` (optional stop) |
| KYC_PENDING / KYC_APPROVED → CREDIT_EXEC_PENDING | `ASSIGN` (`REASSIGN` stays in place) | CH | `assignExecutive` |
| KYC_APPROVED / CREDIT_EXEC_PENDING → SANCTIONED | `SANCTION` | CE (own file), CH | `sanction` |
| any non-terminal with edge → REJECTED | `REJECT_LEAD` | CE (own), CH; PRE_APPROVED: A only | `rejectLead` |
| SANCTIONED → DISBURSEMENT_PENDING | `ACCEPT_OFFER` / `MANDATE_OVERRIDE` / `ADMIN_FORCE_DISBURSE` | B / DH / A only | `acceptOffer`, `mandateOverride`, `adminForceDisbursementPending` |
| PRE_APPROVED → DISBURSEMENT_PENDING | `APPLY_FAST_TRACK` | B | `apply` (legacy) |
| DISBURSEMENT_PENDING → DISBURSED → ACTIVE | `VALIDATE_SUCCESS`, `ACTIVATE` | DH | `disbursementDecision(accept)` |
| DISBURSEMENT_PENDING → REJECTED | `DISB_REJECT` | DH | `disbursementDecision(reject)` |
| DISBURSEMENT_FAILED → DISBURSEMENT_PENDING | `RETRY` | DH | `retryDisbursement` (dead state, BR-LC-20) |
| ACTIVE / OVERDUE → CLOSED | `REPAID` | SYS (whoever verified the last payment) | `closeForLoan` |
| REJECTED → DRAFT → KYC_PENDING | `REOPEN_RESCORE` | A only | `reopenAfterRescore` |
| non-terminal → CANCELLED | `CANCEL` | B (own), A | `cancel` |

### BR-LC-01 — State machine, no stage-skipping
- **Rule:** Every status change must be an edge in `ApplicationStatus.TRANSITIONS`.
- **Condition:**
  - `from.canTransitionTo(to)`.
  - Terminal statuses (empty edge set): KYC_REJECTED, ACCOUNTANT_PENDING, CLOSED, WRITTEN_OFF, REJECTED, CANCELLED.
  - Retired statuses keep their edges for historical rows only: REVIEW_PENDING, CREDIT_EXEC_APPROVED, CREDIT_HEAD_PENDING, CREDIT_HEAD_APPROVED, ACCOUNTANT_PENDING.
- **Enforced:** `ApplicationFlowService.transition`; `ApplicationStatus.canTransitionTo/isTerminal`.
- **Violation:** `ILLEGAL_TRANSITION` ("A → B is not allowed"); a WARN is logged.
- **Example:** Sanctioning straight from KYC_PENDING (no assignment) → `ILLEGAL_TRANSITION`.

### BR-LC-02 — Every transition and decision is audited
- **Rule:** Each transition, and each same-status decision (`APPLY`, `REASSIGN`, `MARK_PENDING`, `REVERIFY`, `STEP_REOPENED`, `STEP_LINK_SENT`, `VERIFICATION_OVERRIDE`), appends an `application_event` row.
- **Condition:** The row carries actor id, real role and acting role (V78), action, notes, timestamp, and `retryFrom` for blocks. It then publishes `ApplicationTransitionedEvent`, which is consumed AFTER_COMMIT and async.
- **Enforced:** `ApplicationFlowService.logEvent`; `VerificationInvalidationService.logReverify`; `VerificationOutreachService.logEvent`.
- **Violation:** n/a. The table is append-only and is the SoD source of truth.
- **Example:** The timeline reads "by Meera Krishnan · ADMIN (as Credit Executive)".

### BR-LC-03 — Starting a fresh application
- **Rule:** A borrower opens a DRAFT only for themselves, when they are not blocked, hold no live loan or file, have never repaid a loan, and intake is open.
- **Condition:** The checks run in this order:
  1. Role BORROWER (or ADMIN); body `customerId` = JWT subject.
  2. `assertCanStartNewApplication` (BR-LC-04).
  3. `hasRepaidLoan` → refuse.
  4. `onboarding-paused` on → refuse.
- **Enforced:** `ApplicationFlowService.createDraft/assertOnboardingOpen`.
- **Violation:** `FORBIDDEN_ROLE`, `NOT_ELIGIBLE` (block), `ACTIVE_LOAN`, `ACTIVE_APPLICATION`, `APPLICATIONS_CLOSED`, `RETURNING_BORROWER`, `ONBOARDING_PAUSED`.
- **Example:** A repaid borrower who hits signup is sent to `/reloan` (`RETURNING_BORROWER`).

### BR-LC-04 — One advance at a time
- **Rule:** A customer may not start a new application while blocked, while a loan is live, or while any pre-disbursement file is still open.
- **Condition:** The checks run in this order:
  1. Advisory lock on the customer (`MandateService.lockCustomer`), held to commit.
  2. `assertNotBlocked`.
  3. Any application in {ACTIVE, OVERDUE, DEFAULTED} → `ACTIVE_LOAN`.
  4. Any other non-terminal application → `ACTIVE_APPLICATION`.
  5. Flag `applications-closed` → `APPLICATIONS_CLOSED`. This check is last, so a borrower with a file in flight still gets `ACTIVE_APPLICATION`.
- **Enforced:** `ApplicationFlowService.assertCanStartNewApplication` (called by `createDraft` and `reborrow`).
- **Violation:** as listed.
- **Example:** Two parallel "start" requests: the second waits on the lock and then sees `ACTIVE_APPLICATION`.

### BR-LC-05 — Submitting intake (DRAFT → KYC_PENDING)
- **Rule:** Submission requires every intake check to have been attempted, the T&C accepted, the Aadhaar number typed and all four card images uploaded.
- **Condition:**
  1. `allRequiredPassed`: PAN, EMAIL, BUREAU and SALARY each hold PASS, REVIEW or FAIL, and `termsAcceptedAt != null`.
  2. `intakeCardsComplete`: `aadhaar` is non-blank and the docs include AADHAAR_CARD_FRONT/BACK and PAN_CARD_FRONT/BACK.
  3. `rejectIfAadhaarDuplicate` (BR-ID-07).
  4. `verifyPanAadhaarLink`, best-effort; a failure never blocks.
  5. `onboarding-paused` off.
  6. `transition`.
- **Enforced:** `ApplicationController.submitKyc`; `ApplicationFlowService.submitKyc`.
- **Violation:** `KYC_INCOMPLETE` (two distinct messages), `ONBOARDING_PAUSED`; duplicate → REJECTED instead of submitted.
- **Example:** A failed PAN (FAIL) still submits; a never-run PAN does not.

### BR-LC-06 — Self-employed declaration
- **Rule:** Declaring self-employed at intake auto-rejects the file with a 90-day block. The borrower sees only a neutral screen.
- **Condition:** `autoReject(appId, SELF_EMPLOYED, "Declared self-employed at intake", 90)`.
- **Enforced:** `ApplicationController.selfEmployed` → `ApplicationFlowService.autoReject` (BORROWER gate).
- **Violation:** The file moves to REJECTED with action `AUTO_REJECT_SELF_EMPLOYED`.
- **Example:** The borrower may restart on or after the date shown (BR-REJ-04).

### BR-LC-07 — Credit assignment
- **Rule:** Only the Credit Head (or ADMIN) assigns. The assignee must be the Head themselves or an ACTIVE Credit Executive. A reassignment keeps the stage and all review data.
- **Condition:**
  - `role = CREDIT_HEAD` (ADMIN passes and is exempt from the assignee check).
  - Self-assignment by the Head is allowed; otherwise `staffDirectory.isActiveWithRole(id, CREDIT_EXECUTIVE)`.
  - If already CREDIT_EXEC_PENDING → `REASSIGN` event (from = to); else transition `ASSIGN`.
- **Enforced:** `ApplicationFlowService.assignExecutive`.
- **Violation:** `FORBIDDEN_ROLE`, `INVALID_ASSIGNEE`, `ILLEGAL_TRANSITION` (from DRAFT, etc.).
- **Example:** Assigning to a disabled executive → `INVALID_ASSIGNEE`.

### BR-LC-08 — Executives act only on their own files
- **Rule:** A Credit Executive may read or decide only files assigned to them. The Head and ADMIN may decide any file.
- **Condition:** If the real role is CREDIT_EXECUTIVE: require `actorId == app.assignedExecutiveId`.
- **Enforced:** `ApplicationFlowService.requireCreditOwnership` (called by `sanction`, `rejectLead`, `markPending`, `get`, `ApplicationVerificationService.manualDecision`).
- **Violation:** `CREDIT_FILE_NOT_ASSIGNED`.
- **Example:** An executive opening a colleague's file via a URL is refused.

### BR-LC-09 — Sanction (the final credit decision)
- **Rule:** The assigned Executive (or the Head) sanctions an amount and a salary day. The repayment date is projected from today. An unresolved duplicate-Aadhaar flag blocks the sanction.
- **Condition:**
  - The AADHAAR_DUPLICATE row is absent or PASS.
  - `amount ≥ ₹1,000`; `1 ≤ salaryDay ≤ 31`.
  - `approvedRepaymentDate = dueDateFromSalary(today, day)`; `sanctionTenureDays = days(today, date)`.
  - Clears the parked tag (`markedPendingAt`, `pendingReason`).
  - Sets `sanctionedBy` / `sanctionedAt`.
- **Enforced:** `ApplicationFlowService.sanction`.
- **Violation:** `FORBIDDEN_ROLE`, `CREDIT_FILE_NOT_ASSIGNED`, `AADHAAR_DUPLICATE_UNRESOLVED`, `AMOUNT_TOO_LOW`, `INVALID_SALARY_DAY`, `ILLEGAL_TRANSITION`.
- **Example:** No Head counter-approval exists. A sanction never expires (decision 41).

### BR-LC-10 — Reject lead (manual credit reject)
- **Rule:** A credit reject needs a reason, writes a MANUAL register row and blocks the customer for 30 days. Only ADMIN may reject a PRE_APPROVED file.
- **Condition:**
  - `remarks` is non-blank.
  - `blockedUntil = now + 30 d`.
  - `recordRejection(MANUAL, remarks, auto=false)`; transition REJECTED with `retryFrom`.
- **Enforced:** `ApplicationFlowService.rejectLead/rejectWithBlock` (`MANUAL_REJECT_BLOCK_DAYS = 30`).
- **Violation:** `NOTE_REQUIRED`, `FORBIDDEN_ROLE` (PRE_APPROVED by non-ADMIN), `CREDIT_FILE_NOT_ASSIGNED`, `ILLEGAL_TRANSITION`.
- **Example:** Works from SANCTIONED too (SANCTIONED → REJECTED is legal).

### BR-LC-11 — Park (mark pending)
- **Rule:** "Mark pending" tags a file with a reason without changing its status, and does not notify the borrower.
- **Condition:** Sets `markedPendingAt = now` and `pendingReason`, and logs a same-status `MARK_PENDING` event.
- **Enforced:** `ApplicationFlowService.markPending`.
- **Violation:** `FORBIDDEN_ROLE`, `CREDIT_FILE_NOT_ASSIGNED`.
- **Example:** The tag is cleared by the next `sanction`.

### BR-LC-12 — Offer-journey steps require SANCTIONED
- **Rule:** Every borrower offer-journey write runs only while the file is SANCTIONED. ADMIN reference corrections are exempt.
- **Condition:** `requireSanctioned`: `status == SANCTIONED`.
- **Enforced:** `OfferService.requireSanctioned` (`chooseAmount`, `saveReferences` for non-ADMIN, `summary`, `confirmDisbursalAccount`, …).
- **Violation:** `NOT_APPLICABLE` ("This offer isn't ready").
- **Example:** After acceptance (DISBURSEMENT_PENDING) the amount screen is read-only.

### BR-LC-13 — References
- **Rule:** Exactly two references. Each needs a name, a 10-digit mobile and a relation from the list. Neither may be the borrower's own mobile, and the two mobiles must differ. With `reference-otp` on, names must be letters only, and each new number must confirm by OTP.
- **Condition:**
  - `inputs.size == 2`; `digits(mobile).length == 10`; `relation ∈ ApplicationReference.RELATIONS`.
  - `mobile ≠ own`; `mobile1 ≠ mobile2`.
  - OTP on: `name ~ [\p{L} .'-]{2,60}`. A changed number gets `otpRequired = true`; an unchanged one keeps its state.
- **Enforced:** `OfferService.saveReferences`.
- **Violation:** `REFERENCES_REQUIRED`, `REFERENCE_INVALID`, `REFERENCE_IS_SELF`, `REFERENCE_DUPLICATE`.
- **Example:** An ADMIN correction never sets an OTP requirement and never moves the journey pointer.

### BR-LC-14 — Accept offer (→ DISBURSEMENT_PENDING)
- **Rule:** The borrower's file moves to disbursement only with a **PASS** eSign, no reference still awaiting OTP, the mandate gate cleared and a valid stored drawdown.
- **Condition:**
  - `status == SANCTIONED`; ESIGN row == PASS (PENDING is not enough).
  - No reference `isOtpPending`.
  - `!mandateRequired(app)`.
  - `drawn = amountRequested ?: ceiling`; `₹1,000 ≤ drawn ≤ ceiling`.
- **Enforced:** `ApplicationFlowService.acceptOffer`. It is called by `OfferService.afterAccountConfirmed` and `OfferService.mandateStatus`.
- **Violation:** `NOT_APPLICABLE`, `ESIGN_REQUIRED`, `REFERENCE_OTP_PENDING`, `MANDATE_REQUIRED`, `AMOUNT_TOO_LOW`, `LIMIT_EXCEEDED`.
- **Example:** A borrower who abandoned the Signzy tab (ESIGN PENDING) cannot accept.

### BR-LC-15 — Mandate override
- **Rule:** The Disbursement Head may bypass the eMandate step only when the borrower's latest mandate attempt is BANK_UNSUPPORTED. The agreement must be signed and the reason recorded.
- **Condition:** `status == SANCTIONED`; `mandateService.isBankUnsupported(appId)`; ESIGN PASS; notes non-blank; `₹1,000 ≤ drawn ≤ ceiling`.
- **Enforced:** `ApplicationFlowService.mandateOverride` (`requireAnyRole("DISBURSEMENT_HEAD")`).
- **Violation:** `NOT_APPLICABLE`, `MANDATE_OVERRIDE_NOT_ALLOWED`, `AGREEMENT_NOT_SIGNED`, `NOTE_REQUIRED`, `AMOUNT_INVALID`.
- **Example:** A borrower whose authorisation merely failed must retry; no override is offered.

### BR-LC-16 — ADMIN force to disbursement
- **Rule:** Only ADMIN may push a SANCTIONED, signed file to DISBURSEMENT_PENDING without the borrower finishing the journey. A note is required.
- **Condition:** Real role == ADMIN (not "ADMIN passes", ADMIN **only**); `status == SANCTIONED`; ESIGN PASS; notes non-blank.
- **Enforced:** `ApplicationFlowService.adminForceDisbursementPending/requireAdminOnly`.
- **Violation:** `FORBIDDEN_ROLE`, `NOT_APPLICABLE`, `AGREEMENT_NOT_SIGNED`, `NOTE_REQUIRED`.
- **Example:** The event notes read "Forced by <name> — <reason>".

### BR-LC-17 — Disbursement decision
- **Rule:** The Disbursement Head either releases with the transfer's transaction id or rejects with a reason (30-day block). There is no accountant hop.
- **Condition:**
  - Accept: `txnRef` is non-blank → `finalizeDisbursal`.
  - Reject: `rejectWithBlock(DISB_REJECT, notes)`.
- **Enforced:** `ApplicationFlowService.disbursementDecision` (`requireRole("DISBURSEMENT_HEAD")`).
- **Violation:** `TXN_REF_REQUIRED`, `NOTE_REQUIRED`, `ILLEGAL_TRANSITION`.
- **Example:** Accept without a UTR → `TXN_REF_REQUIRED`.

### BR-LC-18 — What disbursal does (atomically)
- **Rule:** Release moves the file DISBURSED → ACTIVE and, in the same transaction, mints the loan, stamps `disbursal_txn_ref`, consumes the discount offer and runs the referral reward and the DSA commission. After commit, the case auto-assignment runs.
- **Condition:** The steps, in order:
  1. `transition(DISBURSED)`.
  2. `LoanService.disburse(app, LocalDate.now(), txnRef)`, which runs BR-MNY-02…13 and `RepaymentDiscountOffers.consume`.
  3. `ReferralService.onLoanDisbursed`.
  4. `DsaCommissionService.onLoanDisbursed` (never throws).
  5. `transition(ACTIVE)`.
  - After commit: `CollectionAutoAssigner.onTransition`.
- **Enforced:** `ApplicationFlowService.finalizeDisbursal`.
- **Violation:** A consumed discount offer rolls the whole release back (`DISCOUNT_OFFER_USED`).
- **Example:** The loan status starts as ACTIVE, with `outstanding = total_repayable`.

### BR-LC-19 — Cancel
- **Rule:** Only the owning borrower or ADMIN may cancel, and only where an edge to CANCELLED exists.
- **Condition:**
  - ADMIN, or BORROWER with `app.customerId == actor.id`.
  - The state machine allows CANCELLED from DRAFT, KYC_PENDING, KYC_APPROVED, PRE_APPROVED, CREDIT_EXEC_PENDING, SANCTIONED and DISBURSEMENT_FAILED. **Not** from DISBURSEMENT_PENDING.
- **Enforced:** `ApplicationFlowService.cancel/requireCancelAuthority`.
- **Violation:** `FORBIDDEN`, `FORBIDDEN_ROLE`, `ILLEGAL_TRANSITION`.
- **Example:** Credit staff cannot cancel; they reject instead.

### BR-LC-20 — DISBURSEMENT_FAILED is dead
- **Rule:** No live code moves a file into DISBURSEMENT_FAILED. The `retryDisbursement` edge exists only for historical rows.
- **Condition:** `DISBURSEMENT_FAILED → {DISBURSEMENT_PENDING, CANCELLED}`; no inbound edge is ever triggered.
- **Enforced:** `ApplicationFlowService.retryDisbursement` (DISBURSEMENT_HEAD).
- **Violation:** n/a.
- **Example:** Do not build on it.

### BR-LC-21 — Close on full repayment
- **Rule:** When the loan closes (BR-MNY-19), the application moves ACTIVE/OVERDUE → CLOSED with action `REPAID`. A settlement closure says so in the notes.
- **Condition:**
  - Moves only if the application is ACTIVE or OVERDUE; otherwise this is a no-op (idempotent).
  - Notes: "Loan fully repaid" or "Closed by approved settlement of ₹X (full & final)".
- **Enforced:** `ApplicationFlowService.closeForLoan`; `RepaymentService.recomputeOutstanding`.
- **Violation:** n/a.
- **Example:** It is attributed to the Accountant who verified the last payment, or to SYSTEM for PayU/NACH.

### BR-LC-22 — Delinquency lives on the loan, not the application
- **Rule:** Going past due flips the **loan** to IN_COLLECTIONS; the application stays ACTIVE. No live code drives the application into OVERDUE, DEFAULTED or WRITTEN_OFF.
- **Condition:**
  - Each day the reminder sweep calls `markInCollections`, which sets `loan.status = IN_COLLECTIONS` when `today > dueDate` and the status is ACTIVE or OVERDUE.
  - A read-time `Loan.effectiveStatus` returns OVERDUE for an ACTIVE loan past its due date.
- **Enforced:** `LoanDirectoryAdapter.markInCollections`; `PaymentReminderScheduler.sendDueAndOverdueReminders`; `Loan.effectiveStatus`.
- **Violation:** n/a. See C-04.
- **Example:** The application of a loan 20 days overdue still reads ACTIVE.

### BR-LC-23 — Legacy PRE_APPROVED fast-track
- **Rule:** `apply()` on a PRE_APPROVED file goes straight to DISBURSEMENT_PENDING, but only for a customer who has repaid a loan and has cleared the mandate gate.
- **Condition:** `status ∈ {KYC_APPROVED, PRE_APPROVED}`; PRE_APPROVED requires `hasRepaidLoan`; `amount ≥ ₹1,000`; `amount ≤ storedLimit`; `requireMandate`.
- **Enforced:** `ApplicationFlowService.apply`.
- **Violation:** `NOT_APPLICABLE`, `NOT_ELIGIBLE`, `AMOUNT_TOO_LOW`, `LIMIT_EXCEEDED`, `MANDATE_REQUIRED`.
- **Example:** On KYC_APPROVED this only logs a same-status `APPLY` (→ `LOAN_APPLIED` to the Credit Heads). No ESIGN check runs on this path (C-09).

### BR-LC-24 — Reborrow routing
- **Rule:** A returning borrower's new application is routed by repayment history alone. The credit score is not a gate.
- **Condition:**
  1. BORROWER; `assertCanStartNewApplication`.
  2. A prior profile exists, else `NO_PRIOR_LOAN`.
  3. Not repaid and not disqualified → `NO_PRIOR_LOAN`.
  4. New DRAFT with `eligibleLimit = override ?: limit(prior salary)` and the latest salary day; clone the profile and the CREDIT_BRIEF doc.
  5. If disqualified: an unused allowance → consume it, pin the discount, copy the carried checks and references, `KYC_PENDING (REBORROW_ALLOWED)`. Otherwise `PAST_DELINQUENCY` register row (no block date) → `REJECTED (REBORROW)`.
  6. If clean: pin the discount → `PRE_APPROVED`. With a prior sanctioned application → `carryOverForReapply` → `SANCTIONED (REAPPLY_SANCTION)`.
- **Enforced:** `ApplicationFlowService.reborrow`.
- **Violation:** `NO_PRIOR_LOAN` (the `/reloan` page falls back to signup), `ACTIVE_LOAN`, `ACTIVE_APPLICATION`, `APPLICATIONS_CLOSED`, `NOT_ELIGIBLE`.
- **Example:** `onboarding-paused` does **not** stop a reborrow.

### BR-LC-25 — Standing: `isDisqualifiedByHistory`
- **Rule:** A customer is disqualified if any loan was repaid more than 5 days past its due date, if any loan is still owed past its due date, or if any application is DEFAULTED or WRITTEN_OFF.
- **Condition:**
  - Any application status ∈ {DEFAULTED, WRITTEN_OFF}, **or** for any loan with a due date:
    - `effectiveStatus(today) ∈ {OVERDUE, IN_COLLECTIONS}`, **or**
    - any VERIFIED payment has `paidOn > dueDate + 5`.
  - `LATE_REPAYMENT_TOLERANCE_DAYS = 5`, counted from the due date (not from grace). Rejected payments do not count.
- **Enforced:** `ApplicationFlowService.isDisqualifiedByHistory/paidBeyondTolerance`.
- **Violation:** Auto-reject `PAST_DELINQUENCY` (BR-LC-24).
- **Example:** Due 06-30, last payment 07-05 → clean. Last payment 07-06 → disqualified.

### BR-LC-26 — Carried over on a clean reborrow
- **Rule:** The person-level evidence and the prior sanction ceiling carry over. The eSign, the drawdown and the confirmation of the disbursal account never carry over.
- **Condition:**
  - **Carried:**
    - `reappliedFrom`.
    - `sanctionedAmountPaise` (the ADMIN override if set, else the prior sanction).
    - `sanctionedBy`; salary day.
    - **New** `approvedRepaymentDate = dueDateFromSalary(today, day)`.
    - Disbursal account number, IFSC, holder and bank, plus `disbursalAccountVerified`.
    - Verification rows AADHAAR, SELFIE, ADDRESS, EMPLOYMENT (re-stamped "Carried over from application N").
    - Both references.
    - The profile clone: identity, employment, salary, salary account, emails plus their verified flags, terms/PEP acceptance, bureau score, risk, credit brief and penny-drop flag.
  - **Not carried:** ESIGN, PENNY_DROP row, `amountRequested`, `disbursalAccountChanged` (reset to false), `disbursalConfirmedAt`.
- **Enforced:** `ApplicationFlowService.carryOverForReapply/copyCarriedVerifications/copyReferences/copyProfileForReborrow` (`CARRIED_CHECKS`).
- **Violation:** n/a.
- **Example:** The borrower re-walks amount → date → summary → sanction letter/eSign → 🎉 → account confirm.

### BR-LC-27 — Rescore reopen
- **Rule:** ADMIN or the backfill job may reopen a REJECTED file into KYC_PENDING only if its rejection came straight from DRAFT. Only the LOW_BUREAU_SCORE block is cleared.
- **Condition:** `status == REJECTED && rejectionSource == DRAFT` → clear `blocked_until` on LOW_BUREAU_SCORE rows → set DRAFT → transition KYC_PENDING (`REOPEN_RESCORE`). Otherwise SKIPPED.
- **Enforced:** `ApplicationFlowService.reopenAfterRescore` (`requireRole("ADMIN")`).
- **Violation:** Returns `SKIPPED`; never forces an illegal edge.
- **Example:** A MANUAL block on the same customer survives the reopen.

### BR-LC-28 — Profile slices and the salary day
- **Rule:** Intake saves the profile in partial slices; a null field never overwrites a stored one. The `previousSalaryDate` slice sets the application's salary-credit day, and after DRAFT a borrower can no longer move it.
- **Condition:**
  - `app.salaryCreditDay = previousSalaryDate.dayOfMonth`, applied unless BORROWER and `status ≠ DRAFT`.
  - ADMIN corrects it via `changeSalaryCreditDay` (1–31). On a SANCTIONED, not-disbursed application this also recomputes the approved date, using IST.
- **Enforced:** `CustomerReviewService.saveProfile`; `CustomerService.changeSalaryCreditDay`.
- **Violation:** `INVALID_SALARY_DAY`, `NO_APPLICATION`.
- **Example:** A DISBURSEMENT_PENDING file keeps its signed KFS date even after an ADMIN day change.

---

## 4. Rejections & blocks (BR-REJ)

| Reason code | Set by | Block (`blocked_until`) | Borrower sees | Lifted by |
|---|---|---|---|---|
| `SELF_EMPLOYED` | `ApplicationController.selfEmployed` | now + **90 d** | "not eligible, apply again on DATE" | time |
| `LOW_BUREAU_SCORE` | `ApplicationVerificationService.finishBureauPull/reuseBureauPass` (only if `bureau-auto-reject` on); the ADMIN-run `BureauBackfillService` sweep | now + **90 d** | same | time, or `reopenAfterRescore` |
| `FRAUD_REJECTED` | `ApplicationVerificationService.rejectForIdentityMismatch` | now + **365 d** | same | time |
| `AADHAAR_DUPLICATE` | `ApplicationVerificationService.rejectIfAadhaarDuplicate` | **INDEFINITE** (9999-12-31 UTC) | "not eligible, contact support" | nothing in code |
| `MANUAL` | `rejectLead`, `disbursementDecision(reject)` | now + **30 d** | "apply again on DATE" | time |
| `PAST_DELINQUENCY` | `reborrow` | **null** (none) | rejection screen | ADMIN reapply allowance (BR-GRW-11) |
| `BLOCKED_BY_STAFF` | `CustomerService.block` | **INDEFINITE** | "contact support" | ADMIN `unblock` (sets it to now) |

### BR-REJ-01 — Every rejection writes the register
- **Rule:** Every rejection, automatic or manual, appends an `application_rejection` row with reason, staff-only detail, auto flag, customer id, the resolved mobile and the block end.
- **Condition:** `mobile = this application's profile mobile ?: the customer's latest profile mobile`.
- **Enforced:** `ApplicationFlowService.recordRejection/mobileOf`.
- **Violation:** n/a.
- **Example:** The detail for AADHAAR_DUPLICATE names the earlier customer and application (staff-only).

### BR-REJ-02 — How a block is matched
- **Rule:** A block is matched on the **customer id** first, then on the **mobile**. PAN is never a block key.
- **Condition:**
  1. A live `BLOCKED_BY_STAFF` row on the customer id → refuse ("contact support").
  2. `byCustomer` = the latest-ending row with `blockedUntil > now` for the customer id. `byMobile` = the same for the latest profile mobile.
  3. Use `byCustomer` if present, else `byMobile`.
  - An indefinite block gets the "contact support" text; otherwise "apply again on or after dd MMM yyyy" (IST).
- **Enforced:** `ApplicationFlowService.assertNotBlocked` (from `assertCanStartNewApplication`).
- **Violation:** `NOT_ELIGIBLE`.
- **Example:** A borrower who changes their profile mobile is still blocked by customer id.

### BR-REJ-03 — The reason is never shown
- **Rule:** The borrower is never told which rule fired: only "not eligible" plus a date, or "contact support".
- **Condition:**
  - The register `reason_code` / `reason_detail` are staff-only.
  - Identity-rule events use the generic action `AUTO_REJECT_IDENTITY` / "Identity check failed".
  - Every `AUTO_REJECT_*` maps to the reasonless `CREDIT_REJECTED` notification.
  - The date only, never the window length.
- **Enforced:** `ApplicationFlowService.assertNotBlocked`; `NotificationEventListener.mapAction`; `ApplicationView.forReader`.
- **Violation:** n/a.
- **Example:** 30-day and 90-day blocks render identically apart from the date.

### BR-REJ-04 — The rejection notification carries the retry date
- **Rule:** A rejecting transition that set a block includes "You can apply again on or after DATE" in the borrower message.
- **Condition:** `ApplicationTransitionedEvent.retryFrom != null` → the suffix is appended (IST date).
- **Enforced:** `NotificationEventListener` (retry-suffix helper).
- **Violation:** n/a.
- **Example:** A manual reject on 2026-11-13 → "…on or after 13 Dec 2026."

### BR-REJ-05 — Staff block and unblock
- **Rule:** An Accountant (or ADMIN) may block a customer from future loans with a reason. Only ADMIN may unblock.
- **Condition:**
  - Block: reason required; the customer must be visible and have a mobile; no live BLOCKED_BY_STAFF row on that mobile; writes a row with `blockedUntil = INDEFINITE`.
  - Unblock: ADMIN sets `blockedUntil = now`.
- **Enforced:** `CustomerService.block` (`requireRole("ACCOUNTANT")`), `CustomerService.unblock` (`requireAdmin`).
- **Violation:** `REASON_REQUIRED`, `ALREADY_BLOCKED`, `FORBIDDEN_ROLE`.
- **Example:** Typically decided while verifying a repayment.

### BR-REJ-06 — What bypasses a block or rejection
- **Rule:** Only two bypasses exist, each narrowly scoped: the rescore reopen and the reapply allowance.
- **Condition:**
  - `reopenAfterRescore` lifts **only** LOW_BUREAU_SCORE rows on that application, and only for a DRAFT-sourced rejection.
  - The reapply allowance lifts **only** the PAST_DELINQUENCY history rule. It is checked *after* `assertNotBlocked` and the live-loan check, so it never bypasses a dated block, a staff block, ACTIVE_LOAN, ACTIVE_APPLICATION or APPLICATIONS_CLOSED.
- **Enforced:** `ApplicationFlowService.reopenAfterRescore/reborrow`.
- **Violation:** n/a.
- **Example:** A customer under a 30-day MANUAL block who also holds an allowance waits out the 30 days first.

### BR-REJ-07 — Bureau score auto-reject is suspended
- **Rule:** A numeric bureau score below 550 auto-rejects **only** when `bureau-auto-reject` is on (it is off since V64). It is also skipped on an identity mismatch, and whenever the rescore backfill passes `allowAutoReject = false`.
- **Condition:** `autoRejectEnabled() && allowAutoReject && mismatch == null && score != null && score < 550` → `autoReject(LOW_BUREAU_SCORE, 90 d)`.
- **Enforced:** `ApplicationVerificationService.finishBureauPull/reuseBureauPass/autoRejectEnabled`; `MIN_BUREAU_SCORE = 550`.
- **Violation:** REJECTED (`AUTO_REJECT_LOW_BUREAU_SCORE`).
- **Example:** Today every score goes to a human. A thin file (no score) never auto-rejects.

---

## 5. Identity & fraud (BR-ID)

### BR-ID-01 — Aadhaar shape and Verhoeff
- **Rule:** A typed Aadhaar must be 12 digits, start 2–9, and pass the UIDAI Verhoeff checksum. Spaces and hyphens are stripped first.
- **Condition:**
  - `normalize`: drop `[\s-]`.
  - `isValid`: `len == 12`, `first ∈ '2'..'9'`, and `c = D[c][P[i % 8][digit]]` over the reversed digits yields `c == 0`.
- **Enforced:** `Aadhaar.normalize/isValid`; `CustomerReviewService.normalizeAadhaar`; `OnboardingWaitlistService` (waitlist form). Mirrored in `frontend/src/lib/aadhaar.ts`.
- **Violation:** `INVALID_AADHAAR`. The number is refused, never stored-and-flagged, so a typo cannot later trip the fraud rule.
- **Example:** `1234 5678 9012` → refused (it starts with 1).

### BR-ID-02 — Aadhaar locks once read
- **Rule:** The typed Aadhaar becomes immutable once a provider has read it or the file has left DRAFT.
- **Condition:** Locked if `status ≠ DRAFT`, or a PAN row exists, or an AADHAAR row exists, or `profile.aadhaarVerified == true`.
- **Enforced:** `CustomerReviewService.requireAadhaarEditable` (from `saveProfile` when the number changes).
- **Violation:** `AADHAAR_LOCKED`.
- **Example:** After the consent screen fired PAN, the number cannot be edited.

### BR-ID-03 — Masked-Aadhaar comparison
- **Rule:** The full typed number is compared positionally against a provider's masked copy wherever the mask shows a digit. Any other mask length compares the last four digits.
- **Condition:**
  - If the number is not 12 digits or the mask is null → `UNKNOWN`.
  - A 12-character mask: every digit position must match; no digit shown → `UNKNOWN`.
  - Otherwise: `lastFour(all digits of the mask)` must equal the number's suffix; fewer than 4 digits → `UNKNOWN`.
- **Enforced:** `Aadhaar.matchesMasked/lastFour`.
- **Violation:** returns `MISMATCH`, which feeds BR-ID-04.
- **Example:** `XXXXXXXX1234` vs …1234 → MATCH; `65XXXXXXXX90` compares positions 1–2 and 11–12.

### BR-ID-04 — Aadhaar-number fraud rule
- **Rule:** If the typed Aadhaar disagrees with the masked Aadhaar on the PAN record or from DigiLocker, the file is auto-rejected as FRAUD_REJECTED for 365 days.
- **Condition:**
  - It runs at the PAN verification (if the provider returns a mask) and at DigiLocker completion.
  - It rejects only if `app.status.canTransitionTo(REJECTED)`. That includes SANCTIONED and DISBURSEMENT_PENDING; otherwise it logs and leaves the file for review.
  - The verification row stores `aadhaarMismatch` and `applicationRejected`.
  - At DigiLocker the AADHAAR row becomes **FAIL** and `aadhaarVerified = false`.
- **Enforced:** `ApplicationVerificationService.verifyPan/digilockerComplete/aadhaarMismatch/rejectForIdentityMismatch` → `ApplicationFlowService.autoRejectSystem` (no role gate, so callbacks and staff retries work).
- **Violation:** REJECTED, `FRAUD_REJECT_BLOCK_DAYS = 365`.
- **Example:** Typed …4321, PAN record `XXXXXXXX1234` → rejected.

### BR-ID-05 — DOB fraud rule
- **Rule:** At DigiLocker completion, the DOB entered at intake must equal the Aadhaar DOB. An Aadhaar DOB of 1 January compares on the year only.
- **Condition:**
  - Mismatch iff both are present and `entered ≠ aadhaar` and not (`aadhaar.dayOfYear == 1 && same year`).
  - A mismatch keeps the entered DOB as evidence and joins the Aadhaar mismatch → FRAUD_REJECTED.
  - With no mismatch, the Aadhaar DOB overwrites the profile DOB.
- **Enforced:** `ApplicationVerificationService.dobMismatch/digilockerComplete`.
- **Violation:** As BR-ID-04.
- **Example:** Entered 1990-05-14, Aadhaar 1990-01-01 → OK. Aadhaar 1991-01-01 → mismatch.

### BR-ID-06 — Duplicate Aadhaar at write is a silent flag
- **Rule:** Saving an Aadhaar number that another customer holds is accepted, but writes a staff-only `AADHAAR_DUPLICATE` REVIEW row. Correcting it to a non-colliding number deletes the row.
- **Condition:**
  - `findOtherCustomerIdsByAadhaar(aadhaar, customerId)` non-empty → upsert the REVIEW row (`derived.otherCustomerIds`); empty → delete it.
  - The borrower's response is identical either way.
- **Enforced:** `CustomerReviewService.saveProfile/flagAadhaarDuplicate`.
- **Violation:** n/a here. It blocks sanction (BR-LC-09).
- **Example:** Staff see "Aadhaar number is also on file for customer #N"; the borrower sees nothing.

### BR-ID-07 — Duplicate Aadhaar at submit-kyc is a permanent reject
- **Rule:** At submission, a full Aadhaar already on **another customer's submitted** application rejects this DRAFT permanently.
- **Condition:**
  - `status == DRAFT`; aadhaar non-blank; `findSubmittedApplicationIdsByAadhaarForOtherCustomers` non-empty.
  - → `autoRejectSystem(AADHAAR_DUPLICATE, INDEFINITE, "AUTO_REJECT_IDENTITY", "Identity check failed")`.
- **Enforced:** `ApplicationVerificationService.rejectIfAadhaarDuplicate`.
- **Violation:** REJECTED. The borrower sees only "rejected".
- **Example:** Masked digits are never used here: four digits are too few to call two people the same.

### BR-ID-08 — PAN and mobile belong to one customer
- **Rule:** A PAN or mobile already used by a different customer is refused. A borrower's profile mobile must be their OTP-proven login mobile.
- **Condition:**
  - `existsPanForOtherCustomer` → refuse.
  - `existsMobileForOtherCustomer` → refuse.
  - BORROWER: `borrowerIdentity.isLoginMobile(mobile, customerId)`.
  - Uniqueness is customer-scoped (V23), so the same customer re-onboarding is allowed.
- **Enforced:** `CustomerReviewService.saveProfile`.
- **Violation:** `DUPLICATE_PAN`, `DUPLICATE_MOBILE`, `MOBILE_MISMATCH`.
- **Example:** A sibling using a brother's PAN → `DUPLICATE_PAN`.

### BR-ID-09 — Name, PAN and DOB freeze after DRAFT
- **Rule:** A borrower may correct name, PAN and DOB only while DRAFT.
- **Condition:** BORROWER, `status ≠ DRAFT`, and a stored value would change → refuse.
- **Enforced:** `CustomerReviewService.saveProfile`. `editOwnProfile` never accepts identity fields at all.
- **Violation:** `PROFILE_LOCKED`.
- **Example:** Staff/ADMIN corrections (`CustomerService.updateProfile`) are a separate path.

### BR-ID-10 — Storable DOB
- **Rule:** A DOB must be in the past and less than 100 years ago. There is no age rule at intake.
- **Condition:** `dob < todayIST && dob ≥ todayIST − 100y`.
- **Enforced:** `CustomerReviewService.requireStorableDob`. The ADMIN path additionally enforces 18+ (`CustomerService.requirePlausibleDob`).
- **Violation:** `INVALID_DOB`.
- **Example:** 2030-01-01 → refused.

### BR-ID-11 — Name-match algorithm and threshold
- **Rule:** Names are compared token-wise, accepting initials; a score below 0.60 means REVIEW, never a hard fail.
- **Condition:**
  - Tokens: lowercase, split on `[^a-z0-9]+`.
  - If the smaller set has fewer than 2 tokens: the Jaccard index `|A∩B| / |A∪B|`.
  - Otherwise: exact token matches first, then initial matches in either direction (`"r" ≡ "rahul"`); score = matched / size of the smaller set.
  - The profile score is the **minimum** pairwise score across the PAN, Aadhaar and bank names (rounded to 2 dp).
  - `NAME_MATCH_THRESHOLD = 0.60`.
- **Enforced:** `ApplicationVerificationService.nameSimilarity/recomputeNameMatch`.
- **Violation:** The AADHAAR row → REVIEW ("Name mismatch vs PAN"). Penny-drop below 0.60 → not verified (BR-VER-12).
- **Example:** "SAIPRASATH E" vs "ELANGO SAIPRASATH" → 2/2 = 1.0.

### BR-ID-12 — PAN format
- **Rule:** At intake the PAN is only trimmed and uppercased; the code does not check its shape. Only the waitlist form validates the PAN, and the 10-character pattern `[A-Za-z]{5}[0-9]{4}[A-Za-z]` is used only for the bureau identity cross-check.
- **Condition:** `normalizePan = trim().toUpperCase()`. The `PAN_SHAPE` regex is used in `bureauIdentityMismatch`; `INVALID_PAN` is raised only by `OnboardingWaitlistService`.
- **Enforced:** `CustomerReviewService.normalizePan`; `ApplicationVerificationService` (`PAN_SHAPE`).
- **Violation:** An invalid PAN surfaces as a provider `PAN_NOT_FOUND` → FAIL (BR-VER-05).
- **Example:** The frontend form validates the shape. The server does not.

### BR-ID-13 — UAN shape
- **Rule:** A UAN supplied on the self-edit profile must be exactly 12 digits.
- **Condition:** `uan.matches("\\d{12}")`.
- **Enforced:** `CustomerReviewService.editOwnProfile`.
- **Violation:** `INVALID_UAN`.
- **Example:** Not verified itself; it only sharpens the EPFO lookup.

### BR-ID-14 — Mobile normalisation and customer id
- **Rule:** A mobile is its last 10 digits. A borrower's `customerId` is the last 7 digits of the mobile, claimed once, and a second mobile deriving the same id is refused.
- **Condition:**
  - `normalizeMobile`: digits only, keep the last 10, require exactly 10.
  - `deriveCustomerId = parseLong(last 7 digits)`.
  - A `borrower_mobile` row with the same id but a different number → refuse.
- **Enforced:** `CustomerReviewService.normalizeMobile`; `AuthController.deriveCustomerId/checkCustomerIdClaim/claimCustomerId`.
- **Violation:** `INVALID_MOBILE`, `CUSTOMER_ID_COLLISION`.
- **Example:** 98765**43210** and 91234**43210** both derive id 6543210; the second is refused.

### BR-ID-15 — Bureau report must belong to the borrower
- **Rule:** If the bureau report's PAN or DOB disagrees with the verified profile, the BUREAU row is REVIEW and the score-floor auto-reject is skipped.
- **Condition:**
  - PAN: exact, case-insensitive. DOB: exact date.
  - A blank on either side is "cannot compare", not a mismatch.
- **Enforced:** `ApplicationVerificationService.bureauIdentityMismatch/finishBureauPull`.
- **Violation:** REVIEW with `derived.identityMismatch`.
- **Example:** It also runs on a 24 h reused pull against this application's own profile.

---

## 6. Verification (BR-VER)

Check types (`application_verification.check_type`): PAN, EMAIL, ADDRESS, DIGILOCKER, AADHAAR, BUREAU, SALARY, EMPLOYMENT, PENNY_DROP, SELFIE, AGREEMENT, ESIGN, AADHAAR_DUPLICATE, PAN_AADHAAR_LINK, plus EMANDATE (`MandateService.CHECK_TYPE`), EMAIL_OTP and OFFICIAL_EMAIL_OTP.

### BR-VER-01 — Status vocabulary and "attempted"
- **Rule:** A check row is PASS, FAIL, REVIEW or PENDING. A check counts as "attempted" once it holds PASS, REVIEW or FAIL. PENDING or no row means it never ran.
- **Condition:** `attempted(s) = s ∈ {PASS, REVIEW, FAIL}`.
- **Enforced:** `ApplicationVerificationService.attempted`.
- **Violation:** n/a.
- **Example:** A provider outage writes REVIEW, so it counts as attempted and never strands the borrower.

### BR-VER-02 — REQUIRED (intake) set
- **Rule:** PAN, EMAIL, BUREAU and SALARY must all be attempted before submit-kyc. The outcome does not matter: a FAIL is passed to credit flagged.
- **Condition:** `REQUIRED = [PAN, EMAIL, BUREAU, SALARY]`; all attempted and T&C accepted.
- **Enforced:** `ApplicationVerificationService.allRequiredPassed` (the name is historical; it checks attempts).
- **Violation:** `KYC_INCOMPLETE`.
- **Example:** Bureau no-hit (thin file) is PASS "Thin-file (no bureau record)".

### BR-VER-03 — REQUIRED_SANCTION set is non-blocking
- **Rule:** AADHAAR, SELFIE, ADDRESS and ESIGN are the post-sanction checks shown on the dashboards. Only ESIGN gates anything (BR-LC-14).
- **Condition:** `REQUIRED_SANCTION = [AADHAAR, SELFIE, ADDRESS, ESIGN]`. PENNY_DROP is deliberately absent (it fires only on an account change).
- **Enforced:** `ApplicationVerificationService` (progress / completeness views).
- **Violation:** n/a.
- **Example:** A failed selfie liveness never blocks disbursal.

### BR-VER-04 — No verification hard-blocks a borrower
- **Rule:** A provider failure records REVIEW (with `providerError`) and lets the borrower continue. A human decides. The fraud rules (BR-ID-04/05/07) are the only exceptions.
- **Condition:** catch `RuntimeException` → `providerUnavailable(…)` → REVIEW. This applies to PAN, email, penny drop, DigiLocker init, selfie and the bureau.
- **Enforced:** `ApplicationVerificationService.providerUnavailable` and each `verify*`.
- **Violation:** n/a.
- **Example:** Email provider down → REVIEW "pending manual review"; the borrower moves on.

### BR-VER-05 — PAN not found is a FAIL, not an outage
- **Rule:** A provider answer of `PAN_NOT_FOUND` records FAIL (non-blocking) so the borrower can fix a typo. It is not tagged as a provider error.
- **Condition:** `providerErrorCode == PAN_NOT_FOUND` → FAIL, `derived.panNotFound = true`, `panVerified = false`.
- **Enforced:** `ApplicationVerificationService.verifyPan`.
- **Violation:** FAIL row.
- **Example:** The message reads "PAN not found — please check the number and try again".

### BR-VER-06 — PAN fills name and DOB only when empty
- **Rule:** The PAN record supplies the borrower's name and DOB but never overwrites an existing value. DigiLocker's DOB later wins (BR-ID-05).
- **Condition:** `if profile.dob == null → panDob`; `if fullName blank → panName`.
- **Enforced:** `ApplicationVerificationService.verifyPan`.
- **Violation:** n/a.
- **Example:** Intake never asks for a name (decision 12).

### BR-VER-07 — Manual override of any check
- **Rule:** The credit team (CE on own file, CH, ADMIN) may set any known check to PASS or FAIL with a note. The evidence is kept for PENNY_DROP, EMPLOYMENT, BUREAU and AADHAAR_DUPLICATE.
- **Condition:**
  - `type ∈ KNOWN_CHECKS`; `requireCreditOwnership`.
  - Provider becomes MANUAL; message "Manually approved/rejected by <name> — note".
  - Audited as `VERIFICATION_OVERRIDE`.
  - A PENNY_DROP override also clears the 12 h lock (`PennyDropGuard.clearLock`).
- **Enforced:** `ApplicationVerificationService.manualDecision/requireCreditTeam`.
- **Violation:** `FORBIDDEN_ROLE`, `UNKNOWN_CHECK`, `CREDIT_FILE_NOT_ASSIGNED`.
- **Example:** Clearing AADHAAR_DUPLICATE to PASS is what unblocks sanction.

### BR-VER-08 — Invalidation on a profile edit (after DRAFT)
- **Rule:** Editing a verification-linked field after DRAFT resets the mapped check(s) to PENDING, clears the matching profile flag and logs `REVERIFY`.
- **Condition:**
  - Field → check map:
    - `address` → ADDRESS.
    - `salaryBank`, `salaryAccountNumber`, `salaryIfsc` → PENNY_DROP.
    - `monthlySalaryPaise` → SALARY.
    - `employer`, `employmentStatus` → EMAIL + EMPLOYMENT.
    - `email` → EMAIL.
    - `officialEmail` → EMAIL + OFFICIAL_EMAIL_OTP.
  - Flags cleared: `addressVerified`, `pennyDropVerified`, `emailVerified`.
  - Changing the personal email also nulls `personalEmailVerified`.
- **Enforced:** `VerificationInvalidationService.invalidateForFields` (`FIELD_TO_CHECK`). It is called from `CustomerReviewService.editOwnProfile` (always) and `saveProfile` (only when `status ≠ DRAFT`).
- **Violation:** n/a.
- **Example:** The borrower changes employer → the EPFO finding is reset to PENDING.

### BR-VER-09 — ADMIN re-run of a provider check
- **Rule:** Only ADMIN may re-run a provider-backed check from the staff console. Checks that need a borrower session cannot be retried there.
- **Condition:**
  - Retryable: PAN, PAN_AADHAAR_LINK, EMAIL, ADDRESS, BUREAU (forced, bypassing 24 h reuse), EMPLOYMENT, PENNY_DROP, SELFIE.
  - The row is set to PENDING "Retry requested by administrator", then re-run.
- **Enforced:** `ApplicationVerificationService.retryExternalCheck`.
- **Violation:** `FORBIDDEN_ROLE`, `RETRY_NOT_SUPPORTED`, `RETRY_INPUT_REQUIRED`, `RETRY_INPUT_INVALID`.
- **Example:** A DigiLocker retry is refused: it needs the borrower's consent.

### BR-VER-10 — Resume link and reopen
- **Rule:** The credit team (CE, CH or ADMIN) may send a borrower a link back to one step. Telecallers may send only the KYC reminder (`sendKycReminder`). The first send **reopens** the step (PENDING, `reopenedAt`) and rewinds the journey; later sends only re-share it.
- **Condition:**
  - Offer steps (ESIGN, AADHAAR/DIGILOCKER, SELFIE, ADDRESS) require SANCTIONED. An ESIGN already PASS is refused.
  - BUREAU requires KYC_PENDING and an open KBA challenge, and is emailed once per application.
  - `willReopen = row exists && reopenedAt == null`.
  - The channel is EMAIL (publishes `VerificationStepLinkEvent`) or COPY (audit only).
  - Both write `STEP_LINK_SENT`, plus `STEP_REOPENED` on a reopen.
  - URL = `<frontend>/login?next=<route>`.
- **Enforced:** `VerificationOutreachService.preview/share/eligibility/reopen`; `CreditTeamGuard.requireCreditTeamOr`.
- **Violation:** `STEP_LINK_NOT_ELIGIBLE`, `STEP_LINK_UNSUPPORTED`, `STEP_ALREADY_DONE`, `BUREAU_CHALLENGE_ALREADY_NOTIFIED`.
- **Example:** Reopening AADHAAR also reopens the DIGILOCKER card.

### BR-VER-11 — DigiLocker session cap
- **Rule:** One application may start at most 5 DigiLocker consent sessions per 24-hour window. After that the step is REVIEW and the borrower uploads the Aadhaar card instead.
- **Condition:**
  - The window is fixed: it starts at its first session (`digilockerSessionWindowStart`) and resets once 24 h have passed.
  - `used ≥ 5` → REVIEW, `skippable = true`, `sessionsExhausted = true`, no provider call.
- **Enforced:** `ApplicationVerificationService.digilockerInit/sessionCounters` (`MAX_DIGILOCKER_SESSIONS_PER_DAY = 5`).
- **Violation:** REVIEW row ("please upload your Aadhaar card instead").
- **Example:** The 6th attempt within 24 h of the first → manual upload fallback.

### BR-VER-12 — Penny drop pass rule
- **Rule:** A penny drop passes only if the account exists **and** the bank's name matches the profile name at 0.60 or above.
- **Condition:**
  - `ok = r.accountExists && nameSimilarity(profile.fullName, r.fullName) ≥ 0.60`.
  - A provider failure → REVIEW ("we'll verify it before your advance is sent").
  - Fills `salaryBank` from the provider, else from the first 4 characters of the IFSC.
- **Enforced:** `ApplicationVerificationService.verifyPennyDrop`.
- **Violation:** Not PASS → `ACCOUNT_INVALID` on the confirm screen (BR-VER-14).
- **Example:** "R KUMAR" vs "RAHUL KUMAR" → 1.0 → pass.

### BR-VER-13 — Penny-drop abuse lock
- **Rule:** Three failed penny drops for a customer (counted since the last success or lock) lock further attempts for 12 hours. A success clears the count.
- **Condition:**
  - `STRIKES = 3`, `LOCK = 12 h`.
  - Attempts are written `REQUIRES_NEW`, so a rejected attempt still counts.
  - `remaining = max(0, 3 − failuresSinceReset)`.
- **Enforced:** `PennyDropGuard.lockedUntil/record/remainingAttempts/clearLock`; `OfferService.confirmDisbursalAccount`; `ApplicationVerificationController`.
- **Violation:** `PENNY_DROP_LOCKED` ("try again after 12 hours").
- **Example:** A staff PENNY_DROP override clears the lock.

### BR-VER-14 — Confirming the disbursal account
- **Rule:** The borrower confirms the salary account or enters a new one. A new or unverified account must pass a penny drop, or the borrower uploads a cancelled cheque or passbook instead.
- **Condition:**
  - Amount already chosen; `digits(account).length ≥ 6`; `IFSC.length == 11`.
  - `changed` = differs from both the profile salary account and the stored disbursal account. A blank reference counts as changed.
  - Bank-proof path: a BANK_PROOF doc must exist → `verified = false`, PENNY_DROP PENDING.
  - Otherwise skip the penny drop if this exact account is already verified; else lock check → penny drop → record strike.
  - Then the mandate step or `acceptOffer` (BR-LC-14).
- **Enforced:** `OfferService.confirmDisbursalAccount/afterAccountConfirmed/matches`.
- **Violation:** `AMOUNT_NOT_CHOSEN`, `ACCOUNT_INVALID`, `BANK_PROOF_REQUIRED`, `PENNY_DROP_LOCKED`.
- **Example:** A returning borrower re-confirming the carried account → no new penny drop.

### BR-VER-15 — Bureau 24-hour reuse
- **Rule:** A PASSed bureau pull on another application of the **same customer** within 24 hours is copied instead of calling the provider again. The identity cross-check still runs on this profile.
- **Condition:**
  - Sibling application, BUREAU == PASS, inside `BUREAU_REUSE_WINDOW_HOURS = 24`, and not `force`.
  - Copy the row (+ `reusedFromApplicationId`), the score, the risk grade and the credit brief (doc row pointer).
- **Enforced:** `ApplicationVerificationService.reusableBureauPass/reuseBureauPass/carryCreditBrief`.
- **Violation:** n/a.
- **Example:** Cancel and restart within a day → no second billable CRIF pull.

### BR-VER-16 — Bureau KBA challenge
- **Rule:** A CRIF security question allows 3 answer attempts. The answer must match a stored option exactly. A fresh question can be minted at most once per 60 seconds. The borrower may skip it.
- **Condition:**
  - Answer: an open challenge with both `orderId` and `reportId`, else `BUREAU_CHALLENGE_STALE`.
  - `answer ∈ storedOptions` verbatim (no trim); `attempts < 3`.
  - A wrong answer → attempts+1, REVIEW.
  - `KBA_EXHAUSTED` from CRIF → `bureauChallengeExhausted` (terminal for this question).
  - Attempt counters survive a re-mint.
  - Re-mint: no-op if the row is PASS or was updated less than 60 s ago; else a forced pull.
  - Skip: sets `bureauChallengeSkipped`; REVIEW (counts as attempted).
  - The answer text is never stored.
  - Answering bypasses the routing chain to the issuing vendor.
- **Enforced:** `ApplicationVerificationService.answerBureauChallenge/refreshBureauChallenge/skipBureauChallenge/carriedChallengeCounters` (`MAX_CHALLENGE_ATTEMPTS = 3`, `CHALLENGE_MINT_COOLDOWN = 60 s`).
- **Violation:** `BUREAU_CHALLENGE_NONE`, `BUREAU_CHALLENGE_STALE`, `BUREAU_CHALLENGE_ANSWER_INVALID`; otherwise REVIEW messages.
- **Example:** The 4th answer attempt → "please get a new question", with no provider call.

### BR-VER-17 — Employment auto-retry with backoff
- **Rule:** An EMPLOYMENT check parked by a vendor outage is retried hourly with exponential spacing, at most 5 times, only while the file is undecided.
- **Condition:**
  - Status REVIEW, `providerError = true`.
  - `providerErrorCode ∈ {HTTP_402, HTTP_500, HTTP_502, HTTP_503, HTTP_505, TRANSPORT_FAILURE, EMPTY_RESPONSE, UNPARSEABLE_RESPONSE}`.
  - `retryCount < 5`; `updated_at < now − 2^retryCount h` (1, 2, 4, 8, 16 h).
  - Application status ∈ {DRAFT, KYC_PENDING, REVIEW_PENDING}.
  - Batch 50, oldest first.
- **Enforced:** `EmploymentRetryScheduler.retryParkedEmploymentChecks`; `ApplicationVerificationRepository.findEmploymentRetryCandidates`; `ApplicationVerificationService.markEmploymentRetried` (`MAX_EMPLOYMENT_RETRIES = 5`).
- **Violation:** After 5 tries the check is left to a human.
- **Example:** HTTP_400 / HTTP_412 are never retried: they fail the same way every time.

### BR-VER-18 — What a re-apply inherits vs carries
- **Rule:** Completeness views treat PAN, EMAIL, BUREAU, SALARY, AADHAAR, SELFIE and ADDRESS as inherited from the source application (5 hops at most). Only AADHAAR, SELFIE, ADDRESS and EMPLOYMENT rows are physically copied. ESIGN is never inherited.
- **Condition:** `INHERITABLE_CHECKS` (`REAPPLY_CHAIN_HOPS = 5`) vs `ApplicationFlowService.CARRIED_CHECKS`.
- **Enforced:** `ApplicationVerificationService` (progress); `ApplicationFlowService.copyCarriedVerifications`.
- **Violation:** n/a.
- **Example:** A reborrow is "complete" on identity, but not on signature, until the borrower signs again.

### BR-VER-19 — eSign gating and locks
- **Rule:** Only a PASS ESIGN counts as signed. Once a signing session exists (PENDING), the drawdown amount is locked. Either path completes the check: Aadhaar eSign (`EsignPort`, billable) or the drawn-signature fallback (no provider call).
- **Condition:**
  - `isSigned = ESIGN == PASS`.
  - `requireNotSigned` refuses an amount change if ESIGN ∈ {PENDING, PASS}.
  - The manual signature must sit in a valid location and be a valid PNG; it is refused on an already-completed agreement.
- **Enforced:** `OfferService.esignInit/esignStatus/manualEsign/requireNotSigned`; `ApplicationVerificationService` (`SIGNATURE_*`, `ESIGN_*`).
- **Violation:** `TERMS_LOCKED`, `ESIGN_NOT_STARTED`, `ESIGN_ALREADY_COMPLETED`, `SIGNATURE_INVALID`, `SIGNATURE_LOCATION_INVALID`, `SANCTION_LETTER_MISSING`.
- **Example:** The KFS PDF is re-rendered on every visit, so the borrower never signs a stale amount.

### BR-VER-20 — Uploads belong to the application
- **Rule:** Every uploaded object key a borrower submits must sit under this application's folder. Documents are at most 5 MB.
- **Condition:**
  - The key prefix matches the application and contains no reserved folders or `..`.
  - The file password is at most 128 characters.
  - Inline documents are at most 5 MB and must be valid base64.
- **Enforced:** `ApplicationVerificationService.requireOwnApplicationKeys`; `CustomerReviewService` (`MAX_DOC_BYTES`); `RepaymentService.isProofKey` (proof keys must start `loan/repayment-proof/`).
- **Violation:** `INVALID_DOCUMENT_KEY`, `UNSUPPORTED_DOC_TYPE`, `DOCUMENT_TOO_LARGE`, `BAD_DOCUMENT`, `EMPTY_DOCUMENT`, `INVALID_SELFIE_KEY`.
- **Example:** A borrower posting another application's S3 key is refused.

---

## 7. Credit rating & BRE (BR-CR)

### BR-CR-01 — Unscored report is unrated
- **Rule:** A bureau report with no usable score gets 0 stars and the verdict "SCORE UNAVAILABLE". It is never treated as a zero score.
- **Condition:** `f.creditScore() == null` → `Rating(0.0, "SCORE UNAVAILABLE", summarizeUnscored)`.
- **Enforced:** `CreditRatingCalculator.rate`.
- **Violation:** n/a.
- **Example:** CRIF returns a score outside 300–900 → unrated; underwrite from the tradelines.

### BR-CR-02 — Base stars from the score
- **Rule:** The score maps to a base star band.
- **Condition:** ≥780 → 5.0 · ≥750 → 4.5 · ≥720 → 4.0 · ≥690 → 3.5 · ≥660 → 3.0 · >630 → 2.5 · >600 → 2.0 · else 1.0.
- **Enforced:** `CreditRatingCalculator.baseStars`.
- **Violation:** n/a.
- **Example:** 745 → 4.0★ base.

### BR-CR-03 — Deductions
- **Rule:** Recent delinquency, current arrears, credit-seeking and the lack of a recent personal loan each subtract stars.
- **Condition:**
  - −1.0 if `worstDpd12m > 10`.
  - −0.5 if `accountsCurrentlyPastDue > 0`.
  - −0.5 if enquiries in the last 30 days `> 3`.
  - −0.5 if `personalLoanInWindow == false` (window = 9 months to `asOf`; `null` = no tradelines = no deduction).
- **Enforced:** `CreditRatingCalculator.computeStars/signals/personalLoanInWindow`.
- **Violation:** n/a.
- **Example:** 745 (4.0), 4 enquiries, no PL in 9 months → 3.0★.

### BR-CR-04 — Caps (applied last)
- **Rule:** Defaults, write-offs and a sub-policy score cap the rating, whatever the deductions.
- **Condition:**
  - `defaults > 0` → cap 2.5.
  - `writtenOffOrSettled > 0` → cap 2.0.
  - `score ≤ 600` → cap 1.0 ("fails BRE rule 1").
  - Then clamp to [1.0, 5.0] and round to the nearest 0.5.
- **Enforced:** `CreditRatingCalculator.computeStars`.
- **Violation:** n/a.
- **Example:** 790 with one default → 2.5★.

### BR-CR-05 — Verdict
- **Rule:** The star rating maps to a recommendation shown to staff only.
- **Condition:** ≥4.5 → STRONGLY RECOMMEND · ≥3.5 → RECOMMEND · ≥2.5 → REFER — MANUAL REVIEW · else NOT RECOMMENDED.
- **Enforced:** `CreditRatingCalculator.verdict`.
- **Violation:** n/a. Advisory; it never auto-decides.
- **Example:** 3.0★ → "REFER — MANUAL REVIEW".

### BR-CR-06 — Personal-loan detection
- **Rule:** A tradeline is a personal loan if its Experian code is 5, 41, 45 or 69, or its text matches "personal loan" and does not name a secured product.
- **Condition:** Numeric type ∈ {5, 41, 45, 69}; or `\bpersonal\s+loan\b` and not `auto|two.?wheeler|vehicle|housing|home|gold|property|education|business`. It is in the window if opened on or before `asOf` and either open or closed on or after `asOf − 9 months`.
- **Enforced:** `CreditRatingCalculator.isPersonalLoan/personalLoanInWindow`.
- **Violation:** n/a.
- **Example:** "Gold loan – personal" → not a PL.

### BR-CR-07 — BRE: ten flag-only rules
- **Rule:** The Customer 360 BRE tab evaluates ten credit-policy rules in the browser. They **flag** only and never reject or block.
- **Condition:** Each check is PASS, FLAG, MANUAL or UNKNOWN. A rule rolls up as FLAG > UNKNOWN > MANUAL > PASS over non-informational checks; no deciding checks → UNKNOWN. A confirmed no-hit report → FLAG; not yet pulled → UNKNOWN.
- **Enforced:** `frontend/src/lib/customers/bre/rules.ts` (`rollup`, `rule`).
- **Violation:** n/a.
- **Example:** The rules:

| # | Rule | Threshold |
|---|---|---|
| 1 | Credit score above 600 (+ the bureau report belongs to the borrower) | `score > 600` (`MIN_CREDIT_SCORE`) |
| 2 | PAN linked to the typed Aadhaar | Signzy link status |
| 3 | Personal loan, active or closed, in the last 9 months | `LOOKBACK_MONTHS = 9` |
| 4 | Loans relied on are individually owned, not as guarantor | ownership |
| 5 | NBFC loan in the last 9 months, none more than 10 days past due | `MAX_NBFC_DPD_DAYS = 10` |
| 6 | Not self-employed, army, lawyer/advocate or MHA | profession patterns (CRIF + KYC) |
| 7 | Aadhaar–PAN linkage, and the same name on PAN, Aadhaar and card photos | name match ≥ 0.6 |
| 8 | UAN verified on EPFO and against the slips; company name matches | employer-name agreement |
| 9 | Aadhaar: typed vs DigiLocker last 4 vs uploaded photo | `matchesMasked` |
| 10 | Salary: bank analyser vs slips vs declared | within 10% (`SALARY_MATCH_TOLERANCE`) |

### BR-CR-08 — BRE staging pass
- **Rule:** In a staging build, rules 1, 4, 6, 7 and 9 report PASS. Rules 3, 5, 8 and 10 always evaluate for real.
- **Condition:** `NEXT_PUBLIC_BRE_STAGING_PASS=true` → `STAGING_PASS_RULES = [1,4,6,7,9]`.
- **Enforced:** `rules.ts` (`stagingPassed`).
- **Violation:** n/a. Never set this in production.
- **Example:** Demo environments only.

### BR-CR-09 — Three score thresholds, three meanings
- **Rule:** 550, 600 and 510 are different numbers and must not be unified.
- **Condition:**
  - 550 = the suspended auto-reject floor (`ApplicationFlowService.MIN_BUREAU_SCORE`).
  - 600 = BRE rule 1 and the 1★ cap (`CreditRatingCalculator.BRE_MIN_SCORE`, `rules.ts MIN_CREDIT_SCORE`).
  - 510 = the CRIF model floor (a real score, not a sentinel).
- **Enforced:** as listed.
- **Violation:** n/a.
- **Example:** Score 580 → not auto-rejected (even with the flag on), but 1★ and BRE rule 1 FLAG.

---

## 8. Collections & settlements (BR-COL)

### BR-COL-01 — DPD
- **Rule:** DPD is the number of whole days after the due date, 0 on or before it. It is computed on read and never stored.
- **Condition:** `dpd = asOf > dueDate ? days(dueDate, asOf) : 0`.
- **Enforced:** `DpdCalculator.daysPastDue` (and the inline copy in `DebitService.prepare`).
- **Violation:** n/a.
- **Example:** Due 06-30, as of 07-05 → DPD 5.

### BR-COL-02 — Buckets
- **Rule:** DPD maps to six worklist buckets.
- **Condition:** ≤0 UPCOMING · 1–7 T0_T7 · 8–30 T8_T30 · 31–60 T30_T60 · 61–90 T60_T90 · >90 T90_PLUS.
- **Enforced:** `DpdCalculator.bucket`; `DpdBucket`.
- **Violation:** n/a.
- **Example:** DPD 30 → T8_T30; DPD 31 → T30_T60.

### BR-COL-03 — Upcoming watchlist is read-only
- **Rule:** Loans not yet due appear in UPCOMING from a read-only watchlist. Viewing them never opens a case or changes a status.
- **Condition:** Live loans whose due date has not yet passed, soonest first, excluding loans that already have a case.
- **Enforced:** `CollectionsService.upcomingWatchlist`.
- **Violation:** n/a.
- **Example:** A loan auto-assigned at disbursal appears as a case row instead (BR-COL-05).

### BR-COL-04 — Opening a case
- **Rule:** Only the collections desk may open a case. A new case on an unheld loan is the Head's call and is refused for a settled loan. Opening a new case notifies the desk.
- **Condition:**
  - Desk roles: COLLECTION_HEAD, COLLECTION_EXECUTIVE, ADMIN.
  - An existing case → ownership check, idempotent.
  - New case → `requireManager` and loan status ∉ {CLOSED, REPAID, WRITTEN_OFF}.
  - Then `markInCollections` (it flips only when past due), and `CollectionCaseOpenedEvent` for new cases only.
- **Enforced:** `CollectionsService.openCase`.
- **Violation:** `FORBIDDEN_ROLE`, `LOAN_NOT_COLLECTIBLE`, `CASE_NOT_ASSIGNED`.
- **Example:** A pre-emptive case 3 days before due does not mark the borrower delinquent.

### BR-COL-05 — Auto-assignment at disbursal
- **Rule:** Every newly ACTIVE loan gets a case assigned to one configured collections executive, provided that officer is an ACTIVE COLLECTION_EXECUTIVE. Otherwise nothing happens and the Head assigns by hand.
- **Condition:**
  - On `ApplicationTransitionedEvent` with `toStatus == ACTIVE` and `loanId != null`, after commit.
  - `navix.collections.auto-assign-officer-id` (SSM) is set.
  - No case exists yet; `isActiveWithRole(officer, COLLECTION_EXECUTIVE)`.
  - Writes only the case row: no IN_COLLECTIONS flip, no notification.
  - It is a single officer, not a round-robin.
- **Enforced:** `CollectionAutoAssigner.onTransition`.
- **Violation:** WARN/ERROR log; the disbursal is unaffected.
- **Example:** Officer disabled → loans land "Unallocated".

### BR-COL-06 — Assigning a case
- **Rule:** Only the Collection Head (or ADMIN) assigns. The assignee must be an ACTIVE Collection Executive; the Head may also self-assign.
- **Condition:** The actor's role ∈ {COLLECTION_HEAD, ADMIN}; `selfAssign` or `isActiveWithRole(officer, COLLECTION_EXECUTIVE)`.
- **Enforced:** `CollectionsService.assignOfficer`.
- **Violation:** `FORBIDDEN_ROLE`, `INVALID_OFFICER`.
- **Example:** Assigning to a telecaller → `INVALID_OFFICER`.

### BR-COL-07 — Executives work only their own cases
- **Rule:** Anyone whose **effective** (working) role is COLLECTION_EXECUTIVE may touch only cases assigned to them. This applies to a real executive and to an ADMIN or Head working as one.
- **Condition:** `effectiveRole == COLLECTION_EXECUTIVE` → `actorId == case.assignedOfficerId`.
- **Enforced:** `CollectionsService.requireCaseOwnership/assertCaseAccessible/scopedCaseIdsOrNull`. It is shared by settlements, collection payments and manual debit.
- **Violation:** `CASE_NOT_ASSIGNED`.
- **Example:** An executive proposing a settlement on a colleague's case is refused.

### BR-COL-08 — A PAID interaction needs proof
- **Rule:** Logging an interaction with outcome PAID requires a proof reference.
- **Condition:** `outcome.equalsIgnoreCase("PAID") && proofRef blank` → refuse.
- **Enforced:** `CollectionsService.logInteraction`.
- **Violation:** `PROOF_REQUIRED`.
- **Example:** A promise-to-pay date may be recorded without proof.

### BR-COL-09 — Proposing a settlement
- **Rule:** A Collection Executive (own case) or the Head proposes a positive full-and-final amount. It opens PROPOSED and notifies the Heads.
- **Condition:**
  - Roles {COLLECTION_EXECUTIVE, COLLECTION_HEAD} (ADMIN passes); `settlementAmountPaise > 0` (`@Positive`).
  - No upper bound against the balance (BR-MNY-17 makes a high figure harmless).
- **Enforced:** `SettlementService.propose`; `CollectionsDtos.ProposeSettlementRequest`.
- **Violation:** `FORBIDDEN_ROLE`, `CASE_NOT_ASSIGNED`, 400 on a non-positive amount.
- **Example:** Proposing ₹7,000 against ₹13,700 owed.

### BR-COL-10 — Settlement approve / reject (SoD)
- **Rule:** Only the Collection Head (or ADMIN) decides a PROPOSED settlement, and never the person who proposed it. Approval recomputes the balance at once and may close the loan.
- **Condition:**
  - Status PROPOSED; `approver ≠ proposedBy`.
  - Approve → APPROVED + `loanDirectory.recomputeOutstanding` + `SettlementApprovedEvent` (the DSA commission is voided).
  - Reject → REJECTED (terminal; never caps).
- **Enforced:** `SettlementService.approve/reject/requirePending`.
- **Violation:** `FORBIDDEN_ROLE`, `SOD_VIOLATION`, `SETTLEMENT_NOT_PENDING`.
- **Example:** A Head who proposed on their own case needs another Head or ADMIN to approve.

### BR-COL-11 — Operative settlement
- **Rule:** When a loan has several approved settlements, the most recently approved one is the operative cap.
- **Condition:** `max(approvedAt)` over APPROVED settlements on the loan's case. In the batch path the newest case per loan wins.
- **Enforced:** `SettlementDirectoryAdapter.approvedSettlementAmount/approvedSettlementAmounts`.
- **Violation:** n/a.
- **Example:** Approved ₹8,000 then ₹7,000 → the cap is ₹7,000.

### BR-COL-12 — Collection payment kinds and routing
- **Rule:** A collections officer records PART_PAYMENT, FULL_PAYMENT or SETTLEMENT. Every kind ends at the Accountant; a SETTLEMENT stops at the Head first unless its settlement is already approved.
- **Condition:**
  - Roles {COLLECTION_EXECUTIVE, COLLECTION_HEAD}; `amount > 0`; case accessible.
  - SETTLEMENT must name a non-rejected settlement on the case (or the single live one) → PENDING_HEAD if PROPOSED, PENDING_ACCOUNTANT if APPROVED.
  - Other kinds → PENDING_ACCOUNTANT.
  - `paidOn` defaults to today (IST).
- **Enforced:** `CollectionPaymentService.raise/requireProposedOrApprovedSettlement`; `CollectionPaymentKind.needsHeadApproval`.
- **Violation:** `INVALID_AMOUNT`, `SETTLEMENT_REQUIRED`, `SETTLEMENT_NOT_FOUND`, `CASE_NOT_ASSIGNED`.
- **Example:** Two open settlements and no id → `SETTLEMENT_REQUIRED`.

### BR-COL-13 — Head approval of a settlement payment
- **Rule:** The Head clears a PENDING_HEAD payment in one click, which also approves its still-PROPOSED settlement. The Head must not be the officer who recorded the payment.
- **Condition:** Status PENDING_HEAD; `approver ≠ raisedBy`; a PROPOSED settlement → `SettlementService.approve`, which also enforces approver ≠ proposer. → PENDING_ACCOUNTANT.
- **Enforced:** `CollectionPaymentService.headApprove`.
- **Violation:** `PAYMENT_NOT_PENDING_HEAD`, `SOD_VIOLATION`.
- **Example:** An officer's ₹7,000 settlement payment → the Head approves → the Accountant validates.

### BR-COL-14 — Accountant validation is the only ledger write
- **Rule:** The Accountant validates against the bank (the payment is credited as VERIFIED at once) or rejects with remarks. A retry can never credit twice.
- **Condition:**
  - Status PENDING_ACCOUNTANT; reject requires remarks.
  - Accept: if `ledgerPaymentId == null` → `creditCollectionPayment`, which records and verifies in one step and recomputes/closes → VALIDATED.
- **Enforced:** `CollectionPaymentService.validate`; `LoanDirectoryAdapter.creditCollectionPayment`.
- **Violation:** `PAYMENT_NOT_PENDING_VALIDATION`, `REMARKS_REQUIRED`, `FORBIDDEN_ROLE` (non-Accountant).
- **Example:** ADMIN passes every role gate here, and `validate` has no raiser ≠ validator check, so one ADMIN can raise and validate (C-03).

---

## 9. Mandate & NACH (BR-MAN)

### BR-MAN-01 — The single mandate gate
- **Rule:** An eMandate is required only when `digio-mandate` is on **and** the application has no usable mandate. The journey step, the hand-off, `acceptOffer` and `apply` all read this one predicate.
- **Condition:** `mandateRequired = flag("digio-mandate", false) && !hasUsableMandate(app)`.
- **Enforced:** `MandateService.mandateRequired`; callers `OfferService.afterAccountConfirmed/mandateStatus`, `ApplicationFlowService.requireMandate`.
- **Violation:** `MANDATE_REQUIRED` (from `acceptOffer`/`apply`).
- **Example:** Flag off → the mandate screen never appears.

### BR-MAN-02 — Usable mandate
- **Rule:** A mandate is usable for an application if it is the customer's newest ACTIVE mandate with a UMRN, it debits the same account (last 4 digits + IFSC) and its maximum amount covers the application's formula ceiling.
- **Condition:**
  - Disbursal account matches `^\d{8,18}$` and the IFSC is 11 characters.
  - `mandate.accountLast4 == last4(account) && mandate.ifsc == ifsc && mandate.maxAmount ≥ ceiling(app)`.
  - The full account number is never stored (V82).
- **Enforced:** `MandateService.usableMandate/reusable/usableMandateForLoan`.
- **Violation:** n/a.
- **Example:** Two accounts at one branch sharing their last 4 digits would match. The penny drop proves the account itself.

### BR-MAN-03 — Mandate ceiling formula
- **Rule:** A mandate is sized at twice the larger of the formula limit and the sanctioned amount. An ADMIN override can only raise a **new** registration.
- **Condition:**
  - `formula = 2 × max(LoanMath.eligibleLimitPaise(salary), sanctioned)` (the override-blind limit); `≤ 0` → refuse.
  - `registration = max(formula, override)`.
  - Usability (BR-MAN-02) is judged against `formula`.
  - Override: ADMIN only, `₹1,000 ≤ override ≤ ₹10,00,000`, note at most 500 characters, logged as `mandateCeilingPaise`.
- **Enforced:** `MandateService.ceiling/registrationCeiling/setCeilingOverride/ceilingView`.
- **Violation:** `MANDATE_CEILING_UNAVAILABLE`, `MANDATE_CEILING_OUT_OF_RANGE`, `FORBIDDEN_ROLE`.
- **Example:** E24: ₹30,000.

### BR-MAN-04 — Mandate registration guards
- **Rule:** A borrower may file a mandate only with the flag on, on a confirmed or proven account, and within 10 attempts per application.
- **Condition:**
  - `digio-mandate` on.
  - `disbursalConfirmedAt != null || disbursalAccountVerified`.
  - `count(attempts) < 10`.
  - The application status ∈ {PRE_APPROVED, SANCTIONED, DISBURSEMENT_PENDING, DISBURSEMENT_FAILED, DISBURSED, ACTIVE, OVERDUE, DEFAULTED, WRITTEN_OFF, CLOSED}.
  - `init` re-reads up to 5 earlier attempts before filing.
- **Enforced:** `MandateService.init/requireMandateAllowed` (`MAX_ATTEMPTS_PER_APPLICATION = 10`, `SETTLE_LOOKBACK = 5`); `OfferService.mandateInit` (`MANDATE_SETUP_ALLOWED`).
- **Violation:** `MANDATE_NOT_ENABLED`, `MANDATE_ACCOUNT_UNCONFIRMED`, `MANDATE_ATTEMPTS_EXHAUSTED`, `NOT_APPLICABLE`, `INVALID_ACCOUNT_NUMBER`, `INVALID_IFSC`, `INVALID_CUSTOMER_REF`, `INVALID_NAME`.
- **Example:** The customer reference must match `^[0-9A-Za-z]{1,20}$`, with no hyphen (Digio).

### BR-MAN-05 — Mandate hand-off
- **Rule:** On a SANCTIONED file, a UMRN arriving (or the mandate no longer being required) after account confirmation moves the file to disbursement.
- **Condition:** `status == SANCTIONED && disbursalConfirmedAt != null && !mandateRequired` → `acceptOffer`.
- **Enforced:** `OfferService.mandateStatus`.
- **Violation:** Any `acceptOffer` error (BR-LC-14).
- **Example:** The borrower returns from Digio → the status poll hands off.

### BR-MAN-06 — Mandate invite for live loans
- **Rule:** Staff may send a mandate-setup link to a customer without a mandate, but only if a proven account exists.
- **Condition:** No ACTIVE mandate; the account is confirmed or verified; publishes `MandateInviteEvent`. The link points to our page, not Digio's URL.
- **Enforced:** `MandateService.invite`.
- **Violation:** `MANDATE_ALREADY_ACTIVE`, `MANDATE_NO_ACCOUNT`.
- **Example:** Free and repeatable: the attempt is minted only when the borrower opens the page.

### BR-MAN-07 — Cancelling an ACTIVE mandate
- **Rule:** The Collection Head, Disbursement Head or ADMIN may cancel an ACTIVE mandate with an NPCI reason and a note, but never while a debit is in flight.
- **Condition:**
  - Reason ∈ {CUSTOMER_REQUEST (C001), CORPORATE_REQUEST (C002)}; note non-blank (truncated at 480).
  - Status ACTIVE; no SCHEDULED or IN_PROGRESS debit.
  - Customer and loan locks are held.
- **Enforced:** `ApplicationController` (`MANDATE_CANCEL_ROLES`); `MandateService.cancelActive`.
- **Violation:** `CANCEL_REASON_REQUIRED`, `CANCEL_NOTES_REQUIRED`, `MANDATE_NOT_ACTIVE`, `MANDATE_DEBIT_IN_FLIGHT`, `MANDATE_CANCEL_REFUSED`, `FORBIDDEN_ROLE`.
- **Example:** The borrower gets `MANDATE_CANCELLED` (in-app + email).

### BR-MAN-08 — Changing the mandate account
- **Rule:** The Disbursement Head or ADMIN may move a customer to a new account: penny-drop it, withdraw every old mandate, then re-invite.
- **Condition:**
  - Note required; account `8–18` digits and IFSC `^[A-Z]{4}0[A-Z0-9]{6}$`.
  - Must differ from the current account; the account was confirmed; the bank is not unsupported; no debit in flight.
  - The penny drop must pass (side-effect-free, via `VerificationPort`).
- **Enforced:** `MandateService.changeAccount` (`PAYEE_ROLES = {DISBURSEMENT_HEAD, ADMIN}`).
- **Violation:** `CHANGE_NOTES_REQUIRED`, `ACCOUNT_INVALID`, `ACCOUNT_UNCHANGED`, `MANDATE_ACCOUNT_UNCONFIRMED`, `MANDATE_BANK_UNSUPPORTED`, `MANDATE_DEBIT_IN_FLIGHT`, `PENNY_DROP_FAILED`, `FORBIDDEN_ROLE`.
- **Example:** Mandate amendment and UPI AutoPay are not built.

### BR-MAN-09 — Debit schedule
- **Rule:** With `digio-debit` on, the 10:00 IST sweep presents a NACH debit at DPD 1, 3, 5 and 9, one presentation per slot reached, against loans with a usable mandate.
- **Condition:**
  - `1 ≤ dpd ≤ 9`; `hasUsableMandate`; `slotsReached = |{s ∈ [1,3,5,9] : dpd ≥ s}|`.
  - Present if there is no prior debit, or `latest.attemptNo < slotsReached` and latest is not SCHEDULED/IN_PROGRESS and not a terminal FAILED.
  - The sweep first reconciles in-flight rows (BR-MAN-13).
- **Enforced:** `MandateDebitScheduler.sweep/shouldPresent` (cron `0 0 10 * * *`, zone Asia/Kolkata).
- **Violation:** A per-loan failure is logged and skipped.
- **Example:** A loan first seen at DPD 4 gets one debit (slots 1 and 3 reached, attemptNo 0 < 2), and its next at DPD 5.

### BR-MAN-10 — Debit preconditions
- **Rule:** A debit is presented only against a live, overdue loan with a usable UMRN mandate, a configured settlement account, nothing in flight and an amount within the balance and the mandate.
- **Condition:** The checks run in this order:
  1. Advisory lock `2^40 | loanId`.
  2. No SCHEDULED or IN_PROGRESS row.
  3. FAILED_TECHNICAL latest → look it up by key and re-present with the **same** key (`presentCount < 3`).
  4. A terminal FAILED latest → refuse.
  5. Unsuccessful count `< 4`.
  6. Loan status ∈ {ACTIVE, OVERDUE, IN_COLLECTIONS}; DPD ≥ 1.
  7. Usable mandate with a UMRN.
  8. `configured()`.
  9. `amount = outstandingAsOf(today + 1)` unless given; `0 < amount ≤ outstanding`; `amount ≤ mandate.max`; `amount ≤ Integer.MAX_VALUE`.
  10. Settle date inside the mandate's first/final collection dates.
  - Key = `DB{S|M}<loanId>-<attemptNo>`; narration "DhanBoost repayment".
- **Enforced:** `DebitService.prepare/present`.
- **Violation:** `DEBIT_IN_FLIGHT`, `DEBIT_ATTEMPTS_EXHAUSTED`, `DEBIT_NOT_REPRESENTABLE`, `LOAN_NOT_FOUND`, `LOAN_NOT_DEBITABLE`, `LOAN_NOT_OVERDUE`, `NO_USABLE_MANDATE`, `DEBIT_NOT_CONFIGURED`, `NOTHING_OUTSTANDING`, `INVALID_AMOUNT`, `AMOUNT_EXCEEDS_OUTSTANDING`, `AMOUNT_EXCEEDS_MANDATE`, `AMOUNT_TOO_LARGE`, `OUTSIDE_MANDATE_WINDOW`, `DEBIT_REFERENCE_INVALID`.
- **Example:** Sized to tomorrow's balance (T+1 settlement), so the penalty accrued until settlement is covered.

### BR-MAN-11 — Attempt caps
- **Rule:** At most 4 unsuccessful presentations per loan, and at most 3 presentations of one FAILED_TECHNICAL row.
- **Condition:**
  - `count(FAILED, FAILED_TECHNICAL) ≥ 4` → refuse.
  - `reuse.presentCount ≥ 3` → refuse.
  - SUCCEEDED, CANCELLED and SKIPPED rows do not count.
  - DB backstop: `uq_mandate_debit_in_flight` (V84).
- **Enforced:** `DebitService` (`MAX_ATTEMPTS = 4`, `MAX_PRESENTATIONS_PER_ROW = 3`).
- **Violation:** `DEBIT_ATTEMPTS_EXHAUSTED`.
- **Example:** A partial-success debit does not use up the cap.

### BR-MAN-12 — Terminal failure denylist
- **Rule:** A FAILED debit is re-presentable unless its code or reason contains a terminal marker. An unknown code **is** retried.
- **Condition:**
  - `text = normalise(failureCode) + "_" + normalise(failureReason)`, where normalise = uppercase and every non-alphanumeric run becomes `_`.
  - Terminal iff it contains any of: ACCOUNT_CLOSED, ACCOUNT_BLOCKED, ACCOUNT_FROZEN, ACCOUNT_INOPERATIVE, ACCOUNT_TRANSFERRED, ACCOUNT_DORMANT, DORMANT, NO_SUCH_ACCOUNT, INVALID_ACCOUNT, ACCOUNT_DOES_NOT_EXIST, ACCOUNT_NOT_FOUND, DEBIT_NOT_ALLOWED, DEBIT_BLOCKED, DEBIT_STOPPED, PAYMENT_STOPPED, MANDATE_CANCELLED, MANDATE_REVOKED, MANDATE_EXPIRED, MANDATE_INVALID, MANDATE_NOT_FOUND, MANDATE_NOT_REGISTERED, NO_MANDATE, UMRN_NOT_FOUND, DECEASED, KYC_PENDING, AMOUNT_EXCEEDS, LIMIT_EXCEEDED, DUPLICATE.
  - Only `FAILED` qualifies (FAILED_TECHNICAL goes to recovery).
- **Enforced:** `DebitService.eligibleForRepresentation` (`TERMINAL_MARKERS`, marked UNVERIFIED against real Digio codes).
- **Violation:** `DEBIT_NOT_REPRESENTABLE`.
- **Example:** "Insufficient funds" → retried at the next slot; "Account closed" → never retried.

### BR-MAN-13 — Reconcile by polling
- **Rule:** Debit outcomes come only from a polling pass: 50 rows per run, least-recently-attempted first. An unresolved row is touched so the queue rotates. There is no Digio webhook receiver.
- **Condition:** Statuses {SCHEDULED, IN_PROGRESS, FAILED_TECHNICAL}; `PageRequest.of(0, 50)`; unresolved → `debits.touch(id, now)` (so `updated_at` means "last attempted").
- **Enforced:** `MandateDebitScheduler.reconcileInFlight`; `DebitService.reconcile`.
- **Violation:** WARN per row.
- **Example:** A row stuck IN_PROGRESS for days keeps rotating to the back of the queue.

### BR-MAN-14 — Debit success and overpayment
- **Rule:** A successful debit becomes a VERIFIED payment (txnRef `NACH:<digio txn id>`, deduplicated on replay). A debit that lands on an already-settled loan is flagged as an overpayment for refund.
- **Condition:** Success → record and verify via the repayment seam. `LOAN_SETTLED` → `row.overpayment = true`. The overpayments list feeds "Refunds owed".
- **Enforced:** `DebitService` (apply/credit section); `DebitService.overpayments`.
- **Violation:** n/a.
- **Example:** The borrower paid by UPI on the same morning the NACH settled → refund owed.

### BR-MAN-15 — Manual debit by collections
- **Rule:** The Collection Head, a Collection Executive (own case only) or ADMIN may present a debit by hand, with `digio-debit` on and an open case.
- **Condition:** Role ∈ {COLLECTION_HEAD, COLLECTION_EXECUTIVE, ADMIN}; the flag is on; a case exists for the loan; `assertCaseAccessible`; then the same `DebitService` guards (trigger MANUAL).
- **Enforced:** `ManualDebitService` (endpoint `POST /api/collections/loans/{loanId}/debit`, token `mandate:debit`).
- **Violation:** `FORBIDDEN_ROLE`, `DEBIT_DISABLED`, `CASE_NOT_FOUND`, `CASE_NOT_ASSIGNED`, and every BR-MAN-10 code.
- **Example:** An executive may debit only a loan on their own case.

### BR-MAN-16 — Cancelling an in-flight debit
- **Rule:** A debit that is still in flight can be cancelled with the provider. A finished one cannot.
- **Condition:** Status ∈ IN_FLIGHT, else `DEBIT_NOT_IN_FLIGHT`. Provider refusal or unavailability is surfaced.
- **Enforced:** `DebitService.cancel`.
- **Violation:** `DEBIT_NOT_FOUND`, `DEBIT_NOT_IN_FLIGHT`, `DEBIT_CANCEL_UNAVAILABLE`, `DEBIT_UNAVAILABLE`, `DEBIT_CANCEL_REFUSED`.
- **Example:** A borrower who paid manually before settlement.

---

## 10. Notifications (BR-NOT)

### BR-NOT-01 — Event-driven, after commit, never blocking
- **Rule:** Domain code only publishes a Spring event. A listener dispatches it after the transaction commits, on the async notification executor. Business logic never depends on delivery.
- **Condition:** `@TransactionalEventListener(AFTER_COMMIT) @Async("notificationExecutor")`. Each channel delivery is isolated; one failing channel never sinks the others.
- **Enforced:** `NotificationEventListener.*`; `NotificationDispatcher.dispatch/deliver`.
- **Violation:** The delivery row records FAILED or SKIPPED (`NO_TEMPLATE`, `NO_SENDER`, `OPTED_OUT`, `NO_MOBILE`).
- **Example:** An SMS gateway outage never rolls back a sanction.

### BR-NOT-02 — Lifecycle action → notification type
- **Rule:** Every `ApplicationTransitionedEvent` is mapped by its **action** to a type. Unmapped actions notify nobody.
- **Condition:** The mapping:

| Action | Type | Channels | Audience |
|---|---|---|---|
| `AUTO_REJECT_*` (any) | CREDIT_REJECTED (reasonless) | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `SUBMIT_KYC` | KYC_SUBMITTED | IN_APP, EMAIL | Credit Heads, Admins |
| `KYC_APPROVE` / `KYC_REJECT` | KYC_APPROVED / KYC_REJECTED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `APPLY` | LOAN_APPLIED | IN_APP | Credit Heads |
| `APPLY_FAST_TRACK`, `ACCEPT_OFFER`, `RETRY` | LOAN_APPLIED_FAST_TRACK | IN_APP, EMAIL | Disbursement Heads, Admins, borrower |
| `ASSIGN` | CREDIT_ASSIGNED | IN_APP | assigned executive |
| `REJECT_LEAD` (`EXEC_REJECT`, `HEAD_REJECT` legacy) | CREDIT_REJECTED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `SANCTION` | LOAN_SANCTIONED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `DISB_REJECT` | DISBURSEMENT_REJECTED | IN_APP, EMAIL, WHATSAPP | borrower |
| `ACTIVATE` | LOAN_DISBURSED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `REPAID` | LOAN_CLOSED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `CANCEL` | APPLICATION_CANCELLED | IN_APP, WHATSAPP | borrower |
| `REOPEN_RESCORE` | KYC_REOPENED_RESCORE | IN_APP, EMAIL, WHATSAPP | borrower |
| `REBORROW` → PRE_APPROVED / → REJECTED | REBORROW_PREAPPROVED / CREDIT_REJECTED | IN_APP, SMS, WHATSAPP / as above | borrower |
| `CREATE`, `VALIDATE_SUCCESS`, `MARK_PENDING`, `REASSIGN`, `REAPPLY_SANCTION`, `REBORROW_ALLOWED`, `MANDATE_OVERRIDE`, `ADMIN_FORCE_DISBURSE` | none | — | — |

- **Enforced:** `NotificationEventListener.mapAction`; `NotificationType` (channels, audience).
- **Violation:** n/a. See C-08.
- **Example:** A clean reborrow emits `REBORROW` then `REAPPLY_SANCTION`, so the borrower gets only REBORROW_PREAPPROVED.

### BR-NOT-03 — Non-transition events
- **Rule:** Other domain events map one-to-one to a type.
- **Condition:** Event → type:
  - `WaitlistSubmittedEvent` → WAITLIST_RECEIVED.
  - `KycReminderEvent` → KYC_REMINDER.
  - `BureauQuestionPendingEvent` → BUREAU_QUESTION_PENDING.
  - `VerificationStepLinkEvent` → VERIFICATION_STEP_LINK.
  - `RepaymentRecordedEvent` → REPAYMENT_RECORDED (Accountants + borrower).
  - `RepaymentVerifiedEvent` → REPAYMENT_VERIFIED.
  - `RepaymentRejectedEvent` → REPAYMENT_REJECTED (IN_APP and EMAIL carry the reason; the SMS is DLT-fixed).
  - `SanctionLetterSignedEvent` → SANCTION_LETTER_SIGNED (the signed PDF is attached to the email).
  - `SanctionedAmountRevisedEvent` → SANCTIONED_AMOUNT_REVISED.
  - `LoanLimitRevisedEvent` → LOAN_LIMIT_REVISED (on increases only, BR-ELG-02).
  - `PaymentReminderEvent` → PAYMENT_DUE_SOON / PAYMENT_OVERDUE.
  - `ProviderHealthEvent` → PROVIDER_BALANCE_EXHAUSTED / PROVIDER_CAPABILITY_DOWN (Admins).
  - `CollectionCaseOpenedEvent` → COLLECTION_CASE_OPENED.
  - `SettlementProposed/Approved/RejectedEvent` → SETTLEMENT_PROPOSED (Heads) / SETTLEMENT_APPROVED (borrower) / SETTLEMENT_REJECTED (proposer).
  - `CollectionPaymentRaisedEvent` → COLLECTION_PAYMENT_TO_APPROVE (Heads) or COLLECTION_PAYMENT_TO_VALIDATE (Accountants).
  - `CollectionPaymentDecidedEvent` → COLLECTION_PAYMENT_VALIDATED / _REJECTED.
  - `MandateBankUnsupportedEvent` → MANDATE_BANK_UNSUPPORTED (Disbursement Heads).
  - `MandateInviteEvent` → MANDATE_SETUP_INVITE.
  - `MandateCancelledEvent` → MANDATE_CANCELLED.
  - `ReferralPayoutCreatedEvent` → REFERRAL_PAYOUT_PENDING (Disbursement Heads).
  - `ReferralRewardCreditedEvent` → REFERRAL_REWARD_CREDITED.
  - `StaffAccountEvent` → STAFF_INVITED / CREATED / ROLE_CHANGED / DISABLED.
- **Enforced:** `NotificationEventListener.on*`.
- **Violation:** n/a.
- **Example:** A payment verified that closes the loan → REPAYMENT_VERIFIED plus LOAN_CLOSED (via `REPAID`).

### BR-NOT-04 — Opt-outs
- **Rule:** Borrowers and staff may opt out of EMAIL and SMS. The SMS opt-out also silences WhatsApp. IN_APP is never suppressed, and STAFF_IAM mail ignores preferences.
- **Condition:**
  - Borrower: `!emailOptIn` → EMAIL; `!smsOptIn` → SMS + WHATSAPP. No preferences row → nothing opted out.
  - Category STAFF_IAM → empty opt-out set.
  - Opted-out channel → SKIPPED/`OPTED_OUT` delivery row.
- **Enforced:** `NotificationDispatcher.dispatch`; `BorrowerPreferenceAdapter.optedOutChannels`; `StaffPreferenceAdapter.optedOutChannels`.
- **Violation:** n/a.
- **Example:** A borrower with SMS off still sees the inbox item "Loan disbursed".

### BR-NOT-05 — Email suppression
- **Rule:** An address that hard-bounced (Permanent) or complained is never emailed again. Transient bounces are only logged.
- **Condition:** SES → SNS → SQS. `Bounce` with `bounceType == Permanent`, or any `Complaint` → `email_suppression` row (idempotent). `EmailSender` skips suppressed addresses (case-insensitive).
- **Enforced:** `SesEventSqsListener.handleBounce/handleComplaint`; `EmailSuppressionService.suppress/isSuppressed`; `EmailSender`.
- **Violation:** The delivery is skipped.
- **Example:** A mistyped personal email that bounced once is suppressed permanently.

### BR-NOT-06 — Payment reminder windows
- **Rule:** The daily sweep sends one "due soon" reminder per day from 7 days before due through the last grace day, then one "overdue" reminder per day for the first 7 days past grace. After that the loan is left to collections.
- **Condition:**
  - Only ACTIVE/OVERDUE loans with `outstanding > 0`.
  - `daysToDue = days(today, due)`; `g = loan.graceDays`.
  - `−g ≤ daysToDue ≤ 7` → DUE_SOON(days = max(0, daysToDue)).
  - `g < −daysToDue ≤ g + 7` → OVERDUE(daysOverdue).
  - The sweep also calls `markInCollections` (BR-LC-22).
- **Enforced:** `PaymentReminderScheduler.sendDueAndOverdueReminders/sendReminderFor` (`REMINDER_WINDOW_DAYS = 7`, `OVERDUE_BUCKET_DAYS = 7`).
- **Violation:** n/a.
- **Example:** Grace 0, due 06-30 → DUE_SOON on 06-23…06-30 (8 messages), OVERDUE on 07-01…07-07.

### BR-NOT-07 — Admin customer messaging
- **Rule:** Only ADMIN, with `admin-messaging` on, may message a customer from the catalogue or stored templates. Sends are allowed only between 08:00 and 19:00 IST, and each channel is skipped for any of the listed reasons.
- **Condition:**
  - Send window `08:00 ≤ now < 19:00` IST.
  - Templates with confirm text need `confirmed = true`.
  - Per-channel skip reasons:
    - `NO_EMAIL` / `NO_MOBILE`.
    - `OPTED_OUT` (`smsOptIn` covers SMS and WhatsApp).
    - `SUPPRESSED` (email).
    - `OFFERS_OPTED_OUT` (MARKETING category and `!offersOptIn`).
    - `DUPLICATE_24H` (same customer, template and channel SENT in the last 24 h).
    - `SMS_NOT_REGISTERED`.
  - DLT variable values: ≤ 30 characters, ASCII only.
- **Enforced:** `CustomerMessageService.send/plan/skipReason/checkDltValues/requireAdmin`.
- **Violation:** `OUTSIDE_SEND_HOURS`, `CONFIRMATION_REQUIRED`, `FEATURE_DISABLED`, `INVALID_TEMPLATE`, `CHANNEL_REQUIRED`, `INVALID_CHANNEL`, `INPUT_REQUIRED`, `INVALID_INPUT`, `INVALID_TARGET`, `FORBIDDEN_ROLE`.
- **Example:** A send at 19:00:00 IST is refused.

### BR-NOT-08 — SMS and WhatsApp wording is externally locked
- **Rule:** SMS text must match the DLT template character for character (use `Rs.`, not `₹`, and only the whitelisted login URL). WhatsApp parameters are positional against the Meta-approved template.
- **Condition:** The DLT template ids live in `application.yml` per `NotificationType`. `NotificationTemplates.whatsapp(type, name, orderedKeys…)` keys must never be reordered.
- **Enforced:** `NotificationTemplates`; `SmsSender`; `WhatsAppSender`.
- **Violation:** The provider rejects the message, or it is delivered garbled.
- **Example:** The repayment-rejected reason goes by IN_APP and EMAIL only, because the SMS body is fixed.

---

## 11. Auth & sessions (BR-AUTH)

### BR-AUTH-01 — Borrower SMS OTP
- **Rule:** The login OTP is 6 digits with no leading zero and is valid for 300 seconds. It goes by SMS and also by WhatsApp. In mock mode the code is a fixed `123456` and nothing is sent.
- **Condition:**
  - `code = 10^(n−1) + rand(9·10^(n−1))`, with `n = otp-length` (6).
  - TTL `otp-ttl-seconds` (300).
  - Stored per `purpose:mobile`; a new request replaces the old code.
  - The code is echoed back only in mock or dev-echo mode.
- **Enforced:** `BorrowerOtpService.request/generate`; `SmsProperties`.
- **Violation:** `INVALID_MOBILE` (not 10 digits).
- **Example:** `ProductionConfigGuard` refuses to boot with mock OTP unless `NAVIX_ALLOW_DEV_DEFAULTS=true`.

### BR-AUTH-02 — OTP send and verify caps
- **Rule:** At most 5 OTP sends per number per purpose per 15 minutes, and 5 wrong guesses per code. A code is single-use.
- **Condition:**
  - Sends: `limiter.hit("otp:"+purpose+":"+number, 5, 15 min, 15 min)`.
  - Verify: expired or `attempts ≥ 5` → the code is dropped; a wrong guess → attempts+1; a match → removed.
  - Bureau-consent and login budgets are separate.
- **Enforced:** `BorrowerOtpService.request/verify` (`MAX_SENDS = 5`, `MAX_ATTEMPTS = 5`, `SEND_WINDOW = 15 min`).
- **Violation:** `TOO_MANY_ATTEMPTS` ("Too many OTP requests for this number." + the wait); `INVALID_OTP`.
- **Example:** The limits are in-process maps, so each backend instance holds its own (C-14).

### BR-AUTH-03 — Email OTP
- **Rule:** Personal and official email codes are 6 digits, valid for 10 minutes, with 5 sends per 15 minutes and 5 guesses.
- **Condition:** `100000 + rand(900000)`; TTL 600 s; `limiter.hit("emailotp:…", 5, 15 min, 15 min)`.
- **Enforced:** `EmailOtpService` (`MAX_SENDS = 5`, `MAX_ATTEMPTS = 5`, `TTL_SECONDS = 600`).
- **Violation:** `INVALID_EMAIL`, `INVALID_OTP`, `TOO_MANY_ATTEMPTS`, `EMAIL_MISSING`.
- **Example:** Changing the email later nulls `personalEmailVerified` (BR-VER-08).

### BR-AUTH-04 — Reference OTP
- **Rule:** With `reference-otp` on, each reference must confirm a code that is valid for 10 minutes. Resends need a 60-second wait; there are at most 6 sends and 5 attempts.
- **Condition:** `TTL = 10 min`, `RESEND_WAIT = 60 s`, `MAX_SENDS = 6`, `MAX_ATTEMPTS = 5`. Only references with `otpRequired` qualify, and only while SANCTIONED.
- **Enforced:** `ReferenceOtpService`.
- **Violation:** `REFERENCE_OTP_LIMIT`, `REFERENCE_OTP_COOLDOWN`, `REFERENCE_OTP_NOT_SENT`, `REFERENCE_OTP_EXPIRED`, `REFERENCE_OTP_INVALID`, `NOT_APPLICABLE`.
- **Example:** Once both references confirm, the journey pointer advances past References.

### BR-AUTH-05 — Password sign-in throttle
- **Rule:** Three **wrong** passwords within 15 minutes trigger a 30-second cool-off. A successful sign-in clears the count. The check runs before the account lookup and BCrypt.
- **Condition:**
  - `limiter.hit(key, 3, 15 min, 30 s)`; `key = "staff:"+email` or `"pw:"+mobile`.
  - `limiter.clear(key)` on success.
  - Unknown accounts still pay a dummy BCrypt.
- **Enforced:** `AuthController` (`MAX_LOGIN_ATTEMPTS = 3`, `LOGIN_WINDOW = 15 min`, `LOGIN_COOLOFF = 30 s`); `AttemptLimiter.hit/clear`.
- **Violation:** `TOO_MANY_ATTEMPTS` ("Too many sign-in attempts." + the wait); `INVALID_CREDENTIALS` (the same message whether the account exists or not); `NO_PASSWORD_SET` (borrower).
- **Example:** An E2E test that types the wrong password three times sees what looks like a UI failure.

### BR-AUTH-06 — Forgot-password throttle and token
- **Rule:** Each email may request at most 3 reset links per 15 minutes per audience, whether or not it matches an account. A reset token is valid for 30 minutes and works once.
- **Condition:**
  - `limiter.hit("forgot:{borrower|staff}:"+email, 3, 15 min, 15 min)`.
  - Token TTL 30 min; already used → refuse; expired → refuse.
  - Turnstile is checked on both forms when configured.
- **Enforced:** `AuthController` (`MAX_RESET_REQUESTS = 3`, `RESET_WINDOW = 15 min`); `PasswordResetService` (`TOKEN_TTL = 30 min`); `CaptchaVerifier`.
- **Violation:** `TOO_MANY_ATTEMPTS`, `INVALID_TOKEN`, `EXPIRED_TOKEN`.
- **Example:** The response never reveals whether the email has an account.

### BR-AUTH-07 — Password policy
- **Rule:** Borrower set-password and every reset require at least 8 characters, at most 72 UTF-8 bytes, and at least one letter, one digit and one special (non-space) character. Staff invite acceptance checks only 8–72 characters.
- **Condition:** `PasswordPolicy.validate`: `len ≥ 8 && bytes ≤ 72 && letter && digit && special`. `StaffDtos` uses `@Size(min = 8, max = 72)`.
- **Enforced:** `AuthController` (set-password), `PasswordResetService` (both audiences); `StaffDtos` (invite accept).
- **Violation:** `WEAK_PASSWORD`; 400 on staff invite accept.
- **Example:** "password1" → `WEAK_PASSWORD` (no special character). See C-06.

### BR-AUTH-08 — Token lifetimes and audiences
- **Rule:** The borrower JWT lasts 7 days (audience `borrower`, sub = customerId). The staff JWT lasts 1 day (audience `staff`, sub = staffId, role + `sid`).
- **Condition:** `BORROWER_AUTH_TTL_SECONDS` default 604800; `AUTH_TTL_SECONDS` default 86400. HS256 with `AUTH_SECRET`, which must not be the default and must be at least 32 characters in production.
- **Enforced:** `JwtService`; `ProductionConfigGuard`; `JwtAuthFilter`.
- **Violation:** 401 on a missing or invalid bearer.
- **Example:** A borrower token used on `/api/staff/**` → refused (audience mismatch).

### BR-AUTH-09 — One live staff session
- **Rule:** A staffer has one live console session. A second sign-in while one is live is refused unless the user chooses to continue here (`force`). Disabling the user or changing their role kills the session.
- **Condition:**
  - `sessionLive = activeSessionId != null && activeSessionAt > now − staffTtl`.
  - Live && !force → refuse. Otherwise mint a new `sid`, stamp it, and evict the cache.
  - Each request checks `isCurrent(staffId, sid)` against a 30 s cache; an inactive staffer is refused.
  - Logout clears only a matching `sid`.
- **Enforced:** `AuthController` (staff login/logout); `StaffSessionRegistry.isCurrent/invalidate` (`TTL = 30 s`); `StaffSessionInvalidator`.
- **Violation:** `SESSION_CONFLICT`; 401 for a superseded `sid`.
- **Example:** A stale stamp older than the token TTL is ignored, so no false conflict appears.

### BR-AUTH-10 — Working-role normalisation
- **Rule:** `X-Acting-Role` is honoured only if the real role is allowed to work as it, and it never equals the real role. Anything else is ignored.
- **Condition:**
  - Allowed: ADMIN → {CREDIT_HEAD, CREDIT_EXECUTIVE, DISBURSEMENT_HEAD, ACCOUNTANT, COLLECTION_HEAD, COLLECTION_EXECUTIVE, TELECALLER}; CREDIT_HEAD → {CREDIT_EXECUTIVE}; COLLECTION_HEAD → {COLLECTION_EXECUTIVE}.
  - `normalize = trim().upper()`; must be ≠ real role and allowed; else `null`.
- **Enforced:** `ActingRole.allowed/normalize`; `JwtAuthFilter` → `ActorContext`.
- **Violation:** Silently ignored. Authorization still uses the real role; `effectiveRole()` only scopes lists and own-case checks.
- **Example:** A DISBURSEMENT_HEAD sending `X-Acting-Role: ADMIN` → ignored.

### BR-AUTH-11 — DSA is an authz exclusion
- **Rule:** The DSA role is explicitly rejected on every staff-open read that would expose customer data.
- **Condition:** `"DSA".equals(role)` → refuse, in `requireStaff`-style guards (customers, applications, decisions, search, loans, collections, skip-trace and BSA reads).
- **Enforced:** e.g. `RepaymentService.requireReadableLoan`, `CollectionsService.requireCollectionsStaff`, `CustomerService` / `ApplicationController.requireStaff`.
- **Violation:** `FORBIDDEN_ROLE` ("DSAs cannot view …").
- **Example:** A new staff-open endpoint must add this rejection, because `hasRole("STAFF")` is audience-level.

---

## 12. DSA, referral, discounts, reapply allowance (BR-GRW)

### BR-GRW-01 — DSA lead creation
- **Rule:** A DSA may create at most 30 leads per rolling 24 hours, and only for a PAN that no other DSA owns and that is not already a customer.
- **Condition:**
  - `countByOwnerDsaIdAndCreatedAtAfter(now − 1 d) ≥ 30` → refuse.
  - A PAN owned by a DSA → reject `OTHER_DSA`; `existsByPan` → reject `EXISTING_CUSTOMER`. Both are logged to `dsa_lead_rejection`.
  - The PAN is uppercased; amounts must be ≥ 0.
- **Enforced:** `DsaService.createLead/recordRejection` (`MAX_LEADS_PER_DAY = 30`).
- **Violation:** `LEAD_RATE_LIMITED`, `LEAD_ALREADY_KNOWN` (the same message for both, so the DSA learns nothing), `INVALID_AMOUNT`, `FORBIDDEN_ROLE`.
- **Example:** A DSA submitting a known customer's PAN → "This person is already known to us".

### BR-GRW-02 — DSA commission eligibility
- **Rule:** A DSA earns commission only on the **first** loan of a customer whose PAN matches the DSA's lead, and only if the lead was created **before** the application.
- **Condition:**
  - Profile PAN (uppercased) = `lead.pan` with `ownerDsaId != null`.
  - `lead.createdAt < app.createdAt`; no commission already on the lead.
  - `countByCustomerId(loans) == 1`.
  - It runs inside the disbursal and never throws.
- **Enforced:** `DsaCommissionService.onLoanDisbursed/accrue`.
- **Violation:** No commission, silently.
- **Example:** A lead entered after the borrower had already applied earns nothing.

### BR-GRW-03 — DSA commission amount and maturity
- **Rule:** The commission is 3.5% of net disbursed, accrued at disbursal. It becomes payable when the loan closes, and is voided by an approved settlement or a default/write-off.
- **Condition:**
  - `amount = round(net × 350 / 10000)` (HALF_UP); status ACCRUED.
  - On loan close: PAYABLE, or VOID if a settlement was approved or the application is DEFAULTED/WRITTEN_OFF.
  - On `SettlementApprovedEvent`, an ACCRUED commission → VOID.
- **Enforced:** `DsaCommissionService.accrue/onLoanClosed/isVoidOutcome/onSettlementApproved/voidCommission` (`DSA_COMMISSION_RATE_BPS = 350`).
- **Violation:** n/a.
- **Example:** Net ₹8,820 → ₹308.70 (E29).

### BR-GRW-04 — DSA commission administration
- **Rule:** ADMIN marks a PAYABLE commission paid (txn id required), voids one with a reason (not if paid), reassigns it to another DSA with a reason (not if paid), or attributes a lead manually (once, after a disbursed loan exists).
- **Condition:** As stated.
- **Enforced:** `DsaAdminService` (ADMIN only).
- **Violation:** `MISSING_TXN_REF`, `NOT_PAYABLE`, `VOID_REASON_REQUIRED`, `ALREADY_PAID`, `MISSING_DSA`, `REASSIGN_REASON_REQUIRED`, `MISSING_FIELDS`, `COMMISSION_EXISTS`, `NO_DISBURSED_LOAN`, `FORBIDDEN_ROLE`.
- **Example:** A DSA's own portal shows only their own leads and earnings (`dsa:portal`; not ADMIN).

### BR-GRW-05 — Referral code
- **Rule:** Each borrower's referral code is 8 characters from an alphabet without ambiguous characters, generated with up to 10 tries.
- **Condition:** Alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no I, L, O, 0, 1); length 8; `SecureRandom`.
- **Enforced:** `ReferralService` (`CODE_LENGTH = 8`, `CODE_MAX_TRIES = 10`).
- **Violation:** `REFERRAL_CODE_GEN_FAILED`.
- **Example:** `K7MQ2XPA`.

### BR-GRW-06 — Applying a referral code
- **Rule:** A new borrower (no loans yet) may apply one valid code that is not their own, while the referral program is on.
- **Condition:** `referralEnabled()`; the code exists; the referrer ≠ self; no prior referral for this customer; `loanRepository.findByCustomerId(self).isEmpty()`.
- **Enforced:** `ReferralService.applyCode` (`validate` is the side-effect-free preview).
- **Violation:** `REFERRAL_DISABLED`, `INVALID_REFERRAL_CODE`, `SELF_REFERRAL`, `ALREADY_REFERRED`, `NOT_NEW_BORROWER`.
- **Example:** A returning borrower cannot use a friend's code.

### BR-GRW-07 — Referral reward
- **Rule:** On the referred borrower's first disbursal, both parties get a pending ₹200 payout. The Disbursement Head (or ADMIN) marks payouts paid with a transaction id.
- **Condition:**
  - Referral PENDING → QUALIFIED (`qualifyingLoanId`).
  - Two `referral_payout` rows (REFERRER, REFERRED) of `reward-paise` (default 20,000).
  - Payout: txnRef required; not already paid; the flag must be on.
- **Enforced:** `ReferralService.onLoanDisbursed/payPayout/requireDisbursementHead/requireReferralEnabled`; `ReferralProperties` (`NAVIX_REFERRAL_REWARD_PAISE`).
- **Violation:** `MISSING_TXN_REF`, `ALREADY_PAID`, `REFERRAL_DISABLED`, `FORBIDDEN_ROLE`.
- **Example:** With the flag off, staff payout management is disabled too.

### BR-GRW-08 — Creating a repay-now discount offer
- **Rule:** An ADMIN creates a discount offer by **sending** a discount message to a customer on a live loan: 1–7% off the next fee, conditional on repaying by a date up to 30 days ahead. No message, no offer.
- **Condition:**
  - `admin-messaging` on.
  - `1 ≤ percent ≤ 7` (whole number); `today ≤ repayBy ≤ today + 30`.
  - The `repayment_discount_offer` row is recorded after the send (customer, loan, percent, repayBy, message id).
- **Enforced:** `CustomerMessageService.send/recordOffer/parsePercent/parseRepayBy` (`MIN_PERCENT = 1`, `MAX_PERCENT = 7`, `MAX_REPAY_BY_DAYS = 30`).
- **Violation:** `INVALID_PERCENT`, `INVALID_REPAY_BY`, `OFFER_NOT_RECORDED` (the message was sent but the offer was not saved), `FEATURE_DISABLED`.
- **Example:** "Repay by 30 Nov and get 5% off your next processing fee."

### BR-GRW-09 — Unlocking, pinning and consuming a discount
- **Rule:** An offer unlocks when its loan is CLOSED and the last VERIFIED payment was on or before `repayBy`. The best unlocked offer is pinned on reborrow and consumed exactly once at disbursal.
- **Condition:**
  - `qualifies = unused && loan.CLOSED && max(VERIFIED paidOn) ≤ repayBy`.
  - `bestFor` = highest percent, then newest id.
  - `pin` sets `fee_discount_percent` and `fee_discount_offer_id`. Only `reborrow` pins (the clean path and the allowance path).
  - `consume` is a compare-and-set claim.
- **Enforced:** `RepaymentDiscountOffers.bestFor/qualifies/pin/consume`.
- **Violation:** `DISCOUNT_OFFER_USED` (disbursal refused).
- **Example:** Paid on the 29th against a repay-by of the 30th → the next loan's fee is 5% instead of 10%.

### BR-GRW-10 — Granting a reapply allowance
- **Rule:** An ADMIN may grant a currently disqualified customer one documented, one-use permission to re-apply.
- **Condition:**
  - Reason non-blank; the document must belong to one of the customer's applications.
  - `isDisqualified(customerId)`; no unused allowance already.
  - Audited to `profile_change_log` (`reapplyAllowance`).
- **Enforced:** `ReapplyAllowanceService.grant/status` (ADMIN only).
- **Violation:** `REASON_REQUIRED`, `DOCUMENT_REQUIRED`, `INVALID_DOCUMENT`, `NOT_DISQUALIFIED`, `ALLOWANCE_EXISTS`, `FORBIDDEN_ROLE`.
- **Example:** A borrower who repaid 8 days late because of a hospital stay, with the discharge summary uploaded.

### BR-GRW-11 — Consuming a reapply allowance
- **Rule:** On the next reborrow the oldest unused allowance is consumed atomically. The new file goes to the normal credit queue (KYC_PENDING), never PRE_APPROVED and never auto-sanctioned.
- **Condition:**
  - `findFirstByCustomerIdAndUsedApplicationIdIsNullOrderByIdAsc`; `consume(id, appId, now) == 1`.
  - Copies the carried checks and references from the latest sanctioned application, pins the discount.
  - `KYC_PENDING (REBORROW_ALLOWED)`.
- **Enforced:** `ApplicationFlowService.reborrow`.
- **Violation:** If the claim fails (a race), the normal PAST_DELINQUENCY auto-reject applies.
- **Example:** BR-REJ-06: the allowance does not lift dated blocks.

---

## 13. Schedulers (BR-SCH)

All schedulers are **single-instance**, with no distributed lock. Running two backend tasks would run each job twice. ShedLock-style locking is on the go-live backlog.

| Job | Cron (Spring: s m h dom mon dow) | Zone | Flag | Batch / idempotency |
|---|---|---|---|---|
| `PaymentReminderScheduler.sendDueAndOverdueReminders` | `0 0 9 * * *` (`NAVIX_REMINDERS_CRON`) | **JVM default** (none set) | — | All ACTIVE/OVERDUE loans; one reminder per loan per run; also `markInCollections` (idempotent) |
| `EmploymentRetryScheduler.retryParkedEmploymentChecks` | `0 30 * * * *` (`NAVIX_EMPLOYMENT_RETRY_CRON`) | JVM default | `employment-auto-retry` (missing ⇒ on) | 50 rows, exponential spacing, ≤ 5 retries (BR-VER-17); runs as SYSTEM with call source RETRY |
| `ProviderHealthMonitor` | `0 15 * * * *` (`NAVIX_PROVIDER_HEALTH_CRON`) | JVM default | — | Hourly vendor probe; alerts Admins (`ProviderHealthEvent`) |
| `MandateDebitScheduler.sweep` | `0 0 10 * * *` (`NAVIX_DIGIO_DEBIT_CRON`) | **Asia/Kolkata** | `digio-debit` (missing ⇒ off) | Reconcile 50 in-flight rows, then present (BR-MAN-09); advisory lock per loan + V84 unique index |
| `PayuReconcileScheduler.sweep` | `0 */5 * * * *` (`NAVIX_PAYU_RECONCILE_CRON`) | JVM default | **not gated** (money in flight must still land) | 50 due open orders; per-row lock; credit deduplicated on `mihpayid` |
| `ProviderApiWorkbenchService.purgeExpiredHistory` | `0 15 2 * * *` | JVM default | — | Deletes `expires_at < now` |

### BR-SCH-01 — One bad row never sinks a sweep
- **Rule:** Every sweep catches each row's exception, logs it and continues.
- **Condition:** try/catch per loan, application or order.
- **Enforced:** all of the above.
- **Violation:** WARN log.
- **Example:** One loan with a corrupt due date does not stop 2,000 reminders.

### BR-SCH-02 — Scheduler threads bind an explicit actor
- **Rule:** A scheduler that calls an actor-aware service sets `ActorContext` to SYSTEM (and the provider call context) and clears it in `finally`.
- **Condition:** `ActorContext.set(CurrentActor.SYSTEM)` … `ActorContext.clear()`.
- **Enforced:** `EmploymentRetryScheduler`; `PayuService.recordAsSystem`.
- **Violation:** Otherwise the audit attributes the work to whatever actor last ran on the pooled thread.
- **Example:** Provider rows from the retry are filed with source `RETRY`, not LIVE.

---

## 14. Feature flags (BR-FF)

Flags live in `feature_flag` and are read **uncached** via `FeatureFlagService.isEnabled(key, defaultWhenMissing)`. Rows are changed by SQL, except three that ADMIN flips in Staff → Settings: `onboarding-paused` (`OnboardingWaitlistService`), `applications-closed` (`IntakeSwitchService`) and `payu-repayment` (`PayuSwitchService`). The seed is not the production state, so check the live table.

| Flag | Seed | Missing row ⇒ | ON | OFF |
|---|---|---|---|---|
| `onboarding-paused` | **on** (V76) | off | No new DRAFT or `submit-kyc` for anyone without a loan (`ONBOARDING_PAUSED` → `/apply` waitlist form); reborrow still open | Normal signup |
| `applications-closed` | no row | off | `APPLICATIONS_CLOSED` for every new application **and** reborrow (checked after ACTIVE_APPLICATION) | open |
| `bureau-auto-reject` | **off** (V64) | off | Score < 550 → LOW_BUREAU_SCORE auto-reject, 90 d (BR-REJ-07) | Every score goes to a human |
| `fintrix-bureau` | on | **on** | Fintrix CRIF bureau leg (and KBA answer) callable | `CapabilityNotSupportedException` → silent skip to the next bureau; KBA answering is impossible |
| `digitap-pan`, `digitap-email` | off | off | Digitap fallback legs called | Skipped (not provisioned, 412) |
| `digitap-crif` | no row | off | Digitap CRIF tried in Digitap's bureau leg | Digitap uses Experian only |
| `employment-auto-retry` | on | **on** | Hourly EPFO re-run (BR-VER-17) | No retries |
| `global-search` | on | **on** | Cmd/Ctrl+K search served | `FEATURE_DISABLED` |
| `referral` | on | env `NAVIX_REFERRAL_ENABLED` (default true) | Codes, rewards, payouts | `REFERRAL_DISABLED` everywhere, including staff payouts |
| `digio-mandate` | off (on in prod per runbook) | off | `mandateRequired` may be true → eMandate step and `MANDATE_REQUIRED` gate; registration allowed; boot guard requires a non-mock Digio | No mandate step; `MANDATE_NOT_ENABLED` on init |
| `digio-debit` | off | off | 10:00 IST sweep presents debits; manual debit allowed | Sweep is a no-op; manual → `DEBIT_DISABLED` |
| `payu-repayment` | off | off | "Pay now" (if credentials set and host not `test.payu.in`); manual flow only after a failed attempt (BR-MNY-30) | Manual UPI/bank + screenshot only; the reconcile sweep still runs |
| `admin-messaging` | off | off | ADMIN messaging sends, stored templates, discount offers | `FEATURE_DISABLED`; only catalogue templates are listed |
| `reference-otp` | off | off | Letters-only reference names; new numbers must OTP-confirm; `REFERENCE_OTP_PENDING` gate | References saved without OTP; turning it off releases a held borrower |
| `esign-documents` | off | off | ADMIN eSign documents (billable, binding) | `FEATURE_DISABLED` |

### BR-FF-01 — Missing row means the coded default
- **Rule:** A missing row means the default passed at the call site, not "off".
- **Condition:** `repository.findByFlagKey(key).map(isEnabled).orElse(defaultWhenMissing)`.
- **Enforced:** `FeatureFlagService.isEnabled`.
- **Violation:** n/a.
- **Example:** Deleting the `global-search` row leaves search **on**.

### BR-FF-02 — Production boot guard
- **Rule:** Flag-dependent configuration is checked at boot. `digio-mandate` on with the Digio mock, or `payu-repayment` on without credentials or on the test host, refuses to start unless `NAVIX_ALLOW_DEV_DEFAULTS=true`.
- **Condition:** As stated, plus the non-flag guards (default `AUTH_SECRET`, mock OTP, SMS dev-echo, mock eSign, `log` email, eSign callback URL/secret for Signzy).
- **Enforced:** `ProductionConfigGuard`.
- **Violation:** Startup failure.
- **Example:** Flipping `payu-repayment` on in a stack without PayU keys makes the next deploy fail to boot.

---

## 15. Contradictions found

Each item names the two sources that disagree. Where they differ, **code is authoritative**. The ids are cited above.

- **C-01 — Due date at disbursal vs the signed KFS. FIXED 2026-11-13** (BR-MNY-12): the loan is now booked on `approvedRepaymentDate`. History:
  - The `LoanService.disburse` comment said "the credit team's approved repayment date wins", but the code always computed `dueDateFromSalary(disbursalDate, salaryDay)` and never read `approvedRepaymentDate`.
  - The offer summary and the KFS price interest from *today* to `approvedRepaymentDate` (`OfferService.tenureDays`).
  - The borrower can therefore sign one date and amount and be booked another (E27: KFS ₹10,900 at 9 days; the loan is due 07-30 at ₹13,900).
- **C-02 — Mixed time zones.**
  - These use `LocalDate.now()` in the JVM default zone: `ApplicationFlowService.finalizeDisbursal`, `sanction` and `carryOverForReapply`; `LoanService.disburse` (fallback); `ApplicationFlowService.isDisqualifiedByHistory`; `CreditRatingCalculator.rate`.
  - `RepaymentService`, `OfferService`, `DebitService`, `PayuService` and the collections services use IST. `CustomerService.changeSalaryCreditDay` notes the inconsistency itself.
  - Only `MandateDebitScheduler` declares `zone = Asia/Kolkata`. The reminder, employment-retry, provider-health, PayU and purge crons use the JVM zone.
  - `Dockerfile.backend` sets no timezone. "Payment reminders 09:00" (CLAUDE.md §12) is therefore IST only if the runtime is IST. This is not verified here.
- **C-03 — Repayment SoD.**
  - CLAUDE.md §7 says repayment approvals enforce proposer ≠ approver server-side.
  - In code, separation is by **role exclusion only**: record = BORROWER/ADMIN, verify = ACCOUNTANT/ADMIN (`RepaymentController`), with no recorder ≠ verifier comparison.
  - `POST …/admin-record` deliberately collapses both onto ADMIN.
  - In collections, `CollectionPaymentService.validate` has no raiser ≠ validator check, and ADMIN passes `requireOneOf` for both `raise` and `validate`. Settlement approve/reject and Head approve *do* compare actors.
- **C-04 — Application OVERDUE/DEFAULTED/WRITTEN_OFF are never entered.**
  - CLAUDE.md §5 lists ACTIVE → OVERDUE → DEFAULTED → WRITTEN_OFF as live edges. No code transitions an application into any of them. Delinquency is the loan's `IN_COLLECTIONS` status (BR-LC-22).
  - As a result, the DEFAULTED/WRITTEN_OFF branches of `ApplicationFlowService.isDisqualifiedByHistory`, of `DsaCommissionService.isVoidOutcome` and of `LIVE_LOAN_STATUSES` fire only on historical rows.
- **C-05 — KFS tenure floor.**
  - Because a sanction never expires, `OfferService.tenureDays` floors a past approved date at 1 day. The KFS then shows 1 day of interest and an APR of 4461.1% (E26), while the loan is booked for up to 40 days (C-01).
- **C-06 — Password policy.**
  - CLAUDE.md §7 says "Passwords ≥ 8 chars".
  - `PasswordPolicy` also requires a letter, a digit and a special character, and caps the length at 72 **bytes**.
  - Staff invite acceptance (`StaffDtos`) checks only `@Size(8, 72)` **characters**.
- **C-07 — No hard maximum loan.**
  - CLAUDE.md §1 lists "Min / max loan ₹1,000 / ₹10,00,000". In code ₹10,00,000 only caps the **formula** limit (`LoanMath.eligibleLimitPaise`).
  - `ApplicationFlowService.sanction` has no upper bound and ignores the limit (decision 33).
  - `CustomerService.setLimitOverride` and `changeSanctionedAmount` have no upper bound.
  - The only other ₹10,00,000 cap is the mandate-ceiling override.
- **C-08 — Notification types no live path emits.**
  - Nothing emits CREDIT_RECOMMENDED (`EXEC_APPROVE`), CREDIT_APPROVED (`HEAD_APPROVE`), REBORROW_REVIEW_APPROVED/REJECTED, DISBURSEMENT_PENDING_ACCOUNTANT or DISBURSEMENT_FAILED. They exist only in the enum and the read-side action sets.
  - `REAPPLY_SANCTION`, `REBORROW_ALLOWED`, `MANDATE_OVERRIDE` and `ADMIN_FORCE_DISBURSE` fall to `default -> null` in `NotificationEventListener.mapAction`. The Disbursement Head is not told when a file reaches them through those routes, and a carried-over reborrow sanction sends no LOAN_SANCTIONED.
- **C-09 — Legacy fast-track skips eSign.**
  - `ApplicationFlowService.apply` on PRE_APPROVED goes to DISBURSEMENT_PENDING with no ESIGN check. `acceptOffer`, `mandateOverride` and `adminForceDisbursementPending` all require ESIGN PASS.
  - It is reachable only for PRE_APPROVED rows without a carried sanction, and only by customers with a repaid loan.
- **C-10 — Stale comments.**
  - `RepaymentService.outstandingAsOf` javadoc: "late penalty past the 1-day grace". Grace is per loan, 0 since V91.
  - `ApplicationFlowService.LATE_REPAYMENT_TOLERANCE_DAYS` javadoc: "one-day salary grace".
  - `VerificationInvalidationService` class javadoc lists 5 field mappings; `FIELD_TO_CHECK` has 9.
  - `FeatureFlagService` javadoc: "single exception: onboarding-paused". `IntakeSwitchService` and `PayuSwitchService` also write flags.
  - `ApplicationVerificationService.aadhaarDerived` javadoc: "the raw UID is never persisted". V75 stores the typed 12-digit number on `customer_profile.aadhaar`; only the DigiLocker-derived copy is masked.
- **C-11 — BRE rule cross-reference.** `rules.ts` rule 4's policy says "the loans relied on (rules 2 and 4)". The personal-loan and NBFC rules are ids **3 and 5**; rule 2 is the PAN–Aadhaar link.
- **C-12 — DRAFT edges.** CLAUDE.md §5 lists DRAFT → {KYC_PENDING, PRE_APPROVED, REJECTED, CANCELLED}. `ApplicationStatus.TRANSITIONS` also keeps DRAFT → REVIEW_PENDING (retired, unused).
- **C-13 — "Auto-assigned to a collections officer".** CLAUDE.md §5 reads as universal. In code it is one configured officer (`navix.collections.auto-assign-officer-id`), applied only while that officer is an ACTIVE Collection Executive. Otherwise nothing is assigned. There is no load balancing.
- **C-14 — In-memory limits.** CLAUDE.md §7 presents the sign-in throttle and the OTP caps as hard limits. `AttemptLimiter` (10,000 tracked keys) and the OTP stores in `BorrowerOtpService` and `EmailOtpService` are per-process maps. They reset on every deploy and are not shared across instances, so they hold only while one backend task runs.
- **C-15 — PAN format not validated at intake.** Docs describe PAN capture as validated. Server-side, `CustomerReviewService.normalizePan` only uppercases; the shape check lives in the frontend and the waitlist form (BR-ID-12).
