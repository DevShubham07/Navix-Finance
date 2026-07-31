# Admin console — customer identity on every application row + a unified application detail modal

> Implementation plan. Status: **awaiting approval / not yet implemented.**
> Companion docs: [`CLAUDE.md`](CLAUDE.md) (§7 roles, §9 loan math, §11 API surface),
> [`DEMO_WALKTHROUGH.md`](DEMO_WALKTHROUGH.md).

## Context

Two problems with the staff console as it stands today, both visible while recording the walkthrough:

1. **Application rows are anonymous.** Every queue row reads `Application #4  customer #9000013` —
   two opaque numbers. A reviewer cannot tell *who* they are about to approve without opening the
   row. The customer's name and mobile are already in the database on `customer_profile`, and the
   queue endpoints already join that profile (they pull the credit score / ★ rating off it), so the
   data is one field away.

2. **"Open" navigates away to a page.** `AppRow`'s Open button is a `<Link>` to
   `/staff/credit/{id}`, a full page that loses the reviewer's place in the queue. The
   `CustomerDetailDialog` reachable from `/staff/customers` proves the better pattern already exists
   in this repo — a wide, tabbed, "continued context" popup that keeps the list behind it.

**Outcome:** every application row identifies the human on it, and Open raises a single unified
application popup that carries the whole journey (identity, verifications, documents, credit,
loans, audit trail, remarks) *and* the stage's maker-checker action — so a reviewer never leaves
the queue.

**Confirmed decisions:**
- `frontend/src/app/staff/credit/[applicationId]/page.tsx` is **deleted entirely**; every entry
  point is rewired to the modal.
- The **"Journey" drawer button stays** alongside Open — quick peek vs. deep dive.
- Row header keeps `Application #N` as the title:
  `Application #4  customer #9000013 · Aditya Patel · 9819000012`.

---

## Part A — Name + mobile on every application row

### A1. Backend: two fields on `ApplicationView`

`backend/navix-loan/src/main/java/com/navix/loan/dto/ApplicationDtos.java:40-74`

Add `String customerName, String customerMobile` to the record. The enriched factory
`ApplicationView.of(LoanApplication a, CustomerProfile p)` **already receives the profile** and
already reads `p.getBureauScore()` / `getCreditStarRating()` / `getCreditRecommendation()` off it —
`p.getFullName()` and `p.getMobile()` sit right there. Populate them exactly like the credit
headline, null-guarded on `p`.

This is deliberately **only** on the `of(a, p)` path:
- `ApplicationController.enrich()` (`:317-323`) is the sole caller that passes a profile, and it
  backs both `GET /applications?status=` (`:76`) and `GET /applications/credit-queue` (`:83`) —
  i.e. every staff queue, plus the dashboard (which calls the same `listByStatus`/`creditQueue`).
- `GET /{id}` (`:109`) and `GET /mine` (`:97`) use the borrower-safe `of(a)`, so name/mobile stay
  **null on every borrower-facing read**. That is the existing PII boundary; do not widen it.

Before editing, `grep` for `new ApplicationView(` — the record constructor should have exactly one
call site (inside `of`), but backend tests may construct it positionally and would need the new
components.

### A2. Frontend type

`frontend/src/lib/api/applications.ts:42-59` — add to `ApplicationView`:

```ts
customerName?: string | null;
customerMobile?: string | null;
```

**Optional on purpose.** The borrower tree renders this same interface
(`(borrower)/loans/page.tsx:121`, `(borrower)/dashboard/page.tsx:256`,
`(borrower)/loan/status/page.tsx:67`) and must never render these fields.

### A3. The row itself — one edit covers ~15 queue panels

`frontend/src/components/staff/pipeline/app-row.tsx:43-46`. Append name and mobile to the existing
title line, after the customer id, each guarded on presence:

```
Application #4  customer #9000013 · Aditya Patel · 9819000012
[Kyc Approved]  Requested ₹11,200  [CIBIL 778 ★★★★ 4.0]
```

`AppRow` is rendered by `QueuePanel` (`pipeline/status-queue.tsx:130`) plus
`applications/page.tsx:210` and `:264`, so this single change lands on **applications ·
kyc-approvals · kyc-review · credit/queue · disbursement · accounting**. Truncate the name
(`max-w-*` + `truncate`) so a long name can't push the action cluster off a narrow viewport —
`admin/all-applications/page.tsx:146-152` is the existing precedent for that treatment.

### A4. The hand-rolled rows that bypass `AppRow`

- `frontend/src/app/staff/dashboard/page.tsx` — `WorkHero` (`:485-486`) and `PendingActionRow`
  (`:564-568`, currently titled `Customer #{customerId}`). Both consume the enriched queue, so the
  fields are already there; the `aria-label`s at `:496` and `:560` should name the customer too.
- `frontend/src/components/staff/application-journey.tsx:107` — already resolves `fullName` via
  `staffApi.getProfile`; add the mobile beside it.
- `frontend/src/app/staff/verifications/page.tsx:280-286` and `:239-243` — already show
  `borrowerName`, no mobile. Add `String borrowerMobile` to `VerificationOverviewRow`
  (`ApplicationVerificationService.java:117-121`), populate from `p.getMobile()` at `:1100-1104`,
  include it in `overviewMatches` (`:1113`) so search covers it, and mirror the field in the TS type.

**Already correct, no change:** `admin/all-applications` (Name + Mobile columns + 27-col export),
`staff/customers` (Mobile column), collections/transactions surfaces (`borrowerName` via
`LoanSummary`).

---

## Part B — The unified application detail modal

### B1. New component

**`frontend/src/components/staff/application-detail-dialog.tsx`**

```ts
export function ApplicationDetailDialog({
  applicationId,          // null = closed
  onClose,
}: { applicationId: number | null; onClose: () => void })
```

Follow `CustomerDetailDialog`'s "continued context" contract (`customer-detail-dialog.tsx:37-45`):
open state is derived from `applicationId != null`, and the active-tab state lives **above** the
data so it survives switching rows.

**Shell:** `<Dialog open onClose className="!max-w-5xl !w-[min(72rem,96vw)]">`. The `!` prefixes are
load-bearing — `globals.css:531` sets an un-layered `.modal { max-width: 460px }` that outranks plain
utilities (documented at `customer-detail-dialog.tsx:65-67`). Wider than the customer dialog's
`4xl` because it carries the journey stepper.

**Header (always visible, above the tabs):** `{fullName} — Application #{id}`, subline
`#{customerId} · {mobile} · PAN {pan} · risk {x}`, the status pill, `<CreditBadge>`, and — pinned
right — **the stage's maker-checker action cluster**. Lift `actionFor(app)` verbatim out of the
deleted page (`credit/[applicationId]/page.tsx:34-55`); it already dispatches status →
`KycActions` / `ReviewActions` / `AssignActions` / `ExecActions` / `HeadActions` /
`DisbursementActions` / `AccountantActions` from `@/components/staff/live-pipeline`. Those
components carry their own `ActionGate` permission checks and call `useRefreshAfterAction()`
(`pipeline/hooks.ts:87-98`), which invalidates `staff-queue` / `staff-events` /
`staff-application` / dashboard keys — so acting inside the modal refreshes the queue behind it
for free.

**Tabs** (`Tabs` from `@/components/ui/tabs` — note it is *not* in the `ui` barrel, import direct):

| Tab | Content | Source |
|---|---|---|
| Basic details | Identity & profile + Verification & credit + Salary & eligibility cards | `staffApi.getProfile(id)` → `ProfileView` (has every field in the screenshot) |
| Journey | `JourneyStepper` (nodes open `StageDetailDialog`), fast-track chip, then the `CostCard` | `deriveJourney(app, events)`; `CostCard` lifted from the deleted page (`:62-100`) |
| Verifications | `VerificationChecksPanel` — progress bar, per-check cards, **Manual override** | existing `verification-checks.tsx`, unchanged |
| Documents | Doc list with View/Download, admin upload/delete | existing `DocumentsTab` (see B2) |
| Past details | `LoanHistory` + the customer's other applications / loans / payments | `customersApi.get(app.customerId)` |
| Audit log | `EventTimeline events` | `staffApi.events(id)` |
| Remarks | existing remarks list + add-remark box | existing `RemarksTab` (see B2) |

**Queries** — reuse the established keys so the modal shares cache and invalidation with the
drawer, the step popup and the queues: `["staff-application", id]` (keep the page's
`refetchInterval: 8000` — the only live element), `["staff-events", id]`, `["credit-brief", id]`,
`["staff-profile", id]`, `["staff-docs", id]`, `["staff-verifications", id]`. Gate the per-tab
queries on the active tab so opening the modal doesn't fan out seven requests at once.

**RBAC:** reuse `REVIEW_PERMS` + `hasPermission` exactly as `CustomerReview` does
(`pipeline/customer-review.tsx:30`) to gate the PII-bearing tabs, falling back to `NoAccessNotice`.
Note the repo has **two** role sources — `useStaffMe()` (everything on the credit path) and
`useStaffSession()` (the customer dialog). Standardise this component on `useStaffMe()`, matching
the components it is absorbing.

### B2. Extract the shared pieces first (do this before B1)

`Section`, `KV`, `Bool`, `DocumentsTab` / `DocRow` / `AdminUpload`, and `RemarksTab` are currently
**module-private** inside `customer-detail-dialog.tsx` (`:278-439`, `:488-542`, `:581-607`).
`DocumentsTab` and `RemarksTab` are already application- and customer-scoped respectively, so they
drop straight into the new modal.

Move them to **`frontend/src/components/staff/detail-parts.tsx`** and have both dialogs import from
there. No behaviour change — a pure extraction, done as its own commit so the diff stays readable.

While extracting, replace `customer-detail-dialog.tsx`'s local `CostBreakdown` (`:188-206`, which
hardcodes `PROCESSING_FEE_RATE = 0.1` / `GST_RATE = 0.18` / `DAILY_INTEREST_RATE = 0.01` at
`:33-35`) with the shared `LoanBreakdown` / `ProjectedCostBreakdown` from
`@/components/staff/loan-breakdown`, backed by `buildCostBreakdown` in `@/lib/calc/loan-math`. Two
divergent copies of the money math is a real hazard given CLAUDE.md §9 — one engine, integer paise.

### B3. Rewire every entry point, then delete the page

- **`app-row.tsx:60-66`** — `<Link href={/staff/credit/${app.id}}>` becomes a `<button>` setting
  local `openDetail` state, with `{openDetail && <ApplicationDetailDialog …/>}` rendered beside the
  existing `{showJourney && <ApplicationJourney …/>}` (`:84-90`). Per-row conditional mounting
  matches the Journey button's existing pattern — no state lifting needed.
- **`application-journey.tsx:136-144`** — the drawer footer's `Open full detail` link points at the
  deleted route. Give `ApplicationJourney` an optional `onOpenDetail?: () => void`; when provided,
  render a button that closes the drawer and opens the modal; when absent, omit the footer.
  `AppRow` passes it.
- **Delete `frontend/src/app/staff/credit/[applicationId]/page.tsx`.** `/staff/credit/queue`
  survives untouched (nav item `staff-shell.tsx:69`, dashboard role hrefs
  `dashboard/page.tsx:85-86`, e2e `rbac.spec.ts:9,16`).
- **`frontend/scripts/mobile-shots.mjs:76`** — `page.locator('a[href^="/staff/credit/"]')` was
  reaching the detail page and will now only ever match the queue nav link. Repoint it at the row's
  Open button.

### B4. Modal-stacking caveat

`components/ui/dialog.tsx` is hand-rolled: **no portal, no focus trap, no body-scroll lock**, and
`.modal-overlay` is a flat `z-index: 200` (`globals.css:529`). `StageDetailDialog` already
compensates manually — `useFocusTrap` + a **capture-phase** Escape handler so it closes before its
parent (`stage-detail-dialog.tsx:161-191`) — which is exactly what lets it stack over the Journey
drawer today. The new modal opening `StageDetailDialog` (Journey tab) and `LoanDetailDialog` (Past
details tab) is the same shape, so it should work; verify by hand that **Escape closes only the
top-most layer** and that focus doesn't escape behind the overlay.

---

## Verification

Stack runs locally on backend `:8090`, frontend `:3000`, Postgres `:5433` (`.\scripts\run-demo.ps1`).
Sign in at `/staff/login` as `meera.krishnan@navix.example` / `Admin@12345`.

1. **Backend:** `cd backend && ./mvnw -pl navix-loan test` (JDK 21 — a Java 17 `JAVA_HOME` fails
   with "release version 21 not supported"). Then rebuild + restart via
   `.\scripts\run-demo.ps1 -Rebuild`.
2. **Payload:** `GET /api/staff/applications?status=KYC_APPROVED` returns `customerName` +
   `customerMobile` populated; `GET /api/applications/mine` as a **borrower** returns them `null`.
3. **Frontend:** `cd frontend && npx tsc --noEmit && npm run lint` — the documented way to verify
   this frontend (`npm run build`'s static-prerender step fails at `/staff/admin/staff` on a clean
   checkout, and running it while `npm run dev` is up corrupts `.next`).
4. **Rows:** name + mobile visible on applications, kyc-approvals, kyc-review, credit/queue,
   disbursement, accounting, dashboard, verifications. Check a long name doesn't break the layout.
5. **Modal:** Open from a queue row → header identity correct, all seven tabs load, the stage
   action is present and *works* (approve one KYC application and watch the queue behind the modal
   drop the row). Journey tab → click a stage → `StageDetailDialog` opens above; Escape closes only
   that. Journey drawer's footer button hands off to the modal.
6. **Route gone:** `/staff/credit/4` 404s; `/staff/credit/queue` still loads.
7. **No PII leak:** sign in as a borrower, confirm `/dashboard`, `/loans`, `/loan/status` render
   unchanged.
8. **Re-run `.\scripts\seed-demo-data.ps1 -Verify`** — all 31 surfaces must still PASS.

## Risks

- **Record-component addition** is positional in Java; any test constructing `ApplicationView`
  directly breaks at compile time. Cheap to find, but check before assuming a clean build.
- **Deleting the page breaks bookmarks** to `/staff/credit/{id}` — accepted, per the decision above.
- **The modal is a big component.** Keep it a thin shell that composes the *existing*
  `VerificationChecksPanel` / `CreditProfileCard` / `LoanHistory` / `JourneyStepper` /
  `EventTimeline` rather than re-implementing any of them; the only genuinely new code is the shell,
  the header, and the tab wiring.
- **Backend rebuild is mandatory** for Part A, and `run-demo.ps1` forces `mvnw clean install` for a
  reason: without `clean`, stale `.class` files get repackaged and boot dies on Hibernate schema
  validation.
