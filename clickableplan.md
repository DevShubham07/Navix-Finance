# Clickable loan details → full breakdown popup

## Context

Today the borrower sees loan economics in several places, but **none of them are
clickable** to reveal the full picture. The numbers are scattered:

- `/dashboard` — the navy **"Active advance" `LoanCard`** shows only *total outstanding*
  + due date (the card in the user's screenshot). It links to `/repay`, not a detail view.
- `/dashboard` — the **"Your applications"** rows link to `/loan/status`.
- `/loans` — each `LoanApplicationCard` renders an inline `LoanDetails` grid but the card
  isn't clickable.
- `/transactions` — ledger rows aren't clickable.
- `/repay` — has the richest breakdown, but only for the *one* active loan.

The user wants **every loan-detail surface to be clickable**, opening a **popup that shows
the complete calculation**: amount disbursed, fee/GST deducted, interest, late penalty,
total repayable, what's been paid (with dates), and what's still owed.

The user also asked for **authoritative interest & penalty values from the backend**
(itemized DTO) rather than deriving them on the client, so the split is exact and matches
`LoanMath`.

**Outcome:** one reusable `LoanDetailsDialog` component, fed by an extended
`OutstandingView` that now itemizes interest/penalty/paid, wired into all four surfaces.

---

## Part A — Backend: itemize interest & penalty on `OutstandingView`

All money is integer paise. `LoanMath` already has every per-component method
(`interestPaise`, `latePenaltyPaise`, `outstandingPaise`); we just need to surface them.

### A1. `RepaymentService` — add a breakdown method
File: `backend/navix-loan/src/main/java/com/navix/loan/service/RepaymentService.java`

- Add a small nested record `OutstandingBreakdown(long outstandingPaise, long interestPaise,
  long penaltyPaise, long verifiedPaise, Long settledAmountPaise)`.
- Add `outstandingBreakdownAsOf(Long loanId, LocalDate asOf)` that computes the **same**
  `interestDays` / `penaltyDays` / `verified` already in `outstandingAsOf` (lines 120–134),
  but also returns the individual components:
  - `interestPaise = loanMath.interestPaise(principal, interestDays)`
  - `penaltyPaise  = loanMath.latePenaltyPaise(principal, penaltyDays)`
  - `verifiedPaise = verified`
  - `outstandingPaise` = the existing settlement-capped net (reuse current logic)
  - `settledAmountPaise` = the approved settlement, if any.
- Refactor `outstandingAsOf` to delegate: `return outstandingBreakdownAsOf(loanId, asOf).outstandingPaise();`
  so there is a single source of truth for the formula (no logic duplicated/forked).

### A2. `LoanDtos.OutstandingView` — add fields
File: `backend/navix-loan/src/main/java/com/navix/loan/dto/LoanDtos.java`

- Extend the record with `long interestPaise`, `long penaltyPaise`, `long verifiedPaise`
  (append after `settledAmountPaise` to keep field order stable). Update the Javadoc to note
  the components are the *scheduled/accrued* amounts as of `asOf`; when `settledAmountPaise`
  is non-null, `outstandingPaise` is the settlement-capped figure (components may then exceed it).

### A3. `LoanController.outstanding` — populate the new fields
File: `backend/navix-loan/src/main/java/com/navix/loan/controller/LoanController.java` (lines 85–92)

- Replace the body to call `repaymentService.outstandingBreakdownAsOf(loanId, asOf)` and build
  `OutstandingView` from it (loanId, asOf|now, outstandingPaise, settledAmountPaise,
  interestPaise, penaltyPaise, verifiedPaise). This is the **only** `new OutstandingView(...)`
  producer in app code.

### A4. Backend tests
File: `backend/navix-loan/src/test/java/com/navix/loan/service/RepaymentServiceTest.java`

- Add a `outstandingBreakdownItemizesInterestAndPenalty` test covering an on-time loan
  (penalty = 0, interest = total − principal) and an overdue loan (penalty > 0, capped at
  30 days past the 1-day grace), reusing the existing `activeLoan()` fixture (principal
  ₹10,000, 27-day tenure). No `OutstandingView` constructor call sites exist in tests.

> No Flyway / schema change — everything is compute-on-read.

---

## Part B — Frontend: one reusable `LoanDetailsDialog`, wired to all surfaces

### B1. Extend the TS type
File: `frontend/src/lib/api/applications.ts` (`OutstandingView`, lines 126–133)

- Add optional `interestPaise?: number; penaltyPaise?: number; verifiedPaise?: number;`
  (optional keeps the client resilient if an older backend responds).

### B2. New component `LoanDetailsDialog`
New file: `frontend/src/components/borrower/loan-details-dialog.tsx` (`"use client"`)

- Props: `{ loanId: number | null; open: boolean; onClose: () => void }`.
- Reuse the modal shell `Dialog` / `DialogHeader` / `DialogTitle` from `@/components/ui`
  (`frontend/src/components/ui/dialog.tsx`) and line-item rows via `InfoRow` from
  `frontend/src/components/borrower/summary.tsx`.
- Fetch with React Query (enabled when `loanId != null`), reusing existing `borrowerApi`:
  `borrowerApi.loan(loanId)`, `borrowerApi.outstanding(loanId, todayISO())`,
  `borrowerApi.repayments(loanId)`. Reuse the cache keys already in use where sensible.
- Render sections (all via `paiseToINR`):
  - **Disbursal:** Principal · Processing fee (−) · GST 18% (−) · **Net disbursed** (amount sent).
  - **Cost:** Interest (`out.interestPaise`, fallback `total − principal`) · Late penalty
    (`out.penaltyPaise`, fallback `max(0, outstanding − total)`) · **Total repayable**.
  - **Status:** Paid so far (`out.verifiedPaise`) · **Outstanding / Pay today**
    (`out.outstandingPaise`) · Due date · Disbursed on · loan status badge. If
    `settledAmountPaise != null`, show the "Settlement — full & final" treatment from
    `repay/page.tsx`.
  - **Payments:** list each `PaymentView` with amount · method · txnRef · `paidOn` date ·
    status pill — reuse the `PaymentRow` markup pattern from
    `frontend/src/app/(borrower)/repay/page.tsx` (lines 325–345).
  - Footer: a "Repay / prepay" link to `/repay` for active loans.
- Handle loading / error states; show a graceful message when `loanId` is null.

### B3. Wire the four surfaces (each holds `useState<number|null>` for the open loanId)

1. **Dashboard active-loan card** — `frontend/src/app/(borrower)/dashboard/page.tsx`
   - `LoanCard` (lines 330–387): add a secondary **"View full breakdown"** text button
     (distinct from the primary `/repay` CTA) that calls an `onViewDetails` prop. `DashboardPage`
     passes `loan.id` and opens the dialog. Render `<LoanDetailsDialog>` once in `DashboardPage`.
2. **Dashboard "Your applications" rows** — same file, `ApplicationsHistory` (lines 238–256)
   - For rows with `app.loanId != null`, change the `<Link>` to a `<button>` that opens the
     dialog for that `loanId`; keep `/loan/status` navigation for rows without a loan yet.
3. **Past loans page** — `frontend/src/app/(borrower)/loans/page.tsx`
   - In `LoanApplicationCard` (lines 105–122), when `app.loanId != null` make the card open the
     dialog (clickable card or a "View full details" button). The inline `LoanDetails` grid can
     remain as a preview; the popup is the full view. Render `<LoanDetailsDialog>` at page level.
4. **Transactions page** — `frontend/src/app/(borrower)/transactions/page.tsx`
   - Make each ledger row clickable; open the dialog for that row's `loanId` (the `LedgerRow`
     already carries the loan id, lines 10–17 / 39–68).

> Reuse note: a single `<LoanDetailsDialog>` instance per page is enough — drive it from the
> page's `selectedLoanId` state. Avoid nesting a `<button>` inside an `<a>`/`<Link>` (swap the
> element, don't nest interactive controls).

---

## Verification

1. **Backend unit tests** — `cd backend && ./mvnw test` (Java 21). Confirm the new
   `outstandingBreakdownAsOf` assertions pass and the existing repayment/flow suite stays green.
2. **Frontend typecheck/lint** — `cd frontend && npx tsc --noEmit && npx next lint`
   (the repo notes `npm run build` static-prerender is environmentally flaky; use tsc + lint +
   `npm run dev`).
3. **End-to-end (manual, `npm run dev` + backend up, seeded via `scripts/populate-demo-data.ps1`):**
   - Log in as borrower (mobile `9819000001`, OTP `123456`).
   - `/dashboard` active card → click **View full breakdown** → popup shows net disbursed, fee,
     GST, interest, penalty, total, paid-so-far, outstanding, due date, and the payments list
     with dates/status. Verify Escape + overlay-click close.
   - Repeat from a **"Your applications"** row, a `/loans` card, and a `/transactions` row — all
     open the same popup for the right loan.
   - Cross-check the popup's interest/penalty against the `/repay` page for the active loan (must
     agree, since both now read the itemized `outstanding`).
   - Check an **overdue** persona shows a non-zero late penalty, and a **closed** loan shows
     0 outstanding with the full payment history.
