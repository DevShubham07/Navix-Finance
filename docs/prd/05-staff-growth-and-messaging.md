# 05 — Staff: growth (leads, telecalling, DSA, referral) and customer messaging

This is the use-case catalogue for the growth and outreach side of the staff console. It covers the
telecaller lead register, the telecalling chase-up queue, bulk lead import, the DSA portal and its
administration, the commission and referral ledgers, admin customer messaging, the templates library,
repay-now discount offers and the one-use reapply allowance. Everything here was extracted from the code
(`navix-loan` services plus `frontend/src/app/staff/**`). Where the code and an older document disagree,
the code is described and the disagreement is listed in [§13](#13-contradictions-and-gaps-found).

**Conventions.** Money is integer paise; "₹" figures on screen come from `paiseToINR`. A "rolling 24 h"
window means `now − 1 day`, not a calendar day. Error codes are `BusinessException` codes returned as
HTTP 422 inside the `ApiResponse` envelope. "Real role" means the JWT role. The working-role switcher
never widens authority (CLAUDE.md §7).

## Table of contents

1. [Lead register (`/staff/leads`)](#1-lead-register-staffleads): UC-S3-01 … UC-S3-05
2. [Telecalling queue (`/staff/telecalling`)](#2-telecalling-queue-stafftelecalling): UC-S3-06 … UC-S3-09
3. [Leads dashboard (`/staff/admin/leads`)](#3-leads-dashboard-staffadminleads): UC-S3-10 … UC-S3-11
4. [Lead import (`/staff/leads/import`)](#4-lead-import-staffleadsimport): UC-S3-12 … UC-S3-14
5. [DSA portal (`/staff/dsa/*`)](#5-dsa-portal-staffdsa): UC-S3-15 … UC-S3-19
6. [DSA administration (`/staff/admin/dsa`)](#6-dsa-administration-staffadmindsa): UC-S3-20 … UC-S3-27
7. [Commission engine (system)](#7-dsa-commission-engine-system): UC-S3-28
8. [Referral program](#8-referral-program): UC-S3-29 … UC-S3-32
9. [Admin customer messaging](#9-admin-customer-messaging): UC-S3-33 … UC-S3-36
10. [Templates library (`/staff/admin/templates`)](#10-templates-library-staffadmintemplates): UC-S3-37 … UC-S3-41
11. [Repay-now discount offers](#11-repay-now-discount-offers): UC-S3-42 … UC-S3-43
12. [Reapply allowance](#12-reapply-allowance): UC-S3-44 … UC-S3-45
13. [Contradictions and gaps found](#13-contradictions-and-gaps-found)

### Shared vocabulary

| Concept | Values | Notes |
|---|---|---|
| `lead.call_status` (disposition) | `NOT_CALLED` (default), `CALLED`, `CALLBACK`, `NO_ANSWER`, `NOT_INTERESTED`, `WRONG_NUMBER`, `CONNECTED` | Shown with `_` replaced by a space. Single current value. **No per-call history is kept on a lead.** |
| `lead.lead_outcome` (stored) | `NEW` (default), `OUTREACHED`, `REJECTED` | The only values a human can set. |
| Effective outcome (read) | stored value, **or `CONFIRMED`** | `CONFIRMED` is never stored. It overlays the stored value on every read when an attributed application exists (see below). |
| Outcome labels | NEW → "Not yet worked", OUTREACHED → "Outreached", REJECTED → "Rejected", CONFIRMED → "Confirmed" | Shared by four screens (`components/staff/lead-outcome.tsx`). The Confirmed chip has the tooltip "Derived from this lead's application — not set by staff". |
| `lead.source` | `DSA`, `REFERRAL`, `WALK_IN`, `OTHER` (or empty) | Imports are always `OTHER`; DSA-entered leads are always `DSA`. |
| Quality rating | 1–5 stars, or unrated (null) | |
| Lead partition | `owner_dsa_id IS NULL`: the telecaller side (hand-entered by staff, or imported by anyone) · `owner_dsa_id = <DSA>`: DSA-entered, commission-eligible | The telecaller register, its facets and its stats **always** apply `owner_dsa_id IS NULL`, and no request parameter can override it. |

**Attribution (how `CONFIRMED` and the DSA status resolve).** A lead's attributed application is the
**single earliest `loan_application` created strictly after the lead's `created_at`** for a customer
whose PAN equals the lead's PAN (trimmed, upper-cased). A lead with no PAN can never be attributed, so
it can never show `CONFIRMED`. Telecaller-created leads have no PAN field, so only imported leads that
carried a PAN, and DSA leads, can reach it. The pin is permanent: a later application by the same PAN is
always later and never displaces it. Lists resolve attribution in one batched query (PANs chunked 1,000
per lookup).

---

## 1. Lead register (`/staff/leads`)

### UC-S3-01 Browse and search the lead register
- **Actor / working role / permission:** TELECALLER or ADMIN (real role); frontend token `leads:manage`.
  The backend `requireLeadWriter` refuses any other role with `FORBIDDEN_ROLE` "TELECALLER or ADMIN
  required".
- **Entry point:** sidebar *Leads & sales › Leads*. The global search deep link `?q=` pre-fills the search.
- **Preconditions:** a signed-in staff session. The list waits until the role gate has answered.
- **Screen:**
  - Header "Leads", subtitle "Enter new leads (name + mobile required). Update call status, quality ★
    and remarks." A **Refresh** button (spinner while refetching).
  - The collapsed **New lead** card (UC-S3-02), then a search box (placeholder "Name or mobile").
  - Table columns: S.No. · Name · Mobile (mono) · Source (`source · sourceDetail`, or "—") · Status
    (call-status badge) · Outcome (chip) · ★ (`n★` or "—") · City.
  - Header filters run **server-side**: Name, Mobile, City are free-text; Source and Status are
    checklists of facet values ("—" means a missing source); ★ is a numeric range. Outcome and S.No.
    have no filter. No column sorts; the order is always newest first (`id desc`).
  - Server paging. The page size is capped at **100** (`MAX_PAGE_SIZE`; the default view is 25). The total
    counts every lead matching the filter.
  - The right-hand 320 px **disposition panel** (UC-S3-03).
- **Main flow:** 1. The telecaller types a name or mobile, and the system matches the name
  case-insensitively and the mobile as typed (substring). 2. They open a header filter, and the system fetches that
  column's facet under the same scope and the other filters. 3. They click a row, or press Enter or Space
  on it, and the system marks it `aria-current` and loads it into the panel.
- **Business rules:** the DSA-owned partition is excluded from rows, counts and facets. A facet
  never leaks a value from a DSA lead.
- **Alternate & error flows:** loading shows an 8×8 skeleton. With no match: "No leads match your search or
  filter" plus **Clear search and filter**. With none at all: "No leads yet — add one above." When the
  session can't be confirmed: "Couldn't confirm your staff session, so leads weren't loaded. Sign in
  again if this persists." A wrong role sees "Telecaller or Admin access required."
- **Postconditions:** none (read-only).
- **Edge cases:** a DSA-owned lead id requested directly (`GET /api/leads/{id}`) returns
  `LEAD_NOT_FOUND`, the same as a missing id. A 403 is final, so the client does not retry it.

### UC-S3-02 Create a lead by hand
- **Actor / permission:** TELECALLER or ADMIN; `leads:manage`.
- **Entry point:** **New lead** button (it toggles to "Hide form").
- **Screen (inputs):**

  | Label | Type | Required | Validation / message |
  |---|---|---|---|
  | Name | text | yes | non-blank; server ≤ 160 chars |
  | Mobile | numeric text, placeholder "10 digits" | yes | client normalises to digits; must be exactly `^[0-9]{10}$` (server bean validation) |
  | Email | email | no | server ≤ 160 |
  | City | text | no | ≤ 120 |
  | Employer | text | no | ≤ 160 |
  | Monthly salary (₹) | text, placeholder "e.g. 25,000" | no | see the money rule |
  | Loan amount interested (₹) | text | no | see the money rule |
  | Source | select: —, DSA, REFERRAL, WALK IN, OTHER | no | server: one of the four, else `INVALID_SOURCE` |
  | Source detail (DSA name…) | text | no | ≤ 240 |
  | Notes | text (full width) | no | |

  **Money rule** (`parseLeadRupees`): blank counts as empty and is omitted. Accepted are digits, optionally grouped Indian
  (`2,50,000`) or Western (`250,000`), optionally prefixed "₹", with up to two decimals. Zero, a minus sign, letters
  and misplaced commas ("25,00") are invalid. The error "Enter a rupee amount above 0, e.g. 25,000"
  appears once the field loses focus. **Save lead** stays disabled while either amount is invalid,
  name is blank or mobile is not 10 digits. The server also refuses a negative amount
  (`INVALID_AMOUNT`).
- **Main flow:** 1. Fill the form and click **Save lead**. 2. The system stores `call_status =
  NOT_CALLED`, `lead_outcome = NEW`, `created_by_staff_id = actor`, `owner_dsa_id = null`, no PAN.
  3. The form clears and collapses, and the toast "Lead saved" appears. The register refreshes.
- **Alternate:** a server error shows below the button in red.
- **Edge cases / not allowed:** no duplicate check on manual create (two leads may share a mobile). There
  is no PAN field, so a hand-entered lead can never become `CONFIRMED`. **Editing** an existing lead
  (`PUT /api/leads/{id}`, patch semantics, same field limits) exists in the API but has **no UI**.

### UC-S3-03 Record a call disposition (status, quality ★, remarks)
- **Actor / permission:** TELECALLER or ADMIN; `leads:manage`.
- **Entry point:** select a row. The panel shows the lead's name, mobile, "Salary ₹X · Wants ₹Y" (when
  known) and, at the bottom, "Intake notes: …".
- **Screen:** with no selection, the panel reads "Select a lead to update call status, quality ★ and remarks." With a
  selection it shows: **Call status** select (seven values) · **Quality** five star buttons ("n star(s)",
  `aria-pressed`). Clicking the chosen star again keeps it; **Clear rating** (disabled when unrated)
  clears it. · **Remarks** textarea · then the Outcome block (UC-S3-04) · one **Save** button.
- **Main flow:** 1. The telecaller sets the fields and clicks **Save**. 2. The system sends the disposition
  first: `callStatus` (required; upper-cased; must be one of the seven, else `INVALID_CALL_STATUS`
  "callStatus must be one of …"), `qualityRating` (1–5 or null, else `INVALID_QUALITY_RATING` "qualityRating
  must be 1–5"), `remarks` (blank → null). 3. Only after that does it send the outcome patch, if anything there
  changed (UC-S3-04). The two are **sequential, not parallel**: they write the same row, which has no version
  column. 4. One success toast names what was saved, for example "Disposition saved", "Disposition, outcome &
  DSA note saved".
- **Business rules:** the disposition endpoint uses **replace semantics**. Rating and remarks are
  overwritten with whatever is sent, including null. The previous value is not kept anywhere: there is no
  per-lead call log. (The customer-level `customer_call_log` of the customer 360 is a different feature.)
- **Alternate & error flows:** each request keeps its own inline error, "Call status, rating & remarks
  not saved: …" and/or "Outcome & note not saved: …". If the panel unmounts mid-save, for example because
  another lead was picked or the refetch dropped this lead from a filtered list, the failures are raised as toasts:
  "<name>: call status, rating & remarks not saved — <reason>".
- **Postconditions:** `lead.call_status / quality_rating / remarks` updated; `updated_at` bumped (this
  drives the "Called" trend in UC-S3-10).
- **Edge cases:** saving `NOT_CALLED` is allowed (it effectively un-calls the lead).

### UC-S3-04 Set the outreach outcome and the note for the DSA
- **Actor / permission:** TELECALLER or ADMIN; `leads:manage`.
- **Screen:** the panel's Outcome section shows a **Set outcome** select (Not yet worked / Outreached /
  Rejected) and a **Note for the DSA** textarea (placeholder "What should the agent who sent us this
  lead know?") with the caption "Visible to the DSA who uploaded this lead. Remarks above stay
  internal." When the effective outcome is `CONFIRMED`, a Confirmed chip appears with the text "This
  lead has an application against it, so it shows as Confirmed wherever it appears. That is derived
  and overrides whatever is set below." The picker then shows `NEW`.
- **Main flow:** 1. The telecaller changes the outcome and/or the note, then clicks the one **Save**. 2. The client computes
  a patch holding **only the changed fields**. Notes are compared trimmed; a note cleared to blank is sent
  as `""`. If nothing changed, no outcome request is sent. 3. The server validates `leadOutcome` against
  `NEW | OUTREACHED | REJECTED`; anything else, including `CONFIRMED`, fails with `INVALID_LEAD_OUTCOME`
  "leadOutcome must be one of … (CONFIRMED is derived from the application, not set here)". The note is
  ≤ 2,000 chars and blank clears it.
- **Business rules:** **patch semantics**: a null field is left alone. This is a separate endpoint from
  the disposition because the disposition would otherwise null these two fields on every save.
- **Who reads the note:** `dsaNote` is the one lead field a DSA can read. It reaches a DSA only when that
  DSA created the lead row: hand-entered leads are invisible here, but a lead **imported by a DSA** sits
  in this register (owner null, creator = the DSA) and the DSA sees it, view-only, as "Uploaded" in their
  portal. `remarks` never leaves staff screens.
- **Edge cases:** a CONFIRMED lead still stores its own `lead_outcome`. Editing only the note never
  rewrites it, because the patch omits the untouched outcome.

### UC-S3-05 Lead register permissions summary
- **Actor:** any staff.
- **Rules:** create, list, page, facets, get, update, disposition and outcome all require TELECALLER or
  ADMIN. Stats require ADMIN. Every by-id method goes through `requireTelecallerLead`, which returns
  `LEAD_NOT_FOUND` for a DSA-owned lead. A telecaller therefore cannot read or write DSA leads even by
  guessing ids; ADMIN reaches them only via `/api/admin/dsa/**` (§6). A DSA can never call `/api/leads`
  register endpoints (role refused). The import endpoints under the same path are the exception (§4).

---

## 2. Telecalling queue (`/staff/telecalling`)

The telecalling queue is **not** the lead register. It chases live loan applications stuck before sanction.

### UC-S3-06 Work the pre-sanction chase-up queue
- **Actor / permission:** TELECALLER or ADMIN (backend `requireTelecallingRole`, `FORBIDDEN_ROLE`
  "This action requires role TELECALLER or ADMIN"); frontend `leads:manage`. A wrong role sees
  "Telecalling access only (TELECALLER / ADMIN)."
- **Entry point:** sidebar *Leads & sales › Telecalling*.
- **What's in it:** every `loan_application` whose status has **not** reached sanction, meaning status
  not in {SANCTIONED, DISBURSEMENT_PENDING, ACCOUNTANT_PENDING, DISBURSEMENT_FAILED, DISBURSED, ACTIVE,
  OVERDUE, DEFAULTED, CLOSED, WRITTEN_OFF}. That includes DRAFT, KYC_PENDING, CREDIT_EXEC_PENDING,
  PRE_APPROVED, KYC_APPROVED **and also REJECTED / KYC_REJECTED / CANCELLED**, because they are not in the
  excluded set.
- **Per row:** application id, customer id, name, trust stars ("Signals"), mobile, email (when missing:
  "—" plus a warning icon, "No email on file"), PAN, status badge, stage signal (from `journey_step`),
  **Completeness** = required checks passed / required count, where the required set is PAN, EMAIL,
  BUREAU, SALARY (4), shown as a bar plus "n/4", and **Stale (days)** = whole days since the latest
  `application_event.at` (0 if the row has no event), shown red and bold when ≥ 3.
- **Ordering:** the server sorts stalest first. The page's default sort is also `staleDays desc`. Sortable
  and filterable columns are Customer (text), Status (checklist) and Stale (days) (numeric). Identity columns
  have no filter.
- **Screen:** header "Telecalling", subtitle "Pre-sanction applications that need a chase-up call —
  stalest first", and **Refresh**. There are two sections over the same rows:
  - **Unallocated**: rows whose customer has no `customer_owner` row. Info: "Pre-sanction applications
    with no assigned telecaller. Assign to yourself to start chasing them."
  - **My customers**: rows owned by the signed-in staffer. Info: "Applications currently assigned to you."
  - Each section shows a count badge ("x of y" while filtered). If rows exist that belong to others, it
    adds the muted line "n application(s) assigned to other staff aren't shown." Those rows appear in neither section.
- **Main flow:** the telecaller clicks an application id or name, and the system opens the application detail dialog on the
  Customer tab to place the call.
- **Polling:** the queue refetches every **2 minutes** (and on Refresh). It is not live.
- **Alternate:** a section with nothing in it shows "Nothing here."; a section emptied by filters shows "No applications match the
  column filters."
- **Edge cases:** call outcomes for this queue are recorded in the customer 360 (call log), not here.

### UC-S3-07 Assign a queue customer (Assign to me / owner picker)
- **Actor / permission:** TELECALLER (self, or to another TELECALLER), ADMIN (anyone); backend
  `CustomerService.assignOwner` allows CREDIT_HEAD, COLLECTION_HEAD, TELECALLER (+ ADMIN bypass).
- **Entry point:** **Assign to me** (Unallocated rows only) or the compact owner picker (Telecaller-role
  staff only) in the Actions column.
- **Main flow:** 1. The telecaller clicks **Assign to me**. 2. The system checks that the customer exists and that the assignee is
  **ACTIVE** staff (`INVALID_ASSIGNEE` "The assignee must be an active staff member"), then upserts
  `customer_owner(customer_id → staff, assigned_at)` and writes a `profile_change_log` row (field
  `owner`, old → new). 3. The toast "Customer assigned to you" appears, and the queue refetches. The row moves to My customers.
- **Business rules:** a TELECALLER may not take over or unassign a customer owned by **another** staffer.
  The attempt returns not-found, as if the customer did not exist. A TELECALLER may assign only to
  another TELECALLER (`FORBIDDEN_ROLE` "A TELECALLER may assign customers only to another TELECALLER").
  Assigning `null` unallocates (deletes the row).
- **Alternate:** the failure message shows beside that row's button. Each row tracks its own in-flight state.
- **Postconditions:** ownership is customer-level, so every application of that customer moves together.

### UC-S3-08 Send a verification reminder (single)
- **Actor / permission:** CREDIT_EXECUTIVE, CREDIT_HEAD, ADMIN or TELECALLER; the customer must be visible
  to the caller (`assertVisible`).
- **Entry point:** **Send reminder** in a row ("Send a reminder for outstanding steps").
- **Main flow:** 1. The telecaller clicks it. 2. The system collects the required checks (PAN, EMAIL, BUREAU, SALARY) whose
  status is neither PASS nor REVIEW. 3. If there are none, it returns `sent = false`, and the toast reads "Nothing pending for #id".
   4. Otherwise it publishes a `KycReminderEvent` listing the pending steps (for example "Pan, Bureau"), and the toast
  reads "Reminder queued for #id". The notification engine delivers it on IN_APP, SMS, EMAIL and WhatsApp
  (`db_kyc_reminder`).
- **Business rules:** **no cooldown.** A borrower reminded a minute ago is messaged again. A success
  means "queued", never "delivered".
- **Alternate:** a failure message stays on the row.

### UC-S3-09 Bulk reminders and export
- **Actor:** as UC-S3-08.
- **Screen:** a checkbox per row. The header box selects **only the visible page** (ticking toggles the page;
  ticks made on other pages are kept). **Send to selected (n)** appears when n > 0 and shows
  "Sending… (done/n)" while running. **Export** (CSV/PDF) covers the section's filtered rows with the columns
  Application, Customer ID, Customer, Mobile, Email, PAN, Status, Steps completed, Steps required, Stale
  (days), and file name `dhanboost-telecalling-<section>`.
- **Main flow:** 1. The telecaller ticks rows and clicks **Send to selected**. 2. A confirm dialog appears, titled "Send n reminder(s)?"
  with the body "Each of the n selected application(s) gets a real reminder to the borrower on four channels —
  in-app, SMS, email and WhatsApp. There is no cooldown: a borrower reminded a minute ago is messaged
  again. Applications with no pending verification steps are skipped." It adds "k of them is/are on another
  page of this list." when applicable. 3. On confirm, the client sends **sequentially** and never aborts
  on a row failure. 4. The summary toast reads "Reminders queued for q of n — p had nothing pending — f failed". It is an
  error toast if any send failed.
- **Business rules:** the ids are snapshotted when the dialog opens, so a poll cannot change the count.
  Selection clears when the visible set or the filters change.

---

## 3. Leads dashboard (`/staff/admin/leads`)

### UC-S3-10 Lead tracker graphs
- **Actor / permission:** ADMIN; frontend `staff:manage` (wrong role sees "Admin access only."); backend
  `stats` requires ADMIN.
- **Entry point:** *Leads & sales › Leads dashboard*.
- **Controls:** **From** / **To** date inputs (defaults: today − 30 days → today, browser-local),
  **Telecaller** select ("All", then every staff whose role is TELECALLER or ADMIN, shown as "Name
  (ROLE)"), search, **Outcome** select (UC-S3-11). The note under "Tracker" reads "Follows the date range and
  telecaller only — not the search, outcome or column filters."
- **Figures** (server, over `owner_dsa_id IS NULL` leads with `created_at` in `[from, to+1 day)` UTC,
  optionally narrowed to the chosen creator; omitted dates default to the last 30 days):
  - **Total leads** = count. **Avg quality** = mean of non-null ratings, one decimal plus "★" (or "—").
    **Unrated** = count of null ratings.
  - **Call status**: count per `call_status`. **Source mix**: count per source (missing → "UNKNOWN").
  - **Lead intake trend**: one point per day with two lines. **Created** = leads created that day.
    **Called** = leads whose `updated_at` falls on that day and whose `call_status ≠ NOT_CALLED`. This is
    any edit, not specifically a call, and each lead is counted only on its latest update day.
  - **Quality ★ distribution**: 1★…5★ counts. **Per-telecaller volume**: count per creator (horizontal
    bars, staff name or "#id").
- **Edge cases:** "Telecaller" here means the **creator** (`created_by_staff_id`), not an assignee. Leads
  have no assignee column. An import's rows count under the uploader.

### UC-S3-11 Company-wide lead register and export
- **Actor:** ADMIN.
- **Screen:** "Lead register" table with the columns S.No. · Name · Mobile · Pincode · Source (`· sourceDetail`) ·
  Status · Outcome (chip) · ★ · Remarks (truncated, full text on hover) · Note for DSA · By (creator) ·
  Created (IST day). Server-side header filters: Name, Mobile, Pincode (text), Source, Status
  (checklists), ★ (range). The date window, Telecaller and Outcome selects also narrow the register.
- **Outcome select:** All, Not yet worked, Outreached, Rejected, Confirmed. The server filters the **stored**
  column (see §13 items 6–7).
- **Export:** **ExportMenu** "Leads" exports the page, or **Download all leads (CSV)**, which walks the server
  in 100-row pages. The columns are Name, Mobile, Pincode, Email, City, Employer, Salary, Amount interested,
  Source, Source detail, Call status, Quality, Remarks, Outcome (effective), Note for DSA, Created by,
  Created at. The subtitle records the filters. It is disabled when total = 0.
- **Empty:** "No leads in this filter."
- **Edge cases:** no disposition editing here. ADMIN uses `/staff/leads` for that.

---

## 4. Lead import (`/staff/leads/import`)

### UC-S3-12 Upload a lead list
- **Actor / permission:** **any staff role, DSA included** (`leads:import` on every role; backend
  refuses only BORROWER/ANONYMOUS with `FORBIDDEN_ROLE` "Staff sign-in required"). A wrong role sees "You do
  not have access to lead imports."
- **Entry point:** *Leads & sales › Import leads*. The page deliberately does **not** show the lead register.
- **Screen:** header "Import leads", subtitle "Upload a .csv or .xlsx list. Large files are processed in
  the background — you can leave this page." It shows the **Import leads** button (label changes to "Uploading…" /
  "Importing…"), a checkbox **"Fill blank email / pincode / PAN on leads that already exist"** (merge,
  default off, locked while busy), and the helper text ".csv or .xlsx, up to 25 MB. Expected columns: `name, contact
  number, pan card, pincode, emailid`. Re-uploading the same file is safe — rows that already exist are
  skipped."
- **Main flow:** 1. The user picks a file. 2. The client checks the extension (`.csv`, `.xlsx`, `.xlsm`; else "Upload a .csv
  or .xlsx file.") and the size (≤ 25,000,000 bytes; else "That file is X.X MB — the limit is 25 MB."). 3. The browser
  gets a presigned S3 PUT (category `LEAD_IMPORT`, key prefix `leads/import/`) and uploads the bytes directly.
  4. It posts `{s3Key, fileName, merge}`. 5. The server refuses a key outside `leads/import/` or containing
  `..` outright (`INVALID_IMPORT_KEY` "That file was not uploaded as a lead list"). For other problems it
  **records a FAILED job instead of throwing**, so the uploaded file stays reachable: an unsupported extension gives
  "Upload a .csv or .xlsx file — <name> is neither.", and an existing QUEUED/RUNNING job for the same uploader gives "You already have an import running. Wait for it to finish before starting another."
  6. Otherwise the job is saved `QUEUED`, and a worker starts after the commit.
- **Business rules:** a DSA's upload **never merges** (merge is forced off for role DSA). Every
  imported row is unattributed (`owner_dsa_id` null, `source = OTHER`, `source_detail = "Bulk import:
  <fileName>"` truncated to 240), so a bulk upload can never earn commission. One worker thread runs at a time
  (queue capacity 8).

### UC-S3-13 Import processing rules (system)
- **Columns accepted** (header row; matching ignores case and treats `_` as a space; whitespace
  collapsed; order free; extra columns ignored):

  | Field | Accepted headers | Required column |
  |---|---|---|
  | name | `name` | yes |
  | mobile | `contact number`, `contact`, `mobile`, `phone`, `mobile number` | yes |
  | PAN | `pan card`, `pan`, `pan number` | no |
  | pincode | `pincode`, `pin code`, `pin`, `postal code` | no |
  | email | `emailid`, `email id`, `email`, `e-mail` | no |

  If name or mobile is missing, the file fails with `IMPORT_HEADER_INVALID` "Missing required column(s): … Expected header:
  name, contact number, pan card, pincode, emailid". **One headerless shape is recognised:** exactly 7
  columns with no alias matched is read as `pan, email, name, phone, designation, state, pincode`, and
  row 1 is treated as data.
- **File structure:** CSV is RFC-4180 (quotes, `""`, CRLF/LF, BOM stripped, UTF-8). XLSX reads the first sheet only and
  skips leading blank rows. Numeric cells are read as plain decimals, so a mobile typed as a number is not
  turned into `9.8E9`. Blank records are skipped. In CSV, a row whose column count differs from the header is an issue,
  "Expected N columns, found M." It fails the file with `IMPORT_FILE_EMPTY` ("The file is empty." / "No data rows found." / "The workbook has
  no sheets.") or `IMPORT_FILE_UNREADABLE`.
- **Row rules** (each failure is an issue `{row, field, message}`; the row is skipped, never the file):
  - name required ("is required"), ≤ 160 ("must be at most 160 characters").
  - mobile: strip non-digits; a 12-digit value starting `91` loses the prefix; leading zeros are dropped; then
    `^[6-9][0-9]{9}$` ("must be a valid 10-digit mobile number").
  - PAN optional, upper-cased, `^[A-Z]{5}[0-9]{4}[A-Z]$` ("must be a valid PAN, e.g. ABCDE1234F").
  - pincode optional, `^[1-9][0-9]{5}$` ("must be a valid 6-digit pincode").
  - email optional, ≤ 160, must contain `@` with a `.` after it ("must be a valid email address").
- **Dedupe (in this order):**
  1. **In-file**, across the whole run: a row whose mobile, **or** non-blank PAN, was already seen earlier
     in the file is dropped (counted as a duplicate).
  2. **Existing customer**: the mobile or PAN matches any `customer_profile`, so the row is skipped as "already a customer".
  3. **Existing lead** (any lead, either partition), matched by mobile first, then PAN. Without merge the row is a duplicate.
     With merge it **fills blanks only**: email, pincode, and PAN (PAN only when the existing lead is
     unattributed and no other lead already holds that PAN). If nothing is fillable, it counts as a duplicate.
  4. Otherwise the row is inserted (`NOT_CALLED`, creator = uploader).
- **Chunking:** 1,000 rows per transaction (`REQUIRES_NEW`). Progress is saved after every chunk. A crash
  keeps the committed chunks, and re-uploading skips them (idempotent by the dedupe rules).
- **Issues:** only the **first 50** are stored; `issueCount` is the true total.
- **Restart:** on boot, any QUEUED/RUNNING job is marked FAILED with "Interrupted by a restart — re-upload
  the file to finish the remaining rows."
- **Failure:** a business error stores its message; anything else stores "The import stopped unexpectedly:
  <ExceptionType>". The uploaded object is never deleted.

### UC-S3-14 Watch progress, read history, download the original
- **Actor:** the uploader, or ADMIN (another person's job id → `NOT_FOUND` "Import not found").
- **Progress panel** (polls every 2 s until SUCCEEDED/FAILED): title "Importing / Import finished / Import
  failed · <file>", "processed / total rows". The bar is indeterminate until the total is known (the total is
  only known at the end). When finished it shows "Imported **n** · merged m · skipped d duplicates · c already customers ·
  k rows had problems.", the error message if failed, a collapsible "First n problem row(s)" ("Row r: field —
  message"), and on success the line "Uploaded leads are unattributed — see them under Admin › Leads (Source:
  OTHER)." It refreshes the lead lists once on success.
- **History table** "Your recent imports" (latest **20** of the caller's own): Started, File, Status (red when
  FAILED), Rows (processed / total), Imported, Merged, Duplicates, Customers, Problems, Original. Empty:
  "You haven't imported any lead lists yet."
- **Original:** opens a short-lived presigned GET in a new tab, minted on click.
- **Privacy firewall (role DSA, or any role without `customer:view`):** issues are withheld (empty). "Already a
  customer" and "merged" are folded into one **duplicates** figure (customers and merged show 0), so a
  one-row upload cannot reveal whether a mobile or PAN is a customer. The Original link is hidden
  (`downloadable = false`), and a direct download call returns `NOT_FOUND`.

---

## 5. DSA portal (`/staff/dsa/*`)

The DSA is an external commission agent with a staff login. Backend `DsaService` is **strictly role
DSA**, with no ADMIN bypass (`FORBIDDEN_ROLE` "DSA required"). Every read and write is scoped by the JWT's own id.
Frontend gate `dsa:portal` (DSA only, not ADMIN); a wrong role sees "DSA access required." A DSA lands on
`/staff/dsa/leads` after login.

### UC-S3-15 View my leads and their coarse status
- **Entry point:** *DSA › My leads*.
- **What is listed:** every lead the DSA **entered** (`owner_dsa_id = me`) **and** every lead they
  **uploaded** in a file (`created_by_staff_id = me`, owner null), newest first, loaded whole (client-side
  paging).
- **Screen:** header "My leads", subtitle "Add a lead (PAN, name and mobile required). Track its status as
  it moves through DhanBoost.", **Refresh**. Search (placeholder "Name, PAN or mobile"; substring,
  case-insensitive, null-safe) and a **Status** select (All + six values) run server-side. The columns are S.No. ·
  Name · Source ("Entered" / "Uploaded" pill) · PAN · Mobile · Status chip · Outcome chip · Note from us
  (`dsaNote`) · Net disbursed · Commission · Actions. Name, Source, Outcome, Note, Net disbursed and
  Commission have client-side filters and sort. The default sort is name ascending.
- **Coarse status** (from the attributed application; the DSA learns "declined", never why):

  | DSA status | Application statuses |
  |---|---|
  | NOT_APPLIED | no attributed application (or no PAN) |
  | APPLIED | DRAFT, KYC_PENDING (or null) |
  | IN_PROGRESS | KYC_APPROVED, PRE_APPROVED, CREDIT_EXEC_PENDING, SANCTIONED, DISBURSEMENT_PENDING, DISBURSEMENT_FAILED + retired REVIEW_PENDING, CREDIT_EXEC_APPROVED, CREDIT_HEAD_PENDING, CREDIT_HEAD_APPROVED, ACCOUNTANT_PENDING |
  | DISBURSED | DISBURSED, ACTIVE, OVERDUE, **DEFAULTED, WRITTEN_OFF** (money went out; commission is voided separately) |
  | REPAID | CLOSED |
  | DECLINED | KYC_REJECTED, REJECTED, CANCELLED |

  The chip tones are navy (not applied), amber (applied, in progress), red (declined) and green (disbursed,
  repaid).
- **Money shown:** net disbursed is the attributed application's loan `net_disbursed`. Commission is the
  lead's commission row amount (any status). The DSA view never includes KYC data, a second loan, or any
  field the DSA did not type.
- **Business rules:** uploaded leads are **view-only**, with "View only" in Actions ("Leads from an
  uploaded list are view-only"). A foreign or unknown id → `LEAD_NOT_FOUND`.
- **Empty states:** "No leads yet — add one above." · "No leads match the column filters."

### UC-S3-16 Add a lead (PAN-keyed) and the `LEAD_ALREADY_KNOWN` rule
- **Entry point:** **New lead** card.
- **Inputs:** PAN (required, upper-cased as typed, max 10, placeholder "ABCDE1234F", inline error "Enter
  a valid PAN, e.g. ABCDE1234F"; server `^[A-Z]{5}[0-9]{4}[A-Z]$`, message "pan must be a valid PAN, e.g.
  ABCDE1234F") · Name (required, ≤ 160) · Mobile (required, 10 digits) · Email (≤ 160) · City (≤ 120) ·
  Employer (≤ 160) · Monthly salary (₹) · Loan amount interested (₹) · Notes. **Save lead** stays disabled
  until PAN, name and mobile are valid.
- **Main flow (server order):**
  1. **Rate limit:** ≥ **30** leads created by this DSA in the rolling 24 h → `LEAD_RATE_LIMITED` "You've
     reached today's lead-creation limit".
  2. The PAN is already on **any DSA-owned lead** (this DSA's or another's) → a `dsa_lead_rejection(pan,
     reason OTHER_DSA)` row is written, then the call fails with `LEAD_ALREADY_KNOWN`.
  3. The PAN already exists on **any customer profile** → a rejection row is written (`EXISTING_CUSTOMER`), then `LEAD_ALREADY_KNOWN`.
  4. Otherwise the lead is inserted with `source = DSA`, `call_status = NOT_CALLED`, `owner_dsa_id = created_by = me`.
     The toast "Lead saved" appears and the form clears.
- **`LEAD_ALREADY_KNOWN` behaviour:** both causes return the **same** code and message ("This person is
  already known to us"; the UI shows "This person is already known to us."), so the DSA cannot tell
  "another agent has them" from "they are our customer". Each refusal is written to
  `dsa_lead_rejection`. That table is **write-only** today: no endpoint or screen reads it. A refusal does
  not count toward the 30/day limit, because the limit counts saved leads.
- **Edge cases:** a PAN on a telecaller or imported lead (owner null) does **not** block a DSA from claiming
  it. Uniqueness is only among DSA-owned leads (DB partial unique index on `pan` where
  `owner_dsa_id is not null`).
- **Not allowed:** editing PAN after creation. The `PUT /api/dsa/leads/{id}` endpoint (own entered leads
  only; name, mobile, email, city, employer, amounts, notes) has no UI.

### UC-S3-17 Outreach by email (fixed branded templates)
- **Entry point:** **Outreach** on an entered lead → dialog "Contact <name>" with mobile and email.
- **Screen:** Email / SMS toggle (Email is the default). In Email mode: if the lead has no email, an input
  "Lead's email" appears (required, placeholder `name@example.com`, helper "This lead has no email on file —
  it will be saved to the lead."). Three template cards appear (radio group "Email template"), each showing
  name and subject, with a sandboxed iframe preview of the rendered HTML. **Send email** is disabled until a
  template is chosen and an email exists or the typed one is valid.
- **Templates** (server-rendered; the DSA never types subject or body):

  | Key | Name |
  |---|---|
  | `INTRO` | Introduction |
  | `FOLLOW_UP` | Gentle follow-up |
  | `HOW_IT_WORKS` | How it works |

  The subject and body take only the lead's **first name**, and only when it is letters-only, so typed text can't carry a link.
  Otherwise a neutral greeting is used. The CTA links to `https://dhanboost.com/signup/otp`. The footer says the person was
  referred by a DhanBoost partner and that loans are subject to eligibility.
- **Main flow:** 1. The DSA picks a template and clicks **Send email**. 2. The server checks the channel (`SMS|EMAIL`, else
  `INVALID_CHANNEL`), the rate limits (below), the template key (`INVALID_TEMPLATE` "Pick one of the email
  templates"), and the address: no lead email and none typed gives `NO_EMAIL` "This lead has no email address — enter
  one to send", a bad format gives `INVALID_EMAIL` "Enter a valid email address", and a valid typed email is **saved to
  the lead**. 3. It sends HTML through the mail port and records a `lead_outreach` row (channel, address,
  subject, body "[template KEY]\n<text>", status SENT/FAILED, error "delivery failed").
- **Rate limits** (rolling 24 h, counted on `lead_outreach` rows, **including failed sends**): **3 per
  lead** → `OUTREACH_RATE_LIMITED` "You've reached today's outreach limit for this lead"; **50 per DSA**
  → "You've reached today's outreach limit".
- **Result line:** "Sent." (green) or "Not sent (failed) — <error>".

### UC-S3-18 Outreach by SMS (fixed template)
- **Screen:** SMS mode shows "Fixed SMS template — cannot be edited" and a preview text (see §13 item 2).
- **Main flow:** **Send SMS** → same rate limits → the server sends the fixed text
  "DhanBoost: You were referred for a salary-linked advance. Apply at dhanboost.com to check your offer."
  to `91<mobile>` under the DLT key `DSA_LEAD_INVITE`, and records a `lead_outreach` row (SENT with provider ref, or
  FAILED with the error truncated to 240). A transport failure never throws.
- **Risk:** `DSA_LEAD_INVITE` is not mapped under `navix.sms.dlt-template-ids` in `application.yml`, and its
  URL is not the whitelisted `https://dhanboost.com/login` (CLAUDE.md §13). Treat SMS outreach as likely
  to be rejected by DLT until a template is registered.

### UC-S3-19 My earnings
- **Entry point:** *DSA › My earnings*. Header "My earnings", subtitle "3.5% of the net disbursed amount on
  your lead's first loan, payable once fully repaid.", **Refresh**.
- **Tiles:** **Leads added** = count of leads the DSA **entered** (uploaded leads excluded on purpose, so a big
  upload never sits next to zero earnings) · **Converted** ("applied through disbursed") = entered
  leads with **any** attributed application · **Accrued** ("loan disbursed, not yet repaid") · **Payable**
  ("loan fully repaid") · **Paid**. Each money tile is Σ `amount_paise` of the DSA's commission rows in that status;
  VOID is not shown.
- **Ledger:** S.No. · PAN · Name · Mobile · Net disbursed · Commission · Status (ACCRUED amber, PAYABLE
  blue, PAID green, VOID red). Default sort is commission descending; filters on name, amounts and status. Empty:
  "No commissions yet — they accrue once a lead's loan disburses."
- **Privacy:** only rows with `dsa_staff_id = me`. A reassigned commission moves to the new DSA's page.

---

## 6. DSA administration (`/staff/admin/dsa`)

All of `/api/admin/dsa/**` requires real role **ADMIN** (`FORBIDDEN_ROLE` "ADMIN required"). The frontend
gate is `staff:manage` (wrong role: "Admin access only."). The nav item is *Team & settings › DSA*. The page header reads
"DSA program", subtitle "External commission agents — roster, leads, commission ledger and outreach
audit." **Refresh** reloads the roster. Five tiles sit above the tabs: **DSAs** (roster size), **Leads** (Σ entered leads,
hint "n converted"), **Accrued**, **Payable**, **Paid** (Σ across the roster). Tabs: Roster · Leads ·
Commissions · Outreach. Each table keeps its own remembered view (not in the URL).

### UC-S3-20 DSA roster
- **Screen:** columns S.No. · DSA (name) · Status ("Active"/"Disabled") · Leads · Converted · Accrued ·
  Payable · Paid. The default sort is name ascending. All columns are filterable (text, Active/Disabled checklist, numeric
  ranges; money columns in rupees).
- **Figures:** for each staff user of role DSA: Leads = leads **entered** (`owner_dsa_id`). Converted = those
  with any attributed application (one lookup per lead). Accrued/Payable/Paid = Σ commission amounts by status.
- **Empty:** "No DSAs yet." · "No DSAs match the column filters."
- **Not here:** creating or disabling a DSA happens on the Staff/Invites pages (role DSA). Disabling revokes the session.

### UC-S3-21 Company-wide DSA lead register
- **Controls:** **DSA** select ("All DSAs" or one), **Attribution** select ("Entered + uploaded" = ALL /
  "Entered (earns commission)" = owner set / "Uploaded from a file" = owner null), search "Name, PAN or
  mobile" (name case-insensitive, PAN upper-cased, mobile as typed), **From** / **To** (created, UTC day
  bounds). All server-side. A chosen DSA matches leads they own **or** created.
- **Columns:** S.No. · DSA · Attribution ("Entered"/"Uploaded") · PAN · Name · Mobile · Outcome (effective,
  chip) · Note for DSA · City · Created. Client filters: Name, Outcome, Note for DSA, City ("No city").
- **Export:** "DSA lead register" (`dhanboost-dsa-leads`) with the columns DSA, Attribution, Outcome, Note for DSA, PAN, Name,
  Mobile, Email, City, Employer, Created.
- **Edge cases:** with Attribution = ALL and no DSA chosen, **every** owner-null lead in the system is included,
  hand-entered and imported by staff too, not only DSA uploads.

### UC-S3-22 Mark a commission paid
- **Screen (Commissions tab):** filters **DSA** (All DSAs / one) and **Status** (All / ACCRUED / PAYABLE /
  PAID / VOID), both server-side. Columns: S.No. · DSA · PAN · Net disbursed · Commission · Status (with the txn
  ref under PAID and the void reason under VOID) · Actions. Export "DSA commission ledger" includes Txn ref and Paid
  by. Empty: "No commissions match these filters."
- **Main flow:** 1. On a **PAYABLE** row, ADMIN types a **Txn id** and clicks **Pay** (disabled while blank). 2. The server requires
  a non-blank txn ref (`MISSING_TXN_REF` "A transaction id is required to mark a commission paid") and
  status PAYABLE (`NOT_PAYABLE` "Only a PAYABLE commission can be marked paid"). 3. It sets PAID, `txn_ref`
  (trimmed), `paid_at`, `paid_by` = actor name, and appends `dsa_commission_event(PAID, notes
  "txnRef=<ref>", actor)`. 4. The toast "Commission marked paid" appears.
- **Business rules:** the transfer itself is made by hand outside the system. There is no maker-checker on
  commission payment: a single ADMIN pays.

### UC-S3-23 Void a commission
- **Main flow:** on any row not PAID and not VOID, ADMIN types a **Void reason**, then clicks **Void**. The server requires a reason
  (`VOID_REASON_REQUIRED` "A reason is required to void a commission") and refuses PAID (`ALREADY_PAID`
  "A paid commission cannot be voided"). It sets VOID, `void_reason`, `voided_at`, and appends event `VOIDED`. The toast "Commission
  voided" appears.
- **Edge cases:** the API does not refuse voiding an already-VOID row (it just re-stamps the reason). The UI
  hides the control for VOID rows.

### UC-S3-24 Reassign a commission to another DSA
- **Main flow:** on any row not PAID, ADMIN picks **Reassign to…** (the roster minus the current DSA), types a **Reason** and
  clicks the arrows button (disabled until both are set). The server requires a target (`MISSING_DSA`), a
  reason (`REASSIGN_REASON_REQUIRED` "A reason is required to reassign a commission"), and status not PAID
  (`ALREADY_PAID` "A paid commission cannot be reassigned"). It moves `dsa_staff_id` and appends event
  `REASSIGNED(from, to, reason)`. The toast "Commission reassigned" appears.
- **Edge cases:** the server does **not** check that the target id is a DSA (the UI only offers roster
  DSAs). A VOID commission can be reassigned. The lead's `owner_dsa_id` is **not** changed, so the lead
  stays in the old DSA's portal while the money shows in the new DSA's earnings.

### UC-S3-25 Create a commission manually (API only)
- **Actor:** ADMIN. There is **no UI** for this; the typed client `createManual(dsaId, leadId)` exists but is unused.
- **Main flow:** `POST /api/admin/dsa/commissions {dsaId, leadId}`. Both are required (`MISSING_FIELDS`).
  The request is refused if a commission already exists for that lead (`COMMISSION_EXISTS`), the lead does not exist
  (not-found), or the lead's attributed application has no loan yet (`NO_DISBURSED_LOAN` "This lead has no
  disbursed loan to attribute yet"). On success it computes `round_half_up(net_disbursed × 350 / 10000)`, stores ACCRUED with that rate
  snapshotted, and appends event `CREATED_MANUALLY`.
- **Edge cases:** it does **not** check that `dsaId` is a DSA or the lead's owner, that this was the customer's
  first loan, or the loan's current state. A manual row created after the loan already closed stays
  ACCRUED forever, because maturity only runs at close time.

### UC-S3-26 Correct a lead (API only)
- **Actor:** ADMIN. **No UI**; the client `correctLead` exists but is unused.
- **Fields:** `pan` (PAN regex), `ownerDsaId`, `name` (≤ 160), `mobile` (10 digits), `email` (≤ 160; blank
  clears). Each non-blank field overwrites. The owner can be set or changed but **not cleared** (null means unchanged).
- **Rules:** works on **any** lead, either partition. Setting `ownerDsaId` on an imported lead turns it into a
  commission-eligible DSA lead. PAN uniqueness among DSA-owned leads is enforced only by the DB index. No
  commission event is appended. Returns the lead with its effective outcome.

### UC-S3-27 Outreach audit log
- **Screen (Outreach tab):** **DSA** select (All DSAs / one, server-side). Columns: S.No. · Lead # · Channel ·
  To (address, shown in full) · Subject / body (subject bold, body truncated, error in red) · Status (SENT green /
  FAILED red) · Sent (date-time). Filters: Channel, Status, Sent (IST day). Empty: "No outreach sent yet."
- **Rules:** every DSA send, successful or failed, is listed with its **full rendered body**, so ADMIN sees
  exactly what left the platform. The API also filters by `leadId`.

---

## 7. DSA commission engine (system)

### UC-S3-28 Accrue, mature, void
- **Rate:** **350 bps = 3.5 % of the loan's `net_disbursed`** (principal − fee − GST), rounded HALF_UP to
  the paise. The rate is snapshotted on the row (`rate_bps`). One commission at most per lead (unique `lead_id`).
- **Accrue (at disbursal, in the same transaction as the loan mint, never blocks it):** when
  `finalizeDisbursal` mints a loan, the system:
  1. takes the application's profile PAN (none → nothing);
  2. finds a **DSA-owned** lead with that PAN (none → nothing; imported/telecaller leads never earn);
  3. requires the lead to be created **strictly before** the application (else the file was already in flight);
  4. requires that no commission exist yet for the lead;
  5. requires this to be the customer's **first loan ever** (`count(loans of customer) == 1`), since repeat
     borrowing is invisible to the DSA;
  6. inserts `ACCRUED` (dsa = lead owner, lead, customer, application, loan, net disbursed, rate, amount,
     `accrued_at`).
  Any exception is logged and swallowed.
- **Mature (at close):** when `RepaymentService` closes a loan (penalty-aware outstanding = 0), an ACCRUED row
  becomes **VOID** ("settlement approved or loan defaulted/written off") if the loan has an approved
  settlement or its application is DEFAULTED/WRITTEN_OFF. Otherwise it becomes **PAYABLE** (`payable_at`).
- **Void on settlement approval:** after commit of a `SettlementApprovedEvent`, an ACCRUED row on that loan becomes VOID
  ("settlement approved") at once, without waiting for close.
- **Paid:** only by ADMIN (UC-S3-22). Lifecycle: `ACCRUED → PAYABLE → PAID`, `ACCRUED → VOID` (system), any
  non-PAID → VOID (ADMIN).
- **Worked example:** principal ₹10,000 → net ₹8,820 → commission round(882,000 × 0.035) = 30,870 paise
  = **₹308.70**.
- **Audit:** system voids append `dsa_commission_event(VOIDED, actor null)`. Accrual and PAYABLE append no
  event.
- **Edge case (to verify):** accrual matches any DSA lead by PAN plus "first loan", while the portal's status uses
  the pinned **earliest** application after the lead. If that earliest application was DECLINED and a later one
  disbursed, the DSA sees DECLINED, NET DISBURSED "—" and a commission amount at the same time.

---

## 8. Referral program

The flag `referral` (feature_flag row; when the row is missing it falls back to `navix.referral.enabled`, default
true) is the kill switch for **everything**: codes, apply, payouts and staff screens. Reward:
`navix.referral.reward-paise`, default **20,000 paise = ₹200 per person** (referrer and referred each),
snapshotted onto each payout row. A config change needs a redeploy.

### UC-S3-29 Borrower: see and share my referral code
- **Actor:** BORROWER (`FORBIDDEN_ROLE` otherwise).
- **Entry point:** a card on `/dashboard`. It is hidden entirely when the program is disabled.
- **Screen:** "Refer & earn ₹200", "You and your friend each get ₹200 once their first loan is disbursed.",
  the code in large mono with **Copy** (it shows "Copied" for 1.5 s), **Share your code** (native share sheet with the
  text "Use my DhanBoost referral code CODE when you sign up — you and I each get ₹200 once your first
  loan is disbursed.", falling back to copy), and tiles **Earned** (Σ PAID payouts where I am beneficiary)
  and **Friends joined** (my referrals in QUALIFIED). The line "₹X pending payout" (Σ my non-PAID payouts) appears when > 0.
- **Rules:** the code is minted lazily on first read: **8 characters** from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`
  (no 0/O/1/I/L), unique, up to 10 tries (`REFERRAL_CODE_GEN_FAILED`). The code is permanent per customer.

### UC-S3-30 Borrower: validate and apply a referral code (API only)
- **There is no UI caller.** `referralApi.apply` and `referralApi.validate` exist in the typed client, but no
  signup screen calls them, so today nobody can redeem a code from the product.
- **Validate** (`GET …/validate?code=`, lenient, never throws): the code is trimmed and upper-cased. It returns valid = false with
  "The referral program is not available right now." / "Enter a referral code." / "That referral code
  doesn't exist." / "You can't use your own referral code.". Otherwise it returns valid with the message "You and «referrer
  name, or: your friend» each get ₹200 once your first loan is disbursed."
- **Apply** (`POST …/apply`), in order: disabled → `REFERRAL_DISABLED`; blank → `INVALID_REFERRAL_CODE`
  "Enter a valid referral code."; unknown → `INVALID_REFERRAL_CODE` "That referral code doesn't exist.";
  own code → `SELF_REFERRAL`; already applied a code → `ALREADY_REFERRED` "You've already applied a
  referral code."; the caller has **any** loan → `NOT_NEW_BORROWER` "Referral codes apply to your first
  DhanBoost loan only.". On success it creates `referral(PENDING)` and returns "Referral applied — you and X each get
  ₹200 once your first loan is disbursed."
- **Edge case:** validate does not check ALREADY_REFERRED or NOT_NEW_BORROWER, so it can say "valid" where
  apply would refuse.

### UC-S3-31 Referral qualification (system)
- **Trigger:** `finalizeDisbursal`, in the disbursement transaction.
- **Rule:** if the program is enabled and the disbursed borrower has a **PENDING** referral as the referred
  party, the system sets it QUALIFIED (`qualifying_loan_id`, `qualified_at`) and creates **two** `referral_payout`
  rows (PENDING): REFERRER (beneficiary = referrer, counterparty = referred) and REFERRED (beneficiary =
  new borrower, counterparty = referrer), each worth the configured reward. It then publishes
  `ReferralPayoutCreatedEvent` (async notification).
- **Edge cases:** if the program is disabled at disbursal time, the referral stays PENDING and qualifies on a
  later disbursal once re-enabled. That later disbursal is effectively a reborrow (apply-time is the only "first
  loan" check). `REJECTED` is reserved and never produced.

### UC-S3-32 Settle referral payouts (Disbursement Head)
- **Actor / permission:** DISBURSEMENT_HEAD or ADMIN (`FORBIDDEN_ROLE` "This action requires role
  DISBURSEMENT_HEAD"); frontend `referral:payout`; nav *Leads & sales › Referral payouts* (only shown while the
  `referral` flag is on). A wrong role sees "Disbursement Head access only."
- **Flag off:** the page shows "Referral payouts — The refer-a-friend program is currently disabled." with "The
  referral program is turned off, so payout management is unavailable. It can be re-enabled by a
  developer." Every API call fails with `REFERRAL_DISABLED`.
- **Screen:** header subtitle "Settle the ₹ rewards a referral earned — log the transaction id to credit each
  beneficiary." It shows the role pill, Export, and Refresh.
  - **Expense summary tiles:** Pending (count, ₹), Paid (count, ₹), Total rewards (count, ₹), Total paid out
    (₹, "credited to borrowers"). They are computed over **all** payouts: PAID vs everything else.
  - Tabs **Pending** (server returns oldest first) / **Paid**. Columns: S.No. · Beneficiary · Role ("Referrer" /
    "New borrower") · Referral ("with <counterparty>") · Amount · Loan # · then either **Pay & log txn id**
    (input "Bank / UPI txn id" + **Mark paid**) or Txn ref · Paid by · Paid at (IST day). Filters on
    beneficiary, role, counterparty, amount, paid by, paid at.
  - Export columns: Beneficiary, Role, Friend, Amount (₹), Loan #, Status, Txn ref, Paid by, Paid at.
- **Main flow:** 1. The Disbursement Head transfers the ₹ reward by hand outside the system. 2. They type the txn id and click **Mark paid**.
  3. The server requires a txn id (`MISSING_TXN_REF` "A transaction id is required to mark a payout paid.")
  and refuses an already-PAID payout (`ALREADY_PAID` "This payout has already been paid."). It then sets PAID, txn ref, paid at and paid by,
  and publishes `ReferralRewardCreditedEvent` (the beneficiary is notified). 4. The toast "Payout marked paid" appears.
- **Not allowed:** there is no reject/void of a payout, and no maker-checker.

---

## 9. Admin customer messaging

Flag **`admin-messaging`** (V95, seeded **off**; it sends real messages and creates discount offers).
Every preview, send and audience call requires real role **ADMIN** and the flag on (`FEATURE_DISABLED` "Customer
messaging is switched off"). The log and the templates list require ADMIN only. Frontend token
`message:send` (ADMIN real role and the Admin working role).

### UC-S3-33 Send a message to one or many customers (the send dialog)
- **Entry points (four):** customer 360 page (one customer), Customers register bulk selection, Admin ›
  All applications bulk selection, and the Templates page (UC-S3-38, a group or picked customers).
- **Screen:** title "Send message to n customer(s)". **Template** select ("Select template…"; labels get
  "(marketing)" for MARKETING). For **multiple recipients**, templates marked single-only are hidden, with the
  note "Templates that need per-customer details are hidden for multiple recipients." Then **Channels**
  checkboxes (only the template's channels; pre-ticked when there is exactly one), the template's
  **inputs** (PERCENT → select 1 %…7 %; DATE → date picker; TEXT → text box), and an **"I confirm: «confirm
  text»"** tick box when the template carries a `confirmText`. Buttons: Cancel · **Preview** (or "Preview
  first recipient") · **Send**. Send is disabled until a template, at least one channel and every input are set, a
  **preview has been run since the last change**, and the confirmation is ticked when needed.
- **Main flow:** 1. ADMIN picks a template, channels and inputs. 2. They click **Preview**. The server plans the
  first recipient and returns the name, masked mobile, and per channel the provider template, the resolved
  parameters, the rendered SMS or email text, or "will be skipped: «reason»". 3. They click **Send**. The client calls
  send **once per recipient**, 4 at a time, with progress "done / total done". 4. The result list shows "s of n
  recipient(s) received at least one message." and, per recipient and channel, "sent" / "failed — reason" /
  "skipped — reason".
- **Server rules per send:**
  - Target: exactly one of `applicationId` / `customerId` (`INVALID_TARGET`). A customer id resolves to their
    newest application. The name falls back to the newest profile that has one.
  - The live loan is the application's loan, else the customer's newest loan in ACTIVE / OVERDUE /
    IN_COLLECTIONS.
  - **Send hours:** 08:00 ≤ now < 19:00 IST, else `OUTSIDE_SEND_HOURS` "Messages can only be sent between
    08:00 and 19:00 IST". This is checked at send only; preview works any time.
  - **Confirmation:** a template with `confirmText` and `confirmed = false` → `CONFIRMATION_REQUIRED` "Tick the
    confirmation to send this message". This is driven by the template, **not** by category.
  - `CHANNEL_REQUIRED` "Choose at least one channel"; a channel the template lacks → `INVALID_CHANNEL`;
    unknown key → `INVALID_TEMPLATE`.
  - Typed inputs: blank → `INPUT_REQUIRED` "«label» is required". DATE must parse (`INVALID_INPUT` "«label»
    must be a valid date") and is shown `dd MMM yyyy`. TEXT must be ≤ 100 chars with no control characters. An
    amount must be 1–9 digits once commas are removed ("must be a whole number of rupees") and is shown as "Rs. 12,345".
- **Per-channel skips** (each becomes a SKIPPED ledger row, never an error), checked in order:
  `NOT_APPLICABLE: «why»` (rule not met; no name; no salary day; for stored SMS a value > 30 chars or
  non-ASCII) · `SMS_NOT_REGISTERED` (discount SMS DLT key unmapped) · `NO_MOBILE` / `NO_EMAIL` ·
  `OPTED_OUT` (`smsOptIn = false` covers SMS **and** WhatsApp; `emailOptIn` for email) · `SUPPRESSED`
  (email on the SES suppression list) · `OFFERS_OPTED_OUT` (MARKETING template and `offersOptIn = false`)
  · `DUPLICATE_24H` (same customer, template and channel already SENT in the last 24 h).
- **Rules (`Rule`):** `ANY` needs nothing. `LIVE` needs a live loan. `OVERDUE` needs a live loan with DPD ≥ 1.
  `WINDOW_1_5` needs DPD 1–5 (`LATE_REPAYMENT_TOLERANCE_DAYS = 5`).
- **Auto-filled values:** name; amount = outstanding as of today (`Rs. 1,234` on SMS, `₹1,234` on
  WhatsApp); loanId; daysOverdue; dailyCharge = one day's late penalty (2 % of principal); dueDate;
  dueDatePlus5; salaryDate = next date ≥ today on the salary day (clamped to month length); repayBy;
  percent. An empty WhatsApp parameter is sent as "—".
- **Delivery:** synchronous and per channel (a deliberate exception to the event rule, so the admin sees the
  real outcome). WhatsApp goes via SmartChat with the stored template name. SMS uses the registered body and its DLT key.
  Email goes via the mail port (a refusal → FAILED "Email was not accepted (email switched off or provider
  refused)").
- **Postconditions:** **one `customer_message` row per channel** (SENT with provider ref / FAILED with
  reason / SKIPPED with reason), committed on its own immediately after the provider call. It records customer,
  application, loan, template key, provider template, mobile or email, params, discount %, repay-by, sent-by.
  A repay_discount send creates the offer (UC-S3-42).
- **Edge cases:** the dialog-level error messages are "Customer messaging is switched off (admin-messaging flag)." and
  "Messages can only be sent between 08:00 and 19:00 IST."

### UC-S3-34 The built-in catalogue (22 keys) and when each is meant to be used
All are WhatsApp-only SERVICE templates unless stated. "Inputs" are typed by the admin. "1×" = single
recipient only.

| Key | Label | Rule | Inputs | Confirmation required ("I confirm: …") | Meant for |
|---|---|---|---|---|---|
| `overdue_soft_d7` / `_d15` / `_d30` | Overdue reminder (soft), day 7/15/30 | OVERDUE | — | — | gentle nudge at DPD 7, 15, 30 |
| `overdue_strict_d7` / `_d15` | Overdue notice (strict), day 7/15 | OVERDUE | — | "MLB has confirmed in writing that it reports overdue loans to the credit bureaus." | firmer notice |
| `overdue_strict_d30` | Overdue notice (strict), day 30 | OVERDUE | — | "Earlier reminders really were sent, and MLB has confirmed reporting in writing." | final strict notice |
| `reported_soft` / `reported_strict` | Reported to bureaus (soft/strict) | LIVE | — | "MLB has actually reported this loan to the credit bureaus, confirmed in writing." | only after real bureau reporting |
| `cost_clock` | Daily cost of staying overdue | OVERDUE | — | — | shows the daily late charge |
| `eligibility_window` | Eligibility window closing | WINDOW_1_5 | — | — | DPD 1–5: repay by due + 5 days to stay reborrow-eligible |
| `salary_day` | Salary day reminder | LIVE | — | — | before the next salary credit |
| `ptp_confirmation` | Promise to pay: confirmation | LIVE | Promised amount (Rs), Date · 1× | — | after a borrower promises to pay |
| `ptp_missed` | Promise to pay: missed | LIVE | Promised amount (Rs), Date · 1× | — | a promise was broken |
| `part_payment` | Part payment received | LIVE | — | — | acknowledge a part payment |
| `officer_assigned` | Collection officer assigned | LIVE | Officer name, Officer phone | — | introduce the assigned officer |
| `no_response` | No response to calls | LIVE | — | — | after unanswered calls |
| `settlement_offer` | Settlement offer | LIVE | Settlement amount (Rs), Date · 1× | "The Collection Head approved this settlement." | communicate an approved settlement |
| `senior_review` | Referred to senior collections | OVERDUE | — | "This account really has been referred to the senior collections team." | escalation |
| `notice_issued` | Formal notice issued | LIVE | Date · 1× | "A formal notice really was issued on this date." | after a real notice |
| `visit_notice` | Visit notice | LIVE | Representative name, Date · 1× | "This visit is really scheduled." | a scheduled field visit |
| `repay_discount` | Repay-now fee discount | LIVE | Discount percent (1 to 7), Repay by | — | **MARKETING**, SMS + WhatsApp; creates an offer (§11) |
| `payment_overdue` | Payment overdue | OVERDUE | — | — | SERVICE, SMS + WhatsApp; the registered DLT body |
| `payment_due_soon` | Payment due soon | LIVE | — | — | SERVICE, SMS + WhatsApp; the registered DLT body |

- WhatsApp names are `db_<key>_v1_164` (catalogue) or `db_payment_overdue` / `db_payment_due_soon`.
  `repay_discount` switches to `db_repay_discount_review_v1_164` when DPD > 5, because a late repayer's next
  loan goes to review.
- **SMS texts** (registered DLT; must match char-for-char): payment overdue "Dear {name}, repayment of
  {amount} on your DhanBoost loan {loanId} is overdue by {daysOverdue} day(s). Pay at
  https://dhanboost.com/login. - DhanBoost"; payment due soon "… is due on {dueDate}. Pay at …". Discount
  SMS: "Dear {name}, repay loan {loanId} by {repayBy} for {percent×10}% off processing fee on next
  approved loan|loan after review. Pay at https://dhanboost.com/login. - DhanBoost". There is one DLT key per
  percentage (`REPAY_FEE10` … `REPAY_FEE70`, plus `_REVIEW`), because DLT refused the percent as a variable. The
  SMS states the discount as a share of the **fee** (5 % of the loan = 50 % of the 10 % fee).
- Stored, ACTIVE, non-system templates from the library (UC-S3-37) are appended to the dialog's list with key
  `tpl:<id>` while the flag is on.

### UC-S3-35 Audience groups ("Send to") for bulk sends
- **Actor:** ADMIN, flag on (`FEATURE_DISABLED` otherwise).
- **Groups:** DPD groups by loan, over loans in ACTIVE/OVERDUE/IN_COLLECTIONS with a due date:
  `DUE_SOON` (ACTIVE, due today … today + 3), `OVERDUE_1_7`, `OVERDUE_8_30`, `OVERDUE_31_60`,
  `OVERDUE_61_90`, `OVERDUE_90_PLUS` (DPD ≥ 91). DPD uses the IST date. The remaining groups are
  **customer-register segments**: active, closed, reloan, approved, disbursementPending, incomplete,
  pending, review, hold, rejected. **Blocked and Unallocated are excluded on purpose.** An unknown group gives
  `INVALID_GROUP`.
- **Cap:** at most **2,000** customer ids are returned (`capped = true` beyond that). The page says "Only the
  first 2,000 customers in this group are sent to at a time." The browser loops the sends.

### UC-S3-36 Customer messages log (`/staff/admin/messages`)
- **Actor:** ADMIN (`message:send`; wrong role: "Admin access only."). Not flag-gated.
- **Screen:** "Customer messages", subtitle "Every SMS, WhatsApp and email sent from the console, with the
  result. Admin only." Filters: template (All templates + labels), channel (All / SMS / WhatsApp / Email),
  result (All / Sent / Failed / Skipped), From / To (IST days), Customer ID (digits). Any change returns to page 1.
  Columns: Customer (name + #id) · Template (label) · Channel · Discount (%) · Repay by · Status (sent
  green / failed red / skipped amber) · Reason · Sent by · Time · Sent to (**masked** mobile or email).
  Page size ≤ 100. Export page or all (walks 100-row pages). Empty: "No messages match."
- **Edge cases:** the log only covers admin console sends. System notifications (OTP, reminders) are not here.

---

## 10. Templates library (`/staff/admin/templates`)

Real role ADMIN for every call (`FORBIDDEN_ROLE` "Only an admin can manage message templates"). Reading
the list needs ADMIN only. Create, edit, delete and WhatsApp refresh also need `admin-messaging` on. With
the flag off the page shows "Customer messaging is switched off (admin-messaging flag). Templates can be
read, but not added, changed or sent." and every Add, Edit, Delete, Send and Refresh control is disabled.
Frontend gate `message:send` (wrong role: "Admin access only."). Nav: *Customers & credit › Templates*.

**Status model.** `DRAFT → SUBMITTED → ACTIVE | REJECTED`. Labels: SMS = Draft / Submitted / Active /
Rejected; WhatsApp = Draft / Pending / Approved / Rejected. **Who owns status:** SMS is set by hand,
because the DLT portal has no API. WhatsApp is read from SmartChat (Refresh) or set by hand. **Email has no approval
step**, so a new email template is saved straight as ACTIVE. Only **ACTIVE** can be sent. **System-managed**
rows (seeded V97/V98: the nine loan-status SMS, the reference-check SMS, the two discount WhatsApp
templates, and every listed SmartChat template) are listed but are sent only by their own flow, never by
hand, and cannot be deleted.

### UC-S3-37 Browse templates and see what is sendable
- **Screen:** title "Templates", a search box ("Search templates": label, name or wording), a channel tab
  strip **WhatsApp** (default) / **SMS** / **Email**, **Add template**, and the "Send to" bar (UC-S3-38).
- **Cards**, grouped by **purpose** in this order: Collections, Repayment, Discounts, Loan updates, KYC,
  References, Other (missing → Other). Each card shows a purpose pill, Edit (pencil), Delete (bin; hidden
  for system-managed), the title (label or name), two lines of wording with each placeholder replaced by
  "[customer name]", "[amount outstanding]", "[typed label]" and so on ("Wording is kept on the provider." when
  there is no body), a status line for non-sendable cards (status, `: rejection reason`, or the unsendable
  reason), and **Send** ("Send to N" when a group is chosen).
- **Sections:** ready-to-send cards (ACTIVE + sendable) appear by purpose. Two folds sit below: **Not ready to
  send (n)** and **Sent automatically (n)** (system-managed ACTIVE ones with no catalogue entry).
- **Unsendable reasons (server):** "Sent automatically by its own flow, never by hand." · "The
  registered wording has not been filled in." · "Only an Active template can be sent." · SMS without a DLT id
  "No DLT template id." · WhatsApp without a stored name "Not found on SmartChat yet."
- **System-managed WhatsApp:** when ACTIVE and its name maps to a catalogue key (`db_<key>_v1_164` →
  `<key>`), **Send** opens the catalogue template instead, keeping its guards and confirmation.
- **Empty:** "No templates match." / "No templates fit this group."

### UC-S3-38 Send from the library (group or picked customers)
- **"Send to" bar:** chips Due soon · Overdue 1 to 7 · 8 to 30 · 31 to 60 · 61 to 90 · 90+, and a "More
  groups" select (Active, Closed, Reloan, Approved, Disbursement pending, Incomplete, Pending, Review,
  Hold, Rejected). The count reads "N customers" ("Counting…" while loading, "Could not load this group" on error).
- **Filtering by group:** with a group chosen, only templates every member can receive are shown. OVERDUE
  rules need an overdue group. LIVE needs any group except the segment-only ones. WINDOW_1_5 shows only
  for "Overdue 1 to 7". Templates with per-customer typed inputs are hidden.
- **Main flow (group):** **Send to N** → the send dialog (UC-S3-33) opens with every member as a target
  (`Customer #id`) and the template preselected.
- **Main flow (no group):** **Send** → "Send <template> to" picker. Search customers ("Name, mobile, PAN or
  customer ID", 25 results), tick recipients. A template with typed values allows **one** ("This template
  has values typed per customer, so pick one customer."). Then the send dialog opens.
- **Edge case:** the Overdue 1 to 7 group includes DPD 6–7 customers, who are then SKIPPED
  (`NOT_APPLICABLE: loan is not 1 to 5 days overdue`) for a WINDOW_1_5 template.

### UC-S3-39 Add or edit a template
- **Entry point:** **Add template** (for the current channel tab) or a card's Edit. Dialog "Add WhatsApp/SMS/email
  template" / "Edit <name>".
- **Inputs:**

  | Field | Rules (server message) |
  |---|---|
  | Name | required, ≤ 128 ("A name is required (up to 128 characters)"); WhatsApp: `[a-z0-9_]{1,128}` ("WhatsApp names are lowercase letters, digits and underscores"); unique per channel (`TEMPLATE_EXISTS` "A template with this name already exists"); **read-only when editing** |
  | Display name (optional) | ≤ 120 (UI) |
  | Category (purpose) | Collections / Repayment / Discounts / Loan updates / KYC / References / Other; required ("Choose a category for this template") |
  | Subject (email only) | required, one line, ≤ 200 ("Email needs a one-line subject (up to 200 characters) and a body") |
  | Wording | hint per channel. SMS: "Paste the wording exactly as registered on DLT, with {#var#} for each variable." and plain ASCII ("SMS wording must be plain English characters: write Rs. rather than the rupee sign"); `{{` refused ("SMS wording uses {#var#}, not {{1}}"). WhatsApp/Email: "Use {{1}}, {{2}} for variables…"; `{#var#}` refused; numbers must run 1..n with no gaps ("Variables must run {{1}}, {{2}}... with no gaps"). WhatsApp also refuses > 1,024 chars, a double quote, the word "WhatsApp", three line breaks in a row, or a variable at the very start or end ("The wording … which the portal refuses"). |
  | Variable 1..n | one picker per placeholder (default Customer name): Customer name, Amount outstanding, Loan number, Days overdue, Daily late charge, Due date, Due date + 5 days, Next salary date, Typed when sending (text), Typed when sending (date). A typed variable needs a **Label shown when sending** (≤ 40, no ":"). The count must equal the placeholder count ("The wording has N variable(s) but M were chosen"); unknown → "Unknown variable: x". |
  | Message type | Service / Marketing (`INVALID_CATEGORY` "Category must be SERVICE or MARKETING") |
  | Who can receive it | Any customer / Customers with a live loan / Customers with an overdue loan. The server **raises** the rule to fit the variables: any overdue variable forces OVERDUE, and ANY with a loan variable becomes LIVE. |
  | Status (SMS, WhatsApp) | Draft / Submitted to DLT or Pending at SmartChat / Active or Approved / Rejected |
  | DLT template id (SMS) | exactly **19 digits** (`INVALID_DLT_ID` "A DLT template id is 19 digits"); **unique per channel** (`DLT_ID_IN_USE` "Another template already has this DLT id") |
  | Name stored on SmartChat (WhatsApp) | must be the template's own name or `<name>_<digits>` (`INVALID_STORED_NAME` "Use this template's own name as stored on SmartChat"); unique (`STORED_NAME_IN_USE`); defaults to the name when set ACTIVE |
  | Remarks | ≤ 1,000; for WhatsApp kept only as the rejection reason when REJECTED |

  **Save** stays disabled until name and category are set and every typed variable has a label.
- **Status rules:** an SMS can be ACTIVE only with wording and a DLT id ("An Active SMS template needs its
  wording and DLT id"). A WhatsApp template already SUBMITTED or ACTIVE cannot go back to Draft ("A template
  already with SmartChat cannot go back to Draft").
- **Locks:** a WhatsApp template not in DRAFT/REJECTED cannot change wording, variables, category or rule
  (`TEMPLATE_LOCKED` "This template is with Meta. To change it, add a new one under a new name."). An ACTIVE
  SMS or WhatsApp template with wording cannot change wording ("An Active template's wording matches its
  registration. To change it, add a new one."). Email has no lock.
- **System-managed WhatsApp (listed only):** the dialog shows only wording (read-only once on file), purpose,
  label and status. The server accepts only status, purpose, label and wording that is not yet on file.
- **Not settable here:** the per-template confirmation text (`confirm_text`). It exists only on seeded
  rows (SQL).
- **Postconditions:** the row records created-by and timestamps. A newly ACTIVE, non-system row becomes
  available in the send dialog as `tpl:<id>`.

### UC-S3-40 Delete a template
- **Main flow:** the bin → "Delete <name>?" with "This removes it from this page only. It stays on DLT and
  Ultron / SmartChat." → Delete. A system-managed row gives `TEMPLATE_PROTECTED` "This template is sent by the
  system and cannot be deleted". This is a hard delete of our row only; the provider copy is untouched. Ledger rows keep
  their `tpl:<id>` key.

### UC-S3-41 Refresh WhatsApp status from SmartChat
- **Entry point:** "Refresh status from SmartChat" (WhatsApp tab, no group chosen).
- **Main flow:** the server reads every SmartChat template (read-only on the provider), then for each local WhatsApp
  row matches the **exact name**, else the **single** `<name>_<digits>` candidate (ambiguous → no match).
  It sets the stored name and maps the status: APPROVED → ACTIVE, REJECTED → REJECTED (with the provider's reason),
  anything else → SUBMITTED. The notice reads "N updated. Not on SmartChat yet: a, b."
- **Error:** `WHATSAPP_UNAVAILABLE` "Could not read templates from SmartChat", shown with "You can still update a
  status with Edit."

---

## 11. Repay-now discount offers

### UC-S3-42 Offer a repay-now discount (create via `repay_discount`)
- **Actor:** ADMIN, flag `admin-messaging` on, 08:00–19:00 IST.
- **Entry point:** the send dialog, template "Repay-now fee discount (marketing)".
- **Inputs:** **Discount percent (1 to 7)**, a select of whole numbers 1–7 (server `INVALID_PERCENT` "Discount must be
  a whole number from 1 to 7"). **Repay by**, a date from **today to today + 30 days** inclusive, IST
  (`INVALID_REPAY_BY` "Repay-by date must be from today to 30 days ahead").
- **Rule:** LIVE (needs an ACTIVE/OVERDUE/IN_COLLECTIONS loan). MARKETING, so customers with `offersOptIn =
  false` are skipped (`OFFERS_OPTED_OUT`). Channels are SMS and WhatsApp. WhatsApp uses `db_repay_discount_v1_164`,
  or `db_repay_discount_review_v1_164` when DPD > 5. SMS uses the per-percent DLT text (UC-S3-34); an unmapped DLT key
  → `SMS_NOT_REGISTERED`.
- **Meaning of the percent:** a percent **of the next loan's principal**, taken off that loan's
  processing fee (10 % of principal). So 7 % of the loan = 70 % of the fee. It never touches interest or penalty.
- **Postconditions:** if **at least one** channel was SENT, one `repayment_discount_offer(customer, current loan,
  percent, repay_by, message_id = first SENT row, created_by)` is saved. If that save fails after the message
  left: `OFFER_NOT_RECORDED` "The message was sent but the discount offer could not be saved. Check the log
  and tell the customer the offer is void, or ask engineering to record it." The ledger rows carry the
  percent and repay-by.
- **Edge cases:** several offers can exist for one customer. Duplicate protection is only the 24 h
  same-template-same-channel skip.

### UC-S3-43 Unlock, pin, consume and show on the KFS (system)
- **Unlock:** an offer qualifies when it is **unused**, its loan is **CLOSED**, and the **latest VERIFIED payment's
  `paid_on` ≤ `repay_by`**. A loan closed through a settlement is not excluded (to verify with product).
- **Pin (reborrow only):** `ApplicationFlowService.reborrow` pins the **best** qualifying offer, meaning the highest
  percent with ties going to the newest. It writes `fee_discount_percent` + `fee_discount_offer_id` onto the new application. This happens on the clean
  path (PRE_APPROVED → SANCTIONED) **and** on the reapply-allowance path (KYC_PENDING). It never happens on the
  auto-rejected path, and never on a fresh signup.
- **Show:** the offer journey amount preview, the **summary** ("Repayment discount", hint "n% of your loan,
  already taken off the fee above", value "− ₹X"), and the **KFS / sanction letter** row "Repayment discount
  applied" all read the same application column. Math: `discount = round(P × pct / 100)`; `fee = max(0,
  round(P × 0.10) − discount)`; `GST = round(fee × 0.18)`; `net = P − fee − GST`. Example: P ₹10,000 at 5 % →
  fee ₹500, GST ₹90, net ₹9,410.
- **Consume (disbursal):** `LoanService.disburse` stamps the loan's fee, GST, net and `fee_discount_paise`,
  then claims the offer with a compare-and-set (`used_application_id`, `used_loan_id`, `used_at` where
  unused). If it was already claimed, the disbursal is refused and rolled back: `DISCOUNT_OFFER_USED` "The repayment discount on
  this application was already used on another loan". One offer can never discount two loans.

---

## 12. Reapply allowance

### UC-S3-44 Grant a one-use reapply allowance
- **Actor / permission:** real role ADMIN (`FORBIDDEN_ROLE` "Only an admin can allow re-application"); the
  card sits inside the customer 360 page's `customer:manage` block. It is **not** flag-gated.
- **Entry point:** customer page card **Allow re-application**. It is shown only when the customer **is currently
  disqualified** and has **no unused allowance**; otherwise nothing renders.
- **Disqualified means** the reborrow history rule (`isDisqualifiedByHistory`): a prior loan repaid more than
  5 days late, or never fully repaid / written off.
- **Screen:** the text "This customer cannot re-apply because of their repayment history. Allowing it once sends
  their next application to the normal credit queue; it is not pre-approved." It has a **Reason** textarea
  (placeholder "Reason (required, kept on the audit trail)"), a **Supporting document** select ("Select an
  existing document…", items "DOC_TYPE — file (application #id)"; helper "No documents on file — upload
  one below.") **or** "Or upload a new one" (file input, uploaded as `REAPPLY_SUPPORT` to the latest
  application; the select disables once a file is chosen), and an **Allow re-application** button (disabled until a reason
  and a document are set).
- **Server rules (in order):** the customer must exist (has an application); reason required (`REASON_REQUIRED`);
  document required (`DOCUMENT_REQUIRED`) and belonging to one of this customer's applications
  (`INVALID_DOCUMENT` "The document does not exist for this customer"); the customer must be disqualified
  (`NOT_DISQUALIFIED` "This customer is not currently disqualified"); no unused allowance may exist
  (`ALLOWANCE_EXISTS` "An unused re-application allowance already exists").
- **Postconditions:** `reapply_allowance(customer, reason, document_id, granted_by id+name, granted_at)`
  and a `profile_change_log` row (field `reapplyAllowance`, new value "Allowed re-application: <reason>
  (document #id)"). The toast "Re-application allowed" appears and the card disappears.

### UC-S3-45 Allowance consumption at reborrow (system)
- **Trigger:** the borrower taps reborrow (`/reloan`) and the history rule disqualifies them.
- **Rule:** the oldest unused allowance is claimed by compare-and-set onto the new application. The application is pinned
  with the best discount offer (UC-S3-43), gets the person-level evidence (identity, selfie, address
  verifications and references) from the latest sanctioned application, carries **no sanction**, and moves `DRAFT →
  KYC_PENDING` (event `REBORROW_ALLOWED`, "Re-application allowed by admin (allowance N)"). A credit
  executive still decides.
- **Without an allowance:** auto-reject `PAST_DELINQUENCY` (no time box), `DRAFT → REJECTED`.
- **One use:** the claimed row is never reused. A customer disqualified again needs a new grant.
- **Not allowed:** the allowance does not bypass the "no live loan / in-flight application" guard, and it
  never pre-approves or sanctions.

---

## 13. Contradictions and gaps found

Code is authoritative. Each item names the doc or screen that disagrees.

1. **DSA email is not "DSA-authored"** (PRD §9.5). The code offers three fixed server-rendered templates
   (INTRO, FOLLOW_UP, HOW_IT_WORKS); the DSA types nothing.
2. **The DSA SMS preview is not what is sent.** The UI shows "Hi <name>, DhanBoost can get you a quick salary-linked
   advance. Reply YES and we'll call you, or apply directly at dhanboost.com."; the server sends
   "DhanBoost: You were referred for a salary-linked advance. Apply at dhanboost.com to check your
   offer." The DLT key `DSA_LEAD_INVITE` is not mapped in `application.yml`, and the URL is not the
   whitelisted one (CLAUDE.md §13).
3. **The catalogue has 22 keys** (PRD §9.7 lists 17). PRD omits `overdue_soft_d7/d15/d30` and
   `overdue_strict_d7/d15/d30`.
4. **"Marketing templates need a typed confirmation"** (PRD §9.7) is wrong. The confirmation is a tick box
   driven by a per-template `confirmText`, independent of category. The only built-in MARKETING template
   (`repay_discount`) has none; marketing is instead skipped for `offersOptIn = false`.
5. **Stored templates cannot set `confirmText`.** The request DTO has no such field, so it is SQL/seed-only.
6. **The admin leads "Outcome = Confirmed" filter always returns nothing.** The server filters the stored
   `lead_outcome`, which never holds CONFIRMED.
7. **Outcome filters disagree with the cell.** Filtering "Not yet worked" (stored NEW) also returns leads
   that display Confirmed.
8. **"Call logs" for leads** (PRD §9.5, CLAUDE.md) do not exist. A lead has one overwrite-in-place
   disposition with no history. The call log is customer-level, in the customer 360.
9. **Leads have no assignee** (PRD data model lists `assigned_to`). "Telecaller" on the dashboard is the
   creator. Assignment exists only per customer (`customer_owner`) on the telecalling queue.
10. **Import dedupe is not "PAN dedupe"** (PRD §9.5). It is mobile **or** PAN (in-file and against existing
    leads), plus skipping existing customers by mobile or PAN.
11. **The DSA new-lead form drops "25,000" silently.** It still uses the `Number(x) > 0` gate that the
    telecaller form replaced with `parseLeadRupees`.
12. **DSA "Converted"** (portal tile and admin roster) counts any attributed application, including
    DECLINED, while its hint says "applied through disbursed".
13. **API-only features with typed clients but no UI:** `correctLead`, `createManual`, telecaller lead
    edit (`PUT /api/leads/{id}`), DSA lead edit (`PUT /api/dsa/leads/{id}`), referral `apply` and `validate`.
    So "apply a code at signup, live preview" (PRD §9.6, CLAUDE.md §12 `referral`) is unreachable from
    the product.
14. **DsaAdminService says every mutation appends an event,** but `correctLead` appends none. `reassign`
    and `createManual` do not check that the target is a DSA, and `createManual` skips the first-loan check.
15. **Referral `validate` and `apply` disagree.** Validate says valid where apply refuses (ALREADY_REFERRED,
    NOT_NEW_BORROWER).
16. **Referral payouts have no reject/void path,** against the "every approve has a reject" convention
    (CLAUDE.md §12).
17. **"Today's" limit copy hides a rolling window.** The 30 leads, 3 outreaches per lead and 50 per DSA limits are all rolling 24 h,
    and failed sends count.
18. **Commission accrual and the portal status use different attribution.** Accrual uses any DSA lead by PAN plus first loan;
    the status uses the pinned earliest application (UC-S3-28 edge case).
19. **The discount comment says the rejected path never gets an offer,** yet `reborrow` also pins an offer on the
    reapply-allowance path. Settled-then-closed loans can unlock an offer (to verify).
20. **The Templates page group filter** offers WINDOW_1_5 templates to "Overdue 1 to 7", whose 6–7-day members
    are then skipped.
21. **The `KycReminderEvent` javadoc names three channels** (IN_APP/SMS/EMAIL); the notification type sends
    four (adds WhatsApp), as the telecalling confirm dialog says.
22. **`dsa_lead_rejection` is write-only.** No endpoint or screen reads the refusals it logs.
23. **PRD field names that don't exist:** `reapply_allowance.document_key` (code: `document_id`) and a
    `repayment_discount_offer.status` column (code: `used_application_id` / `used_loan_id` / `used_at`).
24. **The telecalling queue includes rejected and cancelled files.** It is "everything not yet sanctioned",
    so REJECTED / KYC_REJECTED / CANCELLED applications sit in it alongside live ones.
