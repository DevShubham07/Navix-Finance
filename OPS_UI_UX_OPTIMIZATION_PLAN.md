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
