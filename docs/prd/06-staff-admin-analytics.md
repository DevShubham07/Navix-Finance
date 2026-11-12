# 06 · Staff analytics, reports, search and admin pages — use cases

This file is part of the DhanBoost rebuild catalogue. It adds the detail that `docs/PRD_REBUILD.md` leaves out.
Scope: the staff dashboard (every Admin tab and every per-role view), staff performance, decision history, the reports centre, the customer data export, global search, and the admin back-office pages.
Each use case says what the person sees and does, and gives every metric as an exact rule (which rows, which status, which date, which period).
The code is the source of truth. Where it disagrees with CLAUDE.md, a code comment or the on-screen copy, the use case says so, and §Z lists every contradiction.
Request and response payloads are left out on purpose. Endpoint paths appear only for the admin jobs that have no screen.

## Table of contents

- §0 Conventions that apply to every use case
- §A Staff dashboard (`/staff/dashboard`)
  - UC-S4-01 Open the dashboard: header, queue strip, view toggle, period, refresh
  - UC-S4-02 Drill into the records behind any number (records drawer)
  - UC-S4-03 Business Snapshot: business position
  - UC-S4-04 Business Snapshot: KPIs, financial overview, status, closures, collections, PF/ROI tables, performance summary
  - UC-S4-05 Business Snapshot: monthly widgets
  - UC-S4-06 Edit monthly targets
  - UC-S4-07 Business Snapshot: revenue, interest and PF, pre-closure week, AUM, collection analysis
  - UC-S4-08 Team & Performance tab
  - UC-S4-09 Leaderboards and the 0–10 score
  - UC-S4-10 Calendar tab
  - UC-S4-11 Map tab
  - UC-S4-12 Company-wise tab
  - UC-S4-13 Credit Executive Report and Sales Ops Report tabs
  - UC-S4-14 Collection Allocation tab
  - UC-S4-15 Credit Head and Credit Executive views
  - UC-S4-16 Collection Head and Collection Executive views
  - UC-S4-17 Telecaller view
  - UC-S4-18 Disbursement Head view
  - UC-S4-19 Accountant view
- §B Staff performance and decision history
  - UC-S4-20 Staff performance register
  - UC-S4-21 One staffer's performance pop-up
  - UC-S4-22 Decision history (`/staff/my-decisions`)
- §C Reports
  - UC-S4-23 Reports centre (43 reports)
  - UC-S4-24 Customer data export
- §D Global search
  - UC-S4-25 Search the console (Cmd/Ctrl+K)
- §E Staff accounts and access
  - UC-S4-26 List and create staff accounts
  - UC-S4-27 Change a staffer's role or status, disable, enable
  - UC-S4-28 Issue a staff invite
  - UC-S4-29 Activate an account from an invite (`/staff/activate`)
  - UC-S4-30 Staff self-profile
  - UC-S4-31 Staff forgot and reset password
- §F Admin registers and settings
  - UC-S4-32 Fraud blocklist
  - UC-S4-33 Company expenses (with receipt)
  - UC-S4-34 Settings: pause new-customer onboarding
  - UC-S4-35 Settings: close applications and reloans
  - UC-S4-36 Settings: payment gateway switch
  - UC-S4-37 Settings: company payment block and UPI QR
  - UC-S4-38 Onboarding waitlist register
  - UC-S4-39 All-applications register
  - UC-S4-40 Rejections register
- §G Provider operations
  - UC-S4-41 Run a provider API from the workbench
  - UC-S4-42 Browse provider call history
  - UC-S4-43 Provider health alerts (system)
  - UC-S4-44 Bureau rescore backfill (API only)
  - UC-S4-45 Sub-floor sweep (API only)
  - UC-S4-46 Bureau-challenge outreach (API only)
  - UC-S4-47 Recompute outstanding balances (API only)
- §H Admin eSign documents
  - UC-S4-48 Create and send a PDF for Aadhaar eSign
  - UC-S4-49 Track, refresh, resend and download an eSign document
  - UC-S4-50 Signer returns to `/esign/done`
- §Z Contradictions found

---

## §0 Conventions that apply to every use case

**Time and periods**
- Every date filter is an **IST calendar date**. A timestamp column is cast with `at time zone 'Asia/Kolkata'`. A period is inclusive at both ends.
- The shared period picker (dashboard, staff performance, decision history) offers **Today, Yesterday, This week, Last week, This month, Last month, This quarter, Last quarter, This FY, Last FY, All time, Custom**.
  - A week starts on **Monday**. "Last week" is last Monday to last Sunday.
  - The financial year runs **1 Apr – 31 Mar**.
  - A custom range is committed with **Apply**, not on each keystroke.
- The reports centre adds **Last 7 days** (today and the 6 days before) and **Last 30 days** (today and the 29 days before).
- On the dashboard, the default preset is **This month** (1st of the month to today).
  - "All time" is sent as the last **1,095 days**, because the backend refuses a span over **1,100 days** (`INVALID_RANGE`).
  - A `from` after `to` is also `INVALID_RANGE`. A malformed date is `INVALID_DATE` ("Dates must be yyyy-MM-dd").
- **"Previous period"** means the window of the same length that ends the day before `from`. For 10–19 Oct, the previous period is 30 Sep – 9 Oct.

**Fresh / Re-loan (dashboard only)**
- An application is **Re-loan** when the same customer has *another* application whose loan is `CLOSED` with `closed_on` on or before this application's IST creation date. Every other application is **Fresh**.
- `DRAFT` applications are excluded from every dashboard metric.
- The reports centre uses a different rule; see §Z.

**Who may open what**
- **Authorization is checked twice.**
  - The **UI** gates each nav item and page by the **working role**: `can(real, working, permission)` reads only the working role's permission list.
  - The **backend** checks the **real JWT role**.
  - So a real ADMIN who has switched the working role to, say, Credit Executive loses every Admin nav item, and the admin pages show "Admin access only."
  - When working as Admin, an ADMIN gets the narrower `ADMIN_ROLE_PERMISSIONS` set: `staff:manage`, `customer:view`, `customer:view:all`, `customer:manage`, `customer:assign`, `verification:retry`, `dsa:manage`, `waitlist:view`, `loan:register`, `collections:overview`, `document:upload`, `leads:import`, `message:send`, `mandate:override`, `mandate:debit`.
- **DSA is an explicit exclusion** on every surface in this file. The dashboard, decision history, performance and search all reject DSA by name with `FORBIDDEN_ROLE`, and the UI hides the nav entries for DSA.
- Business rejections come back as HTTP 422 with a code, except where a use case says otherwise.

**Money and formatting**
- All money is integer paise. It is shown as ₹ on screen, and as "Rs." in PDFs (the PDF font has no ₹ glyph).
- A ratio with a zero denominator is **null**. It renders as "—", never as 0%.

**Refresh**
- Dashboard analytics queries cache for 5 minutes and re-poll every 5 minutes. The header **Refresh** button invalidates every `staff-dashboard-*` query at once.

**Fields left out of a use case**
- *Preconditions*: none beyond the actor and permission line.
- *Main flow*: read the screen, hover for exact figures, click to drill down (UC-S4-02).
- *Alternate flows*: the shared per-widget ErrorState with Retry.
- *Postconditions*: read-only, nothing written.

---

## §A Staff dashboard (`/staff/dashboard`)

### UC-S4-01 Open the dashboard: header, queue strip, view toggle, period, refresh
- **Actor / working role / permission:** any staff role except DSA. Nav item "Dashboard" (Overview group), hidden for DSA.
- **Entry point:** sidebar → Dashboard. It is also the landing page after staff login (a DSA lands on `/staff/dsa/leads` instead).
- **Preconditions:** a valid `navix_staff` session.
- **Screen:**
  - **Header.** "Welcome, {first name}", with the subtitle "{working-role label} · live business analytics". A **Refresh** button shows a spinner while any analytics query is fetching.
  - **Queue strip ("Your work")**, driven by the *working* role. Each role has its own label and ⓘ text:

    | Working role | Label | What is counted (live, polled) |
    |---|---|---|
    | Credit Executive | "Leads to decide" | applications in `CREDIT_EXEC_PENDING` visible to them |
    | Credit Head | "Leads to assign" | the credit assignment queue |
    | Disbursement Head | "Approved loans to release" | `DISBURSEMENT_PENDING` + `DISBURSEMENT_FAILED`, plus "Referral payouts to settle" (pending payouts) when the `referral` flag is not explicitly off |
    | Accountant | "Repayments to verify" | pending repayments ("Repayments to verify") + collections payments in `PENDING_ACCOUNTANT` ("Collections payments to validate") |
    | Collection Head | "Settlements awaiting your approval" | settlements in `PROPOSED` ("Settlements to approve" → `/staff/collections/settlements`) |
    | Collection Executive | "Open collection cases" | "Your open collection cases" |
    | Telecaller, Admin | no strip | — |

    - Small queues poll every 10 s; whole-book lists poll every 60 s. Each link deep-links to `/staff/applications`, except settlements and referral payouts.
    - Each source is fetched on its own. If any source fails, the strip shows an error with a retry link. It never shows "all caught up" when it could not load.
  - **View toggle strip.** A dashboard-local pill row that never touches the header role switcher. It is shown only when the real role may open more than one view:
    - **ADMIN:** all eight views — Admin overview, Credit Head, Credit Executive, Collection Head, Collection Executive, Telecaller, Disbursement Head, Accountant.
    - **CREDIT_HEAD:** Credit Head, Credit Executive.
    - **COLLECTION_HEAD:** Collection Head, Collection Executive.
    - **Everyone else:** their own view only (strip hidden).
    - The active pill is filled with its domain colour: Admin navy, Credit emerald, Collection red, Telecaller orange, Disbursement violet, Accountant sky.
  - **Period picker.** See §0.
  - **Admin tab bar** (Admin overview only): **Business Snapshot · Team & Performance · Calendar · Map · Company-wise · Credit Executive Report · Sales Ops Report · Collection Allocation**.
- **Main flow:**
  1. Staffer opens the page.
  2. The system resolves the view. It uses `?view=` when that view is allowed. Otherwise a real ADMIN gets **Admin overview**, and anyone else gets their working role if it is allowed, else the first allowed view.
  3. Staffer picks a period. The URL carries `preset`, `from` and `to`; "This month" is not written to the URL.
  4. Staffer picks a tab (`?tab=`; Business Snapshot is the default and is not written).
  5. Switching view clears `tab`.
  6. All widgets refetch with `{view, from, to}`.
- **Business rules:**
  - **Server-side scope (`DashboardScope`).** `view` must equal the real role, or be a view that `ActingRole.allowed(real, view)` permits; otherwise `FORBIDDEN_ROLE` "You cannot open the {view} dashboard view".
    - **ADMIN view:** org-wide (the whole book).
    - **Credit Head view:** roster = active Credit Heads + Credit Executives.
    - **Collection Head view:** roster = active Collection Heads + Collection Executives.
    - **Executive and telecaller views:** roster = active holders of that role.
    - A *real* Credit Executive, Collection Executive or Telecaller in their own view is forced to roster = **[self]**, whatever is requested.
    - Disbursement and Accountant views have **no roster**. They see the whole book.
  - **Book scoping.**
    - Credit views see only applications **assigned to or sanctioned by** the roster (`assigned_executive_id` or `sanctioned_by` in the roster).
    - Collection views see only loans that sit on a **collection case assigned to** the roster.
  - **Each endpoint whitelists views.** For example, the Business Snapshot is ADMIN only, monthly allows ADMIN and the two collection views, and daily allows ADMIN, Disbursement Head and Accountant. Calling an endpoint from another view returns `FORBIDDEN_ROLE` "The {view} view cannot read this data".
  - The API also accepts a `staffIds` filter, narrowed to the roster, but **no UI control sends it**. It is only used when the drawer is reopened with the same params.
- **Alternate & error flows:**
  - A real DSA (or no resolvable view) sees "DSAs use the leads and earnings pages — see the DSA menu."
  - A widget that fails shows its own ErrorState with Retry. The other widgets keep working.
- **Postconditions:** read-only. Nothing is written.
- **Edge cases / not allowed:** the Collection Head's "team today" table (`/api/dashboard/collections/team`) is **not** on this page. It lives on the Collection Head desk (collections file).

### UC-S4-02 Drill into the records behind any number (records drawer)
- **Actor / working role / permission:** whoever can see the clicked figure. The metric's own whitelist is enforced again on the server.
- **Entry point:** clicking any card, bar, table cell, calendar day, map region or leaderboard row that is marked clickable.
- **Preconditions:** the metric is readable by the current view, or the server returns `FORBIDDEN_ROLE` "The {view} view cannot read {metric}". A staff-keyed metric (`STAFF_FILES` / `STAFF_CASES`) with a key outside the roster returns "That staff member is outside your team".
- **Screen:**
  - **Header:** a right-hand drawer with the clicked figure's title and the period label ("01 Oct – 09 Oct 2026").
  - **Segment pills:** **All · Fresh · Re-loan**, each with its count. The counts ignore the segment filter.
  - **Search box:** "Search name or application #". It matches a case-insensitive substring of the customer's full name, or the exact application id.
  - **Total line:** "{n} records · ₹{sum}". This is the server's total for **the same SQL fragment** that produced the clicked number, so the two always agree.
  - **Table columns:** **App # · Customer · Status · Amount · Dates · Assignee · State**.
    - The customer is shown with the **last 4 digits of the mobile only**.
    - Dates = disbursed, due and closed.
    - Re-loan rows carry a "Re-loan" tag.
  - **Paging:** 50 rows per page, with "Load more ({shown} of {total})".
  - **Export CSV** button: **real ADMIN only**. It is disabled while the total is 0.
- **Main flow:**
  1. Staffer clicks a figure. The drawer opens with `{metric, key?, segment?, title, range?}`.
     - A clicked day or month narrows the period to exactly that day or month.
     - An AUM bucket uses the as-of date.
  2. Staffer switches segment or types a search. The page refetches.
  3. Staffer clicks a row. The **application detail dialog** opens on top. Esc closes only the dialog.
  4. Admin clicks **Export CSV**. The browser pages through the records in **500s** until it has `total` rows, then writes `dashboard-{metric}.csv`.
     - Columns: Application, Loan, Customer, Mobile (last 4), Status, Segment, Amount (INR), Owed (INR), Disbursed on, Due date, Closed on, Assignee, State.
- **Business rules:**
  - The server page size is capped at **500**.
  - Results are ordered by application id descending.
  - **Amount** is the metric's own money:
    - the priced figure for the four `POSITION_*` metrics;
    - "what the loan had to pay" (see UC-S4-05) for `DUE_ON`, `COLLECTION_MONTH` and `COLLECTION_GROUP`;
    - otherwise the stored column (principal, requested or sanctioned).
  - **Owed** is always the live, penalty-aware outstanding as of today. AUM rows are priced as of the AUM date.
  - Metrics flagged `owedSum` have a total that is the sum of live outstanding, not an SQL sum. These are `PENDING_ON`, `AUM_BUCKET`, `CALENDAR_DUE_OPEN` and the priced positions.
  - An unknown metric returns `INVALID_METRIC`. A bad key returns `INVALID_KEY`. A bad segment returns `INVALID_SEGMENT` "segment must be ALL, FRESH or RELOAN".
- **Postconditions:** read-only. The CSV is formula-neutralised and BOM-prefixed (see `lib/export`). On the simulation stack the file name is prefixed `SIMULATION-`.
- **Edge cases / not allowed:** the marketing-conversion chart and the telecaller cards have **no** drill-down (leads are not applications).

### UC-S4-03 Business Snapshot: business position
- **Actor / working role / permission:** dashboard view **Admin overview** (`snapshot` is ADMIN view only).
- **Entry point:** Admin overview → Business Snapshot (default tab). This is the first row on the page.
- **Preconditions:** none.
- **Screen:**
  - An explainer line: "Every card follows the same loans: those **disbursed** in the selected period, valued as of today. A loan disbursed earlier is not counted here even if it was repaid in this period — Collection Amount below shows that."
  - Six gradient cards. Each card prints its definition on the card (phones have no hover). Hovering shows the exact ₹ and the loan count; clicking opens the drawer.

    | Card | Caption | Exact value (cohort = loans with `disbursed_on` in period) | Drawer metric |
    |---|---|---|---|
    | Principal disbursed | "Loan amount given out" | Σ `loan.principal` | `POSITION_PRINCIPAL` |
    | Net disbursed | "Cash sent to customers' banks" | Σ `loan.net_disbursed` (principal − 10% fee − 18% GST on fee; ₹100 → ₹88.20) | `POSITION_NET` |
    | Net receivable | "Principal + interest so far" | Σ (principal + interest accrued to today by `RepaymentService`). Interest stops at due date + the loan's grace days | `POSITION_RECEIVABLE` |
    | Total penalty | "Late fees" | Σ penalty (2%/day on principal after due + grace, capped at 30 days) | `POSITION_PENALTY` |
    | Net received | "Repayments verified" | Σ `VERIFIED` payments on these loans, **whenever paid** | `POSITION_RECEIVED` |
    | Outstanding | "Still to collect today", or "Still to collect · ₹X waived" | Σ live outstanding (floored at 0; an approved settlement caps it) | `POSITION_PENDING` |

  - The Outstanding pop-up adds:
    - "Waived via settlements: ₹X". For a loan with an approved settlement, waived = max(0, principal + interest + penalty − verified − outstanding).
    - "Overpaid by borrowers: ₹Y". For a loan without a settlement, overpaid = max(0, verified − principal − interest − penalty).
    - The reconciliation line "Receivable + penalty − received − waived + overpaid".
- **Main flow:** the admin reads the cards, hovers for exact figures, and clicks to list the loans.
- **Business rules:** every figure is priced by `RepaymentService` as of **today**. Nothing is re-derived in SQL.
- **Alternate & error flows:** an older backend without `position` hides the row.
- **Postconditions:** none.
- **Edge cases / not allowed:** a loan disbursed before the period but repaid in it is **not** counted here.

### UC-S4-04 Business Snapshot: KPIs, financial overview, status, closures, collections, PF/ROI tables, performance summary
- **Actor / working role / permission:** Admin overview view.
- **Entry point:** Business Snapshot, below the business position.
- **Screen:**
  - **Key Performance Indicators**: four gradient cards.
    - Each card shows the big number, a ▲/▼ delta chip versus the previous period (hidden when the previous value is 0 or null), and a Fresh / Re-loan split with a share bar.
    - The hover pop-up shows Fresh n · ₹, Re-loan n · ₹, Previous period n · ₹, and Share where applicable, ending with "Click to view records".

    | Card | Metric | Exact rule |
    |---|---|---|
    | Total Applications | `APPLICATIONS` | non-DRAFT applications whose IST `created_at` date is in the period; ₹ = `coalesce(amount_requested, sanctioned_amount_paise)` |
    | Disbursed Cases | `DISBURSED` | loans whose `disbursed_on` is in the period; ₹ = principal. Share = disbursed ÷ applications |
    | Pending Cases | `PENDING` | applications created in the period now in `KYC_PENDING`, `KYC_APPROVED`, `CREDIT_EXEC_PENDING`, `SANCTIONED`, `DISBURSEMENT_PENDING`, `PRE_APPROVED` or `DISBURSEMENT_FAILED` |
    | Rejected Cases | `REJECTED` | applications created in the period now `REJECTED` or `KYC_REJECTED` |

  - **Financial Overview**: four money cards.
    - **Total Loan Amount** = Σ principal of `DISBURSED`.
    - **Sanctioned, Awaiting Borrower** = applications created in the period now `SANCTIONED`, ₹ = sanctioned amount (`PENDING_SANCTIONED`).
    - **Awaiting Disbursal** = created in the period now `DISBURSEMENT_PENDING`, ₹ = requested amount (`PENDING_DISBURSAL`).
    - **Average Loan Size** = Σ principal ÷ count of DISBURSED, rounded half-up, with Fresh and Re-loan averages. Clicking it lists the DISBURSED loans.
  - **Status Distribution**: a bar chart of Disbursed / Pending / Rejected counts. Tooltip: Applications, Share %. Clicking a bar opens that metric.
  - **Closed Cases**:
    - A headline button "Closed + Settlement {n}", with "{x%} of disbursed · Click to view loans". x% = closed ÷ DISBURSED count.
    - Three bars:
      - **Closed (incl. settlement)** (`CLOSED`): loans with status CLOSED and `closed_on` in the period.
      - **of which Settlement** (`SETTLED`): CLOSED in the period and having an `APPROVED` settlement.
      - **Part payment** (`PART_PAID`): loans not CLOSED with a `VERIFIED` payment flagged `partial` and `paid_on` in the period.
  - **Collection Amount**: a navy pill "Total Collected ₹X" plus three bars. Each bar has a tooltip with its share of the total.
    - **Closed loans** = Σ `VERIFIED` payments with `paid_on` in the period on loans now CLOSED without an approved settlement.
    - **Settlements** = the same, on CLOSED loans with an approved settlement.
    - **Part payments** = verified payments in the period on loans not CLOSED.
  - **PF % Table** and **ROI % Table**:
    - The DISBURSED cohort, grouped by `round(processing_fee × 100 ÷ principal, 2)` and by `round(daily_interest_rate × 100, 2)` respectively.
    - Columns: **Rate % · New · Repeat · Total cases · Principal · Net disbursed · Total repayable**.
    - New = Fresh, Repeat = Re-loan. Clicking a New or Repeat count opens that rate and segment; a zero count is disabled.
  - **Performance Summary**: three tiles.
    - **Disbursement rate** = DISBURSED count ÷ APPLICATIONS count.
    - **Pending rate** = PENDING ÷ APPLICATIONS.
    - **Rejection rate** = REJECTED ÷ APPLICATIONS.
    - Each tile opens its metric.
- **Business rules:** see each card. The ⓘ copy on Status Distribution says "Applications created in the period", but the Disbursed bar counts by **disbursal date** (§Z-1).
- **Alternate & error flows:** a KPI error shows one error card with Retry.
- **Edge cases / not allowed:** the Collection Amount bars open the CLOSED, SETTLED and PART_PAID drawers. Those list **loans closed in the period**, not the payments summed in the bar (§Z-2).

### UC-S4-05 Business Snapshot: monthly widgets
- **Actor / working role / permission:** Admin overview (all widgets). Collection Head and Collection Executive views show only the top three (`topOnly`).
- **Entry point:** Business Snapshot, below the closures row.
- **Preconditions:** the last **4** months are shown. They end in the month of the period's `to` date; the API accepts 1–12 months.
- **Screen:**
  - **Collection % (last 4 months)**: month chips plus a composed chart of Owed bars, Collected bars and a Collection % line. The tooltip adds Loans due, Target % and Deficit ₹.
    - **Month of a loan** = the month of its **due date**.
    - **Loans due** = count of loans with `due_date` in that month (in scope).
    - **Owed ("what the loan had to pay")**:
      - Closed loan: principal + interest + penalty accrued up to the day it closed. A settlement waiver is *not* subtracted.
      - Open loan: the larger of `total_repayable` and principal + interest + penalty accrued so far.
    - **Collected** = Σ **all** VERIFIED payments on those loans, whenever paid, so a month keeps rising as late money lands.
    - **Collection %** = collected ÷ owed.
    - Clicking a month opens `COLLECTION_MONTH` for it.
  - **Pre-Closure % (last 4 months)**: a gauge with a month selector.
    - Value = loans due in the month that were CLOSED with `closed_on < due_date`, ÷ loans due that month.
    - Caption "{n} closed before due date". Clicking opens "Loans due (pre-closure base)".
  - **Deficit Collection**: a month selector with per-month bars.
    - Target amount = round(owed × target %).
    - **Deficit ₹** = max(0, target amount − collected).
    - **Deficit %** = max(0, target % − collection %).
    - The target is the month's saved `collection_target_bp`, or **8,800 bp (88%)** when none is saved.
  - **Target vs Achieved** (Admin only): Target vs Achieved bars with a month selector, and an **Edit targets** button (real ADMIN only; UC-S4-06).
    - **Achieved** = Σ principal of loans with `disbursed_on` in the month. This is **org-wide**, not scoped.
    - **Achieved %** = achieved ÷ the saved disbursal target. It shows "No target set" when the target is null or 0.
    - Clicking a bar opens DISBURSED for that month.
  - **Re-loan Retention**: four bars over loans with `due_date` in the **selected period** (not the 4-month window).
    - **Due** = all such loans.
    - **Closed** = those CLOSED.
    - **Re-loan** = CLOSED and the same customer has another non-DRAFT application created on or after `closed_on`.
    - **No repeat** = closed and not re-loaned.
    - The retention ratio is re-loan ÷ closed. The tooltip shows "Share of due". Each bar opens `RELOAN_RETENTION` with its key.
  - **Marketing Conversion** (Admin only): the last **6** months of leads.
    - Converted = a lead with a PAN that matches a customer profile whose loan was disbursed on or after the lead's IST creation day.
    - % = converted ÷ leads.
    - **No drill-down** ("Leads are marketing records, not applications").
- **Business rules:**
  - Owed is priced as of today.
  - A collection view sees only loans on its roster's cases.
  - Disbursal target and marketing are blank outside the Admin view.
- **Edge cases / not allowed:** a future month shows low collection by nature.

### UC-S4-06 Edit monthly targets
- **Actor / working role / permission:** **real ADMIN** only. The PUT checks the real role and returns `FORBIDDEN_ROLE` "Only ADMIN can set targets". Reading targets is open to any non-DSA staffer.
- **Entry point:** Target vs Achieved → **Edit targets**.
- **Screen:**
  - A dialog titled "Monthly targets", with the subtitle "Disbursal target in rupees and the collection-% target the deficit is measured against (default 88%)."
  - One row per month, from 5 months ago to 6 months ahead (12 rows).
  - Columns: **Month · Disbursal target (₹)** (number ≥ 0, placeholder "Not set") · **Collection %** (0–100, step 0.5) · **Save**.
- **Main flow:**
  1. Admin edits a row and clicks **Save** (shows "Saving…").
  2. The system upserts `monthly_target(month)` with the disbursal target in paise (null = not set), the collection target in **basis points** (% × 100) and `updated_by`.
  3. Toast "Saved {Month YYYY} targets". The monthly widgets refresh.
- **Alternate & error flows:**
  - Client-side: "Disbursal target must be zero or more." / "Collection target must be between 0 and 100%."
  - Server-side: `INVALID_TARGET` "The collection target must be between 0 and 10000 bp" / "The disbursal target cannot be negative". A bad month returns `INVALID_DATE` "Months must be yyyy-MM".
  - Server read range: at most 36 months (`INVALID_RANGE`).
- **Postconditions:** the row is upserted. There is no audit-trail event beyond `updated_by` / `updated_at`.

### UC-S4-07 Business Snapshot: revenue, interest and PF, pre-closure week, AUM, collection analysis
- **Actor / working role / permission:**
  - Revenue and Interest & PF: views ADMIN, DISBURSEMENT_HEAD, ACCOUNTANT (`/daily`).
  - Pre-closure week: ADMIN and the collection views.
  - AUM: ADMIN only.
  - Collection analysis: ADMIN and COLLECTION_HEAD.
- **Entry point:** Business Snapshot, lower half. Some of these widgets reappear on the role views.
- **Screen:**
  - **Revenue Performance**: a cumulative area line, with chips for PF, Interest and Penalty totals.
    - Per day: **PF** = Σ `processing_fee` of loans with `disbursed_on` = that day.
    - **Interest** and **Penalty** = the interest and penalty accrued (frozen at closure) on loans with `closed_on` = that day.
    - Cumulative = running Σ (PF + interest + penalty).
    - "GST is not revenue."
  - **Interest & PF (daily)**: interest bars (loans closed that day) and PF bars (loans disbursed that day).
    - In the Admin view, clicking a day opens `CALENDAR_DISBURSED` for that day.
    - In the Disbursement Head view, it opens `DISBURSED`.
    - The Accountant view has no click.
  - **Pre-Closure (next 7 days)**: subtitle "Repayments due in the 7 days after the selected date", with an anchor-date input (default today).
    - The table has one row per day from anchor + 1 to anchor + 7, plus a total row.
    - **Total due:** loans with `due_date` that day. ₹ = what each had to pay.
    - **Pre-closed:** due that day, CLOSED with `closed_on < due_date`. ₹ = verified payments.
    - **Pending:** due that day and not CLOSED. ₹ = live outstanding.
    - **Received:** due that day, not CLOSED, with verified payments > 0. ₹ = verified sum.
    - **Collection %** = (pre-closed ₹ + received ₹) ÷ total-due ₹.
    - Each cell opens its metric for that day (`DUE_ON`, `PRECLOSED_ON`, `PENDING_ON`, `RECEIVED_ON`).
  - **Assets Under Management**: as of its **own as-of date input** (default today; the period picker does not apply). It has Donut/Bar toggles, chips and a bucket table.
    - **Book** = loans with `disbursed_on ≤ as-of`, not closed by the as-of date, with a due date.
    - **Bucket** by (as-of − due_date): ≤0 **Running**, ≤30 **1-30 days**, ≤60 **31-60 days**, ≤90 **61-90 days**, else **90+ days**.
    - Table columns: **Bucket · Cases · Principal · Owed** (penalty-aware, priced as of the as-of date) **· % of cases**.
    - Chips: **Current**, **1-60 days**, **61-90 days**, **90+ days** (case share and count).
    - Clicking a bucket opens `AUM_BUCKET` as of that date.
  - **Collection Analysis**: a date input (default today) and a **By Credit Exec / By State** toggle.
    - Rows cover the loans due on that date, grouped by `assigned_executive_id` ("Unassigned" when null) or by state.
    - State is read from verification data in this order: the ADDRESS check, then AADHAAR, then PAN `addressState`, otherwise "Unknown".
    - Each row shows: {n} loans · Principal · **Owed** (what they had to pay) · **Collected {count}/{n} · ₹** · Avg principal · **Collection %** (collected ₹ ÷ owed ₹).
    - Collected count = loans CLOSED or with nothing left to pay today.
    - Empty state: "No loans due on this date". A row opens `COLLECTION_GROUP`.
- **Business rules:** `groupBy` must be EXEC or STATE (`INVALID_GROUP`).
- **Edge cases / not allowed:** "By Credit Exec" groups by the **credit** executive on the application, not by the collection officer.

### UC-S4-08 Team & Performance tab
- **Actor / working role / permission:** Admin overview.
- **Entry point:** Admin tab "Team & Performance".
- **Screen:**
  - **Employee Snapshot**: four rows, each clickable to list those people (name + role label):
    - **Total Employees** = all `staff_user` rows.
    - **Active Employees** = status ACTIVE.
    - **Inactive Employees** = DISABLED.
    - **Invited (not yet joined)** = INVITED.
  - The list says "Loading staff…", "Could not load the staff list." or "No one in this group."
  - It also shows a breakdown of ACTIVE staff by role.
  - **Head Performance**: a board selector (Credit / Collection) and a bar chart of each Head's score (0–10) from their own leaderboard (UC-S4-09).
    - The tooltip shows "Score / 10", or "Not enough data" when the score is null.
    - Clicking a bar opens `STAFF_FILES` (credit) or `STAFF_CASES` (collection) for that head.
- **Edge cases / not allowed:** no code ever writes `staff_user.status = INVITED` (invites live in `staff_invite`), so "Invited" is effectively always 0 (§Z-4).

### UC-S4-09 Leaderboards and the 0–10 score
- **Actor / working role / permission:** which boards a caller may open depends on the **real** role:
  - ADMIN: all five boards.
  - CREDIT_HEAD: the Credit Head and Credit Executive boards.
  - COLLECTION_HEAD: the Collection Head and Collection Executive boards.
  - Executive and telecaller roles: their own board only.
  - Anything else: `FORBIDDEN_ROLE` "You cannot open the {board} leaderboard".
- **Entry point:** role views (UC-S4-15 to UC-S4-17), Team & Performance, and the report tabs.
- **Screen:**
  - A gradient banner (royal = parent board, teal = child board).
  - A podium for the top three, then rows with rank, name (with "(you)" on the caller's own row), stars and score.
  - A search box "Search by name or rank".
  - Hover shows each factor's value, weight and stars.
  - **Factor detail is visible only to ADMIN and Heads, or on your own row**. Others see "Factor detail is shown for your own row only."
  - Clicking a row opens that staffer's files or cases (not on the telecaller board).
- **Business rules (score):**
  - **Formula.** Score = 10 × Σ(wᵢ·fᵢ) ÷ Σwᵢ, taken over factors that can be measured (a factor with a zero denominator drops out). Each fᵢ is clamped to 0..1. The result is rounded to 2 decimals half-up.
  - A score with no measurable factor is null and sorts last.
  - **Stars** = round(score ÷ 2). A factor's stars = round(f × 5).
  - **Order:** score descending (nulls last), then volume descending, then name.
  - **Roster:** the **active** holders of the board's role. Factors are computed over the selected period.

  | Board | Factor (weight) | Definition |
  |---|---|---|
  | Credit Head / Credit Executive | Processed (0.3) | (sanctions + lead rejections) ÷ files assigned. Assigned = distinct applications named in an `ASSIGN`/`REASSIGN` event in the period |
  | | Approval (0.2) | sanctions ÷ (sanctions + rejections). Counts distinct applications with a `SANCTION` / `REJECT_LEAD` event by this actor in the period |
  | | Collection efficiency (0.5) | verified payments ÷ Σ `total_repayable` on loans this staffer **sanctioned** whose due date ≤ period end. **Not period-bound on the start** |
  | | Volume shown | files assigned |
  | Collection Head / Executive | Recovery (0.5) | verified ₹ collected in the period on cases created in the period ÷ (that + live outstanding on those still-open cases) |
  | | Cases closed (0.3) | cases created in the period whose loan is CLOSED ÷ cases created in the period |
  | | Volume (0.2) | cases created ÷ the largest count on the board |
  | Telecaller | Conversion (0.5) | leads they created in the period whose PAN later got a loan disbursed on or after the lead day ÷ leads |
  | | Contact rate (0.3) | leads with `call_status = CONNECTED`, or whose mobile matches a customer with a call log ÷ leads |
  | | Call volume (0.2) | their `customer_call_log` rows in the period ÷ the largest on the board |

- **Edge cases / not allowed:** a staffer with no measurable factor shows "—", never 0.

### UC-S4-10 Calendar tab
- **Actor / working role / permission:** Admin overview (`calendar` is ADMIN view only).
- **Entry point:** Admin tab "Calendar".
- **Screen:**
  - A month grid with previous and next arrows and a mode selector.
  - Subtitle "Sundays are shaded for orientation only — loans can fall due on any day."
  - Cells are heat-shaded: on the amount in money modes, on the count in count modes.
  - Stat chips: **Days with data**, **Total cases** (in Birthday mode "Customers"; in Active mode "Open at month end"), and **Total amount** (in Due (open) mode "Still owed").

  | Mode | Per-day rule | Money |
  |---|---|---|
  | Due Amount (`DUE`) | loans with `due_date` that day, closed ones included | Σ `total_repayable` |
  | Due (open) (`DUE_OPEN`) | due that day and status ≠ CLOSED | Σ live outstanding today |
  | Disbursed Amount (`DISBURSED`) | loans with `disbursed_on` that day | Σ principal |
  | Customer Birthday (`BIRTHDAY`) | distinct customers who hold a disbursed loan (latest loan's profile), whose DOB month-day = that day | count only |
  | Active loans (`ACTIVE`) | loans disbursed on or before the day and not closed on it (closed_on null or after the day) | count only |

- **Business rules:**
  - The month defaults to the current month.
  - In Active mode the month total is **the figure on the last day** (today for the current month), not the sum of the days.
  - An invalid mode returns `INVALID_MODE` "mode must be DUE, DUE_OPEN, DISBURSED, BIRTHDAY or ACTIVE".
  - Clicking a day opens the mode's `CALENDAR_*` metric for that day.
- **Edge cases / not allowed:** the calendar ignores the period picker; it is driven by its own month.

### UC-S4-11 Map tab
- **Actor / working role / permission:** Admin overview.
- **Entry point:** Admin tab "Map".
- **Screen:**
  - **India map**: loans disbursed in the period, shaded from emerald (least) to navy (most) by principal per state, with a full-screen toggle (Esc exits).
    - Clicking a state filters the table; clicking it again clears the filter.
  - **Locations**: a level toggle **State / Pincode**, chips **Locations · Cases · Principal**, a search box "Search pincode, city, state" and CSV export.
    - Table columns: Location · State · Segment · Cases · Principal · Closed · Close rate %.
- **Business rules:**
  - **Cases** = loans with `disbursed_on` in the period.
  - **State and pincode** come from the ADDRESS verification, then AADHAAR, then PAN. A missing value is "Unknown".
  - **Due** = cases with `due_date ≤ period end`. **Closed** = due and CLOSED. **Close rate** = closed ÷ due.
  - Pincodes are capped at the top **300** by principal.
  - **Segment** is classified in this order:
    1. cases ≥ large (state 200, pincode 20): **Large users** if close rate is null or ≥ 70%, else **Large, weak close**;
    2. close rate null: **Growing** if cases > previous-period cases, else **Better**;
    3. close rate < 50%: **Needs attention**;
    4. close rate ≥ 85% and cases ≥ 3: **Best**;
    5. close rate ≥ 70%: **Better**;
    6. otherwise **Growing** if growing, else **Needs attention**.
  - A row opens `STATE` or `PINCODE` for that key.

### UC-S4-12 Company-wise tab
- **Actor / working role / permission:** Admin overview.
- **Entry point:** Admin tab "Company-wise".
- **Screen:**
  - **Users by Company** (top 10): chips **Users** (distinct customers with an employer on an application created in the period) and **Companies** (number of distinct employers).
    - Columns: **# · Company · Users · Share · Avg salary · Type**.
    - Share = company users ÷ total users.
    - Avg salary = mean `monthly_salary_paise`.
    - Type = the most common `employment_status`.
    - Company names are trimmed, lower-cased for grouping and title-cased for display.
  - **Performance by Company** (top 10 by principal): loans disbursed in the period, by the borrower's employer.
    - Columns: **# · Company · Disbursed · Principal · Owed** (what the loans had to pay) **· Collected** (all verified) **· Collection %**.
  - Clicking any row opens `COMPANY` (loans **disbursed** in the period for that employer).
- **Edge cases / not allowed:** the Users table counts **applications created** in the period, but its drill-down lists **loans disbursed** in the period. The ⓘ says "(latest profile)", but the SQL uses each application's own profile (§Z-3).

### UC-S4-13 Credit Executive Report and Sales Ops Report tabs
- **Actor / working role / permission:** Admin overview.
- **Entry point:** Admin tabs "Credit Executive Report" (board `CREDIT_EXECUTIVE`) and "Sales Ops Report" (board `TELECALLER`).
- **Screen:**
  - Subtitle "{n} members".
  - Table columns: **Rank · Name · Files / Calls · {each factor, header tooltip "Weight w"} · Stars · Score**.
  - The row tooltip reads "{name}: score x / 10 · n files".
  - Empty state "No staff on this board".
  - Credit rows open `STAFF_FILES` for that executive. Telecaller rows have no drill-down.
- **Business rules:** as in UC-S4-09.

### UC-S4-14 Collection Allocation tab
- **Actor / working role / permission:** Admin overview. The same data is allowed for the Collection Head view.
- **Entry point:** Admin tab "Collection Allocation".
- **Screen:**
  - **Cards with deltas versus the previous period:**
    - **Total Assigned** = collection cases created in the period with an assigned officer on the roster.
    - **Total Closed** = distinct cases whose loan closed in the period.
    - **Total Collected (Count)** = distinct loans with a verified payment in the period on the officer's cases.
    - **Total Collected (₹)** = Σ those payments.
  - **Table**: one row per active Collection Head and Collection Executive, sorted by collected ₹ descending, then by name, with a Total row.
    - Columns: **S.No · Collection Ops · Total assigned · Total closed · Fresh (count) · Fresh (₹) · Re-loan (count) · Re-loan (₹) · Total (count) · Total (₹)**.
    - Clicking a count opens `STAFF_CASES` for that officer.
- **Business rules:**
  - Money collected always comes from `payment.status = VERIFIED`. It is never read from `collection_payment`, which would double-count through `ledger_payment_id`.

### UC-S4-15 Credit Head and Credit Executive views
- **Actor / working role / permission:**
  - Dashboard view Credit Head (real Credit Head or ADMIN).
  - Dashboard view Credit Executive (real Credit Executive, Credit Head or ADMIN).
- **Entry point:** the view toggle, or the default for these roles.
- **Screen:**
  - **Leaderboards**:
    - The Head view shows the "Credit Head leaderboard" and the "Credit Executive leaderboard" side by side.
    - The Executive view shows only the "Credit Executive leaderboard".
  - **Rating factors**: one tile per factor with "Overall x / 10". It is shown **only when the view resolves to exactly one staffer**, for example a real executive (roster = self).
  - **Lead Overview cards** (with a previous-period comparison in the tooltip):
    - **Assigned files** = `STAFF_FILES`: distinct (application, staff) pairs from `ASSIGN`/`REASSIGN` events in the period that name a roster member. ₹ = requested amount.
    - **Disbursed** = `DISBURSED`.
    - **Pending** = `PENDING`.
    - **Rejected** = `REJECTED`.
    - Each card is credit-scoped to applications assigned to or sanctioned by the roster.
  - **Charts** (daily):
    - **Assigned vs Follow-ups.** Follow-ups = the roster's call-log rows + their `MARK_PENDING` events that day.
    - **Assigned vs Disbursed** (count and amount).
    - **Sanction Repayment Closure**: loans sanctioned by the roster per sanction day, split Unclosed / Closed.
    - Clicking a point opens `STAFF_FILES` for that day, keyed to the single staffer when there is one.
- **Edge cases / not allowed:** a Head's view aggregates the team. Staff-keyed clicks list everything in scope.

### UC-S4-16 Collection Head and Collection Executive views
- **Actor / working role / permission:** dashboard views Collection Head and Collection Executive.
- **Screen:**
  - **Leaderboards**: the Collection Head board (Head view) and the Collection Executive board.
  - **Rating factors**: as in UC-S4-15.
  - **Collection Overview cards**:
    - **Assigned cases** (`STAFF_CASES`).
    - **Closed** (`CLOSED`, scoped to the roster's cases).
    - **Loans collected on** = distinct loans with a verified payment in the period on the roster's cases (not clickable).
    - **Collected** = Σ those payments (not clickable).
  - **Monthly top three**: Collection %, Pre-Closure %, Deficit (UC-S4-05), scoped to the roster's loans.
  - **Pre-Closure (next 7 days)** (UC-S4-07).
  - **Promise to Pay**:
    - The **PTP Collected** bar chart: verified payments per day on the roster's cases, paid on or after a `PROMISE_TO_PAY` interaction logged by a roster member.
    - Three tiles:
      - **Total after assignment** = verified payments on the roster's cases paid on or after the case's IST creation day, within the period.
      - **Same-day collection** = the part of that paid on the case creation day.
      - **Latest payment**: the date.
    - The tile hints say "promise" for all three, although the SQL for these tiles keys on case creation (§Z-7).
- **Edge cases / not allowed:** a real executive always sees only their own cases.

### UC-S4-17 Telecaller view
- **Actor / working role / permission:** dashboard view Telecaller (real Telecaller or ADMIN).
- **Screen:**
  - **Lead Overview cards** (not clickable):
    - **Leads** = leads they created in the period.
    - **Calls** = their `customer_call_log` rows.
    - **Callbacks** = call logs with outcome `CALLBACK`.
    - **Conversions** = leads whose PAN later got a disbursed loan.
  - **Leads vs Calls** daily line chart.
  - **Sales Ops leaderboard** and **Rating factors**.

### UC-S4-18 Disbursement Head view
- **Actor / working role / permission:** dashboard view Disbursement Head (real Disbursement Head or ADMIN). No roster; the whole book.
- **Screen:**
  - **Cards**:
    - **Pending disbursal** (`PENDING_DISBURSAL`: created in the period, now `DISBURSEMENT_PENDING`).
    - **Disbursed** (`DISBURSED`).
    - **Failed**: applications created in the period now in `DISBURSEMENT_FAILED` (not clickable).
  - **Interest & PF (daily)**: clicking a day opens DISBURSED.
  - **PF-wise Table** (the PF % table).
- **Edge cases / not allowed:** `DISBURSEMENT_FAILED` has no inbound transition (CLAUDE.md), so "Failed" reads 0 for any new data (§Z-6).

### UC-S4-19 Accountant view
- **Actor / working role / permission:** dashboard view Accountant (real Accountant or ADMIN).
- **Screen:**
  - **Repayments cards** (not clickable):
    - **Verified** and **Rejected**: payments with that status and `paid_on` in the period (count and ₹).
      - For a real Accountant, only payments **they decided** (`decided_by = self`).
      - For ADMIN working this view, the whole desk.
    - **Pending verification**: every `PENDING_VERIFICATION` payment right now. It is live, not windowed, and desk-wide.
  - **Revenue Performance** and **Interest & PF (daily)** (no click).

---

## §B Staff performance and decision history

### UC-S4-20 Staff performance register
- **Actor / working role / permission:** any non-DSA staffer. The nav item "Staff performance" carries no permission, because the server scopes the data.
- **Entry point:** `/staff/performance`.
- **Preconditions:** none. The period picker uses the shared presets.
- **Screen:**
  - Header "Staff performance", with the subtitle "What each employee got through, measured on their own role".
  - An **entity switcher** "Which staff to measure", with one line under it describing what the view measures.

    | Entity | Blurb | Headline tiles |
    |---|---|---|
    | Credit Executive | "Files decided: sanctions, rejections and parked files, how fast, and how much was sanctioned." | Sanctioned, Rejected, Parked, Value sanctioned, Approval rate, Files on desk now |
    | Credit Head | "Routing the credit desk: files assigned and reassigned, leads rejected, and files decided personally." | Assigned, Reassigned, Rejected, Sanctioned, Files on desk now |
    | Collection Executive | "Working overdue cases: calls and who was reached, promises to pay, payments recorded and money recovered." | Calls / interactions, Contact rate, Promises to pay, Recovered, Payments recorded, Open cases now |
    | Collection Head | "Checking the collections desk: settlements approved or rejected, plus the cases and calls they worked themselves." | Settlements approved, Value settled, Settlements rejected, Recovered, Open cases now |
    | Disbursement Head | "Releasing sanctioned loans: transfers made, value released, rejections and retries." | Disbursed, Value disbursed, Rejected, Retries |
    | Accountant | "Confirming money in: borrower repayments and collections payments verified or rejected." | Repayments verified, Value verified, Repayments rejected, Collection payments validated, Collections value validated |
    | Telecaller | "Calling the lead list: calls logged and the days they were on the phones." | Lead calls, Active days |
    | Admin | "Admins working other roles: whatever they decided, released, settled or called." | Sanctioned, Disbursed, Settlements approved, File actions |
    | All staff | "Everyone in your scope on one sheet — open a person for the figures that matter for their role." | File actions, Calls, Value moved, Recovered |

  - **Register**: S.No. · Staff · Role · {the entity's metric columns} · First / last action · Details.
    - It has column filters, sort (written to the URL) and pagination, plus CSV/PDF export titled "Staff performance — {entity}".
    - Empty states: "No staff in your scope" / "No staff match the current filters".
  - **Live tiles** carry the hint "Live — not affected by the period".
- **Metric definitions** (each column has an ⓘ):
  - **Active days**: distinct IST days with any action. That includes application events, collections activity and payment decisions.
  - **File actions**: every application-event action they took in the period, routing included. `AUTO_REJECT_*` events are excluded everywhere.
  - **Avg turnaround**: mean minutes from the file's latest `ASSIGN`/`REASSIGN` *inside the window* to their accept or reject.
    - It is blank (with an ⓘ: "No decision in this period had a matching assignment to measure from, so there is nothing to average — this is not a zero wait.") when there are no samples.
  - **Sanctioned** = count of `SANCTION`.
  - **Rejected (credit)** = `REJECT_LEAD` + `KYC_REJECT`.
  - **Parked** = `MARK_PENDING`.
  - **Approval rate** = sanctioned ÷ (sanctioned + rejected). Null when there is nothing to divide by.
  - **Value sanctioned** = Σ amounts parsed from `SANCTION` notes.
  - **Files on desk now** = applications in `CREDIT_EXEC_PENDING` assigned to them right now (live).
  - **Assigned** = `ASSIGN`. **Reassigned** = `REASSIGN`.
  - **Disbursed** = `VALIDATE_SUCCESS` + `ADMIN_FORCE_DISBURSE`, with value released = their amounts.
  - **Rejected (disbursement)** = `DISB_REJECT`. **Retries** = `RETRY`. **Cancelled** = `CANCEL`.
  - **Repayments verified / Value verified / Repayments rejected**: payments where `decided_by` = them and `decided_at` is in the window. Null (not 0) when they decided none.
  - **Collections metrics:**
    - Calls / interactions on cases, and contact rate (every outcome except "No answer").
    - Promises to pay.
    - **Recovered** = payments they recorded that the Accountant validated.
    - Payments recorded, awaiting validation and rejected.
    - Settlements proposed, approved (and value) and rejected as checker.
    - Collection payments validated (and value) and rejected.
    - Open cases now (live).
  - **Calls** = telecalling call logs + collections call interactions they logged. **Lead calls** = the telecalling part.
  - **Value moved** = sanctioned + released value.
  - Calls are only attributable from **21 Aug 2026** (V59). On a window that starts earlier, call metrics carry the partial-window note.
- **Business rules (roster, server-side):**
  - The roster depends on `ActorContext.effectiveRole()`, which is the **working** role:
    - ADMIN gets everyone (`listEveryone`, disabled staff included).
    - CREDIT_HEAD gets active Credit Heads and Executives.
    - COLLECTION_HEAD gets active Collection Heads and Executives.
    - Anyone else gets only themselves.
  - Naming a `staffId` narrows the roster to one person, after the same inspect check (UC-S4-22).
  - Accept actions: `KYC_APPROVE`, `SANCTION`, `VALIDATE_SUCCESS`, `ADMIN_FORCE_DISBURSE`.
  - Reject actions: `KYC_REJECT`, `REJECT_LEAD`, `DISB_REJECT`, `CANCEL`.
- **Alternate & error flows:** a server refusal shows NoAccessNotice with the server's message.
- **Edge cases / not allowed:** this is authorization by working role (§Z-8). A real ADMIN working as Credit Executive sees only their own row.

### UC-S4-21 One staffer's performance pop-up
- **Actor / working role / permission:** as in UC-S4-20.
- **Entry point:** clicking a register row ("View {name}'s performance").
- **Screen:**
  - A dialog with:
    - "{entity}: {blurb}".
    - An "As {role}" block of that role's metrics, each with its sentence.
    - **Also did**: non-zero metrics outside their own role, for example a Head working a file personally.
    - Collections **outcome bars**: Connected, Promise to pay, Paid, No answer, Disputed, Other.
    - **Activity per day**.
    - **File actions by type**.
    - A link to `/staff/my-decisions?staffId={id}&from=&to=`.
- **Business rules:** it is built only from the row already loaded, with no extra request, so it always agrees with the table.

### UC-S4-22 Decision history (`/staff/my-decisions`)
- **Actor / working role / permission:** any non-DSA staffer (no nav permission). The server rejects DSA with "DSAs cannot view decision history".
- **Entry point:** nav "My decisions", or deep-linked from the performance pop-up with `?staffId=&from=&to=`.
- **Screen:**
  - Title "Decision history", with the subtitle "Every lifecycle decision you've made, newest first." or "{name} — every lifecycle decision, newest first."
  - A switcher "Whose decisions to show":
    - "My decisions", plus the staff the server allows (`/inspectable`).
    - ADMIN gets the active Credit Heads, Credit Executives, Collection Heads and Collection Executives.
    - A Head gets their own team.
  - The period picker, and a search box "Application, customer ID, name or PAN" (client-side over the loaded rows).
  - **Stat cards** (for the period, not narrowed by search or filters):
    - **Approved** (hint "For the period — not narrowed by the search or column filters").
    - **Rejected**.
    - **Total actions** ("Every logged action in this period. The list below shows decisions only, so it can be shorter than this.").
    - **In queue now** ("Files sitting with you/them right now. A live snapshot…").
    - **Calls**.
  - **Table columns**: S.No. · When · Application · Customer ID · Customer · Signals (trust stars) · PAN · Decision · Outcome · Amount · Repayment date · Assignee · Txn ref · Remark.
    - The full remark and the raw audit notes are a hover tooltip.
    - Clicking an application opens the detail dialog in place.
- **Business rules:**
  - Decision actions shown: `KYC_APPROVE`, `KYC_REJECT`, `ASSIGN`, `REASSIGN`, `SANCTION`, `REJECT_LEAD`, `MARK_PENDING`, the legacy `EXEC_*` and `HEAD_*` actions, `DISB_ACCEPT`, `DISB_REJECT`, `VALIDATE_SUCCESS`, `VALIDATE_FAIL`, `RETRY`, `CANCEL`.
  - Labels include "Assigned to an executive", "Accepted lead (sanctioned)", "Rejected lead", "Marked lead pending", "Cleared KYC", "Confirmed transfer" and "Reassigned to another executive".
  - The notes are parsed into amount, salary credit day, repayment date, assignee, txn ref and remark.
  - Scoping: a staffer always sees their own history. Viewing someone else requires the effective role to be ADMIN, or a Head whose team contains that staffer's role; otherwise `FORBIDDEN` "You can only view your own team's decisions".
  - Without a period, the history is all time.
- **Edge cases / not allowed:** `ADMIN_FORCE_DISBURSE` counts as an approval in the stats but is not in the decision list.

---

## §C Reports

### UC-S4-23 Reports centre (43 reports)
- **Actor / working role / permission:**
  - The backend requires the **real role ADMIN** (`FORBIDDEN_ROLE` "Reports are available to ADMIN only").
  - The UI gates on `staff:manage` (working role Admin).
- **Entry point:** nav Finance & reports → **Reports** (`/staff/admin/reports`, with `?seg={group}` for one group).
- **Screen:**
  - Title "Reports" or "Reports · {group}", with the subtitle "Pick a period, then download any report as a CSV for spreadsheets or a formatted PDF to share. Every report says in one line what it contains."
  - Group chips: **Business overview, Customers, Disbursement, Repayments & collections, Risk & portfolio quality, Applications & credit, Staff & productivity, Finance & tax, Compliance & audit, Growth & partners**.
  - Period pills (§0 plus Last 7 and Last 30 days), and a range label (or "All time").
  - A search box "Find a report…" over title, description and group. Empty state: "No report matches that search."
  - An entry card to the customer data export (in the Customers group): "Choose customers and fields".
  - Report cards: the title, the one-line description, the basis line "Period by {date basis}" or "Live snapshot", and **Preview · CSV · PDF** buttons.
  - **Preview panel**:
    - group, title, headline-figure tiles, the table (money headers shown with ₹) and notes;
    - "Showing the first 200 of {n} rows — the CSV and PDF include all of them.";
    - empty state "No records for this period.".
- **Main flow:**
  1. Admin picks a period and a report.
  2. Admin clicks Preview, CSV or PDF.
  3. The system generates the report with `from` and `to`.
  4. The CSV and PDF are written from the **same formatted strings**:
     - a preamble with what, when, who ("Downloaded by …") and the headline figures;
     - the full table and its totals row;
     - the notes.
     - The PDF has the DhanBoost header band and the footer "DhanBoost · Confidential · Page i of n".
     - The file name is `dhanboost-report-{key}-{from}-to-{to}`.
- **Business rules:**
  - Report rows are capped at **50,000**. Beyond that the report is truncated with the note "Only the first 50000 rows are included — narrow the period for the rest."
  - A period may span at most **10 years** (`INVALID_RANGE`). A start after its end is also refused.
  - A report with **no date basis** is a live snapshot that **ignores the period**.
  - **Headline figures:** when a report defines none, they default to "Rows" plus up to four "{MONEY column} (total)" figures.
  - The totals row sums MONEY columns only. IDs and ratios are never summed.
  - **PAN is masked** as `AB` + `XXX` + 4 digits + `X`. Aadhaar and bank account numbers never appear.
  - Owed, interest, penalty, outstanding and excess are priced by `RepaymentService` **as of today**.
  - An unknown key returns `REPORT_NOT_FOUND`.
- **The catalogue** (key · purpose · main columns · headline figures · period basis):

| # | Key (group) | Purpose | Columns (abridged) | Headline figures | Basis |
|---|---|---|---|---|---|
| 1 | `platform-summary` (Overview) | Business summary for the period + live book | Area, Metric, Count, Amount, Basis ("In period"/"As of today") | Loans disbursed, Principal disbursed, Repayments verified, Outstanding today | Event date |
| 2 | `monthly-performance` | Month-by-month performance | Month, Loans disbursed, Principal, PF, GST, Net disbursed, Repayments verified, Collected, Loans closed, Expenses | Rows + money totals | Month |
| 3 | `platform-guide` | Shareable plain-language explainer with live counts | Section, Item, How it works, Live count | Customers, Loans disbursed (all time), Active staff | snapshot |
| 4 | `customers` (Customers) | Customer master list | Customer ID, Name, Mobile, PAN (masked), Email, Employer, Salary, Signed up, Latest status, Applications, Loans taken/closed, Total borrowed/repaid, Relationship owner | Customers, With at least one loan | Sign-up date |
| 5 | `customers-overpaid` | Loans paid beyond what was owed (refund due) | Loan-position columns (see 26) | Loans, Outstanding, Excess to refund | Disbursal date |
| 6 | `customers-not-overpaid` | Every other loan with balance | Loan-position columns | Loans, Outstanding | Disbursal date |
| 7 | `customers-outstanding` | Open loans with a balance today | Loan-position + DPD bucket, Collection officer | Loans, Outstanding | snapshot |
| 8 | `customers-repeat` | Customers with ≥ 2 loans | Customer ID, Name, Mobile, Loans taken/closed, First/Latest loan, Borrowed, Repaid | Repeat customers | snapshot |
| 9 | `customer-transactions` | Every payout and repayment, any status | Date, Type, Direction, Customer, Loan ID, Amount, Method, Status, Reference | Transactions, Disbursed (out), Repaid verified (in) | Transaction date |
| 10 | `employer-concentration` | Lending by employer | Employer, Customers, Loans, Principal lent, Outstanding today, Overdue loans, Overdue rate % | Employers, Principal lent | Disbursal date |
| 11 | `disbursals` (Disbursement) | Disbursement register | Loan/App/Customer ID, Disbursed on, Due date, Principal, PF, GST, Net, Total repayable, Status, Transfer reference, First / repeat | Loans disbursed, Principal, Net transferred | Disbursal date |
| 12 | `disbursement-pending` | Sanctioned, not yet paid out | App ID, Customer, Status, **Waiting on** ("Customer — offer step: {step}" / "Disbursement team to transfer"), Sanctioned, Amount chosen, Sanctioned at, Days waiting, Sanctioned by | Files waiting, With the disbursement team, Amount sanctioned | snapshot (SANCTIONED, DISBURSEMENT_PENDING, DISBURSEMENT_FAILED) |
| 13 | `turnaround-time` | Application → sanction → payout days | Applied, Sanctioned, Disbursed, Days to sanction, Days sanction to payout, Total days | Loans, Avg days to sanction, Avg days sanction to payout, Avg total days | Disbursal date |
| 14 | `dues-upcoming` (Collections) | Open loans due in the period | Due date, Loan, Customer, Status, Principal, Contractual repayment, Paid so far, Owed today, DPD, Officer | Loans due, To collect | Due date |
| 15 | `collection-efficiency` | Due vs collected per due date | Due date, Loans due, Amount due, Collected on time, Collected late, Not yet collected, Loans closed on time, On-time %, Overall % | Amount due, Collected on time, On-time %, Overall % | Due date |
| 16 | `repayments` | Repayment register | Payment ID, Loan, Customer, Paid on, Amount, Method, Status, Part payment, Reference, Decided by/at, Rejection reason, Recorded at | Repayments, Verified, Awaiting verification | Payment date |
| 17 | `repayments-pending` | Repayments awaiting verification, oldest first | Payment ID, Loan, Customer, Paid on, Amount, Method, Reference, Proof attached, Reported at, Days waiting | Awaiting verification, Amount | snapshot |
| 18 | `closed-loans` | Closed loans and timing | Disbursed/Due/Closed on, Days late, Principal, Paid verified, Closed by (Settlement / Full repayment), Timing (Paid early / On time / Late) | Loans closed, Repaid late | Closure date |
| 19 | `collection-cases` | Collection cases | Case ID, Loan, Customer, Bucket at opening, Officer, Opened, Due date, Loan status, Principal, Outstanding today, Interactions logged | Cases, Outstanding today | Opened date |
| 20 | `settlements` | Settlement proposals and approvals | Settlement ID, Loan, Customer, Principal, Settlement amount, Status, Proposed by/at, Approved/rejected by, Decided at | Rows + money totals | Proposed date |
| 21 | `nach-refunds` | NACH debits that landed after the loan was paid | Debit ID, Loan, Customer, Amount, Debit status, UTR, Settlement date, Presented at | Refunds owed | Debit date |
| 22 | `overdue` (Risk) | Loans past due today | Loan-position + DPD bucket (Current/1-30/31-60/61-90/90+), Officer | Loans, Outstanding | snapshot |
| 23 | `asset-classification` | RBI SMA/NPA split of the open book | Classification (Standard, SMA-0 1-30, SMA-1 31-60, SMA-2 61-90, NPA over 90), Days past due, Loans, Principal, Outstanding, Share % | Outstanding book, PAR 1+ %, PAR 30+ %, NPA % | snapshot |
| 24 | `vintage` | Repayment by disbursal month | Month, Loans, Principal, Due date passed, Repaid on time/late, Unpaid past due, On-time %, First-payment default %, Ever 30+ %, Ever 90+ %, Outstanding | Cohorts, Loans | Disbursal date |
| 25 | `credit-score-bands` | Repayment by bureau score band (750+, 700-749, 650-699, 600-649, Below 600, No score) | Band, Loans, Principal, Due date passed, On-time %, Late %, Overdue now %, Outstanding | Loans, Scored | Disbursal date |
| 26 | `loan-book` | Every disbursed loan priced today | Loan ID, Customer, Status, Disbursed/Due/Closed on, Principal, Interest, Penalty, Total owed, Paid verified, Outstanding, Excess paid, **Position** (Overpaid / Settled / Paid in full / Partly paid / Unpaid), DPD | Loans, Outstanding, Excess to refund | Disbursal date |
| 27 | `written-off` | Loans whose application is DEFAULTED / WRITTEN_OFF | Loan-position columns | Loans, Outstanding | snapshot |
| 28 | `applications` (Credit) | Application register | App ID, Customer, PAN (masked), Employer, Salary, Created, Status, Journey step, Eligible limit, Requested, Sanctioned, Repayment date, Credit executive, Sanctioned by/at, Parked for, Loan ID, Fresh / reborrow | Applications, Sanctioned | Created date |
| 29 | `application-funnel` | Applications started in the period by current stage | Current status, Applications, Share %, Requested, Sanctioned | Applications | Created date |
| 30 | `credit-decisions` | Per credit officer | Staff, Role, Files decided, Sanctioned, Rejected (`REJECT_LEAD`), Parked (`MARK_PENDING`), Approval % = sanctioned ÷ (sanctioned + rejected), Amount sanctioned | Sanctioned, Rejected, Approval %, Amount sanctioned | Decision date |
| 31 | `rejections` | Every rejection and block | Rejection/App/Customer ID, Reason, Detail, Manual / automatic, Rejected by/at, Blocked until | Rejections, Automatic | Rejection date |
| 32 | `staff` (Staff) | Staff directory | Staff ID, Name, Email, Mobile, Role, Status, Department, Designation, Created, Last sign-in | Staff accounts, Active | snapshot |
| 33 | `staff-activity` | Actions per staffer | Staff, Role, Action, Times, Distinct files/loans, Last done | Staff with activity, Actions | Action date |
| 34 | `collection-officer-performance` | Per collection officer | Staff, Role, Open cases, Balance on open cases, Payments collected, Collected in period, Loans closed in period | Officers, Collected in period, Balance on open cases | Payment date |
| 35 | `fee-income` (Finance) | PF + GST register for tax filing | Loan, Customer, Disbursed on, Principal, PF, GST on fee, Total deducted, Net disbursed | Fee earned, GST collected | Disbursal date |
| 36 | `income-realised` | Earnings on loans closed in the period | Loan, Customer, Closed on, Principal, Total received, Principal recovered, Interest & penalty earned, Principal lost, Upfront fee earned | Loans closed, Interest & penalty earned, Upfront fee earned, Principal lost | Closure date |
| 37 | `expenses` | Company expense register | Expense ID, Date, Description, Paid to, Amount, Notes, Receipt attached, Recorded by | Total expenses | Expense date |
| 38 | `audit-trail` (Compliance) | Every status change | When, App ID, Customer, From/To status, Action, Done by, Role, Acting as, Notes | Events | Event date |
| 39 | `blocklist` | Blocked identifiers | Entry ID, Type, Value, Reason, Active, Added by, Added | Rows | Added date |
| 40 | `leads` (Growth) | Lead register | Lead ID, Name, Mobile, Email, City, Employer, Salary, Loan wanted, Source, Call status, Outcome, Quality (1-5), Created by, DSA, Created | Leads | Created date |
| 41 | `lead-conversion` | Conversion per lead source | Source, Leads, Called, Applied, Got a loan, Applied %, Lead to loan % (match on PAN **or** mobile) | Leads, Got a loan, Lead to loan % | Created date |
| 42 | `dsa-commissions` | DSA commission statement | Commission ID, DSA, Lead, Customer, Loan, Net disbursed, Rate %, Commission, Status, Accrued, Payable from, Paid, Payment reference | Rows + money totals | Accrued date |
| 43 | `referral-payouts` | Refer-a-friend rewards | Payout ID, Customer, Referrer / referee, Amount, Status, Qualifying loan, Created, Paid, Paid by, Reference | Rows + money totals | Created date |

- **Formulas worth pinning:**
  - **Loan position** (5–7, 22, 26, 27):
    - **excess** = 0 if settled, else max(0, paid − principal − interest − penalty).
    - **open** = closed_on null and status not CLOSED/REPAID.
    - An ACTIVE loan past its due date is shown as OVERDUE.
    - **DPD** = today − due_date, counted only when open with a balance.
  - **Collection efficiency:**
    - "On time" = verified payments with `paid_on ≤ due_date + grace_days`.
    - Due = `total_repayable`. A loan closed on time instead uses its on-time paid amount, so a prepaid loan reads 100%.
  - **Platform summary rows:**
    - Applications started; New customers signed up (first application date); Applications sanctioned (`sanctioned_at`); Applications rejected (rejection rows); Loans disbursed (principal); Net transferred; PF earned; GST collected; "Of which repeat loans" (any earlier disbursed loan); Repayments verified/rejected (`paid_on`); Loans closed; Settlements approved; Company expenses; DSA commission accrued; Referral rewards paid; Leads created.
    - Live rows: Open loans outstanding, Overdue loans outstanding, Repayments awaiting verification, Loans overpaid (excess), Active staff accounts.
    - Notes: "\"In period\" rows count events whose date falls in the selected period; \"As of today\" rows are a live snapshot regardless of the period." and "GST is collected on behalf of the government and is not income."
- **Alternate & error flows:** a failed download shows "Download failed" (or the server message) on the card.
- **Postconditions:** read-only. No server-side audit row is written per report download.

### UC-S4-24 Customer data export
- **Actor / working role / permission:**
  - The backend requires the **real role ADMIN**.
  - The UI requires `staff:manage`.
- **Entry point:** Reports → "Choose customers and fields" card → `/staff/admin/reports/customer-export`.
- **Screen:** a three-step wizard, **1. Customers · 2. Fields · 3. Download**.
  - **Step 1 "Who should be in this export?"**
    - **Period** select (the report presets plus "Custom dates"). The note reads "A customer counts by the date of their first application, not their latest."
    - **Group**:
      - All customers, or one Customers-tab segment: `incomplete`, `pending`, `review` ("In review"), `approved`, `disbursementPending` ("Disbursement pending"), `active` ("Active loans"), `overdue` ("Overdue loans"), `hold` ("On hold"), `rejected` ("Rejected loans"), `closed`, `unallocated`, `reloan`, `blocked`.
      - The selection comes from the same `CustomerBookQuery` as the Customers tab.
    - **Days past due** chips: Not yet due, 1–7 days, 8–30 days, 31–60 days, 61–90 days, 90+ days. The note reads "Leave all off for any. A customer with no open loan has no days past due, so choosing a band leaves them out."
    - **One row per**: "Customer, with earlier loans summarised" or "Application, so a returning customer appears more than once".
    - A live match count. Empty state: "Nobody matches these choices."
  - **Step 2 "Which fields do you want?"**
    - About 160 fields in **15 groups**: Personal, ID numbers, Employment and salary, Applications, Bank and payout, Loans, Repayments, Verification and KYC, Credit bureau, References, Mandates and debits, Collections, Calls, notes and owners, Documents and consent, Referral and lead source.
    - Search "Search any field, for example salary or IFSC", collapsible groups, and **Select all** / **Clear**.
    - **Default fields:** Customer ID, Full name, Mobile, Application status, Loan status, Days past due, Outstanding today (live).
    - **Include full ID numbers**: an amber switch, off by default, with the text "Aadhaar, PAN and bank account numbers in full. Off by default and left out of Select all. Each export records who pulled it."
      - It unlocks the six sensitive fields: Aadhaar number, PAN, UAN, Salary account number, Disbursal account number, Penny-drop account number.
  - **Step 3 Preview + download**:
    - **Download CSV** holds up to 50,000 rows.
    - **Download PDF** holds up to 500 customers: as a table when 12 fields or fewer, otherwise one card per customer with empty fields left out.
    - Progress bar and **Cancel**.
    - Messages: "Downloaded {n} rows as CSV." / "Download cancelled. Nothing was saved." / "This export has {n} rows, more than a PDF holds (500). Download the CSV, or go back and narrow the group."
- **Main flow:**
  1. Admin sets the scope. The system runs **match**, which returns the ids (capped at 50,000, flagged `truncated`) and the customer count.
  2. Admin picks fields.
  3. Admin clicks Download. The browser requests rows in pages of **≤ 200 ids**, builds the file locally, and nothing is staged on the server.
- **Business rules:**
  - At most **2 exports run at once**. A request waits up to 2 s for a slot, otherwise it gets HTTP **503 `EXPORT_BUSY`** "Another export is running. Retrying shortly." The client retries up to 6 times with exponential back-off (1 s doubling, capped at 8 s).
  - Statements time out after 30 s.
  - Errors are HTTP **400**, not 422: `INVALID_REQUEST`, `INVALID_SEGMENT` "Unknown group", `INVALID_DPD` "Unknown days-past-due band", `INVALID_DATE` "The start date is after the end date", `TOO_MANY_IDS` "At most 200 ids per request", `NO_FIELDS` "Choose at least one field", `UNKNOWN_FIELD`, and `ID_NUMBERS_NOT_CONFIRMED` "ID number fields need the include-ID-numbers confirmation".
  - When more than 50,000 match, the screen says "More than 50,000 match. Only the first 50,000 are included; narrow the group to get the rest."
- **Postconditions (logging):**
  - One **application log line** per match (staff id, group, DPD, grain, count, ID-numbers flag).
  - One log line per rows request that includes ID fields (staff id, grain, row count, number of ID fields). Values are never logged.
  - There is **no database audit row** (§Z-9).

---

## §D Global search

### UC-S4-25 Search the console (Cmd/Ctrl+K)
- **Actor / working role / permission:** any non-DSA staffer.
  - The component is not mounted for DSA, and the endpoint rejects DSA ("DSAs cannot search the console").
  - It is hidden when `global-search` is off. A missing flag row counts as **on**; a disabled flag returns `FEATURE_DISABLED`.
- **Entry point:** the header "Search…" pill, or **Cmd/Ctrl+K**.
- **Screen:**
  - A palette with the placeholder "Search customers, loans, leads or pages…".
  - A hint chip, set before the response arrives: "Searching by mobile / PAN / ID / name".
  - A **Pages** section, resolved locally from `staff-nav.ts` through the same `navVisible` gate as the sidebar, so it never offers a page the role cannot open.
  - **Records** sections, one per group, each with up to 5 hits and a "View all" link when there are more.
  - **Recents**: the last 8 queries, stored per staffer in localStorage.
  - Keyboard: ↑/↓ to move, Enter to open, Esc to close. The matched text is highlighted.
  - "Keep typing to search records…" shows below the minimum length. "Search is unavailable right now." shows on error.
- **Main flow:**
  1. Staffer types. After a 250 ms debounce the system parses the query:
     - A 10-digit mobile starting 6–9 (an optional +91 is stripped, and spaces or dashes are tolerated) → **mobile**.
     - `AAAAA9999A` → **PAN**, upper-cased.
     - Any other digit string → **ID** (a leading `#` is stripped).
     - Anything else → **name text**, with a minimum of 2 characters.
  2. The server runs each group **sequentially** through the same service the list page uses, so each group keeps its own scoping. It returns up to 5 per group (the server cap is 10), plus a `more` flag. Results are cached for 30 s.
  3. Staffer picks a hit, which deep-links to the record.
- **Groups per real role:**

  | Role | Groups |
  |---|---|
  | ADMIN | Customers, Applications, Loans, Collections, Leads, Staff, Blocklist |
  | COLLECTION_HEAD | Customers, Applications, Collections, Loans |
  | COLLECTION_EXECUTIVE | Customers, Applications, Collections |
  | TELECALLER | Customers, Leads |
  | Credit roles, Disbursement Head, Accountant | Customers, Applications |

- **What each hit shows and opens:**

  | Group | Title / detail | Deep link |
  |---|---|---|
  | Customers | name (or "Customer #id") · mobile · PAN · outstanding · status | `/staff/customers/{id}` |
  | Applications | "#id · {name or 'Unnamed applicant'}" · mobile · "Loan #n" · sanctioned or requested amount · status | `/staff/applications?q={id}` |
  | Loans | "#loanId · borrower" · mobile · PAN · "due {date}" · outstanding | `/staff/loans?q={id}&open={id}` |
  | Collections | case matched in memory: loan id prefix or borrower name contains | `/staff/collections/{loanId}` |
  | Leads | lead list search | `/staff/leads?q={mobile or name}` |
  | Staff | name or email contains | `/staff/admin/staff?q=` |
  | Blocklist | active entry value contains | `/staff/admin/blocklist?q=` |

- **Business rules:**
  - A group the role cannot see is **omitted entirely**, not returned empty, so the palette cannot be used as an oracle for whether a customer exists.
  - A `FORBIDDEN_ROLE` from one delegate drops only that group.
  - The group list follows the **real** role, so an ADMIN in any working role gets all seven groups. The Pages half follows the working role.
- **Postconditions:** the query is saved to recents (client only).

---

## §E Staff accounts and access

### UC-S4-26 List and create staff accounts
- **Actor / working role / permission:** real ADMIN (`StaffService.requireAdmin`); UI `staff:manage`.
- **Entry point:** nav Team & settings → **Staff** (`/staff/admin/staff`).
- **Screen:**
  - Title "Staff accounts", with the subtitle "Manage staff roles and access status."
  - Tabs by status: **Active / Invited / Disabled**, each with a count badge.
  - Search "Name or email". Column filters and sort on Name, Email and Role.
  - CSV/PDF export with the columns ID, Name, Email, Role, Status.
  - Table: S.No. · Name · Email · Role · Status · Actions.
  - Empty state: "No staff accounts match the filters."
  - A **Create staff account** form:
    - **Email** (type email, placeholder "person@navix.example").
    - **Name** ("Full name").
    - **Role** (select of every role label).
    - **Password** ("Set a password (min 8)").
    - The Create button is enabled only when email and name are non-empty and the password is 8–72 characters.
- **Main flow:**
  1. Admin fills the form and submits.
  2. The system rejects a duplicate email with `EMAIL_TAKEN` "A staff account with this email already exists". Otherwise it saves the account with a BCrypt hash, status **ACTIVE**, and publishes `StaffAccountEvent(CREATED)`.
  3. Toast "Staff account created".
- **Business rules:** server validation is `@Email`, name not blank, role required, password 8–72 ("Password must be 8 to 72 characters").
- **Edge cases / not allowed:** this path does **not** apply `PasswordPolicy`, which requires a letter, a digit and a special character (§Z-10). The DSA role can be created here.

### UC-S4-27 Change a staffer's role or status, disable, enable
- **Actor / working role / permission:** real ADMIN.
- **Entry point:** a row's Actions in the staff register.
- **Screen:**
  - Per row: a Role select, a Status select (Active / Disabled only), and **Save** (enabled only when something changed).
  - **Disable** asks for a second click: "Disable" → "Confirm disable".
  - A disabled row offers **Enable**.
  - Toasts: "Staff account updated" / "Staff account disabled" / "Staff account enabled".
- **Main flow:**
  1. Admin changes the role or status and saves.
  2. The system validates and applies the change.
  3. If the **role changed**, it revokes the account's sessions (rotates its active session id, so older tokens stop matching) and publishes `ROLE_CHANGED`.
  4. If it became **DISABLED**, it publishes `DISABLED`.
  5. An after-commit listener evicts the staffer's `StaffSessionRegistry` entry, so the kick-out does not wait for the 30 s cache.
- **Business rules:**
  - Allowed status transitions: ACTIVE ↔ DISABLED, and the one-way INVITED → ACTIVE/DISABLED. Nothing may move back to INVITED. Anything else is `ILLEGAL_TRANSITION` "Cannot move a staff account from X to Y".
  - **SELF_MODIFY** "You cannot disable or change the role of your own account". The caller's id comes from the JWT.
  - **LAST_ADMIN** "At least one ACTIVE admin must remain", raised when disabling the last ACTIVE ADMIN.
  - Disabling is idempotent.
- **Postconditions:** the affected staffer's next request fails, and they must sign in again.
- **Edge cases / not allowed:**
  - A role change on an ADMIN is not checked by LAST_ADMIN. This is safe in practice, because the caller is themselves an active ADMIN who cannot modify their own account.

### UC-S4-28 Issue a staff invite
- **Actor / working role / permission:** real ADMIN.
- **Entry point:** nav **Invites** (`/staff/admin/invites`).
- **Screen:**
  - Title "Staff invites", with the subtitle "Issue a one-time invite token; the invitee activates on /staff/activate."
  - Form: **Email** (placeholder "new.hire@dhanboost.com"), **Role** (a select showing raw role codes), **Send invite**.
  - Table: S.No. · Email · Role · Token (with a copy button) · Expires. Filter and sort on Role and Expires; default sort Expires descending.
  - CSV/PDF export, which includes the token.
  - Empty state: "No invites yet."
- **Main flow:**
  1. Admin enters an email and a role and sends.
  2. The system creates a `staff_invite` with a random UUID token and an expiry **7 days** out, then publishes `StaffAccountEvent(INVITED)` carrying the token.
  3. The notification engine emails `STAFF_INVITED` with the activation link.
- **Business rules:** email (`@Email`) and role are required.
- **Edge cases / not allowed:**
  - Nothing checks for an existing account or a pending invite for the same email.
  - The list does not show whether an invite was accepted (the response carries no accepted date).
  - The secret token is shown on screen and in exports (§Z-11).

### UC-S4-29 Activate an account from an invite (`/staff/activate`)
- **Actor / working role / permission:** the invitee, with no session. Public page; the token is the credential.
- **Entry point:** the link in the invite email (`?token=`), or the token pasted by hand.
- **Screen:**
  - Title "Activate your account", with the subtitle "Set a password to finish setting up your DhanBoost staff access."
  - Fields:
    - **Invite token** ("paste your one-time token").
    - **Your name** ("Aanya Rao").
    - **Mobile (optional, for password recovery)**, normalised to digits.
    - **Password** (placeholder "at least 8 characters (up to 72) with a letter, a digit and a special character").
    - **Confirm password**.
  - The submit button is disabled until token, name, password and confirm are all present.
  - A link: "Already activated? Sign in".
- **Main flow:**
  1. Invitee submits.
  2. The system validates `PasswordPolicy`, then checks the token:
     - `INVALID_INVITE` "Invite token is invalid"
     - `INVITE_ALREADY_ACCEPTED` "Invite has already been accepted"
     - `INVITE_EXPIRED` "Invite token has expired"
  3. It finds or creates the `staff_user` for the invite email and sets: name, the **invited role**, **ACTIVE**, the password hash, and the mobile (digits) when given. It revokes old sessions and stamps the invite's `acceptedAt`.
  4. The staffer is signed in (staff cookie). "Account activated · Signing you in…" shows, then after 0.9 s the page goes to `/staff/dashboard`.
- **Alternate & error flows:**
  - Client errors: "Your invite token is missing — use the link from your invite email, or paste the token." / "Please enter your name." / "Passwords don't match."
  - Server error: `WEAK_PASSWORD` "Password must be at least 8 characters and at most 72 bytes, and include a letter, a digit and a special character."
- **Edge cases / not allowed:** an invite for an email that already belongs to a **DISABLED** or differently-roled account re-activates it with the invited role (§Z-11).

### UC-S4-30 Staff self-profile
- **Actor / working role / permission:** any staffer, own account only.
- **Entry point:** `/staff/profile`.
- **Screen:**
  - Title "My profile", with the subtitle "Your account details. Update your name, department and designation."
  - Read-only: Email, Role, Status ("Role and status are managed by an administrator.").
  - Editable: **Display name**, **Department** ("e.g. Credit Operations"), **Designation** ("e.g. Senior Analyst").
  - **Notifications**: an "Email notifications" switch ("Operational and queue emails. Account and security emails — including password resets — are always sent regardless of this setting.").
  - **Security**: "Reset your password — we'll email you a secure reset link." → `/staff/forgot-password`.
- **Main flow:**
  1. Staffer saves.
  2. The system trims the input. A blank name keeps the old name; a blank department or designation is cleared.
  3. Toasts: "Profile saved", and "Email notifications on / off" for the switch.
- **Edge cases / not allowed:** role and status cannot be changed here.

### UC-S4-31 Staff forgot and reset password
- **Actor / working role / permission:** anyone (public pages).
- **Entry point:** `/staff/forgot-password` (from the login or profile page); `/staff/reset-password?token=` (from the email).
- **Screen:**
  - **Forgot:** **Work email** ("you@navix.example"), Turnstile, and "Email me a reset link" ("Sending…").
    - Success copy: "If that email matches a staff account, we've emailed a reset link (valid 30 minutes)." and "Back to staff sign-in".
  - **Reset:** **New password** (with the policy hint), **Confirm new password**.
    - Success copy: "Your password has been updated. You can now sign in with it." and "Go to staff sign-in".
- **Main flow:**
  1. The system verifies the captcha.
  2. It rate-limits **3 requests per email per 15 minutes** ("Too many reset requests.").
  3. If the email belongs to an **ACTIVE** staff account, it emails a one-time link. The token is stored as a SHA-256 hash with a **30-minute** TTL.
  4. It always returns the same acknowledgement, so the response never reveals whether the account exists.
  5. On reset: the system checks `PasswordPolicy` and the token (`INVALID_TOKEN` "This reset link is invalid." / "This reset link has already been used." / `EXPIRED_TOKEN` "This reset link has expired. Please request a new one."). A borrower token on the staff page is invalid.
  6. It sets the hash, revokes the sessions, evicts the session cache and marks the token used.
- **Alternate & error flows:** client errors "Enter your staff email." / "This reset link is invalid or has expired." / "Password must be …" / "Passwords don't match."

---

## §F Admin registers and settings

### UC-S4-32 Fraud blocklist
> **Updated 2026-11-13 (V103): the blocklist and "Block customer" are one block.** A customer is
> blocked for new applications and reborrows when they hold a staff block or any of their
> identifiers (login + profile mobile, PAN, Aadhaar, salary / payout account) is an active entry —
> `NOT_ELIGIBLE` "Your account is not eligible for new loans. Please contact support." Files already in
> progress are not touched. Values are stored normalised (PAN uppercase, phone = last 10 digits,
> Aadhaar / account digits only; a value with nothing left → `BLOCKLIST_VALUE_REQUIRED`); (type, value)
> is unique. "Block customer" writes every identifier here linked to the customer; unblocking the
> customer clears every matching entry; removing a customer's last linked entry here lifts their staff
> block. The customer page and the Customers "Blocked" segment show a blocklist match as blocked.
- **Actor / working role / permission:** real ADMIN for list, add and remove; UI `staff:manage`.
- **Entry point:** nav **Blocklist** (`/staff/admin/blocklist`).
- **Screen:**
  - Title "Fraud blocklist"; the subtitle explains that a match blocks new applications and borrowing again, and that removing a customer's entries unblocks them.
  - Form: **Type** (PAN · Aadhaar ref · Phone · Device · Bank account), **Value** ("ABCDE1234F"), **Reason (optional)** ("Confirmed fraud").
  - Table: S.No. · Type · Value · Reason · Customer (link to the customer page when "Block customer" wrote the entry) · Action (Remove). Filters on Type and Reason.
  - Export columns: Type, Value, Reason, Customer, Active.
  - Empty state: "No active blocklist entries."
- **Main flow:**
  1. Admin adds an entry. If an entry with the same type and value exists, it is **reactivated** and its reason replaced, so no duplicate is made.
  2. Toast "Added to blocklist".
  3. **Remove** soft-deletes the entry (`active = false`). Toast "Removed from blocklist".
- **Business rules:**
  - Only active entries are listed.
  - The value is stored exactly as typed, with no case or space normalisation.
  - A staff "block customer" action elsewhere mirrors PHONE and PAN entries into this table without the ADMIN guard.
- **Edge cases / not allowed:** the type's comment and CLAUDE.md say a match "stops the application" at sign-up and approval, but `isBlocked` has **no caller**. The register is reference data and screens nothing (§Z-5).

### UC-S4-33 Company expenses (with receipt)
- **Actor / working role / permission:** real ADMIN for every operation, read included.
- **Entry point:** nav Finance & reports → **Company expenses**.
- **Screen:**
  - Title "Company expenses", with the subtitle "Track operational spend — payee, amount and notes. Admin only."
  - **Add expense** form:
    - **Description*** ("Office internet — June").
    - **Amount (₹)*** (number, minimum 1, step 0.01).
    - **Paid to*** ("ACT Fibernet").
    - **Notes (optional)**.
    - **Date*** (defaults to today).
    - **Receipt (optional)** (image or PDF).
  - Table with filters on Description, Paid to, Amount, Receipt (Attached / None) and Added by.
  - **Remove** per row, and **Refresh**.
  - Export columns: Date, Description, Paid to, Amount (₹), Notes, Receipt, Added by.
- **Main flow:**
  1. Admin attaches a receipt. It is uploaded first through an S3 presign (category `EXPENSE_RECEIPT`).
  2. Admin saves. The expense is stored with the receipt key. Toast "Expense added".
  3. In the list, each receipt key becomes a short-lived presigned download URL. The key itself is never returned.
- **Business rules:** amount must be > 0 (`INVALID_AMOUNT` "Expense amount must be positive"). Description and paid-to are required. The date defaults to today.
- **Postconditions:** **Remove** is a hard delete. Expenses feed the `expenses` and `monthly-performance` reports and the platform summary.

### UC-S4-34 Settings: pause new-customer onboarding
- **Actor / working role / permission:** real ADMIN; UI `staff:manage`.
- **Entry point:** nav **Settings** (`/staff/admin/settings`) → **Onboarding**. Title "Settings", subtitle "Switches and company details that apply across the console."
- **Screen:**
  - The switch **Accept new customers**, with the description "Paused — new applicants join the waitlist." or "On — new applicants can sign up.", plus "Last changed by {name} · {date time}" once it has ever been flipped. The Applications switch (UC-S4-35) shows the same line.
- **Main flow:**
  1. Turning onboarding **off** (pausing) asks for confirmation: "Pause new-customer onboarding?" / "New applicants will see the waitlist form until you turn this back on."
  2. The system upserts the `onboarding-paused` flag row.
  3. Toast "New-customer onboarding paused" or "…resumed".
- **Business rules:**
  - While the flag is on, a borrower who has **never held a loan** gets the one-form waitlist (UC-S4-38). Borrowers with any loan, live or repaid, are unaffected; reborrow still works.
  - A fresh database seeds the flag **on** (V76).
  - This is one of the three flags with a write path. Every other flag is changed only by SQL.

### UC-S4-35 Settings: close applications and reloans
- **Actor / working role / permission:** real ADMIN.
- **Entry point:** Settings → **Applications**.
- **Screen:**
  - The switch **Accept applications, including reloans**, with the description "Closed. No new applications or reloans are being accepted." or "On. Anyone eligible can start an application or a reloan."
- **Main flow:**
  1. Closing asks for confirmation: "Close all new applications and reloans?" The dialog lists:
     - "Nobody can start a new application or a reloan until you turn this back on."
     - "Applications already in progress and active loans carry on as normal."
     - "Nothing is cancelled and no messages are sent."
  2. The system upserts `applications-closed`.
  3. Toast "New applications and reloans closed" or "Applications reopened".
- **Business rules:** while the flag is on, `ApplicationFlowService` refuses every new application and reborrow with `APPLICATIONS_CLOSED`, and the borrower lands on `/applications-closed`. A missing row means open.

### UC-S4-36 Settings: payment gateway switch
- **Actor / working role / permission:** real ADMIN.
- **Entry point:** Settings → **Payment gateway**.
- **Screen:**
  - The switch **Enable payment gateway**. Its description is one of:
    - "On. Borrowers pay online through PayU."
    - "Off. Borrowers pay directly by UPI or bank transfer and upload a screenshot."
    - "Off. The gateway's merchant credentials are not set up on this server, so it cannot be turned on." (In this state the switch is disabled.)
- **Main flow:**
  1. Turning it **off** asks for confirmation: "Turn off the payment gateway?" The dialog lists:
     - "Borrowers will see the direct payment option (UPI / bank details + screenshot) instead of online checkout."
     - "Payments already started at PayU are still confirmed and credited."
     - "Manual payments need the accounts team to verify them."
  2. The system upserts `payu-repayment`.
  3. Toast "Payment gateway turned on" or "Payment gateway turned off — borrowers pay manually".
- **Business rules:** turning it **on** is refused with `PAYU_NOT_CONFIGURED` unless the merchant key and salt are set and the hosts are allowed. `ProductionConfigGuard` would refuse to boot with the flag on and no credentials. The PayU reconcile scheduler ignores the flag, so money in flight is never stranded.

### UC-S4-37 Settings: company payment block and UPI QR
- **Actor / working role / permission:** read by any authenticated actor; write by ADMIN only (`FORBIDDEN_ROLE` "Only an admin can change payment settings."). The old route `/staff/admin/payment-settings` redirects to Settings.
- **Entry point:** Settings → **Payment settings**.
- **Screen:**
  - **Payee details** form:
    - **UPI id** ("navix.collections@hdfcbank").
    - **Account name** ("DhanBoost").
    - **Account number**, **IFSC**, **Bank name**.
    - A **UPI QR** image upload and an **Account info** PDF upload.
  - **Current payee (borrower view)** preview with the same rows.
    - The QR enlarges on hover.
    - When nothing is uploaded, the QR and account info show "Static fallback (no upload yet)".
- **Main flow:**
  1. Admin selects files. They upload through presign (category `PAYMENT_SETTINGS`).
  2. Admin saves. The system applies only the non-null fields to the singleton row.
  3. Toast "Payment settings saved".
- **Business rules:** the S3 keys are never returned. Reads return short-lived presigned URLs. This block is what the borrower `/repay` manual-payment screen shows.

### UC-S4-38 Onboarding waitlist register
- **Actor / working role / permission:** real ADMIN (the backend `list` calls `requireAdmin`); UI `waitlist:view`.
- **Entry point:** nav Customers & credit → **Onboarding waitlist**.
- **Screen:**
  - Title "Onboarding waitlist", with the subtitle "Details left by borrowers while new onboarding is paused."
  - **Search** ("Name, mobile, email, PAN or Aadhaar").
  - Columns: Submitted · Name · Mobile · Email · PAN · Aadhaar, **in full**. This is the only surface that shows them in full.
  - Filters on Submitted and Name. Export.
  - Empty state: "No submissions yet."
- **Business rules (the borrower side that fills it):**
  - Validation is local only, with no vendor call: name, email shape, a 10-digit mobile, the PAN regex and the Aadhaar Verhoeff checksum.
  - Errors: `INVALID_NAME` "Enter your full name", `INVALID_EMAIL`, `INVALID_MOBILE`, `INVALID_PAN`, `INVALID_AADHAAR`.
  - `DUPLICATE_PAN` / `DUPLICATE_AADHAAR` "…already registered with another customer."
  - `ONBOARDING_OPEN` when the flag is off.
  - The borrower sees their Aadhaar reduced to the last 4 digits, and gets `WAITLIST_RECEIVED` (in-app + email).
- **Postconditions:** read-only for staff. There is no "promote to onboarding" action.

### UC-S4-39 All-applications register
- **Actor / working role / permission:** real ADMIN (`AdminApplicationService.requireAdmin`); UI `staff:manage`.
- **Entry point:** nav **All applications** (`/staff/admin/all-applications`).
- **Screen:**
  - Title "All applications", with the subtitle "Every application — complete and incomplete — with full KYC detail. Admin only."
  - A completeness filter: All / **Complete only** / **Incomplete only**.
  - A search box. The headline column filters are Status and Risk, plus number bands for Amount and Credit score; Name, PAN, Account, IFSC and Mobile sit under "More filters" (text only).
  - Rows: name, PAN, salary account, IFSC, mobile, status, requested amount, credit score (or a bureau-state label: no record / not fetched), risk, Complete badge, and an agreement warning ("Borrower has not accepted the signup terms").
  - The row action opens the application detail dialog.
  - **Download all** (CSV/PDF) walks every filtered page. The export carries every field: App ID, Customer ID, Status, Complete, Steps x/y, Agreement, Name, PAN, Mobile, Email, DOB, Address and more.
- **Business rules:**
  - Search matches app id, customer id, status, name, PAN, mobile and email, in SQL.
  - Completeness is derived (the required verification steps are PASS or REVIEW, and the agreement is accepted), so it is filtered after SQL.
  - The order is fixed: newest id first. **No column is sortable**, and no sort chevron is shown.
  - The page size is capped at **100**.
  - 4xx errors are not retried.

### UC-S4-40 Rejections register
- **Actor / working role / permission:** real ADMIN; UI `staff:manage`.
- **Entry point:** nav **Rejections** (`/staff/admin/rejections`).
- **Screen:**
  - Title "Rejections" (export subtitle "Rejection register"), with a Refresh button.
  - Columns: Borrower · Amount · Credit · Reason · Source (automatic / manual) · Blocked until (red while in the future) · Rejected on. All are filterable and sortable. Dates are IST days.
  - Reason labels:
    - Self-employed
    - Past delinquency
    - Low credit score
    - Fraud rejected
    - Duplicate Aadhaar
    - Rejected by credit (`MANUAL`)
  - Export columns: ID, App ID, Customer ID, Name, Mobile, PAN, Account, IFSC, Amount, Credit score, Credit rating, Reason, Detail, Source, Blocked until, Rejected on.
  - Empty state: "No rejections yet."
- **Business rules:**
  - Newest first.
  - The borrower never sees any of this; they always get the same neutral message.
  - Cooling-off windows come from the rejection rows. For example:
    - self-employed: 90 days;
    - manual lead rejection: 30 days;
    - fraud: 365 days;
    - duplicate Aadhaar: permanent.
- **Edge cases / not allowed:** the page comment still describes the sub-550 auto-reject as live, but `bureau-auto-reject` is suspended (V64).

---

## §G Provider operations

### UC-S4-41 Run a provider API from the workbench
- **Actor / working role / permission:**
  - The backend requires the real ADMIN ("Administrator access required").
  - The nav uses `verification:retry`.
  - The page itself checks that `session.role === "ADMIN"`, which is the **working** role. Otherwise it shows "Administrator access is required."
- **Entry point:** nav Team & settings → **Provider API dashboard** (`/staff/admin/api-dashboard`).
- **Screen:**
  - Heading "Provider API dashboard", with the text "Every Signzy and Digitap call the platform makes is recorded here with its exact request and response. Provider credentials remain server-side."
  - **API** select, **Provider** select, and the input fields for the chosen API (required fields marked *).
  - **Run API** (for the bureau: "Run API (up to 95s)").
  - The result shows Request and Response JSON blocks with copy buttons and the duration in ms.

  | API | Providers | Fields |
  |---|---|---|
  | PAN | SIGNZY, DIGITAP | PAN* |
  | EMAIL | SIGNZY, DIGITAP | Email*, Individual name, Employer name |
  | ADDRESS | SIGNZY, DIGITAP | Latitude*, Longitude* |
  | BUREAU | FINTRIX, SIGNZY_EXPERIAN, SIGNZY_CRIF, DIGITAP | PAN*, Full name*, Mobile*, Date of birth*, Consent OTP |
  | PENNY_DROP | SIGNZY | Account number*, IFSC*, Beneficiary name* |
  | FACE_MATCH | DIGITAP | Selfie image URL/base64*, Document image URL/base64 |
  | UAN | DIGITAP | PAN, Mobile, Date of birth, Employee name, Employer name |

- **Main flow:**
  1. Admin chooses an API and a provider, fills the fields and runs.
  2. The call executes with the actor and the provider-call context re-bound on the worker thread, so the audit row is attributed to the admin and labelled **MANUAL**.
- **Business rules:**
  - Timeout: 95 s for the bureau, 35 s otherwise (`PROVIDER_TIMEOUT` "Provider call timed out after N seconds").
  - Errors: `UNKNOWN_PROVIDER_OPERATION`, `UNSUPPORTED_PROVIDER` "Provider does not support this operation", `MISSING_PROVIDER_INPUT` "{label} is required", `PROVIDER_API_FAILED`.
  - **Every run is a real, billable vendor call.**
- **Postconditions:** a `provider_api_execution` row is written, with credentials stripped and a 90-day expiry.

### UC-S4-42 Browse provider call history
- **Actor / working role / permission:** as in UC-S4-41.
- **Entry point:** the same page, section "Call history (90 days)".
- **Screen:**
  - Filters:
    - **Source** (All / LIVE / MANUAL), **API**, **Provider**, **Status** (All / SUCCESS / FAILED);
    - **Application id**;
    - **From** / **To** dates;
    - **Reset**.
  - The page shows 25 rows, with "Showing a–b of n" and Previous / Next.
  - Each row opens to load its Request and Response lazily.
  - Empty state: "No provider calls match these filters."
- **Business rules:**
  - The server page size is capped at 100.
  - Rows expire after 90 days. A purge runs daily at **02:15**.
  - Each row carries the `requestId`, so it can be joined to the application logs.
- **Edge cases / not allowed:** the From and To inputs are sent as **UTC** day bounds (`T00:00:00Z` / `T23:59:59Z`), not IST (§Z-12).

### UC-S4-43 Provider health alerts (system)
- **Actor / working role / permission:** the system. Recipients are every ADMIN, by in-app + email.
- **Trigger:**
  - **Balance exhausted:** pushed from inside the call that saw the vendor's exhausted-balance code. This sends `PROVIDER_BALANCE_EXHAUSTED`.
  - **Capability down:** an hourly sweep at **:15** over the last **24 h** of `provider_api_execution`. It alerts `PROVIDER_CAPABILITY_DOWN` for a provider + capability with **≥ 10 live calls and 0 successes**.
- **Business rules:**
  - At most **one alert per key every 6 hours**.
  - Alerting is best-effort. A failed alert never fails a verification.
  - It runs on a single instance only.

### UC-S4-44 Bureau rescore backfill (API only)
- **Actor / working role / permission:** real ADMIN. There is **no screen**; it is driven by `curl` against `/api/admin/bureau-backfill/*`, and the results are read from `bureau_backfill_row` by SQL.
- **Main flow:**
  1. **Preview** (`GET /preview`): returns a count per cohort with zero provider calls. The operator approves this first.
  2. **Execute** (`POST /execute?cohort=&limit=&ids=`): re-pulls the Fintrix CRIF bureau.
- **Cohorts:**
  - **REJECTS:** `REJECTED` with a `LOW_BUREAU_SCORE` rejection. A new score ≥ the floor reopens the file DRAFT → KYC_PENDING.
  - **CREDIT_REVIEW:** `CREDIT_EXEC_PENDING`.
  - **PENDING_REVIEW:** `REVIEW_PENDING`, `KYC_PENDING`.
  - **APPROVED:** `KYC_APPROVED`, `PRE_APPROVED`, `SANCTIONED`.
  - The last three cohorts are refresh-in-place only.
- **Business rules:**
  - `limit` may be at most **500** per run (`BACKFILL_LIMIT_INVALID`).
  - The run is resumable. An application whose latest row is not `FAILED` is skipped, unless an explicit `ids` list is given (still bound by the limit).
  - A refresh **never auto-rejects**.
- **Outcomes:** REOPENED, REFRESHED, STILL_BELOW, NO_BRIEF, MISMATCH_REVIEW (the report's PAN/DOB does not match KYC), KBA_REQUIRED, MASKED_MOBILE_REQUIRED, FAILED (the only one retried), SKIPPED.
- **Postconditions:** one ledger row per application per run. The provider calls appear in the provider dashboard attributed to the backfill actor.

### UC-S4-45 Sub-floor sweep (API only)
- **Actor / working role / permission:** real ADMIN. No screen (`POST /api/admin/bureau-backfill/reject-sub-floor?limit=&dryRun=`).
- **Main flow:**
  1. The system finds live-queue applications whose **existing** score sits under the floor.
  2. With `dryRun=true` (the default) it only counts.
  3. With `dryRun=false` it rejects them with `AUTO_REJECT_LOW_BUREAU_SCORE`, a rejection row and a **90-day** block. The ledger outcome is `SWEPT_REJECTED`.
- **Business rules:**
  - It makes **no provider call**.
  - Only scores in the plausible **300–900** band are eligible. Parse artefacts such as 11 or 15 need a re-pull, not a rejection.

### UC-S4-46 Bureau-challenge outreach (API only)
- **Actor / working role / permission:** real ADMIN. No screen (`/api/admin/bureau-challenge/*`). The per-borrower form is also reachable from the Customers failure dialog and the "send a resume link" BUREAU card.
- **Main flow:**
  1. **Preview** (`GET /preview?limit=200`): lists who would be contacted, with zero sends.
  2. **Notify** (`POST /notify?limit=`): publishes the "bureau question pending" nudge (in-app + email, no SMS) to each eligible application.
  3. **Notify one** (`POST /notify/{applicationId}`).
- **Business rules:**
  - Eligible = applications in **`KYC_PENDING`** whose bureau is parked behind an unanswered CRIF KBA question.
  - At most **200** per run.
  - It is idempotent: an application already stamped as notified is skipped, never re-mailed.
  - It **never calls Fintrix**. The billable fresh question is minted only when the borrower clicks.
  - The operator is told to start with `limit=1` and check the inbox.

### UC-S4-47 Recompute outstanding balances (API only)
- **Actor / working role / permission:** real ADMIN. No screen (`POST /api/admin/loans/recompute-outstanding?apply=`).
- **Main flow:**
  1. The system scans every loan in ACTIVE, OVERDUE or IN_COLLECTIONS.
  2. It reports **scanned**, **balanceDrift** (the recomputed balance differs from the cached `loan.outstanding`), **wouldClose** (the corrected balance is 0) and **closed**.
  3. With `apply=true` it recomputes each loan, which may close it.
- **Business rules:**
  - It is a **dry run by default**, because every closure fires `LOAN_CLOSED` by in-app + SMS + email to the borrower.
  - CLOSED loans are never touched.
  - It is idempotent and re-runnable.

---

## §H Admin eSign documents

### UC-S4-48 Create and send a PDF for Aadhaar eSign
- **Actor / working role / permission:** real ADMIN, with the `esign-documents` flag **on**. Otherwise:
  - `FEATURE_DISABLED` "eSign documents are not enabled."
  - `FORBIDDEN_ROLE` "Only an ADMIN can manage eSign documents".
  - The nav item is hidden when the flag is off, and the UI also requires `staff:manage`.
- **Entry point:** nav **eSign documents** (`/staff/admin/esign`) → **New document**.
- **Screen:**
  - **List page:** "eSign documents", with the subtitle "Send any PDF for Aadhaar eSign — to others, or sign it yourself. Admin only."
    - Columns: Title · Signed (x/y) · Status · Created.
    - Empty state: "No documents sent for eSign yet."
  - **New eSign document dialog:**
    - **PDF (max 10 MB)** (PDF only). Picking a file pre-fills the title from the file name. A preview is available.
    - **Title*** ("Vendor agreement — Acme").
    - **I will sign this myself**: adds a "You" signer first.
    - Signers: **Name exactly as on Aadhaar**, and **Email** (required when emailing). There are 1–5 signers, shown as "{n} / 5 signers", each removable.
    - **Email signing links to external signers** (checkbox).
    - **Signature boxes** (the placer):
      - The copy reads "Pick a signer, then click on a page to drop a box. Drag a box to move it, drag its corner to resize, and use × (or Delete) to remove it."
      - Arrow keys nudge a box.
      - **Repeat on every page** copies the selected box onto every page.
      - The default box is 160 × 70 pt. The minimum is **130 × 70 pt** (the vendor minimum).
      - Each signer chip shows their box count.
- **Main flow:**
  1. Admin uploads the PDF. It is presigned into the `esign-documents/` prefix.
  2. Admin names the signers, places the boxes and submits.
  3. A confirmation appears: "Send for eSign?" / "This creates a **real, billable, legally binding Aadhaar eSign contract** with {n} signers. Check each name matches the signer's Aadhaar exactly." → **Create contract**.
  4. The system validates the request and creates **one Signzy contract**, with success and failure redirects to `/esign/done?status=ok|failed`. It stores the document as **SENT** and each signer as **PENDING** with a sign URL.
  5. When emailing is on, it sends the invite to each non-self signer with an email.
  6. Toast "Sent for eSign".
- **Validation (server, `ESIGN_DOC_INVALID`):**
  - "Title and PDF are required." / "The title is too long (200 characters at most)."
  - "The PDF must be uploaded through the eSign document upload."
  - "The PDF is larger than 10 MB." / "The file is not a PDF." / "The PDF has no pages." / "The PDF could not be read (it may be corrupt or password-protected)."
  - "Add between 1 and 5 signers." / "Every signer needs a name (as on their Aadhaar)." / "A valid email is required for {name} to send the signing link."
  - "Place at least one signature box for {name}." / "At most 50 signature boxes per signer ({name})."
  - "Page N does not exist…" / "…invalid coordinates." / "…is too small (minimum 130 × 70)…" / a box must lie fully inside its page.
- **Validation (client):** the same rules, worded "Upload a PDF.", "Enter a title.", "Signer n: enter the name exactly as on Aadhaar.", "Signer n: each signer needs at least one signature box.", "Signer n: email is required to send the signing link.", "Signer n: enter a valid email.", and "This PDF could not be shown. It may be corrupt or password-protected."
- **Alternate & error flows:** a provider failure marks the document **FAILED**.
- **Postconditions:** the contract is real, billable and legally binding, and there is no sandbox.

### UC-S4-49 Track, refresh, resend and download an eSign document
- **Actor / working role / permission:** as in UC-S4-48.
- **Entry point:** a list row → `/staff/admin/esign/{id}`.
- **Screen:**
  - Header: the title, "{file} · {n} pages · created {date}", and a status badge.
  - While SENT: "Waiting for signatures — this page updates automatically every 10 seconds."
  - Downloads: **Original PDF**, **Signed PDF** (once completed), **Audit certificate**.
  - Signer table: Signer ("(you)" for self) · Placement ("2 boxes on pages 1, 3") · Status · Email (its delivery status) · Actions.
  - Actions:
    - **Sign now** (self, PENDING).
    - **Copy link** (PENDING; toast "Signing link copied" / "Could not copy — select the link manually.").
    - **Resend email** (non-self with an email, PENDING, while the document is SENT; toast "Email re-sent").
  - A **Refresh** button.
- **Main flow:**
  1. Only **Refresh** and the provider callback re-read the contract from Signzy. The provider is the authority, and the callback body is never trusted. The 10 s poll (while SENT) just re-fetches the local detail, so it shows what those two wrote.
  2. The system applies the provider state to the rows:
     - Signers move to **SIGNED** with a signed date.
     - If the provider reports the contract **expired**, the document becomes **EXPIRED**.
     - When the contract is **completed**, every signer is marked SIGNED, the signed PDF is stored at `esign-documents/{id}/signed.pdf`, and the document becomes **COMPLETED**.
- **Business rules:**
  - Document statuses: SENT → COMPLETED / EXPIRED, or FAILED at creation. Signer statuses: PENDING → SIGNED.
  - Resend is refused with "This signer has no email address to send to." and "This signer can no longer be sent a signing link."
  - A provider read failure returns `ESIGN_PROVIDER_FAILED` "Could not read the signing status from the provider. Try again shortly."

### UC-S4-50 Signer returns to `/esign/done`
- **Actor / working role / permission:** any signer, with no session (a public marketing route).
- **Screen:** `status=ok` shows "Document signed — you can close this tab." Any other status shows "Signing didn't complete — please ask the sender for a new link."
- **Business rules:** the page writes nothing. The admin page learns the outcome by refresh or callback.

---

## §Z Contradictions found

1. **Mixed cohorts on the snapshot.**
   - "Status Distribution" and "Performance Summary" are described as "applications created in the period".
   - But Disbursed counts loans by `disbursed_on`, while Applications, Pending and Rejected count by `created_at`.
   - As a result, the disbursement rate can exceed 100%, and the three shares need not sum to 100%.
2. **Collection Amount drill-down mismatch.** The bars sum *verified payments in the period*, but their drawers (CLOSED / SETTLED / PART_PAID) list *loans closed in the period*, or open loans with a partial payment.
3. **Company-wise.**
   - "Users by Company" counts customers on *applications created* in the period, but its row click lists *loans disbursed* in the period.
   - Its ⓘ says "(latest profile)", while the SQL uses each application's own profile.
4. **"Invited" staff.** The Employee Snapshot's "Invited (not yet joined)" and the staff register's Invited tab read `staff_user.status = INVITED`. No code writes that status (it exists only in the V2 CHECK), because invites live in `staff_invite`. So both read 0 unless legacy rows exist.
5. **Blocklist screened nothing — FIXED 2026-11-13 (V103), see UC-S4-32.**
   - Before the fix, `BlocklistService.isBlocked` had no caller and navix-loan never read `blocklist_entry`.
   - The controller also says "RBAC deferred", but the service enforces ADMIN.
6. **Dead state counted.** The Disbursement Head's "Failed" card, its queue strip and the `disbursement-pending` report all read `DISBURSEMENT_FAILED`. CLAUDE.md says nothing transitions into it.
7. **PTP tile copy.** The "Total after assignment" and "Same-day collection" hints say money paid after or on the day of a *promise*, but the SQL keys on the *case creation* day. Only the PTP Collected chart actually requires a `PROMISE_TO_PAY` interaction.
8. **Working-role authorization.**
   - CLAUDE.md says authorization stays on the real JWT role.
   - But the decision-history inspect check, the `/inspectable` list and the staff-performance roster all use `effectiveRole()`, the working role. A real ADMIN working as Credit Executive sees only themselves.
   - Conversely, search groups and dashboard boards use the real role.
   - The Provider API page checks the working role in the UI, while the backend checks the real role.
9. **Customer export "logged".** The UI promises "Each export records who pulled it". In practice that is application log lines only; there is no database audit row.
10. **Password rules differ.**
    - Admin "Create staff account" enforces only 8–72 characters.
    - Invite activation and reset enforce `PasswordPolicy`: a letter, a digit and a special character, at most 72 bytes.
    - CLAUDE.md says "Passwords ≥ 8 chars".
11. **Invites.**
    - There is no duplicate check.
    - Accepting overwrites the role of, and re-activates, an existing `staff_user` with the same email, including a DISABLED one.
    - The secret token is shown in the list and its CSV/PDF export.
    - The list cannot show acceptance.
    - The invite form lists raw role codes, while the create form lists labels.
12. **Provider history dates.** The From and To filters are sent as UTC day bounds, against the console-wide IST convention.
13. **Three "repeat" definitions.**
    - Dashboard Fresh/Re-loan: the customer has another loan **CLOSED** on or before the application date.
    - Reports "First / repeat" and platform-summary "Of which repeat loans": **any earlier disbursed loan**.
    - The `customers-repeat` report: two or more loans.
14. **Rejected counts differ.**
    - Staff performance "Rejected" (credit) = `REJECT_LEAD` + `KYC_REJECT`.
    - The `credit-decisions` report and the leaderboard approval factor use `REJECT_LEAD` only.
15. **Targets read access.** `GET /api/dashboard/targets` is open to any non-DSA staffer, although targets are edited and shown only in the Admin view.
16. **Stale register comment.** The rejections page describes the sub-550 bureau-score rejection as a live *intake* auto-reject, but `bureau-auto-reject` has been off since V64. `LOW_BUREAU_SCORE` rows still appear, from the sub-floor sweep (UC-S4-45) and from historical rejections.
