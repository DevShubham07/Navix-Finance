# 03 — Staff: credit, disbursement, repayment verification and the Customer 360

This file is the staff half of the loan lifecycle, written use case by use case: who sees what, which button they press, and the exact rule behind it.
It covers session basics, the `/staff/applications` console for each working role, the credit brief, BRE and trust signals, verification overrides, the Customer 360 (pop-up and full page, all 21 tabs), ADMIN corrections, and the Customers register.
Source of truth is the code as of 2026-11-13 (frontend `src/app/staff/{applications,customers}`, `components/staff/**`, `lib/auth/rbac.ts`; backend `ApplicationFlowService`, `ApplicationController`, `CustomerService`, `CustomerController`, `ApplicationVerificationService`, `RepaymentService`, `PayuReviewService`, `ReapplyAllowanceService`, `VerificationOutreachService`, `CreditRatingCalculator`, `CustomerSegments`/`CustomerBookQuery`).
Copy in quotes is verbatim from the UI or from a `BusinessException`. "Contradiction" notes mark places where code, comments, CLAUDE.md or two screens disagree; Appendix C collects them.
No request/response payloads are documented here — only behaviour. Use-case ids are `UC-S1-nn`.

## Table of contents

- [0. How to read this file — the authorization model](#0-how-to-read-this-file--the-authorization-model)
- [1. Staff session and working role](#1-staff-session-and-working-role) — UC-S1-01 … 03
- [2. The Live applications console (shell)](#2-the-live-applications-console-shell) — UC-S1-04 … 07
- [3. Credit Head workbench](#3-credit-head-workbench) — UC-S1-08 … 13
- [4. Credit Executive desk](#4-credit-executive-desk) — UC-S1-14 … 19
- [5. Disbursement Head desk](#5-disbursement-head-desk) — UC-S1-20 … 25
- [6. Accountant desk and the repayment panels](#6-accountant-desk-and-the-repayment-panels) — UC-S1-26 … 31
- [7. Credit brief, star rating, BRE and trust signals](#7-credit-brief-star-rating-bre-and-trust-signals) — UC-S1-32 … 34
- [8. Verifications: overrides, retry, resume link, reminder, Aadhaar duplicate](#8-verifications-overrides-retry-resume-link-reminder-aadhaar-duplicate) — UC-S1-35 … 40
- [9. Customer 360 — the pop-up, the full page and all 21 tabs](#9-customer-360--the-pop-up-the-full-page-and-all-21-tabs) — UC-S1-41 … 62
- [10. ADMIN corrections](#10-admin-corrections) — UC-S1-63 … 73
- [11. Customers register](#11-customers-register) — UC-S1-74 … 80
- [Appendix A — Notification map for every staff action here](#appendix-a--notification-map-for-every-staff-action-here)
- [Appendix B — Error codes raised by these use cases](#appendix-b--error-codes-raised-by-these-use-cases)
- [Appendix C — Contradictions found](#appendix-c--contradictions-found)

---

## 0. How to read this file — the authorization model

Every use case below depends on four rules. They are stated once here and not repeated.

1. **Two roles per staffer.** The *real role* is the role in the staff JWT. The *working role* is
   picked in the header pill (UC-S1-03), stored per staffer in localStorage
   (`navix-staff-working-role:<staffId>`) and sent on every request as `X-Acting-Role`.
2. **The backend authorizes on the real role.** `requireRole`/`requireAnyRole` read the JWT role,
   and **ADMIN always passes** them (oversight). The working role reaches the backend only as
   `effectiveRole()`, which **only scopes lists**: the Credit Executive's "assigned to me" filter, the
   customer book (§11), the Collection Executive's book, and the Disbursement Head's "mine" history.
   `ActingRole.normalize` drops an illegal header value, and drops a value equal to the real role.
3. **The frontend gates buttons on the working role.** `can(real, working, permission)` returns
   `effectivePermissions(working)`, which is the working role's own permission list. One exception:
   the working role `ADMIN` gets the narrower `ADMIN_ROLE_PERMISSIONS` list, which holds **no
   lifecycle tokens** (no `kyc:approve`, `loan:review`, `loan:approve`, `loan:disburse`,
   `loan:activate`, `loan:pipeline`). A real ADMIN must therefore switch working role to act on any
   queue. Admin-only corrections are gated on `customer:manage` or `verification:retry`, and only the
   Admin working role holds those. The one screen that breaks this rule is the Customers register
   (UC-S1-79), where `workingAsAdmin` unlocks bulk assign and bulk reject.
4. **A button the UI hides is not a guarantee.** Where the UI and the backend disagree, the backend
   wins and the UI shows its error text (`errMessage` → the exception message). Where the UI offers a
   button that the backend will refuse, this file says so.

Permission tokens used below (from `rbac.ts`):

| Token | Held by (working role) |
|---|---|
| `kyc:approve`, `loan:review` | Credit Executive (and ADMIN's full list, but **not** the Admin working role) |
| `loan:approve` | Credit Head |
| `loan:disburse` | Disbursement Head |
| `loan:activate` | Accountant |
| `loan:pipeline` | every role except Telecaller, DSA and the Admin working role (gates the "Live applications" nav item) |
| `customer:view` | every role except DSA |
| `customer:view:all` | Credit Head, Disbursement Head, Collection Head, Admin |
| `customer:assign` | Credit Head, Collection Head, Telecaller, Admin |
| `customer:manage`, `verification:retry`, `message:send` | Admin working role only |
| `mandate:override` | Disbursement Head, Admin |
| `mandate:debit` | Collection Head, Collection Executive, Admin |
| `collections:manage` / `collections:interact` | Collection Head / Collection Executive |
| `document:upload` | Credit Executive, Credit Head, Admin |
| `leads:manage` | Telecaller, Admin |

**Audit.** Every lifecycle transition and every same-status staff action in `ApplicationFlowService`
goes through `logEvent`. That method appends one `application_event` row carrying from/to status,
action, notes, actor id, **real** role and **acting** role (V78). It also publishes
`ApplicationTransitionedEvent`, which the notification engine maps by action (Appendix A).

---

## 1. Staff session and working role

### UC-S1-01 Sign in to the console (one live session per staffer)
- **Actor / working role / permission:** any staff role, including ADMIN and DSA. No token is needed.
- **Entry point:** `/staff/login`.
- **Preconditions:** the staff account is `ACTIVE`.
- **Screen:** an email field, a password field, a Cloudflare Turnstile widget, and a **Sign in** button
  (it reads "Signing in…" while busy). Footer: "Separation of duties is enforced on every decision."
  If the previous session was superseded, a banner shows "You were signed out because this account
  signed in on another device or browser."
- **Main flow:**
  1. The staffer submits their credentials.
  2. The backend verifies the password.
  3. The backend checks for a live session: `active_session_id` is set, and `active_session_at` is
     newer than the staff token TTL (1 day).
  4. If no session is live, a new session id (UUID) is minted, stored on the staff row, and put in
     the token's `sid` claim.
  5. The BFF sets the `navix_staff` cookie, and the staffer lands on `/staff/dashboard`. A DSA lands
     on `/staff/dsa/leads`.
- **Business rules:**
  - Three wrong passwords within 15 minutes trigger a 30-second cool-off (`TOO_MANY_ATTEMPTS`).
    Successful sign-ins do not count towards the three.
  - A correct password wipes the attempt counter.
  - A session stamp older than the token TTL is treated as stale, so it never triggers a conflict.
- **Alternate & error flows:**
  - Wrong credentials → `INVALID_CREDENTIALS` "Invalid email or password".
  - A live session elsewhere → `SESSION_CONFLICT` "You're already signed in on another device or
    browser." This opens the dialog **"You're signed in elsewhere"**: "Only one DhanBoost console
    session is allowed per account. Choose where you want to stay signed in."
    - **Continue there** closes the dialog.
    - **Continue here** re-challenges Turnstile, then resubmits with `force=true`. That replaces the
      stored session id and evicts the session cache.
- **Postconditions:** the staffer has one live session. Any other tab's token now fails the session
  check (UC-S1-02).
- **Edge cases / not allowed:** a token minted before single-session tracking (no `sid`) is
  grandfathered as current.

### UC-S1-02 Lose the session (superseded, disabled or role changed)
- **Actor:** system.
- **Entry point:** any staff request. `JwtAuthFilter` → `StaffSessionRegistry.isCurrent`.
- **Business rules:**
  - The request is valid only if the account is `ACTIVE` and the token's `sid` equals the stored
    session id.
  - The lookup is cached for **30 seconds**, so a superseded tab can keep working for up to 30 s.
  - Disabling a user or changing their role revokes the session (CLAUDE.md §7).
  - The middleware `/staff/*` gate checks only the cookie JWT's `exp`, without verifying the
    signature. When it fails, it redirects to `/staff/login?redirect=…` and deletes the cookie.
- **Postconditions:** the next request gets 401 and the staffer is sent back to sign in.

### UC-S1-03 Switch working role (header pill)
- **Actor:** a real ADMIN, Credit Head or Collection Head. Every other role has a fixed pill.
- **Entry point:** the role pill in the staff header. Its aria-label is "Switch role, currently <label>".
- **Screen:** a menu of the allowed roles, with a check mark on the current one.
  - ADMIN → Administrator (default), Credit Head, Credit Executive, Disbursement Head, Accountant,
    Collection Head, Collection Executive, Telecaller. DSA is never offered.
  - CREDIT_HEAD → Credit Head, Credit Executive.
  - COLLECTION_HEAD → Collection Head, Collection Executive.
- **Main flow:**
  1. The staffer picks a role.
  2. The new working role is written to localStorage, every cached query is invalidated, the router
     goes to `/staff/dashboard`, and a toast shows "Now working as <role>".
- **Business rules:**
  - The working role narrows lists and the visible tabs and buttons. It never grants backend
    authority (§0).
  - Events record "by <name> · <REAL ROLE> (as <working role>)".
- **Edge cases / not allowed:**
  - Picking the current role just closes the menu.
  - A stored working role the real role may not use is ignored, and the real role is used instead.

---

## 2. The Live applications console (shell)

### UC-S1-04 Open the Live applications console
- **Actor / working role / permission:** every role whose nav shows the item, i.e. holders of
  `loan:pipeline`.
  - The nav label depends on the working role: Credit Head "Credit desk · all files", Credit
    Executive "My credit files", Disbursement Head "Disbursal queue", Accountant "Repayments to
    verify", Collection Head "Payment approvals", Collection Executive "My repayment book".
  - Any other role sees "Live applications".
- **Entry point:** sidebar "Customers & credit → Live applications" (`/staff/applications`). The
  global-search deep link `?q=<id>` pre-fills the search box.
- **Preconditions:** a live staff session. Without one the page shows "No live staff session. Sign
  in via the staff console to use the live application queues."
- **Screen (header):**
  - The page title from the nav label, and the subtitle "Every queue your role can act on, in one
    place. Real backend state machine — walk loans through to ACTIVE."
  - A working-role chip.
  - For the pipeline roles (Credit Head, Credit Executive, Disbursement Head, Accountant) and the two
    collection roles, an **"Applied on"** date filter and a search box. The date filter offers
    Today / Yesterday / Custom / All time and is kept in the URL (`win`, `wfrom`, `wto`). The search
    box placeholder is "Search name, mobile, PAN, application or loan #"; it commits on Enter or
    Search, not on each keystroke.
  - A **Refresh** button.
  - For `loan:activate` (Accountant), an **"All transactions"** link to `/staff/accounting/transactions`.
- **Screen (body):**
  - "Review an application" (UC-S1-05), shown to every role.
  - Then the role's own desk:
    - Credit Head → §3. Credit Executive → §4. Disbursement Head → §5.
    - Accountant → Repayments to verify, PayU review, Collection payment validation, Awaiting
      repayment, Closed (§6).
    - Collection Head → Collection payment approvals and Awaiting repayment.
    - Collection Executive → their repayment book (tabs: Overdue / Active / Closed (repaid)).
  - Any other working role (Admin, Telecaller, DSA) sees: "The <role> role has no
    application-pipeline queue. Use "Review an application" above to open any application by ID…"
- **Business rules:**
  - The date filter narrows every panel to applications **created** in the window. Day boundaries
    are IST.
  - The search narrows **within** the window. It matches an application id or loan id by prefix, or
    a case-insensitive substring of name, mobile or PAN. The match is done in memory over the
    status-and-date page.
  - Every list is filtered again by the caller's customer book (`visibleAmong`, §11). Telecaller is
    refused on the queue endpoint (`FORBIDDEN_ROLE`).
- **Postconditions:** none (read-only).

### UC-S1-05 Review an application by ID
- **Actor / permission:** every role holding one of `customer:view`, `kyc:approve`, `loan:review`,
  `loan:approve`, `loan:disburse`, `loan:activate`. That is every role except DSA.
- **Entry point:** the "Review an application" panel on `/staff/applications`.
- **Screen:**
  - "Open any application by its ID to view the customer's details and documents — loaded on demand."
  - The input "Application ID" accepts digits only (placeholder "e.g. 8").
  - **Open review** stays disabled until something is typed.
- **Main flow:**
  1. The staffer types an id and presses Open review.
  2. Below the input, the panel renders "Application #N" and the read-only customer review: the KYC
     profile and the document list.
- **Business rules:** the backend applies the customer-book scope. Outside the book the response is
  404, never 403, so the customer's existence is not confirmed.
- **Alternate flows:**
  - No profile → "No KYC details were submitted for this application."
  - No documents → "No documents uploaded."

### UC-S1-06 Work a queue table (shared by every application panel)
- **Actor:** any role with a desk.
- **Screen:**
  - **Panel header:** title, an ⓘ info text, a spinner on first load, a count badge, a bulk hint and
    the bulk bar (when the role has bulk rights), and **Refresh**. The bulk hint reads "Tick rows to
    assign or reject in bulk", "…assign in bulk" or "…reject in bulk".
  - **Filter bar:** pinned dropdown filters for Amount, Credit score, Credit executive and Stage, plus
    "Clear filters". Every other sortable column has a caret filter in its header.
  - **Table columns:**
    - S.No.; an optional **Select** checkbox column.
    - **Application** (`#id`, opens the detail dialog), Customer ID, **Date** (created).
    - **Customer** (name, else "Name unavailable"), **Signals** (trust stars, UC-S1-34).
    - Mobile, PAN, Account, IFSC. Account and IFSC show the disbursal account, falling back to the
      salary account, unmasked by product decision.
    - Loan (`#loanId`).
    - **Amount**: the requested figure in ink with "req", or the eligible limit muted with a leading
      "elig".
    - **Due**: the due date, red with "+Nd" when past due and the loan is not settled, plus an amber
      **Pending** tag when the file is marked pending.
    - **Credit score** (badge: score, stars, recommendation).
    - **Stage**: an overdue or in-collections loan status outranks the application status.
    - Credit executive (who **decided** the file), Disbursed by, Collections exec.
    - **Actions**: **Open →**, a journey icon, plus the role's row actions.
- **Main flow:**
  1. The panel polls every **8 s** (history tabs every 60 s). Filters and sort are client-side over
     the loaded rows; the default sort is Date descending.
  2. Pagination sits below the table, with a selectable page size.
- **Business rules:**
  - **Filter before select.** "Select all" ticks only the rows the column filters leave visible, so a
    bulk reject — which carries a 30-day cooling-off — can never hit hidden rows.
  - The selection resets whenever the row-id list changes (a poll, a filter or a page change).
  - Bulk calls loop the per-id endpoint, up to **4 in flight**, with "n / total done" progress. A
    single failure never stops the run; the summary reads "n rejected, m failed (#ids)".
- **Alternate & error flows:**
  - Load error → an error state with Retry.
  - Empty queue → "Nothing in the <STATUS> queue."
  - Filters hide every row → "No applications match the column filters." / "Clear a column filter to
    see more."

### UC-S1-07 Open the application detail dialog (the unified pop-up)
- **Actor:** every role except DSA. Without one of the review permissions the dialog shows
  "Customer details (incl. PII) aren't available to your role."
- **Entry point:** the `#id` or **Open** on any queue row, a Customers-register row, the
  repayment-verify **Customer** button, or a deep link `?open=<loanId>`. A loan-id entry resolves
  loan → customer → the application that carries that loan.
- **Screen:**
  - **Header line:** "<Full name> — Application #N", then: customer #, mobile, PAN, "risk <A–D>", the
    status badge, a red **Blocked** badge (tooltip: the block reason), the stage signal, and the
    credit badge (only when the credit brief is available).
  - **"Needs manual review"** badge, shown when a BANK_PROOF, AADHAAR_FRONT or AADHAAR_BACK document
    exists. Its tooltip reads "Cheque or Aadhaar uploaded — verify manually on the Verifications tab."
  - An **"Action pending → Sanction"** chip (KYC_PENDING, KYC_APPROVED, CREDIT_EXEC_PENDING,
    SANCTIONED) or **"Action pending → Disbursal"** chip (DISBURSEMENT_PENDING, DISBURSEMENT_FAILED)
    that jumps to the tab holding the action.
  - **Full customer page ↗** and **Close (X)**.
  - The mobile-reuse strip: other customers sharing this mobile, opening a match drawer.
  - The 21 pill tabs (§9) on the left, with the tab body on the right.
- **Business rules:**
  - The **default tab follows the stage**:
    - Sanction for CREDIT_EXEC_PENDING, KYC_PENDING, KYC_APPROVED and SANCTIONED.
    - Disbursal for DISBURSEMENT_PENDING and DISBURSEMENT_FAILED.
    - Repayment for ACTIVE and OVERDUE.
    - Customer otherwise.
    - It is chosen once per file, and a caller-supplied `initialTab` wins over it.
  - The application is re-polled every 8 s. A status change invalidates the customer roll-up.
  - Another application of the same customer, opened from the Loan tab, **stacks** one level deep.
    The nested copy cannot open a further level.
- **Edge cases:** the docstring says "20 lifecycle tabs", but `CUSTOMER_TABS` has 21 (contradiction
  C-17).

---

## 3. Credit Head workbench

The Credit Head's single lifecycle step since V45 is to hand a submitted intake to an executive. On
`/staff/applications` the Credit Head working role sees **only** the workbench.

### UC-S1-08 View the credit workbench
- **Actor / working role / permission:** Credit Head (`loan:approve`), or a real ADMIN working as
  Credit Head.
- **Entry point:** `/staff/applications` (nav label "Credit desk · all files").
- **Screen:** stacked panels, one per group. Each group has its own filters, bulk bar and count:
  1. **Unallocated** — the credit queue: every `KYC_PENDING` and `KYC_APPROVED` application, newest
     first. **No row-level action buttons**; assignment happens in the detail dialog or through bulk.
  2. **Assigned to me** — `CREDIT_EXEC_PENDING` files whose `assigned_executive_id` is the Head.
  3. **One panel per ACTIVE Credit Executive**, keyed by staff id. Two executives with the same name
     get two panels.
  4. **Other assignees** — `CREDIT_EXEC_PENDING` files assigned to an id that is neither the Head nor
     an active executive (for example, a deactivated staffer), or to nobody.
  - Every panel's ⓘ reads "Credit review remains one stage. The Credit Head may decide any file or
    reassign it; executives can decide only their own files."
- **Business rules:**
  - The credit-queue endpoint requires the real role CREDIT_HEAD (ADMIN passes).
  - The executive roster is cached for 15 minutes; the panel's Refresh re-reads it.
  - Bulk **assign** is offered only on Unallocated. Bulk **reject** (credit mode) is offered on every
    group.
  - Rows in the assigned groups show **Reassign** and **Reject**. **Accept** appears only when the
    working role holds `loan:review`, which the Credit Head working role does not.
- **Edge cases:** to decide a file the Head must switch to the Credit Executive working role. The
  full action cluster says "Switch to Credit Executive to decide."

### UC-S1-09 Assign or reassign a file to an executive
- **Actor / working role / permission:** Credit Head (`loan:approve`). Backend: real role
  `CREDIT_HEAD`, and ADMIN passes.
- **Entry point:** the detail dialog's Sanction tab. On a queue row, the **Assign** / **Reassign**
  button opens the single-id Assign dialog.
- **Preconditions:** the application is `KYC_PENDING` or `KYC_APPROVED` (first assignment), or
  `CREDIT_EXEC_PENDING` (reassignment).
- **Screen:**
  - In the dialog tab: a "Credit executive" select (placeholder "Select executive…", listing only
    ACTIVE Credit Executives), **Assign** or **Reassign**, and **Assign to me** or **Reassign to me**.
  - The single-id Assign dialog is titled "Assign 1 application?" and has the same select, **Assign**
    and **Cancel**.
  - The roster can also show "Loading executives…", "No active credit executives", or the error
    "Couldn't load executives — <message>" with Retry.
- **Main flow:**
  1. The Head picks an executive and confirms.
  2. From KYC_PENDING or KYC_APPROVED, the file moves to `CREDIT_EXEC_PENDING`. The event is
     `ASSIGN` with "executiveId=<id>".
  3. From `CREDIT_EXEC_PENDING`, the file stays in place and gets a same-status `REASSIGN` event with
     "previousExecutiveId=… newExecutiveId=…". The review data is kept.
  4. A toast confirms: "Credit executive assigned".
- **Business rules:**
  - The assignee must be an ACTIVE Credit Executive, **or** the acting Credit Head themself.
  - ADMIN is exempt from the assignee check and may assign anyone, including themself.
  - There is no amount check: the borrower never names an amount before sanction.
- **Alternate & error flows:**
  - An inactive or non-executive assignee → `INVALID_ASSIGNEE` "The assignee must be the Credit Head
    or an active Credit Executive".
  - Any other status → `ILLEGAL_TRANSITION` "<FROM> → CREDIT_EXEC_PENDING is not allowed".
- **Postconditions:**
  - `ASSIGN` sends **CREDIT_ASSIGNED** (IN_APP) to the assigned executive.
  - `REASSIGN` sends **nothing**: no notification mapping exists for it (contradiction C-10).

### UC-S1-10 Assign to me
- **Actor:** Credit Head (`loan:approve`) or ADMIN in the Credit Head working role.
- **Entry point:** **Assign to me** or **Reassign to me** in the dialog. Its tooltip reads "Assign
  this credit review to yourself".
- **Main flow:** the same call as UC-S1-09, with the staffer's own id. Toast: "Assigned to you".
- **Business rules:** a Credit Head self-assignment skips the active-executive check. The file then
  appears in "Assigned to me".

### UC-S1-11 Bulk assign unallocated files
- **Actor:** Credit Head (`loan:approve`). It is also available on the Customers register for the
  Admin working role (UC-S1-79).
- **Entry point:** tick rows in **Unallocated**, then **Assign selected**.
- **Screen:** the dialog is titled "Assign N applications?". It has the executive select, a progress
  line, then the result "N assigned, M failed (#…)" and a **Done** button.
- **Business rules:**
  - There is no batch endpoint: each id is a separate call, so every guard in UC-S1-09 runs per id.
  - The selection clears when the run finishes.

### UC-S1-12 Reject a credit-stage file (single or bulk)
- **Actor / working role / permission:**
  - UI: shown to anyone with credit-row actions. The bulk flag is `loan:approve`, `loan:disburse` or
    `collections:manage`.
  - Backend `rejectLead`: real role CREDIT_EXECUTIVE or CREDIT_HEAD, and ADMIN passes.
- **Entry point:**
  - The row **Reject** button, or **Reject selected**. Both open the Reject dialog.
  - In the full dialog cluster, **Reject lead** opens an inline input ("Why (staff-only)") with
    **Confirm** and **Cancel**.
- **Preconditions:**
  - The application is in a status that may move to REJECTED: DRAFT, KYC_PENDING, KYC_APPROVED,
    CREDIT_EXEC_PENDING or SANCTIONED.
  - PRE_APPROVED may be rejected by ADMIN only.
- **Screen:**
  - The dialog is titled "Reject N application(s)?".
  - Consequence text: "The borrower is notified and blocked from re-applying for 30 days." (the bulk
    form reads "Each borrower…").
  - A textarea, placeholder "Why (staff-only, required)".
  - **Reject** stays disabled until the textarea has text.
- **Main flow:**
  1. The staffer enters a reason and confirms.
  2. The backend writes a rejection-register row with reason code `MANUAL`, the remarks, and
     `blocked_until` = now + **30 days**.
  3. The file moves to `REJECTED`. The event is `REJECT_LEAD`, with the remarks and the retry-from
     date.
- **Business rules:**
  - A Credit Executive may reject only files assigned to them. A real Credit Head, or ADMIN, may
    reject any file.
  - The borrower is **never told the reason**.
- **Alternate & error flows:**
  - Blank reason → `NOTE_REQUIRED` "A reason is required to reject this application".
  - PRE_APPROVED rejected by a non-ADMIN → `FORBIDDEN_ROLE` "Only an admin can reject a pre-approved
    lead".
  - An executive rejecting a file not assigned to them → `CREDIT_FILE_NOT_ASSIGNED` "This credit file
    is not assigned to you".
  - Wrong status → `ILLEGAL_TRANSITION`.
- **Postconditions:**
  - **CREDIT_REJECTED** goes to the borrower on IN_APP, SMS, EMAIL and WHATSAPP, with no reason.
  - The borrower is blocked from new applications and reborrows until the block date.

### UC-S1-13 Head deciding a file directly
- **Actor:** a real Credit Head in the Credit Executive working role, or a real ADMIN working as
  Credit Executive.
- **Business rules:** the ownership check `requireCreditOwnership` fires only for **real** role
  `CREDIT_EXECUTIVE`. A real Head therefore sanctions, rejects or parks any file, assigned or not.
- **Edge cases:**
  - In the Credit Executive working role, the desk lists are filtered to files assigned to the
    staffer (UC-S1-14). To decide someone else's file, the Head first reassigns it to themself
    (UC-S1-10), or opens it from the Head working role and switches role.
  - `sanction` is also legal from `KYC_APPROVED` and `PRE_APPROVED`, without any assignment. The UI
    only offers Accept on `CREDIT_EXEC_PENDING`.

---

## 4. Credit Executive desk

### UC-S1-14 View "My credit files"
- **Actor / working role / permission:** Credit Executive (`loan:review`, `kyc:approve`). This
  includes a real Credit Head or a real ADMIN working as one.
- **Entry point:** `/staff/applications`, whose nav label is "My credit files".
- **Screen:** queue tabs, each with a live count. A live tab with work gets a gold badge. The last
  tab picked is remembered for the session (`navix-staff-queue-tab:credit-executive`).

  | Tab | Lists | Row actions | Poll |
  |---|---|---|---|
  | **Credit review** (default) | `CREDIT_EXEC_PENDING`, title "Credit review — accept, reject or park" | Accept · Reject | 8 s |
  | **Sanctioned — borrower completing journey** | `SANCTIONED` | none | 8 s |
  | **Past sanctioned** | DISBURSEMENT_PENDING, DISBURSEMENT_FAILED, DISBURSED, ACTIVE, OVERDUE, DEFAULTED, CLOSED, WRITTEN_OFF | none (read-only) | 60 s |
  | **Rejected** | `REJECTED` | none (read-only) | 60 s |

  The Credit review ⓘ reads "…Accepting sets the sanctioned amount and repayment date — that
  decision is final and goes straight to disbursement, so there is no second approval behind it."
- **Business rules:**
  - The backend `byStatus` scopes a working role of CREDIT_EXECUTIVE to `assigned_executive_id = me`.
    The page applies the same filter on the client, so an ADMIN working as Executive sees only their
    own files.
  - "Past sanctioned" means files assigned to me that moved on. It does not mean files I personally
    sanctioned.

### UC-S1-15 Review the applicant before deciding
- **Actor:** Credit Executive (assigned file).
- **Entry point:** **Open** on a Credit review row. The dialog lands on the **Sanction** tab.
- **Screen:** the action cluster sits at the top of the Sanction tab (UC-S1-16…18). The evidence for
  the decision is spread across these tabs:
  - **Customer** — identity, contact, employment, eligible limit, compliance (UC-S1-42).
  - **Credit report** — gauge plus credit brief (UC-S1-49 / 32).
  - **BRE** — 10 policy rules, flags only (UC-S1-33).
  - **Third-party logs** — every provider result (UC-S1-47).
  - **Verifications** — every check, with override (UC-S1-35).
  - **Documents**, **Banking**, **Dedupe**, **AI Analyzer**.
- **Business rules:** no verification failure hard-blocks the decision. A failed check is evidence
  for the human. The one hard gate is an unresolved `AADHAAR_DUPLICATE` (UC-S1-40).

### UC-S1-16 Accept lead — sanction the application (final credit decision)
- **Actor / working role / permission:**
  - UI: `loan:review` (Credit Executive working role).
  - Backend: real role CREDIT_EXECUTIVE or CREDIT_HEAD, and ADMIN passes. A real Executive must be
    the assignee.
- **Entry point:**
  - **Accept** on a Credit review row, or **Accept lead** in the full cluster on the Sanction tab.
  - Either opens the dialog **"Accept lead — sanction application #N"**: "This decision is final and
    goes straight to disbursement. There is no second approval."
- **Preconditions:** the status is `CREDIT_EXEC_PENDING`; the backend also accepts KYC_APPROVED and
  PRE_APPROVED. No unresolved Aadhaar-duplicate flag.
- **Screen (inputs):**
  - **Sanctioned amount (₹)** — required, digits only (anything else is stripped), placeholder
    "25000".
    - Helper: "The ceiling the borrower may draw from — they may take less."
    - Error below ₹1,000: "Minimum ₹1,000".
  - **Salary credit day** — required, a select from 1 to 31. It defaults to the application's
    salary day, else 30.
    - Helper: "Projected <date> — <n> days from today. Final due date is set from actual disbursal."
    - Error: "Select a day from 1 to 31".
  - **Salary account** (read-only): Bank, Account number, IFSC from the KYC profile. Only the
    borrower can change the payee, in the offer journey.
  - **Repayment schedule** (live preview, shown once the inputs are valid): Principal; Processing fee
    (excl. GST); GST on fee; Interest (1%/day over the projected tenure); **Borrower receives**;
    **Total repayable**. These come from the frontend loan-math mirror.
  - **Remarks** (optional), placeholder "What you verified, and anything the next person should
    know", helper "Staff-only — never shown to the borrower."
  - Buttons: **Cancel**, and **Sanction**, which stays disabled until the preview exists.
- **Main flow:**
  1. The Executive enters the amount and the day, reads the schedule, and presses Sanction.
  2. The backend checks the Aadhaar-duplicate row, the amount floor and the salary day.
  3. The backend computes the projected repayment date as `dueDateFromSalary(today, salaryDay)`: the
     latest salary date on or before 40 days from today.
  4. It stores the sanctioned amount, approved repayment date, sanction tenure days, remarks,
     `sanctioned_by`, `sanctioned_at` and the salary credit day.
  5. It **clears the pending tag**.
  6. It moves the file to `SANCTIONED`. The event is `SANCTION`, with "amountPaise=… salaryCreditDay=…
     projectedRepaymentDate=… — remarks".
  7. Toast: "Application #N sanctioned".
- **Business rules:**
  - **No 25%-of-salary cap** (decision 33). The eligible limit is guidance only.
  - **No upper bound** is enforced at sanction. The ₹10,00,000 product maximum is not checked here
    (contradiction C-18).
  - The real due date is fixed later, at disbursal.
  - "Today" is the JVM's `LocalDate.now()`. The salary-day correction (UC-S1-66) uses IST
    (contradiction C-16).
- **Alternate & error flows:**
  - Duplicate unresolved → `AADHAAR_DUPLICATE_UNRESOLVED` "This Aadhaar number is also on file for
    another customer. Clear the 'Aadhaar duplicate' check on the Verifications tab (or reject the
    lead) before sanctioning."
  - Amount below the floor → `AMOUNT_TOO_LOW` "The sanctioned amount is below the minimum of ₹1,000".
  - Bad day → `INVALID_SALARY_DAY` "Salary credit day must be between 1 and 31".
  - Not the assignee → `CREDIT_FILE_NOT_ASSIGNED`. Wrong status → `ILLEGAL_TRANSITION`.
- **Postconditions:**
  - **LOAN_SANCTIONED** goes to the borrower on IN_APP, SMS, EMAIL and WHATSAPP.
  - The borrower can now walk the offer journey. The file appears in "Sanctioned — borrower
    completing journey".
- **Edge cases:** a sanction never expires.

### UC-S1-17 Reject lead
- Same as UC-S1-12, from the Executive's desk. A Credit Executive may reject only files assigned to
  them.

### UC-S1-18 Mark lead pending (park and ask for a document)
- **Actor / working role / permission:**
  - UI: `loan:review` only. The button is hidden for the Credit Head working role.
  - Backend: CREDIT_EXECUTIVE or CREDIT_HEAD, and ADMIN passes. An Executive must be the assignee.
- **Entry point:** the full cluster on the Sanction tab → **Mark lead pending**. An inline input
  appears ("What you're waiting on") with **Confirm** (disabled while blank) and **Cancel**. It is not
  offered on the compact row.
- **Main flow:**
  1. The Executive types the reason and confirms.
  2. `marked_pending_at` is set to now and `pending_reason` to the reason.
  3. A **same-status** `MARK_PENDING` event is appended.
  4. Toast: "Lead marked pending".
- **Business rules:**
  - This is a **tag, not a transition**. The file keeps its status and its place in the queue.
  - The borrower is **not** notified: `MARK_PENDING` deliberately maps to nothing.
  - The row's Due cell shows an amber **Pending** tag, with the tooltip "Marked pending — <reason>".
  - The Sanction tab shows "Marked pending at" and "Pending reason".
  - The cluster shows "Marked pending — <reason>".
  - Sanctioning clears the tag.
- **Alternate & error flows:**
  - The backend has **no blank-reason check** on mark-pending. Only the UI enforces it. A frontend
    comment claims the backend returns `NOTE_REQUIRED` here (contradiction C-1).
  - The backend accepts mark-pending on any status; the UI only offers it on CREDIT_EXEC_PENDING.

### UC-S1-19 Reject a sanctioned file (before the borrower accepts)
- **Actor / working role / permission:**
  - UI: `SanctionedRejectAction`, gated on `loan:review`.
  - Backend: `rejectLead` (CREDIT_EXECUTIVE or CREDIT_HEAD, and ADMIN passes).
- **Entry point:** the Sanction tab of a `SANCTIONED` file → **Reject**, which opens the credit-mode
  Reject dialog. The full customer page shows the same button inside the admin card "Force to
  disbursement (admin)".
- **Main flow:** the same as UC-S1-12: SANCTIONED → REJECTED, a 30-day block, CREDIT_REJECTED to the
  borrower.
- **Edge cases:**
  - The Credit Head working role lacks `loan:review` and sees "Not your step", even though the
    backend allows the Head.
  - On the full customer page the card renders only for `customer:manage` (Admin working role),
    which lacks `loan:review`. So that copy of the button **always** reads "Not your step"
    (contradiction C-6).

---

## 5. Disbursement Head desk

### UC-S1-20 View the disbursal queue
- **Actor / working role / permission:** Disbursement Head (`loan:disburse`), or a real ADMIN working
  as one.
- **Entry point:** `/staff/applications`, whose nav label is "Disbursal queue".
- **Screen:** queue tabs. The default tab is **Standard disbursement**.

  | Tab | Lists | Row actions | Bulk |
  |---|---|---|---|
  | **Pre-approved fast-track** | `DISBURSEMENT_PENDING` with `fastTrack = true` (returning borrowers routed straight here) | Reject | reject (disbursement mode) |
  | **Standard disbursement** | `DISBURSEMENT_PENDING`, not fast-track | Reject | reject |
  | **Failed — retry** | `DISBURSEMENT_FAILED` | Reject | reject |
  | **Past disbursed** | DISBURSED, ACTIVE, OVERDUE, DEFAULTED, CLOSED, WRITTEN_OFF | none | — |

  - The Standard ⓘ reads "Credit-approved loans awaiting release. Make the transfer, then enter its
    bank/UPI transaction id here — that releases and activates the loan straight away. There is no
    accountant step behind you."
  - The Fast-track ⓘ reads "Returning borrowers pre-approved on a clean repayment history — these
    skipped credit review and came straight to you…"
- **Business rules:**
  - **Approve is never on the row.** It needs a per-file transaction id, so the Head opens the
    dialog's **Disbursal** tab.
  - "Past disbursed" for a real Disbursement Head is `mine=true`: only files whose release event they
    drove. A real ADMIN sees every released file.

### UC-S1-21 Approve & release (record the transfer)
- **Actor / working role / permission:**
  - UI: `loan:disburse`.
  - Backend `disbursementDecision`: real role DISBURSEMENT_HEAD, and ADMIN passes.
- **Entry point:** the detail dialog → **Disbursal** tab, which shows the action cluster under the
  amounts card.
- **Preconditions:** the status is `DISBURSEMENT_PENDING`, reached by offer acceptance, the
  fast-track, the mandate override or the ADMIN force.
- **Screen:**
  - **Amount to be disbursed**: a green figure = principal − fee − GST, with the breakdown "Loan ₹X −
    fee ₹Y − GST ₹Z". If no amount has been drawn yet: "The borrower has not chosen an amount yet."
  - **Sanctioned amount**: "Credit ceiling · sanctioned <date>".
  - The action cluster:
    - a **Transaction id** input (placeholder "Transaction id");
    - **Approve & release**, disabled while the input is blank;
    - **Reject**, also disabled while the input is blank (see UC-S1-22);
    - the hint "Enter the transaction id of the transfer you made. Releasing activates the loan
      immediately."
  - **Bank details**: Beneficiary, Bank, Account no, IFSC. The disbursal account is shown, falling
    back to the salary account.
  - **Penny-drop verification** (UC-S1-44).
- **Main flow:**
  1. The Head makes the bank or UPI transfer outside the system.
  2. The Head enters the transfer id and presses Approve & release.
  3. The file moves to `DISBURSED`. The event is `VALIDATE_SUCCESS`, with notes "Txn/ref: <id>".
  4. The loan is minted, dated today, and `disbursal_txn_ref` is stored.
  5. A referral reward fires, if any. DSA commission is accrued, if any.
  6. The file moves to `ACTIVE`. The event is `ACTIVATE`, with "loanId=…".
  7. Toast: "Application #N released".
- **Business rules:**
  - There has been no accountant hop since V47/V48. The transaction id **is** the validation.
  - The txn id is checked only for being non-blank. There is no format or uniqueness check.
  - Penny-drop status is shown as a warning only. Release is **not** blocked by an unverified
    account.
  - Every newly disbursed loan is auto-assigned to a collections officer (CLAUDE.md §5,
    `CollectionAutoAssigner`).
- **Alternate & error flows:**
  - Blank txn → `TXN_REF_REQUIRED` "Enter the transaction id of the transfer to release this loan".
  - Wrong status → `ILLEGAL_TRANSITION`.
- **Postconditions:** **LOAN_DISBURSED** goes to the borrower on IN_APP, SMS, EMAIL and WHATSAPP.
  The loan's due date and grace days are stamped at disbursal.

### UC-S1-22 Reject a release
- **Actor:** Disbursement Head (`loan:disburse`). Backend: DISBURSEMENT_HEAD, and ADMIN passes.
- **Entry point:**
  - From a row or in bulk: the Reject dialog in **disbursement mode**. It is titled "Reject N
    application(s)?", reads "The borrower is notified this release was rejected." and needs a
    reason.
  - From the Disbursal tab: whatever is typed in the **Transaction id** box is sent as the reason.
- **Main flow:** `rejectWithBlock`:
  1. A `MANUAL` rejection-register row is written with a 30-day block.
  2. The file moves DISBURSEMENT_PENDING → REJECTED. The event is `DISB_REJECT`.
- **Alternate & error flows:** blank reason → `NOTE_REQUIRED`.
- **Postconditions:**
  - **DISBURSEMENT_REJECTED** goes to the borrower on IN_APP, EMAIL and WHATSAPP.
  - The 30-day block applies, but the dialog copy for this mode does not mention it
    (contradiction C-9).
- **Edge cases:** in the Disbursal tab the reason shares the txn-id input. A component comment says
  reject "doesn't require one", but the button is disabled until text is typed (C-9).

### UC-S1-23 "Failed — retry" (dead state)
- **Actor:** Disbursement Head.
- **Business rules:**
  - Nothing in the code transitions **into** `DISBURSEMENT_FAILED`, so this tab, and the Customers
    "Hold" segment, are always empty.
  - The backend `retry-disbursement` endpoint (FAILED → DISBURSEMENT_PENDING, action `RETRY`) exists
    but has **no frontend caller**.
  - The tab renders the normal Approve/Reject cluster. Approve from FAILED would be
    `ILLEGAL_TRANSITION`: FAILED may go only to DISBURSEMENT_PENDING or CANCELLED.
  - The ⓘ promises "Re-release them here once the bank issue is resolved" (contradiction C-3).
- **Postconditions (if retry were called):** `RETRY` sends LOAN_APPLIED_FAST_TRACK (IN_APP, EMAIL) to
  the Disbursement Heads, the Admins and the borrower.

### UC-S1-24 ADMIN: force a sanctioned file to Disbursement Pending
- **Actor / working role / permission:**
  - UI: `customer:manage` (Admin working role).
  - Backend: **real ADMIN only**. This is not one of the roles ADMIN merely "passes"; ADMIN is the
    only role allowed.
- **Entry point:**
  - The Sanction tab of a `SANCTIONED` file, beside Reject.
  - The full customer page → card "Force to disbursement (admin)".
- **Screen:**
  - The text "Skips the borrower's offer-acceptance step — only enabled once the agreement is signed."
  - **Force to disbursement**, disabled until an `ESIGN` check reads `PASS`. The tooltip when
    disabled reads "The borrower hasn't signed their agreement (e-sign) yet — this action is only
    available once it's signed."
  - Opening it shows the explanation, a required textarea ("Why you're forcing this (required, goes
    in the audit trail)"), **Confirm** (disabled while blank) and **Cancel**.
- **Main flow:**
  1. The ADMIN enters a reason and confirms.
  2. The file moves SANCTIONED → `DISBURSEMENT_PENDING`. The event is `ADMIN_FORCE_DISBURSE`, with
     "Forced by <name> — <reason>".
  3. Toast: "Moved to disbursement pending".
- **Alternate & error flows:**
  - Not ADMIN → `FORBIDDEN_ROLE` "Forcing an application to disbursement requires the ADMIN role".
  - Not sanctioned → `NOT_APPLICABLE` "This application isn't sanctioned".
  - Not signed → `AGREEMENT_NOT_SIGNED` "The borrower hasn't signed their agreement yet".
  - Blank reason → `NOTE_REQUIRED` "A reason is required to force this transition".
- **Edge cases:**
  - The force skips the **mandate** gate and the **reference-OTP** gate.
  - It never sets or validates the drawn amount, unlike `acceptOffer` and `mandateOverride`. A file
    forced before the borrower picked an amount reaches the desk with no drawn amount, and the
    Disbursal card says "The borrower has not chosen an amount yet."
  - `ADMIN_FORCE_DISBURSE` maps to **no notification** (contradictions C-10, C-11).

### UC-S1-25 Mandate override (bank cannot register an eMandate)
- **Actor / working role / permission:**
  - UI: `mandate:override` (Disbursement Head, Admin).
  - Backend: DISBURSEMENT_HEAD, and ADMIN passes.
- **Entry point:** the Mandate tab, on a `SANCTIONED` file whose latest attempt is `BANK_UNSUPPORTED`
  (`overridable`).
- **Screen:**
  - "This bank cannot register an online mandate, so the borrower cannot proceed by retrying.
    Overriding sends the file to disbursement without a mandate. This action is logged."
  - A required reason textarea and **Override mandate**.
- **Main flow:**
  1. The amount is validated: drawn amount, else the ceiling, between ₹1,000 and the ceiling.
  2. The file moves SANCTIONED → DISBURSEMENT_PENDING. The event is `MANDATE_OVERRIDE`, with "Bank
     unsupported; overridden by <name> — <reason>".
- **Alternate & error flows:**
  - `NOT_APPLICABLE` when the file is not sanctioned.
  - `MANDATE_OVERRIDE_NOT_ALLOWED` "A mandate override is only available when the borrower's bank
    cannot register an online mandate".
  - `AGREEMENT_NOT_SIGNED`.
  - `NOTE_REQUIRED`.
  - `AMOUNT_INVALID` "The borrower has not chosen a valid amount yet".
- **Postconditions:** **no notification** (C-10).

---

## 6. Accountant desk and the repayment panels

The Accountant works only the money coming back in (V48). Their console stacks five panels:

1. Repayments to verify
2. PayU payments needing review
3. Collection payment validation (another PRD file)
4. Awaiting repayment
5. Closed (fully repaid)

### UC-S1-26 Verify a manual repayment
- **Actor / working role / permission:**
  - UI: `loan:activate` (Accountant working role).
  - Backend: real ACCOUNTANT or ADMIN.
- **Entry point:**
  - The **Repayments to verify** panel ("<n> pending").
  - The Repayment tab's payments table (UC-S1-45).
- **Preconditions:** a payment with status `PENDING_VERIFICATION`, recorded by the borrower as
  manual UPI or bank transfer with proof.
- **Screen:**
  - Columns: S.No., **Customer** (name and "Customer #id", clicking opens the 360), Loan, **Amount**
    (with a "partial" tag), **Method** (UPI / Bank transfer / NACH auto-debit / PayU), Reference,
    **Proof** (presigned link), **Paid on**, then actions.
  - Actions: a **Customer** button, an icon-only **Verify** (✓, aria "Verify payment of ₹X on loan
    #N"), and an icon-only **Reject** (✗).
  - Amount, Method and Paid on can be sorted and filtered. The default sort is Paid on descending,
    10 rows per page. The panel polls every 8 s.
  - Footer: "Verifying confirms the transfer landed; it reduces the borrower's balance and closes the
    loan at zero. Reject if the proof doesn't match — the balance is left unchanged."
  - Empty state: "No repayments awaiting verification."
- **Main flow:**
  1. The Accountant checks the proof against the bank, then clicks Verify. There is **no confirm
     step**.
  2. The status becomes `VERIFIED`, and `decided_by` / `decided_at` are stamped (V59).
  3. The outstanding is recomputed: the penalty-aware balance as of today, minus Σ VERIFIED.
  4. At 0 the loan closes, and the application moves ACTIVE/OVERDUE → CLOSED with event `REPAID`.
  5. The post-verify prompt opens (UC-S1-28).
- **Business rules:**
  - **SoD comes from role separation, not an explicit check.** An Accountant cannot record a
    payment, so verifying always follows someone else's entry. ADMIN's one-step record-and-verify
    (UC-S1-31) is the sanctioned exception.
  - Verify is idempotent on an already-VERIFIED payment.
- **Postconditions:**
  - **REPAYMENT_VERIFIED** goes to the borrower (IN_APP, SMS, WHATSAPP).
  - On closure, **LOAN_CLOSED** goes to the borrower (IN_APP, SMS, EMAIL, WHATSAPP).
  - The awaiting-repayment panel and the ledger refresh.
- **Edge cases:** the backend `verifyPayment` flips **any** non-VERIFIED payment, REJECTED included,
  to VERIFIED. Only the UI stops a rejected payment from being verified, because the buttons render
  only on PENDING_VERIFICATION.

### UC-S1-27 Reject a manual repayment
- **Actor:** as UC-S1-26.
- **Entry point:** **Reject** (✗) → dialog **"Reject this payment?"**. It reads "₹X on loan #N ·
  <name>. The borrower's balance stays unchanged."
- **Screen:**
  - **Reason** — required select. Options: "Wrong payment reference", "Amount doesn't match", "Payment
    not received", "Proof unreadable", "Other".
  - **Note (optional)**, placeholder "Any extra detail for the borrower/record…".
  - **Reject payment** stays disabled until a reason is picked.
- **Main flow:**
  1. The status becomes `REJECTED`, with the reason code, the note and the decider stamped.
  2. No balance recompute is needed: only VERIFIED payments count.
- **Alternate & error flows:**
  - `REJECTION_REASON_REQUIRED` "A rejection reason is required".
  - `INVALID_REJECTION_REASON` when the code is outside the five allowed.
  - `PAYMENT_ALREADY_VERIFIED` "A verified payment cannot be rejected".
- **Postconditions:** **REPAYMENT_REJECTED** goes to the borrower on IN_APP, SMS, EMAIL and WHATSAPP.
  Email and in-app carry the reason; the SMS body is DLT-locked.

### UC-S1-28 After verify: keep eligible or block future loans
- **Actor:**
  - Whoever verified. The prompt appears on both verify surfaces.
  - The backend `block` requires real ACCOUNTANT, and ADMIN passes.
- **Entry point:** opens automatically after a successful verify. The dialog is titled **"Payment
  verified"**.
- **Screen:**
  - The question "Should <name> be able to take another loan?".
  - **Yes, keep eligible** is the primary button. It holds focus, so Enter keeps the customer.
  - **No — block future loans** reveals a required **Reason for blocking**:
    - max 300 characters, with a counter;
    - placeholder "e.g. Repeated bounced repayments; paid only after legal notice";
    - helper "Recorded on the customer's file; staff-only.";
    - error "A reason is required.".
  - Then **Back** and **Block customer**.
  - An already-blocked customer sees "Already blocked · <reason>" and a **Done** button instead.
- **Main flow (block):**
  1. An indefinite `BLOCKED_BY_STAFF` rejection-register row is written, keyed on the mobile.
  2. The mobile, and the PAN when present, are mirrored to the fraud blocklist with the reason
     "Blocked by staff: <reason>".
  3. A `profile_change_log` row (field `blocked`) is written.
  4. Toast: "<name> blocked from future loans".
- **Alternate & error flows:**
  - `REASON_REQUIRED` "A reason is required to block a customer".
  - `ALREADY_BLOCKED` "This customer is already blocked".
  - `NO_MOBILE_ON_FILE` "This customer has no mobile on file to key the block on".
  - Out of scope → 404.
- **Postconditions:** every new application and reborrow is refused while the block holds. The
  customer is lifted by UC-S1-69.

### UC-S1-29 Resolve a PayU review item
- **Actor / working role / permission:**
  - UI: the Accountant working role sees the panel.
  - Backend: an allowlist of real ACCOUNTANT or ADMIN. DSA and every other role are refused.
- **Entry point:** the panel **"PayU payments needing review"** ("<n> open"). It polls every 15 s and
  lists at most 200 unresolved rows.
- **Screen:**
  - The note "Refunds are made in the PayU dashboard. Marking a payment resolved only records that
    you dealt with it; it does not credit the loan or refund the borrower."
  - Columns: Customer, Loan, Amount ("+ ₹fee fee"), PayU reference (mihpayid, else "none"; bank ref
    or txnid; mode), Paid on, **What happened**, and **Mark resolved**.
  - "What happened" values:
    - LOAN_SETTLED — "Paid after the loan closed: refund needed"
    - AMOUNT_MISMATCH — "Amount differs from what we asked for: not credited"
    - NO_PROVIDER_ID — "PayU sent no payment reference: not credited"
    - OVERPAID — "Paid more than was owed: credited, check for refund"
    - FEE_MISMATCH — "Card fee differs from what we quoted: credited"
- **Main flow:**
  1. **Mark resolved** opens "Mark this payment resolved?", with a required **What was done** field
     (placeholder "e.g. Refunded in the PayU dashboard").
  2. On confirm, the resolver, the time and the note are stamped.
- **Alternate & error flows:**
  - `REVIEW_NOTE_REQUIRED` "Say what was done about this payment".
  - `ORDER_NOT_FOUND`.
  - `ORDER_NOT_IN_REVIEW` "This order is not in the review queue".
  - A second resolve is a silent no-op that keeps the first resolver.
- **Postconditions:** record-only. Nothing is credited, refunded or edited.

### UC-S1-30 Awaiting repayment and Closed panels
- **Actor:** Accountant gets both panels; Collection Head gets Awaiting repayment only.
- **Screen:**
  - **Awaiting repayment** splits ACTIVE and OVERDUE applications into two columns **by the loan's
    due date**, not by status: the application status stays ACTIVE through the overdue window.
    - **Overdue**: "Past the due date — the 2%/day late penalty is accruing (capped at 30 days;
      older loans had a 1-day grace). Put a collections executive on these."
    - **Active**: "Disbursed and still inside the due date. Interest is accruing at 1%/day; no penalty
      yet."
    - Rows carry the collections assign cluster.
    - The panel polls every 60 s, newest first.
  - **Closed (fully repaid)** is a collapsed `<details>`. It fetches nothing until opened, then polls
    every 8 s.
- **Edge cases:** the rows also mount `AdminLogPaymentButton`, which renders only for the **Admin
  working role**. That role never sees this panel, so in practice the button never appears here
  (contradiction C-12).

### UC-S1-31 ADMIN: log a payment that already happened (record + verify in one step)
- **Actor / working role / permission:**
  - UI: the Admin working role, through the Repayment tab's **Add new payment**, which is
    `customer:manage`.
  - Backend `admin-record`: real role ADMIN only.
- **Preconditions:** the loan status is ACTIVE, OVERDUE, IN_COLLECTIONS, DISBURSED or DEFAULTED.
- **Screen:** dialog "Log a payment":
  - **Date the borrower paid**
  - **Amount (₹)**
  - **Method**
  - **Transaction reference (optional)**
  - **Payment screenshot (optional)**
- **Main flow:** the payment is recorded and then verified at once, which can close the loan (as in
  UC-S1-26).
- **Alternate & error flows:**
  - `INVALID_PAID_ON` "Payment date cannot be in the future" or "Payment date cannot be before the
    loan was disbursed".
  - `INVALID_AMOUNT` "Payment amount must be positive".
  - `LOAN_SETTLED` "Loan is already settled".
- **Business rules:** this collapses maker-checker on purpose, so it is ADMIN-only.

---

## 7. Credit brief, star rating, BRE and trust signals

### UC-S1-32 Read the credit brief and the star rating
- **Actor:** any staff role except DSA. The credit-brief endpoint enforces `requireStaff` and the
  customer book.
- **Entry point:**
  - The credit badge in the dialog header and in queue rows.
  - The **Credit report** tab (UC-S1-49).
  - The credit-score gauge on the full customer page.
- **Screen:**
  - **Gauge bands:** POOR 300–550, FAIR 550–650, GOOD 650–700, VERY GOOD 700–750, EXCELLENT 750–900.
  - **Bureau state:** NOT_FETCHED (no completed pull, or a REVIEW row), NO_RECORD (pull completed, no
    record), FOUND.
  - **Brief card** (`CreditProfileCard`):
    - identity (Name, PAN, Mobile, DOB, City, PIN), always taken from the KYC profile, never from the
      report;
    - accounts (total, active, closed, defaults);
    - balances (total, secured, unsecured, with %);
    - Inquiries (30d);
    - score trend, top exposures, tradelines, enquiries;
    - the underwriter summary;
    - **Download brief (PDF)** and **CRIF report (PDF)**;
    - the complete provider response, as a field-path / value table.
- **Business rules — how the stars are computed** (`CreditRatingCalculator`):
  1. **Base from the score:** 780+ → 5.0 · 750–779 → 4.5 · 720–749 → 4.0 · 690–719 → 3.5 · 660–689 →
     3.0 · 631–659 → 2.5 · 601–630 → 2.0 · ≤600 → 1.0.
  2. **Deductions:**
     - worst DPD above 10 days in the last 12 months: −1.0;
     - an account past due now: −0.5;
     - more than 3 enquiries in 30 days: −0.5;
     - no personal loan in the 9 months up to the report: −0.5.
  3. **Caps:** any default → 2.5; any written-off or settled account → 2.0; score at or under 600 →
     1.0.
  4. Clamp to [1.0, 5.0] and round to the nearest 0.5. A fact the bureau did not send is skipped,
     never counted against the borrower.
  5. **Verdict:** ≥4.5 "STRONGLY RECOMMEND", ≥3.5 "RECOMMEND", ≥2.5 "REFER — MANUAL REVIEW", else
     "NOT RECOMMENDED".
  6. A report with **no usable score** is **unrated**. It stores no stars and no verdict, but the
     tradelines are still shown. It is never a 1-star file.
- **Business rules — handling:**
  - The stars are staff-only and **never change the price**.
  - They are stripped for a borrower reading their own profile.
  - The brief is generated best-effort. A failure never breaks onboarding.
- **Edge cases:** three different score floors are in play: 550 for the auto-reject (suspended), 600
  for the BRE and the stars, and the CRIF model floor of 510 seen in practice (contradiction C-15).

### UC-S1-33 Read the BRE (business rule engine) tab
- **Actor:** any staff role except DSA.
- **Entry point:** the 360 → **BRE** tab. Without an application: "No application yet / Business
  rules run once the customer has an application."
- **Screen:**
  - Section **"Business rule engine"**:
    - outcome counter tiles (PASS / FLAG / MANUAL / UNKNOWN);
    - a "Rules validated" progress bar ("<pass> of <n> · <k> flagged");
    - an overview table (#, Rule, Result, Finding). Clicking a rule jumps to its section.
  - One section per rule. Each shows the rule's checks, its evidence fields, and (where relevant) an
    accounts table (Ownership, Lender type, Worst DPD in the window), plus a **View source** button
    that switches to the tab the evidence came from.
  - Footer: "Flags only — these rules never reject or block a loan. … The bank statement analyser is
    not live yet, so rule 6 reads the CRIF report and rule 10 compares what is on file."
- **Rules (id. title — policy):**
  1. Credit score above 600 — "The bureau credit score must be more than 600." It also flags a bureau
     report that does not belong to this borrower (identity mismatch).
  2. PAN linked to Aadhaar — the PAN must be linked to the typed Aadhaar (Signzy PAN-KYC link status).
  3. Personal loan in the last 9 months — at least one personal loan, active or closed, in the
     window.
  4. Individual ownership, not guarantor — the loans relied on must be the borrower's own. Guarantor
     appearances elsewhere are informational.
  5. NBFC loan in the last 9 months, no DPD over 10 days.
     - Experian's 0–29 DPD bucket gives a MANUAL outcome.
     - A report that does not identify NBFC lenders gives UNKNOWN.
  6. Not self-employed, army, lawyer or MHA — checks the declared employment, the EPFO employer and
     the bureau occupation, and scans every source for profession hits.
  7. Aadhaar–PAN linkage and name.
     - Name match vs the threshold.
     - Card pictures are a MANUAL comparison: "pictures are not read automatically".
  8. UAN and company name — the UAN is verified on EPFO, and the company name must match.
  9. Aadhaar: typed vs DigiLocker vs photo — all three must agree.
  10. Salary: bank statement vs slips vs declared — must agree within 10%.
- **Business rules:**
  - Outcomes are PASS / FLAG / MANUAL / UNKNOWN. **Absent data is UNKNOWN, never FLAG.**
  - A staging build (`NEXT_PUBLIC_BRE_STAGING_PASS=true`) reports rules 1, 4, 6, 7 and 9 as PASS.
- **Edge cases:** rule 4's policy and the rating calculator both cite "rules 2 and 4" for the
  personal-loan and NBFC rules. The UI numbers those rules 3 and 5 (contradiction C-15).

### UC-S1-34 Read trust signals and the case-failure reason
- **Actor:** any staff role except DSA.
- **Screen:**
  - **Signals** column, as three small stars:
    - Credit bureau ("no DPD in last 6 months");
    - UAN / EPFO ("UAN verified");
    - Work email ("Work email verified").
    - Each star is Passed (filled), Failed, or Not checked (dashed). The tooltip gives each verdict
      and the server's "why".
  - **Failure** column (Customers register): why the file has no usable credit decision. Each reason
    has a label, a detail and a remedy. Examples:
    - "Report identity mismatch — Manual review before any decision"
    - "Date of birth missing — Add the date of birth, then re-run"
    - "Security question unanswered — Notify the borrower, or refresh the question"
- **Business rules:** advisory only. Nothing here gates a decision.

---

## 8. Verifications: overrides, retry, resume link, reminder, Aadhaar duplicate

### UC-S1-35 Read the Verifications panel
- **Actor:** any staff role except DSA, within the customer book.
- **Entry point:** the 360 → **Verifications** tab (`VerificationChecksPanel`).
- **Screen:**
  - **Header:** "Verification checks", the **Send reminder** button (UC-S1-39), and a progress line
    "<completed>/<required> done · <pct>% · <n> failed · <n> pending" with a green bar.
  - **One card per check:** the check name, a status badge, the message, and the provenance line
    (which API answered and when, or "Provider unavailable" with the endpoint, code and message).
    Below that, every `derived` fact as key/value; paise keys are shown as ₹.
  - **Synthetic cards** read "NOT RUN · Not run on this application":
    - for every retryable check with no row yet (PAN, EMAIL, ADDRESS, BUREAU, EMPLOYMENT,
      PENNY_DROP, SELFIE);
    - for ESIGN and AADHAAR, but only once the file is SANCTIONED.
  - **Derived Aadhaar–PAN linkage card:** built from the PAN row. It has no buttons; to change it,
    override or re-run PAN instead.
  - Empty state: "No verification checks recorded yet."

### UC-S1-36 Manual override of a check (PASS / FAIL)
- **Actor / working role / permission:**
  - UI: `kyc:approve`, so the Credit Executive working role only. The Credit Head working role and
    the Admin working role do **not** see the button.
  - Backend: real CREDIT_EXECUTIVE, CREDIT_HEAD or ADMIN (contradiction C-7). A real Executive must
    be the assignee: `flow.get` → `CREDIT_FILE_NOT_ASSIGNED`.
- **Entry point:** **Manual override** on a check card. The dialog shows "Manual override · <check>"
  and the current status and message.
- **Screen:**
  - **Decision:** two toggles, **Approve (PASS)** and **Reject (FAIL)**.
  - **Remarks:** "optional — recorded in the audit trail", placeholder "Why is this being
    overridden?".
  - **Confirm** stays disabled until a decision is picked.
- **Main flow:**
  1. The row is upserted with status PASS or FAIL, provider `MANUAL`, and the message "Manually
     approved|rejected by <name> — <remarks>".
  2. **Derived data is wiped**, except on PENNY_DROP, EMPLOYMENT, BUREAU and AADHAAR_DUPLICATE. Those
     keep their evidence and gain `manualOverride`, `manualBy` and `manualAt`.
  3. A same-status `VERIFICATION_OVERRIDE` event is appended, with notes "checkType=… decision=… —
     remarks".
  4. A PENNY_DROP **PASS** also accepts the disbursal account as verified.
  5. Toast: "<Check> overridden to PASS|FAIL".
- **Business rules:**
  - The override moves a **check**, never the application status.
  - It is not counted as a "decision" for performance or customer-book visibility.
- **Alternate & error flows:** `UNKNOWN_CHECK` "Unknown verification check: X"; `FORBIDDEN_ROLE`
  "Manual verification override requires CREDIT_EXECUTIVE or CREDIT_HEAD".
- **Edge cases:** the override event does **not** record `acting_role`, unlike every `logEvent` row
  (contradiction C-13).

### UC-S1-37 Retry a provider API (ADMIN)
- **Actor / working role / permission:**
  - UI: `verification:retry`, the Admin working role.
  - Backend: real ADMIN only.
- **Entry point:**
  - **Retry API** on a retryable card (Verifications tab).
  - **Run** or **Re-run** on a Third-party logs row (UC-S1-47).
- **Screen:** the retry dialog collects only what cannot be recovered from the file:

  | Check | Fields |
  |---|---|
  | PAN | PAN (blank = the saved PAN) |
  | EMAIL | Email (blank = the saved email: official, else personal) |
  | ADDRESS | Latitude and Longitude, both required |
  | BUREAU | Consent OTP, required. Help text: "A fresh OTP is required by Digitap. Do not reuse a previous code." |
  | EMPLOYMENT | none |
  | PENNY_DROP | Account number and IFSC (blank = the saved salary account) |
  | SELFIE | Selfie storage key, required |

  **Retry** stays disabled while a required field is empty, or while an earlier retry's outcome is
  still unknown ("still running" note with a Refresh).
- **Main flow:**
  1. The existing row is set to PENDING with "Retry requested by administrator".
  2. The provider is called. BUREAU is forced, bypassing the 24-hour reuse window. EMPLOYMENT is
     forced too.
- **Alternate & error flows:**
  - `RETRY_NOT_SUPPORTED` "This verification requires a borrower session and cannot be retried here"
    — ESIGN, AADHAAR, DIGILOCKER and the others.
  - `RETRY_INPUT_REQUIRED` "<key> is required to retry this check".
  - `RETRY_INPUT_INVALID` "<key> must be numeric".
- **Edge cases:** every retry is a **billable** provider call. The client stops waiting at 120 s
  without aborting, so Retry stays locked for that check until its row is rewritten.

### UC-S1-38 Send the customer a resume link
- **Actor / working role / permission:**
  - UI: `kyc:approve` on the Verifications tab. On the Third-party logs tab the same action is gated
    on `verification:retry` (contradiction C-8).
  - Backend: real CREDIT_EXECUTIVE, CREDIT_HEAD or ADMIN.
- **Preconditions:**
  - For ESIGN, AADHAAR, DIGILOCKER, SELFIE or ADDRESS: the application is **SANCTIONED**, and the
    card is not PASS. ESIGN that already passed is refused.
  - For BUREAU: the application is **KYC_PENDING** and the bureau holds a security question
    (`bureauChallenge = true`).
- **Entry point:** **Send link to customer** on an eligible card → dialog **"Send link to customer"**.
- **Screen:**
  - "The customer will be sent to <step>". The step is one of: "Sanction letter & e-sign",
    "DigiLocker (Aadhaar)", "Selfie", "Address", "Credit bureau question".
  - When the step will reopen: "This reopens the <step> step — their earlier attempt is set aside and
    they redo it."
  - The masked email and mobile, "Last sent <time>", and the read-only link URL.
  - Buttons: **Close**, **Email link** (disabled without an email: "No email on file for this
    customer"), **Copy link**, and **SMS**. SMS is always disabled: "SMS can't carry a per-customer
    link until a DLT template is approved".
- **Main flow:**
  1. On the first send, the check row is **reopened** (status PENDING, "Reopened for the customer to
     redo"), the journey pointer is rewound to that step, and a `STEP_REOPENED` event is appended.
  2. **Email** publishes `VERIFICATION_STEP_LINK` (IN_APP, EMAIL) to the borrower. For BUREAU it uses
     the once-per-application bureau nudge instead.
  3. **Copy** writes the link to the clipboard and records the send.
  4. Both channels append `STEP_LINK_SENT` "<check> via <channel>".
  5. Toasts: "Link queued for email to <masked>" or "Link copied and logged."
- **Alternate & error flows:**
  - `STEP_LINK_NOT_ELIGIBLE`: "Only a sanctioned application can resend an offer-journey link", "Only
    an application still awaiting review can be sent a bureau link", "This application has no bureau
    check to chase", "This application is not waiting on a bureau security question".
  - `STEP_LINK_UNSUPPORTED`.
  - `STEP_ALREADY_DONE` "eSign is already complete".
  - `BUREAU_CHALLENGE_ALREADY_NOTIFIED` "This customer has already been emailed about the bureau
    question".
- **Business rules:** there is no magic link. The borrower still logs in through
  `/login?next=<step>`. A second send while the reopen is still open does not reopen again.

### UC-S1-39 Send the borrower a verification reminder
- **Actor / working role / permission:**
  - UI: `kyc:approve`.
  - Backend: real CREDIT_EXECUTIVE, CREDIT_HEAD, ADMIN **or TELECALLER**. The UI hides the button
    from the Telecaller and the Credit Head working roles (C-7).
- **Entry point:** **Send reminder** in the Verifications header → confirm **"Send the borrower a
  reminder?"**: "The borrower is sent their pending verification steps by in-app notification, SMS,
  email and WhatsApp. There is no cooldown — every confirm sends again."
- **Main flow:**
  1. The backend lists the required checks that are not PASS and not REVIEW.
  2. If any remain, it publishes **KYC_REMINDER** (IN_APP, SMS, EMAIL, WHATSAPP) and the toast says
     "Reminder queued".
  3. If none remain: "Nothing pending — no reminder sent".
- **Edge cases:** the button then reads "Reminded" or "Nothing pending" until the panel remounts.
  There is no cooldown.

### UC-S1-40 Review an Aadhaar duplicate
- **Actor:** credit team (override); any staff role except DSA (view).
- **Trigger:**
  - Intake writes a silent `AADHAAR_DUPLICATE` **REVIEW** row when another customer holds the same
    typed Aadhaar. Its message reads "Aadhaar number is also on file for customer #X — confirm it is
    the same person before sanction", and it carries `otherCustomerIds`.
  - The row is deleted if the number stops colliding while it is still editable.
  - The borrower is never told.
- **Entry point:** the 360 → **Dedupe** tab → section "Aadhaar duplicate". The Verifications tab shows
  the same row.
- **Screen:**
  - A badge: Clear / REVIEW / FAIL / PASS.
  - The message, and "Other customers: #id" links to `/staff/customers/<id>`.
  - "Clear it with the manual override on the Verifications tab."
  - An **Override** button (shown for `kyc:approve`) that switches to the Verifications tab.
- **Business rules:**
  - Any status other than PASS **blocks sanction** (`AADHAAR_DUPLICATE_UNRESOLVED`). That includes an
    untouched REVIEW and a FAIL ("not the same person").
  - The way out is an override to PASS (UC-S1-36), or rejecting the lead.
  - At `submit-kyc`, a number already on another customer's **submitted** file auto-rejects
    permanently (`AADHAAR_DUPLICATE`). That happens before staff ever see the file.

---

## 9. Customer 360 — the pop-up, the full page and all 21 tabs

### UC-S1-41 Open the Customer 360 (pop-up vs full page)
- **Actor:** every role except DSA, within the customer book (out of scope → 404).
- **Entry point:**
  - **Pop-up:** the unified dialog (UC-S1-07), opened on an application.
  - **Full page:** `/staff/customers/<id>`, through "Full customer page ↗" or the register.
- **Screen (full page):**
  - "← All customers", the title (full name), and the subtitle "Customer #N · borrower history ·
    owner <name> | Unallocated".
  - **Refresh**; the ADMIN-only export menu (CSV/PDF); **Send message** (`message:send`).
  - The tabs, on the newest application.
  - A right rail: the **Credit score** gauge, plus the ADMIN cards (§10), shown only for
    `customer:manage`.
  - An older application opened from the Loan tab opens the application dialog on top, on its
    Customer tab.
- **Tabs (lifecycle order, three groups):**
  1. Customer, Loan, Disbursal, Repayment, Collections
  2. Third-party logs, Banking, Credit report, BRE, AI Analyzer
  3. Journey, References, Documents, Addresses, Dedupe, Communication, Activity
  4. Sanction, Follow-ups, Mandate, Verifications
  - Every tab body shows the "Needs manual review" badge when it applies.
  - Repayment and Disbursal also show the bureau-mobiles strip.

### UC-S1-42 Customer tab
- **Screen:**
  - **Basic information:** Customer no, Full name, Gender (from PAN), Date of birth, Employment
    type, Risk category (A–D, staff-only), Credit score (badge). When blocked: "Future loans —
    Blocked · <reason>".
  - **Contact information:** Personal email and Official email, each with "OTP ✓" when confirmed;
    Mobile; Emergency contact. A pencil **Edit** button (`customer:manage`) opens UC-S1-63.
  - **Identity & address:** PAN, **Aadhaar in full** (a product decision, V75), Aadhaar linked, Full
    address, City/State/Country/PIN (from DigiLocker, else PAN).
  - **Employment:**
    - a pill: "Employer mismatch", or "UAN matched";
    - Company; Salary day ("Day N"); Last salary received; Net monthly income; Annual salary;
      Salary %; Increment %; UAN;
    - **Eligible limit**: the ADMIN override wins, and a basis badge says which rule applied.
  - **Compliance:** Terms version, Terms accepted, PEP declared.
  - **Owner** (UC-S1-77), then "Open full customer page".

### UC-S1-43 Loan tab
- **Screen:**
  - With no loan yet: "No loan on this application yet — it is at <status>." and a **Go to
    Sanction** button.
  - **Loan pills**, shown only with 2 or more loans. Each pill reads "Loan #id · date · ₹principal"
    and a behaviour badge:
    - early (closed 3 or more days before due);
    - on time (within grace);
    - late (up to 7 days);
    - very late (more than 7 days, **or settled**);
    - open; overdue.
  - **Current loan card:**
    - the status badge, **Sanction letter** and **Signed agreement** document buttons, and a collapse
      toggle;
    - a **Financial summary** strip: Amount due today, Interest accrued (with day count), Late
      penalty (with days × 2%), and the 1%/day rate. Note: "Penalty is 2%/day of principal, starts
      the day after the due date (after the one grace day on older loans) and is capped at 30 days."
  - **Loan facts:**
    - Loan number, Customer no, Max eligible, Requested amount;
    - Sanction details: net disbursed, sanctioned on, fee 10%, GST 18%, contracted repayable,
      repayment date (+Nd when overdue), tenure, interest at term, sanctioned amount and by, total
      deductions, purpose, salary day, decided at, remarks, risk;
    - Disbursal details: disbursed on, Txn ref, payable account, "Manual bank transfer", released by,
      written off, disbursed ✓.
  - **Applications (n):** each row shows "#id · status · purpose", "Assigned to <exec> · in stage
    since <time>", and the amount. The `#id` of any *other* application opens it stacked on top.
    **Cancel** appears for `customer:manage` (UC-S1-73).
  - **Business with this customer:**
    - Lending: loans taken (repaid / running), total lent, currently disbursed, currently due.
    - Profit: processing fees (excl. GST), interest received, penalty received, total profit.
    - "Profit on money lent" %, and "Net cash so far" (received − sent out, "still recovering" when
      negative).
    - Settlement waivers.

### UC-S1-44 Disbursal tab
- **Screen:**
  - The amounts card and the action cluster (UC-S1-21/22), shown on DISBURSEMENT_PENDING and FAILED.
  - **Bank details:** Beneficiary, Bank, Account no, IFSC. Account type and Branch are blank: the
    data is never captured.
  - **Penny-drop verification:**
    - a pill: SUCCESS / FAILED / PENDING / Not run;
    - Account exists, Beneficiary at bank, Name match score (%), Provider, Verified at, Bank RRN,
      Provider name match, Reason;
    - "Manually overridden by <name> on <time>".
  - **Warnings:**
    - "Awaiting cancelled cheque / passbook — verify the proof against the account above before
      releasing."
    - "Never penny-dropped — the borrower kept the salary account they typed at intake. Check the
      number against their payslips before releasing funds."
    - "The penny-drop check has not passed on this account. Confirm it before releasing funds."
  - **Disbursal records:** Loan, Txn ref, Disbursed on, Net disbursed.
- **Business rules:** the account counts as verified when any one of these holds: disbursal account
  verified, profile penny-drop verified, or the PENNY_DROP row is PASS.

### UC-S1-45 Repayment tab
- **Screen:**
  - A **Repayment date** banner (red when overdue): "On the borrower's salary day · N days overdue |
    due today | in N days".
  - The loan selector, the loan card and the summary.
  - **How it adds up:** Payable at term / Due today / Received (verified) / Outstanding, by amount
    type. It also shows days since disbursal, days to due, the grace day if any, and "Pay early &
    save".
  - Loan facts.
  - **Payments (n):**
    - columns: Amount (coloured by status), Mode, Txn id, Proof, Paid on, Status (with "Partial" and
      the rejection reason + note);
    - **Verify** and **Reject** on PENDING_VERIFICATION rows, for `loan:activate` (UC-S1-26/27/28).
  - **Add new payment** (collapsible): `customer:manage`, open loans only (UC-S1-31).
  - **Collection & profitability:** DPD + bucket, penalty days used "/ 30", penalty headroom (₹ for
    the remaining days × 2%), days overdue or to due, last verified payment, collected vs repayable %,
    recovered vs disbursed, settlement, promise to pay.

### UC-S1-46 Collections tab
- **Actor:**
  - The case actions need `collections:manage` (Collection Head) or `collections:interact`
    (Collection Executive, own cases only).
  - Every other role sees the tab read-only.
- **Screen:**
  - DPD + bucket badge, Assigned officer, Case status, Promise to pay (red "overdue" when past), Last
    interaction, Loan cycle ("2nd advance").
  - States: "This case is assigned to another officer." (the executive's `CASE_NOT_ASSIGNED`), or
    "No collection case is open on this loan." with an **Open case** button for `collections:manage`.
  - **Interactions:**
    - Type: CALL / SMS / EMAIL / VISIT.
    - Outcome: CONNECTED / NO_ANSWER / PROMISE_TO_PAY / PAID / DISPUTED.
    - A Promise-to-pay date appears for PROMISE_TO_PAY; without one: "No promise-to-pay date — it
      will be logged without one."
    - A Proof ref appears for PAID ("UTR / receipt").
  - **Assign officer** (Head): "Officer (active executives)".
  - **Propose settlement** (Executive): "Settlement amount (₹)", with "A Collection Head approves it
    on the Settlements page (separation of duties)."
  - **Payments:** case payments, and record a field payment (interact).
  - Calls about this loan, and other customer calls.
- **Business rules:** detailed in the collections PRD file. Settlement and field-payment approvals
  are SoD-checked there (`SOD_VIOLATION`).

### UC-S1-47 Third-party logs tab
- **Screen:**
  - One collapsible row per source: PAN Detail, Aadhaar–PAN Linkage, UAN / EPFO Detail, Aadhaar
    Detail (DigiLocker), Email Verification, Bank Statement Analysis, Bureau and Credit Report
    (gauge + brief), Penny Drop, Selfie / Liveness, Address Check, eSign, Skip Trace.
  - Each row shows a status pill, the provider and the checked time.
  - The body shows the facts and "View raw response". `url`, `token`, `videoUrl` and `sessionId` are
    shown as "[hidden]".
- **Actions** (`verification:retry`, Admin working role):
  - **Run / Re-run** opens the retry dialog (UC-S1-37).
    - Bank Statement Analysis re-pulls the **bureau**: "Re-pulls the bureau report this analysis is
      read from".
    - Aadhaar–PAN re-runs PAN.
    - Non-runnable rows show a disabled Run: "This check cannot be run by staff."
  - **Send link** on DigiLocker and eSign. Disabled with "Already completed by the borrower", or
    "Available once the file is sanctioned (the borrower's offer journey)".
  - **Skip Trace → Run**, behind a confirm: "Looks up alternate mobiles, emails and addresses from
    Digitap for this customer. Each run is a paid lookup." Toasts: "Skip trace complete." / "Digitap
    has no record for this customer." / "Skip trace recorded."
- **Business rules:** CLAUDE.md lists Skip Tracer as Collection Head or ADMIN. Here the UI exposes it
  to the Admin working role only.

### UC-S1-48 Banking tab
- **Screen:**
  - **Salary bank:** Bank, Account, IFSC, Account mobile, Penny drop, Source "KYC profile".
  - **Bank statement analysis:**
    - status pill; **⬇ Download Excel** and **⬇ JSON** when available;
    - report id, run by, started;
    - per account: masked number, type, status, IFSC, MICR, facility, branch, period, transactions,
      document authenticity, opening and closing balance.
  - **Salary identification:** primary source, consecutive months, minimum salary, unique credits,
    employer on statement, declared salary and day, analysed at. Shows "Declared only" when not
    analysed.
- **Actions:** **Run analysis / Re-run analysis** for `kyc:approve` or `loan:approve`, behind a
  confirm: "Signzy charges for every statement analysed. This sends every bank statement on file for
  this customer." It is disabled while a run is PENDING.
- **Edge cases:** CLAUDE.md warns that the BSA read-back is 403 on our account — "don't press Run".

### UC-S1-49 Credit report tab
- **Screen:**
  - The gauge (score, stars, recommendation, bureau state) and the credit brief card (UC-S1-32).
  - **Underwriting & brief metadata:** Risk category, Bureau, Credit brief generated.
  - **Bureau pull (raw verification diagnostics):** Source, No record, Active accounts, Overdue /
    defaults, Total balance. These come from the BUREAU row, an earlier snapshot than the parsed
    facts.

### UC-S1-50 BRE tab
- See UC-S1-33.

### UC-S1-51 AI Analyzer tab
- **Screen:**
  - A rule-based, **advisory** comparison against comparable past loans: a result card and band.
  - A "Declared income not corroborated" warning: "Salary of ₹50,000 or more, no EPFO confirmation of
    the declared employer, and a thin credit record…", with the cohort's bad rate and zero-paid count.
  - Sections: Summary; Signs (or "Signs measured on first loans"); Own record; "Checked but not
    relied on" (Sign / This customer / With / Without); "What to do"; Portfolio gist; loan groups;
    concerns per first loan.
  - With nothing to compare: "Nothing to compare — This customer has no loan and no application…".
- **Business rules:** it never blocks a loan.

### UC-S1-52 Journey tab
- **Screen:**
  - A **Fast-track** badge when it applies, the current assignee, and the stage stepper. Clicking a
    stage opens its detail dialog, with prev/next navigation.
  - Event filter chips with counts, and the event timeline: who, as which role, when, and the notes.
  - Empty states: "No events recorded yet." / "No <kind> events for this application."

### UC-S1-53 References tab
- **Screen:** two reference cards (slot 1–2): Name, Contact (mobile), Relation.
- **Actions** (`customer:manage`, Admin working role):
  - **Add reference**, shown while fewer than 2 exist.
  - A pencil **Edit** on each reference.
  - Both open an edit grid (Full name, Mobile (10 digits max), Relation select) with **Save** and
    **Cancel**. Toast: "References saved".
- **Empty state:** "No references on file yet."

### UC-S1-54 Documents tab
- **Screen:**
  - **Customer documents**, grouped by type. Customer-level types are AADHAAR, AADHAAR_PHOTO,
    SELFIE, PAN, ADDRESS, SALARY, SALARY_SLIP, BANK_STATEMENT and the four V75 card sides.
  - **Application documents:** one collapsible per application ("Application #N · status · count").
    The newest starts open.
  - Each card shows a stable number, type, upload time, "Application #N", the **file password** if
    any, and **View**.
- **Actions:**
  - **Upload / replace** (`document:upload`): a category text input (forced to upper case,
    placeholder "Category e.g. PAN, PAYSLIP"), a file picker and **Upload**.
  - **Delete** on a card: ADMIN (`customer:manage`) only.
- **Business rules:**
  - Upload is blocked while a document of the same category exists: "A "<CAT>" document already
    exists — delete it above before uploading a replacement." A non-admin is told instead "…ask an
    administrator to delete it before a replacement can be uploaded."
  - Roles without upload see "Your role cannot upload or replace documents."

### UC-S1-55 Addresses tab
- **Screen:**
  - Declared (profile address).
  - Aadhaar (DigiLocker): address, line, landmark, city, district, state, PIN, country.
  - Address check: provider, result, resolved address. Otherwise "Address check not run".
  - Found by skip trace. Otherwise "No skip trace has been run" / "Run a lookup from Third-party logs
    → Skip trace".

### UC-S1-56 Dedupe tab
- **Screen:**
  - **Mobile matches:** other customers on this mobile, which open stacked.
  - **Aadhaar duplicate** (UC-S1-40).
  - **Blocklist hits:** Type, masked Value, Reason, Added on. Otherwise "No blocklist match".
  - **Rejection register:** "Blocked until <date> · <reason>". Otherwise "No active cooling-off block
    on this mobile."

### UC-S1-57 Communication tab (call logs and remarks)
- **Actor:** any staff role in the book. The backend `requireVisible` applies, and DSA is refused.
- **Screen — Log call form:**
  - **Call type:** Outbound / Inbound / Missed.
  - **Outcome:** Connected / No answer / Callback / Refused / Wrong number.
  - **Relates to loan:** "— not loan-specific —" or "#id · disbursed <date>". It is pre-selected when
    the customer has exactly one loan.
  - **Callback on**: a date, shown only for Callback.
  - Notes ("Call notes…") and **Log call**.
- **Screen — lists:** the call-log list, then **Remarks**: a textarea ("Add a remark about this
  customer…") and **Add remark**, disabled while blank, then the remark list (author and time).
- **Business rules:**
  - Call type and outcome are required (`@NotBlank`).
  - A tagged loan must belong to the customer: `LOAN_NOT_FOUND` "No such loan: N" or
    `LOAN_CUSTOMER_MISMATCH` "That loan does not belong to this customer".
  - The callback date is sent only for Callback.
  - Remarks are free text, and the author is captured by JPA auditing.
- **Postconditions:** both appear in the Activity tab and in Follow-ups (when a callback is set). Toast:
  "Call logged".

### UC-S1-58 Activity tab
- **Screen:**
  - The unified timeline, newest first. It merges lifecycle and re-verify events, profile edits
    (change log), uploads (metadata), remarks and calls.
  - Type filter chips with counts: LIFECYCLE / PROFILE / REVERIFY / VERIFICATION / REFERENCE / UPLOAD /
    REMARK / CALL.
  - Entries are grouped per application ("#id · ₹amount · status · stage · Assigned to … · in stage
    since …"), plus "Not tied to an application".
  - Each entry shows its title, "app #id", detail, "by <actor>" and the time.
  - Empty state: "No activity recorded yet."

### UC-S1-59 Sanction tab
- **Screen:**
  - The stage action at the top: assignment, decision, the sanctioned Reject and the ADMIN force.
  - **Decision:**
    - a pill: Rejected / Sanctioned / Pending;
    - sanctioned amount, approved repayment date (on salary day N), decided by and at,
      recommendation, remarks, marked-pending info;
    - when rejected: rejected at, by, reason (the event notes).
  - **e-Sign:**
    - a pill: COMPLETED / Awaiting signature / Not started;
    - Provider, Contract id, Last checked, Signed at, Signature ref, Capture method ("Aadhaar eSign" or
      "Drawn signature"), Identity match.
  - **Sanction letter:**
    - "Key Fact Statement / sanction letter", or "The sanction letter has not been generated yet.";
    - "Borrower-signed copy", or "Borrower has not signed yet — they are at the eSign step.";
    - **Borrower acceptance**: accepted amount (within the ceiling), date, time, "Consent / T&C ✓ Yes".

### UC-S1-60 Follow-ups tab
- **Screen:**
  - Telecalling status (call status and lead outcome badges, "Added by"). Only `leads:manage` roles
    can read it; others see "Telecalling status is visible to telecallers".
  - **Schedule follow-up** opens the Log-call form with outcome Callback and the date focused.
  - Upcoming (callback date from today), and Past follow-ups. Columns: Callback on, Call type,
    Outcome, Notes, Logged by, Logged at.
  - Empty state: "No follow-up scheduled for this customer."

### UC-S1-61 Mandate tab (summary)
- **Screen:**
  - The mandate summary (Bank, Account ••••last4, Authorised, Attempt), attempt history, and debit
    attempts with settlement.
  - When there is no mandate: "No mandate started."
- **Actions by permission:**
  - **Invite**: `mandate:debit` or `mandate:override`.
  - **Manual debit**: `mandate:debit`, only when the `digio-debit` flag is explicitly true.
  - **Cancel attempt / cancel ACTIVE mandate**: `mandate:override` or `collections:manage`. Both need
    a confirm, and the active cancel needs a note.
  - **Change mandate account**: same roles. Before payout (SANCTIONED or DISBURSEMENT_PENDING) it
    needs `mandate:override`, because the account is also the payee. It penny-drops first, then
    withdraws the old mandates, then re-invites.
  - **Mandate limit for new registrations**: ADMIN (`customer:manage`) only.
  - **Override mandate**: UC-S1-25.
- **Business rules:** full detail is in the collections/mandates PRD file. Every mandate is real and
  billable.

### UC-S1-62 Verifications tab
- See UC-S1-35 … 40.

---

## 10. ADMIN corrections

Every correction below shares four rules:

- It is **real-role ADMIN only** on the backend (`requireAdmin`; Block is the one exception).
- In the UI it is gated on `customer:manage`, so it appears only in the Admin working role, as cards
  in the full customer page's right rail.
- Each change is audited to `profile_change_log` (field, previous → new, who, when).
- The audit shows in the Activity tab as PROFILE entries.

### UC-S1-63 Edit KYC / salary details
- **Entry point:**
  - The pencil on the Customer tab or a register row → dialog "Edit customer details".
  - The rail card "Edit KYC / salary (admin)" (no DOB field).
- **Screen:**
  - "PAN and Aadhaar are locked. Mobile is corrected from the customer page, with OTP verification.
    Salary edits are audited and recompute the eligible limit."
  - Fields: Full name; **Date of birth** (a date between today and 100 years ago; helper "Required by
    the bureau before a credit check can run."); Employer; Employment status; Monthly salary (₹);
    Annual salary (₹); Salary percentage (%); Increment percentage (%); Salary bank; Address.
  - **Save changes**. Toast: "Customer details saved".
- **Business rules:**
  - The update is a **full replace** of the latest profile. A blank field is saved as empty, so the
    dialog always sends every field, prefilled. DOB alone is patch-only: null leaves it unchanged.
  - DOB is validated: `INVALID_DOB` "Date of birth must be in the past", "The borrower must be at
    least 18 years old", "Date of birth is not plausible".
  - A monthly-salary change recomputes the eligible limit on not-yet-disbursed applications. An ADMIN
    limit override still wins.
  - PAN, Aadhaar and mobile are never touched here.
- **Edge cases:** the dialog shows a skeleton, never an empty form, until the profile loads, so an
  early save cannot erase data.

### UC-S1-64 Correct the mobile number (OTP to the new number)
- **Entry point:** card "Correct mobile number (admin)": "Current: <mobile>. Sends an OTP to the new
  number before saving it — does not change the borrower's login number."
- **Main flow:**
  1. The ADMIN enters a **New mobile number** (10 digits) and presses **Send OTP**. The OTP goes to
     the **new** number (purpose `ADMIN_MOBILE_CHANGE`).
  2. The ADMIN enters the 6-digit **OTP** and presses **Confirm**. The profile mobile is updated and
     audited. Toast: "Mobile number updated".
  3. **Change number** returns to step 1.
- **Alternate & error flows:**
  - `INVALID_MOBILE` "Enter a valid 10-digit mobile number" (must match `[6-9]\d{9}`).
  - `DUPLICATE_MOBILE` "This mobile number is already registered with another customer."
  - `MOBILE_IDENTITY_COLLISION` "This mobile number's login identity is already claimed by another
    customer." (the login identity keeps only the last 7 digits).
  - `INVALID_OTP` "Invalid or expired code".
- **Edge cases:** login identity is unchanged. Forgot-password lookups and the session display name
  **do** read this column.

### UC-S1-65 Correct the approved (sanctioned) amount
- **Preconditions:** the customer has an application in `SANCTIONED` with no loan yet. The card shows
  only then.
- **Screen:** card "Correct approved amount (admin)": "Currently approved: ₹X. Saves immediately and
  emails the customer the revised approved amount. Only available before disbursement." It shows the
  eligible limit (with basis badge) as context, a **New approved amount (₹)** field and **Save**.
- **Business rules:**
  - The only checks are the ₹1,000 floor, and that the amount is not below what the borrower already
    chose to draw. There is **no upper cap**.
  - The status does not change.
- **Alternate & error flows:**
  - `NO_SANCTIONED_APPLICATION` "This application is not sanctioned and awaiting disbursement".
  - `AMOUNT_TOO_LOW`.
  - `BELOW_DRAWN_AMOUNT` "The borrower already chose to draw <₹> — the sanctioned amount cannot be
    corrected below that".
- **Postconditions:** **SANCTIONED_AMOUNT_REVISED** goes to the borrower (IN_APP, EMAIL, WHATSAPP)
  after commit. Toast: "Approved amount updated".

### UC-S1-66 Correct the salary credit day
- **Screen:**
  - Card "Correct salary credit day (admin)": "Currently on file: N. Carried over to a reborrow and
    read by disbursal to set the loan's due date."
  - A select from 1 to 31. Helper: "Projected next due date: <date>."
  - When the projection is beyond 40 days, a warning: "That due date is N days after a disbursal today
    — beyond the 40-day limit. Check the salary day before saving."
  - "Also moves the pending offer's repayment date." Active loans keep their existing due date.
  - **Save**, disabled while the day is unchanged.
- **Business rules:**
  - The change applies to the **latest** application.
  - If that application is SANCTIONED with no loan, `approvedRepaymentDate` and the tenure are
    recomputed from today (IST) and audited.
  - A DISBURSEMENT_PENDING file (KFS already signed) or a disbursed loan keeps its date.
- **Alternate & error flows:** `INVALID_SALARY_DAY`; `NO_APPLICATION` "This customer has no
  application".

### UC-S1-67 Set or reset the maximum loan amount (eligible-limit override)
- **Screen:** card "Maximum loan amount (admin)":
  - "Current limit: ₹X" with a basis badge; "The salary rule alone would give ₹Y."; "A limit set here
    sticks — it survives a salary re-check and carries into future re-borrows, raising what the
    borrower can actually draw. They are emailed when it increases."
  - **Maximum amount (₹)** and **Save**. Toast: "Maximum loan amount saved".
  - **Reset to 25% of salary**, shown when an override exists. Toast: "Limit reset to 25% of salary".
- **Business rules:**
  - The override is stored **per customer** (V69). The floor is ₹1,000 (`AMOUNT_TOO_LOW` "The limit is
    below the minimum of ₹1,000"). There is **no ceiling**: it may exceed ₹10,00,000.
  - It is applied at once to every live application.
- **Postconditions:** **LOAN_LIMIT_REVISED** (IN_APP, EMAIL, WHATSAPP) **only when the limit went up**.
  The component docstring says the borrower is not notified (contradiction C-14).

### UC-S1-68 Block a customer for future loans
- See UC-S1-28.
- **Actor:** the backend allows real ACCOUNTANT or ADMIN. The only UI entry is the post-verify prompt.
  There is no standalone Block card on the customer page.

### UC-S1-69 Unblock a customer
- **Preconditions:** the customer has a live `BLOCKED_BY_STAFF` row. The card shows only when
  `blocked`.
- **Screen:** card "Blocked for future loans": "Reason on file: <r>. The borrower cannot start a new
  application or reborrow while this holds. Unblocking also lifts the mirrored blocklist entries this
  block added." Button: **Unblock customer**.
- **Main flow:**
  1. Every live staff block on the mobile is ended now. The rows stay as history.
  2. The mirrored PHONE and PAN blocklist entries that carry this block's reason are deactivated.
  3. The change is audited.
  4. Toast: "Customer unblocked — they may apply again".
- **Business rules:**
  - A credit-reject **cooling-off is not touched**.
  - The `blocked` flag on the card and the register means *any* live rejection block on the mobile
    (SQL `blocked_until > now()`). So the card can appear for a customer who only has a 30-day
    cooling-off, and Unblock then fails with `NOT_BLOCKED` "This customer is not blocked by staff".

### UC-S1-70 Allow re-application (one-use reapply allowance)
- **Preconditions:** the customer is **disqualified by repayment history**: repaid more than 5 days
  late, never fully repaid, or a DEFAULTED/WRITTEN_OFF loan. They also hold no unused allowance. The
  card renders nothing otherwise.
- **Screen:**
  - Card "Allow re-application": "This customer cannot re-apply because of their repayment history.
    Allowing it once sends their next application to the normal credit queue; it is not
    pre-approved."
  - A required **Reason** ("Reason (required, kept on the audit trail)").
  - A **Supporting document**: pick an existing one ("<type> — <file> (application #N)"), or **Or
    upload a new one**, which is uploaded as `REAPPLY_SUPPORT` on the latest application.
  - **Allow re-application**, disabled until there is a reason and a document. Toast:
    "Re-application allowed".
- **Alternate & error flows:**
  - `REASON_REQUIRED`.
  - `DOCUMENT_REQUIRED`.
  - `INVALID_DOCUMENT` "The document does not exist for this customer".
  - `NOT_DISQUALIFIED`.
  - `ALLOWANCE_EXISTS`.
  - `FORBIDDEN_ROLE` "Only an admin can allow re-application".
- **Postconditions:**
  - A change-log row is written (field `reapplyAllowance`).
  - The next `reborrow` consumes the allowance. The new file goes to credit review instead of the
    `PAST_DELINQUENCY` auto-reject.

### UC-S1-71 Add to the fraud blocklist
- **Screen:** card "Add to blocklist (admin)": "Flag a PAN / phone / device against fraud. Enter the
  full value to block."
  - **Type**: PAN, AADHAAR_REF, PHONE, DEVICE, BANK_ACCOUNT.
  - **Value**.
  - **Reason** (optional; defaults to "Flagged from customer #N").
  - **Add to blocklist**. Toast: "Added to blocklist".
- **Business rules:** the blocklist feeds dedupe on later signups. It is separate from the rejection
  register.

### UC-S1-72 Delete a customer permanently
- **Screen:**
  - Card "Danger zone": "Permanently delete this customer and all of their data — applications, loans,
    payments, verifications, documents and collections. This cannot be undone."
  - With a live loan: "⚠ This customer has a live loan — deleting also erases that active loan
    record."
  - **Delete this user** arms the card. The ADMIN must "Type <full name> to confirm", which enables
    **Permanently delete**.
- **Main flow:**
  1. One transaction runs a hand-written cascade, children first. It covers:
     - settlements, collection cases, payments;
     - application documents, verifications, events, profiles;
     - loans, applications;
     - the change log, remarks, call logs, owner, limit and mandate-ceiling overrides;
     - borrower mobile, preferences and credentials;
     - referrals and payouts and codes, income profile, risk assessment;
     - borrower notifications and deliveries, reset tokens.
  2. The ADMIN is redirected to `/staff/customers`, with the toast "Customer deleted".
- **Alternate & error flows:** nothing deleted → 404.
- **Edge cases:** the cascade list does **not** include several customer-keyed tables, for example
  the `application_rejection` register, `reapply_allowance`, mandate and PayU order tables, and skip
  trace. Rows there are not deleted by this call; what happens downstream to them is unverified.

### UC-S1-73 Cancel an application (ADMIN)
- **Entry point:** Loan tab → Applications list → **Cancel** (`customer:manage`). It shows on
  statuses DRAFT, KYC_PENDING, KYC_APPROVED, PRE_APPROVED, REVIEW_PENDING, CREDIT_EXEC_PENDING,
  CREDIT_EXEC_APPROVED, CREDIT_HEAD_PENDING, CREDIT_HEAD_APPROVED, DISBURSEMENT_PENDING,
  ACCOUNTANT_PENDING and DISBURSEMENT_FAILED.
- **Main flow:**
  1. **One click, with no confirm**, sends the fixed note "Cancelled by admin from customer page".
  2. The application moves to `CANCELLED` (event `CANCEL`). Toast: "Application cancelled".
- **Business rules:** the backend allows ADMIN, or the owning borrower.
- **Alternate & error flows:**
  - Several offered statuses have **no CANCELLED edge**: DISBURSEMENT_PENDING, CREDIT_EXEC_APPROVED,
    CREDIT_HEAD_PENDING, CREDIT_HEAD_APPROVED and ACCOUNTANT_PENDING. They fail with
    `ILLEGAL_TRANSITION` (contradiction C-4).
  - Others → `FORBIDDEN_ROLE` "You are not allowed to cancel this application".
- **Postconditions:** **APPLICATION_CANCELLED** goes to the borrower (IN_APP, WHATSAPP).

---

## 11. Customers register

### UC-S1-74 Browse the Customers register by segment
- **Actor / permission:** `customer:view` (every role except DSA).
- **Entry point:** sidebar **Customers**, plus one sub-item per segment (`?seg=<seg>`). Roles without
  `customer:view:all` get `&mine=1` appended automatically.
- **Screen:**
  - Header: "Customers" — "Every borrower — search by name or ID, then open to see loans, payments and
    KYC."
  - Segment chips with counts: All, then Incomplete … Unallocated.
  - Then the search, the date filter and the table (UC-S1-75).
- **Business rules — segment of a row:** the customer's **latest** application plus latest loan.
  Loan state outranks application status. Rules are applied in this order:

  | Segment | Rule |
  |---|---|
  | Overdue | loan OVERDUE/IN_COLLECTIONS (the SQL treats an ACTIVE loan past `due_date` as OVERDUE), or app OVERDUE/DEFAULTED |
  | Active | loan ACTIVE/DISBURSING, or app DISBURSED/ACTIVE |
  | Review | app REVIEW_PENDING, CREDIT_EXEC_PENDING, CREDIT_HEAD_PENDING |
  | Approved | app KYC_APPROVED, CREDIT_EXEC_APPROVED, CREDIT_HEAD_APPROVED, **SANCTIONED** |
  | Disbursement Pending | app DISBURSEMENT_PENDING, ACCOUNTANT_PENDING |
  | Hold | app DISBURSEMENT_FAILED (always empty — C-3) |
  | Incomplete | app DRAFT (abandoned mid-onboarding) |
  | Rejected | app KYC_REJECTED, REJECTED |
  | Closed | app CLOSED, WRITTEN_OFF, CANCELLED |
  | Pending | app KYC_PENDING, PRE_APPROVED, and the fallback for anything else |
  | *Overlays* | **Blocked** (a live rejection-register block on the mobile) · **Reloan** (latest application, not DRAFT, created on or after an earlier loan closed) · **Unallocated** (no owner). A row counts in its lifecycle segment **and** in each overlay. |

  - The chip counts come from a separate summary call that may lag the table by up to 30 s.
  - The backend `CustomerSegments` and the SQL in `CustomerBookQuery` mirror the frontend
    `segments.ts`.

### UC-S1-75 Search, filter, sort and page the register
- **Screen:**
  - **Search:** "Name, PAN, mobile, customer or application ID". This is a substring match on name,
    PAN, mobile and customer id, or on any of the customer's application ids.
  - The **"Joined"** date filter applies to the customer's **first** application date (`joined`). The
    controller doc says "latest application" (contradiction C-19).
  - Pinned filters: Stage, DPD, Loan amount, Owner, Credit score, Risk, Employer. Name, mobile, PAN,
    date, stage date, joined and salary sit under "More filters".
  - Sort keys: stage date (default), date, name, customer ID, stage (lifecycle order), mobile, PAN.
    Rows are grouped under date headers when sorted by a date.
  - **Columns:**
    - S.No.; select; **Customer** (opens the latest application, or the customer view when there is
      none); Signals; Date; Mobile; PAN; Account; IFSC;
    - Loan; Amount; Due; **Owner** (or "Unallocated"); Loans; Outstanding;
    - Bureau; **Failure**; Latest status; Stage; Stage date;
    - Credit exec; Disbursed by; Collections exec; **Open →**.
  - Page size defaults to 25, with a server maximum of 100.
- **Alternate flows:** the empty state names what is narrowing the list: "No customers for "<q>" in
  <segment> in the selected date range with these column filters."

### UC-S1-76 My customers / Unallocated / scope
- **Business rules — who sees which customers** (`CustomerService.scope`, by working role):
  - **Whole book:** ADMIN, CREDIT_HEAD, COLLECTION_HEAD, DISBURSEMENT_HEAD.
  - **Scoped roles** see a customer they own (`customer_owner`), have an application assigned to, or
    recorded a lifecycle decision on.
    - Collection Executive: plus customers behind collection cases assigned to them.
    - Accountant: plus **every customer who has a loan**.
    - Telecaller: plus every **unallocated** customer, and any customer on their pre-sanction queue.
  - A scoped staffer sees the banner "Showing only customers assigned to you or that you have
    recorded a decision on."
  - A customer outside the scope returns **404**, never 403.
- **"Mine"** (`?mine=1`) means owned by me **or** decided by me. Its chip reads "My customers".
- **"Unallocated customers"** nav (`customer:assign`, hidden for the Admin working role) opens
  `?seg=unallocated`.

### UC-S1-77 Assign or clear a customer's owner
- **Actor / permission:**
  - UI: `customer:assign` (Credit Head, Collection Head, Telecaller, Admin).
  - Backend: real CREDIT_HEAD, COLLECTION_HEAD or TELECALLER, and ADMIN passes.
- **Entry point:** the Customer tab → section **Owner** ("Current owner: <name> | Unallocated"),
  and the compact picker on the telecalling rows.
- **Screen:** the "Assign to" select, with "Unallocated" first, then staff as "Name (ROLE)" — Credit
  Executives, Collection Executives and Telecallers. A Telecaller sees only Telecallers. The roster
  loads on first focus. **Save**. Toast: "Owner updated".
- **Business rules:**
  - Choosing "Unallocated" deletes the owner row.
  - The assignee must be an active staff member: `INVALID_ASSIGNEE` "The assignee must be an active
    staff member".
  - A Telecaller may assign **only to a Telecaller** (`FORBIDDEN_ROLE` "A TELECALLER may assign
    customers only to another TELECALLER").
  - A Telecaller **cannot take over or clear another staffer's customer**: the attempt returns 404.
  - Every change is audited (field `owner`).
- **Postconditions:** the new owner gains visibility of the customer (UC-S1-76).

### UC-S1-78 Remarks and call logs from the register
- See UC-S1-57 (Communication tab) and UC-S1-60 (Follow-ups). Both are reached through **Open**.

### UC-S1-79 Bulk assign / bulk reject from the register
- **Actor:**
  - **Assign:** `loan:approve` (Credit Head), **or** the Admin working role (`workingAsAdmin`).
  - **Reject:** `loan:approve`, `loan:disburse` or `collections:manage`, or the Admin working role.
- **Screen:**
  - The checkbox column targets each row's **latest application**. A row is selectable only when its
    latest status is rejectable: DRAFT, KYC_PENDING, KYC_APPROVED, REVIEW_PENDING,
    CREDIT_EXEC_PENDING, SANCTIONED or DISBURSEMENT_PENDING.
  - The checkbox tooltip says why a row is not selectable: "Already rejected", "Application already
    cancelled", "Loan is closed — nothing to reject", "Loan is active — nothing to reject", "No
    application on this customer to act on".
  - Row icons: **Assign**, **Reject**, and **Edit** (`customer:manage`). Row Reject is also offered on
    PRE_APPROVED for the ADMIN.
  - The bulk bar: "N selected", **Assign selected**, **Reject selected**.
  - A selection mixing DISBURSEMENT_PENDING with credit-stage rows disables Reject, with the ⓘ "The
    selection mixes credit-stage and disbursement-pending files, which are rejected through different
    steps. Select files of one kind to reject them in bulk."
- **Business rules:** the same per-id loop and dialogs as UC-S1-11/12/22. Select-all covers only the
  current page.
- **Edge cases (backend refusals the UI does not foresee):**
  - **Assign** on DRAFT, SANCTIONED or DISBURSEMENT_PENDING rows → `ILLEGAL_TRANSITION`.
  - **Reject** by a Collection Head → `FORBIDDEN_ROLE`, because both reject endpoints refuse that
    role.
  - Cross-mode rejects fail too: a Disbursement Head rejecting a credit-stage row, or a Credit Head
    rejecting a DISBURSEMENT_PENDING row (contradiction C-5).
  - Where both are offered, a **Select customers to message** toggle (`message:send`) switches the
    checkbox column to a customer-id selection for **Send message**.

### UC-S1-80 Export the register
- **Actor:** the **ADMIN working role only**. The export menu renders nothing for any other role, and
  the backend `/export` is `requireAdmin`.
- **Screen:**
  - The export menu offers **this page** (CSV and branded PDF) and **"Download all customers (CSV)"**.
    The full download re-queries the server with the same search, window, segment and column filters,
    capped at **50,000** rows.
  - The filename base is `dhanboost-customers`. The subtitle describes the active filters.
  - Columns: Customer ID, Date, Name, PAN, Mobile, Account, IFSC, Loan, Amount (₹), Amount type
    (requested / eligible limit), Due date, DPD, Owner, Applications, Loans, Latest status, Stage date,
    Loan status, Outstanding (₹), Failure, Credit score, Credit rating, Credit exec, Disbursed by,
    Collections exec.
- **Business rules:** CSV output is formula-neutralised, with a BOM (`lib/export`).

---

## Appendix A — Notification map for every staff action here

| Action / event | Notification type | Channels | Recipients |
|---|---|---|---|
| `ASSIGN` (first assignment) | CREDIT_ASSIGNED | IN_APP | assigned executive |
| `REASSIGN`, `MARK_PENDING`, `VERIFICATION_OVERRIDE`, `ADMIN_FORCE_DISBURSE`, `MANDATE_OVERRIDE`, `VALIDATE_SUCCESS`, `STEP_REOPENED`, `STEP_LINK_SENT` | — none — | — | — |
| `SANCTION` | LOAN_SANCTIONED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `REJECT_LEAD` (and any `AUTO_REJECT_*`) | CREDIT_REJECTED (reasonless) | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `DISB_REJECT` | DISBURSEMENT_REJECTED | IN_APP, EMAIL, WHATSAPP | borrower |
| `ACTIVATE` | LOAN_DISBURSED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| `RETRY` (unreachable from UI) | LOAN_APPLIED_FAST_TRACK | IN_APP, EMAIL | Disbursement Heads, Admins, borrower |
| `CANCEL` | APPLICATION_CANCELLED | IN_APP, WHATSAPP | borrower |
| Repayment verified | REPAYMENT_VERIFIED | IN_APP, SMS, WHATSAPP | borrower |
| `REPAID` (loan closed) | LOAN_CLOSED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| Repayment rejected | REPAYMENT_REJECTED | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| Send reminder | KYC_REMINDER | IN_APP, SMS, EMAIL, WHATSAPP | borrower |
| Resume link (email) | VERIFICATION_STEP_LINK | IN_APP, EMAIL | borrower |
| Sanctioned amount corrected | SANCTIONED_AMOUNT_REVISED | IN_APP, EMAIL, WHATSAPP | borrower |
| Limit override **increased** | LOAN_LIMIT_REVISED | IN_APP, EMAIL, WHATSAPP | borrower |

Delivery is event-driven (`AFTER_COMMIT`, async). No business step depends on a delivery.

## Appendix B — Error codes raised by these use cases

| Code | Where |
|---|---|
| `SESSION_CONFLICT`, `INVALID_CREDENTIALS`, `TOO_MANY_ATTEMPTS` | UC-S1-01 |
| `FORBIDDEN_ROLE` | any role gate; also "Only an admin can reject a pre-approved lead", "DSAs cannot view applications", "DSAs cannot view customer data" |
| `CREDIT_FILE_NOT_ASSIGNED` | sanction / reject / mark-pending / override by a non-assignee real Executive |
| `INVALID_ASSIGNEE` | UC-S1-09, UC-S1-77 |
| `ILLEGAL_TRANSITION` | any transition not in `ApplicationStatus.canTransitionTo` |
| `NOTE_REQUIRED` | reject-lead, disbursement reject, force, mandate override |
| `AADHAAR_DUPLICATE_UNRESOLVED`, `AMOUNT_TOO_LOW`, `INVALID_SALARY_DAY` | UC-S1-16 (and the 65–67 corrections) |
| `TXN_REF_REQUIRED` | UC-S1-21 |
| `NOT_APPLICABLE`, `AGREEMENT_NOT_SIGNED`, `MANDATE_OVERRIDE_NOT_ALLOWED`, `AMOUNT_INVALID` | UC-S1-24/25 |
| `REJECTION_REASON_REQUIRED`, `INVALID_REJECTION_REASON`, `PAYMENT_ALREADY_VERIFIED` | UC-S1-27 |
| `INVALID_PAID_ON`, `INVALID_AMOUNT`, `LOAN_SETTLED` | UC-S1-31 |
| `REASON_REQUIRED`, `ALREADY_BLOCKED`, `NOT_BLOCKED`, `NO_MOBILE_ON_FILE` | UC-S1-28/69 |
| `REVIEW_NOTE_REQUIRED`, `ORDER_NOT_FOUND`, `ORDER_NOT_IN_REVIEW` | UC-S1-29 |
| `UNKNOWN_CHECK` | UC-S1-36 |
| `RETRY_NOT_SUPPORTED`, `RETRY_INPUT_REQUIRED`, `RETRY_INPUT_INVALID` | UC-S1-37 |
| `STEP_LINK_NOT_ELIGIBLE`, `STEP_LINK_UNSUPPORTED`, `STEP_ALREADY_DONE`, `BUREAU_CHALLENGE_ALREADY_NOTIFIED` | UC-S1-38 |
| `INVALID_DOB` | UC-S1-63 |
| `INVALID_MOBILE`, `DUPLICATE_MOBILE`, `MOBILE_IDENTITY_COLLISION`, `INVALID_OTP` | UC-S1-64 |
| `NO_SANCTIONED_APPLICATION`, `BELOW_DRAWN_AMOUNT`, `APPLICATION_ID_REQUIRED` | UC-S1-65 |
| `NO_APPLICATION` | UC-S1-66 |
| `DOCUMENT_REQUIRED`, `INVALID_DOCUMENT`, `NOT_DISQUALIFIED`, `ALLOWANCE_EXISTS` | UC-S1-70 |
| `LOAN_NOT_FOUND`, `LOAN_CUSTOMER_MISMATCH` | UC-S1-57 |
| 404 (`ResourceNotFoundException`) | any customer outside the caller's book; a Telecaller touching another's owner row |

## Appendix C — Contradictions found

| # | Contradiction | Where |
|---|---|---|
| C-1 | The UI comment says the backend rejects a blank reason with `NOTE_REQUIRED` on mark-pending. `markPending` has **no** such check; only the UI enforces it. | `pipeline/actions.tsx` vs `ApplicationFlowService.markPending` |
| C-2 | CLAUDE.md §7/§12 say SoD is enforced "via the event trail". `ApplicationFlowService.actorOf` is defined but **never called**. Credit and disbursement separation is by role only, and repayment separation is structural (the Accountant cannot record). | `ApplicationFlowService` |
| C-3 | `DISBURSEMENT_FAILED` is dead: nothing enters it. Still, the "Failed — retry" tab and the "Hold" segment exist; `retry-disbursement` has no UI caller; the row's Approve would be `ILLEGAL_TRANSITION`; and the ⓘ promises "Re-release them here". | `applications/page.tsx`, `ApplicationStatus` |
| C-4 | `CANCELLABLE` offers Cancel on statuses with no CANCELLED edge (DISBURSEMENT_PENDING, CREDIT_EXEC_APPROVED, CREDIT_HEAD_*, ACCOUNTANT_PENDING). Cancel is also one click with a fixed note and no confirmation. | `customer-tabs.tsx` |
| C-5 | Register bulk-reject rights include `collections:manage` and cross-mode rejects, which the backend refuses. Register Assign is offered on DRAFT, SANCTIONED and DISBURSEMENT_PENDING rows, which `assignExecutive` cannot transition. | `customers/page.tsx`, `bulk-actions.tsx` |
| C-6 | `SanctionedRejectAction` is gated on `loan:review`, which the Credit Head working role lacks although the backend allows the Head. On the full customer page it sits inside a `customer:manage` card, so it always reads "Not your step". | `actions.tsx`, `[customerId]/page.tsx` |
| C-7 | Manual override and Send reminder are gated on `kyc:approve` in the UI. The backend also allows CREDIT_HEAD and ADMIN, and TELECALLER for the reminder, but those working roles never see the buttons. | `verification-checks.tsx` vs `CreditTeamGuard` |
| C-8 | The resume link is gated two ways: `kyc:approve` on the Verifications tab, `verification:retry` on the Third-party logs tab. | the two tabs |
| C-9 | The disbursement Reject reuses the "Transaction id" box as the reason. The docstring says reject needs no text, yet the button is disabled until text is typed. The disbursement-mode dialog copy omits the 30-day block that `rejectWithBlock` applies. | `actions.tsx`, `bulk-actions.tsx` |
| C-10 | `REASSIGN`, `ADMIN_FORCE_DISBURSE` and `MANDATE_OVERRIDE` map to no notification. The Disbursement Head is not told that a forced or overridden file arrived, although every other route into DISBURSEMENT_PENDING notifies (decision noted in `NotificationType`). | `NotificationEventListener.mapAction` |
| C-11 | The ADMIN force skips the mandate and reference-OTP gates, and never sets or validates the drawn amount. `mandateOverride` does both. | `adminForceDisbursementPending` |
| C-12 | `AdminLogPaymentButton` is mounted in the Awaiting-repayment rows but renders only for the Admin working role, which never sees that panel. | `applications/page.tsx` |
| C-13 | The verification-override audit row does not set `acting_role`; every `logEvent` row does. | `logVerificationOverride` |
| C-14 | The `LimitOverrideCard` docstring says "the borrower is not notified". Its own copy and the backend notify on an increase. | `[customerId]/page.tsx` |
| C-15 | Score floors differ: 550 (auto-reject, suspended), 600 (BRE and stars), 510 (CRIF model floor). BRE cross-references drift too: the calculator and rule 4's policy say "rules 2 and 4", but the UI numbers the personal-loan and NBFC rules 3 and 5. | `CreditRatingCalculator`, `bre/rules.ts` |
| C-16 | Sanction projects the repayment date with `LocalDate.now()` (JVM zone). The salary-day correction uses IST. | `sanction` vs `changeSalaryCreditDay` |
| C-17 | `ApplicationDetailDialog` docs say "20 lifecycle tabs"; `CUSTOMER_TABS` has 21. | `application-detail-dialog.tsx` |
| C-18 | CLAUDE.md gives a ₹10,00,000 maximum loan, but neither the sanction, the approved-amount correction nor the limit override enforces an upper bound. | `sanction`, `changeSanctionedAmount`, `setLimitOverride` |
| C-19 | The `/api/customers/page` doc says the date window applies to the "latest application". The SQL filters on `joined`, the **first** application, and the UI labels it "Joined". | `CustomerController` vs `CustomerBookQuery` |
| C-20 | The "Blocked" flag and card cover any live rejection block (cooling-off included), but Unblock lifts only staff blocks, so it can fail with `NOT_BLOCKED` on a customer shown as Blocked. | `CustomerBookQuery`, `CustomerService.unblock` |
