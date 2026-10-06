# Ops console — UI/UX optimisation plan for the 15 most-used pages

> **Status:** proposal, **reviewed 2026-10-06, awaiting approval** / not yet implemented. Branch
> `claude/ops-ui-ux-optimization-dokoco`. The review's corrections are folded in below and summarised in the
> "Review pass" note after the reconciliation block; read §4's two new subsections ("Conflicts with the 2026-09-16
> measured investigation", "What this plan did not measure") before sequencing any backend work, and the Phase 1.0
> prerequisite before touching shared CSS or `ui/` primitives.
> Companion docs: [`CLAUDE.md`](CLAUDE.md) (§7 roles, §8 frontend architecture), [`ADMIN_CONSOLE_REVAMP_PLAN.md`](ADMIN_CONSOLE_REVAMP_PLAN.md)
> (the earlier "name on every row + unified application dialog" plan, whose Part A/B shipped and whose patterns this plan reuses),
> and **[`docs/perf/UI_PERFORMANCE_INVESTIGATION_2026-09-16.md`](docs/perf/UI_PERFORMANCE_INVESTIGATION_2026-09-16.md)**
> — the *measured* pass over the same 15 pages (live timings, payload sizes, statement counts, the real `pg_indexes`
> catalog), whose recommendations shipped in `dc25b93` / `fa7473b` / `V71` / `vercel.json`. Where this plan and that
> document disagree, that document has evidence and this one has code reading; §4 lists the three conflicts.
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

> **Reconciled against `main` on 2026-10-06 (`0f14dab`).** The analysis below was written against `953f546`.
> `main` has since moved 15 commits and touched 22 staff-console files, so **line numbers in §3 drift for 10 of the
> 15 pages** — treat them as pointers to the right function, not exact offsets. Two substantive reconciliations:
>
> - **Superseded:** a shared `SearchBar` (`frontend/src/components/staff/search-bar.tsx`) now serves 12 staff pages.
>   It searches on Enter rather than per keystroke and carries its own clear button, so every "300 ms debounce"
>   observation and the "add a ✕ to the search field" proposal below are **already solved**. §2.1's `TableToolbar`
>   narrows accordingly to the period / refresh / export cluster, and should compose `SearchBar` rather than replace it.
>   Whether a *non-search* filter change (period, direction) still fires a duplicate request needs re-checking at
>   current `main`.
> - **Still open, re-verified at `0f14dab`:** all **ten** Phase-0 items (§4 — an earlier draft of this note said
>   "five"; the table has always had ten rows) and every shared-foundation gap in §2 —
>   no toast or `Skeleton` primitive exists, no sticky `thead` rule exists, `ui/Table` still has zero consumers,
>   `customer-edit-dialog.tsx` still invalidates `["customers"]` while the register reads `["customers-page"]`, and
>   `manualDecision` still writes no `application_event` (the two `ApplicationEvent` hits in that service are Spring's
>   `ApplicationEventPublisher`, not the audit repository).

> **Review pass 2026-10-06 — corrections folded in below.** A second reviewer re-read the cited code rather than the
> prose and found the diagnosis sound but four load-bearing details wrong. Each is corrected in place and listed here
> so the corrections are not lost in a 1,500-line document:
>
> 1. **`V74` is taken.** The index migration in §4 Phase 3 is now **`V76`** — `V74` is
>    `application_verification_reopened` and `V75` is `customer_profile_aadhaar_card_intake`. §3.2 §8's claim that
>    `loan_application(assigned_executive_id, status)` "ships in the V74 batch" was false; it does not exist yet.
> 2. **The sticky-header rule cannot be written against `.staff-table-scroll`.** That class has **31 consumers**,
>    several of them small tables nested inside scrollers that already bound their height, and it is a documented
>    popover-clipping context. §2.2 now scopes the rule to a new `.staff-register-scroll` modifier.
> 3. **The console body is 11 px, not 10.4 px** (`.navix-crm { font-size: 0.688rem }` against a 16 px root — the
>    `12.8px` in `globals.css:100` is on `body`, so it never changes what `rem` resolves to). The 9.2 px header
>    figure is correct. §3.2 now states the consequence the plan had missed: an 11 px floor makes headers the same
>    size as body text and pushes more registers past the `84rem` floor.
> 4. **`ui/Dialog` has 41 consumers, 5 of them borrower-facing** (not "three borrower dialogs"), one of which is the
>    regulated consent screen `components/borrower/terms-modal.tsx`. Phase 1 item 3 is re-sized accordingly.
>
> 5. **Four §2.2 proposals do not work as written** — the pagination bar is *inside* the scroller on 4 of the 6
>    paginated registers (so "the pagination bar stays in view" is false), `border-collapse: collapse` eats a sticky
>    `th`'s bottom border, a `z-index: 3` header paints over the in-place Journey `Drawer` that renders inside the
>    `z-index: 2` sticky actions cell, and `.modal-overlay.closing` is inert because `Dialog` unmounts instantly.
>    Two of the four need **component** changes that §2.1 does not list. Details and fixes in §2.2.
> 6. **§2.3's three headline mechanisms are each argued wrongly** (right conclusions, wrong reasons): the poll gate is
>    tab *visibility*, not window focus — so a visible-but-unfocused console still polls at full rate; the session
>    double-fetch has five call sites and the telecalling worst case is 27 round trips against a cookie-only route, not
>    "up to 50" backend calls; and "fabricated zeros" is real on `/staff/performance` but **not** on
>    `/staff/my-decisions`, while an instance on `/staff/loans` was missed. Details in §2.3.
> 7. **§2.1 budgets writing the primitives (~335 lines) and not adopting them** (51 + 46 + 29 + 170 call sites), and
>    the `ui/` barrel already exports a `Table` with zero consumers. Several tallies were under-counted. See §2.1.
> 8. **One new Phase 0 item (0.11):** leads row *selection* — not just hover — is invisible on every even row, on the
>    page a Telecaller works all day. Phase 0 is now 11 items.
>
> 9. **Phase 0.5 was already shipped** eight days before this plan claims it re-verified every Phase-0 item
>    (`696f37f`, 2026-09-28), and §3.5's matching "High"-severity finding describes a state the code is not in. Struck
>    in §4. Treat the header's "re-verified at `0f14dab`" line as unproven for the other items too.
> 10. **The headline estimate is off by ~2–2.5×** (12–16 weeks, not 6) and **the phase order is backwards on risk** —
>    the unguarded frontend refactor is scheduled first, the well-covered backend last. Both corrected in §4.
> 11. **Several proposals cannot work as specified:** `rowFlash`'s colour is **1.000:1** against the zebra (invisible),
>    its keyframes would never be emitted from `tailwind.config.ts`, the group-height animation is not applicable to
>    table display types, `loading.tsx` helps nothing when 33 of 34 staff pages are `"use client"`, and the proposed
>    batch endpoints contradict an explicit directive in `bulk-actions.tsx:5-12`. All corrected in place.
>
> Three methodological gaps are recorded separately, because they change how the plan should be *read*: §4 "Conflicts
> with the 2026-09-16 measured investigation", §4 "What this plan did not measure", and
> **[Appendix C](#appendix-c--second-review-findings-2026-10-06)** — the full ledger (259 claims: 212 confirmed, 34
> partial, 12 refuted) plus the scope the plan never covers: front-end delivery performance, rendering/paint cost,
> contrast and type scale, error boundaries, measurement, and operator evidence.
>
> **Net assessment of the review:** the diagnosis in §2 and §3 headings 1–5 is sound, well-evidenced and worth acting
> on now — the console really has no loading/empty/error/toast vocabulary, no sticky header, left-aligned money, a
> broken row hover, a focus-trap-less Dialog, 9.2 px headers and several genuine correctness bugs. What needs work is
> the sequencing, the risk accounting, the evidence base under the backend half, and the absence of any front-end
> delivery-performance or accessibility dimension in a document titled "UI/UX optimisation".

---

## 0. Executive summary

The console is structurally sound: one design language, one table treatment (`.staff-data-table`), one query client,
role-aware queues, and — after the earlier revamp — identity on every application row and a unified application
dialog. What makes it feel slow, noisy and inconsistent is not the information architecture but a layer of small,
repeated gaps:

| Gap (verified) | Where it shows | Evidence |
|---|---|---|
| **No skeleton / empty / error / toast primitives** — 31 files inline `animate-pulse` blocks with four different fills (51 occurrences), 21 files render a bare `Loading…` string (46 occurrences), 35 files hand-roll an empty state with 11 wrapper variants, **60 files render `<p class="text-error-700">` with no retry (170 occurrences)** (re-counted 2026-10-06 over `frontend/src/components/staff/**` and `frontend/src/app/staff/**`; the first draft said 13 / 50 files, which understated the error and loading cases — the exact tallies move with the glob, the pattern does not), and there is **no toast and no live region for success feedback** (the whole app contains **4** `role="alert"`/`aria-live` instances, all form errors) | every page | shared-layer scan; `frontend/src/components/staff/**`, `frontend/src/app/staff/**` |
| **Status colour is hand-rolled** — 23 files keep a private status→class map and 16 files inline `bg-*-100` pills; `ui/Badge` is used by 5 files; `StageBadge`/`KycStatusBadge` have 0 consumers | queues, ledger, telecalling, settlements, verifications | `frontend/src/components/ui/badge.tsx:4-28`, `frontend/src/components/staff/staff-ui.tsx:90-117` |
| **Table header never sticks; numbers are left-aligned** — `.staff-data-table` pins identity/action *columns* but has no `thead` sticky rule; money cells use `font-mono` (tabular via `tnum`) but `text-align:left` | every register | `frontend/src/app/globals.css:418-433` |
| **Row hover cannot work on even rows** — 8 files add `hover:bg-grey-50` to `<tr>` but the zebra colour is painted on `<td>` | customers, loans, all-applications… | `frontend/src/app/globals.css:422` |
| **Dialogs enter but never exit; Dialog has no focus trap** — `.modal-overlay.show` animates in (`fadeUp .25s`), unmount is instant; Drawer traps focus, Dialog does not | every modal | `frontend/src/components/ui/dialog.tsx:47`, `globals.css:586` |
| **Two competing period controls** — `PeriodPicker` (rounded-full, gold) vs `QueueDateFilter` (square, navy) | dashboard/performance/decisions vs queues/customers/loans/collections | `period-picker.tsx:21-24`, `queue-date-filter.tsx:106-108` |
| **Refresh is hand-rolled 26 times**; `RefreshButton` used by 2 pages | most pages | `staff-ui.tsx:38-57` |
| **Session is fetched twice per page load** — the shell's `useStaffSession` is a raw `fetch`, every `PermissionGate`/`ExportMenu` uses the React Query `['staff-me']` key | every page | `frontend/src/lib/auth/staff-session.ts:25-34,90-109`, `pipeline/hooks.ts:30-40` |
| **Polling is per-page and uncoordinated** — 8 s queue polls, 10 s dashboard, 20 s bell, 30 s sidebar worklist badge, 45 s verifications, 60 s ledger, 120 s telecalling, all fixed | every page | see §2.3 |
| **Seven registers load the whole table and paginate in the browser** — applications queues, loans, collections worklist, telecalling, all-applications, my-decisions, settlements | the heaviest pages | §3 per page |

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
| `<Skeleton variant="line\|stat\|row\|table" rows=n />` | one `animate-pulse` implementation with the console's `bg-grey-100`/`border-line` fill; `table` renders n zebra rows with the same 8px 14px cell rhythm so the skeleton has the shape of the table it replaces | 51 ad-hoc `animate-pulse` blocks (31 files), 46 bare `Loading…` strings (21 files) | ~40 lines |
| `<EmptyState icon title hint action? />` | the `px-5 py-8 text-center text-sm text-muted` line every page already writes, with an optional CTA | 35 files × 11 wrapper variants | ~30 lines |
| `<ErrorState error onRetry />` | `errMessage(error)` + a `Try again` button that calls `refetch()` | 170 occurrences across 60 files of `<p class="text-error-700">` with no retry — the largest single adoption cost in Phase 1, and the reason the migration (not the primitive) is what needs estimating | ~30 lines |
| `<Toaster/>` + `toast.success/error(msg)` | a tiny queue rendered in the shell with `role="status" aria-live="polite"`, auto-dismiss 4 s, `prefers-reduced-motion` respected; **no new dependency** | inline success strings that never dismiss (customer detail cards, telecalling `results[id]`, settlements) | ~80 lines |
| `<StatusBadge kind="application\|loan\|payment\|lead\|settlement\|verification" value/>` | **one** map from each backend enum to `ui/Badge` variants (the vocabulary the sidebar already uses: `success`/`warning`/`error`/`info`/`neutral`) | 23 private status→class maps; revive or delete the dead `StageBadge`/`KycStatusBadge` | ~60 lines |
| `<Money paise align="right"/>` | `paiseToINR` + `font-mono text-right whitespace-nowrap` (tabular digits already come from `globals.css:113`) | every money `<td>` written by hand | ~15 lines |
| `<TableToolbar>` | one flex row for period / segment chips / refresh / export with the existing class strings, **composing the shared `SearchBar` rather than replacing it**, so pages stop composing the cluster ad hoc | 15+ ad-hoc toolbars | ~40 lines |
| `<ConfirmDialog title body confirmLabel tone/>` | a `ui/Dialog` preset for the one-click destructive actions (settlement approve/reject, bulk reminders) | none today | ~40 lines |

> **Two things §2.1 does not account for (2026-10-06 review).**
>
> - **The sizes quoted are for *writing* the primitives, not for adopting them.** ~40 + ~30 + ~30 + ~80 + ~60 + ~15 +
>   ~40 + ~40 ≈ 335 lines of new code, against **51** `animate-pulse` sites, **46** `Loading…` sites, ~29 empty states
>   and **170** error paragraphs to convert — plus every status pill and money cell. The primitives are a day; the
>   migration is the project. Phase 1's "≈ 1 week" covers the former. Budget the latter explicitly, or scope Phase 1 to
>   *introducing* the primitives plus converting the 15 pages in §1 only, and let the rest convert opportunistically.
> - **The barrel already ships dead code.** `components/ui/table.tsx` has **zero** consumers — nothing imports it and
>   no `<Table`/`<TableRow`/`<TableHead` appears anywhere — yet it is exported from `ui/index.ts`. §2.1 flags the dead
>   `StageBadge`/`KycStatusBadge` but not this. Adding eight more primitives to a barrel with an unused table component
>   invites the same outcome, so make adoption part of each primitive's definition of done, and delete `ui/table.tsx`
>   (or make `Skeleton variant="table"` and the register markup use it) rather than leaving a third dead export.
>
> Corrected tallies from the same review, for anyone sizing the migration: **9** files import `ui/Badge` (6 in staff
> scope), not 5; **29** files hand-roll an empty state across **13** className variants, not 35 across 11; the
> "23 private status→class maps" is **11 hand-verified maps inside a population of 31 files** carrying ad-hoc colour
> logic — the pattern is real, the integer is an estimate.

### 2.2 CSS rules to add (all in `frontend/src/app/globals.css`, scoped to the console — never global, per CLAUDE.md §8)

> **The heading is not true of this block as written (2026-10-06).** `globals.css` is imported by the **root** layout
> (`src/app/layout.tsx:3`), so it loads on every marketing and borrower page. Two rules below are unscoped and would
> leave the console: the new `:root` variables, and
> `@media (prefers-reduced-motion: reduce) { .btn:hover { transform: none } }`, which changes `.btn` everywhere. The
> reduced-motion rule is arguably a global improvement, but it is a global change and this section promises otherwise —
> decide it deliberately. Scope the rest under `.navix-crm`. Separately, `--grey-50: #FBF7F0` and `--gold-50: #E7F6EF`
> are not new values: both already exist as Tailwind tokens (`tailwind.config.ts:59` `grey.50`, `:50` `gold.50`), so
> define the CSS variables **from** those tokens rather than hardcoding a second copy that can drift.

> **Corrected 2026-10-06.** The first draft bounded `.staff-table-scroll` itself. That is wrong and must not be
> implemented as written: **the class has 31 consumers**, and only some of them are full-page registers.
> `customer-tabs.tsx:599,671` puts field tables inside it *while the tab panel above it is already a
> `max-h-[68vh]` scroller* (a `calc(100vh - 14rem)` child yields two nested vertical scrollbars on the Customer 360
> page); `salary-days-panel.tsx:57`, the four `admin/dsa` tables, `admin/rejections`, `admin/expenses` and the two
> `dsa/*` pages are short panel tables that would gain a scrollport they do not want. The class is also a **known
> popover-clipping context** — `overflow-x: auto` computes `overflow-y` to `auto`, which is precisely why
> `components/ui/tooltip.tsx:29`, `components/staff/column-filter.tsx:350`,
> `components/staff/collections-assign.tsx:288` and `components/staff/case-failure-dialog.tsx:25` each carry a comment
> explaining that their popover/live-region had to be portalled out of it. Turning it into a real vertical scroller
> makes that clip taller as well as wider, so any *new* in-cell overlay must be portalled too.
>
> The rule therefore goes on a **new opt-in modifier**, `.staff-register-scroll`, added alongside
> `.staff-table-scroll` on the ~15 register wrappers in §1 and nowhere else. Same visual outcome, no blast radius.

```css
/* Sticky header, opt-in per register. `.staff-table-scroll`'s overflow-x:auto already makes IT (not the
   page) the sticky scrollport, but with no height bound it never scrolls vertically, so `top: 0` would
   pin the header to a box as tall as its own content and nothing would appear to stick. The modifier
   adds the missing vertical bound. Deliberate behaviour change: a long register scrolls inside its panel
   and the pagination bar stays in view. The navy background is already on thead th.
   Applied ONLY to the 15 register wrappers — never to `.staff-table-scroll` itself (see the note above).
   `dvh` over `vh` so mobile browser chrome does not clip the last row. */
.staff-register-scroll { max-height: calc(100dvh - 14rem); overflow: auto; }
.staff-data-table thead th { position: sticky; top: 0; z-index: 3; }
.staff-data-table thead .staff-sticky-identity,
.staff-data-table thead .staff-sticky-actions { z-index: 4; }

/* Money and counts: right-aligned tabular figures. */
.staff-data-table td.num, .staff-data-table th.num { text-align: right; font-feature-settings: "tnum" 1; }

/* Row hover that works on even rows too (the zebra is painted on <td>). Only --grey-100 exists as a
   CSS variable today (globals.css:49); add --grey-50: #FBF7F0 and --gold-50: #E7F6EF to :root. */
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
(`background: var(--gold-50)` → transparent, with the new `--gold-50` variable above). Nothing else in the motion
vocabulary changes.

**Six defects in the §2.2 block itself, found by the 2026-10-06 review. Four of them mean the rule does not do what
the section claims until other work is done — read these before estimating Phase 1:**

1. **The pagination bar is *inside* the scroller on six registers**, so capping the wrapper scrolls the pagination bar
   away with the rows — the exact opposite of the stated benefit ("the pagination bar stays in view"). Verified by JSX
   nesting: `customers/page.tsx` (scroller `:388` → `:652`, `PaginationBar` `:644`), `loans/page.tsx` (`:254` →
   `:361`, PB `:360`), `collections/page.tsx` (`:342` → `:509`, PB `:500`), `my-decisions/page.tsx` (`:171` → `:251`,
   PB `:241`), plus **`leads` and `collections/settlements`**. It is already outside only on
   `accounting/transactions/page.tsx` (`:254-275`, PB `:278`).  *(A first pass at this said "four of six"; it is six.)*
   **So the sticky header requires a markup change on four pages — lift `PaginationBar` out of the scroller and make it
   a sibling footer inside the bordered panel.** That is unscoped work the plan does not list; it is small but it is
   not zero, and the benefit is false without it.
2. **`border-collapse: collapse` eats the sticky header's bottom border.** `globals.css:406` sets
   `border-collapse: collapse` on `.navix-crm table`, and `:420` puts `border-bottom` on `th`. Under collapsed borders
   the border is painted by the *table*, not the cell, so it does not travel with a sticky `th` — the header will
   detach from the rows with no rule under it as soon as it sticks. Standard fix: keep `border-collapse: collapse` and
   give the sticky header its separation with a shadow instead of a border —
   `.staff-data-table thead th { box-shadow: inset 0 -1px 0 var(--line); }`.
3. **A `z-index: 3` sticky header will paint over the Journey drawer.** `ui/Drawer` has **no portal** — it renders in
   place as `fixed inset-0 z-[200]` (`drawer.tsx:71`) — and `ApplicationJourney` (which *is* a `Drawer`,
   `application-journey.tsx:94`) is rendered at `app-row.tsx:159`, **inside the `<td className="staff-sticky-actions">`
   that opens at `:125`**. That cell is `position: sticky; z-index: 2` (`globals.css:425`), and a positioned element
   with a non-`auto` z-index **establishes a stacking context**, so the drawer's `z-[200]` is resolved *inside* it and
   cannot out-paint a sibling at `z-index: 3`. **This bug is already live — the sticky header would not introduce it,
   it would widen it.** `globals.css:430-431` already puts the `thead` sticky identity/actions cells at `z-index: 3`,
   so those two navy header cells already punch through an open Journey drawer today; making the whole `thead` sticky
   at `z-index: 3` extends the defect from two cells to the entire header row. Fix by portalling `ui/Drawer` to
   `document.body` (as `ui/Dialog` already does, `dialog.tsx:48,62`) — a **Phase 1 component change**, not a CSS one,
   and worth doing whether or not the sticky header ships.
4. **`.modal-overlay.closing { animation: fadeOut }` is inert as written.** CSS cannot animate an unmount, and
   `dialog.tsx:47` returns `null` the instant `open` flips, so no node is ever present to carry the `closing` class.
   The exit fade needs an `exiting` state in `Dialog` (and `Drawer`) that keeps the node mounted for the animation
   duration and then unmounts — again a component change, which §2.1 does not list and §2.2 sizes as a CSS rule.
   Either scope that component work or drop the exit animation; do not ship the rule believing it does something.
5. **The row-hover fix does not cover the whole zebra defect.** `tbody tr:nth-child(even) td` also counts the
   **collapsible date-group header rows** that `loans/page.tsx:296-310` emits into the same `<tbody>` via a Fragment,
   so the stripe pattern is already scrambled on every grouped register independently of hover. Either move group
   headers into their own `<tbody>` per group (which also makes the collapse semantics cleaner) or drive the stripe
   from a data attribute the row renderer sets, rather than `nth-child`.
6. **The same zebra bug hides a functional state, not just hover** — see the new Phase 0 item 0.11: selected rows on
   the leads table are invisible on every even row.

**Two further caveats on the sticky rule, to settle before implementing it (added 2026-10-06):**

- **`14rem` is a guess, and it is per-page.** The offset has to clear the shell header, the `PageHeader`, the toolbar
  row and — on the dashboard, verifications, performance, my-decisions and transactions — a row of stat cards above
  the table. A single constant will leave dead space on the lean pages and clip the last row on the tall ones. Measure
  the four tallest layouts first and either pick the worst case or set the bound per page with a CSS variable
  (`--register-offset`, defaulted in the modifier and overridden on the five stat-card pages). Check a 768 px-tall
  laptop and a 150 % browser zoom before shipping: below roughly 560 px of usable height the inner scroller is worse
  than the page scroll it replaces, so pair it with
  `@media (max-height: 700px) { .staff-register-scroll { max-height: none; } }`.
- **`rowFlash` as specified is invisible, and its keyframes would never be emitted.** Two separate defects:
  (a) **Contrast.** `--gold-50` is `#E7F6EF` and the zebra `--grey-100` is `#F7F2E9` (`globals.css:49`); their
  luminance ratio is **1.000:1** — the flash literally cannot be seen on even rows, which is half of them. Pick a
  flash colour against *both* row backgrounds (the `gold.100`/`gold.200` end of the scale, or a left-border pulse
  rather than a fill) and check it against `#FFFFFF` and `#F7F2E9`.
  (b) **The keyframes will not exist.** The plan says to add `fadeOut` and `rowFlash` to `tailwind.config.ts` beside
  `fadeUp`, but Tailwind 3.4 emits `theme.keyframes` **only** as part of a generated `animate-*` utility found in the
  content scan. Both consumers here are raw CSS (`animation: rowFlash …` / `animation: fadeOut …` in `globals.css`), so
  no `@keyframes` block is ever written and both animations are silent no-ops. Declare `@keyframes` directly in
  `globals.css` next to the rules that use them.
  (c) **And it still needs a cap.** On the queues and the collections worklist a poll can change many rows at once;
  flashing 30 rows is noise, not information. Flash only when the changed-row count is ≤ 5, and never on the first
  paint after a filter or period change (every row is "new" then). Note each page must hand-write its own change
  detection — the plan prices `rowFlash` as one CSS rule, but it is one CSS rule plus per-page diffing on ~8 pages.
- **Also unimplementable as written: the group-height animation.** §3.3/§3.6/§3.7 propose animating collapsible date
  groups via `grid-template-rows: 0fr → 1fr`. The groups are `<tr>` elements emitted into a shared `<tbody>` through a
  React Fragment, so there is no per-group box to animate, and `grid-template-rows` has no effect on table-internal
  display types (`table-row-group`/`table-row`/`table-cell`). Either give each group its own `<tbody>` (which also
  fixes the `nth-child` zebra defect above) or drop the animation and keep the instant collapse.

### 2.3 Data-fetching hygiene (frontend, no backend change)

| Change | Why (verified) | Where |
|---|---|---|
| **One session query.** Make `useStaffSession` read through the `['staff-me']` React Query key (or seed it with `queryClient.setQueryData` after its fetch) and invalidate that key on the `navix-staff-session` window event that login/logout dispatch (`staff-session.ts:22,101-106`) | every page load hits `/api/auth/staff/me` twice: the shell's raw `fetch` cannot dedupe with the RQ hook used by 32 files; `CustomerOwnerPicker` even calls the raw hook per row (up to 50 fetches on the telecalling page) | `staff-session.ts:25-34,90-109`, `pipeline/hooks.ts:30-40`, `customer-owner-picker.tsx:30-34` |
| **Shared query keys for shared endpoints.** `staffApi.performance` is cached as `['staff-dashboard-performance']` on the dashboard and `['staff-performance']` on the performance page; settlements as `['staff-dashboard-settlements']` vs `['collections-settlements']` | navigating between them refetches identical payloads and a decision on one page leaves the other's count stale | `dashboard/page.tsx:53,304-309`, `performance/page.tsx:67-71`, `settlements/page.tsx:31` |
| **`placeholderData: keepPreviousData` on every keyed list query** | pages whose key changes on filter/period (performance, my-decisions, loans, verifications already has it) blank the table and flash **fabricated zeros in the stat tiles** on every period change | `performance/page.tsx:73,97-106` |
| **Reset page synchronously with the filter** | with `page` in the key and the reset in a `useEffect`, a filter change on page > 1 fires two requests and discards the first | `accounting/transactions/page.tsx:93-95,99` (same pattern in customers/leads) |
| **A polling policy.** Keep the intervals (they are product decisions) but (a) never poll hidden tabs — React Query already pauses interval refetches when the window is unfocused, so the "background tab hammers the server" fear is unfounded; (b) invalidate narrowly: logging a collections interaction currently invalidates `['collections-worklist']`, which the **sidebar badge on every page** observes | `staff-shell.tsx:120-131`, `collections/[loanId]/page.tsx:53-57` |
| **Route-level `loading.tsx` under `src/app/staff/`** rendering `<Skeleton variant="table"/>` inside the shell | there is none; the only navigation feedback is the 3 px `RouteProgress` bar and five different page-local `Suspense` fallbacks | `frontend/src/components/app/route-progress.tsx`, shared-layer scan |

> **§2.3 corrections from the 2026-10-06 review.** The five items above are all worth doing; three of them are
> argued from a wrong mechanism or a wrong number, which matters because the wrong parts are what a reviewer would
> check:
>
> - **"Never poll hidden tabs — React Query already pauses when the window is unfocused."** The conclusion is right and
>   the load-bearing word is wrong. At the pinned `@tanstack/react-query` **5.62.0**, `refetchIntervalInBackground` has
>   no default (falsy), and the interval callback gates on `this.options.refetchIntervalInBackground ||
>   focusManager.isFocused()` — where `isFocused()` resolves to **`document.visibilityState === "visible"`**, i.e.
>   **tab visibility, not window focus**. A background *tab* is hidden, so intervals do pause and the
>   "background tab hammers the server" fear is indeed unfounded. But a **visible yet unfocused window** — second
>   monitor, split screen, devtools focused, another app in front: the normal state of an ops console left open beside a
>   dialer — is `visible` and **keeps polling at full rate**. So the §2.3 row understates the standing cost, and Phase 4's
>   "add `refetchIntervalInBackground: false` explicitly (already the default)" is a no-op. If the ambient poll load is
>   the concern, the lever is a **visibility-*and*-idle backoff** (pause or lengthen after N minutes without
>   interaction), not a flag that is already set the way the plan wants.
> - **Relatedly, `staff-shell.tsx:122-125` carries a comment asserting that "React Query takes the shortest interval
>   across observers".** It does not — `QueryObserver#updateRefetchInterval` sets the interval per observer. Fix the
>   comment while touching the shell, and do not design the polling policy around the behaviour it claims.
> - **The session double-fetch: right outcome, wrong mechanism and wrong magnitude.** `useStaffSession` has **five**
>   call sites, not one (`staff-shell.tsx:277`, `customer-owner-picker.tsx:30`, `detail-parts.tsx:101,214`,
>   `admin/api-dashboard/page.tsx:51`, `dashboard/page.tsx:281`), and on **`/staff/dashboard` and
>   `/staff/admin/api-dashboard` both `/me` reads are raw** — neither page mounts a `['staff-me']` consumer at all — so
>   "the shell's raw fetch cannot dedupe with the RQ hook" is not the mechanism on the first two pages it would apply
>   to. The telecalling worst case is **27** round trips at the default page size (1 shell + 1 shared `['staff-me']` +
>   25 pickers), not "up to 50"; it is 102 at page size 100, and because the rows are keyed `<tr key={r.id}>` a page
>   turn remounts all 25 pickers for **25 more** — a cost the "per page load" framing misses entirely. Finally,
>   `app/api/auth/staff/me/route.ts` is a nine-line **cookie read** with no hop to `BACKEND_BASE_URL` and no JWT
>   verification, so these are Next function invocations and serial RTTs, **not** Spring or database load. The fix
>   stays correct and cheap; the item should be ranked as a latency/waterfall fix, not a backend-load fix.
>   Two bonus findings on the same component: the picker's `useStaffSession()` at `:30` is **dead on the telecalling
>   page** (`telecalling/page.tsx:281` passes `allowedRoles={TELECALLER_ONLY}`, so `actorRole` is never consulted), and
>   it sits **above** its own `<PermissionGate permission="customer:assign">` at `:69`, so roles that will never see the
>   control still pay for the fetch. Deleting the call is smaller than rerouting it.
> - **"Fabricated zeros" is right on `/staff/performance` and wrong on `/staff/my-decisions`.** Performance really does
>   render literal `0`s (`:73` `rows ?? []` → `:97` `reduce` from a zeroed accumulator → `:176-191` `StatCard`, with the
>   tiles mounted *above* the `isLoading` branch at `:215`). My-decisions already guards with
>   `stats ? … : …` (`:148-163`), so it shows no fake zero — strike it from §2.3's list. The review found **one instance
>   the plan misses**: `loans/page.tsx:122-123` derives `counts = segmentCounts(q.data ?? [])` and renders
>   `({counts[s]})` in the segment chips at `:235`, so every submitted search or date change flashes **"(0)"** across
>   all four chips. Same bug class, different widget; add it to §3.6.
> - **One more over-fetch on the customers register, unmentioned in §3.3:** the chip-count query
>   `["customers-summary", query, from, to, mine]` (`customers/page.tsx:181-185`) **keys on the search term**, so every
>   submitted search fires a second whole-book aggregate beside the page query. The in-file comment ("it stops every
>   keystroke re-counting the whole book") is stale now that `SearchBar` submits on Enter — true, but it still
>   re-counts once per search. Drop `query` from that key unless the chips are meant to reflect the filtered set.
> - **No request is ever cancelled.** The single fetch helper `bff()` (`lib/api/applications.ts:1036-1046`) passes no
>   `signal`, so React Query cannot abort a superseded request. With `keepPreviousData` arriving in Phase 1 and keys
>   that change on every filter, period and page, a fast operator leaves a trail of in-flight requests whose responses
>   are parsed and discarded. Threading `signal` through `bff()` is a ~5-line change and belongs in Phase 1 item 5
>   alongside `keepPreviousData`, not after it.

### 2.4 Table conventions (apply once in `AppRow`/`QueueTable`, then per register)

1. Column order stays `S.No. · identity · facts · actions`; **identity and actions stay sticky**.
2. Money and counts get `className="num"`; dates keep `whitespace-nowrap`; IDs keep `font-mono`.
3. Every status cell renders through `StatusBadge`.
4. Every register gets the sticky `thead`, the working row hover, and `Skeleton variant="table"` on first load only
   (`isLoading`, never `isFetching`), with the header refresh spinner for background fetches (already the behaviour on
   most pages; the plan just makes it uniform).
5. The two date controls converge: `QueueDateFilter` keeps its behaviour but adopts the `PeriodPicker` pill styling
   (or vice versa — pick one; recommendation: the square navy pills, because they sit inside toolbars on 9 pages).
6. **Added 2026-10-06 — table semantics, which this section omitted.** Across the **33** `<table>` elements in staff
   scope there are **2** total `scope="col"`/`<caption>` occurrences and **0** `<table aria-label>`. §2.4 codifies
   column order, alignment, badges, skeletons and hover but says nothing about whether a screen reader can navigate
   these grids, and §3.2 asks for a caption on `QueueTable` alone. Make it a convention here instead: `scope="col"` on
   every `th`, a visually-hidden `<caption>` naming the register and its filter state, and `aria-sort` on the sortable
   headers that already exist (`SortableTh`, `FilterableTh`). It is a one-pass change in the shared row/header helpers,
   and it is the difference between "dense" and "unusable with assistive tech".

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

**3. Proposed subtle UI improvements.**
- A per-source `failed` flag in `RoleQueue`; render "Couldn't load part of your queue — Refresh" (`ErrorState`) instead
  of the empty hero.
- Refresh → `queryClient.invalidateQueries({ predicate })` over the dashboard keys (active only).
- One `Skeleton variant="stat"` grid per section that keeps the card shape; "Updated 8 s ago" in each section header
  (one clock for the whole board).
- `tabular-nums` on every `StatCard` value and team-table number (the `num` class).
- `PeriodPicker` custom range commits on blur / Apply.
- Keep the queue table newest-first (the order every role reads today); make the hero's oldest-waiting line link
  to its row and add an "Oldest first" toggle; show the age column.
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
`payouts/count`, `pending-repayments/count` — each rejecting DSA like every other staff-open surface, CLAUDE.md §7,
since `hasRole("STAFF")` alone admits a DSA token); `bookStats` as one or two SQL aggregates over the scoped customer-id
CTE; resolve `scope()` once per request; `trends` as three `GROUP BY date` projections; `listCases` with
`assignedOfficerId`/`status` predicates in SQL; the ledger fix of §3.10 with `size=5` from the dashboard; poll
performance/decisions at 60 s (`SLOW_MS`, `dashboard/page.tsx:55`) instead of 10 s (`REFRESH_MS`, `:53`); batch the referral name lookup.

**8. Database optimisation opportunities.** `idx_loan_disbursed_on` (trends + ledger + register);
`loan_application(assigned_executive_id, status)` (used by `byStatus` for executives and by `scope()` on every
book-stats/by-ids call).

**9. Expected user impact.** The landing page tells the truth when the backend is down, stops flickering section by
section, and an ADMIN's board no longer rebuilds the whole ledger and the whole book every minute.

**10. Implementation complexity.** UI: **S–M**. Refresh/flags/payouts fixes: **XS–S**. Backend aggregates: **M**
(bookStats, trends), **S** (count endpoints), plus the shared ledger work.

---

### 3.2 Live applications — `/staff/applications`

**1. Current-page observations.** `PageHeader` with `QueueDateFilter` (Today / Yesterday / Custom / All), the shared
`SearchBar` (searches on Enter) that composes with the date window in every query key, the role badge, `RefreshButton` and a link to
the ledger; a `ReviewLookup` panel; then the role's panels (`page.tsx:160-234`): the Credit Head's `CreditWorkbench`
(unallocated + per-executive groups + the executive roster), `StatusQueue`s per lifecycle status, the
`AwaitingRepaymentPanel` (ACTIVE / OVERDUE split client-side, 60 s), `RepaymentVerifyQueue` (Accountant, 8 s),
collection-payment queues (10 s) and a lazy `ClosedPanel`. Each queue is a `staff-data-table` of 17–19 columns (options such as ☐ and Journey add cells)
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
  three places (`application-detail-dialog.tsx:876,1139,1174`). The **console** body is **11 px**
  (`.navix-crm { font-size: 0.688rem }`, `globals.css:116`, against the 16 px root — the `12.8px` at `globals.css:100`
  is set on `body`, so it never changes what `rem` resolves to), **but those captions do not sit under it**: the dialog
  sets its own body to `text-[10.4px]` (`application-detail-dialog.tsx:309`) and is outside `.navix-crm` anyway, so the
  original "10.4 px body" figure was right *for the dialog* and 11 px is right for the console. The honest framing: the
  real baselines on these screens are **9.6 px (`text-xs`, 414 uses in staff scope), 11.2 px (`text-sm`, 368 uses) and
  10.4 px (dialog + Customer 360 panel)** — so the *contrast* gap this bullet dramatises (9.2 vs 11) is smaller than
  claimed while the *absolute* size problem is much larger and console-wide. `text-[8.8px]` itself occurs **29 times
  across 15 files**, including `verifications/page.tsx:432`, `dashboard/page.tsx:1191`, `customer-tabs.tsx:1091,1094`,
  `detail-parts.tsx:182,322,548,609` and five in `verification-checks.tsx` — four of the five pages §3.1/§3.4/§3.5
  analyse. Capping the three dialog captions leaves 26 behind, so this is a **type-scale decision (Appendix C, A11Y)**,
  not a dialog-local fix. The dialog is also portaled
  **outside `.navix-crm`** so the console density rules do not even apply inside it.
- No sticky header on queues that are routinely 25–100 rows tall; no `<caption>`/`aria-label` on `QueueTable`.
- Account, IFSC, PAN and mobile are shown in full on every row (`app-row.tsx:49-53,86-89`) — an explicit product decision for
  screen-shared queues (`app-row.tsx:49-53`); recorded here because the backend `com.navix.common.util.Masking`
  already defines the masking rules and the loan dialog carries a local `maskPan` (`loan-detail-dialog.tsx:50`).
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
  **Two consequences the first draft did not state.** (a) 11 px *is* the console body size, so floored headers stop
  being visually subordinate to their cells — keep them distinct with the uppercase + `letter-spacing: .04em` +
  navy-fill treatment they already have, not with size. (b) 9.2 → 11 px is a **19 % widening of every header cell** on
  tables of 17–22 columns, so registers that fit inside `84rem` today will cross it and start scrolling horizontally
  — the very symptom §3.11 treats as a bug on the leads table. Measure the widest three registers
  (customers 22 cols, applications 17–19, collections 15) at 1366 px and 1440 px before committing to 11 px; if they
  cross, the honest trade is **10 px headers plus the `84rem` floor re-scoped per table** (§2.4 item 5), not 11 px and
  a wider scroll. Do not raise the floor and leave the width unmeasured.
- Sticky header; visually-hidden caption per queue; a visible "Select" header label and a one-line hint
  ("Tick rows to assign or reject in bulk") in the panel header when bulk is enabled.
- Amount cell: two-tone — requested amounts in `text-ink`, eligible limits in `text-muted` with the tag first
  (`elig ₹25,000`).
- "Updated 6 s ago" beside each count pill; `rowFlash` on rows that arrived or changed status since the last tick.
- Icon buttons (✓ / ✕ with tooltips) in the repayment queue to match `AppRow`.
- `staleTime: 15 min` on the executive roster, invalidated from the admin staff page.
- Optional per-user "mask identifiers" toggle in the header (default off, remembered per staff id) for screen-shared
  sessions — client-side only: add `maskPan/maskMobile/maskAccount` to `lib/utils.ts` mirroring
  `com.navix.common.util.Masking` (lifting the local `maskPan` from `loan-detail-dialog.tsx:50`).
- `useMemo` the ACTIVE/OVERDUE split.

**4. Data-presentation improvements.** Promote `Due`/DPD and the stage-entered age into the first visible columns for
the repayment panels (identity · amount · due · age · actions); on the disbursement panel **keep account and IFSC in
the visible columns** — the Disbursement Head reads them to make the transfer — and reorder only within the scroll
region; group the credit queues by day like the customers register; show "in stage 3 d" as a
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
`customer_profile(application_id)`). `loan_application(assigned_executive_id, status)` **does not exist yet** — an
earlier draft said it "ships in the V74 batch", but `V74` is `application_verification_reopened` and the highest
migration on `main` is `V75`; it is proposed here for the new **`V76`** index migration in §4 — it also serves
`scope()` on every book-stats / by-ids call (§3.1).

**9. Expected user impact.** Reviewers stop squinting at 9 px headers and 8.8 px captions, see which file just
arrived, and can read amounts without misreading a limit as a request. With paging, a busy disbursement day no
longer re-enriches 300 files every 8 s.

**10. Implementation complexity.** UI: **S–M** (type floor, sticky, badges, cues, memo). Backend: **M** (pagination
+ SQL search + projection); credit-brief trim: **S**.

---

### 3.3 Customers — `/staff/customers`

**1. Current-page observations.** `PageHeader` + ADMIN `ExportMenu` (this page / all customers, capped at
`EXPORT_CAP = 50 000`, `CustomerService.java:284`) + Refresh; 13 segment chips with live counts; a filter row —
the shared `SearchBar` (placeholder "Name, PAN, mobile, customer or application ID"), `QueueDateFilter`, a "My
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
- A **Columns** menu (checkbox list, persisted per staff id in `localStorage`) default: all 22 columns visible, hiding is
  opt-in and remembered; the sticky identity/actions columns are never hideable.
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
`page.tsx:74`): left, a 9-tab panel (`customer-tabs.tsx:39-49` — Personal, Employment, Bank, Verifications,
Credit, Documents, Loan applications, Call logs, Audit logs) inside a `max-h-[68vh]` scroller; right, the ADMIN
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
- Right column keeps every correction card visible; at most a per-card collapse, all expanded by default (same
  cards, same forms).
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
`:1519` is unused), staff names **per distinct id, uncached** (`:681,712,757` each call `findStaff(id)`;
`StaffDirectory` has no cache anywhere — note the batched path is *not* missing from the codebase, as an earlier draft
implied: `findStaffByIds` is live behind `StaffDirectory.namesFor`, which `SettlementService`,
`CollectionPaymentService`, `CollectionsService` and `ApplicationController` already use, so this is a call-site fix
with an in-repo precedent rather than new machinery), every `application_event` row for the customer (`:716`), three breakdown queries, payments **per
loan** (N+1, `:747-751`), owner (+1 name), credit brief (4 queries, `CreditBriefService.view:179-217`), limit
override; scoped roles add 3–6 (`TELECALLER`: `ownerRepository.findAll()`). The response embeds the **full raw bureau
`providerResponse` JSON** on every customer read (`CreditBriefDtos.java:29-30`) while the page uses four fields and
the Credit tab re-fetches the brief anyway. The roll-up is fetched again under `['customer-detail', id]` when a loan
card or dialog opens (`loan-detail-dialog.tsx:95-99`). `activity()` (Audit tab) is a 3-per-application N+1
(`:1229-1274`).

**7. Backend/API optimisation opportunities.** `paymentRepository.findByLoanIdIn(loanIds)`; the batched
`latestProfile` overload; `findStaffByIds` once; serve a headline variant of `CreditBriefView` (no `providerResponse`) from
`CustomerService.detail` and keep the full record on the credit-brief endpoint; batch `activity()` per customer; share one query key for the
roll-up across page and dialogs; give `VerificationChecksPanel` the `['verifications', appId]` key (or vice versa) so
the endpoint is read once; defer the owner-picker roster to first open.

**8. Database optimisation opportunities.** All filtered columns are indexed; no change needed.

**9. Expected user impact.** ADMIN corrections confirm themselves; the page opens with a quarter of the queries and
without a credit report in the payload; reviewers get an exposure line instead of adding up loans by eye.

**10. Implementation complexity.** UI: **S–M**. Backend batching: **S–M** (all batched methods already exist). Brief
payload trim: **S** (a headline `CreditBriefView` variant for the customer read; one consumer, `CustomerDetail`).

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
  `ApplicationVerificationService.java:3520-3524`). **High.**
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
- The reminder button (a borrower-facing send) opens `ConfirmDialog`; override keeps its one click and gets a `toast`
  ("PAN overridden to PASS") plus the new audit event — the panel already refetches immediately because its keys
  are active.
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
(all / active / overdue / closed with live counts, `cal-preset`); the shared `SearchBar` (`w-80`, searches on Enter) + `QueueDateFilter`
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
- **Added 2026-10-06:** the segment chips flash **"(0)"** on every submitted search or date change — `:122-123` derives
  `counts = segmentCounts(q.data ?? [])` and `:235` renders `({counts[s]})`, and the query (`:118`) keys on `query`
  with no `placeholderData`, so all four chip counts read zero while the refetch is in flight. Same defect class as the
  performance page's fabricated zeros (§2.3); fixed by the same `keepPreviousData`.
- Outstanding is a single number; the backend computes interest / penalty / paid / settlement cap for every row and
  `outstandingForAll` **discards the breakdown** (`RepaymentService.java:400-403`); an approved settlement is invisible
  in the row (`LoanRegisterRow` has no settlement field).
- Pagination does not reset on search/segment change; each new search flickers the skeleton and the chip counts.
- DPD shows "—" both for "not yet due" and "due today".
- The detail dialog re-derives everything from `loanId` in a 2–3 hop waterfall (`loanQ` → `customerQ` → …) although
  the register row already carries `customerId`, `applicationId`, name, cycle and sanction fields; `outstanding` is
  **eager** (`enabled: open`, `loan-detail-dialog.tsx:106-111`), not deferred as the observer thought.
- The by-loan collections-case call is a swallowed 404 on every healthy loan.

**3. Proposed subtle UI improvements.**
- `num` on Sanctioned / Principal / Net / Repayable / Outstanding; sticky header; `setPage(1)` inside the segment
  handler; `keepPreviousData` so counts and rows do not flicker. (The search box and its clear button now come from
  the shared `SearchBar`.)
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
the shared `SearchBar` (case-insensitive) + `QueueDateFilter` + `BulkActionBar` (Assign); a 15-column `staff-data-table`
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
- Selection clears whenever the page's row set changes (`bulk-actions.tsx:47-51`, wired at
  `collections/page.tsx:197-204`) — by design, so a bulk assign never touches a loan the operator paged away from;
  the cost is that a Head cannot assign across pages in one go.
- Overdue rows are marked by red text in two cells only; no row-level cue; no sticky header; money left-aligned.
- Export refetches the customer enrichment on every menu open (`page.tsx:223-225`).
- No "last refreshed" cue although the page is live.

**3. Proposed subtle UI improvements.**
- Sticky header, `num` on Principal / Outstanding / Salary, a `Days to due` value inside the DPD cell (`+5` / `−3`)
  with the same badge tones as the loans segments, and a soft `error-50` left border on overdue rows.
- "Updated 6 s ago" next to Refresh (the poll is a feature; say so).
- Keep the page-only selection rule the page already enforces (`collections/page.tsx:197-204`: a bulk assign must
  never touch a loan the operator paged away from); show "N selected on this page" and let large batches use page
  size 100 + the bucket filter.
- Bulk assign dialog shows "12 / 40 assigned" with per-loan ✓/✗ and runs 4 in parallel; skip the pre-GET (§7).
- `setQueryData` the returned case into the worklist row instead of invalidating.

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
per bucket; rejects DSA like every other collections read) for the six cards **and** the sidebar badge, so the badge stops fetching the full worklist; a
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
- Show PTP date only for `PROMISE_TO_PAY` and prompt for it (a warning, not a hard requirement — the backend accepts a
  null `promiseToPayDate`, `CollectionsDtos.java:132-145`); keep proof-ref for `PAID`.
- `inputMode="decimal"`, collapse to one dot / two fraction digits, disable the button on `NaN`.
- Refresh refetches all six queries; the ADMIN payment dialog invalidates the case and payments keys.
- First load: `Skeleton variant="row"` per card instead of one page-sized block; keep the two-column layout visible.

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
- Pass the resolved `LoanSummary` into `buildDetail`; have the page `setQueryData` the `CaseDetailView` that `assign`
  already returns instead of invalidating (response shape unchanged), so the second detail build stops being wasted.
- Add `loggedByStaffId` + `loggedByName` to `InteractionView` (one `namesFor` batch).
- Invalidate the worklist only from `assignOfficer`.
- Memoise `scope()` per request (request-scoped attribute) or replace it with a single visibility query.
- Call `openCase` directly for numeric ids (add `?create=false` if a read-only visit must not flip the loan).

**8. Database optimisation opportunities.**
- `collection_case.loan_id` is indexed but **not unique**; `openCase` is check-then-insert, so a concurrent double-open
  can create two cases (`CollectionCaseRepository.java:16-35`). Add `uq_collection_case_loan_id` as a **separate, guarded step** —
  count duplicates, re-point `settlement` / `interaction_log` / `collection_payment` rows to the newest case (there
  are no FKs to protect them, CLAUDE.md §10), delete the rest, then create the unique index — not part of the
  index-only `V76` batch.
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
- Status segment chips (All · Proposed · Approved · Rejected) with counts, defaulting to **All** with `PROPOSED` rows
  sorted to the top, so the view the Head sees today is unchanged.
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
when active) and three direction tabs (navy when active); the shared `SearchBar`; refresh (icon swaps, button never
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
`page.tsx:221,321`); the shared `SearchBar` + Call-status select; a two-column grid — an 8-column table (S.No.,
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
- Override the min-width on this table (`!min-w-[40rem]` — a plain utility loses to the un-layered `globals.css`
  rule, the same reason every dialog uses `!max-w-*`) — or better, scope `84rem` to the pipeline table via a modifier
  class (§2.4).
- Name cell becomes a `<button>` (or row `tabIndex=0` + Enter/Space); `aria-pressed` on the stars; a "clear rating"
  ✕ next to them.
- Inline `error` on salary/amount when non-empty and not a positive number; block Save.
- One **Save** button that always sends the disposition PUT and sends the outcome PUT **only when the outcome or DSA
  note changed** (today the page rewrites both on every click, `:374`).
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
- A count line "12 assigned to other telecallers" under the section headers; the rows themselves stay hidden unless
  product approves exposing colleagues' allocations (`telecalling/page.tsx:58-64`).
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
  **and is already used by `ApplicationController:592`**, just not here (`AdminApplicationService.java:149-151` still
  calls `findByApplicationIdInOrderByAtDesc`; an earlier draft called the repository method "unused", which overstated
  it — and understated how cheap the fix is: the pattern is proven one module away);
  it also hydrates full `LoanApplication` (26 cols) and
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
  in the table (`DecisionHistoryService.java:98-100` vs `:242-270`).
- No search or sort within the loaded list; the application id is not a link (only the customer name is).
- Remarks live only in a `title` attribute (hover-only, invisible to keyboard and screen readers, `:234`).
- Period change unmounts the table to "Loading…" and resets to page 1; the Loader2 next to the selector flickers on
  every refetch.
- Customer ID and Application ID sit side by side in identical mono styling; no sticky header; `min-width: 84rem`
  with no sticky identity column although the helpers exist.

**3. Proposed subtle UI improvements.**
- Always pass the caller's own id when no `staffId` is chosen —
  `staffApi.performance(range.from, range.to, staffId ? Number(staffId) : me.id)` in `page.tsx`. The service must
  keep `rosterFor(null)` = whole roster, because `/staff/performance` relies on it; assert on the client that
  `summaryQ.data.rows.length === 1`.
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

**7. Backend/API optimisation opportunities.** Push the `DECISION_ACTIONS` filter and a `LIMIT` into the events query; return the
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
- The client-side filter recomputes on every render with no `useMemo`, so `usePagination`'s memo never hits
  (`:64-81,109`). (Per-keystroke work is gone now that the page uses the shared `SearchBar`.)
- The incomplete badge says " · no e-sign" but the flag is `agreementAccepted`, set at the agreement-consent step and
  again on eSign completion (`:174-177`; `ApplicationVerificationService.java:2511,2690`) — a false flag means the
  agreement itself was never accepted, yet staff look at the eSign step.
- Status and Completeness are private pills; no sort; no sticky header; no "clear filters"; the row count is easy to miss.
- In the detail dialog, `briefQ` waits for `appQ` only to skip `DRAFT` although the endpoint already returns an
  `available=false` shell (`application-detail-dialog.tsx:204-208`); `appQ` polls every 8 s while open even in a
  read-only register.

**3. Proposed subtle UI improvements.**
- `enabled: myRole === "ADMIN"` and `retry: false` on 4xx; `useMemo` the filtered rows.
- Rename the badge to " · agreement not accepted" (tooltip "Agreement documents not yet accepted").
- `StatusBadge` for status and completeness; `num` on Amount; sticky header; a "Clear" chip when a filter is active;
  the count inside the `TableToolbar`.
- Start `briefQ` with `enabled: open` (the 8 s dialog poll already pauses in hidden tabs — TanStack's default).

**4. Data-presentation improvements.** Show `currentStageEnteredAt` (already fetched, only exported) as a "Stage
since" column; group by status with a collapsible header (same interaction as the customers date groups); sort by
Status / Amount / Stage since.

**5. Transition / micro-interaction improvements.** Skeleton table on first load; `rowFlash` after a Refresh on rows
whose status changed; dialog exit fade.

**6. Performance observations (verified).** `listAll` = `findAll()` unpaged and unordered (sorted in Java), full
`CustomerProfile` entities (~50 columns incl. `credit_brief_facts` JSONB) for **every** application, bureau states and
required-passed counts chunked by 1000 (+ re-apply hops), `findStaff` per distinct assignee at `:84` (the batched
`namesFor` exists **and is used by `ApplicationController:577`** — this service simply never adopted it), and `findByApplicationIdInOrderByAtDesc(all ids)` = **the whole `application_event` table read and sorted**
to keep one timestamp per application (`AdminApplicationService.java:65-104`). The DTO carries 34 fields, the table
renders 18 of them across its 13 columns; 464 KB at 550 rows (`docs/perf`). Indexes on `loan_application` do not help a filterless `findAll`; there
is no `application_event (application_id, at)` index.

**7. Backend/API optimisation opportunities.** `page/size/q/complete` params →
`applicationRepository.findAll(spec, PageRequest.of(page, size, Sort.by(DESC, "id")))`, enrich only the page's ids;
`findLatestEventAt(chunk)`; `namesFor(distinctAssigneeIds)`; a light list DTO (the 18 rendered fields) with the export-only fields fetched by
`ExportMenu.onOpen` — a narrowing of `GET /applications/all`, whose only consumer is this page; an interface projection for the profile columns actually read; `eventViews` resolves actors with
one `namesFor`.

**8. Database optimisation opportunities.** Add `idx_application_event_application_at (application_id, at desc)`.

**9. Expected user impact.** The ADMIN register opens in one page instead of the whole book; the badge stops
misdirecting staff to the eSign step.

**10. Implementation complexity.** UI: **S**. Server pagination + projections: **M**. Index: **S**.

---

## 4. Combined implementation plan

Ordered by **impact ÷ disruption**. Each phase is independently shippable and leaves every workflow, route and role
exactly where it is. Sizes: XS < ½ day · S ≈ 1 day · M ≈ 2–4 days · L ≈ 1–2 weeks (one engineer).

> **Headline estimate and phase order, both corrected 2026-10-06.**
>
> **Effort: 12–16 weeks for one engineer, not ~6.** The plan's own review note concedes "the primitives are a day; the
> migration is the project" and then never updates the headline. Against the actual surface: the 15 pages are **6,290
> lines** of `page.tsx` inside **25,824 lines** of staff scope, and adoption means roughly **296 mechanical edits**
> across ~60 files (51 skeleton sites + 46 loading strings + ~29 empty states + 170 error paragraphs, plus every status
> pill and money cell), on top of Phase 1.0 building a test harness that does not exist, six registers needing their
> pagination markup restructured, and `ui/Drawer` needing a portal. Phase 3's fifteen backend items are separately
> sized M–L each. Re-quote the phases honestly or cut scope; a 6-week number will be read as a commitment.
>
> **The phase order is backwards on risk.** Phase 1 — shared CSS used by 15 registers, a `ui/Dialog` with 41
> consumers including the regulated consent screen, and four new primitives across ~60 files — lands **first**, into a
> pipeline with no component tests, no screenshot baseline and no build gate. Phase 3 — the backend half — lands
> **last**, and it is the half with real coverage (`./mvnw test`, the Testcontainers flow test, the `docs/perf`
> statement-count harness, and service tests already written for most of the touched classes in `dc25b93`).
> Recommended order: **Phase 0 (split per the note below) → Phase 1.0 (harness) → the three or four highest-value
> shared changes → Phase 3 items re-ranked by a fresh measurement → the rest of Phase 1/2 opportunistically.** That
> front-loads the work that can be verified and defers the work that cannot.

### Phase 0 — correctness fixes that fell out of verification (ship first, ≈ 3–4 days total; **11 items**, one of which — 0.5 — the 2026-10-06 review found already shipped)

> **Split this phase in two and ship the first half independently of this plan (added 2026-10-06).** Four of the ten
> items are not UI work and should not wait on a UI proposal's approval, its review, or its branch:
>
> | # | Why it is not a UI item |
> |---|---|
> | **0.4** | An **authorization hole**. `SettlementService.listAll` carries no `requireOneOf` and no DSA rejection at all, while `CollectionsService` gates the same data at `:168,195` — so any staff bearer, DSA and TELECALLER included, can list every settlement ever proposed. This is exactly the open-by-default trap `CLAUDE.md` §7 warns about for `requireStaff()`-shaped surfaces |
> | **0.5** | A **permission-model disagreement** between `rbac.ts` and the service. Whichever way it is resolved, it is a policy decision about who may re-run a billable identity check |
> | **0.7** | **Real money.** No in-flight guard on retry means a second click re-bills the provider; no reminder cooldown means a borrower can be SMS-blasted. `CLAUDE.md` §14 flags provider calls as billable with no sandbox |
> | **0.8** | An **audit gap**. `manualDecision` writes no `application_event`, so a human override of a verification verdict is invisible to the maker-checker trail that `CLAUDE.md` §5/§12 make the source of truth for SoD |
>
> The remaining seven (0.1, 0.2, 0.3, 0.6, 0.9, 0.10, 0.11) are genuine front-end correctness and belong in this plan.
>
> Verified independently at `0f14dab`: 0.4 (no guard present), 0.1 (`rosterFor(null)` → whole roster at
> `DecisionHistoryService:404-430`, page reads `rows[0]` at `my-decisions/page.tsx:99`), 0.2 (`["customers"]` cannot
> prefix-match `["customers-page"]`), and the §3.5 "Not started" defect (`byApp` is built from `data.rows`, i.e. the
> current page only, so a cleared file on another page renders as a `total: 0` "never verified" card).
>
> **Recommended:** 0.4 / 0.5 / 0.7 / 0.8 go out as a small security-and-audit release on their own, with backend tests,
> reviewed by whoever owns authz — not bundled into a UI changelog where they will be read as polish. Splitting also
> unblocks the security half if the rest of this plan is deferred or rejected.

| # | Fix | Page | Size |
|---|---|---|---|
| 0.1 | `/staff/my-decisions` sends the caller's own `staffId` when none is chosen (page-side only — the service's whole-roster default is what `/staff/performance` relies on) so a Head/ADMIN never sees another staffer's totals under "your decisions" | §3.14 | XS |
| 0.2 | `CustomerEditDialog` / `CaseFailureDialog` invalidate `['customers-page']` + `['customers-summary']` (not the legacy `['customers']`) | §3.3 | XS |
| 0.3 | `AdminLogPaymentDialog` invalidates `['collections-case-by-loan', loanId]` + `['collection-payments']` | §3.8 | XS |
| 0.4 | `SettlementService.listAll` gets the same `requireOneOf`/DSA rejection as `CollectionsService` (any staff token can list settlements today) | §3.9 | XS |
| ~~0.5~~ | ~~`rbac.ts` stops granting `verification:retry` to credit roles~~ — **STRIKE: already shipped.** `rbac.ts` holds `verification:retry` on **ADMIN only** (`:170`); `CREDIT_EXECUTIVE` (`:108-115`) and `CREDIT_HEAD` (`:116-126`) do not have it, the file carries a comment at `:83-89` explaining the ADMIN-only alignment, and the backend agrees (`ApplicationVerificationService:1119-1122`). Fixed in **`696f37f`** (2026-09-28). The plan's cited lines `:113`/`:120` are `"document:upload"` and `"customer:view"`. §3.5's matching "High"-severity finding and its "hide Retry unless ADMIN" proposal are stale too — **and this is the one place the header's "re-verified at `0f14dab`" claim demonstrably did not happen** | §3.5 | — |
| 0.6 | Rename " · no e-sign" on the all-applications completeness badge. **The plan's own replacement is also wrong:** `AdminApplicationService:97` derives the value from the signup **screen-1 terms tick**, not from the agreement-documents step, so " · agreement not accepted" substitutes a second misleading label and would still send staff to the wrong screen. Correct copy: **" · terms & conditions not accepted"** (tooltip "Borrower has not accepted the signup terms") | §3.15 | XS |
| 0.7 | Retry in-flight guard (`RETRY_IN_PROGRESS`) and a KYC-reminder cooldown — both prevent real money/SMS spend | §3.5, §3.12 | S |
| 0.8 | `manualDecision` appends a `VERIFICATION_OVERRIDE` `application_event` so overrides are auditable | §3.5 | S |
| 0.9 | Dashboard: a per-source `failed` flag so a backend outage renders an error notice instead of "You're all caught up" | §3.1 | S |
| 0.10 | Dashboard Refresh uses `invalidateQueries` (active only) instead of `.refetch()` on disabled queries | §3.1 | XS |
| 0.11 | **Added 2026-10-06 — leads row selection is invisible on every even row.** `leads/page.tsx:169` paints the selected state on the `<tr>` (`selectedId === row.id ? "bg-gold/10" : ""`), but the zebra is an **opaque** `tbody tr:nth-child(even) td` (`globals.css:422`), so on even rows the only feedback that a lead is selected is the `DispositionPanel` changing beside the table. Same root cause as the row-hover defect in §0, but this one hides a **functional** state on the page a Telecaller works all day — hence Phase 0, not Phase 2. Fix with the §2.2 hover/selection rules (`tr[aria-selected="true"] td`), and set `aria-selected` while you are there | §3.11 | XS |

### Phase 1 — shared foundation (≈ 1 week; every page benefits, zero workflow change)

1. `Skeleton`, `EmptyState`, `ErrorState` (+retry), `Toaster`/`toast`, `ConfirmDialog`, `Money`, `StatusBadge`,
   `TableToolbar` in `components/ui` (§2.1). **Drop the route-level `src/app/staff/loading.tsx`** — **33 of the 34**
   `src/app/staff/**/page.tsx` files are `"use client"` (only `src/app/staff/page.tsx` is not), none fetches
   server-side and none `await`s anything, so `loading.tsx` would cover only the RSC-payload fetch and then hand over
   to the page's own client-side pending state: a second flash, not a fix. The navigation feedback these pages need is
   the existing `RouteProgress` bar plus each page's `isLoading` skeleton. Revisit `loading.tsx` only alongside an
   actual RSC/`useSuspenseQuery` move, which this plan does not propose.
2. CSS: sticky `thead`, `.num`, working row hover, `rowFlash`, dialog exit, reduced-motion coverage (§2.2); scope
   the `84rem` min-width to the pipeline table via a modifier class so 8-column tables stop scrolling sideways.
3. `ui/Dialog` gains the focus trap / focus restore / scroll lock `ui/Drawer` already has, an exit animation and a
   `size` prop (`md` 460 px · `lg` 56 rem · `xl` 80 vw) replacing the 13 `!max-w-*` overrides (10 staff, 3 borrower).
   The trap itself is **not new code**: `hooks/use-focus-trap.ts` already exists and `ui/drawer.tsx:5` imports it, so
   this is an import plus the `body.style.overflow` lock Drawer already does at `drawer.tsx:49-52`. **XS, not S.**
   **Blast radius, corrected 2026-10-06:** `ui/Dialog` has **41 consumers**, of which **5 are borrower-facing** —
   `app/(borrower)/dashboard`, `app/(borrower)/loans`, `app/(borrower)/transactions`,
   `components/borrower/loan-details-dialog.tsx` and `components/borrower/terms-modal.tsx`. An earlier draft said
   "three borrower dialogs" and named two files. `terms-modal.tsx` is the **agreement-consent screen of the regulated
   Phase-1 intake** (§6 step 2, `agreementAccepted`), so a scroll-lock or focus-trap change there is not cosmetic:
   if the trap keeps the borrower from reaching the scrollable terms body or the Accept control on a phone, consent
   cannot be given and intake stalls at the gate. Smoke-test all five on a real handset viewport, and treat the
   borrower five as a separate review item from the 36 staff consumers — not as a footnote to a console change.
4. One session query (`useStaffSession` → `['staff-me']`); shared query keys for performance and settlements; the
   `CustomerOwnerPicker` reads the role from React Query (removes up to 50 `/me` fetches on the telecalling page).
5. `keepPreviousData` on the keyed list queries **that do not already have it, and only where the key change is a
   page/size move** — not "every keyed list query". Seven files already set it (`transactions:97,111`,
   `verifications:106,122`, `leads:67,76`, `customers:173,177`, plus `admin/leads` and `global-search`), so the real
   targets are `performance`, `my-decisions` and `loans`. **And it needs a guard the plan does not state:** when the
   key encodes a *scope* rather than a window — `['decisions', staffId, …]`, `['customer-detail', id]` — holding the
   previous data means showing **one staffer's or one customer's rows under another's heading** until the refetch
   lands. On `/staff/my-decisions` that is the same class of defect as Phase 0.1. Rule: `keepPreviousData` is for
   page/size/period changes; for an identity change, clear and show the skeleton.
   Thread an `AbortSignal` through `bff()` (`lib/api/applications.ts:1036-1046`) in the same change — it passes none
   today, so no superseded request is ever cancelled, and `keepPreviousData` makes that more visible, not less.
6. Page resets move into filter handlers. Scope note: on `/staff/loans` **search already resets** (`:243-246`); it is
   **segment** (`:144-150`) and the **date range** (`:250`) that do not — the latter unmentioned in §3.6. On
   `/staff/leads` only the Call-status select is affected (`:126-127` vs the effect at `:62-64`); `SearchBar` already
   batches `q` and `page` in one handler. The fix is right; the "changing a filter" framing is broader than the bug.
7. Converge `PeriodPicker` and `QueueDateFilter` on one pill style; replace 26 hand-rolled refresh buttons with
   `RefreshButton`.

**Acceptance:** `npx tsc --noEmit` and ESLint clean; Playwright smoke on the 15 routes; a visual diff shows only
header stickiness (registers now scroll inside their panel), number alignment, hover, loading-state changes and
the one period-pill restyle.

> **⚠ That acceptance bar does not exist yet, and as written it cannot catch what Phase 1 risks (added 2026-10-06).**
> Phase 1 is the most dangerous phase in this plan — it edits shared CSS that 15 registers depend on, a `ui/Dialog`
> with 41 consumers (5 of them in the regulated borrower flow), and adopts four new primitives across 31–60 files —
> and it is the phase with the least coverage behind it:
>
> - **There are no component tests.** `frontend` has 14 Vitest files and every one targets a pure function under
>   `src/lib/**` (`loans/segments`, `customers/customer-page`, `staff/my-stats`, `utils`, …). Nothing renders a
>   component, so no test in the repo can fail when a register's header stops sticking, a skeleton replaces a table
>   with the wrong shape, `keepPreviousData` leaves one staffer's rows under another's heading, or a focus trap swallows
>   a borrower's Accept button.
> - **CI does not run Playwright, and does not run the build.** `.github/workflows/ci.yml` gates on `npx tsc --noEmit`
>   and `npx eslint .` only; the comment at `:62-63` records that `npm run build` is deliberately skipped (the known
>   Next 15.1 prerender failure at `/staff/admin/staff`), and the e2e job at `:270` needs AWS credentials and the
>   Fintrix sandbox and is run by hand against `npm run dev`. The nine specs in `frontend/e2e/` are real but are not a
>   merge gate.
> - **"A visual diff" names no tool.** There is no Chromatic / Percy / Playwright-screenshot baseline in the repo, so
>   today that line means one engineer clicking fifteen pages and remembering what they looked like.
>
> **So Phase 1 acquires a prerequisite, Phase 1.0, and it is not optional:** before the first shared-CSS or
> shared-primitive edit, land (a) Playwright **screenshot baselines** for the 15 routes per the roles in §1, run
> locally against the seeded demo stack (`scripts/run-demo.ps1` + `scripts/seed-demo-data.ps1`, §4.6 of `CLAUDE.md`) so
> the diff is mechanical rather than remembered; (b) render tests for the four primitives that carry logic —
> `StatusBadge` (every backend enum maps to a variant, and an unknown value falls back rather than throwing),
> `Skeleton variant="table"` (column count matches), `ErrorState` (retry invokes `refetch`), `Money` (paise → `₹`
> formatting and right alignment); and (c) one render test per *shape* of consumer, not per file — a register with
> sticky columns, a dialog, a two-column detail page.
>
> **Worse than "no component tests": the harness cannot run one.** `vitest.config.ts` sets
> `environment: "node"` with `include: ["src/**/*.test.ts"]`, so a `.tsx` test is **not even collected**, and there is
> no `jsdom`/`happy-dom` and no `@testing-library/*` in `package.json`. Phase 1.0 therefore starts with *building* the
> harness (jsdom or happy-dom, `@testing-library/react` + `/user-event`, a `.tsx` glob, a `QueryClientProvider` test
> wrapper), not with writing tests. Realistically **4–6 days**, not 2–3. **Phase 1 becomes ~2–2.5 weeks.**
>
> Related and unaddressed: the reason CI has no build gate is a **stale diagnosis**. `package.json` pins
> **`next 15.1.12`** while both `CLAUDE.md` §4.5 and `ci.yml:62` blame "Next 15.1.3" for the `/staff/admin/staff`
> prerender failure, and nobody re-tested after the bump. Re-run `npm run build` on the current pin before accepting
> "environmental, not app code" — if it passes, the console gains a real gate for free, which is worth more to Phase 1
> than any single primitive.
>
> If Phase 1.0 is rejected, then Phase 1 must be split so that each shared change ships alone and is reverted
> independently — sticky header, then `.num`, then hover, then the primitives one at a time — because with
> `tsc` + ESLint as the only gate, a bundled Phase 1 has no safe rollback granularity.

### Phase 2 — per-page polish (small, page-local; ≈ 1–1.5 weeks in parallel with Phase 3)

| Page | Items (from §3) | Size |
|---|---|---|
| Dashboard | section skeletons that keep shape; "Updated n s ago"; tabular stat values; no collections queries for roles without the section (§3.1) | S |
| Live applications | `StatusBadge`; `num`; labelled ☐ column + bulk hint; "Updated n s ago" per panel; verify/reject icon buttons; closed-panel immediate fetch; dialog `size="xl"` with type floored at 11 px (§3.2) | S–M |
| Customers | Columns menu; disabled-with-tooltip mixed reject; open dialog from `latestApplicationId`; bounded-concurrency bulk actions; DPD column | S–M |
| Customer 360 | toasts on every card; per-card skeletons; corrections accordion; 40-day salary-day warning; exposure line; audit filter chips | S–M |
| Verifications | collapsible buckets; unit label; confirm on reminder/override; neutral EPFO chip; hide Retry for non-ADMIN | S |
| Loans | `num`; sticky; ✕ search; breakdown tooltip + "Settled" badge; row-seeded dialog; page reset | S |
| Collections | overdue row cue; days-to-due; "N selected on this page"; bulk progress; "Updated n s ago" | S–M |
| Collection case | error branches; toasts; PTP date logic; decimal inputs; per-card skeletons; settlements list | S–M |
| Settlements | status chips (default Proposed); confirm + reject reason; SoD pre-check; borrower/loan columns | S |
| Transactions | `num`; typed status badge; proof blank for disbursals; one pill style; in-table busy veil | S |
| Leads | min-width override; keyboard rows; inline money errors; one Save; filtered-empty copy | S |
| Telecalling | page-only select-all + confirm + summary toast; per-row spinners; status badges; "Assigned to others" | S–M |
| Performance | `keepPreviousData` (no fake zeros); error branch keeps header; `num`; two empty messages | S |
| My decisions | search + sort; linked application id; remark tooltip; URL write-back; day groups | S |
| All applications | role-gated query; memoised filter; `StatusBadge`; stage-since column; clear-filters | S |

### Phase 3 — backend and database (each item is an additive parameter, projection, batch or index; ≈ 2–3 weeks)

Ordered by verified cost. Additive for existing callers (new query parameters are optional) **except 3.3, 3.5 and
3.13, which narrow a response** — and note the 2026-10-06 review found the "single consumer named in its §3 section"
guarantee to be false for **3.13** (`createdByStaffName` has a second consumer, `/staff/admin/leads`, which renders it
and exports it). Re-grep every field before narrowing any DTO; the plan's consumer census was not exhaustive — a headline brief without `providerResponse` inside `CustomerDetail`, a light list
DTO for `GET /applications/all`, and dropping the unrendered `createdByStaffName` from the lead list — each with a
single consumer named in its §3 section.

| # | Change | Evidence | Size |
|---|---|---|---|
| 3.1 | **Stop the sidebar badge fetching the full worklist**: `GET /collections/worklist/counts` (per-bucket count + outstanding; rejects DSA) used by the six cards and the shell badge | `staff-shell.tsx:120-129` polls the unpaginated worklist every 30 s on every page | S |
| 3.2 | **Verification overview**: projections (no `raw_response` JSON), SQL tallies (`GROUP BY status`), SQL paging, server-side "not started" | `ApplicationVerificationService.java:3455-3525` | M |
| 3.3 | **Customer 360 read**: `findByLoanIdIn`, batched `latestProfile`, `findStaffByIds`, a headline `CreditBriefView` (no `providerResponse`) for the customer read, batch `activity()` | `CustomerService.java:689-769,1229-1274` | S–M |
| 3.4 | **Telecalling**: `findLatestEventAt` + projections + `?owner=&page=&size=`; `assignOwner` without two `detail()` calls | `AdminApplicationService.java:138-178`, `CustomerService.java:807-841` | M |
| 3.5 | **All applications**: `Pageable` + `q` + `complete`, `findLatestEventAt`, `namesFor`, light list DTO | `AdminApplicationService.java:65-104` | M |
| 3.6 | **Transactions ledger** as one SQL query with `LIMIT/OFFSET`, `FILTER` totals and a streaming export | `TransactionService.java:71-169` | M–L |
| 3.7 | **Loans register**: `Pageable` + `segment` + `sort`, `q` before enrichment, breakdown/settlement fields on the row, `collection_case` read once | `LoanRegisterService.java`, `RepaymentService.java:400-403` | M |
| 3.8 | **Collections worklist**: `Pageable` + bucket/q/sort in SQL, `namesFor`, de-duplicated batch reads. **The `bulk-assign` endpoint conflicts with an explicit in-code directive** — `components/staff/pipeline/bulk-actions.tsx:5-12` reads *"There is NO batch endpoint on the backend and this module must never add one — a sequential loop over the existing per-id endpoints keeps every audited SoD/ownership/event-trail guard those endpoints already enforce, for free."* The same objection applies to the batch reject/assign endpoint proposed in §3.3 §7. Either honour the directive and ship **only** the bounded-concurrency client change (§3.3, §3.7), or make the case for overturning it explicitly — a batch endpoint has to re-implement per-id SoD, ownership and event-trail guards, which is exactly the risk the comment is guarding against. Do not ship it as an unremarked performance item | `CollectionsService.java:193-226` | M |
| 3.9 | **Collection case**: reuse the resolved `LoanSummary` (`openCase`, `raise`, `assign`), skip `findLoans` for case-scoped payments, `loggedByName` on interactions, memoised `scope()` | `CollectionsService.java:89-106,365-371`, `CollectionPaymentService.java` | M |
| 3.10 | **Customers page**: `count(*) over()`, one `findStaffByIds`, pass maps into `failures()`, projection for `decidedCustomerIds`, chunked export | `CustomerService.java:292-309,227-239`, `CustomerBookQuery.java` | M |
| 3.11 | **Settlements**: enrich rows (batched case + loan reads), `?status=`, reject `remarks`, `namesFor` in `toView` | `SettlementService.java:204-248` | S–M |
| 3.12 | **Decision history**: read the trail once for list + summary, push `DECISION_ACTIONS` + `LIMIT` into SQL, `findStaffByIds`; performance page projections | `DecisionHistoryService.java:98-99,242-243` | S–M |
| 3.13 | **Leads**: `namesFor` **only — do NOT drop the field.** `createdByStaffName` is unrendered on `/staff/leads` but **is** rendered on `/staff/admin/leads` and written into its CSV export, so dropping it blanks a column and an export on another page. This also falsifies the Phase 3 preamble's claim that each narrowing has "a single consumer named in its §3 section" — 3.13's second consumer was unnamed. Slim list projection still applies | `LeadService.java:147-156,458-463` | S |
| 3.14 | **Live applications queues**: optional `page/size` on `GET /applications?status=` and `credit-queue`, `creditHeadQueue` date window in SQL, slim `QueueRowView` (see §3.2) | `ApplicationController.java:104-133`, `ApplicationFlowService.java:938-949` | M |
| 3.15 | **Dashboard aggregates**: `bookStats` as SQL aggregates over the scoped customer CTE, `scope()` once per request, `trends` as three `GROUP BY date` projections, count endpoints for hero/badges (payouts, pending repayments, worklist buckets), `listCases` scoped in SQL | `CustomerService.java:324-427`, `DashboardService.java:39-66`, `CollectionsService.java:169` | M |

**Indexes (one migration, `V76` — corrected 2026-10-06; `V74` is `application_verification_reopened` and `V75` is
`customer_profile_aadhaar_card_intake`, so the number this section originally claimed is taken. Re-check the highest
migration at implementation time, since `main` moves):**
`idx_application_event_application_at (application_id, at desc)` · `idx_loan_disbursed_on (disbursed_on)` ·
`idx_payment_paid_on (paid_on)` · `idx_settlement_status_created_at (status, created_at desc)` ·
`idx_lead_outcome (lead_outcome)` · `idx_loan_application_assigned_exec (assigned_executive_id, status)` · optional `pg_trgm` GIN indexes on
`customer_profile(lower(full_name))`, `(pan)`, `(mobile)` and `lead(lower(name))`, `(mobile)` for the contains-searches.
All are additive; historical migrations stay immutable. The `uq_collection_case_loan_id` unique index is a separate,
guarded step after de-duplication (§3.8 §8), not part of `V76`.

> **This list is not yet justified to the standard the previous investigation set, and should not ship first.**
> `docs/perf/UI_PERFORMANCE_INVESTIGATION_2026-09-16.md` §2.7 dumped the real `pg_indexes` catalog (**152 indexes**),
> corrected several static-analysis "missing index" claims that were wrong, found only four genuinely absent — all four
> shipped as `V71` — and closed with *"no speculative composite indexes beyond the four listed"* and *"no b-tree
> indexes on `LIKE '%q%'` columns"*. The six indexes above were derived by reading code, not by dumping the catalog or
> running `EXPLAIN`, so each needs one of two warrants before it goes in:
>
> - **Newly justified by code that changed since September** — `idx_payment_paid_on` and `idx_loan_disbursed_on` have a
>   real argument of this kind: `dc25b93` pushed the ledger's date window into SQL *after* §2.7 was written, and the
>   ledger applies no status predicate, so the trailing-column `idx_payment_status_paid_on` (`V71`) cannot serve it.
>   State that argument in the migration header.
> - **Confirmed absent against the catalog and shown to matter by `EXPLAIN`** on seeded data for the other four.
>
> And say the quiet part the previous doc said out loud: at today's volumes (**113 live loans**, low-hundreds payment
> rows, ~10 k customers) none of these will produce a measurable change. They are *insurance*, like `V71` was — which
> is a fine reason to add them and a bad reason to put them in a phase whose other items are measured wins. **Move the
> index migration to the end of Phase 3, not the front, and label it "no measurable effect today".** The `pg_trgm` GINs
> stay explicitly deferred: §3.3 §8 already rates them low priority at the current book, and §2.7 found search is not
> among the measured hot paths.

### Phase 4 — polling policy (½ week, after Phase 3.1)

Keep the product intervals but make them cheap: the sidebar badge and dashboard read the counts endpoints; queue polls
add `refetchIntervalInBackground: false` explicitly (already the default) and a visible "Updated n s ago"; a single `/me/badges` (unread count + bucket counts; rejects DSA like every staff-open endpoint) can later replace
the 20 s bell + 30 s badge polls with one request.

### Conflicts with the 2026-09-16 measured investigation (added 2026-10-06)

`docs/perf/UI_PERFORMANCE_INVESTIGATION_2026-09-16.md` covered substantially the same 15 pages with **live production
timings, decoded payload sizes, SQL statement counts on a seeded instance and the real `pg_indexes` catalog**, and its
§5 E is an explicit *"changes that should NOT be made"* list. Its recommendations shipped in `dc25b93` / `fa7473b` /
`V71` / `vercel.json`. This plan was written without reconciling against that list, and conflicts with it in three
places. None is necessarily fatal, but each needs an owner's decision rather than a silent override:

| This plan proposes | The measured investigation said | Resolution needed |
|---|---|---|
| **§3.14 / Phase 3.14** — optional `page/size` on `GET /applications?status=` and `credit-queue`, and §3.2 §9 argues for it ("a busy disbursement day no longer re-enriches 300 files every 8 s") | *"**No default pagination** or longer polling **on the maker-checker queues** (`KYC_PENDING`, `CREDIT_EXEC_PENDING`, `SANCTIONED`, `DISBURSEMENT_*`, repayment verification): they are small by throughput, and **completeness plus promptness are the point**."* | The word "optional" is doing a lot of work. Keep the parameter **opt-in and unused by the console's queue panels** — a reviewer must never page past a file waiting on them. If the real problem is re-enrichment cost, fix it with the slim `QueueRowView` projection (same section) and leave the row count alone. Paging a maker-checker queue is a product decision for the Credit Head, not a performance change |
| **Phase 3 index batch** — six new indexes plus `pg_trgm` GINs | *"no speculative composite indexes beyond the four listed"*; *"no b-tree indexes on `LIKE '%q%'` columns"*; 152 indexes already exist and several "missing index" claims were wrong | Handled in the note under the index list above: two have a genuine post-September warrant, the other four need `EXPLAIN`, the GINs stay deferred |
| **Nothing in this plan** addresses request latency or the BFF hop | Its **#2 finding overall**: every BFF call ran in `iad1` while the backend is in `ap-south-1`, ≈ **0.45–0.5 s of pure network per call** on pages making 10–16 calls on mount — the single largest measured win, fixed by `frontend/vercel.json` | Already fixed, so not a gap in the product — but it *is* a gap in this plan's model of "slow". Several §3 waterfalls (customers "Open" = 5 requests, the loans dialog's 2–3 hop chain, the Customer 360 first paint) cost **round trips**, and round trips are what users feel. Rank the waterfall-collapsing items (§3.3, §3.6, §3.4) above the statement-count items, which is the opposite of the current Phase 3 ordering |

### What this plan did not measure (added 2026-10-06)

Stated plainly, because the plan's own bar at the end of this section is *"never claim a page is faster without the
statement count and the payload size before/after"* — a bar it sets for the implementer and did not meet itself:

- **Every number in §3 heading 6 is derived from reading code**, not from running it. Query counts are counted by eye
  from call sites; no endpoint was timed; no payload was measured; `pg_indexes` was not dumped; `EXPLAIN` was not run.
  The one exception is where §3 cites `docs/perf`, which measured.
- **`code-review-graph` was unavailable** for the session (noted in the header), so even the static pass was manual.
- **No UI-side measurement at all** — no Core Web Vitals, no profile of the 100-row × 22-column render, no bundle
  analysis. Note that `recharts` is imported **statically** by `app/staff/performance/page.tsx` and
  `components/staff/leads-tracker.tsx`; whether that matters is unknown because nobody looked.
- **Consequence for how to read §3:** the severity labels rank items by *how bad the code looks*, which is not the same
  ordering as *what costs the operator time*. Treat Phase 3's order as a hypothesis. Before starting it, re-run the
  Appendix A/B harness from `docs/perf` against current `main` and re-order by what that shows; expect some items to
  have been absorbed by `dc25b93` already and others to be larger than estimated.
- **The UI/UX half (§2, §3 headings 1–5) does not depend on any of this.** Those findings are structural facts about
  the code — no sticky `thead` exists, money is `text-align: left`, there is no toast primitive, `ui/Dialog` has no
  focus trap, the "Not started" bucket is built from one page of rows — and they were re-verified at `0f14dab`. They
  are safe to act on now. It is specifically the **backend/DB half** that needs measurement before it is sequenced.

### Priority matrix

> **Re-ordered 2026-10-06:** the matrix below ranks by the plan's own impact estimates, which are unmeasured for every
> backend row (see above). Two concrete changes: the index batch drops to "lower impact" where `V71`'s precedent puts
> it, and the **waterfall** items (customers "Open" 5 → 1 request, the loans row-seeded dialog, Customer 360's first
> paint) rise to "high impact, low disruption" because round trips are what the operator feels and the fixes are
> page-local. Phase 0 stays first regardless — those are bugs, not optimisations.

| | Low disruption | Medium disruption |
|---|---|---|
| **High impact** | Phase 0 fixes · sticky header + `num` + row hover · Skeleton/Empty/Error/Toast · session dedupe · **waterfall collapses (§3.3 "Open", §3.6 row-seeded dialog, §3.4 first paint)** · counts endpoint for the badge (3.1) · verification projections (3.2) · customer 360 batching (3.3) | telecalling / all-applications / loans / collections server pagination (3.4, 3.5, 3.7, 3.8) · ledger SQL (3.6) |
| **Medium impact** | `StatusBadge` everywhere · confirm dialogs · Columns menu · one period control · `keepPreviousData` | bulk-assign / batch reject endpoints · settlement enrichment (3.11) |
| **Lower impact** | reduced-motion coverage · URL write-back · export timestamp formatting · **the `V76` index batch (no measurable effect at today's volumes — insurance, like `V71`)** | trigram indexes (deferred) · `/me/badges` |

### What deliberately does **not** change

Navigation and the sidebar; the one-console composition of queues; every action cluster and its SoD/role gates; the
BFF layout and JWT model; the design tokens by name; the 8 s / 60 s product polling decisions; the borrower-facing
app (apart from the shared `ui/Dialog` primitive's focus trap, scroll lock and exit fade, which reach **five**
borrower surfaces including the regulated consent screen `terms-modal.tsx` — see Phase 1 item 3).

**Five things in this plan *do* change behaviour, despite the heading (added 2026-10-06).** Each is defensible; none
should be discovered by an operator rather than agreed in advance:

1. **Registers scroll inside their panel** (§2.2 sticky header). The plan calls this out as deliberate, but it changes
   how every register is navigated — mouse wheel over the table no longer scrolls the page. Demo it to one Credit Head
   and one Collection Head before rolling it out.
2. **"Not started" stops containing files it contains today** (§3.5). Correct — they were mislabelled — but the bucket
   will visibly shrink, and a reviewer who has learned to watch that number will notice. Say so in the release note.
3. **Retry API disappears for `CREDIT_EXECUTIVE` / `CREDIT_HEAD`** (Phase 0.5). Those roles can click it today; it
   always fails, so nothing is lost functionally, but a visible control is being removed from two roles — confirm the
   direction (hide the button vs. relax the backend to the credit team) with the Credit Head rather than picking the
   "safer" one by default, because the credit team may well be the right holder of that permission.
4. **"Select all" stops meaning all pages** on telecalling (§3.12) and settlements defaults to a status view (§3.9).
   Both are improvements; both change a muscle-memory action on a page whose users send real SMS.
5. **Confirm dialogs on settlement approve/reject, verification reminders and bulk sends** add a click to actions a
   Collection Head and a Telecaller perform many times a day. Worth it for the bulk borrower-facing sends (an
   accidental 25-reminder blast is unrecoverable); re-examine it for single settlement approve/reject, where the
   SoD pre-check and an undo-by-reject already exist and the dialog may just be friction.

### Verification of the plan itself

- Frontend: `npx tsc --noEmit`, `npm run lint`, Vitest for `StatusBadge` maps and `usePagination`, Playwright smoke on
  the 15 routes per role (login as each seeded role, assert the page renders and the first action button is present).
- Backend: `./mvnw test`; for each Phase-3 item a repository test asserting the query count with the existing
  `docs/perf` harness (the customers page already has a statement-count baseline: 51 → target ≤ 20).
- Never claim a page is faster without the statement count and the payload size before/after.
- **Per `CLAUDE.md` "Verification Rules": check the exit code, never grepped output.** `npx tsc --noEmit` and
  `npx eslint .` are the only CI gates (`.github/workflows/ci.yml:58,61`); `npm run build` is deliberately not run and
  the Playwright job needs AWS credentials, so "Playwright smoke" above is a **manual** step someone has to own.
- **Honest caveat on the two frontend bullets:** as written they describe tests that do not exist. There are no
  component tests in the repo (14 Vitest files, all on `src/lib/**` pure functions) and no screenshot baseline. See the
  Phase 1.0 prerequisite under Phase 1 — without it, "Vitest for `StatusBadge`" is the *first* component test anyone
  will have written here, and the sticky-header / hover / skeleton changes have no automated check at all.
- **And the bar this section sets applies to this document too.** Nothing in §3 heading 6–8 was measured; see "What
  this plan did not measure" above. Re-run the `docs/perf` harness against current `main` before sequencing Phase 3.

---

## Appendix A — verification ledger (observer claims that were refuted)

Each page was observed by one agent and independently verified by another. The verifier re-read every cited line. Claims marked REFUTED below are **not** in §3 and should not be re-reported; PARTIAL claims were corrected before use. Counts are per page: confirmed / partial / refuted. Each entry A.n verifies the page section §3.n above.


### A.1 Dashboard — 24 / 30 / 12 (§3.1)

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

### A.2 Live applications — 23 / 33 / 11 (§3.2)

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

### A.3 Customers — 24 / 17 / 3 (§3.3)

- **Refuted:** Segment counts aggregated in CustomerSegments.counts(rows) after hydrate - frontend segments.ts:115-135, 'client-side helper used by bookStats() servi… → A Java service cannot call a TypeScript helper. bookStats uses the backend CustomerSegments.counts; the frontend segmentCounts() in segments.ts:115-135 is not used anywhere on this page - the chips read the summary endpoint. (`backend/navix-loan/src/main/java/com/navix/loan/service/CustomerServic…`)
- **Refuted:** Friction: date-group collapse state is lost on pagination/filter change (useState resets on every page, 203-210) → collapsedDates is never reset while the page is mounted: a date collapsed on page 1 stays collapsed if it recurs on page 2 or under a new filter, and groups start EXPANDED (empty set) - the opposite of 'groups re-appear collapsed'… (`frontend/src/app/staff/customers/page.tsx:203 useState<Set<string>>(ne…`)
- **Refuted:** Perf: no virtual scroll - 1000+ customers on one date all render at once → A page can never exceed 100 rows, so the premise is impossible; the absence of virtualization is true but immaterial. (`frontend/src/components/staff/pipeline/pagination.tsx:68 (25|50|100);…`)

### A.4 Customer 360 — 45 / 16 / 7 (§3.4)

- **Refuted:** tables[0] StaffFieldTable (Personal/Employment/Bank/Credit): not sortable/filterable/paginated, stickyHeader TRUE, density py-1.5 text-[10.4px] → Header is NOT sticky: .staff-data-table thead th (globals.css:421) has no position:sticky; only the .staff-sticky-identity/.staff-sticky-actions column classes are sticky (:424-425) and StaffFieldTable uses neither. Cell padding i… (`frontend/src/components/staff/customer-tabs.tsx:649-666; frontend/src/…`)
- **Refuted:** tables[1] Disbursal txn refs (Bank tab): stickyHeader TRUE, standard .staff-data-table → Same as above — no sticky header in .staff-data-table; the 3-column table also inherits min-width:84rem. (`frontend/src/components/staff/customer-tabs.tsx:579-586; frontend/src/…`)
- **Refuted:** loading: Verifications tab shows a loading spinner (unverified) → VerificationChecksPanel renders plain 'Loading…' text, no spinner. (`frontend/src/components/staff/verification-checks.tsx:167-168`)
- **Refuted:** empty: Documents tab fallback 'No application to show documents for.' (unverified) → That string does not exist. On this route DocumentsTab runs in customer mode and shows 'No documents uploaded.' (detail-parts.tsx:129); the no-id fallback text is 'No application to attach documents to.' (:84). (`frontend/src/components/staff/detail-parts.tsx:84,129; grep -rn 'No ap…`)
- **Refuted:** transition: no CSS transitions/animations on mount other than Loader2 animate-spin and the page.tsx:69 animate-pulse → CreditScoreGauge (right panel on load, and the Credit tab) animates its needle (transform 1.25s cubic-bezier) and tick opacity via CSS transitions kicked off in a useEffect on mount (credit-score-gauge.tsx:130-145, 222-258; honour… (`frontend/src/app/staff/customers/[customerId]/page.tsx:90-97; frontend…`)
- **Refuted:** friction[12] tab switching resets the left pane's scroll position to top → No scroll reset exists. The overflow-y-auto container (page.tsx:77) is not keyed or remounted on tab change — only its child swaps (:78-84) — so the browser keeps scrollTop (clamped to the new content height). If anything the oppo… (`frontend/src/app/staff/customers/[customerId]/page.tsx:76-84`)
- **Refuted:** perf[1] clicking Verifications, Bank and Credit in quick succession could fire three identical GETs → Bank and Credit share one key and only one tab body is mounted at a time (customer-tabs.tsx:112-152), and React Query dedupes in-flight fetches per key — so no triple fetch. The real duplicate is structural: the Verifications tab… (`frontend/src/components/staff/customer-tabs.tsx:112-152,189,495,600; f…`)

### A.5 Verification dashboard — 27 / 19 / 6 (§3.5)

- **Refuted:** progress(appId) — 'No separate DB query — derived from the in-memory snapshot or a lightweight query per app' → progress() runs its own queries: applicationRepo.findById (3241) + statusesOf → findByApplicationIdOrderByIdAsc (3283-3286) = 2 queries minimum, plus 2 more per re-apply hop (statusesOf(source) + findById(source), 3274-3279) up to… (`backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVer…`)
- **Refuted:** manualDecision — 'upsert application_verification + append application_event (SoD audit trail); single write per override' → No application_event is appended anywhere in the service (no eventRepository reference). Writes: upsert (findByApplicationIdAndCheckType + save, 3559-3561/3595) plus, for PENNY_DROP/EMPLOYMENT/BUREAU, a derivedFor read (2708-2712)… (`backend/navix-loan/src/main/java/com/navix/loan/service/ApplicationVer…`)
- **Refuted:** Dialog open/close: no custom CSS transitions visible → The Dialog renders `.modal-overlay.show`, which has `animation: fadeUp .25s ease` (globals.css:586, keyframes at 662) — there is an open animation; there is no close animation. (`frontend/src/components/ui/dialog.tsx:49; frontend/src/app/globals.css…`)
- **Refuted:** Override/Retry buttons do NOT show pending state with spinner — only disabled opacity → Both submit buttons render `<Loader2 className="animate-spin"/>` while isPending (Retry at 342, Confirm at 492). The report's own loadingStates section says the same, contradicting this claim. (`frontend/src/components/staff/verification-checks.tsx:342,492`)
- **Refuted:** Search + pagination: 'pagination does not respect search across pages; a reviewer paging through gets stale results' → Search is applied server-side BEFORE paging (filter 3494-3510, then group+skip/limit 3514-3520, total = rowsByApp.size() 3525), the query key includes `debounced` (102) and page resets to 1 whenever the term changes (97-99). Every… (`frontend/src/app/staff/verifications/page.tsx:97-99,102; backend/navix…`)
- **Refuted:** Typing fast (e.g. '1234') fires 4 backend queries, one per debounce window → useDebouncedValue only settles after the value has been unchanged for 300ms (setTimeout cleared on every change, 18-21). Typing four characters quickly yields ONE settled value and one overview request. Only a pause >300ms between… (`frontend/src/hooks/use-debounced-value.ts:15-24; frontend/src/app/staf…`)

### A.6 Loans register — 26 / 10 / 4 (§3.6)

- **Refuted:** date-group header count feels off by one because the header row is itself rendered → group.rows contains only loan rows (178-186); the header is a separate <tr> and is not counted. The label 'N loans' is exact; the claim concedes this and the 'feels off' is speculation. (`frontend/src/app/staff/loans/page.tsx:176-187,311-312`)
- **Refuted:** react-query caches keyed by search+range but no deduplication across users; a second user searching 'John' hits the cache → React Query's cache is per browser tab/session (makeQueryClient), never shared across users; there is no server-side cache at all. Two users always issue two requests; the claim conflates client cache with a shared one. (`frontend/src/lib/query-client.ts:9-18; frontend/src/app/staff/loans/pa…`)
- **Refuted:** QueueDateFilter supports ALL/TODAY/7D/30D/90D/CUSTOM → QueuePeriod is 'ALL' | 'TODAY' | 'YESTERDAY' | 'CUSTOM' - there are no 7D/30D/90D presets. (`frontend/src/components/staff/pipeline/queue-date-filter.tsx:16,62-85`)
- **Refuted:** customersApi.list is also called from the page (question to confirm) → page.tsx never imports or calls customersApi; line 121 is a comment saying the search/range semantics 'mirror customersApi.list'. The only page-level data call is loansApi.list. The customer table is never loaded by this page; cus… (`frontend/src/app/staff/loans/page.tsx:15,121-126 (grep: only a comment…`)

### A.7 Collections worklist — 15 / 16 / 10 (§3.7)

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

### A.8 Collection case — 21 / 20 / 10 (§3.8)

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

### A.9 Settlements — 28 / 4 / 3 (§3.9)

- **Refuted:** componentsUsed: RefreshButton from staff-ui.tsx:38 is used on this page → The page does NOT use the shared RefreshButton component (page.tsx:6 imports only PageHeader from staff-ui). It renders its own inline <button onClick={() => q.refetch()}> at page.tsx:65-70 with RefreshCw/Loader2 imported directly… (`frontend/src/app/staff/collections/settlements/page.tsx:5-6, 65-70; fr…`)
- **Refuted:** stickyHeader: 'thead not explicitly sticky but .staff-data-table may apply via globals.css' → globals.css:418-433 puts NO position:sticky on thead. The only sticky rules are the column classes .staff-sticky-identity / .staff-sticky-actions (424-431), and this page uses neither (page.tsx:84-88, 95-112). There is no sticky h… (`frontend/src/app/globals.css:418-433; frontend/src/app/staff/collectio…`)
- **Refuted:** Amount is displayed twice (table row line 106 and export column line 55) with no dedup → Nothing renders twice. The export `columns` are lazily-evaluated value() functions invoked only when an ADMIN clicks CSV/PDF (export-menu.tsx:62-77); they are not on-screen output. The only real discrepancy is formatting: on-scree… (`frontend/src/app/staff/collections/settlements/page.tsx:53-63, 106; fr…`)

### A.10 Transactions ledger — 27 / 11 / 5 (§3.10)

- **Refuted:** tables[Transactions Ledger].stickyHeader: true → The header is not sticky. The only sticky behaviour in the staff table system is horizontal pinning of identity/action COLUMNS via .staff-sticky-identity/.staff-sticky-actions, and this table uses neither. There is no vertical sti… (`frontend/src/app/globals.css:409 (.staff-table-scroll is overflow-x:au…`)
- **Refuted:** frictionPoints[1]: two repository queries mean both disbursals AND repayments are loaded for the window and filtered in memory; 'no query optimization… → The direction filter already skips the unneeded half: line 80 is `"INCOMING".equals(dir) ? List.of() : loanRepository.findAllForRegister(from, to)` and line 81 is the mirror for OUTGOING. No repository call is made for the exclude… (`backend/navix-loan/src/main/java/com/navix/loan/service/TransactionSer…`)
- **Refuted:** frictionPoints[9]: Export is ADMIN-only (export-menu.tsx 51) 'but the button is still mounted and visible in the PageHeader for all roles'; non-admins… → ADMIN-only is right, but the component returns null for every other role -- nothing is mounted or visible in the PageHeader, so there is no button for a non-admin to be confused by. The report contradicts its own evidence ('return… (`frontend/src/components/staff/export-menu.tsx:53-55 (`if (me?.role !==…`)
- **Refuted:** performanceIssues[1]: presigning happens per page (good) but 100 repayment rows with proofs = '100 S3 DescribeObject API calls'; no batching/caching → S3Presigner.presignGetObject is a local SigV4 computation -- it issues no S3 request at all (no HeadObject/DescribeObject). Cost is microseconds of CPU per URL, and only REPAYMENT rows carry a key (disbursal rows pass null at line… (`backend/navix-storage/src/main/java/com/navix/storage/service/Document…`)
- **Refuted:** performanceIssues[2]: the 60-second poll is unconditional -- 'a background tab still polls, wasting bandwidth' (no visibility API) → TanStack Query's interval refetch is gated on focusManager.isFocused() unless refetchIntervalInBackground: true is passed (default false). The page does not set it, so the 60s poll pauses while the tab is hidden and resumes on foc… (`frontend/src/app/staff/accounting/transactions/page.tsx:97-114 (refetc…`)

### A.11 Leads — 18 / 12 / 4 (§3.11)

- **Refuted:** Whole-list invalidation ['leads'] after any mutation refetches every page/pageSize variant (overly broad) → invalidateQueries({queryKey:['leads']}) marks every cached variant stale but refetches only ACTIVE queries (the one key currently observed: [debouncedQ, callStatus, page, pageSize]). Inactive page variants are merely stale and ref… (`frontend/src/app/staff/leads/page.tsx:84; TanStack invalidateQueries d…`)
- **Refuted:** Money fields accept any string; 'abc', '-500' can be submitted to the backend → They cannot be submitted: mutationFn attaches monthlySalaryPaise/loanAmountInterestedPaise only when Number(value) > 0 (page.tsx:232-235), so 'abc' (NaN) and '-500' are silently DROPPED. The actual defect is silent data loss with… (`frontend/src/app/staff/leads/page.tsx:232-235,282-295; backend/navix-l…`)
- **Refuted:** leadsApi.stats() defined but never called — missing/incomplete feature → leadsApi.stats IS consumed by the ADMIN lead register (/staff/admin/leads, queryKey ['lead-stats', ...]). The backend gates /api/leads/stats to ADMIN (LeadService:222 requireAdmin), so the telecaller page could not call it anyway.… (`frontend/src/app/staff/admin/leads/page.tsx:84-90; backend/navix-loan/…`)
- **Refuted:** Invalidation cascades: if user is on page 3, all pages refetch simultaneously → Only the active query refetches (invalidateQueries default refetchType 'active'); other page keys are marked stale, not fetched. One list request per mutation. (`frontend/src/app/staff/leads/page.tsx:84`)

### A.12 Telecalling — 14 / 13 / 3 (§3.12)

- **Refuted:** apiCalls[owner-picker-open]: GET /api/staff/creditExecutives/{role} one per role; controller 'unverified'; fires each time picker is opened, re-mounts… → Wrong path and wrong trigger. Actual call: GET /api/staff/applications/credit-executives?role=TELECALLER -> backend GET /api/applications/credit-executives (ApplicationController:144) -> StaffDirectoryAdapter.listActive -> staffUs… (`frontend/src/lib/api/applications.ts:1649-1653; backend/navix-loan/src…`)
- **Refuted:** tables.stickyHeader: 'Yes, <thead> is part of staff-data-table' → The <thead> is NOT sticky: .staff-data-table thead th (globals.css:421) sets background/colour/font only - no position:sticky/top. Only the identity (checkbox) and Actions COLUMNS are sticky horizontally (:424-425, left:0 / right:… (`frontend/src/app/globals.css:418-433`)
- **Refuted:** friction: CustomerOwnerPicker loads staff from the API every time it opens; 3 pickers could fire 3 redundant queries; re-mounts on every table re-rend… → The picker has no open state - it is rendered inline in every row - and its staff list is a shared React Query key ['staff-picker','TELECALLER'] with staleTime 60s, so N pickers = 1 request and re-renders do not remount (rows keye… (`frontend/src/components/staff/customer-owner-picker.tsx:30,41-47; fron…`)

### A.13 Staff performance — 18 / 14 / 3 (§3.13)

- **Refuted:** loadingStates: whole-page replace on q.isLoading; chart and stat cards render underneath but are invisible behind the pulse overlay; table behind the… → There is no overlay and no whole-page replace. The isLoading ternary at 215-217 swaps only the table block for an h-48 animate-pulse div. PageHeader (127-158), PeriodPicker (160-173) and all five StatCards (175-190) render normall… (`frontend/src/app/staff/performance/page.tsx:73-75,97-106,127-190,192,2…`)
- **Refuted:** table: Value moved is font-mono, right-aligned by CSS class → The cell has only className='font-mono' (284) and .staff-data-table td sets text-align: left (globals.css:420). Numeric columns on this table are all left-aligned. (`frontend/src/app/staff/performance/page.tsx:284; frontend/src/app/glob…`)
- **Refuted:** performance: useColumnFilters re-filters the row array on every keystroke in the filter popover (column-filter.tsx:62-99) → Keystrokes only touch FilterPanel's local `search` state (136) and the `visible` memo, which filters the already-computed options string list (143-147). applyExcept/filtered/optionsFor are memoised on [rows, selections, byKey] (75… (`frontend/src/components/staff/column-filter.tsx:75-99,136,143-147; fro…`)

### A.14 My decisions — 18 / 13 / 4 (§3.14)

- **Refuted:** table: stickyHeader true → No sticky header. `.staff-data-table thead th` (globals.css:421) sets colours only; the only `position: sticky` rules are the per-cell `.staff-sticky-identity` / `.staff-sticky-actions` classes (424-425), which this page never app… (`frontend/src/app/globals.css:418-433; frontend/src/app/staff/my-decisi…`)
- **Refuted:** friction: Amount column has no ₹ symbol; paiseToINR renders '500.00' → paiseToINR uses Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:0}) — 500000 paise renders as '₹5,000' (rupee sign, Indian grouping, no decimals). The column is not ambiguous between paise and r… (`frontend/src/lib/api/applications.ts:3431-3438; frontend/src/app/staff…`)
- **Refuted:** friction: Outcome column is a colour-only Badge (SANCTIONED green / REJECTED red), unreadable for red-green colour-blind staff → statusLabel() returns a plain string ('Sanctioned', 'Rejected', …) — it title-cases the enum; the page renders it as text in a bare <td>. No Badge, no colour is involved. (`frontend/src/lib/api/applications.ts:3441-3447; frontend/src/app/staff…`)
- **Refuted:** performance: Loader2 re-renders on every isFetching change causing flicker when toggling pages via PaginationBar → Paging is purely in-memory (rows.slice) and never changes q.isFetching, so the spinner does not toggle and there is no flicker — the observer's own evidence concedes this. The remaining complaint ('no visual cue when paging') is n… (`frontend/src/app/staff/my-decisions/page.tsx:139; frontend/src/compone…`)

### A.15 All applications — 28 / 13 / 4 (§3.15)

- **Refuted:** Detail dialog fires 4+ queries sequentially without batching; each waits for the previous (appQ -> eventsQ -> briefQ -> profileQ -> customersApi.get) → appQ (:195 enabled: open), eventsQ (:201 enabled: open) and profileQ (:213 enabled: open && canReview) all start on the same render when the dialog opens - three parallel requests. Only briefQ (:207) waits for appQ.data (to skip D… (`frontend/src/components/staff/application-detail-dialog.tsx:192-227`)
- **Refuted:** Info button duplicates the detail dialog's queries; no query deduplication if the user opens both on the same row → Three of the four keys are identical across both dialogs - ['staff-application', id], ['staff-profile', id], ['credit-brief', id] - so React Query serves the second dialog from cache within the 60s staleTime (the info dialog's hea… (`frontend/src/components/staff/application-info-dialog.tsx:10-12,63,68,…`)
- **Refuted:** Entire table replaced with skeleton during refetch; rows disappear when Refresh is clicked → With React Query 5.62 `isLoading` is only true while there is no data; q.refetch() keeps the rows on screen and toggles only the header spinner (`q.isFetching`, :101). The skeleton shows on first load only. (`frontend/src/app/staff/admin/all-applications/page.tsx:101,129; fronte…`)
- **Refuted:** uiStructure: main table 'with sticky thead' → No sticky positioning on thead/th anywhere in .staff-data-table; the report's tables[0].stickyHeader=false is the correct statement. (`frontend/src/app/globals.css:421; frontend/src/app/staff/admin/all-app…`)

**Totals:** 356 confirmed · 241 partially correct · 89 refuted.

---

## Appendix C — second-review findings (2026-10-06)

A second review re-read the cited code rather than the prose: **259 claims checked — 212 confirmed, 34 partial,
12 refuted, 1 unverifiable.** The §2/§3 UI diagnosis held up well; what follows is everything that did not, plus the
gaps. Corrections that change an action are already folded into the sections above; this appendix is the ledger and the
missing-scope list.

### C.1 Claims that were wrong (not merely imprecise)

| § | Claim | Reality |
|---|---|---|
| 0.5 / 3.5 | `rbac.ts` grants `verification:retry` to credit roles, so "every click ends in an error" (**High**) | **Already fixed in `696f37f`, 2026-09-28.** ADMIN holds the token alone (`rbac.ts:170`); the cited `:113`/`:120` are `document:upload` and `customer:view`. The clearest case of a claim this plan says was independently verified and was not |
| 3.4 | "The current application is deliberately not clickable in the applications list" | Mis-filed: on *this* page every application **is** clickable, because `[customerId]/page.tsx:79-85` passes no `applicationId`, so the guard at `customer-tabs.tsx:754` is never false. Where the behaviour does exist, the comment at `:752-753` says it avoids opening a **nested copy of the dialog the reader is already in** — which is exactly what the proposed fix would do. **Drop the proposal** |
| 3.14 | "Remarks live only in a `title` attribute (hover-only, invisible to keyboard and screen readers)" | Contradicts the section's own heading 1. The parsed `remark` **is** rendered as visible cell text (`:234-235`); the raw audit `notes` is what is hover-only. Real, narrower defect: a long remark sits untruncated in a `staff-cell` (`min-width: 9rem`, `white-space: nowrap`) and widens the row instead of ellipsing |
| 3.7 | "15-column table, ten sortable columns" | 17 columns for a manage role (16 without the checkbox) and **11** `SortableTh` — Customer ID and Mobile are missing from the census. Cosmetic, but it is a column-by-column inventory the plan claims to have verified |
| 3.3 | "The ⓘ quick summary is a 3-call chain" | 5–6 requests across **3 dependent waves** (`resolveQ` → `appQ`/`profileQ`/`briefQ`/`verificationQ` in parallel → `loanQ`). Understated; the proposed fix removes one wave, not two of three calls |
| 3.2 | "Queues are 17–19 columns"; "rows carry ~45 fields" | 17–18 (`app-row.tsx:179` `colSpan={onToggleSelect ? 18 : 17}`; "Journey" is a button in the Actions cell, not a column) and **40** record components on `ApplicationView` |
| 3.2 | "The dialog fires application/events/profile in parallel, then credit-brief **and documents** as a dependent hop" | Documents is **not** fetched on open — its tab body is gated on `tab === "documents"`. On open the dialog costs 3 requests then 1 |
| 3.4 | "A batched `latestProfile` overload exists at `:1519` and is unused" | It **is** used, at `CustomerService.java:541` (the customers list page). `detail()` at `:704` calls the unbatched one. The fix is right and now trivial — swap `:704` to `latestProfile(apps, profByApp)`; the map is already in scope |
| 3.6 | "Pagination does not reset on search/segment change" | Search **does** reset (`:243-246`). Segment does not, and neither does the date range — which the section never mentions |
| 3.11 | "Changing a filter on page ≥ 2 fires one wasted request" | Only the Call-status select. `SearchBar` sets `q` and `page` in one batched handler |
| 3.15 | " · no e-sign" should read " · agreement not accepted" | Also wrong — the flag comes from the **signup screen-1 terms tick** (`AdminApplicationService:97`), so that copy would misdirect staff to a different wrong screen. See the corrected 0.6 |
| 3.13 | Drop the unrendered `createdByStaffName` from the lead list DTO | **Breaks a caller:** it is rendered *and* CSV-exported on `/staff/admin/leads`. Only the `namesFor` half is safe |

### C.2 Figures presented as measured that are not

- **"Bulk Assign/Reject ≈ 300 ms each, so 100 rows ≈ 30 s"** (§3.3) — not derivable from the code and no measurement
  artifact exists in the repo. The serial structure is confirmed (`bulk-actions.tsx:91-102`); the timing is invented.
- **"~9.7 k pre-sanction applications in production per the service comment"** (§3.12) — the cited
  `ApplicationVerificationService.java:3033-3035` is the official-email-OTP method and does not say this. The figure
  traces to a round-trip count in a unit-test comment, not a production row count. (`docs/perf` *did* measure 9,658
  rows live — cite that instead.)
- **"bookStats ≈ 3 + 8 × ⌈N/100⌉ queries"** (§3.1) — the 3 is right, the 8 is a ~2× undercount (~14–18 + D per chunk);
  `docs/perf` line 183's measured 51 statements for a 25-row page is consistent with the higher figure.
- **"assign runs a second full `getCaseDetail` (~11 queries)"** (§3.8) — ≈ 8. The waste and the fix stand.
- **"Case-scoped payments resolve a full `LoanSummary` per loan"** (§3.8) — the call is **batched**
  (`LoanDirectoryAdapter:71-85`); the waste is real but fixed-size (~6 statements), not per-row.
- **"Removing `raw_response` is the single biggest byte saving in the console"** (§3.5) — credible as a **DB-transfer**
  win (captured vendor envelopes in-repo run 23 KB–192 KB each), but it is mislabelled as a payload win and it is
  cheaper than "M": `ApplicationVerificationRepository:50-70` already defines a `raw_response`-free `CaseFailureRow`
  projection, documented as existing precisely so "raw_response never leaves Postgres for a list view".
- **"`trends` loads three full entity lists"** (§3.1) — all three **are** windowed by date. Three `GROUP BY date`
  projections is still the right fix, but `idx_payment_paid_on` does not help trends; only the loan leg is unindexed.

### C.3 Scope the plan does not cover

**Front-end delivery performance — absent entirely.** Every "performance" item in 1,900+ lines is a SQL statement
count, a payload size or a poll interval. Nothing addresses what the browser downloads, parses or paints:
- `recharts ^2.15.0` is imported **statically** by `app/staff/performance/page.tsx` and
  `components/staff/leads-tracker.tsx`, and there is **not one `next/dynamic` or `React.lazy` anywhere** in
  `frontend/src`, so it lands unconditionally in those route chunks.
- `src/app/layout.tsx:16-27` loads **five Inter weights with `display: "block"`** in the **root** layout, blocking
  first text paint on all 34 staff routes — for a reason the comment attributes entirely to a marketing hero
  animation. Scoping `block` to the marketing layout and using `swap` for the console is a one-line, measurable win
  the plan never considers.
- **Virtualization is never mentioned**, though the plan itself says seven registers fetch whole and paginate in the
  browser. `usePagination` allows 100 rows, customers is 22 columns, and `globals.css:405` sets `table-layout: auto`
  on an `84rem`-minimum table — which forces a full non-incremental column-width solve on every render. For registers
  whose network cost is already tens of KB and ~50–100 ms of backend time, paint/layout is the plausible remaining
  bottleneck, and nobody has measured it.

**Accessibility — the largest defect by occurrence is unmentioned.** `--muted` `#8593A6` is **3.12:1** on white and
**2.80:1** on the zebra, both below WCAG AA's 4.5:1, and it is used as `text-muted` **649 times** in staff scope. The
whole Tailwind type scale is px-based and shifted ~20 % down (`text-xs` 9.6 px, 414 uses; `text-sm` 11.2 px, 368 uses),
so the 11 px floor in §3.2 treats one symptom of a scale-wide decision. Also unaddressed: no skip link anywhere; the
`ErrorState` spec does not add `role="alert"`, so a screen-reader user still is never told a register failed; 51
skeleton blocks with no `aria-busy`; `SortableTh` renders a bare `<button>` whose `:focus-visible` outline is clipped
by the sticky header the plan is adding; `.staff-sticky-identity` pins at `left: 0` although it sits on the 2nd or 3rd
column.

**Resilience — nothing in the plan, nothing in the repo.** No error boundaries of any kind
(`ErrorBoundary|componentDidCatch|throwOnError` = 0 hits; no `error.tsx`/`global-error.tsx`), so a render throw
white-screens the console. `refetchOnWindowFocus` is globally `false` and `refetchOnReconnect` is never set, so after a
laptop sleeps the console shows stale numbers with no cue — which the "Updated n s ago" proposal would make *look*
authoritative while being wrong. `lib/query-client.ts:5-18` is still literally a `TODO: tune defaults`.

**Measurement — the plan is unfalsifiable as written.** No RUM, no `web-vitals`/`useReportWebVitals`, no
`@vercel/analytics`, no Speed Insights, no server-side latency metrics (the actuator still exposes only
`health,info`; `docs/perf` §5-D3 remains open). Claims like "pages stop blanking out" and "the heavy pages open in a
fraction of the time" cannot be checked before or after.

**Operator evidence — none.** There is no role-by-role statement of who opens which of the 15 pages how often, no
shadowing, no ops interview, no ticket or complaint source. For a plan whose thesis is operator value, that is the
central missing input, and it is why several Phase 2 items (the Columns menu over 22 columns defaulting to all-visible,
"Updated n s ago" on ~8 pages, `rowFlash`) cannot be ranked honestly against each other.

### C.4 Proposals to cut or re-shape

- **The identifier-masking toggle (§3.2) — wrong shape.** Default-off and per-staff protects nobody by default and
  nobody can attest to it; the full values stay in the JSON and the devtools Network tab; it never mentions
  **Aadhaar** (stored in full since V75, rendered unmasked at `customer-tabs.tsx:213`, and the one identifier with a
  statutory display regime — `CLAUDE.md` §6 singles it out); and it never mentions the **CSV export**, which writes
  PAN, mobile, account and IFSC up to `EXPORT_CAP = 50 000`. Two mask implementations already disagree
  (`Masking.maskPan` keeps first-2/last-3; `loan-detail-dialog.tsx:50-54` keeps first-3/middle-4) and the plan
  proposes a third in `lib/utils.ts`. Make it a server-side policy decision scoped by role, or leave the explicit
  product decision alone.
- **The bulk-reminder "summary toast (sent / nothing pending / failed) with per-row ticks" (§3.12) — undeliverable.**
  `sendKycReminder` returns the moment it publishes `KycReminderEvent`; per-row delivery is unknowable at that point,
  so the toast would put a **false statement** on an operator surface. Report "queued", not "sent". Related: the
  reminder is **four** channels including a billable WhatsApp template (`NotificationType:44`), not three — so the
  confirm step matters more than the plan says, and the **cooldown's write point must be at publish time**, never on
  delivery success, or the cooldown itself becomes business logic depending on a delivery succeeding
  (`CLAUDE.md` §12).
- **`VERIFICATION_OVERRIDE` (0.8) needs a `DECISION_ACTIONS` decision first.** Appending it to `application_event`
  will silently move every staffer's "Total actions" on `/staff/performance` and `/staff/my-decisions`
  (`DecisionHistoryService:52,100`). Decide membership deliberately and say so in the release note.
- **Phase 4 — delete it.** Its one substantive item (the counts endpoint) is already Phase 3.1, and its other item
  (`refetchIntervalInBackground: false` "already the default") is a self-declared no-op. The real lever, per §2.3, is a
  visibility-**and**-idle backoff; make that a Phase 3 item or drop the phase.
- **§3.14's "read the trail once via `/decisions?withSummary=true`" must follow 0.1, not precede it.** §6 asserts the
  two reads cover the same actor set; §2 correctly says they do not (that disagreement *is* the bug). Merging the reads
  before the scoping fix silently changes what the stat cards show.
- **Per-staff `localStorage` state has an unpriced cost.** The Columns menu, the collapsible buckets and the search
  recents all persist per staff id. In a back office that screen-shares queues and hands work between shifts, "my
  console does not look like yours" is a real support burden — default to shared/global behaviour unless an operator
  asks otherwise.
