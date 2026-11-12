# 04 — Staff: collections, settlements, mandates, NACH debits and money in

This file covers every **staff** use case that happens after a loan is paid out: the Loans register, the DPD bucket worklist, the Collection Head desk and assignment, case work by a Collection Executive, settlements, skip trace, the Mandates & debits console and the per-customer Mandate tab, NACH debits and refunds owed, the Accountant's money-in queues (manual repayments, collections payments, PayU review), the transactions ledger, payment reminders, and how the outstanding balance is computed.
Every rule below comes from the code: frontend `app/staff/{loans,collections,accounting}/**`, `components/staff/{collections/**,mandates/**,customer-360/{collections,mandate,repayment}-tab.tsx,collection-payments,collections-assign,skip-trace-panel,payu-review-queue,repayment-verify-queue,admin-log-payment}.tsx`, `lib/{collections,loans,calc}/**`; backend `navix-collections` (`CollectionsService`, `CollectionsWorklistQuery`, `SettlementService`, `CollectionPaymentService`, `ManualDebitService`, `CollectionAutoAssigner`, `DpdCalculator`), `navix-loan` (`LoanRegisterService/Query`, `MandateService`, `DebitService`, `MandateDashboardService`, `RepaymentService`, `TransactionService`, `PayuService`, `PayuReviewService`), `navix-app` (`scheduler/*`, `skiptrace/*`, `dashboard/DashboardService`). Where docs disagree, the code wins and the disagreement is listed in §Z.
Money is integer paise; "IST" = Asia/Kolkata. Every business date (DPD, due, paid-on) is an IST calendar date even though the server clock runs UTC.
Use-case ids are `UC-S2-nn`. Credit and disbursement are file 03; borrower-side repay/PayU/mandate screens are file 02. Endpoints are named only where they orient the reader; payload shapes are deliberately omitted.

## Table of contents

- [0. Roles, permissions and shared definitions](#0-roles-permissions-and-shared-definitions)
- [A. Loans register and DPD worklist](#a-loans-register-and-dpd-worklist)
  - UC-S2-01 Browse the Loans register
  - UC-S2-02 Open a loan from any register (customer pop-up) and ADMIN "Log payment"
  - UC-S2-03 Work the DPD bucket worklist — Collection Head / Admin view
  - UC-S2-04 Work "My DPD cases" — Collection Executive view
  - UC-S2-05 Export the worklist / agency-handover file (ADMIN only)
- [B. Collection Head desk and assignment](#b-collection-head-desk-and-assignment)
  - UC-S2-06 "Needs assignment" panel
  - UC-S2-07 "Team today / Team tomorrow" panel
  - UC-S2-08 Assign or reassign one loan to a collections executive
  - UC-S2-09 Bulk-assign selected loans
  - UC-S2-10 Auto-assign every newly activated loan (system)
  - UC-S2-11 Open a collection case explicitly
- [C. Case work](#c-case-work)
  - UC-S2-12 Read a loan's Collections tab
  - UC-S2-13 Log an interaction / promise to pay
  - UC-S2-14 Record a part or full payment taken in the field
  - UC-S2-15 Record a settlement payment
  - UC-S2-16 Collection Head approves a settlement payment
  - UC-S2-17 Accountant validates or rejects a collections payment
- [D. Settlements and hardship](#d-settlements-and-hardship)
  - UC-S2-18 Propose a settlement
  - UC-S2-19 Settlements register
  - UC-S2-20 Approve a settlement
  - UC-S2-21 Reject a settlement
  - UC-S2-22 Settlements dashboard (page exists, not wired)
  - UC-S2-23 Hardship / revised repayment plan (not built)
- [E. Skip tracer](#e-skip-tracer)
  - UC-S2-24 Run a skip trace and read its history
- [F. Mandates](#f-mandates)
  - UC-S2-25 Mandates & debits console — Mandates tab
  - UC-S2-26 Mandates & debits console — Debits tab
  - UC-S2-27 Mandate tab: read the mandate and "Re-check with bank"
  - UC-S2-28 Invite the customer to set up auto-repayment
  - UC-S2-29 Override a BANK_UNSUPPORTED mandate
  - UC-S2-30 Raise the mandate ceiling for new registrations (ADMIN)
  - UC-S2-31 Cancel a pending / stale mandate attempt
  - UC-S2-32 Cancel the ACTIVE mandate (with reason)
  - UC-S2-33 Change the mandate account
- [G. NACH debits](#g-nach-debits)
  - UC-S2-34 Scheduled NACH sweep — DPD 1, 3, 5, 9 (system)
  - UC-S2-35 Debit now (manual NACH presentation)
  - UC-S2-36 Check status / cancel one debit
  - UC-S2-37 Refunds owed (NACH overpayments)
- [H. Money in and the ledger](#h-money-in-and-the-ledger)
  - UC-S2-38 Accountant verifies or rejects a manual repayment
  - UC-S2-39 Resolve a PayU payment in review (and the 5-minute reconcile)
  - UC-S2-40 Transactions ledger
- [I. Schedulers and the arithmetic](#i-schedulers-and-the-arithmetic)
  - UC-S2-41 Payment reminders (system)
  - UC-S2-42 Outstanding balance — computation and worked examples
- [Z. Contradictions and gaps found](#z-contradictions-and-gaps-found)

---

## 0. Roles, permissions and shared definitions

### 0.1 Who holds which collections/money token (`lib/auth/rbac.ts`)

| Token | Held by (real role) | Admin *working role* ("Admin" in the switcher) |
|---|---|---|
| `loan:register` | COLLECTION_HEAD, ADMIN | yes |
| `collections:manage` | COLLECTION_HEAD, ADMIN | **no** |
| `collections:interact` | COLLECTION_EXECUTIVE, ADMIN | **no** |
| `collections:overview` | COLLECTION_HEAD, ADMIN | yes (read-only "Total settlements") |
| `mandate:debit` | COLLECTION_HEAD, COLLECTION_EXECUTIVE, ADMIN | yes |
| `mandate:override` | DISBURSEMENT_HEAD, ADMIN | yes |
| `loan:activate` | ACCOUNTANT, ADMIN | no |
| `staff:manage` / `customer:manage` | ADMIN | yes |
| `verification:retry` | ADMIN | yes |

Consequences that matter in every use case below:
- Each working role carries **only its own** tokens (`effectivePermissions`). An ADMIN in the "Admin" working role sees the collections pages read-only; to assign, approve a settlement or work a case they switch to "Collection Head" / "Collection Executive". **Authorization on the server stays on the real JWT role** (ADMIN passes every `requireOneOf` check); the working role only scopes lists.
- **COLLECTION_HEAD does not hold `collections:interact`.** In the Head working role the Collections tab hides "Log interaction", "Propose settlement" and "Record a payment" even though the backend accepts all three from a Head. A Head who wants to do field work switches to the "Collection Executive" working role — which then scopes them to cases assigned to *their own* staff id (see §0.3).
- Exports (`ExportMenu`) render **only for an ADMIN in the “Admin” working role** (the check reads the working role) across the whole console (data-governance decision). No other role, and no ADMIN working as another role, sees a download button anywhere in this file.

### 0.2 DPD and buckets (computed on read, never stored)

`DPD = max(0, today_IST − due_date)` in whole days (`DpdCalculator.daysPastDue`, SQL twin in `CollectionsWorklistQuery`). DPD 0 covers both "due today" and "not yet due". A closed loan's DPD in the Loans register is frozen at its close date.

| Bucket enum | Label on screen | DPD | Card/pill tone |
|---|---|---|---|
| `UPCOMING` | Upcoming | ≤ 0 (due today or later) | sky |
| `T0_T7` | 1–7 DPD | 1–7 | amber |
| `T8_T30` | 8–30 DPD | 8–30 | orange |
| `T30_T60` | 31–60 DPD | 31–60 | red |
| `T60_T90` | 61–90 DPD | 61–90 | violet |
| `T90_PLUS` | 90+ DPD | > 90 | navy |

The **collectible set** (worklist, NACH sweep, bucket cards): loans with status `ACTIVE`, `OVERDUE` or `IN_COLLECTIONS` **and** `due_date ≤ today + 7` (`LoanDirectory.PRE_DUE_WINDOW_DAYS = 7`). A loan due 8+ days from now is in no bucket at all; `UPCOMING` therefore means "due within the next 7 days, today included". Loans in `CLOSED`, `REPAID`, `WRITTEN_OFF` never appear.

### 0.3 Collection case and scoping

- A **collection case** (`collection_case`: loan id, assigned officer id, created at) is a bookkeeping row created behind the first real action — assign, or an explicit "Open collection case". Nobody needs to "start collections" by hand. One case per loan is the intent (`openCase` is idempotent) but not a DB constraint; readers take the newest.
- **Scoping rule:** when the caller's *effective* (working) role is `COLLECTION_EXECUTIVE`, every list (worklist, bucket cards, facets, settlements, collection payments) is restricted in SQL to cases whose `assigned_officer_id` = the caller's staff id. If the id cannot be resolved the list is **empty** (fails closed). Every single-case read/write by such a caller on someone else's case fails `CASE_NOT_ASSIGNED` "This case is not assigned to you".
- **Readers of collections data** (`READER_ROLES`): ADMIN, COLLECTION_HEAD, COLLECTION_EXECUTIVE, ACCOUNTANT, DISBURSEMENT_HEAD, CREDIT_HEAD. Anyone else → `FORBIDDEN_ROLE` "Your role cannot view collections data". DSA is always refused ("DSAs cannot view customer data" / "…collections cases").
- **Opening a case** flips the loan to `IN_COLLECTIONS` **only if it is already past due** (`today > due_date`) and currently `ACTIVE`/`OVERDUE`; a pre-due case leaves the loan `ACTIVE` so an on-time borrower is never branded delinquent.

---

## A. Loans register and DPD worklist

### UC-S2-01 Browse the Loans register
- **Actor / working role / permission:** Collection Head or Admin; `loan:register`. Backend: `COLLECTION_HEAD` (ADMIN bypasses) else `FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD".
- **Entry point:** sidebar *Collections → Loans* (`/staff/loans`), with sub-chips Active / Overdue / Closed (`?seg=`). Global search deep link `?q=<id>&open=<id>`.
- **Preconditions:** none; the register lists every disbursed loan ever, open or closed.
- **Screen:**
  - Header "Loans" — "Every disbursed loan, past and present — search, filter and sort the full register." Refresh button; ADMIN-only export menu ("Download all loans (CSV)", file base `dhanboost-loans`); a pill with the staffer's role label.
  - **Segment chips** `All (n)`, `Active (n)`, `Overdue (n)`, `Closed (n)`. Segment by *effective* loan status: Overdue = `OVERDUE` or `IN_COLLECTIONS`; Closed = `REPAID` or `CLOSED`; Active = everything else. Effective status: an `ACTIVE` loan whose due date is before today reads `OVERDUE`. Counts follow the search box and the date window but **ignore column filters** (they are navigation); All = sum of the three.
  - **Search** placeholder "Borrower, mobile, PAN, loan or application ID" (server matches name, PAN, mobile, loan id — see §Z). **Date window** "Disbursed on" (presets + custom range, kept in the URL as `win/wfrom/wto`).
  - **Filter bar:** pinned Status, DPD (bucket checklist), Principal (amount bands), Officer, Outstanding (amount bands), Cycle (cycle bands); the rest under "More filters". Money bounds are typed in rupees. Status/Officer are server-faceted checklists; the officer checklist is keyed on staff id and shows "Unallocated" for blank.
  - **Columns:** S.No. · Loan (`#id`, sticky, opens the pop-up) · Borrower (name, then "mobile · PAN") · Signals (trust stars) · Sanctioned · Disbursed · Due (red when DPD > 0) · Closure date · Cycle ("1st", "2nd"… — the loan's ordinal among that customer's loans by disbursal date) · Principal · Net disbursed · Repayable · Outstanding (bold; compute-on-read, see UC-S2-42) · DPD · Status (badge, tone by segment: Active green, Overdue red, Closed grey; label is the status in words e.g. "In Collections") · Officer (or "Unallocated") · Open (sticky; ADMIN also sees "Log payment").
  - **DPD cell:** "`N`d" when DPD > 0 (red, semibold) — shown even on a closed loan (frozen); "due today" when the due date is today (IST); "not due" when it is in the future; "—" for closed/no date/stale.
  - **Bucket of a row:** only an open loan (`ACTIVE`/`OVERDUE`/`IN_COLLECTIONS`) has one; closed rows are blank for the DPD filter.
  - **Date grouping:** when sorted by Due, Sanctioned, Disbursed or Closure date, rows on the page are grouped under collapsible header rows "<date> · n loans" ("Date unknown" when null). No grouping for amount/DPD sorts.
  - Default sort **Due, descending**. Server paging; page size capped at 500.
  - States: skeleton (16 cols) while loading or while a placeholder page is empty; `ErrorState` with Retry; empty "No loans[ for “q”][ in <segment>][ in the selected date range]."; no-permission → `NoAccessNotice` (no request is fired until the session confirms `loan:register`).
- **Main flow:**
  1. Staffer opens the page → system loads one page plus three 1-row count queries.
  2. Staffer picks a chip/window/search/filter → page resets to 1, server re-filters.
  3. Staffer clicks a loan → UC-S2-02.
- **Business rules:** outstanding and officer sort/filter run in Java over the SQL-narrowed ids (they are derived), everything else in SQL. Search, segment, window narrow first; column filters are ANDed last.
- **Alternate & error flows:** non-Head/Admin calling the API → `FORBIDDEN_ROLE`. A truncated status facet degrades to a text filter; the officer column searches the server instead.
- **Postconditions:** read-only.
- **Edge cases / not allowed:** no bulk actions, no assignment from this register (assignment lives on the worklist).

### UC-S2-02 Open a loan from any register (customer pop-up) and ADMIN "Log payment"
- **Actor / working role / permission:** any staff who can see the register row; "Log payment" is **ADMIN only** (UI: working role must be ADMIN, i.e. an ADMIN in the “Admin” working role; backend `POST /api/loan/{id}/repayments/admin-record` → `requireRole("ADMIN")`).
- **Entry point:** "Open" / loan number on the Loans register (opens on the **Loan** tab), on the DPD worklist (opens on the **Repayment** tab), `?open=<loanId>` deep links, and the legacy `/staff/collections/<loanId>` URL (redirects to `/staff/collections?open=<loanId>&tab=collections`; a non-numeric legacy case UUID lands on the plain worklist).
- **Preconditions (Log payment):** loan status is one of `ACTIVE`, `OVERDUE`, `IN_COLLECTIONS`, `DISBURSED`, `DEFAULTED` (otherwise the button is hidden).
- **Screen (Log payment dialog "Log a payment · Loan #id"):** sub-copy "For money already received. Recorded and verified in one step — no accountant queue."
  - *Date the borrower paid* (date, required; min = disbursal date, max = today) — changing it re-fetches the balance **as of that date** and re-prefills the amount.
  - *Amount (₹)* (number, min 1) — prefilled with the balance owed on that date (rounded rupees) until the operator types.
  - *Method* UPI / Bank transfer. *Transaction reference (optional)*. *Payment screenshot (optional)* (image/*, .pdf; uploaded to S3 under the repayment-proof category).
  - Right panel "If they paid on <date>": the loan breakdown as of that date, then either "This settles the loan — it closes on this date and the borrower is notified." or "Part payment — ₹X would remain, and interest keeps running."
  - Buttons Cancel / Record payment (disabled until amount > 0 and a date).
- **Main flow:**
  1. Admin picks date and amount.
  2. Record payment → system records the payment (`BANK_TRANSFER`/`UPI`, paid-on = chosen date) and verifies it in the same call → outstanding recomputed as of the payment date → loan closes if 0 → toast "Payment recorded", every register/queue cache refreshed.
- **Business rules:** paid-on cannot be after tomorrow (`INVALID_PAID_ON` "Payment date cannot be in the future"; tomorrow is clamped to today) nor before disbursal ("Payment date cannot be before the loan was disbursed"). Staff are not bound by the borrower's 3-day back-date limit. Idempotent on transaction reference per loan (a repeat txn ref returns the existing payment). Closed/REPAID loan → `LOAN_SETTLED` "Loan is already settled". Amount ≤ 0 → `INVALID_AMOUNT`.
- **Postconditions:** VERIFIED payment; `REPAYMENT_RECORDED` (in-app to accountants + borrower, WhatsApp) and `REPAYMENT_VERIFIED` (borrower in-app/SMS/WhatsApp, "Your payment of {amount} is confirmed. Outstanding: {outstanding}."); on close `LOAN_CLOSED` and the application moves to `CLOSED`.
- **Edge cases / not allowed:** collapses maker-checker by design — ADMIN only. ACCOUNTANT can never record a payment (they are the checker); collections roles record through UC-S2-14 instead.

### UC-S2-03 Work the DPD bucket worklist — Collection Head / Admin view
- **Actor / working role / permission:** Collection Head (real or working), ADMIN; nav gate any of `collections:interact`, `collections:manage`, `collections:overview`. Bulk select, inline assign and the Head desk need `collections:manage` (so an ADMIN in the "Admin" working role sees the list read-only).
- **Entry point:** sidebar *Collections → DPD buckets (all loans)* (`/staff/collections`); `?bucket=<ENUM>` opens one bucket; `?q=` from global search; `/staff/collections/overview` redirects here.
- **Preconditions:** none.
- **Screen:**
  - Title "<page title> · <All loans | bucket label>"; subtitle "Every live loan in this bucket — past due, or falling due within the week. DPD is computed on read." plus the active-filter description. Refresh; ADMIN-only export (UC-S2-05).
  - **Head desk** above the cards (UC-S2-06, UC-S2-07) — only with `collections:manage`.
  - **Bucket cards** (7, act as the bucket switcher): "All loans" (teal) then the six buckets (§0.2 tones), each showing **count** and **summed outstanding** (compute-on-read). Cards are over the caller's whole scoped collectible set and **ignore** search, date window and column filters; polled every 30 s. The open card is full strength with a ring; others dimmed until hover. Switching a card keeps every other URL parameter and resets to page 1.
  - **Toolbar:** search ("Customer ID, name, mobile, PAN, employer, officer or loan #" — matches name, PAN, mobile, employer, officer name, customer id, loan id); date window on the **due date** (All / presets / custom); when rows are ticked: "n selected on this page" + "Assign selected".
  - **Filter bar** (column filters, "Clear" drops column filters only).
  - **Columns:** S.No. (left red bar when DPD > 0) · ☐ (Head/Admin only; header checkbox selects the page) · Customer ID · Borrower (+ pill **"Not yet due"**, hover "Not yet due — a courtesy follow-up, not a delinquency", on pre-due rows) · Signals · Mobile · PAN · Loan · Principal · Outstanding (bold) · Due (red when late) · DPD · Bucket (coloured pill) · Stage (compact stage signal) · Employer · Salary · Credit exec (who decided credit, from the event trail, "—" if not recorded) · Disbursed by · Collections exec (inline picker for Head/Admin, plain name otherwise) · Actions (sticky: ADMIN "Log payment", "Open →").
  - **DPD cell text:** the server DPD when > 0 (red); otherwise "due today" (navy) / "due in N d" (muted) by IST calendar; "0" when no date or stale.
  - **Sortable:** Customer ID, Borrower, Loan, Principal, Outstanding, Due, DPD, Bucket (sorts by severity), Employer, Salary, Credit exec, Disbursed by, Collections exec. **Filterable:** everything except the identity columns (customer id, loan id, mobile, PAN). Bucket and Collections exec are server checklists ("Unallocated" = no officer, keyed on officer id); Credit exec / Disbursed by checklists include "Not recorded".
  - Default sort **DPD descending** (most overdue first); unknown sort key also falls back to that. Page size default 25, max 200. Register polls every **8 s**.
  - States: skeleton (19/20 cols); `ErrorState` + Retry; in-table empty "No loans in <scope> match the column filters." or "No loans in <scope>[ for “q”][ in the selected date range]." (headers stay so a filter can be cleared).
- **Main flow:**
  1. Head opens the page → cards + page 1 (most overdue first).
  2. Clicks a card → register narrows to that bucket.
  3. Picks an officer in a row's Collections exec cell → UC-S2-08; or ticks rows → UC-S2-09.
  4. Clicks Open → customer pop-up on the Repayment tab (UC-S2-12 for the Collections tab).
- **Business rules:** the worklist is **loan-driven**, not case-driven: every collectible loan appears whether or not a case exists; case fields are blank until one does. Derived columns (outstanding, credit exec, disbursed by) filter/sort in Java after SQL narrowing.
- **Alternate & error flows:** DSA/borrower → `FORBIDDEN_ROLE`; non-reader role → "Your role cannot view collections data".
- **Postconditions:** read-only except via the assign/payment actions.
- **Edge cases / not allowed:** selection is page-scoped by design — rows paged or filtered away are never included in a bulk assign. There is no "unassign" (the picker's blank option is disabled).

### UC-S2-04 Work "My DPD cases" — Collection Executive view
- **Actor / working role / permission:** Collection Executive (real, or a Head/ADMIN in that working role); `collections:interact`.
- **Entry point:** sidebar item relabelled **"My DPD cases"** (`/staff/collections`); also `/staff/applications` → *My repayment book* tabs **Overdue / Active / Closed (repaid)** (notes: Overdue "Your loans past the due date — the 2%/day late penalty is accruing (capped at 30 days; older loans had a 1-day grace)."; Active "Your loans disbursed and still inside the due date. Interest is accruing at 1%/day; no penalty yet."; Closed "Cases assigned to you whose loan has been fully repaid. Read-only.").
- **Preconditions:** loans must be assigned to this executive (by the Head, bulk assign or auto-assign).
- **Screen:** same register as UC-S2-03 except: no Head desk, no checkboxes, Collections exec column read-only, bucket cards count only this executive's loans, every facet checklist is scoped (no names from other officers' books).
- **Main flow:**
  1. Executive opens → sees only assigned collectible loans, most overdue first.
  2. Opens a row → pop-up; works the Collections tab (UC-S2-13..15) and, with a live mandate, the Mandate tab (UC-S2-35).
- **Business rules:** scope is applied in SQL first; column filters can only narrow (filtering on another officer's id returns nothing). Staff id unresolvable → empty list. A Head/ADMIN working as Executive is scoped to cases assigned to their own staff id.
- **Alternate & error flows:** opening someone else's case by URL → Collections tab shows "This case is assigned to another officer."
- **Postconditions:** read-only.
- **Edge cases / not allowed:** an executive cannot open a case on an unassigned loan (`FORBIDDEN_ROLE` — starting a case is the Head's call) and cannot assign.

### UC-S2-05 Export the worklist / agency-handover file (ADMIN only)
- **Actor / working role / permission:** ADMIN in the “Admin” working role (export menu renders for no other role or working role; the handover read `GET /api/customers/collection-handover` is ADMIN-only because it returns PAN and Aadhaar in full).
- **Entry point:** export menu on `/staff/collections` — "this page" or **"Download all loans (CSV)"**; file base `collections-<bucket>`; title "Collections — <scope>". Also PDF.
- **Preconditions:** none.
- **Screen / content (column order, for an outside collection agency):** Borrower, PAN, both addresses, Mobile, contact block, identity block (full Aadhaar/PAN), references 1–2, Loan, Principal (₹), Outstanding (₹), Disbursed on, Due date, DPD, Bucket, Pre-due (Yes/No), Loan status, sanction block (amount, tenure…), bank block (disbursal bank, salary bank, penny-drop RRN…), Employer, Monthly salary (₹), employment block, company/EPFO + work-email match, PAN check, DigiLocker, selfie & eSign, credit (incl. star rating), Customer ID, Credit exec, Disbursed by, Collections exec, Case ID, Case opened, Signup date, Applications, Loans, T&C accepted at.
- **Main flow:**
  1. Admin opens the menu → system loads the export-only enrichment for the visible page (button disabled while loading).
  2. "Download all" → system re-requests every page of the **current view** (same search, bucket, window, column filters, sort) in pages of 200 (runaway guard 250 pages), de-duplicates by loan (the register polls), enriches, writes the file.
- **Business rules:** CSV is RFC-4180, formula-neutralised, with BOM; a failed enrichment leaves those cells "—".
- **Postconditions:** none in the system (export logged by the platform-wide export rules).
- **Edge cases / not allowed:** a Collection Head cannot produce the handover file (see §Z).

---

## B. Collection Head desk and assignment

### UC-S2-06 "Needs assignment" panel
- **Actor / working role / permission:** Collection Head / ADMIN with `collections:manage` (renders nothing otherwise, including while the session loads).
- **Entry point:** top-left panel above the bucket cards on `/staff/collections`. Collapsible; collapsed state remembered per staffer in localStorage `navix-staff-head-desk:<staffId>`.
- **Preconditions:** none.
- **Screen:** title "Needs assignment · <server total>"; ⓘ "Live loans in the worklist — past due or due within the week — that no collections executive owns yet. Pick an officer here and the row leaves the list." Table Borrower (name + mobile) · Loan · DPD (red when overdue, else "due today"/"due in N d") · Outstanding · Assign (the same inline picker as the register). Up to **200** rows, ordered most overdue first then due soonest; footer "Showing the 200 most overdue of N; the rest follow as these are assigned." when more. Empty "Every live case has an officer." Polled every 30 s.
- **Main flow:** Head picks an officer on a row → UC-S2-08 → row disappears on refresh.
- **Business rules:** "unassigned" = the register's own `officerName = Unallocated` filter (no case, or a case with no officer), inside the Head's scope.
- **Postconditions:** as UC-S2-08.

### UC-S2-07 "Team today / Team tomorrow" panel
- **Actor / working role / permission:** Collection Head / ADMIN (`collections:manage`); backend `DashboardScope.require("ADMIN","COLLECTION_HEAD")`.
- **Entry point:** top-right panel above the bucket cards; pill tabs **Today / Tomorrow**.
- **Screen:** ⓘ "One row per active collections executive (a Head appears when they hold figures). Today: interactions logged, promises falling due, verified part payments, verified rupees collected and loans closed. Tomorrow: promises and loans falling due. A count narrows the register to that officer."
  - Today columns: Officer (with "Head" tag) · Calls logged · Promises due (count · ₹) · Part payments (count · ₹) · Collected (₹, verified) · Closed.
  - Tomorrow columns: Officer · Promises due · Falling due.
  - A **Total** row; every non-zero count is a button that sets the register's Collections exec filter to that officer. Empty "No active collections executives." Polled every 30 s. The date is the IST day (today or +1).
- **Business rules:** every ACTIVE Collection Executive always gets a row (a zero day is information); ACTIVE Collection Heads only when any of their figures is > 0. Rows sorted by name. ADMIN reads the whole book; a Head their team.
- **Postconditions:** read-only.

### UC-S2-08 Assign or reassign one loan to a collections executive
- **Actor / working role / permission:** Collection Head / ADMIN. UI `collections:manage`; backend real role `COLLECTION_HEAD` or `ADMIN` else `FORBIDDEN_ROLE` "Only the Collection Head can assign a case to an officer".
- **Entry point:** (a) the Collections exec cell on the DPD register and Needs-assignment panel (inline select); (b) the "Assign / Reassign" button + dialog on other queues ("Assign collections · Loan #id", "Currently with X." or "Only ACTIVE collections executives can be assigned."); (c) the "Assign officer" section of the Collections tab ("Officer (active executives)" — options "Name (ROLE)").
- **Preconditions:** loan is live (an unassigned loan with no case must not be `CLOSED`/`REPAID`/`WRITTEN_OFF`).
- **Screen:** select with disabled placeholder "Assign to…" / "Reassign to…", **"Assign to me"** on top (the Head's/Admin's own id), then every ACTIVE Collection Executive; the current holder is kept in the list even if no longer active. "Loading officers…", "No active collections executives.", or an error with Retry. Inline cell shows a spinner while saving and holds the new choice until the 8 s poll confirms it.
- **Main flow:**
  1. Head picks an officer.
  2. System: look up the case by loan; if none, open one (UC-S2-11 rules); assign officer.
  3. Toast "Officer assigned"; worklist, case lists and dashboard queues refresh.
- **Business rules:** the assignee must be an ACTIVE `COLLECTION_EXECUTIVE` (`INVALID_OFFICER` "The assignee must be an ACTIVE collections executive") — except **self-assign** by a Head/ADMIN, which is allowed. Opening the case on demand flips the loan to `IN_COLLECTIONS` only if already past due. Reassign keeps the case and all its history.
- **Alternate & error flows:** loan already settled → `LOAN_NOT_COLLECTIBLE` "This loan is already settled"; the inline cell reverts to server truth and shows the error under the select.
- **Postconditions:** `collection_case.assigned_officer_id` set. A freshly created case publishes `COLLECTION_CASE_OPENED` (in-app to Collection Heads and Executives: "A collections case was opened on loan #{loanId}."); a reassign is silent. No `application_event` row (case assignment is not on the application trail).
- **Edge cases / not allowed:** no unassign endpoint, so "unassign" is never offered.

### UC-S2-09 Bulk-assign selected loans
- **Actor / working role / permission:** Collection Head / ADMIN (`collections:manage`; dialog renders nothing otherwise).
- **Entry point:** tick rows on the DPD register → "Assign selected".
- **Screen:** dialog "Assign n loan(s)?" — "Each selected loan is assigned to this executive, up to 4 at a time. Only ACTIVE collections executives can be assigned." Same officer select (with "Assign to me"). While running: "x / n assigned[, f failed]". Result: "x assigned, f failed." and a per-loan list (Loan #id — "Assigned" or the failure reason, "No result recorded" if missing). Buttons Cancel / Assign, then Done.
- **Main flow:**
  1. Head selects rows (page-scoped) and an officer.
  2. System runs the UC-S2-08 sequence per loan with concurrency **4**; one failure does not stop the rest.
  3. Caches refresh once at the end; selection clears; dialog stays open with the summary.
- **Business rules:** deliberately a client loop over the audited per-case endpoint (no batch endpoint), with the pre-lookup kept so a loan whose status changed is not re-flagged blindly.
- **Postconditions:** as UC-S2-08 for each successful loan.

### UC-S2-10 Auto-assign every newly activated loan (system)
- **Actor:** system (`CollectionAutoAssigner`, after-commit listener on application transitions).
- **Trigger:** an application transitions to **`ACTIVE`** with a loan id (i.e. disbursal accepted, `DISBURSED → ACTIVE`).
- **Preconditions:** `navix.collections.auto-assign-officer-id` (SSM `/navix/<env>/navix/collections/auto-assign-officer-id`) is set **and** that staffer is an ACTIVE `COLLECTION_EXECUTIVE`; the loan has no case yet.
- **Main flow:** system writes a case row with that officer. Nothing else.
- **Business rules:** does **not** call `openCase`: no status flip, no `COLLECTION_CASE_OPENED` notification. Every new loan goes to the **one** configured officer; the Head rebalances by reassigning. If unset/inactive the step is skipped (warning logged) and the loan shows "Unallocated" in Needs assignment. A failure never affects the disbursal (separate transaction).
- **Postconditions:** the loan appears in that executive's "My DPD cases" once it enters the 7-day pre-due window.

### UC-S2-11 Open a collection case explicitly
- **Actor / working role / permission:** Collection Head / ADMIN (`collections:manage` in UI; backend `requireCollectionsDesk` then `requireManager` for a new case).
- **Entry point:** Collections tab of the pop-up when the loan has no case: empty state "No collection case is open on this loan." with button **"Open collection case"** (others see hint "The Collection Head opens and assigns cases."). Also implicitly from UC-S2-08.
- **Main flow:** click → case created (idempotent: an existing case is returned unchanged) → toast "Collection case opened".
- **Business rules:** loan must resolve and must not be `CLOSED`/`REPAID`/`WRITTEN_OFF` (`LOAN_NOT_COLLECTIBLE`). Past-due `ACTIVE`/`OVERDUE` loan → status `IN_COLLECTIONS`.
- **Postconditions:** new case → `COLLECTION_CASE_OPENED` (in-app, Heads + Executives). Viewing a tab never creates a case.

---

## C. Case work

### UC-S2-12 Read a loan's Collections tab
- **Actor / working role / permission:** any collections reader (§0.3); actions inside need `collections:interact` (log, propose, record) or `collections:manage` (assign, open case).
- **Entry point:** customer pop-up → **Collections** tab (from the DPD register's Open, `?tab=collections`, the legacy case URL, or the customer 360 page).
- **Preconditions:** the application has a loan, else "No loan on this application yet — nothing to collect."
- **Screen:**
  - **Collections** section (tone: green when paid up, red when DPD ≥ 30, amber when DPD > 0) with a loan selector (customers with several loans) and a field grid: **DPD** ("N days" + bucket badge; a closed loan's DPD stops at its close date), **Assigned officer** (name or "Not yet assigned"), **Case status** ("Open" / "No case"), **Promise to pay** (the newest PTP date anyone logged; red caption "overdue" when it is past and the loan is not CLOSED/REPAID), **Last interaction** (timestamp), **Loan cycle** ("1st advance", "2nd advance"…).
  - Then one of: skeleton; "This case is assigned to another officer." (executive on someone else's case); error + Retry; **no case** → UC-S2-11 empty state; **case open** → *Interactions* (UC-S2-13), *Assign officer* (Head/Admin, UC-S2-08), *Propose settlement* (UC-S2-18), *Payments* ("Payments collected" list + "Record a payment" card, UC-S2-14/15; footnote linking to the Repayment tab for verified repayments and the ledger).
  - Always last: "Calls about this loan (n)" and "Other customer calls (n)" — the telecalling call logs for this customer, split by loan tag.
- **Business rules:** reading never creates a case. The by-loan case lookup is staff-only (a borrower walking loan ids gets `FORBIDDEN_ROLE`); a loan with no case is a normal 404 rendered as "No case".
- **Postconditions:** read-only.

### UC-S2-13 Log an interaction / promise to pay
- **Actor / working role / permission:** Collection Executive (own cases), Head or ADMIN. UI `collections:interact`; backend `COLLECTION_HEAD`, `COLLECTION_EXECUTIVE` or `ADMIN` (`FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD, COLLECTION_EXECUTIVE or ADMIN") + case ownership for an executive.
- **Entry point:** Collections tab → *Interactions* section (badge = number logged).
- **Preconditions:** a case exists on the loan.
- **Screen (inputs on one line):**
  - *Type* select: `CALL`, `SMS`, `EMAIL`, `VISIT` (default CALL).
  - *Outcome* select: `CONNECTED`, `NO_ANSWER`, `PROMISE_TO_PAY`, `PAID`, `DISPUTED` (default CONNECTED).
  - *Promise-to-pay* (date) — shown only for PROMISE_TO_PAY; if empty a warning "No promise-to-pay date — it will be logged without one." (warning, not a block).
  - *Proof ref* (text, placeholder "UTR / receipt") — shown only for PAID.
  - **Log** button. Below: the history newest first, "TYPE · OUTCOME[ · PTP yyyy-mm-dd][ · proof <ref>]" with the logged time.
- **Main flow:**
  1. Officer picks type/outcome (+ date or proof).
  2. Log → system saves the interaction with the logging staffer and time.
  3. Toast "Interaction logged"; proof/date boxes clear; case, worklist and customer views refresh.
- **Business rules:** only the fields relevant to the chosen outcome are sent. **PAID requires a non-blank proof reference** → otherwise `PROOF_REQUIRED` "A PAID outcome requires a proof reference (transaction id or screenshot)". Type/outcome are free strings server-side (any non-blank value is accepted). The PTP date is not validated (a past date is accepted). Logging PAID **credits nothing** — money only reaches the loan through UC-S2-14/17.
- **Postconditions:** an `interaction_log` row (`logged_by_staff_id`); feeds "Calls logged" and "Promises due" on the Team panel (UC-S2-07) and the staff-performance dashboard. No notification.
- **Edge cases / not allowed:** interactions cannot be edited or deleted.

### UC-S2-14 Record a part or full payment taken in the field
- **Actor / working role / permission:** Collection Executive (own case) / Head / ADMIN. UI `collections:interact` (card is permission-gated); backend `COLLECTION_EXECUTIVE` or `COLLECTION_HEAD` (ADMIN bypass) else `FORBIDDEN_ROLE` "This action requires one of: COLLECTION_EXECUTIVE, COLLECTION_HEAD".
- **Entry point:** Collections tab → *Payments* → **"Record a payment"** card.
- **Preconditions:** case exists and is accessible to the caller.
- **Screen:**
  - *What did the borrower pay?* — "Part payment" (hint "Some of the balance; the rest is still owed."), "Full payment" ("Clears the balance outright."), "Settlement" (UC-S2-15).
  - *Amount (₹)* — keeps digits and one decimal point with at most 2 decimals (pasted "₹1,000.50" → "1000.50"); submit disabled while empty, ".", zero or too large for exact paise.
  - *Paid on* (date, defaults to today) — see §Z for the UTC default.
  - *Transaction id (optional)* "UTR / UPI reference"; *Proof (optional)* "Screenshot reference or a note" — **proof is flexible**: a screenshot reference, free text or a transaction id, or nothing.
  - **Record payment**; footer "Nothing is credited to the loan until the accountant validates it against the bank."
  - "Payments collected" list: amount, status pill (**Awaiting Collection Head** / **Awaiting accountant** / **Validated** / **Rejected**), "Kind · paid <date> · recorded by <name>[ · txn]", and "Accountant: <remarks>" when present.
- **Main flow:**
  1. Officer fills the card.
  2. Record → system creates a collections payment `PENDING_ACCOUNTANT` (paid-on defaults to today IST when omitted; blank refs stored as null).
  3. Toast "Recorded ₹X — waiting on the accountant."
- **Business rules:** amount must be > 0 (`INVALID_AMOUNT` "Payment amount must be positive"). Part and full payments concede nothing, so they skip the Head and go straight to the Accountant. "Full payment" is a label only — no check that the amount equals the balance; whatever is validated is credited and the loan closes only if the outstanding reaches 0. Unknown kind → `INVALID_PAYMENT_KIND` "Payment kind must be PART_PAYMENT, FULL_PAYMENT or SETTLEMENT".
- **Postconditions:** `COLLECTION_PAYMENT_TO_VALIDATE` (in-app to Accountants). The loan is untouched until UC-S2-17.
- **Edge cases / not allowed:** the person who takes a payment never books it. An overpayment is not blocked here (it simply closes the loan on validation; no refund register exists for this path).

### UC-S2-15 Record a settlement payment
- **Actor / working role / permission:** as UC-S2-14.
- **Entry point:** "Record a payment" card with kind **Settlement** (hint "Full & final for less than the balance — needs a settlement open on the case, and the Collection Head's approval.").
- **Preconditions:** the case has exactly one non-rejected settlement (PROPOSED or APPROVED) — the card never sends a settlement id.
- **Main flow:**
  1. Officer records the amount actually paid against the full-and-final.
  2. System links the payment to that settlement; status **`PENDING_HEAD`** if the settlement is still PROPOSED, **`PENDING_ACCOUNTANT`** if it is already APPROVED.
  3. Toast "Recorded ₹X — waiting on the Collection Head." (or "…the accountant.").
- **Alternate & error flows:**
  - no live settlement → `SETTLEMENT_REQUIRED` "Propose a settlement on this case before recording a settlement payment".
  - two or more live settlements → `SETTLEMENT_REQUIRED` "This case has more than one open settlement — say which one this pays" (the UI has no picker, see §Z)
  - a given id not open on the case → `SETTLEMENT_NOT_FOUND`. A REJECTED settlement can never be paid against.
- **Postconditions:** `COLLECTION_PAYMENT_TO_APPROVE` (in-app to Collection Heads) when PENDING_HEAD, else `COLLECTION_PAYMENT_TO_VALIDATE` (Accountants).

### UC-S2-16 Collection Head approves a settlement payment
- **Actor / working role / permission:** Collection Head (working role) or ADMIN; backend `COLLECTION_HEAD` (ADMIN bypass).
- **Entry point:** `/staff/applications` in the Collection Head working role → queue **"Settlement payments to approve" (n)**, ⓘ "Payments recorded against a settlement. Approving does both halves of your decision — it approves the settlement itself and releases the payment to the accountant to validate. You cannot approve one you recorded yourself." Polls every 10 s.
- **Screen:** one card per payment: "₹X · Settlement", "<borrower> · loan #id · paid <date>[ · txn]", "Recorded by <name> on <time>", "Proof: …"; button **Approve**. Empty "No settlement payments waiting on you."
- **Main flow:**
  1. Head clicks Approve.
  2. System: payment must be PENDING_HEAD; if its settlement is still PROPOSED it is approved in the same step (UC-S2-20 rules, including its SoD); payment → `PENDING_ACCOUNTANT`.
  3. Toast "Settlement payment approved".
- **Business rules (SoD):** approver ≠ officer who recorded the payment → `SOD_VIOLATION` "The approver must differ from the officer who recorded the payment"; and, via the settlement approval, approver ≠ settlement proposer → `SOD_VIOLATION` "The approver must differ from the proposer (separation of duties)". Not pending head → `PAYMENT_NOT_PENDING_HEAD` "This payment is not waiting for a Collection Head".
- **Postconditions:** settlement APPROVED (cap applies at once, see UC-S2-20); `COLLECTION_PAYMENT_TO_VALIDATE` to Accountants; `SETTLEMENT_APPROVED` to the borrower if it was approved here.
- **Edge cases / not allowed:** there is **no Head reject** for a collections payment; a wrong one is rejected by the Accountant (UC-S2-17) or the settlement is rejected (UC-S2-21) before payment.

### UC-S2-17 Accountant validates or rejects a collections payment
- **Actor / working role / permission:** Accountant (or ADMIN); backend `ACCOUNTANT` (ADMIN bypass).
- **Entry point:** `/staff/applications` in the Accountant working role → third queue **"Collections payments to validate" (n)**, ⓘ "Money the collections team took from borrowers. Validating credits it to the loan; rejecting sends it back to the officer and the Collection Head with your remarks." Polls every 10 s.
- **Screen:** per card the payment facts (as UC-S2-16) plus *Remarks* textarea (placeholder "Matched on the statement / amount doesn't match…") and buttons **Validate** and **Reject** (Reject disabled until remarks are typed, hover "Say why before rejecting"). Empty "No collections payments awaiting validation."
- **Main flow (validate):**
  1. Accountant matches the money against the bank.
  2. Validate → system records a `BANK_TRANSFER` repayment on the loan (txn ref, proof ref, **paid-on = the date the officer entered**) and verifies it in one step → outstanding recomputed as of the latest verified paid-on → loan closes at 0. 3. Payment → `VALIDATED`, toast "Payment validated".
- **Main flow (reject):** Reject with remarks → `REJECTED` (terminal), toast "Payment rejected".
- **Business rules:** only PENDING_ACCOUNTANT can be decided → `PAYMENT_NOT_PENDING_VALIDATION` ("The Collection Head has not approved this settlement yet" or "This payment has already been validated/rejected"). Reject without remarks → `REMARKS_REQUIRED` "Say why you are rejecting this payment — the collections officer needs to act on it". The ledger write is guarded by `ledger_payment_id`, so a retry or double-click never credits twice. Validation is the only path by which a collections payment touches the loan.
- **Alternate & error flows:** loan already closed when validating → `LOAN_SETTLED` "Loan is already settled" (the payment stays pending; the money must be handled outside the system).
- **Postconditions:** `COLLECTION_PAYMENT_VALIDATED` or `COLLECTION_PAYMENT_REJECTED` (in-app to the recording officer and the Collection Heads). On validate also `REPAYMENT_RECORDED` + `REPAYMENT_VERIFIED` (borrower), and on close `LOAN_CLOSED`, application → `CLOSED`, DSA commission matured (or voided on a settlement closure).
- **Edge cases / not allowed:** there is no separate proposer≠validator check — the roles differ by construction, except ADMIN who could do both.

---

## D. Settlements and hardship

### UC-S2-18 Propose a settlement
- **Actor / working role / permission:** Collection Executive (own case), Head, ADMIN. UI: *Propose settlement* section needs `collections:interact` (in practice the Executive working role); backend `COLLECTION_EXECUTIVE` or `COLLECTION_HEAD` (ADMIN bypass) + case ownership.
- **Entry point:** Collections tab → **"Propose settlement"** section.
- **Preconditions:** case exists.
- **Screen:** *Settlement amount (₹)* (same rupee sanitiser, placeholder "25000"), button **Propose** (disabled until a positive amount). On success "Proposed ₹X — pending approval."; footnote "A Collection Head approves it on the Settlements page (separation of duties)."
- **Main flow:** officer enters the agreed full-and-final figure → system stores a `PROPOSED` settlement with `proposed_by` = caller → settlements list refreshes.
- **Business rules:** the amount is the **total full-and-final** the borrower will have paid on this loan, **including** payments already verified (the cap is `settlement − verified`, UC-S2-42). Only rule enforced: amount > 0 (`@Positive`). There is **no** upper bound against the outstanding, no minimum recovery %, and no "one open settlement per case" rule (see §Z) — a proposal above the dues is harmless because the cap can only lower what is owed.
- **Postconditions:** `SETTLEMENT_PROPOSED` (in-app to Collection Heads, "Settlement to approve"). Nothing changes on the loan.

### UC-S2-19 Settlements register
- **Actor / working role / permission:** nav gate `collections:interact`, `collections:manage` or `collections:overview`; title by role: ADMIN "Total settlements", COLLECTION_HEAD "Settlement approvals", COLLECTION_EXECUTIVE "My settlements". Backend list: `COLLECTION_EXECUTIVE`/`COLLECTION_HEAD` (ADMIN bypass); an executive gets only settlements on their own cases.
- **Entry point:** *Collections → Settlements* (`/staff/collections/settlements`).
- **Screen:**
  - Subtitle "Review proposed settlements against the customer's full dues (separation of duties enforced)."; ADMIN export (title "Collections settlements", every column below plus case/settlement ids, decided-by/at).
  - **Period** filter (on the proposal's IST day) + **search** ("Name, mobile, email, PAN, account, customer or loan ID" — also matches settlement/case ids). Both narrow the summary table, the chip counts and the register.
  - **Summary table** — rows *Pending* (loss labelled "if approved"), *Approved*, *Rejected*, *Total*; columns Settlements · Total due (ⓘ "Principal + interest + late penalty, before any payment") · Total penalty · Net paid (ⓘ "Verified payments received") · Settlement amount · Total loss (ⓘ "Total due minus the settlement amount — what the settlement writes off"). A rejected settlement's loss is 0, so total loss = pending (potential) + approved (actual).
  - **Status chips** All / Proposed / Approved / Rejected with counts; "All" lists PROPOSED rows first, then the rest in server order (newest first).
  - **Columns (22):** S.No. · Customer · Mobile · Email · PAN · Account · IFSC · Employer · Bureau · Loan · DPD · Principal · Interest · Penalty · Total due · Net paid · Outstanding (today, settlement-capped) · Settlement · Loss · Proposed (date + proposer; full settlement/case ids on hover) · Status (Pending / Approved / Rejected) · Action.
  - **Action cell:** APPROVED → "<approver> · <time>"; REJECTED → "<rejecter> · <time>"; PROPOSED → for `collections:manage`: **Approve** (green) / **Reject** (red), or "You proposed this — another Collection Head must decide" when the viewer is the proposer; for others "Awaiting Collection Head".
  - Sortable/filterable: Customer, DPD, Total due, Penalty, Net paid, Outstanding, Settlement, Proposed, Status. Default sort Proposed desc.
  - Empty: "No settlements proposed." / "No settlements match the period, search or column filters." / "No <chip> settlements."
- **Business rules:** **Loss** = max(0, total due − settlement amount), where total due = principal + interest + penalty *before* payments (falls back to today's outstanding when dues are missing). **Recovery %** = round(settlement ÷ total due × 100).
- **Postconditions:** read-only (decisions via UC-S2-20/21).

### UC-S2-20 Approve a settlement
- **Actor / working role / permission:** Collection Head (working role, `collections:manage`) or ADMIN in the Head working role; backend `COLLECTION_HEAD` (ADMIN bypass).
- **Entry point:** Settlements register → **Approve** → confirmation (also happens implicitly in UC-S2-16).
- **Screen (confirm "Approve this settlement?"):** Customer (+ mobile) · Loan (+ "N days past due") · Total due (incl. ₹ penalty) · Net paid so far · Outstanding today · Settlement amount (+ "(x% of dues)") · Loss (written off) · Proposed by (+ time); text "Approving caps what this borrower owes at the settlement amount; the balance above it is written off once the settlement payment is booked. This cannot be undone." Button **Approve settlement**.
- **Main flow:**
  1. Head confirms.
  2. System: settlement must be PROPOSED; approver ≠ proposer; status → `APPROVED`, `approved_by/at` stamped; **the loan's outstanding is recomputed immediately**.
  3. Toast "Settlement approved".
- **Business rules:**
  - SoD: `SOD_VIOLATION` "The approver must differ from the proposer (separation of duties)". Already decided → `SETTLEMENT_NOT_PENDING` "This settlement has already been approved|rejected".
  - **The cap:** from approval on, outstanding = min(formula balance, max(0, settlement − Σ verified payments)). With several approved settlements on a case, the **most recently approved** is operative. A rejected or merely proposed settlement never caps anything.
  - **Close at approval:** if verified payments already ≥ the settlement amount, the loan closes inside the approval transaction (closed-on = latest verified paid-on), the application moves `ACTIVE/OVERDUE → CLOSED` with the audit note "Closed by approved settlement of ₹X (full & final)", and any DSA commission is voided.
- **Postconditions:** `SETTLEMENT_APPROVED` to the borrower (in-app "A full & final settlement of {settlementAmount} is approved on your loan. Pay it to close."; SMS and WhatsApp `db_settlement_approved`). The borrower's repay page now quotes the capped figure.
- **Edge cases / not allowed:** cannot be undone or edited; a new figure needs a new proposal (and the newest approval wins).

### UC-S2-21 Reject a settlement
- **Actor / working role / permission:** as UC-S2-20.
- **Entry point:** Settlements register → **Reject** → confirmation "Reject this settlement?" with the same figures and "Rejecting closes this proposal for good — the borrower still owes the full outstanding, and the officer must propose afresh." Button **Reject settlement**.
- **Main flow:** same guards as approve (PROPOSED only, rejecter ≠ proposer, same `SOD_VIOLATION` text) → status `REJECTED`, `rejected_by/at` → toast "Settlement rejected".
- **Postconditions:** `SETTLEMENT_REJECTED` (in-app to the proposer). The loan is untouched. A settlement payment can no longer be recorded against it.
- **Edge cases / not allowed:** no reason field is captured on a settlement reject.

### UC-S2-22 Settlements dashboard (page exists, not wired)
- **Status:** `app/staff/collections/settlements/dashboard/page.tsx` is an **untracked, unshipped** page: it calls `collectionsApi.settlementDashboard`, which does not exist in `lib/api/applications.ts`, and there is no settlements-dashboard endpoint in the backend. It is not in the sidebar.
- **Intended design (from the page, for the rebuild):** Collection Head / Admin; period filter; tiles **Proposed** (count + ₹ offered), **Approved** (₹ + count), **Rejected** (count + % of decided), **Awaiting decision** (₹ + count), **Recovered so far** (validated settlement payments ÷ approved ₹), **Avg time to decision**, **Avg haircut** (ⓘ "(outstanding − settlement) ÷ outstanding, using the loan's current outstanding"), **Pending payments** (recorded, not yet validated); charts "Settlements per day" (proposals on the raise day, decisions on the decide day) and "Pending aging" (< 24h, 1–3 days, 3–7 days, > 7 days); an **Officer leaderboard** (Proposed, Approved, Rejected, Pending, Approval %, Settled, Paid, Avg haircut, Avg time — click a row to filter the detail table); a **Deciders** table; an **All settlements** table with status chips, search and inline Approve/Reject.
- **Note:** its *haircut* uses today's outstanding while the register's *loss* uses gross dues — two definitions of the same concession (§Z).

### UC-S2-23 Hardship / revised repayment plan (not built)
- **Status:** only the schema exists — table `repayment_plan` (V2: `collection_case_id`, `revised_due_date`, `proposed_by`, `approved_by`, `created_at`, `approved_at`), the JPA entity `RepaymentPlan` (javadoc: "A hardship-driven revised repayment plan … Proposed by an officer and Head-approved (maker-checker)"; TODO for multi-instalment rows), a frontend type, and one customer-export field "Revised repayment plan date". There is **no repository, service, endpoint or screen**; `proposed_by`/`approved_by` are still UUIDs from before the staff-id migration.
- **Intended rule for the rebuild:** an officer proposes one revised due date on a case; a different Collection Head approves (SoD, audited); the approved date would govern DPD and penalty. Today the only hardship levers are a settlement (UC-S2-18) and the ADMIN dated payment (UC-S2-02).

---

## E. Skip tracer

### UC-S2-24 Run a skip trace and read its history
- **Actor / working role / permission:** Run: Collection Head or ADMIN (UI gate `collections:manage` or `verification:retry`; backend `COLLECTION_HEAD`, ADMIN bypass, else `FORBIDDEN_ROLE`). History: anyone who can see the customer (DSA refused, customer scope applies).
- **Entry point:** customer pop-up → **Third-party logs** tab → skip-trace panel. Never runs in the lifecycle.
- **Preconditions:** the customer has a PAN or a mobile on file.
- **Screen:** button **"Find contacts"** (or **"Run again"** after a run); "Last run by <name> (<role>) on <time>."; confirmation "Run a skip trace for this customer?" / "Run the skip trace again?" — "This calls Digitap Skip Tracing Lite and is billed per lookup, including when nothing is found." Button "Run skip trace". Result: *Identity returned*, *Contacts*, *Addresses (n found, m unique)* with per-address quality badge, "Match vs our address", "Visit likelihood", "Last activity", and *Data confidence* badges (FOUND green / NOT_FOUND red / other amber). "Previous runs (n)" below. Empty "No skip trace has been run for this customer." hint "Use Find contacts when their registered details stop working."; no record "Digitap has no record for this PAN / mobile." ("Searched with …"); failure "Digitap could not complete this lookup: …".
- **Main flow:**
  1. Head confirms.
  2. System sends PAN, mobile, name and the on-file address (a leading Google plus-code is stripped); if Digitap rejects the address format it retries once without it.
  3. A row is saved with status `SUCCESS` / `NO_RECORD` / `FAILED`, run-by, duration, request and response.
- **Business rules:** every run is billable and recorded whatever the outcome (also in `provider_api_execution`, attributed to the newest application, source MANUAL).
- **Alternate & error flows:** no PAN and no mobile → `SKIP_TRACE_NO_IDENTIFIER` "This customer has neither a PAN nor a mobile number on file to trace"; provider failure → `SKIP_TRACE_FAILED` "Digitap could not complete the skip trace: <safe detail>" (the FAILED row is still saved).
- **Postconditions:** a `skip_trace` row. No notification; found contacts are not written back to the profile.

---

## F. Mandates

Background (rules shared by F and G): a mandate is a **per-customer** Digio eMandate (NPCI registration, UMRN). It is *usable* for a loan only when it is `ACTIVE`, has a UMRN, debits this application's disbursal account (matched on the stored last-4 + IFSC; the full number is never stored) and its maximum amount ≥ the **ceiling** = 2 × max(eligible limit, sanctioned amount) (or a higher ADMIN override for new registrations). Every eMandate action is real, billable and legally binding — there is no sandbox. Our DB row is the source of truth; there is no Digio webhook receiver, so state is learned by re-reading.

### UC-S2-25 Mandates & debits console — Mandates tab
- **Actor / working role / permission:** Collection Head / ADMIN (`loan:register`; backend allowlist `COLLECTION_HEAD`, `ADMIN` else `FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD or ADMIN"). Not flag-gated: debits already taken stay auditable with `digio-debit` off.
- **Entry point:** *Collections → Mandates & debits* (`/staff/collections/mandates`), tab **Mandates** (default). Header "Mandates & debits" — "Every eMandate and every auto-debit, with who did what".
- **Screen:**
  - **Stat cards** (whole book): Active mandates (+ "n customers"); Active mandate limit (ⓘ "Sum of the maximum amount every active mandate allows per debit."); Total debited (+ "n successful debits"); Debits in progress (count + ₹); Awaiting authorisation (ⓘ "The borrower has started a mandate but not finished authorising it with their bank."); Debits failed (red when > 0; FAILED + FAILED_TECHNICAL); Cancelled mandates; Refunds owed (₹, red when > 0, ⓘ "Money a debit collected after the loan was already settled.").
  - **Chart:** rupees debited per day for the last **30 days** (by the bank settlement date, else the IST day we learned of it).
  - **Status chips:** Active (default) · Awaiting authorisation · Failed / expired · Cancelled · Bank not supported · All. "Cancelled" = staff-cancelled (`cancelled_at` set), which overrides the stored status on display. **Search** "Name, mobile, UMRN, bank or customer ID" (also application/mandate id). Paged, 25 per page (max 100).
  - **Row (expandable card):** customer name (or "Customer #id"), "mobile · #customerId · loan <application status>", UMRN, Limit, Debited (+ "n attempts · m in progress"), status badge (Active green; Awaiting authorisation amber; Authorisation failed / Expired red; Cancelled grey; Bank not supported amber).
  - **Expanded:** Bank (+ •••• last-4), Mode, Attempt #, Started, Authorised, Last debit, Cancelled ("<time> by <who> — <reason>"), Bank reason; button **"Open mandate actions"** (opens the customer pop-up on the **Mandate** tab, UC-S2-27..36); "Debit attempts on this mandate" table (UC-S2-26 columns) or "No debit has been presented on this mandate."
  - Empty "No mandates match." hint "Try another status or search."
- **Business rules:** the console is **read-only**; every action happens in the Mandate tab so the same rules and permissions apply. Closing the pop-up refreshes all console lists.

### UC-S2-26 Mandates & debits console — Debits tab
- **Actor / working role / permission:** as UC-S2-25.
- **Entry point:** tab **Debits**.
- **Screen:** filters **Status** (All statuses · Succeeded · In progress = SCHEDULED/IN_PROGRESS · Failed = FAILED/FAILED_TECHNICAL · Cancelled · Skipped · Refund owed), **Trigger** (Manual + scheduled · Manual (by staff) · Scheduled sweep), **Presented by** (Anyone or a staffer who has presented one), **From / To** dates, **search** "Name, mobile, UMRN, UTR or loan ID". Summary line "n debits · m succeeded · ₹ total". Table: Presented (time) · Customer · UMRN · Amount · Status (Scheduled amber, In progress blue, Succeeded green, Failed / Failed (technical) red, Cancelled / Skipped grey) · By ("<name> (<role>)", or **"Automatic sweep"** for a scheduled one) · Settlement · UTR. A row expands to: Presented by, How ("Manual debit from the console" / "Scheduled sweep (DPD 1/3/5/9)"), Presented on, Amount, Status (+ "· bank: <raw Digio status>"), Settlement date, UTR, DPD when presented, "Attempt / times sent" ("#n · sent k×"), Failure (code — reason), Loan, UMRN, Digio transaction, Last updated (+ by); a red line "Refund owed — this debit collected money after the loan was already settled." when applicable; plus "Open mandate actions". Empty "No debits match." hint "Change the filters or search." Paged 25.
- **Business rules:** read-only; provider text is shown only here (staff console), never on a borrower path.

### UC-S2-27 Mandate tab: read the mandate and "Re-check with bank"
- **Actor / working role / permission:** read: any staff who can see the application (`requireBorrowerOwnsOrStaff`); attempt history `requireStaff`. "Re-check with bank": `mandate:debit` or `mandate:override` in UI; backend `COLLECTION_HEAD`, `COLLECTION_EXECUTIVE`, `DISBURSEMENT_HEAD`, `ADMIN` else `FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD, COLLECTION_EXECUTIVE, DISBURSEMENT_HEAD or ADMIN".
- **Entry point:** customer pop-up → **Mandate** tab (also via "Open mandate actions").
- **Screen:**
  - No application → "No application yet." hint "A mandate is registered after sanction."; no mandate at all → empty card plus the invite panel (UC-S2-28), change-account (UC-S2-33) and ceiling (UC-S2-30) panels.
  - **"Auto-repayment mandate" card:** status badge — "Awaiting borrower authorisation" (amber), "Active" (green) + **"Bank approved"** / **"Awaiting bank approval"**, "Authorisation failed" / "Expired" (red), "Bank does not support online mandates" (amber), or "Cancelled". UMRN; Bank; Account "•••• last-4"; Authorised; Attempt; Reject reason. Footer "Carried over from an earlier advance — not registered on this application." when the effective mandate came from a previous application. The **effective** mandate is shown first (a usable ACTIVE one wins over a newer unauthorised attempt).
  - **"Mandate attempts (n)"** table (every attempt across the customer's applications): # · Status ("Cancelled" + "<time> by <who> — <reason>", "· in force" in green for the usable one, "Acknowledged — awaiting bank approval" / "Bank approved" under ACTIVE, else the bank reject reason) · UMRN · Mode · Ceiling · Started · App · Action (heads only, UC-S2-31/32). Warning when more than one attempt is still pending: "More than one attempt is still awaiting authorisation. Each is a live mandate request at the bank — if the customer completes one of the older ones they get a second UMRN. Ask them to close any open bank tabs."
  - **"Re-check with bank"** button.
- **Main flow (re-check):** click → system re-reads from Digio every attempt that is not settled (settled = ACTIVE with bank state `register_success`, BANK_UNSUPPORTED, or staff-cancelled; a cancelled row is never re-adopted) → toast "Active mandate found — UMRN <umrn>" or "Checked with the bank; nothing changed". Opening the tab auto-runs one silent re-check when an ACTIVE attempt is not yet bank-approved.
- **Business rules:** files nothing, cancels nothing; per-attempt read failures are logged and skipped. "Authorisation failed" is not terminal — a re-read can reveal it completed.
- **Postconditions:** attempt rows updated to the provider's state.

### UC-S2-28 Invite the customer to set up auto-repayment
- **Actor / working role / permission:** UI `mandate:debit` or `mandate:override`; backend `COLLECTION_HEAD`, `COLLECTION_EXECUTIVE`, `DISBURSEMENT_HEAD`, `ADMIN`.
- **Entry point:** Mandate tab → panel **"Invite the customer to set up auto-repayment"** — "Sends the customer a link on every channel they have, and gives you the same link to copy. Nothing is filed until they open it and authorise with their bank, so this is safe to repeat." Shown when there is no mandate or the shown one is not ACTIVE.
- **Preconditions:** no usable mandate for this application; a disbursal account (8–18 digits) and 11-char IFSC on file.
- **Main flow:** **Send invite** → system publishes the invite with the link `<app base>/loan/mandate` (our page, no token, no app id — the page adopts the borrower's own current application from their session) → toast "Invite sent; link ready to copy" → **Copy link** ("Copied"; on failure "Couldn't copy — select the link and copy it manually") and the link shown in a box.
- **Alternate & error flows:** usable mandate exists → `MANDATE_ALREADY_ACTIVE` "This customer already has an active mandate"; no account → `MANDATE_NO_ACCOUNT` "No verified bank account on file for this customer, so a mandate cannot be set up".
- **Postconditions:** `MANDATE_SETUP_INVITE` to the borrower (in-app, SMS, email, WhatsApp). No mandate row until the borrower authorises.

### UC-S2-29 Override a BANK_UNSUPPORTED mandate
- **Actor / working role / permission:** Disbursement Head / ADMIN (`mandate:override`; backend `requireAnyRole("DISBURSEMENT_HEAD")`, ADMIN bypass).
- **Entry point:** Mandate tab panel shown only when the newest attempt **on this application** is `BANK_UNSUPPORTED` (not cancelled) **and** the application is `SANCTIONED`: "This bank cannot register an online mandate, so the borrower cannot proceed by retrying. Overriding sends the file to disbursement without a mandate. This action is logged."
- **Screen:** textarea "Why you're overriding (required, goes in the audit trail)"; button **Override mandate** (disabled until a note).
- **Main flow:** system checks and transitions `SANCTIONED → DISBURSEMENT_PENDING` (event `MANDATE_OVERRIDE`, note "Bank unsupported; overridden by <name> — <note>") → toast "Mandate overridden; moved to disbursement".
- **Alternate & error flows:**
  - not sanctioned → `NOT_APPLICABLE` "This application isn't sanctioned".
  - newest attempt not BANK_UNSUPPORTED → `MANDATE_OVERRIDE_NOT_ALLOWED` "A mandate override is only available when the borrower's bank cannot register an online mandate".
  - eSign not PASS → `AGREEMENT_NOT_SIGNED` "The borrower hasn't signed their agreement yet".
  - blank note → `NOTE_REQUIRED`.
  - drawn amount below ₹1,000 or above the sanction → `AMOUNT_INVALID` "The borrower has not chosen a valid amount yet" (a missing amount defaults to the sanctioned ceiling).
- **Postconditions:** application in the Disbursement Head's queue; the loan will have no mandate (manual repayment only).

### UC-S2-30 Raise the mandate ceiling for new registrations (ADMIN)
- **Actor / working role / permission:** read: any staff; set/clear: **ADMIN** (UI shows inputs with `customer:manage`, which only the “Admin” working role carries; backend `FORBIDDEN_ROLE` "This action requires role ADMIN").
- **Entry point:** Mandate tab → panel **"Mandate limit for new registrations"**.
- **Screen:** "A new mandate is registered for **₹X** — raised by an admin override of ₹Y (standard formula ₹Z), set by <who> on <time> — "<note>"." or "… (standard formula: 2 × the larger of the eligible limit and the sanctioned amount)." then "It does not change what the customer can borrow, and an existing mandate keeps the limit it was authorised for." ADMIN inputs: *New mandate limit (₹)* (placeholder "75000"; rupees with ≤ 2 decimals else "Enter an amount in rupees, up to 2 decimals."), *Reason (optional)*, **Set limit**, and a clear action.
- **Main flow:** set → registration ceiling = max(formula, override) → toast "New mandates will be registered for ₹X"; clear → toast "Mandate limit back to the standard formula".
- **Business rules:** override must be ₹1,000–₹10,00,000 (`MANDATE_CEILING_OUT_OF_RANGE` "The mandate limit must be between Rs 1,000 and Rs 10,00,000"); it can only *raise* the ceiling; it is per customer; whether an existing mandate covers an application is still judged against the formula. Note stored up to 500 chars.
- **Postconditions:** `profile_change_log` entry `mandateCeilingPaise` (old → new; clearing deletes the override row, the log is the only record).

### UC-S2-31 Cancel a pending / stale mandate attempt
- **Actor / working role / permission:** UI `mandate:override` or `collections:manage`; backend `COLLECTION_HEAD`, `DISBURSEMENT_HEAD`, `ADMIN` else `FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD, DISBURSEMENT_HEAD or ADMIN".
- **Entry point:** Mandate attempts table → **"Cancel…"** on a non-ACTIVE, non-cancelled, non-BANK_UNSUPPORTED attempt → confirmation panel "Cancel mandate attempt #n?".
- **Screen:** with a UMRN: "This asks the bank to withdraw registration <UMRN> at NPCI. It cannot be undone." Without a UMRN: "We ask the bank to cancel it first. A bank usually refuses to cancel an attempt that was never authorised — if so, it is **withdrawn on our side only**, and the customer's next try files a fresh mandate instead of reopening this one. Their old bank link may still work for up to 7 days; if they complete it, that mandate stays at their bank and we never debit it." Buttons **Yes, cancel attempt** / **Keep it**.
- **Main flow:** system asks Digio to cancel (reason code `C002` corporate request). Accepted → re-read the provider state, mark cancelled "C002: stale attempt withdrawn" → toast "Mandate attempt cancelled at the bank". Refused and the attempt has no UMRN → marked `AUTHORIZATION_FAILED` + cancelled "Withdrawn on our side: never authorised, so the bank cannot cancel it" → toast "Attempt withdrawn on our side — the customer's next try files a fresh mandate".
- **Alternate & error flows:**
  - BANK_UNSUPPORTED → `MANDATE_NOTHING_FILED` "Nothing was filed for this attempt, so there is nothing to cancel".
  - ACTIVE → `MANDATE_ACTIVE_NOT_CANCELLABLE` "This mandate is active. Cancelling a live mandate is not done from here." (use UC-S2-32)
  - refused with a UMRN → `MANDATE_CANCEL_REFUSED` "The bank would not cancel this mandate. It may already be closed or past the point of cancellation.".
  - mandate of another customer → `MANDATE_NOT_FOUND`.
- **Postconditions:** `cancelled_at/by/reason` stamped. No borrower notification.

### UC-S2-32 Cancel the ACTIVE mandate (with reason)
- **Actor / working role / permission:** as UC-S2-31 (Heads / ADMIN).
- **Entry point:** Mandate attempts table → **"Cancel mandate…"** on the ACTIVE row → red panel "Cancel the active mandate (UMRN …)".
- **Screen:** "This revokes the customer's auto-repayment authority at their bank and cannot be undone — a new mandate needs them to authorise again." + (live loan) "**This customer has a live loan: automatic debits stop now and it must be repaid manually.**" or "Automatic debits stop for any loan this mandate served." + "The customer is notified by email and in the app." *Reason* radios: "The customer asked us to cancel it" (`CUSTOMER_REQUEST` → C001, default) / "Replacing it (e.g. a higher limit is needed)" (`CORPORATE_REQUEST` → C002). *Note (required)* (max 480, placeholder "e.g. Customer requested on call 22 Oct; or: limit too low for new sanction of ₹15,000"). Button **Cancel mandate** (disabled until a note).
- **Main flow:**
  1. Head confirms.
  2. System locks the customer and every loan of theirs (same locks the debit path takes), re-reads the mandate, refuses if any debit against it is SCHEDULED/IN_PROGRESS, asks Digio to cancel with the reason code.
  3. Accepted → re-read; if the bank still says active the row is set `AUTHORIZATION_FAILED` / state `cancel_requested` (dead to us from now); cancelled "<code>: <note>".
  4. Toast "Mandate cancelled; the customer has been notified" or (corporate) "Mandate cancelled — send the customer an invite to set up the new one".
- **Alternate & error flows:**
  - no reason → `CANCEL_REASON_REQUIRED`.
  - unknown reason → `CANCEL_REASON_INVALID`.
  - blank note → `CANCEL_NOTES_REQUIRED` "Add a note explaining the cancellation".
  - not ACTIVE → `MANDATE_NOT_ACTIVE` "This mandate is not active. Use the row's Cancel action for an unfinished attempt.".
  - debit in flight → `MANDATE_DEBIT_IN_FLIGHT` "A debit against this mandate is still being processed. Check its status or cancel it under Debit attempts, then try again.".
  - bank refuses → `MANDATE_CANCEL_REFUSED`.
- **Postconditions:** no further debit can be presented on this mandate (usable needs ACTIVE); `MANDATE_CANCELLED` to the borrower (in-app + email).
- **Edge cases / not allowed:** allowed while a loan is live (the loan falls back to manual repayment). Mandate amendment is not built.

### UC-S2-33 Change the mandate account
- **Actor / working role / permission:** Heads / ADMIN (as UC-S2-31). **Before payout** (application `SANCTIONED` or `DISBURSEMENT_PENDING`) the account is also the payee, so only **Disbursement Head / ADMIN** (UI hides the panel unless `mandate:override`; backend `FORBIDDEN_ROLE` "This advance has not been paid out, so the account is also where it will be paid. Only the Disbursement Head or ADMIN can change it.").
- **Entry point:** Mandate tab → **"Change mandate account"** → "Change account…" (hint "Use when the customer's bank cannot register an eMandate, or they want auto-repayment from another account.").
- **Preconditions:** the customer has confirmed/verified a disbursal account on this application; no debit in flight on any of their mandates.
- **Screen (form):** *New account number* (digits only, spaces stripped), *Re-enter account number* (paste blocked), *IFSC* (upper-cased, max 11, placeholder "HDFC0001234"), *Reason (required)* (max 400, placeholder "e.g. Slice account cannot register an eMandate; customer shared their Kotak account on call"). Inline errors: "Account number must be 8–18 digits." / "The two account numbers do not match." / "Enter a valid 11-character IFSC." **Review change** (enabled when all four are valid).
- **Screen (confirm "Change the mandate account to ••••1234 (IFSC)?"):** "We first verify the new account with a penny drop. If it fails, nothing changes." · "All n old mandates will be cancelled: #1 Bank ••••xxxx (status), …" (or "The customer has no mandates to withdraw.") · red "This includes a live mandate: automatic debits stop until the customer authorises the new one, and they are notified. This cannot be undone." · (before payout) "This advance has not been paid out yet, so it will be paid to the new account." · "The customer gets an invite to register the mandate on the new account."
- **Main flow:**
  1. Staff confirms.
  2. Pre-checks (all before any money/mandate is touched): note present; account `^\d{8,18}$` and IFSC `^[A-Z]{4}0[A-Z0-9]{6}$`; account confirmed on the file; not identical to the current one; payee-role rule; the bank (IFSC prefix) supports API eMandate; no in-flight debit.
  3. Penny drop straight through `VerificationPort` (not recorded as the file's PENNY_DROP verification): account must exist and the holder name must match the customer above the name-match threshold.
  4. Application's disbursal account, IFSC, bank label and holder name replaced; account marked verified; `disbursal_account_changed` = differs from the salary account.
  5. Every non-cancelled mandate of the customer is withdrawn: BANK_UNSUPPORTED → closed ("nothing had been filed"); one with a debit in flight → "NOT cancelled, a debit is still in flight"; others → Digio cancel C002 — a refused ACTIVE one → "NOT cancelled, the bank refused. It is a live mandate on the old account; ask the customer to cancel it with their bank".
  6. Invite sent (UC-S2-28 link).
  7. Toast "Account changed; old mandates withdrawn and invite sent"; result panel "Done. What happened to the old mandates:" (one line per mandate, NOT-cancelled lines in red) + "The invite link is in the invite box; the customer registers on the new account."
- **Alternate & error flows:** `CHANGE_NOTES_REQUIRED`, `ACCOUNT_INVALID` "Enter a valid account number (8-18 digits) and IFSC", `MANDATE_ACCOUNT_UNCONFIRMED` "This customer has not confirmed a bank account on this application yet", `ACCOUNT_UNCHANGED` "That is already the account on file", `MANDATE_BANK_UNSUPPORTED` "This bank cannot register an eMandate online. Use an account at another bank.", `MANDATE_DEBIT_IN_FLIGHT` "A debit is still being processed. Wait for it to settle, then change the account.", `PENNY_DROP_FAILED` ("The account could not be verified right now. Try again in a few minutes. Nothing was changed." / "The name on this bank account does not match the customer. Nothing was changed." / "The bank could not confirm this account number and IFSC. Nothing was changed." — the holder name is never revealed).
- **Postconditions:** `profile_change_log` entries (masked old/new account, IFSC, the note, "PASS via <provider> txn <id>, name match n%"); `MANDATE_CANCELLED` to the borrower for each live mandate cancelled; `MANDATE_SETUP_INVITE`. Once a provider cancel has gone out the step never throws (refusals are reported per row).

---

## G. NACH debits

Shared guards (`DebitService.prepare`, applied to the sweep **and** manual debits, all under a per-loan advisory lock and the partial unique index `uq_mandate_debit_in_flight`):

| Check | Refusal code — message |
|---|---|
| A debit SCHEDULED/IN_PROGRESS on the loan | `DEBIT_IN_FLIGHT` — "A debit is already in progress for this loan" |
| Latest row is FAILED_TECHNICAL | first look it up by its unique key; found → adopt it (no new presentation); not found → re-present **the same row with the same key**, max 3 presentations per row → `DEBIT_ATTEMPTS_EXHAUSTED` |
| Latest row FAILED with a terminal reason | `DEBIT_NOT_REPRESENTABLE` — "The last debit failed in a way that cannot be retried" |
| ≥ 4 FAILED/FAILED_TECHNICAL rows on the loan | `DEBIT_ATTEMPTS_EXHAUSTED` — "The maximum number of debit attempts has been reached" |
| Loan status not ACTIVE/OVERDUE/IN_COLLECTIONS | `LOAN_NOT_DEBITABLE` |
| DPD < 1 | `LOAN_NOT_OVERDUE` — "This loan is not overdue" |
| No usable mandate / no UMRN | `NO_USABLE_MANDATE` — "No active mandate for this loan" |
| Lender settlement account (`NAVIX_DIGIO_CORPORATE_ACCOUNT_NUMBER`) unset | `DEBIT_NOT_CONFIGURED` — "Debits are not configured" |
| Balance owed on the settlement date ≤ 0 | `NOTHING_OUTSTANDING` |
| Amount ≤ 0 / > owed / > mandate max / > int32 paise | `INVALID_AMOUNT` / `AMOUNT_EXCEEDS_OUTSTANDING` "The amount is more than is owed" / `AMOUNT_EXCEEDS_MANDATE` "The amount is above the mandate limit" / `AMOUNT_TOO_LARGE` |
| Settlement date outside the mandate's first/final collection dates | `OUTSIDE_MANDATE_WINDOW` |

- **Settlement date** = tomorrow (T-1 cutoff). A null amount means "the outstanding **as of tomorrow**" (penalty keeps accruing until settlement).
- **Row** is committed (`SCHEDULED`) before the provider is called; unique key `DB<S|M><loanId>-<attempt>`; narration "DhanBoost repayment". A failed/timed-out create is looked up by key: found → adopt; not found → `FAILED_TECHNICAL` and `DEBIT_UNCONFIRMED` "The debit could not be confirmed. Please try again shortly."
- **Outcome mapping** (Digio status → ours): NEW, READY_TO_SEND → SCHEDULED; SUBMITTED_TO_BANK, ACCEPTED_SPO_BANK, PAYMENT_DELAYED, PAYMENT_AUTHENTICATED → IN_PROGRESS; PAYMENT_SUCCESS → SUCCEEDED; PAYMENT_FAILED, REJECT_SPO_BANK → FAILED (failure code + reason kept, reason ≤ 500 chars); SKIPPED; CANCELLED; DIGIO_TECHNICAL_ERROR → FAILED_TECHNICAL; anything else is logged and left unchanged. Status never moves backwards (IN_PROGRESS is not demoted to SCHEDULED).
- **Success credit:** a VERIFIED repayment through the same credit seam as collections payments (so stored with method `BANK_TRANSFER`, not `NACH` — see §Z; txn ref = UTR, else the Digio txn id; proof ref `NACH:<txnId>`; paid-on = settlement date, never in the future); deduped on the txn ref. If the loan closed meanwhile (`LOAN_SETTLED`) the row is SUCCEEDED with **`overpayment = true`** → Refunds owed (UC-S2-37).
- **Terminal denylist** (`TERMINAL_MARKERS`, matched as substrings of the upper-cased code **and** reason): ACCOUNT_CLOSED, ACCOUNT_BLOCKED, ACCOUNT_FROZEN, ACCOUNT_INOPERATIVE, ACCOUNT_TRANSFERRED, ACCOUNT_DORMANT, DORMANT, NO_SUCH_ACCOUNT, INVALID_ACCOUNT, ACCOUNT_DOES_NOT_EXIST, ACCOUNT_NOT_FOUND, DEBIT_NOT_ALLOWED, DEBIT_BLOCKED, DEBIT_STOPPED, PAYMENT_STOPPED, MANDATE_CANCELLED, MANDATE_REVOKED, MANDATE_EXPIRED, MANDATE_INVALID, MANDATE_NOT_FOUND, MANDATE_NOT_REGISTERED, NO_MANDATE, UMRN_NOT_FOUND, DECEASED, KYC_PENDING, AMOUNT_EXCEEDS, LIMIT_EXCEEDED, DUPLICATE. **Any other failure — including a code never seen — is re-presentable** (unverified against real Digio codes).

### UC-S2-34 Scheduled NACH sweep — DPD 1, 3, 5, 9 (system)
- **Actor:** system (`MandateDebitScheduler`), **10:00 IST** daily (`navix.digio.debit-cron`, zone pinned). Single instance only.
- **Preconditions:** flag `digio-debit` **on** (missing row ⇒ off); settlement account configured (else one ERROR "Debit sweep stopped: … is unset" and nothing presented).
- **Main flow:**
  1. **Reconcile pass:** up to **50** SCHEDULED / IN_PROGRESS / FAILED_TECHNICAL rows whose settlement date ≤ today, least-recently-attempted first; each is re-read from Digio and its outcome applied; an unresolved row is "touched" (`updated_at` = now, meaning "last attempted") so the queue rotates.
  2. **Presentation pass:** for every collectible loan (§0.2) decide `shouldPresent`, then present for the full outstanding (null amount), trigger SCHEDULED, no actor.
- **`shouldPresent` rule:** DPD in **1..9**; the loan has a usable mandate (pre-filter so mandate-less loans don't spam warnings); latest row not SCHEDULED/IN_PROGRESS and not a terminal FAILED; and `latest.attemptNo < slotsReached`, where slotsReached = how many of {1, 3, 5, 9} are ≤ DPD. A missed day is caught up on the next run; nothing is presented after DPD 9; a slot is never used twice. Manual debits share the attempt counter, so they consume slots.
- **Postconditions:** per-loan failures are logged and skipped; log "Debit sweep: n presented". Outcomes arrive on later reconcile passes (no webhook).
- **Edge cases / not allowed:** a SUCCEEDED partial debit does not block the next slot; CANCELLED/SKIPPED rows do not count toward the 4-failure cap.

### UC-S2-35 Debit now (manual NACH presentation)
- **Actor / working role / permission:** Collection Executive (own case only), Collection Head, ADMIN. UI `mandate:debit`; backend allowlist `COLLECTION_HEAD`, `COLLECTION_EXECUTIVE`, `ADMIN` else `FORBIDDEN_ROLE` "This action requires role COLLECTION_HEAD, COLLECTION_EXECUTIVE or ADMIN" (DSA and every other role refused by omission).
- **Entry point:** customer pop-up → Mandate tab → debit panel.
- **Preconditions (panel shown only when all hold):** flag `digio-debit` explicitly true; caller has `mandate:debit`; the application has a loan; mandate ACTIVE with a last-4; a collection case exists and is readable by the caller (an executive on someone else's case sees nothing); outstanding > 0; DPD ≥ 1.
- **Screen:** "Debit the borrower's bank account now under this mandate. This takes real money and cannot be undone. Outstanding today: **₹X**. The amount can be lowered, never raised." *Amount to debit (₹)* (placeholder = today's outstanding; "Enter an amount in rupees, up to 2 decimals." / "It cannot be more than the outstanding ₹X."). **Review debit** (empty box fills with today's outstanding) → "Debit **₹X** from the account ending **•••• 1234**?" **Confirm and debit** / Cancel.
- **Main flow:**
  1. Officer reviews and confirms.
  2. System: flag check (`DEBIT_DISABLED` "Debits are not enabled"), case must exist (`CASE_NOT_FOUND` "Open a collection case for this loan first"), case ownership (`CASE_NOT_ASSIGNED`), staff id required, then every shared guard (table above) with trigger MANUAL and the actor stored.
  3. Toast "Debit of ₹X requested"; history refreshes.
- **Business rules:** every denial is logged with actor, loan and code before it is returned. Not `@Transactional` — the provider call sits between two commits.
- **Postconditions:** a `mandate_debit` row (MANUAL, actor, amount, DPD at attempt); outcome via reconcile (UC-S2-36 or the 10:00 sweep).
- **Edge cases / not allowed:** the UI always sends an explicit amount (today's balance), not "full outstanding as of settlement" — see §Z.

### UC-S2-36 Check status / cancel one debit
- **Actor / working role / permission:** same allowlist as UC-S2-35; **not** flag-gated (money already in flight must stay controllable). History/check/cancel on a loan with no case: Head/ADMIN only (an executive gets `CASE_NOT_FOUND`).
- **Entry point:** Mandate tab → **"Debit attempts"** table (# · Status (+ "· refund owed" in red, "· not retryable" for a terminal FAILED) · Amount · Settlement · UTR · Trigger · actions). Actions only on SCHEDULED/IN_PROGRESS rows: **Check status** and **Cancel debit** (inline "Cancel this debit?" → confirm / **No**).
- **Main flow (check status):** system re-reads Digio under the loan lock and applies the outcome (crediting a success) → toast "Bank says: <status>". A row without a Digio txn id is resolved by key first. Read failure → `DEBIT_UNAVAILABLE` "The debit status could not be read just now".
- **Main flow (cancel):**
  1. System first reconciles (a debit that already succeeded is credited, not cancelled; if it is no longer in flight the result is returned).
  2. No provider txn and no plan, last write ≥ 15 min old, and a key lookup confirms nothing exists → closed locally "Cancelled by <who> (never filed with the provider)".
  3. Otherwise asks Digio to cancel → accepted → CANCELLED "Cancelled by <who> (cancelled at the provider)". Toast "Debit cancelled" or "Not cancelled — the bank already reports <status>".
- **Alternate & error flows:** finished → `DEBIT_NOT_IN_FLIGHT` "This debit has already finished; there is nothing to cancel"; too young / plan without txn → `DEBIT_CANCEL_UNAVAILABLE` "The bank has not issued a transaction for this debit yet. Check status again shortly."; bank picked it up → `DEBIT_CANCEL_REFUSED` "The bank has already started processing this debit, so it can no longer be cancelled. Use Check status for the outcome."; provider error → `DEBIT_UNAVAILABLE` "The debit could not be cancelled just now. Try again."; wrong loan → `DEBIT_NOT_FOUND`.
- **Postconditions:** row status updated; a CANCELLED row does not count toward the attempt cap.

### UC-S2-37 Refunds owed (NACH overpayments)
- **Actor / working role / permission:** Collection Head / ADMIN (`collections:manage`; backend `REGISTER_ALLOWED` = COLLECTION_HEAD, ADMIN). Not flag-gated.
- **Entry point:** *Collections → Refunds owed* (`/staff/collections/refunds`); also the "Refund owed" filter on the Debits tab and the Refunds owed stat card.
- **Screen:** "Refunds owed" — "NACH debits that took money a borrower no longer owed"; Refresh. "Total owed ₹X" (ⓘ "Money collected by NACH from borrowers whose loans were already settled. Each row needs returning to the borrower; this register is the record that it is owed."). Table Loan · Amount · UTR · Settled on · Presented · Trigger. Empty "No refunds owed." hint "Every successful debit landed on a loan that still had a balance."
- **Business rules:** every SUCCEEDED debit with `overpayment = true`, newest first; no date window and **no "mark refunded" state** (the refund workflow is not built, so rows never disappear).
- **Postconditions:** read-only. The refund itself happens outside the system.

---

## H. Money in and the ledger

Where repayments come from and how each reaches `VERIFIED`:

| Source | Recorded by | Starts as | Who verifies |
|---|---|---|---|
| Borrower manual UPI/bank + screenshot (`/repay`) | borrower | `PENDING_VERIFICATION` | Accountant (UC-S2-38) |
| PayU checkout | system on PayU confirmation | `VERIFIED` in one step | gateway (re-checked with `verify_payment`) |
| NACH debit success | system on reconcile | `VERIFIED` in one step | bank |
| Collections field payment | Collection Executive/Head | collections chain (UC-S2-14..17) | Accountant validation creates a VERIFIED payment |
| ADMIN "Log payment" | ADMIN | `VERIFIED` in one step | — (ADMIN only) |

Only `VERIFIED` payments reduce the outstanding; `REJECTED` ones never count.

### UC-S2-38 Accountant verifies or rejects a manual repayment
- **Actor / working role / permission:** Accountant / ADMIN (`loan:activate`); backend `requireRole("ACCOUNTANT","ADMIN")` on verify and reject, else `FORBIDDEN_ROLE` "This action requires role ACCOUNTANT or ADMIN".
- **Entry point:** `/staff/applications` in the Accountant working role → first panel **"Repayments to verify"** ("n pending"); also the customer pop-up → **Repayment** tab → *Payments* list (Verify / Reject on pending rows).
- **Preconditions:** payment `PENDING_VERIFICATION`.
- **Screen (queue):** columns S.No. · Customer (name + "Customer #id", opens the customer dialog; or "Customer details unavailable") · Loan · Amount (+ "partial" badge when the amount was below the balance owed on its paid-on date at the time it was recorded) · Method (UPI / Bank transfer / NACH auto-debit / PayU) · Reference · Proof (presigned link, or "—") · Paid on · actions **Customer**, **Verify** (✓, gold), **Reject** (✕, red). Sort/filter on Amount, Method, Paid on; default Paid on desc, 10 per page; polls every 8 s. Footer "Verifying confirms the transfer landed; it reduces the borrower's balance and closes the loan at zero. Reject if the proof doesn't match — the balance is left unchanged." Empty "No repayments awaiting verification."
- **Main flow (verify):**
  1. Accountant matches the proof against the bank.
  2. Verify → status `VERIFIED`, `decided_by/at` stamped → outstanding recomputed **as of the latest verified payment's paid-on date** (a slow verifier never costs the borrower interest) → loan `CLOSED` at 0 (closed-on = that date), application `CLOSED`, DSA commission matured.
  3. The **post-verify prompt** opens: "should this customer be able to take another loan?" — primary **"Yes, keep eligible"** (focused, Enter = close); secondary "block" reveals a required reason and a second destructive confirmation; blocking writes an indefinite `BLOCKED_BY_STAFF` rejection that stops every future application and reborrow. An already-blocked customer gets a one-line notice instead.
- **Main flow (reject):** Reject → dialog "Reject this payment?" — "₹X on loan #id · <name>. The borrower's balance stays unchanged." *Reason* (required): Wrong payment reference · Amount doesn't match · Payment not received · Proof unreadable · Other; *Note (optional)* "Any extra detail for the borrower/record…"; **Reject payment** ("Rejecting…").
- **Business rules:** reason must be one of `WRONG_REFERENCE`, `AMOUNT_MISMATCH`, `NOT_RECEIVED`, `UNREADABLE_PROOF`, `OTHER` (`REJECTION_REASON_REQUIRED` / `INVALID_REJECTION_REASON`); a VERIFIED payment cannot be rejected (`PAYMENT_ALREADY_VERIFIED` "A verified payment cannot be rejected"). A rejected payment leaves accrual running — the failed payment costs the borrower the days. The Accountant can never *record* a payment, so maker ≠ checker by construction.
- **Postconditions:** verify → `REPAYMENT_VERIFIED` to the borrower (in-app/SMS/WhatsApp: "Your payment of {amount} is confirmed. Outstanding: {outstanding}."), `LOAN_CLOSED` when closed ("Your loan is fully repaid and closed. Thank you!"). Reject → `REPAYMENT_REJECTED` (in-app, SMS, email, WhatsApp; in-app/email carry the reason, the SMS body is DLT-locked). The transactions ledger and "Awaiting repayment" panels refresh.

### UC-S2-39 Resolve a PayU payment in review (and the 5-minute reconcile)
- **Actor / working role / permission:** Accountant / ADMIN (backend allowlist; every other role and DSA → `FORBIDDEN_ROLE` "This action requires role ACCOUNTANT or ADMIN").
- **Entry point:** `/staff/applications` in the Accountant working role → second panel **"PayU payments needing review"** ("n open").
- **Screen:** info "Refunds are made in the PayU dashboard. Marking a payment resolved only records that you dealt with it; it does not credit the loan or refund the borrower." Columns Customer · Loan · Amount · PayU reference · Paid on · **What happened**: "Paid after the loan closed: refund needed" (`LOAN_SETTLED`, not credited, overpayment) · "Amount differs from what we asked for: not credited" (`AMOUNT_MISMATCH`) · "PayU sent no payment reference: not credited" (`NO_PROVIDER_ID`) · "Paid more than was owed: credited, check for refund" (`OVERPAID`) · "Card fee differs from what we quoted: credited" (`FEE_MISMATCH`). Row button **Mark resolved** → dialog "Mark this payment resolved?" — "₹X on loan #id. <reason>. This only records your note; refunds are done in the PayU dashboard." *What was done (required)* placeholder "e.g. Refunded in the PayU dashboard"; **Mark resolved**. Empty "No PayU payments need review." Up to 200 unresolved rows.
- **Main flow:** Accountant does the real-world fix (refund in PayU, manual credit via ADMIN, etc.) → writes the note → system stamps resolved-at/by/note.
- **Business rules:** blank note → `REVIEW_NOTE_REQUIRED` "Say what was done about this payment"; not in review → `ORDER_NOT_IN_REVIEW`; idempotent (a second resolve keeps the first resolver). Resolution never credits, refunds or edits the order.
- **How rows get here (system):** on every PayU signal (browser return, webhook, poll, **reconcile every 5 minutes** — up to 50 open orders per pass, not gated on `payu-repayment`) the order is re-checked with PayU's `verify_payment`; the credited figure is `transaction_amount`. Amount ≠ order amount → AMOUNT_MISMATCH (not credited); no `mihpayid` → NO_PROVIDER_ID (not credited); card fee not one of the quoted fees → FEE_MISMATCH (still credited); amount > outstanding on the paid-on date → OVERPAID (credited); loan already closed → LOAN_SETTLED (not credited). An order still open 24 h after creation becomes EXPIRED.
- **Postconditions:** review row closed; nothing else changes.

### UC-S2-40 Transactions ledger
- **Actor / working role / permission:** Accountant / ADMIN (nav `loan:activate` or `staff:manage`; backend `requireRole("ACCOUNTANT","ADMIN")`).
- **Entry point:** *Finance & reports → Transactions* (`/staff/accounting/transactions`).
- **Screen:** "Transactions ledger" — "All money movement — disbursals out and repayments in, company-wide." Period chips Today · Last 7 days · This month · This quarter · This year · All time; direction tabs All · Incoming · Outgoing; search "Search borrower / mobile / loan #"; Refresh. **Cards** for the whole filter (not the page): *Inflow* (verified repayments only; caption "Verified · ₹X awaiting verification" when pending exist, else "Verified repayments"), *Outflow* (disbursals), *Net* (inflow − outflow, red when negative). Row-count caption once the server has answered. Columns S.No. · Date · Repayment date (the loan's due date; red with hover "Paid after the repayment date" when a repayment's date is later than due + the loan's grace days) · Borrower (+ PAN; opens the loan) · Type (Repayment green "incoming" / Disbursal navy "outgoing") · Amount (signed) · Reference · Proof · Status · Loan. Column filters + facets; ADMIN export (Date, Repayment date, Borrower, PAN, Type, Direction, Amount (₹), Reference, Proof attached, Status, Loan). Page max 100.
- **Business rules:** synthesized on read (no ledger table): OUTGOING = every loan's `net_disbursed` on `disbursed_on` with `disbursal_txn_ref`; INCOMING = every payment that is **not REJECTED** (pending rows are listed but not counted in Inflow). Full PAN shown (staff-only). Search matches borrower name, mobile, loan id.
- **Postconditions:** read-only.
- **Empty states:** "No transactions for “q”." hint "Try a different name, PAN or reference, or widen the period." (see §Z); "No transactions match these filters." hint "Clear a column filter, or widen the period."; "No transactions."

---

## I. Schedulers and the arithmetic

### UC-S2-41 Payment reminders (system)
- **Actor:** system (`PaymentReminderScheduler`), cron `navix.reminders.cron` = `NAVIX_REMINDERS_CRON`, default `0 0 9 * * *` with **no zone** — 09:00 in the JVM's default zone (UTC on ECS, i.e. 14:30 IST; see §Z).
- **Scope:** loans whose stored status is `ACTIVE` or `OVERDUE`.
- **Main flow per loan:**
  1. `markInCollections(loan)` — flips an `ACTIVE`/`OVERDUE` loan to `IN_COLLECTIONS` once `today > due_date` (this sweep is the thing that moves loans into collections the day after they fall due).
  2. Skip if no due date or the penalty-aware outstanding today ≤ 0. 3. With `daysToDue = due − today` and `grace = loan.grace_days`:
  - **Due-soon** when `−grace ≤ daysToDue ≤ 7` → `PAYMENT_DUE_SOON` with days = max(0, daysToDue). In-app "Payment due soon" — "Your payment of {amount} is due in {daysToDue} day(s) (by {dueDate}). Pay by your due date to avoid a late penalty. Prepay anytime to save on interest."; SMS "Dear {name}, repayment of {amount} on your DhanBoost loan {loanId} is due on {dueDate}. Pay at https://dhanboost.com/login. - DhanBoost"; email "Your DhanBoost payment is due soon"; WhatsApp `db_payment_due_soon` (name, amount, loanId, dueDate).
  - **Overdue** when `grace < daysOverdue ≤ grace + 7` → `PAYMENT_OVERDUE` with days = daysOverdue. In-app "Payment overdue" — "Your payment of {amount} is overdue by {daysOverdue} day(s). Please pay now — a late penalty of 2%/day is accruing and your credit score may be impacted."; SMS "Dear {name}, repayment of {amount} on your DhanBoost loan {loanId} is overdue by {daysOverdue} day(s). Pay at https://dhanboost.com/login. - DhanBoost"; email "Your DhanBoost payment is overdue"; WhatsApp `db_payment_overdue` (name, amount, loanId, daysOverdue).
  - Otherwise nothing (more than 7 days out, or past the overdue window — collections owns it).
- **Business rules:** at most one reminder per loan per run (the daily cadence is the de-dup); {amount} is the outstanding as of today; one bad loan never stops the sweep. Reminders stop the moment the balance reaches 0.
- **Effective behaviour today:** because step 1 moves a past-due loan to `IN_COLLECTIONS` and the next run only scans `ACTIVE`/`OVERDUE`, a grace-0 loan receives exactly **one** overdue reminder (DPD 1) and a grace-1 loan receives **none** (its DPD-1 run is still inside the due-soon branch). See §Z.

### UC-S2-42 Outstanding balance — computation and worked examples
- **Where used:** the one figure behind `/api/loan/{id}`, `/outstanding`, the Loans register, the DPD worklist and bucket cards, settlements, the debit sizing, the borrower dashboard and repay page, reminders, reports. `loan.outstanding` is only a cache; the loan closes only when this figure reaches 0.
- **Formula** (`RepaymentService.breakdown`, integer paise, HALF_UP):
  - `at` = as-of date (default today IST), but never later than `closed_on` for a closed loan (frozen).
  - `tenure = days(disbursed_on → due_date)`; `held = max(0, days(disbursed_on → at))`.
  - `interestDays = min(held, tenure + grace_days)`; `interest = round(P × 1% × interestDays)` — always on the **original principal** (part payments do not shrink the base).
  - `dpd = max(0, at − due_date)`; `penaltyDays = max(0, dpd − grace_days)`; `penalty = round(P × 2% × min(penaltyDays, 30))`.
  - `formula = max(0, P + interest + penalty − Σ VERIFIED payments)`.
  - With an approved settlement S (newest approval): `owed = min(formula, max(0, S − Σ VERIFIED))`.
  - **Close decision** is evaluated as of the **latest verified payment's paid-on date**, not the verification date; if not closed, the cache tracks today.
- **Worked examples** — P = ₹10,000 (fee ₹1,000, GST ₹180, net disbursed ₹8,820), disbursed 2026-06-03, salary day 30 → due 2026-06-30 (tenure 27). Interest ₹100/day, penalty ₹200/day.

| # | Situation | Interest | Penalty | Paid | Owed |
|---|---|---|---|---|---|
| 1 | Paid in full on the due date (06-30) | 27 d = ₹2,700 | 0 | — | **₹12,700** |
| 2 | Prepaid on 06-10 (7 days held) | 7 d = ₹700 | 0 | — | **₹10,700** |
| 3 | Unpaid on 07-05, grace 0 (DPD 5) | min(32, 27) = 27 d = ₹2,700 | 5 d = ₹1,000 | — | **₹13,700** |
| 4 | Unpaid on 07-05, legacy grace 1 | min(32, 28) = 28 d = ₹2,800 | 5−1 = 4 d = ₹800 | — | **₹13,600** |
| 5 | Unpaid on 08-14, grace 0 (DPD 45, cap) | 27 d = ₹2,700 | min(45, 30) = 30 d = ₹6,000 | — | **₹18,700** (stops growing after 07-30) |
| 6 | ₹5,000 verified (paid 07-02), as of 07-05, grace 0 | ₹2,700 | ₹1,000 | ₹5,000 | **₹8,700** |
| 7 | Settlement S = ₹8,000 approved, ₹2,000 already verified, as of 07-05 | ₹2,700 | ₹1,000 | ₹2,000 | formula ₹11,700, cap ₹6,000 → **₹6,000** |
| 8 | Same, borrower pays ₹6,000 on 07-08, validated 07-10 | ₹2,700 | 8 d = ₹1,600 | ₹8,000 | formula ₹6,300, cap 0 → **₹0, closes on 07-08** |
| 9 | Full ₹12,700 paid 06-30, verified on 07-02 | ₹2,700 (as of 06-30) | 0 | ₹12,700 | **₹0, closes 06-30** (verification lag costs nothing) |
| 10 | NACH at DPD 1 (07-01), grace 0: debit sized for settlement 07-02 | ₹2,700 | 2 d = ₹400 | — | backend null-amount debit ₹13,100; UI default (today) ₹12,900 → ₹200 residue |

- **Edge cases:** a REJECTED payment is never summed; a closed loan never accrues after `closed_on`; DPD in the register for a closed loan = days late at closure; a settlement above the formula balance changes nothing (min).

---

## Z. Contradictions and gaps found

1. **Settlements dashboard is not shipped.** `app/staff/collections/settlements/dashboard/page.tsx` is untracked; `collectionsApi.settlementDashboard` and any backend endpoint are missing; no nav entry (UC-S2-22).
2. **Hardship / revised repayment plan is schema-only** (`repayment_plan` table + entity with UUID staff ids, no service/endpoint/UI) (UC-S2-23).
3. **Payment reminders:** (a) the cron has no `zone`, unlike the NACH sweep — CLAUDE.md's "payment reminders 09:00" is 09:00 *JVM zone* (UTC on ECS ⇒ 14:30 IST) unless `NAVIX_REMINDERS_CRON`/TZ says otherwise; (b) the sweep scans only `ACTIVE`/`OVERDUE` but flips past-due loans to `IN_COLLECTIONS` first, so the documented "first 7 overdue days" window sends one overdue reminder (grace 0) or none (grace 1); (c) the scheduler javadoc still says "day-after-salary grace day".
4. **Agency-handover export is ADMIN-only.** The export menu renders only for an ADMIN in the “Admin” working role and the handover read is ADMIN-only; a Collection Head has no export from the DPD register.
5. **Auto-assign is conditional.** CLAUDE.md says every newly disbursed loan is auto-assigned; the code does so only when `navix.collections.auto-assign-officer-id` is set to an ACTIVE Collection Executive, and always to that single officer.
6. **COLLECTION_HEAD lacks `collections:interact`** (frontend), so the Head working role cannot log interactions, propose settlements or record payments from the UI although the backend allows it; switching to the Executive working role then scopes the Head to cases assigned to themselves. ADMIN in the Admin working role lacks `collections:manage`/`collections:interact` (read-only collections).
7. **Settlement guardrails are thin:** proposal only checks amount > 0 (no cap vs outstanding, no minimum recovery, no one-open-settlement rule). The Record-payment card never sends a settlement id, so with two live settlements on a case a SETTLEMENT payment always fails `SETTLEMENT_REQUIRED` with no picker.
8. **Settlement amount semantics vs borrower message:** the cap is `S − already-verified`, i.e. S is the total full-and-final including past payments, but `SETTLEMENT_APPROVED` tells the borrower "A full & final settlement of {settlementAmount} is approved … Pay it to close." — overstates what is left when something was already paid.
9. **Loss vs haircut:** the settlements register's *Loss* = gross dues (before payments) − S; the dashboard page's *haircut* = (today's outstanding − S) ÷ outstanding. Different bases for the same concession.
10. **Settlement approval copy** says the balance is written off "once the settlement payment is booked", but the cap applies at approval and the loan can close inside the approval itself.
11. **Manual debit sizing:** the backend sizes a null amount to the balance owed on the settlement date (tomorrow) to avoid a one-day residue; the UI always sends an explicit amount defaulting to *today's* outstanding, recreating that residue (UC-S2-42 row 10).
12. **NACH credits are stored as `BANK_TRANSFER`,** not `NACH` (they go through `creditCollectionPayment`); the `NACH auto-debit` method label in the verify queue is never produced by the debit path. `RepaymentController` javadoc still says "NACH auto-debit is [FUTURE]".
13. **Loans register search:** placeholder promises "application ID" but the server matches only name, PAN, mobile and loan id.
14. **Transactions ledger search:** empty-state hint says "Try a different name, PAN or reference" but the server matches only borrower name, mobile and loan id.
15. **Comment drift:** `CollectionsService.openCase` says opening a case "notifies the borrower" — `COLLECTION_CASE_OPENED` goes in-app to Collection Heads/Executives only; `RepaymentService` javadoc says "late penalty past the 1-day grace" — grace is per loan (0 since V91); `DpdBucket.T0_T7` javadoc says "0-7" — code and UI are 1–7; the `InteractionLog` entity suggests FIELD_VISIT/DISPUTE while the UI sends VISIT/DISPUTED (backend accepts any string; the entity's "TODO: enforce PAID requires proofRef" is already enforced).
16. **Paid-on default** on the Record-payment card is `new Date().toISOString().slice(0,10)` — a UTC date, so between 00:00 and 05:30 IST it pre-fills yesterday.
17. **`verifyPayment` does not refuse a REJECTED payment** server-side (any non-VERIFIED row is flipped to VERIFIED); only the UI hides Verify on rejected rows.
18. **Executive with no case on a debited loan:** the sweep can present a debit on a loan that has no collection case; its debit history/check/cancel is then Head/ADMIN-only (`CASE_NOT_FOUND` for an executive).
19. **No webhook, no refund workflow:** Digio debit outcomes are poll-only (sweep + "Check status"); Refunds owed and PayU review have no "refunded" state — resolution is a note only.
