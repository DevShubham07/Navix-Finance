# DhanBoost Staff Console — Claude Design spec: working roles (role switcher)

> Paste this file into Claude Design **after** `docs/design/claude-design-staff-wireframes.md` (the base spec). Everything not mentioned here is identical to the base spec: tokens, shell, table styles, the 14-tab pop-up, the dashboard sections. This file adds the **working-role switcher** and describes, for every working role, exactly what the header, sidebar, dashboard, Live applications page, customer pop-up, collections pages and audit entries show. Sample people, ids and amounts are the same as the base spec (Vijay Rajpal Pohaal #2296807, application #11489, loan #208; admin Meera Krishnan). Token names below (navy, line, line-2, grey-50, grey-100, ink, muted, shadow-md) are the base spec's.

---

## 0. The concept in one paragraph

A staffer has one **real role** (from login, used for all permissions) and works in one **working role** at a time. The working role is chosen from a labelled pill button in the header. It changes what the console *shows and scopes* (queues, buttons, lists), never what the person is *allowed* to do server-side. Only three real roles have more than one working role:

| Real role | Working roles (first = default after login) |
| --- | --- |
| ADMIN | **Admin** (default) · Credit Head · Credit Executive · Disbursement Head · Accountant · Collection Head · Collection Executive · Telecaller (no DSA) |
| CREDIT_HEAD | **Credit Head** · Credit Executive |
| COLLECTION_HEAD | **Collection Head** · Collection Executive |
| everyone else (Credit Executive, Disbursement Head, Accountant, Collection Executive, Telecaller, DSA) | their own role only — the same pill, **static, no chevron, no menu** |

Rules the wireframes must express:

- **Head roles = assign + monitor + approvals.** In Credit Head / Collection Head the Sanction / Mark pending and Log interaction / Record payment / Propose settlement buttons do not exist.
- **Executive roles = "my work only".** Credit Executive and Collection Executive show only files/cases assigned to the signed-in person (scoped by the server). For an admin or head this is empty until they assign something to themselves in the Head role.
- **Each working role shows only its own tabs and buttons.** The ADMINISTRATION nav group and admin pages, Edit customer, Cancel, Force to disbursement, Export, Log payment, deleting documents, editing references, Verification Retry and the **Admin overview** dashboard (§3.1) exist only in the **Admin** working role.
- The choice is remembered per person on that device and resets to the default on sign-out.
- Actions are recorded with the working role used (audit line "(as …)", §7).

---

## 1. The working-role switcher (header, every frame)

### 1.1 Closed state

Header right cluster, left to right: Search pill · **role pill** · avatar · bell · Sign-out icon button. The name/role text block that used to sit beside the avatar is **removed**; only the initials avatar remains.

```
 ┌──────────────────────────┐   ┌──────────────────────────┐  (MK)   🔔 99+   [→]
 │ 🔍 Search…      Ctrl K   │   │ 🛡  Credit Head       ⌄  │
 └──────────────────────────┘   └──────────────────────────┘
```

- **Avatar**: 36px circle "MK", `#EAEFF6`, navy 700 initials. It links to `/staff/profile`. Tooltip on hover: **"Meera Krishnan · Administrator"** (name · *real* role label).
- **Role pill**: height 36px, padding 0 12px 0 10px, `rounded-full`, 1px border `line-2`, white background, gap 8px. Contents: 16px role icon (navy) · label (11.2px, 600, navy) · 14px `ChevronDown` (muted). Hover: background grey-50. Focus: 2px navy-tint ring.
- **Label** = the current working role, e.g. "Credit Head". Default = first in the §0 table.
- **Single-role users**: the identical pill, but static — no chevron, no hover state, not clickable.
- **Sign out** is an icon-only button (36px, `LogOut` icon, tooltip "Sign out"), ghost style.
- Role icons (lucide): Credit Head `ShieldCheck` · Credit Executive `ClipboardCheck` · Disbursement Head `Banknote` · Accountant `Calculator` · Collection Head `UsersRound` · Collection Executive `PhoneCall` · Telecaller `Headset` · Admin `Shield` · DSA `Briefcase`.
- Below `sm` width the pill is hidden from the header; the mobile nav strip gets a one-line select above the links with the same options.

### 1.2 Open state (draw on the Admin dashboard frame)

Clicking the pill opens a white card anchored under it, right-aligned to the pill: **width 280px**, border `line`, radius 12, shadow-md, padding 6.

- **Heading row**: "SWITCH ROLE" — 8.8px, 700, letter-spacing +0.08em, muted, padding 8px 10px.
- **One row per allowed role**, padding 8px 10px, radius 8, hover grey-100, gap 10px:
  - **32px icon tile**: radius 8, navy-tint background (`#EAEFF6`), 16px navy role icon.
  - Text stack: **role name** (11.2px, 600, ink) over a **one-line purpose** (9.6px, muted).
  - Right: 14px navy `Check` on the **current** role only.
- **Footer** (top border `line`, padding 8px 10px, 8.8px muted): *"Actions are recorded with the role you worked as."*

ADMIN menu (8 rows, Admin first and current):

| Role | Purpose line |
| --- | --- |
| Admin ✓ | Staff, settings and company-wide oversight |
| Credit Head | Assign leads and monitor your team |
| Credit Executive | Decide the files assigned to you |
| Disbursement Head | Release approved loans |
| Accountant | Verify repayments and payments |
| Collection Head | Assign cases and approve settlements |
| Collection Executive | Work your collection cases |
| Telecaller | Call leads and log outcomes |

CREDIT_HEAD menu: Credit Head ✓ · Credit Executive. COLLECTION_HEAD menu: Collection Head ✓ · Collection Executive. (Same rows and purpose lines as above.)

### 1.3 Behaviour to show

- Selecting a row closes the menu, **navigates to `/staff/dashboard`**, and shows a toast (bottom-right) **"Now working as Credit Executive"**. Sidebar and dashboard re-render for the new role.
- The role badge on page headers (Live applications, Loans, Transactions) shows the working role label.
- Dashboard subtitle: **"<working role> · your work, decisions and borrowers"**, e.g. *"Credit Executive · your work, decisions and borrowers"*; title stays "Welcome, Meera".

---

## 2. Sidebar per working role

Group headings (OPERATIONS / COLLECTIONS / ADMINISTRATION), icons and active styling are unchanged. Groups with no items are omitted. Draw eight columns side by side on one frame ("Frame 18 — sidebar per working role"): the seven operational roles below plus a final **Admin** column. Other roles show no admin extras. Active item "Dashboard" in each.

**Credit Head** — OPERATIONS Dashboard · Live applications · Customers ▸ (11 segments) · Unallocated customers · My decisions · Staff performance · Import leads. (No Verification Dashboard — overrides are executive work.)

**Credit Executive** — OPERATIONS Dashboard · Live applications · Customers (→ `/staff/customers?mine=1`, no segment children) · Verification Dashboard · My decisions · Staff performance · Import leads.

**Disbursement Head** — OPERATIONS Dashboard · Live applications · Customers ▸ · My decisions · Staff performance · Import leads · Referral payouts (only when the referral flag is on).

**Accountant** — OPERATIONS Dashboard · Live applications · Customers · My decisions · Staff performance · Import leads · ADMINISTRATION Transactions.

**Collection Head** — OPERATIONS Dashboard · Live applications · Customers ▸ · Unallocated customers · My decisions · Staff performance · Import leads · COLLECTIONS DPD buckets ▸ (Upcoming 14 · 1–7 DPD 6 · 8–30 DPD 4 · 31–60 DPD 2 · 61–90 DPD 1 · 90+ DPD 1) · Settlements · Loans ▸ (Active · Overdue · Closed).

**Collection Executive** — OPERATIONS Dashboard · Live applications · Customers (→ `?mine=1`) · My decisions · Staff performance · Import leads · COLLECTIONS DPD buckets ▸ with **my-cases counts only** (Upcoming 0 · 1–7 DPD 2 · 8–30 DPD 1 · 31–60 DPD 0 · 61–90 DPD 0 · 90+ DPD 0) · Settlements.

**Telecaller** — OPERATIONS Dashboard · Customers ▸ · Unallocated customers · My decisions · Staff performance · Leads · Import leads · Telecalling.

**Admin** — OPERATIONS Dashboard · Customers ▸ (11 segments) · Unallocated customers · Verification Dashboard · My decisions · Staff performance · Import leads · Loans ▸ (Active · Overdue · Closed) · ADMINISTRATION Staff · DSA · Invites · Blocklist · Payment settings · Company expenses · Leads dashboard · All applications · Rejections · Provider API dashboard · Transactions. No Live applications, DPD buckets, Settlements, Leads, Telecalling or Referral payouts.

---

## 3. Dashboard per working role (`/staff/dashboard`)

Title **"Welcome, Meera"**, subtitle per §1.3. Sections use the base spec's components; only composition and copy change.

### 3.1 Admin role dashboard (Admin overview)

In the **Admin** working role only, the dashboard has no "Your work" hero; its main content is a section heading **"Admin overview"** (small `ShieldCheck` icon, muted caption "Visible in the Admin role") followed by the base spec's admin sections in order: **Trends** · **Pipeline at a glance** · **Customers by segment** · **Salary dates** · **Transactions**. No other working role (and no non-admin) shows this block.

### 3.2 Credit Head

- Hero: "Your work" + ⓘ, label **"Leads to assign"**, figure **4** (unallocated intakes only), "Open queue →". ⓘ text: *"Hand each submitted intake to an active credit executive, or to yourself. To decide a file, switch to Credit Executive."* Oldest-waiting pill + one-row table as base.
- Section **"Leads to assign"** + pill "4 pending"; QueueTable rows = KYC_PENDING intakes (#11462 ANAMIKA · #11425 KARTIK JINDAL · #11488 SUBASINI PADHAN · #11427 VIJAY KUMAR MISHRA), Actions = ⓘ · "Open →".
- Extra rows: **19** Customers allocated to you · **3** Your customers now overdue.
- PERIOD row · **Your decisions** (8 tiles, no "Calls made") · **Your decision outcomes** · **Your borrowers** (15 tiles) · **Team** (credit executives only: Kabir Singh · Neha Gupta · Lalit Kumar · E2E Staff …).

### 3.3 Credit Executive

- Hero label **"Leads to decide"**, figure = files assigned to me. Draw two states: **(a)** figure **1**, one-row table #11489 VIJAY RAJPAL POHAAL · 08 Oct 2026, 09:59 pm · … · Actions `[ ✓ Sanction ] [ ✕ Reject ] [ Mark pending ]`; **(b)** figure **0**, grey-50 box *"You're all caught up — nothing waiting on you right now."* and, for an admin or head, a second 9.6px muted line *"Assign a file to yourself in Credit Head mode to pick it up."*
- Sections: Your decisions · Your decision outcomes · Your borrowers (customers assigned to me / decided by me). No Team.

### 3.4 Disbursement Head · Accountant · Telecaller

Exactly the base-spec dashboard for that role (hero "Approved loans to release" / "Repayments to verify" / no hero for Telecaller; Telecaller shows "Your decisions" with "Calls made" and "Your borrowers").

### 3.5 Collection Head

- Hero label **"Settlements awaiting your approval"**, figure **2**; extra rows **2** Settlements to approve (→ Settlements) · **3** Collections payments to approve (→ Live applications) · **19** Customers allocated to you · **3** Your customers now overdue.
- Sections: PERIOD · Your decisions · **Collections desk (9 tiles)**: Open cases 28 · By DPD bucket (stacked bar) · Outstanding (your cases) ₹4,10,900 · Recovered by you ₹1,05,200 · Recovery rate 26% · Awaiting validation 3 · Settlements proposed 4 ("2 approved · 1 rejected") · **Settlements you approved 2** · **Conceded ₹18,500** · Your borrowers · Team (collection executives: Sana Khan · Dharmendra Rajput · Srishti Jaiswal · Ajeet Tamrakar · Abhishek Pandey · Anamika Roy).

### 3.6 Collection Executive

- Hero label **"Open collection cases"**, figure **3** (= cases assigned to me); extra row **3** Your open collection cases → Live applications. Empty state (admin/head with nothing assigned): *"No cases are assigned to you. Assign one to yourself in Collection Head mode."*
- **Collections desk (7 tiles)**: Open cases 3 · By DPD bucket (1–7 DPD 2 · 8–30 DPD 1) · Outstanding (your cases) ₹21,350 · Recovered by you ₹6,050 · Recovery rate 22% · Awaiting validation 1 · Settlements proposed 1 ("0 approved · 0 rejected") · Your borrowers.

---

## 4. Live applications per working role (`/staff/applications`)

Page header **"Live applications"** with the working-role badge pill (navy-tint) beside the title. The search box + date filter row stays for every pipeline role; Telecaller sees the lock notice *"Your role has no step in the loan pipeline."* Panels are the base-spec QueueTable panels (navy header, zebra rows, ⓘ + "Open →"). The admin **"Log payment"** button appears on "Awaiting repayment" rows only in the Admin role.

### 4.1 Credit Head — assign only

Only the **Credit workbench**:

- Group **"Unallocated (4)"**: rows #11462 ANAMIKA · #11425 KARTIK JINDAL · #11427 VIJAY KUMAR MISHRA · #11330 SAVITHA. Checkbox column present. Row Actions: `[ Assign ▾ ]` (outline; executive picker: Neha Gupta · Kabir Singh · Lalit Kumar · Anamika Roy, **ACTIVE only**) · `[ Assign to me ]` (outline) · `[ ✕ Reject ]` (red). Bulk bar when ticked: **"2 selected · \[Assign\] \[Reject\] \[Clear\]"**.
- Group **"Assigned to me (1)"**: #11489 VIJAY RAJPAL POHAAL · `[ Reassign ▾ ]` · `[ ✕ Reject ]` and a 9.6px muted hint *"Switch to Credit Executive to decide."*
- Groups **"Neha Gupta (2)"**, **"Kabir Singh (1)"**, **"Other assignees (3)"**: `[ Reassign ▾ ]` · `[ ✕ Reject ]`.
- **No Sanction, no Mark pending anywhere.**

### 4.2 Credit Executive — my files, decide

- Panel **"Credit review — accept, reject or park"** (ⓘ as base). Rows = **only files assigned to me** (server-scoped): a real executive e.g. 3 rows; Meera working as Credit Executive exactly the files she assigned to herself (1 row: #11489). Actions: `[ ✓ Sanction ]` (green; dialog: amount + repayment date + remarks) · `[ ✕ Reject ]` (red; prompt "Why (staff-only)") · `[ Mark pending ]` (outline; prompt "What you're waiting on"). No Assign/Reassign, no checkboxes.
- Panel **"Sanctioned — borrower completing their journey"**, read-only, my files only.
- Empty state: **"No files are assigned to you. Assign one to yourself in Credit Head mode."**

### 4.3 Disbursement Head

Three panels as base (fast-track / standard / failed) with the row input *"Transaction id"* + `[ Release ]` green + `[ Fail ]` red; bulk reject bar. Unchanged. Sample: **Standard disbursement** (1: #11488 SUBASINI PADHAN).

### 4.4 Accountant

**Repayments to verify** (borrower, loan, amount, proof link, `[ ✓ Verify ]` green `[ ✕ Reject ]` red; sample count 4) · **Collections payments to validate** (`[ Validate ]` / `[ Reject ]`; 1) · **Awaiting repayment** (ACTIVE 79 | OVERDUE 56) · **Closed** (collapsed, 10). Unchanged.

### 4.5 Collection Head — assign + approve

- **Collections payments to approve** (3 rows raised by executives: Sana Khan ₹2,000 cash · Srishti Jaiswal ₹5,000 UPI · Ajeet Tamrakar ₹1,500 UPI) with `[ Approve ]` green / `[ Reject ]` red. A row raised by the signed-in person shows *"You raised this — another head must approve"* (SoD).
- **Awaiting repayment** (ACTIVE | OVERDUE): each row ends with an officer picker `Assign to [ — Unallocated ▾ ]` listing **active** collection executives **plus "Assign to me"**, and a `[ Save ]` icon button; checkbox column + bulk bar **"3 selected · \[Assign officer ▾\] \[Clear\]"**. No "Start collections", no "Log interaction", no "Record payment".

### 4.6 Collection Executive — my cases, work them

- **Awaiting repayment** limited to loans whose case is assigned to me (3 rows: #309 ANJALI VERMA 28 Sep 2026 +11d · #298 ROHIT BANSAL · #302 DEEPA KRISHNAN). Row actions: `[ Start collections ]` (outline, only if no case yet) or `[ Log interaction ]` (outline) + `[ Record payment ]` (outline). No picker, no checkboxes.
- Empty state: *"No cases are assigned to you yet."*

### 4.7 Telecaller

Lock notice card: *"Your role has no step in the loan pipeline. Work your leads from Telecalling."* + link.

---

## 5. Customer pop-up per working role

Same 14-tab dialog as base spec §3 on Vijay Rajpal Pohaal (#11489). Everything is identical except the **header action row** (row 2 of the header) and a handful of buttons inside tabs. Draw the header action row as variant strips on one frame (Frame 22).

### 5.1 Header action row by working role and file status

| File status | Credit Head | Credit Executive | Disbursement Head | Any other role |
| --- | --- | --- | --- | --- |
| Kyc Pending | `[ Assign ▾ ]` `[ Assign to me ]` `[ ✕ Reject ]` | "Not your step" | "Not your step" | "Not your step" |
| Credit Exec Pending | `[ Reassign ▾ ]` `[ ✕ Reject ]` + hint *"Switch to Credit Executive to decide"* | `[ ✓ Sanction ]` `[ ✕ Reject ]` `[ Mark pending ]` (only if assigned to me; otherwise muted *"Assigned to Neha Gupta"*) | "Not your step" | "Not your step" |
| Sanctioned | `[ ✕ Reject ]` | `[ ✕ Reject ]` | "Not your step" | "Not your step" |
| Disbursement Pending / Failed | "Not your step" | "Not your step" | input *"Transaction id"* `[ Release ]` `[ Fail ]` / `[ ↻ Retry ]` | "Not your step" |
| Active / Overdue / Closed | empty | empty | empty | empty |

**Admin additions (Admin working role only):** the row also carries `[ ⚡ Force to disbursement ]` (gold) on a Sanctioned file, `[ Edit customer ]`, `[ Cancel ]` (danger outline) and `[ Export ]`, to the right of the role's own buttons and separated by a 1px divider. When an admin has no role button at that status, the row shows "Not your step" followed by these.

"Not your step" = 9.6px muted text in the action row.

### 5.2 Tab-level deltas

- **Overview**: the focus cards follow the working role — Credit Head/Executive show KYC + Credit; Disbursement Head shows Disbursement; Accountant shows Disbursement + Amount due; Collection roles show Amount due; Telecaller shows none. References card: pencil (edit) in the Admin role only.
- **Verifications**: check cards always visible. `[ ↻ Retry ]` in the Admin role only; `[ Override ]` `[ Remind borrower ]` `[ Send link ]` only in **Credit Executive**. Credit Head and the rest: read-only cards.
- **Documents**: Upload area in Credit Head and Credit Executive and Admin; red "Delete" in the Admin role only.
- **Personal**: Owner picker (Assign to …) in Credit Head, Collection Head and Telecaller roles; hidden in executive roles.
- **Loan applications**: "Cancel" in the Admin role only.
- **Calls & remarks**: the "Log call" form is visible in Credit Executive, Collection Executive and Telecaller; Heads see the list only. Remarks form everywhere.
- **Skip Tracer**: "Find contacts" in Collection Head; others see the plain notice *"Skip tracing is run by the Collection Head."*
- **Audit log / Customer activity**: see §7.

---

## 6. Collections pages per working role

### 6.1 DPD buckets (`/staff/collections?bucket=…`)

Bucket chips across the top (Upcoming · 1–7 · 8–30 · 31–60 · 61–90 · 90+) with counts.

- **Collection Head**: full worklist table (`S.No. · Loan · Customer · Mobile · DPD · Bucket · Outstanding · Officer · Opened · Credit exec · Disbursed by · Actions`). Officer column shows an inline select `[ — Unallocated ▾ ]` incl. "Assign to me"; checkbox column + bulk bar **"5 selected · \[Assign officer ▾\]"**. Row action "Open →".
- **Collection Executive**: same table, rows = only my cases (server-scoped, real executives included); Officer column shows the plain text "You"; no checkboxes, no bulk bar. Empty bucket copy: *"No cases assigned to you in this bucket."* The **Upcoming** bucket is empty with the note *"Pre-due loans are watched by the Collection Head."*

### 6.2 Settlements (`/staff/collections/settlements`)

Table `Loan · Borrower · Outstanding · Proposed amount · Proposed by · Status · Actions`.

- **Collection Head**: all settlements; `PROPOSED` rows show `[ Approve ]` green / `[ Reject ]` red, except rows proposed by the signed-in person which show *"You proposed this — another head must decide"*. No "Propose" button.
- **Collection Executive**: only settlements on my cases; a `[ + Propose settlement ]` button (navy) opens the proposal form (amount, reason, hardship plan); `PROPOSED` rows read *"Awaiting Collection Head"*.

### 6.3 Case page (`/staff/collections/{loanId}`)

Left: loan summary + amount-due calculation. Right rail:

- **Collection Head**: **Assign card** (select incl. "Assign to me" + Save); interaction list (read-only); payments list with `[ Approve ]` on PENDING_HEAD rows; settlement card with Approve/Reject. No log/record/propose forms.
- **Collection Executive** (own case): read-only line **"Officer: you"**; **Log interaction** form (Type: Outbound/Inbound/Visit · Outcome: Promise to pay/No answer/Refused/… · Promise date · Notes · `[ Log ]`); **Record payment** card (amount, method, reference, proof upload, `[ Record ]`); `[ Propose settlement ]`.
- **Collection Executive on another officer's case** (opened by link): the page body is replaced by a muted white card with a lock icon, **"This case is assigned to another officer."** (server error `CASE_NOT_ASSIGNED`), and a "Back to my cases" link.
- The `[ Log payment ]` correction button appears in the Admin role only.

---

## 7. Audit trail with the working role

Wherever an actor line is rendered (pop-up **Audit log**, **Customer activity**, Journey stepper meta line, dashboard "Oldest waiting"), an action taken in a working role other than the person's real role carries it in brackets after the role:

- `by Meera Krishnan · ADMIN (as Credit Executive) · 09 Oct 2026, 11:02 am`
- `by Priya Nair · CREDIT_HEAD (as Credit Executive) · 09 Oct 2026, 03:14 pm`
- Working as the real role is unchanged: `by Neha Kapoor · CREDIT_EXECUTIVE · …`

Style: same 9.6px/8.8px muted line; the bracket text is not emphasised. In the **Audit log** tab add a sample entry: `BadgeCheck` success **Approved** CREDIT_EXEC_PENDING → SANCTIONED · by Meera Krishnan · ADMIN (as Credit Executive) · 09 Oct 2026, 11:02 am · note "Sanctioned ₹6,000 · due 10 Nov 2026".

---

## 8. Blocked and empty states (plain notices, no auto-switch)

- Stage action hidden by the working role → the action row shows **"Not your step"**.
- Page not in the role's nav but opened by link (e.g. a notification to the Verification Dashboard in Credit Head) → the NoAccessNotice card: *"This page isn't available in your current role."* (shield icon, muted, centred, white card).
- Credit Executive with nothing assigned → *"No files are assigned to you. Assign one to yourself in Credit Head mode."*
- Collection Executive with nothing assigned → *"No cases are assigned to you yet."*; another officer's case → *"This case is assigned to another officer."*

---

## 9. Frames to add to the deliverable

17. **Admin dashboard, role menu open** (§1.2) over the base Frame 16, with the closed-pill detail inset (§1.1) incl. the avatar tooltip.
18. **Sidebar per working role** — eight 240px columns side by side (§2), the last being Admin.
19. **Live applications — Credit Head** (§4.1) incl. the bulk bar with 2 selected.
20. **Live applications — Credit Executive** (§4.2), one-file state plus the empty state.
21. **Dashboard — Admin role** with the Admin overview as main content (§3.1).
22. **Pop-up header action rows** — the §5.1 matrix as strips, plus the admin additions.
23. **Pop-up Verifications tab** — Credit Executive (Override/Remind/Send link) beside Credit Head (read-only; admin Retry only).
24. **DPD buckets** — Collection Executive beside Collection Head (§6.1).
25. **Case page right rail** — Collection Executive vs Collection Head (§6.3), plus the "assigned to another officer" state.
26. **Audit log entry** with "(as Credit Executive)" (§7).
27. **Dashboard empty states** — Credit Executive (§3.3 b) and Collection Executive (§3.6).
28. **Toast** "Now working as Credit Executive" after a switch (§1.3).
