# Ops console — UI/UX optimisation plan for the 15 most-used pages

> **Status:** proposal, awaiting approval / not yet implemented. Branch `claude/ops-ui-ux-optimization-dokoco`.
> Companion docs: [`CLAUDE.md`](CLAUDE.md) (§7 roles, §8 frontend architecture), [`ADMIN_CONSOLE_REVAMP_PLAN.md`](ADMIN_CONSOLE_REVAMP_PLAN.md)
> (the earlier "name on every row + unified application dialog" plan, whose Part A/B shipped and whose patterns this plan reuses).
>
> **How this was produced (2026-09-22).** One read-only observer agent per page inspected the page, its components, the
> `xxxApi` client, the BFF route, the Spring controller → service → repository chain and the Flyway migrations, and
> reported UI structure, data flow, loading/empty/error states, transitions, tables, friction and performance issues.
> A second, independent verifier agent per page then re-read every factual claim against the code and either confirmed,
> corrected or refuted it, and audited the backend for N+1 loops, missing pagination, over-fetching, duplicate calls,
> sequential calls and missing indexes. A third scan covered the shared layer (shell, primitives, tokens, motion).
> **Only claims that survived verification are in this document**, each with a `file:line` reference. Observer claims
> that were refuted are listed in Appendix A so nobody re-reports them. The `code-review-graph` MCP server was
> unavailable in the session (Windows-only executable); the graph was replaced by direct code reading.

---

## 0. Executive summary

The console is structurally sound: one design language, one table treatment (`.staff-data-table`), one query client,
role-aware queues, and — after the earlier revamp — identity on every application row and a unified application
dialog. What makes it feel slow, noisy and inconsistent is not the information architecture but a layer of small,
repeated gaps:

| Gap (verified) | Where it shows | Evidence |
|---|---|---|
| **No skeleton / empty / error / toast primitives** — 31 files inline `animate-pulse` blocks with four different fills, 13 files render a bare `Loading…` string, 35 files hand-roll an empty state with 11 wrapper variants, 50 files render `<p class="text-error-700">` with no retry, and there is **no toast or `aria-live` region anywhere** | every page | shared-layer scan; `frontend/src/components/staff/**`, `frontend/src/app/staff/**` |
| **Status colour is hand-rolled** — 23 files keep a private status→class map and 16 files inline `bg-*-100` pills; `ui/Badge` is used by 5 files; `StageBadge`/`KycStatusBadge` have 0 consumers | queues, ledger, telecalling, settlements, verifications | `frontend/src/components/ui/badge.tsx:4-28`, `frontend/src/components/staff/staff-ui.tsx:90-117` |
| **Table header never sticks; numbers are left-aligned** — `.staff-data-table` pins identity/action *columns* but has no `thead` sticky rule; money cells use `font-mono` (tabular via `tnum`) but `text-align:left` | every register | `frontend/src/app/globals.css:418-433` |
| **Row hover cannot work on even rows** — 8 files add `hover:bg-grey-50` to `<tr>` but the zebra colour is painted on `<td>` | customers, loans, all-applications… | `frontend/src/app/globals.css:422` |
| **Dialogs enter but never exit; Dialog has no focus trap** — `.modal-overlay.show` animates in (`fadeUp .25s`), unmount is instant; Drawer traps focus, Dialog does not | every modal | `frontend/src/components/ui/dialog.tsx:47`, `globals.css:586` |
| **Two competing period controls** — `PeriodPicker` (rounded-full, gold) vs `QueueDateFilter` (square, navy) | dashboard/performance/decisions vs queues/customers/loans/collections | `period-picker.tsx:21-24`, `queue-date-filter.tsx:106-108` |
| **Refresh is hand-rolled 26 times**; `RefreshButton` used by 2 pages | most pages | `staff-ui.tsx:38-57` |
| **Session is fetched twice per page load** — the shell's `useStaffSession` is a raw `fetch`, every `PermissionGate`/`ExportMenu` uses the React Query `['staff-me']` key | every page | `frontend/src/lib/auth/staff-session.ts:25-34,90-109`, `pipeline/hooks.ts:30-40` |
| **Polling is per-page and uncoordinated** — 8 s queue polls, 10 s dashboard, 20 s bell, 30 s sidebar worklist badge, 45 s verifications, 60 s ledger, 120 s telecalling, all fixed | every page | see §2.3 |
| **Five registers load the whole table and paginate in the browser** — applications queues, loans, collections worklist, telecalling, all-applications, my-decisions, settlements | the heaviest pages | §3 per page |

The plan therefore has two halves:

1. **A shared foundation (§2)** — ~12 small primitives and CSS rules (Skeleton, EmptyState, ErrorState-with-retry,
   Toast, sticky `thead`, right-aligned money, working row hover, dialog exit + focus trap, one status-badge map, one
   period control, one refresh button, one session query). Each is a few dozen lines, changes no workflow, and lifts all
   15 pages at once.
2. **Per-page polish (§3)** — small, page-local changes: which columns to promote, where to add a confirm step, which
   query to stop firing, which endpoint to paginate. Every backend/database recommendation is tied to a verified code
   path and is *incremental* (a `Pageable` parameter, a projection, an index) — nothing changes the state machine, the
   maker-checker rules, the BFF layout or the JWT model.

The Ops team keeps the same sidebar, the same queues, the same dialogs and the same actions. What they notice is that
pages stop blanking out on refresh, headers stay put, rows respond to the mouse, money lines up, actions confirm
themselves, and the heavy pages open in a fraction of the time.

---

## 1. Which 15 pages, and why

Chosen from `frontend/src/components/staff/staff-nav.ts` (the role-gated sidebar), the role → page mapping in
`CLAUDE.md` §7, page size/complexity, and which roles land on them daily.

| # | Page | Route | Primary roles | Why it is in the top 15 |
|---|---|---|---|---|
| 1 | Staff dashboard | `/staff/dashboard` | every role | landing page; 10–13 queries/role; the console's front door |
| 2 | Live applications | `/staff/applications` | Credit, Disbursement, Accountant, Collections | the one maker-checker console; 8 s polls |
| 3 | Customers | `/staff/customers` | every role | the CRM register; 22 columns, segments, bulk actions |
| 4 | Customer 360 | `/staff/customers/[customerId]` | every role; ADMIN corrections | the deep-dive page behind every row |
| 5 | Verification dashboard | `/staff/verifications` | Credit roles | KYC/provider desk with override/retry |
| 6 | Loans register | `/staff/loans` | Collection Head, ADMIN | the loan book; sortable register |
| 7 | Collections worklist | `/staff/collections` | Collection Head/Executive | DPD buckets, officer assignment, bulk assign |
| 8 | Collection case | `/staff/collections/[loanId]` | Collection roles | the per-loan workspace |
| 9 | Settlements | `/staff/collections/settlements` | Collection Head | approve/reject maker-checker |
| 10 | Transactions ledger | `/staff/accounting/transactions` | Accountant, ADMIN | company-wide money movement |
| 11 | Leads | `/staff/leads` | Telecaller, ADMIN | lead intake + disposition |
| 12 | Telecalling | `/staff/telecalling` | Telecaller, ADMIN | chase-up worklist + reminders |
| 13 | Staff performance | `/staff/performance` | Heads, ADMIN (self for others) | roster metrics |
| 14 | My decisions | `/staff/my-decisions` | every role | personal audit trail |
| 15 | All applications | `/staff/admin/all-applications` | ADMIN | the full register + unified dialog |

Not included (lower traffic or admin configuration): `admin/{staff,invites,blocklist,expenses,payment-settings,dsa,
leads,rejections,api-dashboard}`, `disbursement/referrals`, `dsa/*`, `leads/import`, `profile`, auth pages.

---

## 2. Shared foundation — the changes that lift every page

Everything below reuses existing tokens and classes. Nothing is renamed (`font-serif`, `gold`, `navy` stay as they
are — note that in the current build all three font families resolve to Inter and the `gold` token is emerald-valued,
`frontend/tailwind.config.ts:118-126,44-46`; the plan uses the tokens by *name* exactly as the pages already do).

### 2.1 Primitives to add (all under `frontend/src/components/ui/`, exported from `ui/index.ts`)

| Primitive | What it is | Replaces | Size |
|---|---|---|---|
| `<Skeleton variant="line\|stat\|row\|table" rows=n />` | one `animate-pulse` implementation with the console's `bg-grey-100`/`border-line` fill; `table` renders n zebra rows with the same 8px 14px cell rhythm so the skeleton has the shape of the table it replaces | 49 ad-hoc `animate-pulse` blocks (31 files), 30 bare `Loading…` strings (13 files) | ~40 lines |
| `<EmptyState icon title hint action? />` | the `px-5 py-8 text-center text-sm text-muted` line every page already writes, with an optional CTA | 35 files × 11 wrapper variants | ~30 lines |
| `<ErrorState error onRetry />` | `errMessage(error)` + a `Try again` button that calls `refetch()` | 50 files of `<p class="text-error-700">` with no retry | ~30 lines |
| `<Toaster/>` + `toast.success/error(msg)` | a tiny queue rendered in the shell with `role="status" aria-live="polite"`, auto-dismiss 4 s, `prefers-reduced-motion` respected; **no new dependency** | inline success strings that never dismiss (customer detail cards, telecalling `results[id]`, settlements) | ~80 lines |
| `<StatusBadge kind="application\|loan\|payment\|lead\|settlement\|verification" value/>` | **one** map from each backend enum to `ui/Badge` variants (the vocabulary the sidebar already uses: `success`/`warning`/`error`/`info`/`neutral`) | 23 private status→class maps; revive or delete the dead `StageBadge`/`KycStatusBadge` | ~60 lines |
| `<Money paise align="right"/>` | `paiseToINR` + `font-mono text-right whitespace-nowrap` (tabular digits already come from `globals.css:113`) | every money `<td>` written by hand | ~15 lines |
| `<TableToolbar>` | one flex row for search / period / segment chips / refresh / export with the existing class strings, so pages stop composing it ad hoc | 15+ ad-hoc toolbars | ~40 lines |
| `<ConfirmDialog title body confirmLabel tone/>` | a `ui/Dialog` preset for the one-click destructive actions (settlement approve/reject, bulk reminders) | none today | ~40 lines |

### 2.2 CSS rules to add (all in `frontend/src/app/globals.css`, scoped to the console — never global, per CLAUDE.md §8)

```css
/* Sticky header for every register. The page is the scroll container, so top:0 works;
   the navy background is already on thead th. */
.staff-data-table thead th { position: sticky; top: 0; z-index: 3; }
.staff-data-table thead .staff-sticky-identity,
.staff-data-table thead .staff-sticky-actions { z-index: 4; }

/* Money and counts: right-aligned tabular figures. */
.staff-data-table td.num, .staff-data-table th.num { text-align: right; font-feature-settings: "tnum" 1; }

/* Row hover that works on even rows too (the zebra is painted on <td>). */
.staff-data-table tbody tr:hover td { background: var(--grey-50); }
.staff-data-table tbody tr:hover .staff-sticky-identity,
.staff-data-table tbody tr:hover .staff-sticky-actions { background: var(--grey-50); }
.staff-data-table tbody tr[data-changed="true"] td { animation: rowFlash 1.2s ease-out; }

/* Dialog exit + reduced motion. */
.modal-overlay.closing { animation: fadeOut .18s ease forwards; }
@media (prefers-reduced-motion: reduce) {
  .modal-overlay.show, .modal-overlay.closing, .staff-data-table tbody tr[data-changed="true"] td { animation: none; }
  .btn:hover { transform: none; }
}
```

Plus two keyframes in `tailwind.config.ts` next to `fadeUp`: `fadeOut` (opacity 1→0) and `rowFlash`
(`background: var(--gold-50)` → transparent). Nothing else in the motion vocabulary changes.

### 2.3 Data-fetching hygiene (frontend, no backend change)

| Change | Why (verified) | Where |
|---|---|---|
| **One session query.** Make `useStaffSession` read through the `['staff-me']` React Query key (or seed it with `queryClient.setQueryData` after its fetch) | every page load hits `/api/auth/staff/me` twice: the shell's raw `fetch` cannot dedupe with the RQ hook used by 32 files; `CustomerOwnerPicker` even calls the raw hook per row (up to 50 fetches on the telecalling page) | `staff-session.ts:25-34,90-109`, `pipeline/hooks.ts:30-40`, `customer-owner-picker.tsx:30-34` |
| **Shared query keys for shared endpoints.** `staffApi.performance` is cached as `['staff-dashboard-performance']` on the dashboard and `['staff-performance']` on the performance page; settlements as `['staff-dashboard-settlements']` vs `['collections-settlements']` | navigating between them refetches identical payloads and a decision on one page leaves the other's count stale | `dashboard/page.tsx:53,304-309`, `performance/page.tsx:67-71`, `settlements/page.tsx:31` |
| **`placeholderData: keepPreviousData` on every keyed list query** | pages whose key changes on filter/period (performance, my-decisions, loans, verifications already has it) blank the table and flash **fabricated zeros in the stat tiles** on every period change | `performance/page.tsx:73,97-106` |
| **Reset page synchronously with the filter** | with `page` in the key and the reset in a `useEffect`, a filter change on page > 1 fires two requests and discards the first | `accounting/transactions/page.tsx:93-95,99` (same pattern in customers/leads) |
| **A polling policy.** Keep the intervals (they are product decisions) but (a) never poll hidden tabs — React Query already pauses interval refetches when the window is unfocused, so the "background tab hammers the server" fear is unfounded; (b) invalidate narrowly: logging a collections interaction currently invalidates `['collections-worklist']`, which the **sidebar badge on every page** observes | `staff-shell.tsx:120-131`, `collections/[loanId]/page.tsx:53-57` |
| **Route-level `loading.tsx` under `src/app/staff/`** rendering `<Skeleton variant="table"/>` inside the shell | there is none; the only navigation feedback is the 3 px `RouteProgress` bar and five different page-local `Suspense` fallbacks | `frontend/src/components/app/route-progress.tsx`, shared-layer scan |

### 2.4 Table conventions (apply once in `AppRow`/`QueueTable`, then per register)

1. Column order stays `S.No. · identity · facts · actions`; **identity and actions stay sticky**.
2. Money and counts get `className="num"`; dates keep `whitespace-nowrap`; IDs keep `font-mono`.
3. Every status cell renders through `StatusBadge`.
4. Every register gets the sticky `thead`, the working row hover, and `Skeleton variant="table"` on first load only
   (`isLoading`, never `isFetching`), with the header refresh spinner for background fetches (already the behaviour on
   most pages; the plan just makes it uniform).
5. The two date controls converge: `QueueDateFilter` keeps its behaviour but adopts the `PeriodPicker` pill styling
   (or vice versa — pick one; recommendation: the square navy pills, because they sit inside toolbars on 9 pages).

---

## 3. Per-page analysis

Each page below follows the same ten headings. "Verified" means the verifier re-read the cited lines; line numbers
refer to the branch head at the time of writing (`953f546`). Sizes use the scale in §4.

### 3.1 Staff dashboard — `/staff/dashboard`

**1. Current-page observations.** `PageHeader` + a Refresh that calls `.refetch()` on every query; a **WorkHero**
(queue count, oldest-waiting file, age) and the role's queue table (`QueueTable`, 17 columns, client-paginated
25/page — the observer's "7 columns, unpaginated" was refuted); a `PeriodPicker`; Decisions, Outcomes, Borrowers,
Collections and Team sections (`SECTIONS` per role, `page.tsx:104-114`), and for ADMIN Trends sparklines, a
`PipelineBar`, a segment strip, a salary-days panel and a collapsible latest-transactions block. Every section has its
own `h-24` pulse block, so sections load and refresh at staggered times. First-paint requests per role (page only,
before the shell's 2× `/me`, feature flags and unread count): CREDIT_EXECUTIVE 4 (+by-ids), CREDIT_HEAD 5 (+by-ids),
DISBURSEMENT_HEAD 6 (payouts awaited *after* flags), ACCOUNTANT 4, COLLECTION_HEAD 5, COLLECTION_EXECUTIVE 4,
TELECALLER 2, **ADMIN 15 (+by-ids)**. Intervals: 10 s queues/decisions/performance/stats, 60 s lists/trends/ledger,
5 min book aggregates — all pause in hidden tabs. Collections queries are correctly gated on `has("collections")`
(refuted claim).

**2. UI/UX problems (verified).**
- **Outages read as "all caught up":** `safe()`/`countOf` swallow every error, so a dead backend renders "0 items need
  your action" with no error state or retry (`page.tsx:171-172,198-277,553-555,748-750`). **Medium, but the worst
  kind of wrong.**
- **Refresh fires disabled queries:** `refreshAll()` calls `.refetch()`, which TanStack v5 honours regardless of
  `enabled`, so any role's Refresh also fires the whole-book segment summary and three unscoped collections lists
  (`:484-499`).
- Twelve independent pulse blocks refresh out of step (a "polka-dot" board); no section says when it last updated.
- `StatCard` values are proportional type with no `tabular-nums`; the team table's numeric columns are `text-right`
  but not tabular; Indian-format money does not align.
- The period picker's native date inputs commit on every `onChange` with no Apply (`period-picker.tsx:41-55`) —
  each complete date change refetches decisions + performance.
- "DPD split" is a `10 / 5 / 2` string; "By DPD bucket" likewise; sparklines have no axis or legend.
- The WorkHero's oldest-first pick and the queue table's newest-first order disagree, so the file to act on next is
  at the bottom of the table.
- Cards render "—" for both *null* and *zero* although the code comment distinguishes them (`:79-84`).

**3. Proposed subtle UI improvements.**
- A per-source `failed` flag in `RoleQueue`; render "Couldn't load part of your queue — Refresh" (`ErrorState`) instead
  of the empty hero.
- Refresh → `queryClient.invalidateQueries({ predicate })` over the dashboard keys (active only).
- One `Skeleton variant="stat"` grid per section that keeps the card shape; "Updated 8 s ago" in each section header
  (one clock for the whole board).
- `tabular-nums` on every `StatCard` value and team-table number (the `num` class); "—" for null, "0" for zero.
- `PeriodPicker` custom range commits on blur / Apply.
- Queue table sorted oldest-first by default (matching the hero) with the age column visible.
- Fire the referral payouts count inside the same `Promise.all` as the flags (drop one round trip for the
  Disbursement Head); read flags from the shell's `['feature-flags']` query instead of a direct fetch every 10 s.

**4. Data-presentation improvements.** Replace the two DPD strings with a three-segment stacked bar (labelled,
clickable into `/staff/collections?bucket=`); give sparklines a baseline and an end-value label; a compact
"Pending actions" strip at the top (repayments to verify · settlements to approve · payouts) built from counts the
page already derives.

**5. Transition / micro-interaction improvements.** Stat values cross-fade on change; `rowFlash` on queue rows that
arrived since the last tick; keep the collapsible chevron rotation already present (`group-open:rotate-90`).

**6. Performance observations (verified).**
- The ADMIN "latest transactions" block polls the **in-memory ledger** every 60 s: `listTransactions` loads every loan
  and every payment, builds views, sums totals and slices 25 rows (`TransactionService.java:72-168`). **High.**
- `bookStats` is a full-book hydration, not an aggregate: the actor's **all-time event trail** as entities, then
  every customer in 100-id chunks (≈ 3 + 8 × ⌈N/100⌉ queries) reduced in Java (`CustomerService.java:351-427`); and
  `byIds()` re-runs `scope()` (the same trail) **per 100-id chunk, per tick** (`:324-337`).
- `trends` loads three full entity lists (CREATE events, loans, verified payments) and buckets per day in Java;
  `loan.disbursed_on` is unindexed (`DashboardService.java:39-66`).
- Collections lists are unscoped `findAll` over every case/settlement/payment with the officer filter applied in
  Java (`CollectionsService.java:169`).
- Referral payouts count materialises every PENDING payout and calls `latestProfile` per distinct customer to
  produce a `.length` (`ReferralService.java:220`).
- The decision summary is polled at 10 s although it changes at human speed.
- `FeatureFlagService.all()` is an uncached `findAll()` hit by the Disbursement Head's queue every 10 s.

**7. Backend/API optimisation opportunities.** Count endpoints for badges (`/collections/worklist/counts`,
`payouts/count`, `pending-repayments/count`); `bookStats` as one or two SQL aggregates over the scoped customer-id
CTE; resolve `scope()` once per request; `trends` as three `GROUP BY date` projections; `listCases` with
`assignedOfficerId`/`status` predicates in SQL; the ledger fix of §3.10 with `size=5` from the dashboard; poll
performance/decisions at `SLOW_MS`; batch the referral name lookup.

**8. Database optimisation opportunities.** `idx_loan_disbursed_on` (trends + ledger + register);
`loan_application(assigned_executive_id, status)` (used by `byStatus` for executives and by `scope()` on every
book-stats/by-ids call).

**9. Expected user impact.** The landing page tells the truth when the backend is down, stops flickering section by
section, and an ADMIN's board no longer rebuilds the whole ledger and the whole book every minute.

**10. Implementation complexity.** UI: **S–M**. Refresh/flags/payouts fixes: **XS–S**. Backend aggregates: **M**
(bookStats, trends), **S** (count endpoints), plus the shared ledger work.

---

### 3.2 Live applications — `/staff/applications`

**1. Current-page observations.** `PageHeader` with `QueueDateFilter` (Today / Yesterday / Custom / All), a 300 ms
debounced search that composes with the date window in every query key, the role badge, `RefreshButton` and a link to
the ledger; a `ReviewLookup` panel; then the role's panels (`page.tsx:160-234`): the Credit Head's `CreditWorkbench`
(unallocated + per-executive groups + the executive roster), `StatusQueue`s per lifecycle status, the
`AwaitingRepaymentPanel` (ACTIVE / OVERDUE split client-side, 60 s), `RepaymentVerifyQueue` (Accountant, 8 s),
collection-payment queues (10 s) and a lazy `ClosedPanel`. Each queue is a `staff-data-table` of 17–18 columns
(`AppRow`: S.No., ☐, #id, customer id, date, name ★sticky, mobile, PAN, account, IFSC, loan, `AmountCell` with a
`req`/`elig` tag, `DueCell`, `CreditBadge`, three staff names, actions ★sticky: ⓘ, Open, Journey + the stage's
maker-checker buttons). Per 8 s tick an **ADMIN fires 5 requests** (credit-queue, `CREDIT_EXEC_PENDING`,
`DISBURSEMENT_PENDING` — shared by the fast-track and standard panels via an identical key — `DISBURSEMENT_FAILED`,
pending-repayments), plus two 10 s collection-payment queues, ACTIVE/OVERDUE at 60 s and the shell's 30 s badge / 20 s
bell (the observer's "9+" was refuted). The unified `ApplicationDetailDialog` (80 vw, 13 tabs) fires application /
events / profile in parallel, then credit-brief and documents as a dependent hop; it polls the application every 8 s
while open.

**2. UI/UX problems (verified).**
- **Sub-10 px type:** table headers are 0.576 rem = **9.2 px** (`globals.css:421`), the dialog uses `text-[8.8px]` in
  three places (`application-detail-dialog.tsx:876,1139,1174`) under a 10.4 px body, and the dialog is portaled
  **outside `.navix-crm`** so the console density rules do not even apply inside it.
- No sticky header on queues that are routinely 25–100 rows tall; no `<caption>`/`aria-label` on `QueueTable`.
- Account, IFSC, PAN and mobile are shown in full on every row (`app-row.tsx:49-53,86-89`) — a product decision for
  screen-shared queues, recorded here because the `Masking` helper exists and the search palette already uses it.
- The `req` / `elig` tag on the Amount cell is easy to miss; a reader can mistake an eligible limit for a request.
- Bulk selection is discoverable only by the unlabeled ☐ header (it has an `aria-label`, no visible text); the
  `BulkActionBar` appears only once something is ticked.
- Rows update silently every 8 s: no cue which file moved or arrived; the count pill changes with no timestamp.
- Verify / Reject in the repayment queue are text buttons while every other row action is an icon button.
- The `['staff-executives']` roster (static reference data) is refetched on every workbench mount after 60 s.
- `AwaitingRepaymentPanel` filters and sorts both lists on every render without `useMemo` (`page.tsx:271-277`).

**3. Proposed subtle UI improvements.**
- Floor table headers and dialog captions at **11 px**; add `.navix-crm` to the dialog portal root so dialogs match the
  grid; `size="xl"` from the shared Dialog instead of `!max-w-[80vw]`.
- Sticky header; visually-hidden caption per queue; a visible "Select" header label and a one-line hint
  ("Tick rows to assign or reject in bulk") in the panel header when bulk is enabled.
- Amount cell: two-tone — requested amounts in `text-ink`, eligible limits in `text-muted` with the tag first
  (`elig ₹25,000`).
- "Updated 6 s ago" beside each count pill; `rowFlash` on rows that arrived or changed status since the last tick.
- Icon buttons (✓ / ✕ with tooltips) in the repayment queue to match `AppRow`.
- `staleTime: 15 min` on the executive roster, invalidated from the admin staff page.
- Optional per-user "mask identifiers" toggle in the header (default off, remembered per staff id) for screen-shared
  sessions — no backend change, uses the existing `Masking` helper.
- `useMemo` the ACTIVE/OVERDUE split.

**4. Data-presentation improvements.** Promote `Due`/DPD and the stage-entered age into the first visible columns for
the disbursement and repayment panels (identity · amount · due · age · actions), demoting account/IFSC to the
scroll region or the ⓘ dialog; group the credit queues by day like the customers register; show "in stage 3 d" as a
muted suffix under the status count.

**5. Transition / micro-interaction improvements.** `.btn:active` already scales; add the dialog exit fade and a
progressive header (identity + status paint from the row's data immediately, tabs fill as queries land — the
dialog already renders header/tabs before the body); `ClosedPanel` gets `Skeleton variant="row"` on expand (it
does fetch immediately — the observer's "waits 8 s" was refuted).

**6. Performance observations (verified).** `GET /applications?status=` = 1 list `findAll(spec, sort)` (**no
`Pageable`**) + [1 search profile query] + **8 batched enrich queries** + k per-distinct-staff-actor `findStaff` +
a conditional per-customer fallback chain for rows without a profile snapshot (reborrow / fast-track,
`CustomerReviewService.java:380-410`) — the "N+1 enrich" claim was refuted, the residual loops are bounded.
`credit-queue` loads **all** `KYC_PENDING` and `KYC_APPROVED` rows and applies the date window **in memory**
(`ApplicationFlowService.java:938-949`). Every queue endpoint is unbounded and re-enriched on each poll, including
ACTIVE/OVERDUE/CLOSED. Queue rows carry ~45 fields while `AppRow` renders 17. The credit-brief payload embeds the
**raw bureau JSON** on every dialog open although only the Credit report tab renders it; `CreditBriefService.view`
reads the BUREAU row twice, is `@Transactional` (not read-only) and lazily generates a PDF on a GET.

**7. Backend/API optimisation opportunities.** Optional `page/size` + total on `/applications` and `/credit-queue`
with the search pushed into SQL; `creditHeadQueue` reuses the `byStatus` Specification with `status IN (...)` and the
`createdAt` bounds; a slim `QueueRowView` projection for list endpoints; one `namesFor` in `ApplicationActorResolver`
and a latest-event projection instead of the ordered scan; a "latest profile per customer" JPQL for the snapshot
fallback; strip `providerResponse` from the headline brief (lazy `/credit-brief/raw` for the tab) and read the BUREAU
row once; move PDF regeneration to an explicit POST or a job.

**8. Database optimisation opportunities.** All relevant indexes exist (`status` V5, `created_at` + `(status,
created_at)` V53, `loan_id` V71, `customer_id`, `application_event(application_id)`, unique
`customer_profile(application_id)`). `loan_application(assigned_executive_id, status)` only if the executive queue
grows past a few hundred rows.

**9. Expected user impact.** Reviewers stop squinting at 9 px headers and 8.8 px captions, see which file just
arrived, and can read amounts without misreading a limit as a request. With paging, a busy disbursement day no
longer re-enriches 300 files every 8 s.

**10. Implementation complexity.** UI: **S–M** (type floor, sticky, badges, cues, memo). Backend: **M** (pagination
+ SQL search + projection); credit-brief trim: **S**.

---

### 3.3 Customers — `/staff/customers`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` (this page / all customers, capped at
`EXPORT_CAP = 50 000`, `CustomerService.java:284`) + Refresh; 13 segment chips with live counts; a filter row —
search (300 ms debounce, placeholder "Name, PAN, mobile, customer or application ID"), `QueueDateFilter`, a "My
customers" badge, and a `BulkActionBar` (Assign / Reject) when rows are ticked; a scoped-listing notice for non-Head
roles; a **22-column** `staff-data-table` (S.No., ☐, Customer ★sticky, Date, Mobile, PAN, Account, IFSC, Loan, Amount,
Due (+DPD), Owner, Loans, Outstanding, Bureau ★, Failure, Latest status, Stage date, Credit exec, Disbursed by,
Collections exec, Actions ★sticky) grouped under collapsible date headers (collapsed set persists across pages and
filters — the observer's "resets" claim was refuted, `page.tsx:203`); `PaginationBar`. **Real server-side pagination**
(SQL `LIMIT/OFFSET`, size clamped 1..100) with `keepPreviousData`; a second `summary` query (30 s stale) feeds the
chips; filters reset the page in a `useEffect`. Row actions: Assign, Reject (`/reject-lead`, one POST per id,
serial), Edit (ADMIN), ⓘ, Open. Dialogs: `CustomerDetailDialog` → `ApplicationDetailDialog`, `ApplicationInfoDialog`,
`CaseFailureDialog`, `CustomerEditDialog`, `RejectDialog`, `AssignDialog`.

**2. UI/UX problems (verified).**
- **Stale row after an ADMIN edit or a failure retry (medium bug):** `CustomerEditDialog` and `CaseFailureDialog`
  invalidate the legacy key `['customers']`, which matches neither `['customers-page', …]` nor
  `['customers-summary', …]` — the table keeps the old values until Refresh (`customer-edit-dialog.tsx:102-107`,
  `case-failure-dialog.tsx:55-57`).
- 22 columns with no column visibility control; six of them (PAN, Account, IFSC, Credit exec, Disbursed by,
  Collections exec) are reference data most roles rarely scan.
- Sort is fixed (`status_changed_at DESC`) and nothing tells the reader; no sticky header.
- The mixed-mode bulk-reject rule (credit-stage vs `DISBURSEMENT_PENDING`) **hides** the Reject button
  (`bulk-actions.tsx:401-409`) with a small warning line elsewhere — the reader sees the button vanish.
- "Open" costs a full `CustomerDetail` (profile, every application, loan, per-loan payments, credit brief) only to read
  `applications[0].id` before `ApplicationDetailDialog` fetches the application again, although
  `latestApplicationId` is already on the row (`customer-detail-dialog.tsx:35-48`); the ⓘ quick summary is a 3-call
  chain.
- Bulk Assign/Reject run strictly serially (100 rows ≈ 30 s at the measured ~300 ms each, `bulk-actions.tsx:91-102`)
  with only the dialog's busy state as progress.
- Money cells (`Amount`, `Outstanding`) left-aligned; `Due` mixes a date and a `+Nd` DPD tag in one cell.

**3. Proposed subtle UI improvements.**
- Fix the invalidation keys (or pass `onDone={invalidateAll}` as `RejectDialog`/`AssignDialog` already do).
- A **Columns** menu (checkbox list, persisted per staff id in `localStorage`) with a sensible default that hides the
  six reference columns; the sticky identity/actions columns are never hideable.
- Sticky header; `num` on money; a visible "Sorted by stage date ↓" caption in the toolbar (until server-side sort
  exists — §7).
- Keep the Reject button visible but **disabled with an `InfoTooltip`** explaining the mixed selection.
- Open `ApplicationDetailDialog` directly with `latestApplicationId` (fall back to `CustomerDetailDialog` when null);
  pass `applicationId` to the ⓘ dialog.
- Bulk actions: bounded concurrency (4) via `Promise.allSettled` with a "12 / 40 done" counter in the dialog.
- Reset the page inside the filter handlers (not an effect).

**4. Data-presentation improvements.** Split `Due` into `Due` and a `DPD` column with the badge tones already used
by the loans segments (0 = none, 1–30 warning, 31+ error); show the owner as a small avatar-initial pill; group
headers show the count and the segment mix ("12 · 3 overdue").

**5. Transition / micro-interaction improvements.** Group open/close animates height; `rowFlash` on rows changed by a
bulk action; skeleton table on first load; the in-table busy veil while a slow search runs behind `keepPreviousData`.

**6. Performance observations (verified).** For a Head/ADMIN a 25-row page costs `count` + `pageIds` over the
four-CTE `BOOK_CTE`, then ≈15 batched hydrate statements plus one `staff_user findById` per distinct staff id from
**three separate per-request caches** — ≈17 + D statements (the 2026-09-16 perf doc measured 51 statements / 44–50 ms
/ 19 KB before settlement batching). The CTE is evaluated **three times** per load (count, pageIds, segmentCounts) and
`count` re-runs on every page flip. `VerificationFailureService` re-loads profiles and applications already in memory
(2 duplicate statements); `collection_case` is read twice. **Scoped roles** (`CREDIT_EXECUTIVE`, `TELECALLER`,
`COLLECTION_EXECUTIVE`, `ACCOUNTANT`) pay a full audit-trail scan per request — `decidedCustomerIds` loads every
`application_event` row for the actor as full entities and filters in Java; with `?mine=1` it runs twice
(`CustomerService.java:227-239`); `TELECALLER` scope loads the entire `customer_owner` table. Export hydrates up to
50 000 ids in **one unchunked `IN` list** and will hit the pgjdbc 32 767 bind-parameter ceiling before the cap
(`:305-309`).

**7. Backend/API optimisation opportunities.** `count(*) over()` in `pageIds` (drop the separate count); resolve all
staff names with one `findStaffByIds`; pass the in-memory profile/application maps into `failures()`; one directory
call for case + officer + settlement per loan; a JPQL projection for `decidedCustomerIds`; a
`select customerId from CustomerOwner where ownerStaffId is not null` projection for the telecaller scope; page the
export through `pageIds` slices (or stream CSV); an optional `sort` parameter (stage date, name, outstanding, DPD)
so the fixed sort can become a header control; a batch reject/assign endpoint applying the same per-id guards.

**8. Database optimisation opportunities.** Btree indexes on `pan`/`mobile` exist but cannot serve `%needle%` with
`lower()` over the CTE; `full_name` has none. Add `pg_trgm` GIN indexes on `customer_profile(lower(full_name))`,
`(pan)`, `(mobile)` and push the needle into the `prof` CTE. Low priority at the current ~10 k book, high once it
grows. Index `application_event(application_id, at desc)` also serves the `stage` CTE.

**9. Expected user impact.** Edits show up immediately; the table fits the screen for most roles without sideways
scrolling; opening a row is one request instead of five; bulk actions finish in seconds.

**10. Implementation complexity.** Invalidation fix: **XS**. Columns menu + sticky + badges: **S–M**. Dialog seeding:
**S**. Backend batching + projections: **M**. Trigram indexes: **S**.

---

### 3.4 Customer 360 — `/staff/customers/[customerId]`

**1. Current-page observations.** Back link + Refresh; a two-column layout (`lg:grid-cols-[1fr_minmax(0,340px)]`,
`page.tsx:74`): left, a 9-tab panel (`customer-tabs.tsx:39-49` — Personal, Employment, Bank, Credit, Loan
applications, Verifications, Documents, Call logs, Audit logs) inside a `max-h-[68vh]` scroller; right, the ADMIN
column behind `customer:manage` — credit-score gauge (read-only, animated needle), force-disbursement / reject-sanction
actions, and **seven correction cards** (sanctioned amount, limit override, salary day, edit KYC, mobile change
(2-step OTP), blocklist, delete-with-typed-name) driving eight `useMutation`s. First paint fires **more than one
request**: `GET /customers/{id}` (the roll-up), plus on the default Personal tab `GET …/documents`
(`NeedsManualReviewBadge`, `customer-tabs.tsx:164`), `GET …/verifications` (`:188-192` — so verifications are *not*
lazy), three `GET /credit-executives?role=` from the owner picker (`customer-owner-picker.tsx:12,42-47`) and the
uncached `/api/auth/staff/me`. Call logs and Audit logs load on tab click. The Verifications tab uses different
keys (`['staff-verifications']`, `['staff-verification-progress']`) from the Personal/Bank/Credit tabs
(`['verifications']`), so it **re-fetches** the same endpoint (`verification-checks.tsx:103-112`).

**2. UI/UX problems (verified).**
- Success messages on the correction cards never auto-dismiss (`page.tsx:256,540`) and **four cards give no success
  feedback at all**; errors are inline paragraphs.
- Whole-page pulse block on first load; lazily loaded tabs show a text "Loading…" (`customer-tabs.tsx:554-555,963,
  1010`); the field tables inherit `min-width: 84rem` and have no sticky header.
- The current application is deliberately not clickable in the applications list (`:732-743`) — a reader who wants
  the dialog for the file they are looking at cannot open it.
- "Limit override" and "Sanctioned amount" cards say "(set by admin)" / "(25 % of salary)" in small text with no
  visual distinction; the salary-day card projects a due date but never warns when it falls outside the 40-day window.
- Loans / payments lists are unbounded and unsorted by status; payments are ordered by id only.
- Blocklist has no duplicate warning; edit-KYC salary inputs are bare numeric fields with no ₹ prefix or thousands
  preview.
- The ADMIN column stacks below the tabs under `lg`; on a laptop the seven cards become a long scroll.

**3. Proposed subtle UI improvements.**
- `toast.success` for every mutation (keep the inline copy for the two-step mobile flow); auto-dismiss inline notes.
- `Skeleton variant="row"` per card / per tab instead of the page-sized block; a `min-w-0` override on the field tables.
- Make the current application clickable (it simply opens the same dialog).
- A `Badge` ("Admin override" / "Salary rule") on the limit and sanction cards; an amber note under the salary-day
  projection when the projected due date exceeds disbursal + 40 days (`dueDateFromSalary` is already computed
  client-side, `page.tsx:483`).
- Right column becomes a collapsible "Corrections" accordion with one card open at a time (same cards, same forms).
- Salary inputs with a `₹` `leftIcon` and a live "₹ 42,000" preview.

**4. Data-presentation improvements.** A one-line exposure summary above the Loans list (total principal ·
outstanding · last payment); payments grouped by status (Verified / Pending / Rejected) with counts; the Audit tab
gains a type filter chip row (lifecycle / re-verify / profile edit / remark) — all client-side over data already
loaded; "Provider: Signzy" per verification row (the DTO has it).

**5. Transition / micro-interaction improvements.** Tab panel cross-fades on switch (the scroller is not remounted,
so scroll position already persists — the observer's claim was refuted); `rowFlash` on the loan card after a
correction; keep the gauge's mount animation but disable it under reduced motion.

**6. Performance observations (verified).** `CustomerService.detail` is **≈17 queries** for one application / one loan
(`:689-769`): applications, loans, profiles `IN`, `latestProfile` **per application** (`:704`; a batched overload at
`:1519` is unused), staff names **per distinct id, uncached** (`findStaffByIds` unused; `StaffDirectory` has no
cache anywhere), every `application_event` row for the customer (`:716`), three breakdown queries, payments **per
loan** (N+1, `:747-751`), owner (+1 name), credit brief (4 queries, `CreditBriefService.view:179-217`), limit
override; scoped roles add 3–6 (`TELECALLER`: `ownerRepository.findAll()`). The response embeds the **full raw bureau
`providerResponse` JSON** on every customer read (`CreditBriefDtos.java:29-30`) while the page uses four fields and
the Credit tab re-fetches the brief anyway. The roll-up is fetched again under `['customer-detail', id]` when a loan
card or dialog opens (`loan-detail-dialog.tsx:95-99`). `activity()` (Audit tab) is a 3-per-application N+1
(`:1229-1274`).

**7. Backend/API optimisation opportunities.** `paymentRepository.findByLoanIdIn(loanIds)`; the batched
`latestProfile` overload; `findStaffByIds` once; drop `providerResponse` from `CreditBriefView` in the customer read
(serve it only on the explicit credit-brief endpoint); batch `activity()` per customer; share one query key for the
roll-up across page and dialogs; give `VerificationChecksPanel` the `['verifications', appId]` key (or vice versa) so
the endpoint is read once; defer the owner-picker roster to first open.

**8. Database optimisation opportunities.** All filtered columns are indexed; no change needed.

**9. Expected user impact.** ADMIN corrections confirm themselves; the page opens with a quarter of the queries and
without a credit report in the payload; reviewers get an exposure line instead of adding up loans by eye.

**10. Implementation complexity.** UI: **S–M**. Backend batching: **S–M** (all batched methods already exist). Brief
payload trim: **S** (one DTO field, check the borrower-safe path is unaffected).

---

### 3.5 Verification dashboard — `/staff/verifications`

**1. Current-page observations.** `PageHeader` + Refresh; five stat tiles (Pending / Failed / In review / Passed /
Never run); an "Include cleared" checkbox and a debounced search (`aria-label` present, `page.tsx:263-271`); then a
**card grid** (not a table) in four fixed buckets — failures → awaiting → passed → not started — each card showing
name, ids, mobile, a `{passed}/{total} passed` progress bar with `transition-all`, and failed/pending/EPFO chips; a
`PaginationBar` at the bottom. Clicking a card opens `ui/Dialog` → `VerificationChecksPanel` with per-check
**Manual override** (PASS/FAIL + notes), **Retry API** (dynamic inputs per check type) and **Send reminder**.
Data: `['staff-verif-overview', q, page, size, includeCleared]` polled every 45 s with `keepPreviousData`
(`page.tsx:101-119`), plus a second 45 s poll of the **unpaginated** `KYC_PENDING` queue used to synthesise the "Not
started" bucket client-side (`page.tsx:121-125,137-145`). Panel queries (`summary` + `progress`) run with `retry:false`.
Override/retry invalidate five keys, of which three are active on this page (`verification-checks.tsx:312-318,399-409`).

**2. UI/UX problems (verified).**
- The "Not started" bucket is wrong by construction: every `KYC_PENDING` file not on the *current page* of overview
  rows is pushed as a "Not started" card, while the backend deliberately omits fully-passed files when "Include
  cleared" is off — so cleared files and files on other pages are mislabelled (`page.tsx:137-145,183-207`,
  `ApplicationVerificationService.java:3514-3516`). **High.**
- Stat tiles always cover the whole undecided queue and ignore the search term; searching one customer leaves global
  counts on screen (`page.tsx:247-251`).
- `Retry API` is shown to `CREDIT_EXECUTIVE`/`CREDIT_HEAD` (they hold `verification:retry` in `rbac.ts:113,120`) but the
  backend rejects everyone except ADMIN (`ApplicationVerificationService.java:1005-1007`) — every click ends in an error.
- Retry has no in-flight guard: after the 120 s client `Promise.race` rejects, the button re-enables while the provider
  call is still running; a second click sets the row `PENDING` again and calls the (billable) provider again
  (`lib/api/applications.ts:1833-1841`, `verification-checks.tsx:341-342`). **High.**
- Sending a reminder has no confirm step and no server-side cooldown; every POST dispatches SMS + email + in-app
  (`ApplicationVerificationService.java:3417-3436`).
- The EPFO chip's PASS/FAIL states reuse the gating check colours (`bg-success-100`/`bg-error-100`,
  `page.tsx:381-383`) although employment never gates a transition; only its REVIEW state is neutral.
- `PaginationBar` says "Rows per page" and prints no unit, but this page pages by **application** and the "Not started"
  cards sit outside `total` (`pagination.tsx:77-82`, service `:3525`).
- The "Never run" tile counts the 4 REQUIRED checks while the card's `missingRequired` uses the 8 gating checks, so the
  two disagree (`service:158,3485-3492` vs `page.tsx` `REQUIRED_CHECKS`).
- The panel's "No verification checks recorded yet." branch is unreachable (a NOT_RUN placeholder is always appended,
  `verification-checks.tsx:124-134,171-172`).
- One combined error card for two queries; the loading state is a bare `h-40 animate-pulse` block; panel content shows
  a text "Loading…".
- Buckets cannot be collapsed; a long failures bucket pushes "passed" below the fold.

**3. Proposed subtle UI improvements.**
- Bucket headers become collapsible `<details>`-style sections with the count pill; remember the collapsed set in
  `localStorage` per staff id (same idea as the customers date groups).
- Stat tiles get a small "(all undecided files)" caption when a search term is active, so the mismatch is explained
  rather than hidden.
- `PaginationBar` gains an optional `unitLabel` ("applications") used here.
- Hide `Retry API` unless the role is ADMIN (or relax the backend to the credit team — the two must agree; recommend
  aligning `rbac.ts` to the backend, the safer change).
- Reminder and override buttons open `ConfirmDialog`; after success show a `toast` ("PAN overridden to PASS") — the
  panel already refetches immediately because its keys are active.
- Replace the EPFO chip's PASS/FAIL colours with the neutral `info` badge variant + an ⓘ tooltip "advisory, never gates".
- Keep the Retry button disabled after a client-side timeout until the row's `updatedAt` changes.

**4. Data-presentation improvements.**
- Show "last checked *2 h ago*" under each card's progress bar (from the newest `updatedAt` in its rows; already in the
  payload).
- On each check row inside the panel, show the provider that answered (`provider` is in the DTO) — it is the first
  question support asks.
- Move the "Not started" synthesis to the server (below) so the bucket is trustworthy; until then, exclude any id
  present in the current overview page from the client merge.

**5. Transition / micro-interaction improvements.** Card hover already lifts (`hover:border-navy/40 hover:shadow`);
add the same `rowFlash` treatment to a card whose status changed between polls; use `Skeleton variant="row"` ×3 in
place of the pulse block; give the dialog the shared exit fade.

**6. Performance observations (verified).**
- `overview()` runs **three unbounded queries** — every undecided application, every `application_verification` row
  for them **as full entities including the `raw_response` JSONB (a whole credit report on BUREAU rows)**, and every
  profile — then tallies, filters, groups and pages in Java (`ApplicationVerificationService.java:3455-3525`). The
  `IN (...)` is not chunked even though `ID_CHUNK_SIZE=1000` exists for this purpose.
- The second poll re-reads the whole `KYC_PENDING` queue (~6 queries via `enrich`) every 45 s for a bucket the UI
  then mislabels.
- Opening the panel issues two requests that re-read the same rows (`summary` and `progress`; `progress` = 2 queries
  + 2 per re-apply hop, `service:3241,3274-3286`).
- Search is applied in memory across every undecided row.

**7. Backend/API optimisation opportunities (evidence-backed).**
- Add an interface projection for overview rows (`applicationId, checkType, status, provider, message, updatedAt`) and a
  `findOverviewRowsByApplicationIdIn` query; project `LoanApplication` to the 4 fields the board needs. Removes the
  credit-report JSON from every 45 s poll.
- Compute the five tallies with `select v.status, count(*) … group by v.status` (+ one never-run count) and page
  applications in SQL; return `untouchedApplicationIds` (or synthetic rows) so the client stops merging the
  `KYC_PENDING` queue and the second poll disappears.
- Return the progress snapshot alongside the step list (one endpoint, one read).
- Persist an in-flight marker for retries (`derived.retryStartedAt`, reject with `RETRY_IN_PROGRESS` inside N minutes).
- Record `last-reminded-at` per application and refuse a second reminder inside a cooldown; surface it on the button.
- **Audit gap:** `manualDecision` writes no `application_event` (`service:3324-3353`) — the maker-checker trail never
  sees who overrode which check. Append `VERIFICATION_OVERRIDE` with actor, check, decision and remarks.
- Apply the same `CREDIT_EXECUTIVE` scoping in `overview()` that `byStatus(KYC_PENDING)` already applies
  (`ApplicationFlowService.java:888-892`) or document that the desk is deliberately unscoped.

**8. Database optimisation opportunities.** Indexes needed by this page exist (`V5` status, `V53` (status,
created_at), `V15` unique (application_id, check_type)). No new index is required; the win is projections + SQL
aggregation above.

**9. Expected user impact.** Reviewers stop seeing phantom "Not started" files and stop double-billing providers on
retries; the board loads without the credit-report payload (the single biggest byte saving in the console); every
action confirms itself. Nothing about where checks live or how override/retry/reminder work changes.

**10. Implementation complexity.** UI: **S** (badges, confirm, collapse, unit label, hide retry). Backend projections +
tallies: **M** (one service method, one repository query, one test). Retry guard + reminder cooldown + override event:
**M** (small schema-free changes; the event is one insert). Server-side "not started": **M**.

---

### 3.6 Loans register — `/staff/loans`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` (17 columns) + an inline refresh; segment chips
(all / active / overdue / closed with live counts, `cal-preset`); search (300 ms debounce, `w-80`) + `QueueDateFilter`
(ALL / TODAY / YESTERDAY / CUSTOM) + role badge; a 15-column `staff-data-table` (S.No., Loan ★sticky, Borrower,
Sanctioned↕, Disbursed↕, Due↕, Cycle "2nd", Principal↕, Net disbursed, Repayable, Outstanding↕, DPD↕, Status badge by
segment tone, Officer, Open ★sticky) with collapsible date groups when a date column is the sort key; client
pagination; `LoanDetailDialog` keyed by `?open=` (tabs overview / repayments / documents / calls / timeline). One
query `['staff-loans', q, from, to]` (`page.tsx:123-126`): **search and date range are server-side, segment / sort /
pagination are client-side over the whole result**. Sort and segment are written to the URL; `q` and `open` are read
at mount only (`:104-111,136-156`). The page never loads the customer table (the observer's `customersApi.list` claim
was refuted — line 121 is a comment).

**2. UI/UX problems (verified).**
- Money columns are `font-mono` but left-aligned (`:338-341`; `globals.css:420` forces `text-align:left`); no sticky
  header on a register that routinely spans pages.
- Outstanding is a single number; the backend computes interest / penalty / paid / settlement cap for every row and
  `outstandingForAll` **discards the breakdown** (`RepaymentService.java:400-403`); an approved settlement is invisible
  in the row (`LoanRegisterRow` has no settlement field).
- Pagination does not reset on search/segment change; each new search flickers the skeleton and the chip counts.
- Search has no clear (✕) affordance; DPD shows "—" both for "not yet due" and "due today".
- The detail dialog re-derives everything from `loanId` in a 2–3 hop waterfall (`loanQ` → `customerQ` → …) although
  the register row already carries `customerId`, `applicationId`, name, cycle and sanction fields; `outstanding` is
  **eager** (`enabled: open`, `loan-detail-dialog.tsx:106-111`), not deferred as the observer thought.
- The by-loan collections-case call is a swallowed 404 on every healthy loan.

**3. Proposed subtle UI improvements.**
- `num` on Sanctioned / Principal / Net / Repayable / Outstanding; sticky header; ✕ in the search field; `setPage(1)`
  inside the search and segment handlers; `keepPreviousData` so counts and rows do not flicker.
- Outstanding cell gets an `InfoTooltip` with the itemised breakdown (principal · interest *n* d · penalty *n* d ·
  paid) and a small "Settled" `Badge` when a settlement caps the balance — data the backend already has (§7).
- DPD "—" → "due today" when `dueDate === today`, "not due" otherwise.
- The dialog is seeded from the row (`initialData` for `loanQ`, `customerId` passed as a prop) so the overview paints
  instantly; the case lookup only when the status is collections-relevant.

**4. Data-presentation improvements.** Add "Days to due" (negative when overdue) as a sortable column for the
Collection Head; make the segment chip tone match the status badge tone (both already use `SEGMENT_TONE`); group by
due date by default for the overdue segment.

**5. Transition / micro-interaction improvements.** Date-group chevron rotates with `transition-transform`; group
body height animates open (`grid-template-rows: 0fr → 1fr`); `rowFlash` on rows whose segment changed after a
refresh; dialog exit fade.

**6. Performance observations (verified).** `LoanRegisterService.list` is batched (no per-row N+1) but returns **every
matching loan**: loans by date window, applications `IN`, profiles `IN`, one grouped verified-payment sum, two
settlement queries (`SettlementDirectoryAdapter:67,77`), `collection_case` read **twice** per request
(`CollectionCaseDirectoryAdapter:37` + the settlement adapter), `staff_user findById` per distinct officer — ≈ 7 +
N_officers statements per `GET /api/loans`, no pagination; `q` is applied **after** full enrichment. Opening one
dialog computes the same outstanding **three times** server-side (loan view, `/outstanding`, and the customer detail's
`outstandingByLoanId`, which already carries the itemised breakdown) and `GET /api/customers/{id}` has the per-loan
payments N+1 plus every `application_event` for the customer (`CustomerService.java:748-749`).

**7. Backend/API optimisation opportunities.** `Pageable` + `segment` + `sort` on `GET /api/loans` (segment
predicates are simple status/due-date tests) and apply `q` in SQL before enrichment; expose
`interestPaise / penaltyPaise / paidPaise / settledPaise` on `LoanRegisterRow` (computed already); read
`collection_case` once and resolve officer names with `namesFor`; in the dialog, reuse the row's outstanding for
"today" and call `/outstanding` only for another date; skip the case lookup unless `status` is overdue/in-collections.

**8. Database optimisation opportunities.** No index on `loan(disbursed_on)` — the register's date window scans; only
`idx_loan_customer_id` and `idx_loan_status_due_date` exist (`V2:316/V33:27`, `V71:29`). Add
`idx_loan_disbursed_on (disbursed_on)`.

**9. Expected user impact.** The Collection Head reads the book like a ledger (aligned figures, header always
visible), sees a settlement without opening the loan, and the dialog opens from the row instantly.

**10. Implementation complexity.** UI: **S**. Row breakdown fields: **S** (data exists). Server pagination + SQL
search: **M**. Index: **S**.

---

### 3.7 Collections worklist — `/staff/collections`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` + Refresh; six **bucket cards** (label, count,
outstanding; the active one navy) that switch the view via `router.push` with no request (`page.tsx:122,303`);
search (300 ms, case-insensitive) + `QueueDateFilter` + `BulkActionBar` (Assign); a 15-column `staff-data-table`
(S.No., ☐, Borrower ★sticky, PAN, Loan, Principal, **Outstanding**, Due (red when overdue), DPD (red), Employer,
Salary, Credit exec, Disbursed by, Collections exec = inline officer `Select` for Head/ADMIN, Actions ★sticky:
ADMIN log-payment, quick-view eye, Open); ten sortable columns (default DPD ↓); client pagination;
`BulkAssignOfficerDialog`; `ApplicationDetailDialog` quick view. One query `['collections-worklist']` polled every
**8 s** (`:121-125`), **unpaginated**; bucket, search, date range, sort and pagination are all client-side over the
whole worklist (`:169-188`). The officer roster is one shared key with a 60 s `staleTime`, enabled only for manage
roles (the "per-row refetch" claim was refuted, `collections-assign.tsx:71-77,230`). The loading shimmer is gated on
`isLoading`, so the 8 s refetches never obscure the table.

**2. UI/UX problems (verified).**
- The **same unpaginated worklist is polled twice**: every 8 s here and every 30 s by the sidebar badge on *every*
  staff page for collection roles and ADMIN (`staff-shell.tsx:120-129`) — the heaviest collections read runs
  continuously.
- Each inline officer assign invalidates the worklist (immediate full refetch) while the ~7-query `CaseDetailView`
  the backend returns is discarded (`collections-assign.tsx:31-37,281`).
- Bulk assign is 2–3 requests **per loan** run serially — `GET by-loan`, a conditional `POST cases`, `POST assign`
  (`:44-49,370`); 100 loans ≈ 250 round trips with only a busy state.
- Selection clears whenever the page's row set changes (`useQueueSelection`, `:50`), so multi-page bulk work is lost.
- Overdue rows are marked by red text in two cells only; no row-level cue; no sticky header; money left-aligned.
- The `w-[11.5rem]` wrapper and the `w-40` select inside it disagree (`:285-295`), producing a visible jog on render.
- Export refetches the customer enrichment on every menu open (`page.tsx:223-225`).
- No "last refreshed" cue although the page is live.

**3. Proposed subtle UI improvements.**
- Sticky header, `num` on Principal / Outstanding / Salary, a `Days to due` value inside the DPD cell (`+5` / `−3`)
  with the same badge tones as the loans segments, and a soft `error-50` left border on overdue rows.
- "Updated 6 s ago" next to Refresh (the poll is a feature; say so).
- Keep selections across pages (store selected loan ids, not row indices) and show "Select all 347 in this bucket".
- Bulk assign dialog shows "12 / 40 assigned" with per-loan ✓/✗ and runs 4 in parallel; skip the pre-GET (§7).
- Fix the select width mismatch (one `w-44`); `setQueryData` the returned case into the worklist row instead of
  invalidating.

**4. Data-presentation improvements.** Bucket cards gain a tiny stacked bar of outstanding by DPD band; the
"Collections exec" column shows an avatar-initial pill + name for read-only roles; group rows by due date inside a
bucket (the same collapsible groups the loans register has).

**5. Transition / micro-interaction improvements.** Bucket card switch cross-fades the table (it is a client filter,
so it can animate); `rowFlash` on rows whose bucket or officer changed between polls; skeleton table only on first load.

**6. Performance observations (verified).** `CollectionsService.worklist` is ≈ **10–11 fixed queries + K
per-distinct-actor `staff_user` lookups** (`ApplicationActorResolver.toActor`), with `loan_application
findByLoanIdIn` and `collection_case findByLoanIdIn` each run **twice** per request — for the whole collectible book,
every 8 s per open page and every 30 s per shell. Outstanding/DPD are computed on read for every row (by design).
All filtered/joined columns are indexed (`loan(status, due_date)` V71, `collection_case(loan_id)`,
`loan_application(loan_id)` V71, `payment(loan_id)`, `settlement(collection_case_id)`).

**7. Backend/API optimisation opportunities.** `?bucket=&q=&from=&to=&sort=&page=&size=` on `GET /collections/worklist`
(bucket = a DPD range on `due_date`, computable in SQL); resolve actor names with one `namesFor`; read each
`loan_application`/`collection_case` batch once; a lightweight `GET /collections/worklist/counts` (count + outstanding
per bucket) for the six cards **and** the sidebar badge, so the badge stops fetching the full worklist; a
`POST /collections/cases/bulk-assign {loanIds, officerId}` that opens-if-missing and assigns in one transaction; make
`openCase` idempotent-by-POST for the bulk loop (drop the pre-GET); include the export enrichment fields (mobile,
bank, score) in the worklist row so `byIdsAll` on export disappears.

**8. Database optimisation opportunities.** Only a composite `application_event(application_id, action, at)` is
absent (low). Nothing else required.

**9. Expected user impact.** The heaviest collections read stops running on every page in the console; officers
see overdue rows at a glance, keep their selection while paging, and assign 40 loans in one dialog instead of 250
requests.

**10. Implementation complexity.** UI: **S–M**. Counts endpoint + badge: **S**. Worklist pagination + de-dup: **M**.
Bulk-assign endpoint: **M** (must reuse the per-loan SoD/role guards).

---

### 3.8 Collection case — `/staff/collections/[loanId]`

**1. Current-page observations.** Two-column grid (`lg:grid-cols-[1fr_minmax(0,360px)]`, `page.tsx:78-127`). Left:
case header card (bucket/DPD, loan, officer, opened), `LoanCard` (principal, net, repayable, **outstanding computed
on read**, dates, status), `BorrowerCard` (name, PAN, employment; employer/salary/bank behind `collections:manage`),
`CasePaymentsCard`, `InteractionsCard` (type/outcome/PTP date/proof form + list), `CallRemarksCard` (tabs: call
history, remarks). Right: `AssignCard` or `AssignedOfficerCard`, `RecordPaymentCard`, `AdminLogPaymentButton`
(ADMIN), `SettlementCard`. Load is a **two-tier fan-out**: `caseQ` (numeric id → `GET by-loan` → on 404 → `POST
cases`; UUID → `GET cases/{id}`), then in parallel interactions, case payments, the **full customer roll-up**
(`customersApi.get`, used only for the credit badge), call logs for **all** the borrower's loans, and remarks
(`page.tsx:36-51,77,97,106,165-169,218-222`). Officers load only when `AssignCard` mounts (the gate is correct).
Every on-page mutation calls `invalidate()` = case + worklist + interactions (`page.tsx:53-57`).

**2. UI/UX problems (verified).**
- `InteractionsCard` and `CasePaymentsCard` have **no error branch**: a failed GET renders "No interactions logged
  yet." / "No payments recorded on this case yet." (`page.tsx:287-291`, `collection-payments.tsx:149-152`) — in
  collections an officer may conclude nobody has called.
- `logInteraction` and `assignOfficer` give no success feedback (fields clear + refetch); `raisePayment` and
  `proposeSettlement` do (`page.tsx:261-267,323-326,363`, `collection-payments.tsx:109-116`) — inconsistent.
- The interactions list cannot show **who** logged each call: `InteractionView` omits `loggedByStaffId` although the
  entity persists it (`CollectionsDtos.java:132-145`).
- Promise-to-pay date is rendered for every outcome and is not required for `PROMISE_TO_PAY`; proof-ref only appears
  for `PAID` (`page.tsx:277-280`).
- Money inputs use `inputMode="numeric"` (no decimal key on phones) while the sanitiser accepts decimals and multiple
  dots; a lone "." parses to `NaN` and is sent as `null` (`collection-payments.tsx:88-94`, `page.tsx:361`).
- After an ADMIN logs a payment from this page, `LoanCard` outstanding and the payments list stay stale: the dialog's
  onSuccess invalidates neither `['collections-case-by-loan', loanId]` nor `['collection-payments']`
  (`admin-log-payment.tsx:124-141`).
- The Refresh button refetches case + interactions only (`page.tsx:67`).
- `SettlementCard` shows no list of the case's existing proposals; the proposer cannot see a pending one.
- Whole page body is replaced by one pulse block on first load (`page.tsx:73-74`); cards show text "Loading…".
- Two payment-recording forms with different proof semantics (free-text ref vs S3 upload) sit on one page.

**3. Proposed subtle UI improvements.**
- Add `ErrorState` before the empty check in both cards (CallRemarksCard already does this, `page.tsx:234-235`).
- `toast.success` after log/assign; keep the inline copy on the two cards that already have it.
- Show PTP date only for `PROMISE_TO_PAY` and make it required there; keep proof-ref for `PAID`.
- `inputMode="decimal"`, collapse to one dot / two fraction digits, disable the button on `NaN`.
- Refresh refetches all six queries; the ADMIN payment dialog invalidates the case and payments keys.
- First load: `Skeleton variant="row"` per card instead of one page-sized block; keep the two-column layout visible.
- Collapse the interaction form behind a "Log interaction" header once one interaction exists (the list is what an
  officer scans first).

**4. Data-presentation improvements.**
- "Last action *3 h ago by Priya*" line at the top of `InteractionsCard` (needs `loggedByName`, below).
- A small "Settlements on this case" list in `SettlementCard` (proposed / approved / rejected with dates).
- `LoanCard` outstanding gets an "as of *now*" caption (it *is* computed on read; say so).
- Payment status pills through `StatusBadge kind="payment"` (today a private `STATUS_LABEL` map,
  `collection-payments.tsx:27-36`).

**5. Transition / micro-interaction improvements.** New interaction row gets `rowFlash`; card skeleton → content
fade; the officer select shows its 14 px spinner slot already — keep it.

**6. Performance observations (verified).**
- `customersApi.get` pulls the **entire** `CustomerDetail` roll-up (every application/loan/payment, credit brief with
  raw provider JSON, breakdowns) — 12–20 queries with a per-loan payments N+1 (`CustomerService.java:748`) — to render
  four credit-headline fields (`page.tsx:163-182`). **High.**
- `GET /cases/{id}/payments` resolves a full `LoanSummary` (≈7 queries incl. outstanding math) per loan although only
  `borrowerName` is used for this case-scoped list (`CollectionPaymentService.java:232-245`).
- `openCase` resolves the loan twice (`CollectionsService.java:91,366`); `raise` builds the `LoanSummary` twice more
  (`CollectionPaymentService.java:120,291`); `assign` runs a second full `getCaseDetail` (~11 queries) whose result
  the page discards by invalidating (`CollectionsController.java:128-133`).
- For a `COLLECTION_EXECUTIVE`, each of the three customer reads re-runs `scope()` (~5 queries incl. the staffer's
  entire decision history) (`CustomerService.java:169-220,227-238`).
- Every mutation invalidates `['collections-worklist']`, the heaviest collections read, which the **sidebar badge on
  every page** observes (`staff-shell.tsx:120-131`).
- First-ever open is two serial round trips (GET 404 → POST) although `openCase` is idempotent.

**7. Backend/API optimisation opportunities.**
- Add the four credit-headline fields to `LoanSummary` (built from the `CustomerProfile` already loaded in
  `LoanDirectoryAdapter.toSummary:198-220`), or read them via `GET /customers/by-ids`; drop `customersApi.get` here.
- Case-scoped payments: skip `findLoans` (or use a light profile projection).
- Pass the resolved `LoanSummary` into `buildDetail`; return the bare case from `assign` (or `setQueryData` on the
  page) instead of a second detail build.
- Add `loggedByStaffId` + `loggedByName` to `InteractionView` (one `namesFor` batch).
- Invalidate the worklist only from `assignOfficer`.
- Memoise `scope()` per request (request-scoped attribute) or replace it with a single visibility query.
- Call `openCase` directly for numeric ids (add `?create=false` if a read-only visit must not flip the loan).

**8. Database optimisation opportunities.**
- `collection_case.loan_id` is indexed but **not unique**; `openCase` is check-then-insert, so a concurrent double-open
  can create two cases (`CollectionCaseRepository.java:16-35`). Add `uq_collection_case_loan_id` after de-duplicating.
- `interaction_log` is read `ORDER BY logged_at DESC` on an index covering `collection_case_id` only; cosmetic today,
  add `(collection_case_id, logged_at desc)` when volume grows. Interactions, call logs and remarks are unbounded
  lists — bound with `?limit=` when a case exceeds a few dozen entries.

**9. Expected user impact.** Officers see who called last and when, get told when an action landed, and never mistake
an outage for "nobody has called". The page stops pulling a customer's whole history to draw one badge, and the
sidebar stops refetching the worklist every time someone logs a call.

**10. Implementation complexity.** UI: **S–M**. Backend: **M** (DTO fields, buildDetail reuse, invalidation scope);
unique index: **S** plus a one-off de-dup check.

---

### 3.9 Settlements — `/staff/collections/settlements`

**1. Current-page observations.** `PageHeader` + ADMIN-only `ExportMenu` + an inline refresh button (not the shared
`RefreshButton`, `page.tsx:65-70`); one 5-column table — S.No., Settlement (truncated settlement id + truncated case
id + created + proposer), Amount, Status (private `STATUS_PILL` map, `page.tsx:18-20`), Action (Approve / Reject for
`PROPOSED` rows behind `PermissionGate collections:manage`, else the decision line); client pagination. Query
`['collections-settlements']`, no poll; approve/reject invalidate the list.

**2. UI/UX problems (verified).**
- A row carries **no loan or borrower identity and no navigation**: `SettlementView` has `collectionCaseId` but no
  `loanId`/`customerId`/name/outstanding (`CollectionsDtos.java:151-164`), and the case page is keyed by `loanId`, so
  a Collection Head approves a rupee amount without knowing whose loan it is. **High.**
- No status filter — `PROPOSED`, `APPROVED` and `REJECTED` are mixed, newest first; the actual worklist is buried.
- Approve/Reject fire on a single click with no confirmation (`page.tsx:125-138`).
- SoD is server-only; the DTO carries `proposedBy` and `useStaffMe()` gives the actor id, so the page could hide the
  buttons for the proposer with a one-line explanation instead of a `SOD_VIOLATION` error after the click.
- Reject accepts **no reason** (endpoint, entity, event) — the proposing officer is told "rejected" but never why
  (`CollectionsController.java:170-173`).
- A stale error banner: `approve.error ?? reject.error` is never reset, so an old `SOD_VIOLATION` stays above the table
  after a later successful reject (`page.tsx:41,73`).
- Truncated UUIDs (8 chars) with no tooltip; the refresh button is never disabled; no sticky header.

**3. Proposed subtle UI improvements.**
- Status segment chips (Proposed · Approved · Rejected · All), defaulting to **Proposed**; counts in the chips.
- `ConfirmDialog` on Approve ("Approve ₹X for *name*, loan #N?") and a `RejectDialog` that collects a reason.
- Pre-flight SoD: if `proposedBy === me.id`, replace the buttons with "You proposed this — another Collection Head must
  decide" (the backend check stays).
- Reset mutation errors on the next action; disable the refresh button while fetching; `InfoTooltip` with the full ids.
- Use `StatusBadge kind="settlement"`.

**4. Data-presentation improvements.** Add Borrower (name + mobile), Loan (#id, link to the case page) and
Outstanding columns; move the settlement id into the row's secondary line. Amount right-aligned (`num`).

**5. Transition / micro-interaction improvements.** Optimistic status change (`setQueryData` from the response the
mutation already returns) so the pill flips instantly; `rowFlash` on the decided row; `toast.success`.

**6. Performance observations (verified).** `listAll` is 2 queries (`findAll(Sort)` + one staff `IN`) but **unbounded**
— every settlement ever created, refetched after every decision (`SettlementService.java:205-216`); the mutation
response path resolves names with per-id `findStaff` although the batched `toView(Settlement, Map)` exists
(`:219-248`). The dashboard caches the same endpoint under a different key (`dashboard/page.tsx`).

**7. Backend/API optimisation opportunities.**
- Enrich `listAll` with two batched reads that already exist: `caseRepository.findAllById(caseIds)` → `loanIds`, then
  `loanDirectory.findLoans(loanIds)` (`LoanDirectoryAdapter.java:71-83`) for borrower/loan/outstanding.
- Accept `?status=PROPOSED` and `Pageable` on `GET /api/collections/settlements`.
- Add an optional `remarks` body to reject, carry it on the event/notification.
- Use `namesFor` in the single-row `toView`.
- **Authz:** `SettlementService.listAll` has no `requireOneOf` and no DSA rejection — any staff token, including DSA and
  TELECALLER, can list all settlements (`SecurityConfig` gates `/api/collections/**` on `ROLE_STAFF` only). Add the same
  guard `CollectionsService` uses, per the CLAUDE.md §7 rule for staff-open surfaces.

**8. Database optimisation opportunities.** Only `idx_settlement_case_id` exists (`V2:326`); add
`idx_settlement_status_created_at (status, created_at desc)` — it serves both the PROPOSED worklist and the sort.

**9. Expected user impact.** The approver sees who and how much before deciding, decides deliberately, and the officer
learns why a proposal was rejected. Same page, same two buttons.

**10. Implementation complexity.** UI: **S**. DTO enrichment + status param: **S–M**. Reject reason: **M** (migration +
event). Authz guard + index: **S**.

---

### 3.10 Transactions ledger — `/staff/accounting/transactions`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` + role badge; back-link; six period pills (gold
when active) and three direction tabs (navy when active); 300 ms-debounced search; refresh (icon swaps, button never
disabled); three stat cards (period totals, not page totals); a 9-column table — S.No., Date, Borrower (+PAN),
Type badge with arrow icon, Amount (signed, coloured), Reference, Proof (`PaymentProofLink` with paperclip, or "—"),
Status (raw enum text), Loan — **server-paginated** 25/50/100 with `keepPreviousData` and a 60 s poll that pauses
when the tab is unfocused (`page.tsx:97-114`). Filters reset the page via a `useEffect`.

**2. UI/UX problems (verified).**
- Amount is left-aligned proportional text (`page.tsx:313-314`); Status mixes `LoanStatus` (disbursal rows) and
  `PaymentStatus` (repayment rows) enum names under one header with no badge (`:321`).
- Proof shows "—" for every disbursal row, which can never have a proof, so the column reads as "missing" 50 % of the
  time.
- Two active-state colours in one toolbar (gold pills, navy tabs, `:189,203`).
- Changing a filter while on page > 1 fires **two** requests (new filter + old page, then page 1) and discards the first
  (`:93-95,99`).
- No column sort, no sticky header, the ledger is forced to `min-width: 84rem`; the search input is a fixed `w-64`.
- Error is a bare red paragraph; there is no in-table cue while a slow search runs behind `keepPreviousData`.

**3. Proposed subtle UI improvements.**
- Money → `num`; Status → `StatusBadge` keyed on `t.type` so disbursal and repayment vocabularies look different;
  Proof → paperclip only for repayment rows, blank (not "—") for disbursals.
- One pill style for period + direction (the `TableToolbar`).
- A faint `opacity-60` + header spinner on the table while `isFetching && !isLoading`.
- Reset `page` in the same handler as the filter change.
- Sticky header, sortable Date/Amount headers (server-side, see §7), `ErrorState` with retry.

**4. Data-presentation improvements.** A "Period · *This month* · 1,245 rows" caption under the stat cards so
readers know the totals are period-wide while the table is one page; "Updated 40 s ago" next to Refresh.

**5. Transition / micro-interaction improvements.** `rowFlash` on rows that arrived since the last poll (diff by
`(type, id)`); skeleton table on first load only.

**6. Performance observations (verified).** Per request 3–5 `SELECT`s and **no N+1** (the direction filter already skips
the unneeded half — the observer's claim to the contrary was refuted, `TransactionService.java:80-81`). But the ledger
is **materialised in Java**: every `Loan` and `Payment` in the window plus the full `LoanApplication` (~35 columns) and
full `CustomerProfile` (~50 columns incl. the credit-brief JSON) for every loan, then text-filtered, summed, sorted
and sliced in memory (`:107-168`); for "All time" that is the whole book on every page, every 60 s poll and every
"Download all" chunk (sequential page loop, `page.tsx:152-162`). `INCOMING` issues an unbounded `findAllById` IN-list.
Presigning is a local SigV4 computation — zero S3 calls (the "100 S3 calls per page" claim was refuted).

**7. Backend/API optimisation opportunities.**
- Replace the in-memory synthesis with one native `UNION ALL` (loan ⟕ application ⟕ profile; payment ⟕ loan ⟕ …)
  carrying the date/direction/text predicates, `ORDER BY`, `LIMIT/OFFSET`, and `SUM(...) FILTER (WHERE direction=…)`
  for the totals; select only the 9 rendered columns.
- A streaming `GET /api/loan/transactions/export` for "Download all" (or `Promise.all` the pages after reading `total`).
- Chunk or join away the `INCOMING` id list.

**8. Database optimisation opportunities.** No index on `loan.disbursed_on` in `V1..V73`; `payment.paid_on` is only the
trailing column of `idx_payment_status_paid_on` (`V71:31`) and the ledger has no status predicate, so the window scan
cannot use it. Add `idx_loan_disbursed_on (disbursed_on)` and `idx_payment_paid_on (paid_on)`.

**9. Expected user impact.** The Accountant reads amounts as a column, tells disbursals from repayments at a glance,
and month-end "All time" views stop taking seconds per page.

**10. Implementation complexity.** UI: **S**. SQL ledger: **M–L** (one native query + tests, behaviour unchanged).
Indexes: **S**.

---

### 3.11 Leads — `/staff/leads`

**1. Current-page observations.** `PageHeader` + Refresh; a collapsible **New lead** form (name, mobile, email, city,
employer, salary, loan interest, source, source detail, notes; Save disabled until name + 10-digit mobile,
`page.tsx:221,321`); search (300 ms debounce) + Call-status select; a two-column grid — an 8-column table (S.No.,
Name, Mobile, Source, Status chip, Outcome chip, ★, City; click a row to select) beside a 320 px `DispositionPanel`
with two independent saves (disposition = status / ★ / remarks; outcome = lead outcome / DSA note). **Server-side**
pagination 25/50/100 with `keepPreviousData` (`:69-80`); filters reset the page in a `useEffect`. Backend: one page
`SELECT` + one `COUNT` + one `staff_user SELECT` per distinct creator on the page (`LeadService.java:147-156,458-463`)
+ at most one PAN-attribution query (usually zero — telecaller leads carry no PAN).

**2. UI/UX problems (verified).**
- The global `.staff-data-table { min-width: 84rem }` (sized for the 21-column pipeline table) forces this 8-column
  table into a horizontal scroll beside the panel on every common laptop width (`globals.css:418`, `page.tsx:143-145`).
- Rows select via `<tr onClick>` with no `tabIndex`/`role`/key handler — the panel cannot be opened from the keyboard
  (`:166-171`); star buttons expose no `aria-pressed`.
- Money inputs **silently drop** invalid values ("25,000", "-500", "abc" vanish from the request — `Number(x) > 0`
  gate, `:232-235`); no inline error although `ui/Input` supports `error`.
- Two saves for one conversation ("Save disposition", then "Save outcome & note"); the outcome PUT always sends both
  fields, so the backend's patch semantics are never used (`:374`).
- The empty message "No leads yet — add one above." also shows for a search/filter that matched nothing (`:158-165`).
- Status chip is 8 px text while the outcome chip is `text-xs`; both are private colour maps (`:510-517`,
  `lead-outcome.tsx`).
- Clicking the same star again clears the rating with no affordance (`:419`).
- Changing a filter on page ≥ 2 fires one wasted request (`:65-67` vs `:69-80`); the list query fires before the role
  gate resolves (`:51,69-80,93-95`).
- The panel drops below the table under `lg`, where the update actions become hard to find.

**3. Proposed subtle UI improvements.**
- Override the min-width on this table (`min-w-[40rem]`) — or better, scope `84rem` to the pipeline table via a
  modifier class (§2.4).
- Name cell becomes a `<button>` (or row `tabIndex=0` + Enter/Space); `aria-pressed` on the stars; a "clear rating"
  ✕ next to them.
- Inline `error` on salary/amount when non-empty and not a positive number; block Save.
- One **Save** button that submits disposition then outcome in sequence (two requests, one click, one toast).
- Filtered-empty vs true-empty copy; `enabled: hasPermission(role, 'leads:manage')`; reset page in the filter handlers.
- `StatusBadge kind="lead"` for both chips at one size.

**4. Data-presentation improvements.** Add "Created *3 d ago*" and "Last called *yesterday*" (`createdAt`/`updatedAt`
are in the payload) as one muted secondary line under the name; format mobiles as `98765 43210`; quick-filter chips
for the three most-used call statuses above the select.

**5. Transition / micro-interaction improvements.** Selected row keeps the `bg-gold/10` highlight and gets a 2 px
left border in `gold`; panel content cross-fades on selection; `rowFlash` on the row after a save; `Skeleton
variant="table"` on first load; a faint in-table busy state while a page loads behind `keepPreviousData`.

**6. Performance observations (verified).** Server pagination is correct; the leftovers are: a `staff_user SELECT` per
distinct creator per page (up to page-size) to fill `createdByStaffName`, **which this page never renders**
(`LeadService.java:150,458-463`; `StaffDirectory.findStaff` is a plain `findById`, no cache); a leading-wildcard
`lower(name) LIKE '%q%' OR mobile LIKE '%q%'` search that sequentially scans `lead` (`:114-118`; `idx_lead_mobile`
cannot serve a contains-match); `LeadView` carries notes/remarks/dsaNote/email/employer/pincode/timestamps per row
while the table renders 8 fields; the attribution query hydrates full `LoanApplication` entities to read two columns.
The "invalidation refetches every page variant" and "stats endpoint unused" claims were refuted.

**7. Backend/API optimisation opportunities.** `staffDirectory.namesFor(distinctCreatorIds)` once per page (or drop the
field from the list DTO); a slim list projection; project the attribution query to `(pan, id, createdAt, status)`.

**8. Database optimisation opportunities.** `idx_lead_call_status`, `idx_lead_created_at`, `idx_lead_owner_dsa`
exist (`V43`, `V55`). Add `pg_trgm` GIN indexes on `lower(name)` and `mobile` for the contains-search (or restrict
mobile to a prefix match), and an index on `lead_outcome` (filterable since `V70`, unindexed).

**9. Expected user impact.** Telecallers see the whole table without sideways scrolling, save a call in one click, and
get told when a salary they typed was ignored.

**10. Implementation complexity.** UI: **S**. `namesFor` + projection: **S**. Trigram indexes: **S** (one migration,
`CREATE EXTENSION pg_trgm` if absent).

---

### 3.12 Telecalling — `/staff/telecalling`

**1. Current-page observations.** `PageHeader` + Refresh; two `TelecallingSection`s (Unallocated, My customers), each
with a count pill, a "Send to selected (N)" bulk button, a 12-column table (S.No., checkbox, Application, Customer ID,
Customer, Mobile, Email, PAN, Status, Completeness `3/5`, Stale days, Actions = Assign to me / owner picker / Send
reminder) and client pagination; `ApplicationInfoDialog` on the name links. One query `['staff-telecalling']`, 120 s
poll, **unpaginated** (~9.7 k pre-sanction applications in production per the service comment,
`ApplicationVerificationService.java:3033-3035`); the page splits rows into unallocated / mine on every render
(`page.tsx:58-64`) and **silently drops rows owned by other telecallers** (`:86-105`).

**2. UI/UX problems (verified).**
- Header "Select all" selects every row in the section **across all pages** and "Send to selected" has no confirm; each
  reminder is a real in-app + SMS + email send with no dedupe key or cooldown (`page.tsx:150-167,202-207`,
  `NotificationDispatcher` never sets `dedupe_key`). **High.**
- Bulk send calls the API directly, so per-row results are never recorded — after "Sending… (N/N)" nothing says which
  rows succeeded (`:154-167,294`). A failed "Assign to me" is silent (no `onError`, `assign.error` never rendered,
  `:42-45,262-277`).
- The spinner tracks only the latest mutation's `variables`; two quick clicks re-enable the first button (`:92-94`).
- Status is a grey pill for every stage (`:250-252`); completeness is a bare fraction; a missing email is an italic
  "no email" while every other empty cell is "—" (`:245-247`).
- A telecaller cannot see that a lead is already being worked by a colleague (dropped rows) and section counts
  understate the queue.
- `staleDays` falls back to 0 ("just seen") when an application has no events instead of `created_at` (`V53` added the
  column; `AdminApplicationService` comment is stale).
- Whole-table pulse block on first load; no sticky header.

**3. Proposed subtle UI improvements.**
- "Select all" selects the **visible page** only; the bulk button opens `ConfirmDialog` ("Send a reminder to 25
  applicants?") and reports a summary toast (sent / nothing pending / failed) with per-row ticks.
- Track in-flight ids in a `Set` (or `useMutationState`) so every button shows its own spinner.
- `StatusBadge kind="application"` (DRAFT neutral, KYC_PENDING info, CREDIT_EXEC_PENDING warning …).
- Completeness as a 60 px bar + `3/5` label; "no email" → "—" with a warning icon + tooltip.
- A third collapsed section "Assigned to others" (count + owner column) so dropped rows are visible.
- Render assign errors next to the button; `Skeleton variant="table"` on first load; sticky header.

**4. Data-presentation improvements.** Show the owner's name in "My customers" rows when an ADMIN views the page;
sort each section stale-first (the backend already does) and colour `Stale ≥ 3 d` (already red — keep).
Amount columns are **deliberately absent** (no amount exists before sanction; `TelecallingView` javadoc) — do not add
them.

**5. Transition / micro-interaction improvements.** Row moves between sections after "Assign to me" get `rowFlash`
in the destination section; reminder success shows a 2 s inline tick before the toast.

**6. Performance observations (verified).**
- Every `CustomerOwnerPicker` (one per row) calls `useStaffSession()`, a raw `fetch('/api/auth/staff/me')` outside
  React Query — up to **50 BFF round trips** per render of two 25-row sections (`customer-owner-picker.tsx:30-34`).
  **High.** (The staff list itself is a shared RQ key — one request.)
- `listForTelecalling` is batched (five queries, no N+1) but loads **every `application_event` row** for every queued
  application (notes up to 2000 chars) to keep one timestamp each — `findLatestEventAt` (max(at) group by) exists
  and is unused (`AdminApplicationService.java:149-151`); it also hydrates full `LoanApplication` (26 cols) and
  `CustomerProfile` (45 cols) entities for an 11-field DTO. `findByStatusNotIn` over 10 statuses returning most of the
  table will not use `idx_loan_application_status`.
- `assignOwner` runs `CustomerService.detail()` **twice** per click (~10+ queries each, incl. the per-loan payments
  N+1) as an existence check and to build a response the page then discards (`CustomerService.java:807-841`).
- `sendKycReminder` loads full `ApplicationVerification` entities (raw JSON) to read `checkType/status`, once per
  reminder (`ApplicationVerificationService.java:3421-3423`).

**7. Backend/API optimisation opportunities.**
- Replace `useStaffSession` in the picker with `useStaffMe` (§2.3).
- `?owner=unallocated|me|others&page&size` on the telecalling endpoint, filtering `customer_owner` in SQL and ordering
  by latest event in the query; return a page envelope.
- Use `findLatestEventAt(appIds)` in 1000-id chunks; add interface projections for the two entities.
- `assignOwner`: `existsByCustomerId` + a small `{customerId, ownerStaffId, ownerName}` response.
- Reminder cooldown: `dedupe_key = KYC_REMINDER:{appId}:{day}` or a `last_reminded_at` column; refuse inside the window.
- Use the existing `CaseFailureRow` projection in `sendKycReminder`.

**8. Database optimisation opportunities.** Add `application_event (application_id, at desc)` so the per-app max is an
index-only read (also helps `/staff/admin/all-applications` and the customer 360).

**9. Expected user impact.** Telecallers stop spamming borrowers by accident, see the outcome of every click, and can
tell which leads a colleague already owns. The page opens with 25 rows instead of ~10 k.

**10. Implementation complexity.** UI: **S–M**. Session dedupe: **S**. Endpoint pagination + projections: **M**.
Reminder cooldown: **S–M**.

---

### 3.13 Staff performance — `/staff/performance`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` + shared `RefreshButton`; `PeriodPicker` with a
"Clear filters" button when column filters are active; five `StatCard`s (Approved / Rejected / Total actions / In queue
now (live, gold) / Calls); a Recharts daily line chart (roster-wide, unfiltered — labelled as such); a 13-column table
with Excel-style `FilterableTh` on Staff and Role, `SortableTh` on ten columns, default sort `totalActions desc`
(`page.tsx:79-89,222-254`); client pagination. One query `['staff-performance', from, to]` with `retry:false` and
**no** `keepPreviousData` (`:67-71`); this page never sends `staffId`.

**2. UI/UX problems (verified).**
- The stat tiles show **fabricated zeros** during every fetch and on every period change (`rows=[]` → reduce from 0,
  `:73,97-106`) — contradicting the service's own "an unmeasured value never reads as 0" rule.
- On any error the page returns before the header actions and the `PeriodPicker` mount, so a transient failure (not
  retried) leaves no refresh, no period change and no retry — only a reload recovers (`:116-123`).
- "Value moved" and every numeric column are left-aligned (`:284`, `globals.css:420`).
- The empty copy "No staff activity in this period." can never mean that (every roster member yields an all-zero
  row); it appears only when column filters exclude everyone or the roster is empty (`:258-263`).
- Heads see only **active** teammates (`listActive`) while ADMIN sees everyone (`listEveryone`), so a Head's period totals
  silently drop a deactivated executive's work (`DecisionHistoryService.java:417-422`).
- "In queue now" is a live snapshot beside windowed cards (tooltip exists, still confusing); the chart is unfiltered
  beside filtered totals; sort is not URL-persisted; export writes raw ISO timestamps; no sticky header.

**3. Proposed subtle UI improvements.**
- `placeholderData: keepPreviousData`; tiles render "—" (or a stat skeleton) while pending, never 0.
- Render the header + period picker in the error branch and add `ErrorState` with retry.
- `num` class on every numeric column; sticky header.
- Two empty-state messages: "No staff match the current filters" vs "No staff in your scope".
- Visually separate "In queue now" (a hairline divider + "live" caption) from the four windowed tiles.
- Persist sort in the URL like `/staff/loans` does; format export timestamps with `formatDateTime`.

**4. Data-presentation improvements.** Split "First / last action" into two sortable columns; an "(inactive)"
`Badge` instead of muted text; a role filter chip row above the table (the roster mixes credit, collections and
telecalling roles whose metrics do not compare).

**5. Transition / micro-interaction improvements.** Tile values cross-fade on period change instead of jumping to
0 and back; chart uses Recharts' built-in `isAnimationActive={false}` under reduced motion.

**6. Performance observations (verified).** `summary()` is 6–7 **set-based** queries with no N+1 (roster, events,
pending GROUP BY, call-log GROUP BY, interaction-log GROUP BY, payments). All indexes are present (`V59`, `V60`,
`V61`, `V5`). Two over-fetches remain: the payment leg loads full `Payment` entities to count and sum fields this page
never renders (`PaymentRepository.java:56-59`), and "All time" loads the roster's entire audit trail as entities
including the 2000-char `notes` column with an unused `ORDER BY` (`DecisionHistoryService.java:231-243,255-270`).
`countGroupByAssignedExecutive` aggregates every executive in the company, not the roster. The dashboard fetches the
same endpoint under a different key with a 10 s poll. The column-filter popover is already memoised (the observer's
"re-filters per keystroke" claim was refuted).

**7. Backend/API optimisation opportunities.** Project the payment leg to `decidedBy, status, count, sum`; push the
per-actor counts / min-max / distinct-day and the daily trend into `GROUP BY` projections, keeping an entity read only
for the money/turnaround rows; add `and a.assignedExecutiveId in :staffIds` to the pending count; use `listAll(role)`
for the Head roster; share the query key with the dashboard.

**8. Database optimisation opportunities.** None required — every filtered/joined column on this path is indexed.

**9. Expected user impact.** Heads stop reading "0 approved" for a second every time they change the period, can
recover from a blip without reloading, and get honest totals when a teammate leaves.

**10. Implementation complexity.** UI: **S**. Backend projections: **M**. Roster fix: **S**.

---

### 3.14 My decisions — `/staff/my-decisions`

**1. Current-page observations.** `PageHeader` with a role-aware subtitle; a "whose decisions" `Select` for Heads and
ADMIN (fed by `GET /decisions/inspectable`, which for ADMIN returns only *active* credit + collections role holders,
`DecisionHistoryService.java:434-444`); `PeriodPicker` (URL `staffId/from/to` read once on mount, never written
back, `page.tsx:59-69`); five `StatCard`s; a 13-column `staff-data-table` (When, Application, Customer ID, Customer
(link), PAN, Decision, Outcome (plain title-cased text), Amount `₹`, Repayment date, Assignee, Txn ref, Remark with
raw notes in `title`); client pagination. Two data queries per load — `['decisions', staffId, from, to]` and
`['decisions-summary', staffId, from, to]` (`:86-98`) — with the table replaced by a text "Loading…" and no
`keepPreviousData`.

**2. UI/UX problems (verified).**
- **Correctness (high):** with the default "my decisions" the page sends no `staffId`; the server then aggregates the
  caller's **whole roster** (ADMIN → everyone, Head → team, `DecisionHistoryService.java:404-430`) and the page reads
  `rows[0]` for the stat cards (`page.tsx:99`) — a Head or ADMIN can see **another staffer's totals labelled as their
  own**. The table (`/decisions`) is scoped to the caller, so cards and rows disagree.
- Actions outside `DECISION_ACTIONS` (notably `ADMIN_FORCE_DISBURSE`) are counted in "Total actions" but never listed
  in the table (`:100,354-360`).
- No search or sort within the loaded list; the application id is not a link (only the customer name is).
- Remarks live only in a `title` attribute (hover-only, invisible to keyboard and screen readers, `:234`).
- Period change unmounts the table to "Loading…" and resets to page 1; the Loader2 next to the selector flickers on
  every refetch.
- Customer ID and Application ID sit side by side in identical mono styling; no sticky header; `min-width: 84rem`
  with no sticky identity column although the helpers exist.

**3. Proposed subtle UI improvements.**
- Always pass the caller's own id when no `staffId` is chosen (one-line fix in `page.tsx` or default `staffId` to
  `ActorContext.id` in the service); assert on the client that `summaryQ.data.rows.length === 1`.
- Include the force-disbursement action in the table (it is a decision) so "Total actions" equals the row count, or
  caption the card "incl. N routing actions".
- A client-side search box (application id / customer) and `SortableTh` on When / Decision / Amount — the list is
  already in memory.
- Link the application id to `/staff/applications?open=<id>`; render the remark as a truncated cell with an
  `InfoTooltip`; `StatusBadge` for Outcome; `num` on Amount; sticky header + `staff-sticky-identity` on the customer.
- `keepPreviousData` + `Skeleton variant="table"` on first load; write `from/to/staffId` back to the URL so deep links
  from `/staff/performance` stay shareable.

**4. Data-presentation improvements.** Group rows by day with a muted date header (same interaction as customers);
show the daily activity bar (already returned as `daily` in the summary) under the stat cards so the page has the
same rhythm as `/staff/performance`.

**5. Transition / micro-interaction improvements.** Stat values cross-fade on period change; the selector's spinner
becomes the header refresh spinner; `rowFlash` on newly loaded rows after a period change.

**6. Performance observations (verified).** `/decisions` and `/summary` each run `findForActorsInWindow` over the
same actor + window on every load — the same rows read and parsed twice (`DecisionHistoryService.java:98-99,242-243`);
the list is unbounded (no `Pageable`) with `DECISION_ACTIONS` filtered in Java after loading every event of the actor;
full-entity over-fetch (`CustomerProfile` for name + PAN, `LoanApplication` for `customerId`, `Payment` rows for
count/sum); per-distinct-assignee `staff_user SELECT`s although `findStaffByIds` exists (`StaffDirectory.java:37`; the
service comment saying otherwise is stale). The `(actor_id, at)` index exists (`V59:44`).

**7. Backend/API optimisation opportunities.** Default `staffId` to the caller in `summary` when absent from this
page (or make the page pass it); push the `DECISION_ACTIONS` filter and a `LIMIT` into the events query; return the
summary's per-person row from the same event read (`/decisions?withSummary=true`) so the trail is read once; batch
assignee names with `findStaffByIds`; project profile/application lookups to the three fields used.

**8. Database optimisation opportunities.** None beyond the existing `(actor_id, at)` index; a partial index on
`action in (DECISION_ACTIONS)` is unnecessary once the filter rides the actor index.

**9. Expected user impact.** Heads stop seeing someone else's numbers under "Your decisions"; anyone can find one
file in their history without paging; the page no longer blanks on every period change.

**10. Implementation complexity.** Correctness fix: **XS**. UI: **S**. Backend read-once + batch names: **S–M**.

---

### 3.15 All applications — `/staff/admin/all-applications`

**1. Current-page observations.** `PageHeader` + `ExportMenu` + Refresh; a `w-80` search (no debounce), a Completeness
select (All / Complete / Incomplete) and a muted "X of Y" count; a 13-column table (S.No., App, Customer (two-line),
PAN, Account, IFSC, Mobile, Status pill, Completeness pill, Amount, Credit, Risk, Open) with an ⓘ Info button and an
Open link per row; client pagination; `ApplicationDetailDialog` (80vw, 13 tabs) and `ApplicationInfoDialog`. One
query `['admin-all-applications']`, no poll, refetch only on Refresh. Skeleton on **first load only** — a Refresh keeps
the rows and spins the header icon (the observer's "rows disappear" claim was refuted, `page.tsx:101,129`).

**2. UI/UX problems (verified).**
- The query fires for **every role before `/me` resolves**; non-ADMINs get `FORBIDDEN_ROLE`, retried once, then the
  page shows "Admin access only" (`page.tsx:63,68,83-85`).
- Search recomputes the filter on every render with no `useMemo`/debounce, so `usePagination`'s memo never hits
  (`:64-81,109`).
- The incomplete badge says " · no e-sign" but the flag is `termsAcceptedAt` (screen-1 T&C), not the Aadhaar eSign
  (`:174-177`) — staff look at the wrong step.
- Status and Completeness are private pills; no sort; no sticky header; no "clear filters"; the row count is easy to miss.
- In the detail dialog, `briefQ` waits for `appQ` only to skip `DRAFT` although the endpoint already returns an
  `available=false` shell (`application-detail-dialog.tsx:204-208`); `appQ` polls every 8 s while open even in a
  read-only register.

**3. Proposed subtle UI improvements.**
- `enabled: myRole === "ADMIN"` and `retry: false` on 4xx; `useMemo` the filtered rows and debounce the needle 200 ms.
- Rename the badge to " · T&C not accepted" (tooltip "Terms & Conditions not accepted").
- `StatusBadge` for status and completeness; `num` on Amount; sticky header; a "Clear" chip when a filter is active;
  the count inside the `TableToolbar`.
- Start `briefQ` with `enabled: open`; pause the 8 s dialog poll when the document is hidden.

**4. Data-presentation improvements.** Show `currentStageEnteredAt` (already fetched, only exported) as a "Stage
since" column; group by status with a collapsible header (same interaction as the customers date groups); sort by
Status / Amount / Stage since.

**5. Transition / micro-interaction improvements.** Skeleton table on first load; `rowFlash` after a Refresh on rows
whose status changed; dialog exit fade.

**6. Performance observations (verified).** `listAll` = `findAll()` unpaged and unordered (sorted in Java), full
`CustomerProfile` entities (~50 columns incl. `credit_brief_facts` JSONB) for **every** application, bureau states and
required-passed counts chunked by 1000 (+ re-apply hops), `findStaff` per distinct assignee (the batched `namesFor`
exists), and `findByApplicationIdInOrderByAtDesc(all ids)` = **the whole `application_event` table read and sorted**
to keep one timestamp per application (`AdminApplicationService.java:65-104`). The DTO carries 34 fields, the table
renders 18; 464 KB at 550 rows (`docs/perf`). Indexes on `loan_application` do not help a filterless `findAll`; there
is no `application_event (application_id, at)` index.

**7. Backend/API optimisation opportunities.** `page/size/q/complete` params →
`applicationRepository.findAll(spec, PageRequest.of(page, size, Sort.by(DESC, "id")))`, enrich only the page's ids;
`findLatestEventAt(chunk)`; `namesFor(distinctAssigneeIds)`; a light list DTO with export-only fields served from
`ExportMenu.onOpen`; an interface projection for the profile columns actually read; `eventViews` resolves actors with
one `namesFor`.

**8. Database optimisation opportunities.** Add `idx_application_event_application_at (application_id, at desc)`.

**9. Expected user impact.** The ADMIN register opens in one page instead of the whole book; the badge stops
misdirecting staff to the eSign step.

**10. Implementation complexity.** UI: **S**. Server pagination + projections: **M**. Index: **S**.

---

## 4. Combined implementation plan

Ordered by **impact ÷ disruption**. Each phase is independently shippable and leaves every workflow, route and role
exactly where it is. Sizes: XS < ½ day · S ≈ 1 day · M ≈ 2–4 days · L ≈ 1–2 weeks (one engineer).

### Phase 0 — correctness fixes that fell out of verification (ship first, ½–1 day total)

| # | Fix | Page | Size |
|---|---|---|---|
| 0.1 | `/staff/my-decisions` sends the caller's own `staffId` when none is chosen (or the service defaults it) so a Head/ADMIN never sees another staffer's totals under "your decisions" | §3.14 | XS |
| 0.2 | `CustomerEditDialog` / `CaseFailureDialog` invalidate `['customers-page']` + `['customers-summary']` (not the legacy `['customers']`) | §3.3 | XS |
| 0.3 | `AdminLogPaymentDialog` invalidates `['collections-case-by-loan', loanId]` + `['collection-payments']` | §3.8 | XS |
| 0.4 | `SettlementService.listAll` gets the same `requireOneOf`/DSA rejection as `CollectionsService` (any staff token can list settlements today) | §3.9 | XS |
| 0.5 | `rbac.ts` stops granting `verification:retry` to credit roles (backend is ADMIN-only) — or the backend relaxes; the two must agree | §3.5 | XS |
| 0.6 | Rename " · no e-sign" → " · T&C not accepted" on the all-applications completeness badge | §3.15 | XS |
| 0.7 | Retry in-flight guard (`RETRY_IN_PROGRESS`) and a KYC-reminder cooldown — both prevent real money/SMS spend | §3.5, §3.12 | S |
| 0.8 | `manualDecision` appends a `VERIFICATION_OVERRIDE` `application_event` so overrides are auditable | §3.5 | S |
| 0.9 | Dashboard: a per-source `failed` flag so a backend outage renders an error notice instead of "You're all caught up" | §3.1 | S |
| 0.10 | Dashboard Refresh uses `invalidateQueries` (active only) instead of `.refetch()` on disabled queries | §3.1 | XS |

### Phase 1 — shared foundation (≈ 1 week; every page benefits, zero workflow change)

1. `Skeleton`, `EmptyState`, `ErrorState` (+retry), `Toaster`/`toast`, `ConfirmDialog`, `Money`, `StatusBadge`,
   `TableToolbar` in `components/ui` (§2.1); route-level `src/app/staff/loading.tsx`.
2. CSS: sticky `thead`, `.num`, working row hover, `rowFlash`, dialog exit, reduced-motion coverage (§2.2); scope
   the `84rem` min-width to the pipeline table via a modifier class so 8-column tables stop scrolling sideways.
3. `ui/Dialog` gains the focus trap / focus restore / scroll lock `ui/Drawer` already has, an exit animation and a
   `size` prop (`md` 460 px · `lg` 56 rem · `xl` 80 vw) replacing the seven `!max-w-*` overrides.
4. One session query (`useStaffSession` → `['staff-me']`); shared query keys for performance and settlements; the
   `CustomerOwnerPicker` reads the role from React Query (removes up to 50 `/me` fetches on the telecalling page).
5. `keepPreviousData` on every keyed list query; page resets move into filter handlers.
6. Converge `PeriodPicker` and `QueueDateFilter` on one pill style; replace 26 hand-rolled refresh buttons with
   `RefreshButton`.

**Acceptance:** `npx tsc --noEmit` and ESLint clean; Playwright smoke on the 15 routes; a visual diff shows only
header stickiness, number alignment, hover and loading-state changes.

### Phase 2 — per-page polish (small, page-local; ≈ 1–1.5 weeks in parallel with Phase 3)

| Page | Items (from §3) | Size |
|---|---|---|
| Dashboard | section skeletons that keep shape; "Updated n s ago"; tabular stat values; no collections queries for roles without the section (§3.1) | S |
| Live applications | `StatusBadge`; `num`; labelled ☐ column + bulk hint; "Updated n s ago" per panel; verify/reject icon buttons; closed-panel immediate fetch; dialog `size="xl"` with 12.8 px body text (§3.2) | S–M |
| Customers | Columns menu; disabled-with-tooltip mixed reject; open dialog from `latestApplicationId`; bounded-concurrency bulk actions; DPD column | S–M |
| Customer 360 | toasts on every card; per-card skeletons; corrections accordion; 40-day salary-day warning; exposure line; audit filter chips | S–M |
| Verifications | collapsible buckets; unit label; confirm on reminder/override; neutral EPFO chip; hide Retry for non-ADMIN | S |
| Loans | `num`; sticky; ✕ search; breakdown tooltip + "Settled" badge; row-seeded dialog; page reset | S |
| Collections | overdue row cue; days-to-due; selection kept across pages; bulk progress; select width | S–M |
| Collection case | error branches; toasts; PTP date logic; decimal inputs; per-card skeletons; settlements list | S–M |
| Settlements | status chips (default Proposed); confirm + reject reason; SoD pre-check; borrower/loan columns | S |
| Transactions | `num`; typed status badge; proof blank for disbursals; one pill style; in-table busy veil | S |
| Leads | min-width override; keyboard rows; inline money errors; one Save; filtered-empty copy | S |
| Telecalling | page-only select-all + confirm + summary toast; per-row spinners; status badges; "Assigned to others" | S–M |
| Performance | `keepPreviousData` (no fake zeros); error branch keeps header; `num`; two empty messages | S |
| My decisions | search + sort; linked application id; remark tooltip; URL write-back; day groups | S |
| All applications | role-gated query; memoised filter; `StatusBadge`; stage-since column; clear-filters | S |

### Phase 3 — backend and database (each item is an additive parameter, projection, batch or index; ≈ 2–3 weeks)

Ordered by verified cost. None changes an API's shape for existing callers (new query parameters are optional).

| # | Change | Evidence | Size |
|---|---|---|---|
| 3.1 | **Stop the sidebar badge fetching the full worklist**: `GET /collections/worklist/counts` (per-bucket count + outstanding) used by the six cards and the shell badge | `staff-shell.tsx:120-129` polls the unpaginated worklist every 30 s on every page | S |
| 3.2 | **Verification overview**: projections (no `raw_response` JSON), SQL tallies (`GROUP BY status`), SQL paging, server-side "not started" | `ApplicationVerificationService.java:3455-3525` | M |
| 3.3 | **Customer 360 read**: `findByLoanIdIn`, batched `latestProfile`, `findStaffByIds`, drop `providerResponse` from the customer read, batch `activity()` | `CustomerService.java:689-769,1229-1274` | S–M |
| 3.4 | **Telecalling**: `findLatestEventAt` + projections + `?owner=&page=&size=`; `assignOwner` without two `detail()` calls | `AdminApplicationService.java:138-178`, `CustomerService.java:807-841` | M |
| 3.5 | **All applications**: `Pageable` + `q` + `complete`, `findLatestEventAt`, `namesFor`, light list DTO | `AdminApplicationService.java:65-104` | M |
| 3.6 | **Transactions ledger** as one SQL query with `LIMIT/OFFSET`, `FILTER` totals and a streaming export | `TransactionService.java:71-169` | M–L |
| 3.7 | **Loans register**: `Pageable` + `segment` + `sort`, `q` before enrichment, breakdown/settlement fields on the row, `collection_case` read once | `LoanRegisterService.java`, `RepaymentService.java:400-403` | M |
| 3.8 | **Collections worklist**: `Pageable` + bucket/q/sort in SQL, `namesFor`, de-duplicated batch reads, `bulk-assign` endpoint | `CollectionsService.java:193-226` | M |
| 3.9 | **Collection case**: reuse the resolved `LoanSummary` (`openCase`, `raise`, `assign`), skip `findLoans` for case-scoped payments, `loggedByName` on interactions, memoised `scope()` | `CollectionsService.java:89-106,365-371`, `CollectionPaymentService.java` | M |
| 3.10 | **Customers page**: `count(*) over()`, one `findStaffByIds`, pass maps into `failures()`, projection for `decidedCustomerIds`, chunked export | `CustomerService.java:292-309,227-239`, `CustomerBookQuery.java` | M |
| 3.11 | **Settlements**: enrich rows (batched case + loan reads), `?status=`, reject `remarks`, `namesFor` in `toView` | `SettlementService.java:204-248` | S–M |
| 3.12 | **Decision history**: read the trail once for list + summary, push `DECISION_ACTIONS` + `LIMIT` into SQL, `findStaffByIds`; performance page projections | `DecisionHistoryService.java:98-99,242-243` | S–M |
| 3.13 | **Leads**: `namesFor` (or drop the unrendered field), slim list projection | `LeadService.java:147-156,458-463` | S |
| 3.14 | **Live applications queues**: optional `page/size` on `GET /applications?status=` and `credit-queue`, `creditHeadQueue` date window in SQL, slim `QueueRowView` (see §3.2) | `ApplicationController.java:104-133`, `ApplicationFlowService.java:938-949` | M |
| 3.15 | **Dashboard aggregates**: `bookStats` as SQL aggregates over the scoped customer CTE, `scope()` once per request, `trends` as three `GROUP BY date` projections, count endpoints for hero/badges (payouts, pending repayments, worklist buckets), `listCases` scoped in SQL | `CustomerService.java:324-427`, `DashboardService.java:39-66`, `CollectionsService.java:169` | M |

**Indexes (one migration, `V74`):**
`idx_application_event_application_at (application_id, at desc)` · `idx_loan_disbursed_on (disbursed_on)` ·
`idx_payment_paid_on (paid_on)` · `idx_settlement_status_created_at (status, created_at desc)` ·
`idx_lead_outcome (lead_outcome)` · `idx_loan_application_assigned_exec (assigned_executive_id, status)` · `uq_collection_case_loan_id` (after de-dup) · optional `pg_trgm` GIN indexes on
`customer_profile(lower(full_name))`, `(pan)`, `(mobile)` and `lead(lower(name))`, `(mobile)` for the contains-searches.
All are additive; historical migrations stay immutable.

### Phase 4 — polling policy (½ week, after Phase 3.1)

Keep the product intervals but make them cheap: the sidebar badge and dashboard read the counts endpoints; queue polls
add `refetchIntervalInBackground: false` explicitly (already the default) and a visible "Updated n s ago"; the detail
dialog's 8 s poll pauses when `document.hidden`; a single `/me/badges` (unread count + bucket counts) can later replace
the 20 s bell + 30 s badge polls with one request.

### Priority matrix

| | Low disruption | Medium disruption |
|---|---|---|
| **High impact** | Phase 0 fixes · sticky header + `num` + row hover · Skeleton/Empty/Error/Toast · session dedupe · counts endpoint for the badge (3.1) · verification projections (3.2) · customer 360 batching (3.3) | telecalling / all-applications / loans / collections server pagination (3.4, 3.5, 3.7, 3.8) · ledger SQL (3.6) |
| **Medium impact** | `StatusBadge` everywhere · confirm dialogs · Columns menu · one period control · `keepPreviousData` | bulk-assign / batch reject endpoints · settlement enrichment (3.11) |
| **Lower impact** | reduced-motion coverage · URL write-back · export timestamp formatting | trigram indexes · `/me/badges` |

### What deliberately does **not** change

Navigation and the sidebar; the one-console composition of queues; every action cluster and its SoD/role gates; the
BFF layout and JWT model; the design tokens by name; the 8 s / 60 s product polling decisions; the borrower-facing
app.

### Verification of the plan itself

- Frontend: `npx tsc --noEmit`, `npm run lint`, Vitest for `StatusBadge` maps and `usePagination`, Playwright smoke on
  the 15 routes per role (login as each seeded role, assert the page renders and the first action button is present).
- Backend: `./mvnw test`; for each Phase-3 item a repository test asserting the query count with the existing
  `docs/perf` harness (the customers page already has a statement-count baseline: 51 → target ≤ 20).
- Never claim a page is faster without the statement count and the payload size before/after.

---

## Appendix A — verification ledger (observer claims that were refuted)

Each page was observed by one agent and independently verified by another. The verifier re-read every cited line. Claims marked REFUTED below are **not** in §3 and should not be re-reported; PARTIAL claims were corrected before use. Counts are per page: confirmed / partial / refuted.


### 3.1 Dashboard — 24 / 30 / 12

- **Refuted:** dbAccess[2] CustomerService.bookStats: single query computing 15 metrics, SQL window functions/GROUP BY, backend may cache → Not one query and not SQL aggregation. bookStats(): mineCustomerIds() = owner ids (1 query) + decidedCustomerIds() which loads the actor's ENTIRE all-time application_event trail as entities and filters in Java (227-234) + custome… (`backend/navix-loan/src/main/java/com/navix/loan/service/CustomerServic…`)
- **Refuted:** dbAccess[5] CollectionsService.listCases: scoped to caller's role (Executive own cases, Head team, Admin all), unpaged, JOIN on staff_user → No role scoping: caseRepository.findAll(Sort by createdAt DESC) returns every collection_case row for any collections-capable staff role (requireCollectionsStaff rejects only BORROWER/ANONYMOUS/DSA). Loans and officer names are ba… (`backend/navix-collections/src/main/java/com/navix/collections/service/…`)
- **Refuted:** dbAccess[8] DashboardService.trends: SELECT DATE(created_at), COUNT(*) … GROUP BY per table (3 queries or UNION) → Three queries, but none aggregates in SQL: findByActionAndAtGreaterThanEqual('CREATE', since), findByDisbursedOnGreaterThanEqual(start) and findByStatusAndPaidOnGreaterThanEqual(VERIFIED, start) each return full entity lists that… (`backend/navix-loan/src/main/java/com/navix/loan/service/DashboardServi…`)
- **Refuted:** dbAccess[10] RepaymentService.transactions: paged query on the ledger, default size 20, totals company-wide → TransactionService.listTransactions is not a paged query: with no from/to it runs loanRepository.findAllForRegister(null,null) (every loan) and paymentRepository.findAllForLedger(null,null) (every ledger payment), resolves borrowe… (`backend/navix-loan/src/main/java/com/navix/loan/service/TransactionSer…`)
- **Refuted:** dbAccess[11] FeatureFlagService.isEnabled single-row fetch, likely cached server-side → The endpoint calls FeatureFlagService.all(), which is repository.findAll() over the feature_flag table on every request with no cache (the codebase deliberately has none — CLAUDE.md §12 'no cache → instant'). Not isEnabled(key). (`backend/navix-common/src/main/java/com/navix/common/featureflag/Featur…`)
- **Refuted:** tables[0] QueueTable: 7 columns; not sortable/filterable; paginated: none (full list); rowActions none (544, 729); stickyHeader unverified → QueueTable IS client-side paginated: usePagination(apps) with initialPageSize 25 and a PaginationBar (status-queue.tsx 302, 357-364; pagination.tsx 21-34). It renders 17 columns (S.No., Application, Customer ID, Date, Customer, Mo… (`frontend/src/components/staff/pipeline/status-queue.tsx:287-366, front…`)
- **Refuted:** friction[0] casesQuery/settlementsQuery/collectionPaymentsQuery still fire and refetch every 60s for roles without 'collections' (e.g. CREDIT_EXECUTIV… → All three are `enabled: mounted && !!role && has('collections')` (376, 383, 389), and only COLLECTION_EXECUTIVE, COLLECTION_HEAD and ADMIN have 'collections' (SECTIONS 104-114). They do not mount-fetch or poll for other roles. The… (`frontend/src/app/staff/dashboard/page.tsx:374-391,104-114,484-499`)
- **Refuted:** friction[3] admin-only queries (stats/trends/txns/segment) fire for non-admin roles → The observer's own evidence concedes it: all four are `enabled: mounted && isAdmin`, so they never mount-fetch or poll for non-admins. Exception (not raised by the observer): refreshAll() refetches segmentSummaryQuery for everyone… (`frontend/src/app/staff/dashboard/page.tsx:337,397,403,410,489`)
- **Refuted:** friction[5] no keyboard navigation: period picker may not support Tab/Arrow; StatCards lack tabindex/focus ring → PeriodPicker is native <button>s and <input type="date">, so Tab/Space/Enter/arrow (inside the date field) work by default. Unlinked StatCards are static display, not controls, so tabindex would be wrong; every actionable card is… (`frontend/src/components/staff/period-picker.tsx:31,35,41-55, frontend/…`)
- **Refuted:** friction[13] pipeline/segment bars could overflow for orgs with 100+ statuses or custom segments → Both are fixed enumerations: PipelineBar renders the ApplicationStatus enum and SegmentBar renders 'all' + the 12 SEGMENTS constants (1068). Neither can grow with organisation size. (`frontend/src/app/staff/dashboard/page.tsx:1068,1083-1096, frontend/src…`)
- **Refuted:** friction[16] decidedIds useMemo re-keys the query whenever the decision list order changes, causing spurious refetches → decidedIds is a Set → deduped → numerically sorted array (345), and TanStack hashes queryKeys structurally (JSON), so the same ids in any incoming order yield an identical key — no refetch. Only the cheap useMemo recomputes. (`frontend/src/app/staff/dashboard/page.tsx:344-355, frontend/src/lib/cu…`)
- **Refuted:** perf[6] polling continues when the tab is hidden / battery saver → TanStack Query v5.62 (package.json:25) defaults refetchIntervalInBackground to false and the interval refetch checks focusManager.isFocused() (document.visibilityState), so all intervals pause while the tab is hidden; the provider… (`frontend/src/app/staff/dashboard/page.tsx:300-410, frontend/src/lib/qu…`)

### 3.2 Live applications — 23 / 33 / 11

- **Refuted:** apiCalls[13] ADMIN makes 9+ simultaneous requests every 8s (KYC_PENDING, CREDIT_EXEC_PENDING, SANCTIONED, DISBURSEMENT_PENDING fast-track, standard, f… → Exact count for ADMIN on the 8s tick = 5: credit-queue, CREDIT_EXEC_PENDING, DISBURSEMENT_PENDING (one request shared by fast-track+standard, same query key), DISBURSEMENT_FAILED, staff-pending-repayments. Plus 2 collection-paymen… (`frontend/src/app/staff/applications/page.tsx:160-234; frontend/src/com…`)
- **Refuted:** dbAccess[1] creditHeadQueue filters KYC_APPROVED AND amountRequested not null; date range on created_at; no pagination; N+1 on enrichment → Returns KYC_PENDING plus KYC_APPROVED via two full findByStatusOrderByCreatedAtDescIdDesc calls, no amountRequested check; the date range is applied in memory after both status sets are loaded. Enrichment is batched (see dbAccess[… (`backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationFlo…`)
- **Refuted:** dbAccess[6] CreditBriefService.view joins credit_brief + document; one query → profileRepo.findByApplicationId + verificationRepo.findByApplicationIdAndCheckType(BUREAU) fetched TWICE (providerResponse() and bureauStateService.state()) + briefDocument (twice on the generation path) = 4-5 queries; method is @… (`backend/navix-loan/src/main/java/com/navix/loan/service/CreditBriefSer…`)
- **Refuted:** loadingStates: 'Dialogs appear instantly (no enter/exit animation)' → Every Dialog renders `.modal-overlay.show`, which has `animation: fadeUp .25s ease` (opacity 0->1, translateY 14px->0). (`frontend/src/app/globals.css:586, 662; frontend/src/components/ui/dial…`)
- **Refuted:** transitions: none; buttons have no active/pressed animation (no scale or shadow); hover via Tailwind only → `.btn` has `transition: transform .3s, box-shadow .3s` and `.btn:active { transform: scale(.985) }`; `.btn-outline:hover` lifts (translateY(-2px) + box-shadow); dialogs fade-up .25s. Row/pagination changes are indeed instant. (`frontend/src/app/globals.css:151-160, 169, 586, 662`)
- **Refuted:** friction[4] Cannot change the date range without closing the search; search + date not composable → Period tabs and the search box are independent state in the same header; both are folded into every query key (`range.from, range.to, query`), so changing the date while a search is active simply re-queries. Only a 'clear' afforda… (`frontend/src/app/staff/applications/page.tsx:61-74, 101-112, 135; fron…`)
- **Refuted:** friction[9] Bulk Assign/Reject modal flow unclear; risk of accidental bulk actions → Both bulk actions open an explicit Dialog (RejectDialog / AssignDialog) with a reason/executive picker and a confirm button; nothing fires on selection alone. (`frontend/src/components/staff/pipeline/bulk-actions.tsx:161-206, 232-2…`)
- **Refuted:** perf[2] Dialog opens with 4 parallel queries; app blocks behind profile; waterfall not parallel → Self-contradictory and wrong: app/events/profile are independent and parallel; brief is a dependent (enabled on appQ.data) second hop; no query waits on another - the body renders as soon as appQ resolves regardless of profileQ. (`frontend/src/components/staff/application-detail-dialog.tsx:192-215, 3…`)
- **Refuted:** perf[3] ClosedPanel does not fetch on expand; waits for the 8s tick → React Query v5 fetches immediately when `enabled` flips false->true and the query has no data (stale): QueryObserver.setOptions -> shouldFetchOptionally -> fetch. The 8s interval only governs subsequent polls. (`frontend/src/app/staff/applications/page.tsx:392-397`)
- **Refuted:** perf[4] Debounce: each keystroke fires a request 300ms later; 10 keystrokes = 10 requests → The effect cleanup `clearTimeout(t)` cancels the pending timer on every keystroke, so exactly one setQuery (and one request per panel) fires 300ms after the LAST keystroke. Only a pause >= 300ms mid-typing produces an extra reques… (`frontend/src/app/staff/applications/page.tsx:71-74`)
- **Refuted:** perf[7] customerQ has no staleTime so it refetches every 60s while mounted → staleTime never triggers a refetch by itself; with no refetchInterval and refetchOnWindowFocus:false, a mounted query never refetches on its own. staleTime only decides whether a remount/focus/reconnect refetches. (`frontend/src/components/staff/application-detail-dialog.tsx:223-227; f…`)

### 3.3 Customers — 24 / 17 / 3

- **Refuted:** Segment counts aggregated in CustomerSegments.counts(rows) after hydrate - frontend segments.ts:115-135, 'client-side helper used by bookStats() servi… → A Java service cannot call a TypeScript helper. bookStats uses the backend CustomerSegments.counts; the frontend segmentCounts() in segments.ts:115-135 is not used anywhere on this page - the chips read the summary endpoint. (`backend/navix-loan/src/main/java/com/navix/loan/service/CustomerServic…`)
- **Refuted:** Friction: date-group collapse state is lost on pagination/filter change (useState resets on every page, 203-210) → collapsedDates is never reset while the page is mounted: a date collapsed on page 1 stays collapsed if it recurs on page 2 or under a new filter, and groups start EXPANDED (empty set) - the opposite of 'groups re-appear collapsed'… (`frontend/src/app/staff/customers/page.tsx:203 useState<Set<string>>(ne…`)
- **Refuted:** Perf: no virtual scroll - 1000+ customers on one date all render at once → A page can never exceed 100 rows, so the premise is impossible; the absence of virtualization is true but immaterial. (`frontend/src/components/staff/pipeline/pagination.tsx:68 (25|50|100);…`)

### 3.4 Customer 360 — 45 / 16 / 7

- **Refuted:** tables[0] StaffFieldTable (Personal/Employment/Bank/Credit): not sortable/filterable/paginated, stickyHeader TRUE, density py-1.5 text-[10.4px] → Header is NOT sticky: .staff-data-table thead th (globals.css:421) has no position:sticky; only the .staff-sticky-identity/.staff-sticky-actions column classes are sticky (:424-425) and StaffFieldTable uses neither. Cell padding i… (`frontend/src/components/staff/customer-tabs.tsx:649-666; frontend/src/…`)
- **Refuted:** tables[1] Disbursal txn refs (Bank tab): stickyHeader TRUE, standard .staff-data-table → Same as above — no sticky header in .staff-data-table; the 3-column table also inherits min-width:84rem. (`frontend/src/components/staff/customer-tabs.tsx:579-586; frontend/src/…`)
- **Refuted:** loading: Verifications tab shows a loading spinner (unverified) → VerificationChecksPanel renders plain 'Loading…' text, no spinner. (`frontend/src/components/staff/verification-checks.tsx:167-168`)
- **Refuted:** empty: Documents tab fallback 'No application to show documents for.' (unverified) → That string does not exist. On this route DocumentsTab runs in customer mode and shows 'No documents uploaded.' (detail-parts.tsx:129); the no-id fallback text is 'No application to attach documents to.' (:84). (`frontend/src/components/staff/detail-parts.tsx:84,129; grep -rn 'No ap…`)
- **Refuted:** transition: no CSS transitions/animations on mount other than Loader2 animate-spin and the page.tsx:69 animate-pulse → CreditScoreGauge (right panel on load, and the Credit tab) animates its needle (transform 1.25s cubic-bezier) and tick opacity via CSS transitions kicked off in a useEffect on mount (credit-score-gauge.tsx:130-145, 222-258; honour… (`frontend/src/app/staff/customers/[customerId]/page.tsx:90-97; frontend…`)
- **Refuted:** friction[12] tab switching resets the left pane's scroll position to top → No scroll reset exists. The overflow-y-auto container (page.tsx:77) is not keyed or remounted on tab change — only its child swaps (:78-84) — so the browser keeps scrollTop (clamped to the new content height). If anything the oppo… (`frontend/src/app/staff/customers/[customerId]/page.tsx:76-84`)
- **Refuted:** perf[1] clicking Verifications, Bank and Credit in quick succession could fire three identical GETs → Bank and Credit share one key and only one tab body is mounted at a time (customer-tabs.tsx:112-152), and React Query dedupes in-flight fetches per key — so no triple fetch. The real duplicate is structural: the Verifications tab… (`frontend/src/components/staff/customer-tabs.tsx:112-152,189,495,600; f…`)

### 3.5 Verification dashboard — 27 / 19 / 6

- **Refuted:** progress(appId) — 'No separate DB query — derived from the in-memory snapshot or a lightweight query per app' → progress() runs its own queries: applicationRepo.findById (3241) + statusesOf → findByApplicationIdOrderByIdAsc (3283-3286) = 2 queries minimum, plus 2 more per re-apply hop (statusesOf(source) + findById(source), 3274-3279) up to… (`backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVer…`)
- **Refuted:** manualDecision — 'upsert application_verification + append application_event (SoD audit trail); single write per override' → No application_event is appended anywhere in the service (no eventRepository reference). Writes: upsert (findByApplicationIdAndCheckType + save, 3559-3561/3595) plus, for PENNY_DROP/EMPLOYMENT/BUREAU, a derivedFor read (2708-2712)… (`backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVer…`)
- **Refuted:** Dialog open/close: no custom CSS transitions visible → The Dialog renders `.modal-overlay.show`, which has `animation: fadeUp .25s ease` (globals.css:586, keyframes at 662) — there is an open animation; there is no close animation. (`frontend/src/components/ui/dialog.tsx:49; frontend/src/app/globals.css…`)
- **Refuted:** Override/Retry buttons do NOT show pending state with spinner — only disabled opacity → Both submit buttons render `<Loader2 className="animate-spin"/>` while isPending (Retry at 342, Confirm at 492). The report's own loadingStates section says the same, contradicting this claim. (`frontend/src/components/staff/verification-checks.tsx:342,492`)
- **Refuted:** Search + pagination: 'pagination does not respect search across pages; a reviewer paging through gets stale results' → Search is applied server-side BEFORE paging (filter 3494-3510, then group+skip/limit 3514-3520, total = rowsByApp.size() 3525), the query key includes `debounced` (102) and page resets to 1 whenever the term changes (97-99). Every… (`frontend/src/app/staff/verifications/page.tsx:97-99,102; backend/navix…`)
- **Refuted:** Typing fast (e.g. '1234') fires 4 backend queries, one per debounce window → useDebouncedValue only settles after the value has been unchanged for 300ms (setTimeout cleared on every change, 18-21). Typing four characters quickly yields ONE settled value and one overview request. Only a pause >300ms between… (`frontend/src/hooks/use-debounced-value.ts:15-24; frontend/src/app/staf…`)

### 3.6 Loans register — 26 / 10 / 4

- **Refuted:** date-group header count feels off by one because the header row is itself rendered → group.rows contains only loan rows (178-186); the header is a separate <tr> and is not counted. The label 'N loans' is exact; the claim concedes this and the 'feels off' is speculation. (`frontend/src/app/staff/loans/page.tsx:176-187,311-312`)
- **Refuted:** react-query caches keyed by search+range but no deduplication across users; a second user searching 'John' hits the cache → React Query's cache is per browser tab/session (makeQueryClient), never shared across users; there is no server-side cache at all. Two users always issue two requests; the claim conflates client cache with a shared one. (`frontend/src/lib/query-client.ts:9-18; frontend/src/app/staff/loans/pa…`)
- **Refuted:** QueueDateFilter supports ALL/TODAY/7D/30D/90D/CUSTOM → QueuePeriod is 'ALL' | 'TODAY' | 'YESTERDAY' | 'CUSTOM' - there are no 7D/30D/90D presets. (`frontend/src/components/staff/pipeline/queue-date-filter.tsx:16,62-85`)
- **Refuted:** customersApi.list is also called from the page (question to confirm) → page.tsx never imports or calls customersApi; line 121 is a comment saying the search/range semantics 'mirror customersApi.list'. The only page-level data call is loansApi.list. The customer table is never loaded by this page; cus… (`frontend/src/app/staff/loans/page.tsx:15,121-126 (grep: only a comment…`)

### 3.7 Collections worklist — 15 / 16 / 10

- **Refuted:** GET /api/staff/me -> AuthController#staffMe (inferred); called by useStaffMe at page level (193) and inside collections-assign (89, 227, 353) → The hook fetches /api/auth/staff/me (hooks.ts:31), not /api/staff/me, and that route is a BFF-only handler that decodes the navix_staff cookie and returns {id,name,role} -- it never calls the Spring backend and no AuthController i… (`frontend/src/components/staff/pipeline/hooks.ts:30-40; frontend/src/ap…`)
- **Refuted:** stickyHeader: YES (thead position: sticky top-0 implied by staff-data-table class, globals.css) → There is no sticky header. `.staff-data-table thead th` (globals.css:421) sets only background/color/font-size/weight/letter-spacing/text-transform -- no `position: sticky` and no `top`. The only sticky rules are `.staff-sticky-id… (`frontend/src/app/globals.css:418-433; frontend/src/app/staff/collectio…`)
- **Refuted:** Whole-page loading state replaces the table with a blank shimmer on refetch, losing bucket/filter/sort context → The shimmer is gated on q.isLoading (initial load only); refetches (poll / Refresh) leave the table, bucket cards, filters and sort in place. Context is never lost on refetch. (`frontend/src/app/staff/collections/page.tsx:335-336`)
- **Refuted:** InlineOfficerSelect wrapper w-[11.5rem] vs Select w-40 mismatch may cause layout twitch → The 11.5rem wrapper is sized deliberately: 10rem select (w-40) + gap-1 (0.25rem) + a fixed w-3.5 spinner slot (313-315) = 10.9rem <= 11.5rem, documented at lines 297-298 and 313. The spinner slot is always rendered, so nothing ref… (`frontend/src/components/staff/collections-assign.tsx:297-316`)
- **Refuted:** Search is case-sensitive after debounce; no case-insensitivity guarantee → Both sides are lower-cased: the debounced query (line 110 `.trim().toLowerCase()`) and the haystack (line 175 `.toLowerCase()`), so matching is case-insensitive. The raw `query` state is used only to echo the user's text in the in… (`frontend/src/app/staff/collections/page.tsx:110,171-176`)
- **Refuted:** Export money columns use toFixed(2), which may clip trailing zeros or use locale decimal separators → Number.prototype.toFixed is locale-independent by spec (always '.' and always exactly 2 decimals), so neither clipping nor locale separators can occur in the produced string. (`frontend/src/app/staff/collections/page.tsx:236-238`)
- **Refuted:** Rapid bucket clicking triggers a new query on the full worklist per click → router.push only changes ?bucket=; the worklist queryKey is the constant ['collections-worklist'] (122) and does not include the bucket, so switching buckets issues no network request -- only the in-memory `filtered` useMemo (169)… (`frontend/src/app/staff/collections/page.tsx:121-125,169,303`)
- **Refuted:** Officer names fetched on every rows-per-page change or filter because InlineOfficerSelect mounts per row → All rows observe one cache entry keyed ['collection-officers'] with staleTime 60_000; a newly mounted row only triggers a refetch if the entry is older than 60s, and then exactly one request is issued for all rows. Mounting alone… (`frontend/src/components/staff/collections-assign.tsx:69-77,230`)
- **Refuted:** Officer names fetched per InlineOfficerSelect mount; each row is a consumer of the query → Observers are free; one shared cache entry, one request per staleness window. Not a performance cost. (`frontend/src/components/staff/collections-assign.tsx:69-77`)
- **Refuted:** No request dedup for concurrent officer fetches when many rows mount in the same tick → React Query dedupes in-flight fetches on the same queryKey; N rows mounting together produce exactly one request. (`frontend/src/components/staff/collections-assign.tsx:71-77`)

### 3.8 Collection case — 21 / 20 / 10

- **Refuted:** load (ADMIN-only): staffApi.loan(loanId) + staffApi.outstanding(loanId, paidOn) fire for the AdminLogPaymentDialog; adminRecordRepayment invalidates 1… → Not a load-time call. AdminLogPaymentDialog is mounted only after the button is clicked ({open && <AdminLogPaymentDialog/>} at :68), so staff-loan and staff-outstanding-asof fire on click, not on page load. The invalidation list h… (`frontend/src/components/staff/admin-log-payment.tsx:56-57,68,82-91,125…`)
- **Refuted:** assignableOfficers -> staff_user WHERE role IN (...) AND active = true; full table scan; no pagination; cached → Query is findByRoleAndStatusOrderByIdAsc(COLLECTION_EXECUTIVE, ACTIVE): a single equality on role (not IN) and status = ACTIVE (there is no 'active' boolean). idx_staff_user_role covers role, so it is an index lookup, not a full-t… (`backend/navix-collections/src/main/java/com/navix/collections/service/…`)
- **Refuted:** CustomerService.get(customerId) -> SELECT * FROM customer_profile WHERE customer_id = ?; single row by PK → detail() is ~12-20 queries, not one: scope() visibility (0 for Heads/ADMIN, ~5 for a COLLECTION_EXECUTIVE), applications findByCustomerId, loans findByCustomerId, profiles findByApplicationIdIn, latestProfile -> profile findByAppl… (`backend/navix-loan/src/main/java/com/navix/loan/service/CustomerServic…`)
- **Refuted:** CollectionPaymentService.listForCase -> findByCaseId + join staff_user for raisedByName; 'potential N+1 per payment row' → No N+1: toViews() batches ONE staffDirectory.namesFor (findAllById) and ONE loanDirectory.findLoans for the whole list; the per-row toView(p) with findStaff/findLoan is only used by the single-row mutation returns. The real cost i… (`backend/navix-collections/src/main/java/com/navix/collections/service/…`)
- **Refuted:** Long waterfall on page load: caseQ -> caseId -> interQ / creditQ / callRemarksQ all sequential; header skeleton until all 4 finish → It is a two-tier fan-out, not a 4-deep chain. Tier 1: caseQ. Tier 2 (all mounted together inside the else-branch at :77 and started in parallel): interQ (caseId), CasePaymentsCard (caseId), creditQ (loan.customerId), callLogs (cus… (`frontend/src/app/staff/collections/[loanId]/page.tsx:36-51,73-77,97,10…`)
- **Refuted:** Officer list loads even when PermissionGate hides AssignCard; query fires regardless → PermissionGate returns fallback (or null while the role is unknown) and never renders children when the permission is missing. AssignCard - and its useQuery at :321 - is a child ELEMENT, so the component never mounts and the hook… (`frontend/src/app/staff/collections/[loanId]/page.tsx:112-117,320-321;…`)
- **Refuted:** PermissionGate on employer/salary/bank returns null leaving an odd number of dt/dd pairs → The gate wraps three complete <Row> elements, each rendering its own dt+dd pair; nothing for those rows is rendered outside the gate. When it returns null the dl loses whole pairs and stays balanced. (`frontend/src/app/staff/collections/[loanId]/page.tsx:132-139,188-199`)
- **Refuted:** AdminLogPaymentDialog's LoanBreakdown renders a static breakdown that may drift from backend penalty/grace rules → LoanBreakdown renders the backend OutstandingView fields (out.interestPaise, penaltyPaise, interestDays, penaltyDays, outstandingPaise) fetched from GET /outstanding?asOf=paidOn; it does not compute interest/penalty locally, so it… (`frontend/src/components/staff/admin-log-payment.tsx:87-92,210-212; fro…`)
- **Refuted:** Waterfall load: caseQ -> interQ -> creditQ -> callRemarksQ (4 sequential fetches); no Promise.all → Two tiers, not four: everything after caseQ starts in parallel (see the friction verdict). caseId/customerId are only known from the case response, so tier 2 cannot start earlier without changing the URL contract. (`frontend/src/app/staff/collections/[loanId]/page.tsx:36-51,77,97,106,1…`)
- **Refuted:** listOfficersQ fires even when the role lacks collections:manage → AssignCard is never mounted for such roles, so its useQuery never runs. (`frontend/src/app/staff/collections/[loanId]/page.tsx:112-117,321; fron…`)

### 3.9 Settlements — 28 / 4 / 3

- **Refuted:** componentsUsed: RefreshButton from staff-ui.tsx:38 is used on this page → The page does NOT use the shared RefreshButton component (page.tsx:6 imports only PageHeader from staff-ui). It renders its own inline <button onClick={() => q.refetch()}> at page.tsx:65-70 with RefreshCw/Loader2 imported directly… (`frontend/src/app/staff/collections/settlements/page.tsx:5-6, 65-70; fr…`)
- **Refuted:** stickyHeader: 'thead not explicitly sticky but .staff-data-table may apply via globals.css' → globals.css:418-433 puts NO position:sticky on thead. The only sticky rules are the column classes .staff-sticky-identity / .staff-sticky-actions (424-431), and this page uses neither (page.tsx:84-88, 95-112). There is no sticky h… (`frontend/src/app/globals.css:418-433; frontend/src/app/staff/collectio…`)
- **Refuted:** Amount is displayed twice (table row line 106 and export column line 55) with no dedup → Nothing renders twice. The export `columns` are lazily-evaluated value() functions invoked only when an ADMIN clicks CSV/PDF (export-menu.tsx:62-77); they are not on-screen output. The only real discrepancy is formatting: on-scree… (`frontend/src/app/staff/collections/settlements/page.tsx:53-63, 106; fr…`)

### 3.10 Transactions ledger — 27 / 11 / 5

- **Refuted:** tables[Transactions Ledger].stickyHeader: true → The header is not sticky. The only sticky behaviour in the staff table system is horizontal pinning of identity/action COLUMNS via .staff-sticky-identity/.staff-sticky-actions, and this table uses neither. There is no vertical sti… (`frontend/src/app/globals.css:409 (.staff-table-scroll is overflow-x:au…`)
- **Refuted:** frictionPoints[1]: two repository queries mean both disbursals AND repayments are loaded for the window and filtered in memory; 'no query optimization… → The direction filter already skips the unneeded half: line 80 is `"INCOMING".equals(dir) ? List.of() : loanRepository.findAllForRegister(from, to)` and line 81 is the mirror for OUTGOING. No repository call is made for the exclude… (`backend/navix-loan/src/main/java/com/navix/loan/service/TransactionSer…`)
- **Refuted:** frictionPoints[9]: Export is ADMIN-only (export-menu.tsx 51) 'but the button is still mounted and visible in the PageHeader for all roles'; non-admins… → ADMIN-only is right, but the component returns null for every other role -- nothing is mounted or visible in the PageHeader, so there is no button for a non-admin to be confused by. The report contradicts its own evidence ('return… (`frontend/src/components/staff/export-menu.tsx:53-55 (`if (me?.role !==…`)
- **Refuted:** performanceIssues[1]: presigning happens per page (good) but 100 repayment rows with proofs = '100 S3 DescribeObject API calls'; no batching/caching → S3Presigner.presignGetObject is a local SigV4 computation -- it issues no S3 request at all (no HeadObject/DescribeObject). Cost is microseconds of CPU per URL, and only REPAYMENT rows carry a key (disbursal rows pass null at line… (`backend/navix-storage/src/main/java/com/navix/storage/service/Document…`)
- **Refuted:** performanceIssues[2]: the 60-second poll is unconditional -- 'a background tab still polls, wasting bandwidth' (no visibility API) → TanStack Query's interval refetch is gated on focusManager.isFocused() unless refetchIntervalInBackground: true is passed (default false). The page does not set it, so the 60s poll pauses while the tab is hidden and resumes on foc… (`frontend/src/app/staff/accounting/transactions/page.tsx:97-114 (refetc…`)

### 3.11 Leads — 18 / 12 / 4

- **Refuted:** Whole-list invalidation ['leads'] after any mutation refetches every page/pageSize variant (overly broad) → invalidateQueries({queryKey:['leads']}) marks every cached variant stale but refetches only ACTIVE queries (the one key currently observed: [debouncedQ, callStatus, page, pageSize]). Inactive page variants are merely stale and ref… (`frontend/src/app/staff/leads/page.tsx:84; TanStack invalidateQueries d…`)
- **Refuted:** Money fields accept any string; 'abc', '-500' can be submitted to the backend → They cannot be submitted: mutationFn attaches monthlySalaryPaise/loanAmountInterestedPaise only when Number(value) > 0 (page.tsx:232-235), so 'abc' (NaN) and '-500' are silently DROPPED. The actual defect is silent data loss with… (`frontend/src/app/staff/leads/page.tsx:232-235,282-295; backend/navix-l…`)
- **Refuted:** leadsApi.stats() defined but never called — missing/incomplete feature → leadsApi.stats IS consumed by the ADMIN lead register (/staff/admin/leads, queryKey ['lead-stats', ...]). The backend gates /api/leads/stats to ADMIN (LeadService:222 requireAdmin), so the telecaller page could not call it anyway.… (`frontend/src/app/staff/admin/leads/page.tsx:84-90; backend/navix-loan/…`)
- **Refuted:** Invalidation cascades: if user is on page 3, all pages refetch simultaneously → Only the active query refetches (invalidateQueries default refetchType 'active'); other page keys are marked stale, not fetched. One list request per mutation. (`frontend/src/app/staff/leads/page.tsx:84`)

### 3.12 Telecalling — 14 / 13 / 3

- **Refuted:** apiCalls[owner-picker-open]: GET /api/staff/creditExecutives/{role} one per role; controller 'unverified'; fires each time picker is opened, re-mounts… → Wrong path and wrong trigger. Actual call: GET /api/staff/applications/credit-executives?role=TELECALLER -> backend GET /api/applications/credit-executives (ApplicationController:144) -> StaffDirectoryAdapter.listActive -> staffUs… (`frontend/src/lib/api/applications.ts:1649-1653; backend/navix-loan/src…`)
- **Refuted:** tables.stickyHeader: 'Yes, <thead> is part of staff-data-table' → The <thead> is NOT sticky: .staff-data-table thead th (globals.css:421) sets background/colour/font only - no position:sticky/top. Only the identity (checkbox) and Actions COLUMNS are sticky horizontally (:424-425, left:0 / right:… (`frontend/src/app/globals.css:418-433`)
- **Refuted:** friction: CustomerOwnerPicker loads staff from the API every time it opens; 3 pickers could fire 3 redundant queries; re-mounts on every table re-rend… → The picker has no open state - it is rendered inline in every row - and its staff list is a shared React Query key ['staff-picker','TELECALLER'] with staleTime 60s, so N pickers = 1 request and re-renders do not remount (rows keye… (`frontend/src/components/staff/customer-owner-picker.tsx:30,41-47; fron…`)

### 3.13 Staff performance — 18 / 14 / 3

- **Refuted:** loadingStates: whole-page replace on q.isLoading; chart and stat cards render underneath but are invisible behind the pulse overlay; table behind the… → There is no overlay and no whole-page replace. The isLoading ternary at 215-217 swaps only the table block for an h-48 animate-pulse div. PageHeader (127-158), PeriodPicker (160-173) and all five StatCards (175-190) render normall… (`frontend/src/app/staff/performance/page.tsx:73-75,97-106,127-190,192,2…`)
- **Refuted:** table: Value moved is font-mono, right-aligned by CSS class → The cell has only className='font-mono' (284) and .staff-data-table td sets text-align: left (globals.css:420). Numeric columns on this table are all left-aligned. (`frontend/src/app/staff/performance/page.tsx:284; frontend/src/app/glob…`)
- **Refuted:** performance: useColumnFilters re-filters the row array on every keystroke in the filter popover (column-filter.tsx:62-99) → Keystrokes only touch FilterPanel's local `search` state (136) and the `visible` memo, which filters the already-computed options string list (143-147). applyExcept/filtered/optionsFor are memoised on [rows, selections, byKey] (75… (`frontend/src/components/staff/column-filter.tsx:75-99,136,143-147; fro…`)

### 3.14 My decisions — 18 / 13 / 4

- **Refuted:** table: stickyHeader true → No sticky header. `.staff-data-table thead th` (globals.css:421) sets colours only; the only `position: sticky` rules are the per-cell `.staff-sticky-identity` / `.staff-sticky-actions` classes (424-425), which this page never app… (`frontend/src/app/globals.css:418-433; frontend/src/app/staff/my-decisi…`)
- **Refuted:** friction: Amount column has no ₹ symbol; paiseToINR renders '500.00' → paiseToINR uses Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:0}) — 500000 paise renders as '₹5,000' (rupee sign, Indian grouping, no decimals). The column is not ambiguous between paise and r… (`frontend/src/lib/api/applications.ts:3431-3438; frontend/src/app/staff…`)
- **Refuted:** friction: Outcome column is a colour-only Badge (SANCTIONED green / REJECTED red), unreadable for red-green colour-blind staff → statusLabel() returns a plain string ('Sanctioned', 'Rejected', …) — it title-cases the enum; the page renders it as text in a bare <td>. No Badge, no colour is involved. (`frontend/src/lib/api/applications.ts:3441-3447; frontend/src/app/staff…`)
- **Refuted:** performance: Loader2 re-renders on every isFetching change causing flicker when toggling pages via PaginationBar → Paging is purely in-memory (rows.slice) and never changes q.isFetching, so the spinner does not toggle and there is no flicker — the observer's own evidence concedes this. The remaining complaint ('no visual cue when paging') is n… (`frontend/src/app/staff/my-decisions/page.tsx:139; frontend/src/compone…`)

### 3.15 All applications — 28 / 13 / 4

- **Refuted:** Detail dialog fires 4+ queries sequentially without batching; each waits for the previous (appQ -> eventsQ -> briefQ -> profileQ -> customersApi.get) → appQ (:195 enabled: open), eventsQ (:201 enabled: open) and profileQ (:213 enabled: open && canReview) all start on the same render when the dialog opens - three parallel requests. Only briefQ (:207) waits for appQ.data (to skip D… (`frontend/src/components/staff/application-detail-dialog.tsx:192-227`)
- **Refuted:** Info button duplicates the detail dialog's queries; no query deduplication if the user opens both on the same row → Three of the four keys are identical across both dialogs - ['staff-application', id], ['staff-profile', id], ['credit-brief', id] - so React Query serves the second dialog from cache within the 60s staleTime (the info dialog's hea… (`frontend/src/components/staff/application-info-dialog.tsx:10-12,63,68,…`)
- **Refuted:** Entire table replaced with skeleton during refetch; rows disappear when Refresh is clicked → With React Query 5.62 `isLoading` is only true while there is no data; q.refetch() keeps the rows on screen and toggles only the header spinner (`q.isFetching`, :101). The skeleton shows on first load only. (`frontend/src/app/staff/admin/all-applications/page.tsx:101,129; fronte…`)
- **Refuted:** uiStructure: main table 'with sticky thead' → No sticky positioning on thead/th anywhere in .staff-data-table; the report's tables[0].stickyHeader=false is the correct statement. (`frontend/src/app/globals.css:421; frontend/src/app/staff/admin/all-app…`)

**Totals:** 356 confirmed · 241 partially correct · 89 refuted.
