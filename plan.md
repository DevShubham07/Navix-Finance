# Staff CRM revamp — segmented customers, ownership, call logs, role-useful nav & dashboard

## Context

The reference CRM (screenshots) organises staff work around the **customer**, not the application:
a left sidebar that classifies customers into status segments, a per-customer detail page with
tabs (Personal / Employment / Bank Accounts / Credit Report / Documents / Loan Applications /
Call Logs / Audit Logs), customer→staff ownership with an "Unallocated" queue, and call logging.

DhanBoost today is organised around the **application aggregate**. `/staff/customers` is a flat
searchable table; the detail page is an untabbed 2-column card grid; and nothing lead-, ownership-
or call-shaped exists in the schema at all. Non-admin roles also see a near-empty sidebar (3–4
links) and a dashboard that shows an empty queue for roles with no pipeline items.

**Outcome:** staff get a customer-first console — segmented navigation, a personal book of business,
tabbed customer records, and call logging — built on the existing lifecycle rather than on invented
CRM concepts.

### Scope decisions (settled)
- **In:** segmented sidebar, tabbed detail page, customer ownership + Unallocated, customer call logs,
  fuller per-role nav, role-useful dashboard.
- **Out:** a separate pre-customer Lead entity / Lead Management pipeline, Call Requests inbox,
  Global Search, bulk allocation.
- Segments map to **our** lifecycle, not the reference's BRE/soft/hard rejection taxonomy.
- Customer list filtering stays **client-side**; `CustomerService.list()` is not rewritten into a
  paged query.

### Two defects found during exploration (both verified, both fixed in Phase 0)
1. **`CustomerController` has no role guard on any of its 8 handlers.** `CustomerService` guards only
   `updateProfile` and `deleteCustomer`. A **borrower** JWT hitting `GET /api/customers/{anyId}`
   receives another person's full profile, PAN, mobile, credit score, loans and payments. The BFF's
   staff-cookie separation is the only thing hiding this — a proxy, not a trust boundary.
2. **`deleteCustomer()` never deletes `borrower_mobile`** (table added in V40, after the cascade was
   written). A deleted customer's mobile stays claimed forever → they can never re-onboard.

---

## Phase 0 — Guards & cascade (S, backend, ~20 lines)

- `backend/navix-loan/.../controller/CustomerController.java` — add the private `requireStaff()`
  method copied verbatim from `ApplicationController.java:360` (rejects null / `BORROWER` /
  `ANONYMOUS`) and call it at the top of all 8 handlers.
- `backend/navix-loan/.../service/CustomerService.java` `deleteCustomer()` — add
  `DELETE FROM borrower_mobile WHERE customer_id = ?` to the cascade.

**Verify:** `curl` `/api/customers/1` with a borrower JWT → `FORBIDDEN_ROLE` (before: full PII).
Delete a seeded customer, re-onboard the same mobile → login succeeds instead of
`CUSTOMER_ID_COLLISION`. Existing `CustomerServiceTest` still green.

---

## Phase 1 — Migration + ownership + call logs (M, backend, ~250 lines)

### `V41__customer_owner_and_call_log.sql` (next version confirmed: V40 is latest)

**Where ownership lives:** `customer_profile` is per-*application* grain (a reborrow would fork the
owner) and `borrower_mobile` is scoped to the login-collision guard. Use a **new narrow table keyed
on `customer_id`**, copying V40's proven pattern. Sparse by design — *no row = Unallocated*, so the
segment falls out for free with no nullable-column scan and no backfill.

```sql
create table customer_owner (
    customer_id    bigint primary key,
    owner_staff_id bigint      not null,
    assigned_at    timestamptz not null default now()
);
create index idx_customer_owner_staff on customer_owner (owner_staff_id);

create table customer_call_log (
    id          bigserial primary key,
    customer_id bigint      not null,
    call_type   varchar(24) not null,   -- OUTBOUND | INBOUND | MISSED
    outcome     varchar(32) not null,   -- CONNECTED | NO_ANSWER | CALLBACK | REFUSED | WRONG_NUMBER
    callback_on date,
    notes       text,
    created_at  timestamptz not null,
    created_by  varchar(160),
    updated_at  timestamptz,
    updated_by  varchar(160)
);
create index idx_customer_call_log_customer on customer_call_log (customer_id, id desc);
create index idx_customer_call_log_callback on customer_call_log (callback_on) where callback_on is not null;
```
No FKs — the schema has none anywhere, and adding them here alone would break `deleteCustomer`'s
hand-rolled cascade ordering.

### Entities / repos (`backend/navix-loan/.../{entity,repository}/`)
- `CustomerOwner` — plain `@Entity`, **not** `BaseAuditEntity` (that class owns `@Id id`, which fights
  the natural PK). `CustomerOwnerRepository extends JpaRepository<CustomerOwner, Long>`, no custom methods.
- `CustomerCallLog` — **copy `CustomerRemark.java` verbatim**, swap `body` for
  `callType`/`outcome`/`callbackOn`/`notes`. `CustomerCallLogRepository.findByCustomerIdOrderByIdDesc`,
  mirroring `CustomerRemarkRepository`.

### DTOs — `dto/CustomerDtos.java`
- `CustomerSummary` gains `String loanStatus, Long ownerStaffId, String ownerName`.
  `loanStatus` = newest loan's `effectiveStatus(today)`. **This one field is what makes the whole
  segment map derivable client-side** — the application aggregate stays `ACTIVE` for the entire
  overdue window, so `latestStatus` alone can never say "Overdue". `list()` already loads every loan
  per customer → zero extra queries.
- `CustomerDetail` gains `Long ownerStaffId, String ownerName`.
- New: `AssignOwnerRequest(Long staffId)` (null = unallocate), `AddCallLogRequest`, `CallLogView`
  (mirror `RemarkView.of`).

### `CustomerService`
Inject `CustomerOwnerRepository`, `CustomerCallLogRepository`, `StaffDirectory` (already a
`navix-loan` dependency — used by `ApplicationFlowService`).
- `list(q)` — one `ownerRepository.findAll()` → `Map` before the loop; memoise staff names in a local
  `HashMap` (a dozen staff, not N+1). Add `loanStatus`. Leave the rollup otherwise alone, with:
  `// ponytail: whole-table rollup + client-side segmenting. Move to a paged indexed query when the`
  `// list stops fitting one response — same change as adding server-side segment filters.`
- `detail()` — populate the two owner fields.
- **`assignOwner(customerId, staffId)`** `@Transactional`, new. Guard: a private varargs
  `requireRole("CREDIT_HEAD","COLLECTION_HEAD")` with the ADMIN bypass the sibling services use
  (copy the idiom; do **not** extract a shared helper — none exists and this isn't that ticket).
  Validate assignee via `staffDirectory` + active, else `BusinessException("INVALID_ASSIGNEE")`.
  `staffId == null` → `deleteById`. Audit by reusing the existing private
  `logIfChanged(customerId, null, "owner", old, new)` — one line, and it surfaces in `changes()` and
  `activity()` for free. Return `detail(customerId)`.
- **`callLogs()` / `addCallLog()`** — verbatim copies of `remarks()` / `addRemark()`.
- `activity()` — emit a 4th entry type `CALL`.
- `deleteCustomer()` — add `customer_owner` + `customer_call_log` to the cascade.

### Controller
Three mappings: `POST /{customerId}/owner`, `GET|POST /{customerId}/call-logs`.

### Owner picker — reuse, do not add an endpoint
`GET /api/applications/credit-executives` already returns `StaffSummary[]` and is staff-readable
(it exists precisely because `GET /api/staff` is ADMIN-only). Add one optional param:
`@RequestParam(defaultValue = "CREDIT_EXECUTIVE") String role` → `staffDirectory.listActive(role)`.
Existing callers are unaffected; the CRM picker calls it twice and concatenates. Zero new endpoints,
zero new port methods.

**Verify:** app boots (V41 applies). `POST /api/customers/1/owner {"staffId":3}` → 200 as CREDIT_HEAD,
`FORBIDDEN_ROLE` as CREDIT_EXECUTIVE, `INVALID_ASSIGNEE` for an inactive id, row deleted for `null`.
`GET /api/customers` shows `ownerName` + `loanStatus`. `POST .../call-logs` → appears in `/activity`;
owner change appears in `/changes`. `/credit-executives?role=COLLECTION_EXECUTIVE` returns collectors.

---

## Phase 2 — API client types (S, ~40 lines)

`frontend/src/lib/api/applications.ts` — new fields on `CustomerSummary`/`CustomerDetail`,
`CallLogView` type, and `customersApi.assignOwner / callLogs / addCallLog`.

**No BFF work:** `frontend/src/app/api/staff/customers/[[...path]]/route.ts` is already a bare
catch-all exporting GET/PUT/POST/DELETE with no path restriction.

**Verify:** `npx tsc --noEmit` clean.

---

## Phase 3 — Segments + customers list (M, ~180 lines)

New **`frontend/src/lib/customers/segments.ts`** (not in the 1500-line API client — this is derived
domain logic with no fetch, imported by the list page, the nav config and the dashboard):

- `CustomerSegment` = `pending | review | approved | active | overdue | hold | rejected | closed |
  unallocated | all`, plus `SEGMENT_LABEL` and an ordered `SEGMENTS`.
- `segmentOf(c)` — **loan state outranks application status** (that's the whole reason `loanStatus`
  was added). Then by `latestStatus`:
  | Segment | From |
  |---|---|
  | `overdue` | loan OVERDUE/IN_COLLECTIONS, or app OVERDUE/DEFAULTED |
  | `active` | loan ACTIVE/DISBURSING, or app DISBURSED/ACTIVE |
  | `pending` | DRAFT, KYC_PENDING, PRE_APPROVED (also the `default`) |
  | `review` | REVIEW_PENDING, CREDIT_EXEC_PENDING, CREDIT_HEAD_PENDING |
  | `approved` | KYC_APPROVED, CREDIT_EXEC_APPROVED, CREDIT_HEAD_APPROVED, DISBURSEMENT_PENDING, ACCOUNTANT_PENDING |
  | `hold` | DISBURSEMENT_FAILED — we have no explicit hold state; this is the honest equivalent (parked, needs a human, not rejected) |
  | `rejected` | KYC_REJECTED, REJECTED |
  | `closed` | CLOSED, WRITTEN_OFF, CANCELLED |
- `inSegment(c, seg)` (`unallocated` = `ownerStaffId == null`) and `segmentCounts(rows)` — one pass.

Reference → ours, stated once: BRE Rejected + Hard Rejected → `rejected`; Soft Rejected → `hold`;
Partial/Pending → `pending`; Dormant → `closed`. We additionally split `review`/`approved`/`overdue`,
which the reference lumps together and which are the three states our staff actually work.

**`app/staff/customers/page.tsx`:** read `seg` from `useSearchParams()` (default `all`); a `.cal-preset`
pill row of segment chips with counts above the table (this is also the mobile affordance); new
**Owner** column (`ownerName ?? "Unallocated"`); `rows.filter(c => inSegment(c, seg))`; counts via
`useMemo(() => segmentCounts(rows), [rows])` so they reflect the current search. Keep the row click
opening `CustomerDetailDialog` (faster loop) but make the `#id` sub-label a real `<Link>` to the page.

**One check to leave behind:** `frontend/src/lib/customers/segments.test.ts`, ~8 asserts — overdue
beats a still-ACTIVE application, `DISBURSEMENT_FAILED → hold`, null status → `pending`, and
`segmentCounts(...).all === rows.length`. That's the routing logic; the rest of the page is markup.

**Verify:** `npx vitest run segments`. Click each chip; counts sum to All; a customer with an overdue
loan sits under Overdue while their application still reads ACTIVE; `?seg=unallocated` lists exactly
the ownerless customers; the export menu exports the filtered rows.

---

## Phase 4 — Nav (M, ~120 lines)

`frontend/src/components/staff/staff-shell.tsx` — extend `NavItem` with **one** optional field:
`sub?: { label: string; seg: CustomerSegment }[]`. Children carry a `seg`, not an href
(`${it.href}?seg=${seg}`), keeping the config declarative.

**Render with native `<details>`** — no state, no library, keyboard- and SR-correct out of the box;
`open={parentActive}`, `<summary>` styled as the existing nav row, chevron rotated via
`group-open:rotate-90`, children in a `border-l border-white/10` indented list.

Child active-state needs the query string, which `usePathname()` omits → use `useSearchParams()` in
`NavLinks` and **wrap `<NavLinks/>` in `<Suspense>`** at its call site, or Next 15 emits a CSR-bailout
build warning for every `/staff` page.

`MobileNavLinks` stays flat and simply ignores `sub` — the segment chips on the page itself already
cover mobile; 9 more pills in a horizontal scroller would be noise.

New/changed items:
```ts
{ label: "Customers", href: "/staff/customers", Icon: Contact, perm: "customer:view",
  sub: SEGMENTS.map((seg) => ({ label: SEGMENT_LABEL[seg], seg })) },
{ label: "Unallocated customers", href: "/staff/customers?seg=unallocated", Icon: UserX,
  perm: "customer:assign" },
```
Plus **Settlements** `collections:manage` → `collections:interact`: the page body is ungated and only
the approve button sits in a `PermissionGate`, so a Collection Executive currently can't see the
settlements they proposed. **Verification Dashboard stays `kyc:approve`** — that whole page is
wrapped in a `PermissionGate`, so un-gating the nav would just route people to a "no access" card.

New permission in `frontend/src/lib/auth/rbac.ts`: **`customer:assign`** → ADMIN, CREDIT_HEAD,
COLLECTION_HEAD (mirrors the backend guard). Allocation is a Head's job, but `customer:manage` is
ADMIN-only and also grants KYC-edit + hard-delete. Logging a call needs **no new token** — it reuses
`customer:view` (held by all 9 roles), matching `addRemark`, which has no token today.

Resulting sidebar — every role goes from 3–4 leaf links to 12–15 destinations without touching any
page's own gate:

| Role | Items |
|---|---|
| KYC_APPROVER | Dashboard, Live applications, **Customers ▸ 9**, Verification Dashboard |
| CREDIT_EXECUTIVE | Dashboard, Live applications, **Customers ▸ 9** |
| CREDIT_HEAD | + **Unallocated customers** |
| DISBURSEMENT_HEAD | + Referral payouts |
| ACCOUNTANT | + Transactions |
| COLLECTION_HEAD | + **Unallocated customers**, Settlements |
| COLLECTION_EXECUTIVE | + **Settlements** (new) |
| DEVELOPER | Dashboard, Live applications, **Customers ▸ 9** |
| ADMIN | everything + all 7 Administration items |

**Verify:** sign in as each of the 9 roles (`StaffRoleBar` makes this quick) and check the table
line by line; the group auto-opens on `/staff/customers` and the right child highlights per `?seg=`;
the mobile strip (< 1024px) is unchanged; `next build` emits no `useSearchParams` bailout warning.

---

## Phase 5 — Tabbed customer detail (L, ~500 new / ~250 deleted)

**Unify the dialog and the page onto one tab module.** New
`frontend/src/components/staff/customer-tabs.tsx` exporting `CUSTOMER_TABS: TabDef[]` and
`<CustomerTabBody tab detail customerId />`. Then:
- `app/staff/customers/[customerId]/page.tsx` renders `<Tabs>` + `<CustomerTabBody>` in the main
  column and **keeps** its right-hand admin rail (`AdminEditCard`, `BlocklistCard`,
  `DeleteCustomerCard` under `PermissionGate customer:manage`) — that rail is the page's reason to
  exist over the dialog.
- `components/staff/customer-detail-dialog.tsx` renders the same, and **deletes its private
  `BasicTab`/`PastTab`/`LogsTab`/`MoreTab`** (~200 lines). Net repo line count goes down. This is the
  precedent `detail-parts.tsx` already set ("so both dialogs compose the same tabs instead of
  drifting apart" — its own header comment).

| Tab | Content | Work |
|---|---|---|
| Personal Details | dialog `BasicTab` identity section + page `ProfileCard`, merged; **+ `OwnerCard`** (current owner + `Select` picker under `PermissionGate customer:assign`) | move |
| Employment | employer, employmentStatus, salaryBank, monthly/annual salary, salary %, increment %, `eligibleLimitPaise` — re-slice of the existing salary section | ~30 lines |
| Bank Accounts | **No bank-account entity exists in the schema.** Synthesize from what's real: `profile.salaryBank`, `pennyDropVerified`, the `PENNY_DROP` row from `staffApi.verifications(latestAppId)` (`StepResult.derived` carries account/IFSC/name-match), and each loan's `disbursalTxnRef`. Header note explains the provenance | ~60 lines, new |
| Credit Report | `<CreditProfileCard applicationId={latestAppId}/>` as-is + the dialog's credit KV block | ~0 |
| Documents | `<DocumentsTab/>` from `detail-parts.tsx`, unchanged | 0 |
| Loan Applications | dialog `PastTab` + page `ApplicationsCard` cancel action + `LoanDetailDialog` | move |
| Customer Call Logs | new `CallLogsTab` — copy `RemarksTab`'s query/mutate/invalidate skeleton, swap the textarea for `Select` type, `Select` outcome, native `<input type="date">` callback (shown only when outcome = CALLBACK), notes. Render `<RemarksTab/>` beneath it in the same tab | ~90 lines, new |
| Audit Logs | the dialog's existing `LogsTab` (`customersApi.activity`), now carrying `CALL` entries and `owner` changes. **Delete `ChangeHistoryCard`** from the page — `activity()` already merges `profile_change_log`, so it's a strict subset | move + delete |

Add `CALL` to the existing `TYPE_STYLE` map when `LogsTab` moves.

**Verify:** open a customer from a list row (dialog) and via deep link (page) — same 8 tabs, same
data; tab selection persists as you click through rows in the dialog (existing behaviour must
survive); as ADMIN the right rail shows edit/blocklist/delete, as CREDIT_EXECUTIVE it's absent but
all 8 tabs render; assigning an owner updates the list's Owner column and the Unallocated count;
a CALLBACK call appears in both Call Logs and Audit Logs.

---

## Phase 6 — Role-useful dashboard (S/M, ~100 lines)

Three surgical edits to `app/staff/dashboard/page.tsx`. `WorkHero`, `TrendsSection`, `PipelineBar`,
`PendingActionRow`, `ExtraActionRow` and `AdminTransactions` are all reused untouched.

1. **"My customers" becomes a `QueueExtra` for every role.** `fetchRoleQueue(role)` →
   `fetchRoleQueue(role, staffId)`; after the switch, filter `customersApi.list()` by
   `ownerStaffId === staffId` and push two extras: *Customers allocated to you* and *Your customers
   now overdue* (`loanStatus` OVERDUE/IN_COLLECTIONS) → `/staff/customers?seg=…&mine=1`. This is the
   core fix: CREDIT_EXECUTIVE and DEVELOPER, who see an empty queue today, now get a personal book of
   business with a live overdue count. Same `ponytail:` ceiling comment as the list page.
2. **`QUEUE` gains a DEVELOPER entry** ("Read-only oversight") so its hero stops falling into the
   empty `FallbackHero`; `FALLBACK_AREA` can then be deleted.
3. **ADMIN command centre = one new `SegmentBar`** (~40 lines), rendered when `isAdmin` between
   `PipelineBar` and `AdminTransactions`: `segmentCounts()` from Phase 3 rendered as `StatCard`s
   (from `staff-ui.tsx`) linking to `/staff/customers?seg=X`, Unallocated tinted amber when > 0.
   Admin then lands on: pipeline by status + book by segment + unallocated backlog + trends + money.

**Verify:** as CREDIT_EXECUTIVE with 0 pipeline items the hero is non-empty; DEVELOPER no longer
hits the fallback; as ADMIN the segment bar totals match the customers page "All" count exactly and
Unallocated links to the same set the nav item shows.

---

## Reuse inventory (don't rebuild these)

`PermissionGate` / `NoAccessNotice` (`@/components/staff/live-pipeline` barrel) · `PageHeader`,
`RefreshButton`, `StatCard`, `StageBadge` (`staff-ui.tsx`) · `Tabs`, `Select`, `Badge`, `Dialog`,
`Drawer`, `InfoTooltip` (`components/ui/`) · `Section`, `KV`, `Bool`, `DocumentsTab`, `RemarksTab`
(`detail-parts.tsx`) · `ExportMenu` · `paiseToINR` / `statusLabel` / `isLoanOverdue`
(`lib/api/applications.ts`) · `.navix-crm` density class, `.table-wrap` + `table.data`, `.kv-table`,
`.cal-preset` chips, `font-mono` on money (`globals.css`) · `StaffDirectory`, `logIfChanged`,
`CustomerRemark`/`RemarkView` shape (backend).

## Deliberately skipped

Bulk allocation · a Call Requests inbox · server-side `?owner=&segment=` filtering · Global Search ·
a Lead entity. Add the server filter the first time the customers response exceeds ~1MB — it's one
query method, and the `inSegment` call sites become query params. Add bulk allocation when someone
has to allocate more than ten customers in a sitting.
