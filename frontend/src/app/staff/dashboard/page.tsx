"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Receipt,
  RefreshCw,
  Loader2,
  ArrowDownLeft,
  ArrowUpRight,
  Clock,
  Route,
  ChevronRight,
  Users,
} from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/live-pipeline";
import { InfoTooltip } from "@/components/ui";
import { PipelineBar } from "@/components/staff/pipeline-bar";
import { QueueTable } from "@/components/staff/pipeline/status-queue";
import { PeriodPicker } from "@/components/staff/period-picker";
import { rangeFor as decisionRangeFor, type Range } from "@/lib/period";
import { useStaffSession } from "@/lib/auth/staff-session";
import { STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import {
  staffApi,
  staffReferralApi,
  featureFlagsApi,
  collectionsApi,
  customersApi,
  dashboardApi,
  paiseToINR,
  type ApplicationStatus,
  type ApplicationView,
  type TransactionView,
  type TrendPoint,
  type TrendResponse,
  type CaseView,
  type CustomerSummary,
} from "@/lib/api/applications";
import { segmentCounts, SEGMENT_LABEL, SEGMENTS, type CustomerSegment } from "@/lib/customers/segments";
import { isMine, decidedCustomerIds } from "@/lib/customers/mine";
import {
  decisionStats,
  outcomeStats,
  bookStats,
  collectionsStats,
} from "@/lib/staff/my-stats";
import { useMounted } from "@/hooks/use-mounted";
import { formatDate } from "@/lib/utils";

const REFRESH_MS = 10_000;

// ---------------------------------------------------------------------------
// Formatting helpers — a null metric is unmeasurable, never a fabricated 0.
// ---------------------------------------------------------------------------

function num(n: number | null | undefined): string {
  return n == null ? "—" : String(n);
}
function pct(n: number | null | undefined, digits = 0): string {
  return n == null ? "—" : `${(n * 100).toFixed(digits)}%`;
}
function mins(n: number | null | undefined): string {
  if (n == null) return "—";
  return n < 60 ? `${Math.round(n)}m` : `${(n / 60).toFixed(1)}h`;
}
function score(n: number | null | undefined): string {
  return n == null ? "—" : n.toFixed(0);
}

// ---------------------------------------------------------------------------
// Section composition — which sections each role's dashboard shows.
// ---------------------------------------------------------------------------

type SectionKey = "work" | "decisions" | "outcomes" | "borrowers" | "collections" | "team";

// Total Record<StaffRole, …> on purpose: adding a role to rbac.ts without listing its
// sections here is a typecheck failure, not a silently blank dashboard.
const SECTIONS: Record<StaffRole, SectionKey[]> = {
  CREDIT_EXECUTIVE: ["work", "decisions", "outcomes", "borrowers"],
  CREDIT_HEAD: ["work", "decisions", "outcomes", "borrowers", "team"],
  DISBURSEMENT_HEAD: ["work", "decisions", "borrowers"],
  ACCOUNTANT: ["work", "decisions", "borrowers"],
  COLLECTION_EXECUTIVE: ["work", "collections", "borrowers"],
  COLLECTION_HEAD: ["work", "decisions", "collections", "borrowers", "team"],
  TELECALLER: ["work", "decisions", "borrowers"],
  DSA: [],
  ADMIN: ["work", "decisions", "outcomes", "borrowers", "collections", "team"],
};

/** Per-role "your queue" label (+ an ⓘ explanation) and the live statuses that feed it. */
const QUEUE: Partial<Record<StaffRole, { label: string; info: string }>> = {
  CREDIT_EXECUTIVE: {
    label: "Leads to decide",
    info: "Verify the file, then accept it with a sanctioned amount and repayment date, reject it, or park it as pending. Your decision is final — it goes straight to disbursement.",
  },
  CREDIT_HEAD: {
    label: "Leads to assign or decide",
    info: "Hand each submitted intake to an ACTIVE credit executive, or decide it yourself. This count also includes files already out with your team, since you may decide those too — either way the decision is final.",
  },
  DISBURSEMENT_HEAD: {
    label: "Approved loans to release",
    info: "Release funds to the borrower's bank, then enter the transaction id — that activates the loan immediately. The transaction id is required; there is no second pair of eyes after you.",
  },
  ACCOUNTANT: {
    label: "Repayments to verify",
    info: "Confirm borrower repayments landed, and validate the payments collections raise. See all money movement under Accounting → all transactions.",
  },
  COLLECTION_HEAD: {
    label: "Settlements awaiting your approval",
    info: "Approve or reject the settlements collection executives propose. Separation of duties applies — you can't approve one you proposed. Work overdue loans from the collections desk.",
  },
  COLLECTION_EXECUTIVE: {
    label: "Open collection cases",
    info: "Work overdue loans assigned to you in your DPD buckets and log borrower interactions.",
  },
  ADMIN: {
    label: "Live pipeline",
    info: "Oversight across every queue — ADMIN can act in any role.",
  },
};

/**
 * Deep-link from a role to the page where it acts on its queue.
 *
 * Every role now points at /staff/applications — the single workbench that renders each role's own
 * queues (the dedicated KYC-approvals / reborrow / credit / disbursement / accounting / DPD-buckets
 * pages were all folded into it). A Collection Executive lands on the awaiting-repayment columns +
 * the DPD grid; the Head's settlements worklist is still its own page, reached from the nav.
 */
const ROLE_HREF: Partial<Record<StaffRole, string>> = {
  CREDIT_EXECUTIVE: "/staff/applications",
  CREDIT_HEAD: "/staff/applications",
  DISBURSEMENT_HEAD: "/staff/applications",
  ACCOUNTANT: "/staff/applications",
  COLLECTION_HEAD: "/staff/applications",
  COLLECTION_EXECUTIVE: "/staff/applications",
  ADMIN: "/staff/applications",
};

/** A non-application actionable source (repayments, referral payouts, settlements, cases). */
type QueueExtra = { key: string; label: string; count: number; href: string };
/** A role's full action queue: applications the role acts on + non-application actionable sources. */
type RoleQueue = { apps: ApplicationView[]; extras: QueueExtra[] };

const safe = (p: Promise<ApplicationView[]>) => p.catch(() => [] as ApplicationView[]);
const countOf = <T,>(p: Promise<T[]>): Promise<number> => p.then((r) => r.length).catch(() => 0);

/** Mirrors /staff/collections/settlements: proposed settlements awaiting approval. */
const pendingSettlementCount = () =>
  collectionsApi.listSettlements()
    .then((r) => r.filter((s) => s.status === "PROPOSED").length)
    .catch(() => 0);

/** Mirrors the accountant's repayment-verify queue on /staff/applications. */
const pendingRepaymentCount = () => countOf(staffApi.pendingRepayments());

/** Mirrors the accountant's collections-payment validation queue on /staff/applications. */
const pendingCollectionPaymentCount = () =>
  countOf(collectionsApi.listPayments("PENDING_ACCOUNTANT"));

const repaymentsExtra = (count: number): QueueExtra =>
  ({ key: "repayments", label: "Repayments to verify", count, href: "/staff/applications" });
const collectionPaymentsExtra = (count: number): QueueExtra =>
  ({ key: "collection-payments", label: "Collections payments to validate", count, href: "/staff/applications" });
const settlementsExtra = (count: number): QueueExtra =>
  ({ key: "settlements", label: "Settlements to approve", count, href: "/staff/collections/settlements" });

/**
 * The live items for a role's action queue — the union of everything the role's queue
 * page(s) actually list. Every source is individually fault-tolerant (`.catch`) so one
 * failing call can never zero the whole count.
 *
 * After the role switch, every role also gets "My customers" extras filtered by ownership.
 * // ponytail: whole-table rollup + client-side segmenting. Move to a paged indexed query when the
 * // list stops fitting one response — same change as adding server-side segment filters.
 */
async function fetchRoleQueue(role: StaffRole, staffId?: string | number): Promise<RoleQueue> {
  const sid = staffId != null && staffId !== "" && Number.isFinite(Number(staffId)) ? Number(staffId) : undefined;
  let base: RoleQueue;
  switch (role) {
    case "CREDIT_EXECUTIVE":
      base = { apps: await safe(staffApi.listByStatus("CREDIT_EXEC_PENDING")), extras: [] };
      break;
    case "CREDIT_HEAD": {
      // Everything the Head can act on: intakes to assign, plus files already out with an
      // executive (the Head may decide those too).
      const [queue, withExec] = await Promise.all([
        safe(staffApi.creditQueue()),
        safe(staffApi.listByStatus("CREDIT_EXEC_PENDING")),
      ]);
      base = { apps: [...queue, ...withExec], extras: [] };
      break;
    }
    case "DISBURSEMENT_HEAD": {
      const [pending, failed, flags] = await Promise.all([
        safe(staffApi.listByStatus("DISBURSEMENT_PENDING")),
        safe(staffApi.listByStatus("DISBURSEMENT_FAILED")),
        featureFlagsApi.get().catch(() => ({} as Record<string, boolean>)),
      ]);
      const extras: QueueExtra[] = [];
      if (flags.referral !== false) {
        const payouts = await countOf(staffReferralApi.payouts("PENDING"));
        if (payouts > 0) {
          extras.push({ key: "referral-payouts", label: "Referral payouts to settle", count: payouts, href: "/staff/disbursement/referrals" });
        }
      }
      base = { apps: [...pending, ...failed], extras };
      break;
    }
    case "ACCOUNTANT": {
      // No application queue: since V48 the Accountant has no disbursement step at all. Their work
      // is money coming back in — borrower repayments and what collections took in the field.
      const [repayments, collected] = await Promise.all([
        pendingRepaymentCount(),
        pendingCollectionPaymentCount(),
      ]);
      const extras: QueueExtra[] = [];
      if (repayments > 0) extras.push(repaymentsExtra(repayments));
      if (collected > 0) extras.push(collectionPaymentsExtra(collected));
      base = { apps: [], extras };
      break;
    }
    case "COLLECTION_HEAD": {
      const pending = await pendingSettlementCount();
      base = { apps: [], extras: pending > 0 ? [settlementsExtra(pending)] : [] };
      break;
    }
    case "COLLECTION_EXECUTIVE": {
      // Headline bug fix: this used to count every open case company-wide. An executive only acts
      // on cases assigned to them.
      // Fail CLOSED without a resolvable staff id — showing every company case is the bug, so an
      // unknown actor gets nothing. Same convention as ApplicationFlowService.byStatus (returns
      // List.of() when the executive id is missing) and CustomerService.scope().
      const allCases = sid == null ? [] : await collectionsApi.listCases().catch(() => [] as CaseView[]);
      const mine = allCases.filter((c) => c.assignedOfficerId === sid);
      const extras: QueueExtra[] = [];
      if (mine.length > 0) {
        extras.push({ key: "cases", label: "Your open collection cases", count: mine.length, href: "/staff/applications" });
      }
      base = { apps: [], extras };
      break;
    }
    case "ADMIN": {
      const [lists, repayments, settlements] = await Promise.all([
        Promise.all(
          (["KYC_PENDING", "CREDIT_EXEC_PENDING", "SANCTIONED", "DISBURSEMENT_PENDING"] as ApplicationStatus[]).map(
            (s) => safe(staffApi.listByStatus(s)),
          ),
        ),
        pendingRepaymentCount(),
        pendingSettlementCount(),
      ]);
      const extras: QueueExtra[] = [];
      if (repayments > 0) extras.push(repaymentsExtra(repayments));
      if (settlements > 0) extras.push(settlementsExtra(settlements));
      base = { apps: lists.flat(), extras };
      break;
    }
    case "TELECALLER":
    case "DSA":
    default:
      base = { apps: [], extras: [] };
      break;
  }

  if (sid != null) {
    try {
      const mine = (await customersApi.list()).filter((c) => c.ownerStaffId === sid);
      const overdue = mine.filter(
        (c) => c.loanStatus === "OVERDUE" || c.loanStatus === "IN_COLLECTIONS",
      );
      base.extras.push({
        key: "my-customers",
        label: "Customers allocated to you",
        count: mine.length,
        href: "/staff/customers?mine=1",
      });
      if (overdue.length > 0) {
        base.extras.push({
          key: "my-overdue",
          label: "Your customers now overdue",
          count: overdue.length,
          href: "/staff/customers?seg=overdue&mine=1",
        });
      }
    } catch {
      // customers list is best-effort; don't zero the pipeline queue
    }
  }
  return base;
}

export default function StaffDashboardPage() {
  const mounted = useMounted();
  const { session } = useStaffSession();
  const role = session?.role as StaffRole | undefined;
  const sid = session?.id != null ? Number(session.id) : undefined;
  const sections = role ? SECTIONS[role] : [];
  const has = (k: SectionKey) => sections.includes(k);
  const isAdmin = role === "ADMIN";

  // Reporting period for the decisions/outcomes sections — shared with /staff/my-decisions and
  // /staff/performance so the same "this month" means the same thing everywhere.
  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});
  const range: Range = React.useMemo(() => decisionRangeFor(preset, custom), [preset, custom]);

  // Layer 1+2 — the signed-in role's action queue (+ book-of-business extras).
  const queueQuery = useQuery({
    queryKey: ["staff-dashboard-queue", role, session?.id],
    queryFn: () => fetchRoleQueue(role as StaffRole, session?.id),
    enabled: mounted && !!role && has("work"),
    refetchInterval: REFRESH_MS,
  });

  // "Your decisions" + team roster — one call, server-scoped (self, or the whole team for a Head).
  const performanceQuery = useQuery({
    queryKey: ["staff-dashboard-performance", range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.performance(range.from, range.to),
    enabled: mounted && !!role && (has("decisions") || has("team")),
    refetchInterval: REFRESH_MS,
  });

  // "Your decision outcomes" — decisions in the selected period, joined against the book below.
  const windowedDecisionsQuery = useQuery({
    queryKey: ["staff-dashboard-decisions-windowed", range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.decisions(undefined, range.from, range.to),
    enabled: mounted && !!role && has("outcomes"),
    refetchInterval: REFRESH_MS,
  });

  // All-time "mine" decisions — used only to decide book membership (isMine), so the "borrowers in
  // your book" tile matches /staff/customers?mine=1 exactly (that page's ownership check is also
  // all-time, unwindowed).
  const allDecisionsQuery = useQuery({
    queryKey: ["staff-dashboard-decisions-all"],
    queryFn: () => staffApi.decisions(),
    enabled: mounted && !!role && has("borrowers"),
    refetchInterval: REFRESH_MS,
  });

  // "Your borrowers" — company list, scoped server-side for most roles already; narrowed further by
  // isMine for the roles (Head/ADMIN) whose server scope is broader.
  const myBookQuery = useQuery({
    queryKey: ["staff-dashboard-book"],
    queryFn: () => customersApi.list(),
    enabled: mounted && !!role && has("borrowers"),
    refetchInterval: REFRESH_MS,
  });
  const book: CustomerSummary[] = React.useMemo(() => {
    const rows = myBookQuery.data ?? [];
    // Fail CLOSED: without a resolvable staff id an ADMIN/Head would read the whole company book
    // under a "your borrowers" heading — the precise mislabelling this dashboard exists to remove.
    if (sid == null) return [];
    const decided = decidedCustomerIds(allDecisionsQuery.data ?? []);
    return rows.filter((c) => isMine(c, sid, decided));
  }, [myBookQuery.data, allDecisionsQuery.data, sid]);

  // Collections desk — only for the two collections roles (+ ADMIN).
  const casesQuery = useQuery({
    queryKey: ["staff-dashboard-cases"],
    queryFn: () => collectionsApi.listCases(),
    enabled: mounted && !!role && has("collections"),
    refetchInterval: REFRESH_MS,
  });
  const settlementsQuery = useQuery({
    queryKey: ["staff-dashboard-settlements"],
    queryFn: () => collectionsApi.listSettlements(),
    enabled: mounted && !!role && has("collections"),
    refetchInterval: REFRESH_MS,
  });
  const collectionPaymentsQuery = useQuery({
    queryKey: ["staff-dashboard-collection-payments"],
    queryFn: () => collectionsApi.listPayments(),
    enabled: mounted && !!role && has("collections"),
    refetchInterval: REFRESH_MS,
  });

  // Admin oversight only — company-wide pipeline / trend / segment / ledger rollups.
  const stats = useQuery({
    queryKey: ["staff-dashboard-stats"],
    queryFn: () => staffApi.stats(),
    enabled: mounted && isAdmin,
    refetchInterval: REFRESH_MS,
  });
  const trends = useQuery({
    queryKey: ["staff-dashboard-trends"],
    queryFn: () => dashboardApi.trends(30),
    enabled: mounted && isAdmin,
    refetchInterval: REFRESH_MS,
  });
  const customersQ = useQuery({
    queryKey: ["staff-dashboard-customers"],
    queryFn: () => customersApi.list(),
    enabled: mounted && isAdmin,
    refetchInterval: REFRESH_MS,
  });
  const txns = useQuery({
    queryKey: ["admin-dashboard-txns"],
    queryFn: () => staffApi.transactions(),
    enabled: mounted && isAdmin,
    refetchInterval: REFRESH_MS,
  });

  if (!mounted || !session || !role) {
    return <div className="h-64 rounded border border-line bg-white" />;
  }

  // DSA is a firewalled portal role with no dashboard permission at all (see lib/auth/rbac.ts) — the
  // backend is the real guard, but bail out early so the page doesn't half-render empty queue panels.
  if (role === "DSA") {
    return <NoAccessNotice message="DSAs use the leads and earnings pages — see the DSA menu." />;
  }

  const queue = QUEUE[role];
  const queueData = queueQuery.data ?? { apps: [], extras: [] };
  const myApps = queueData.apps;
  const extras = queueData.extras;
  const activeExtras = extras.filter((e) => e.count > 0);
  // Headline count = the union of everything the role's queue page(s) list: application
  // rows + non-application actionable sources (repayments, payouts, settlements, cases).
  const headlineCount = myApps.length + activeExtras.reduce((s, e) => s + e.count, 0);
  const actingHref = ROLE_HREF[role];

  const decisions = performanceQuery.data ? decisionStats(performanceQuery.data, range.from) : null;
  const outcomes = has("outcomes") && windowedDecisionsQuery.data
    ? outcomeStats(windowedDecisionsQuery.data, book)
    : null;
  const books = has("borrowers") ? bookStats(book, new Date()) : null;
  const collections = has("collections")
    && casesQuery.data && settlementsQuery.data && collectionPaymentsQuery.data && sid != null
    ? collectionsStats(casesQuery.data, settlementsQuery.data, collectionPaymentsQuery.data, sid)
    : null;
  const accountantRow = performanceQuery.data?.rows?.[0];

  // Refresh spinner (RQ v5): isLoading is first-load only — key the spinner off isFetching
  // across every dashboard query so a manual refresh gives visible feedback.
  const fetching =
    queueQuery.isFetching ||
    performanceQuery.isFetching ||
    windowedDecisionsQuery.isFetching ||
    allDecisionsQuery.isFetching ||
    myBookQuery.isFetching ||
    casesQuery.isFetching ||
    settlementsQuery.isFetching ||
    collectionPaymentsQuery.isFetching ||
    (isAdmin && (stats.isFetching || trends.isFetching || txns.isFetching || customersQ.isFetching));

  const refreshAll = () => {
    queueQuery.refetch();
    performanceQuery.refetch();
    windowedDecisionsQuery.refetch();
    allDecisionsQuery.refetch();
    myBookQuery.refetch();
    casesQuery.refetch();
    settlementsQuery.refetch();
    collectionPaymentsQuery.refetch();
    if (isAdmin) {
      stats.refetch();
      trends.refetch();
      txns.refetch();
      customersQ.refetch();
    }
  };

  return (
    <div>
      <PageHeader
        title={`Welcome, ${session.name.split(" ")[0]}`}
        subtitle={`${STAFF_ROLE_LABELS[role]} · your work, decisions and borrowers`}
      >
        <button
          onClick={refreshAll}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {fetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Layer 1 — "Your work" hero */}
      {has("work") && queue && (
        <WorkHero
          queue={queue}
          count={headlineCount}
          items={myApps}
          extras={activeExtras}
          loading={queueQuery.isLoading}
          actingHref={actingHref}
        />
      )}

      {has("work") && queue && (
        <section>
          <div className="mb-3 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h2 className="mb-0 text-xl">{queue.label}</h2>
              <InfoTooltip content={queue.info} />
            </div>
            <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">
              {headlineCount} pending
            </span>
          </div>

          {queueQuery.isLoading ? (
            <div className="h-40 animate-pulse rounded border border-line bg-white" />
          ) : headlineCount ? (
            <div className="space-y-3">
              {myApps.length > 0 && (
                <QueueTable apps={myApps} actions={() => null} showJourney={false} />
              )}
              {activeExtras.length > 0 && (
                <ul className="divide-y divide-grey-200 rounded border border-line bg-white">
                  {activeExtras.map((extra) => <ExtraActionRow key={extra.key} extra={extra} />)}
                </ul>
              )}
            </div>
          ) : (
            <div className="rounded border border-line bg-white p-8 text-center text-sm text-muted">
              You&apos;re all caught up — nothing in your queue.
            </div>
          )}
        </section>
      )}

      {/* Period picker governs the two decision-quality sections below. */}
      {(has("decisions") || has("outcomes")) && (
        <section className="mt-8">
          <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />
        </section>
      )}

      {/* Section 2 — Your decisions */}
      {has("decisions") && (
        <DecisionsSection
          stats={decisions}
          loading={performanceQuery.isLoading}
          error={performanceQuery.isError}
          accountantExtra={role === "ACCOUNTANT" ? accountantRow : undefined}
        />
      )}

      {/* Section 3 — Your decision outcomes */}
      {has("outcomes") && (
        <OutcomesSection
          stats={outcomes}
          loading={windowedDecisionsQuery.isLoading || myBookQuery.isLoading}
        />
      )}

      {/* Section 4 — Your borrowers */}
      {has("borrowers") && (
        <BorrowersSection stats={books} loading={myBookQuery.isLoading || allDecisionsQuery.isLoading} />
      )}

      {/* Section 5 — Collections desk */}
      {has("collections") && (
        <CollectionsSection
          stats={collections}
          isHead={role === "COLLECTION_HEAD"}
          loading={casesQuery.isLoading || settlementsQuery.isLoading || collectionPaymentsQuery.isLoading}
        />
      )}

      {/* Section 6 — Team roster (heads only) */}
      {has("team") && (
        <TeamSection rows={performanceQuery.data?.rows ?? []} loading={performanceQuery.isLoading} range={range} />
      )}

      {/* Section 7 — Admin oversight */}
      {isAdmin && (
        <>
          <TrendsSection data={trends.data} loading={trends.isLoading} />

          <section className="mt-8">
            <div className="mb-3 flex items-center gap-2">
              <Route size={16} className="text-navy" />
              <h2 className="mb-0 text-xl">Pipeline at a glance</h2>
              <InfoTooltip content="Live application load across the loan lifecycle, company-wide. Your role's stage is highlighted; terminal (closed) loans are shown subdued." />
            </div>
            {stats.isLoading ? (
              <div className="h-24 animate-pulse rounded border border-line bg-white" />
            ) : (
              <PipelineBar stats={stats.data ?? {}} role={role} />
            )}
          </section>

          <SegmentBar
            counts={segmentCounts(customersQ.data ?? [])}
            loading={customersQ.isLoading}
          />

          <details className="group mt-8 rounded border border-line bg-white shadow-sm">
            {/* No interactive children inside <summary> — it is itself a disclosure control. */}
            <summary className="flex cursor-pointer items-center gap-2 px-5 py-4 [&::-webkit-details-marker]:hidden">
              <ChevronRight size={15} className="text-navy transition-transform group-open:rotate-90" />
              <Receipt size={16} className="text-navy" />
              <h2 className="mb-0 text-lg">Transactions</h2>
            </summary>
            <div className="border-t border-line p-5">
              <p className="mb-3 flex items-center gap-1.5 text-xs text-muted">
                Company-wide money movement — disbursals out and repayments in.
                <InfoTooltip content="Admin oversight; the full searchable ledger lives under Administration → Transactions." />
              </p>
              <AdminTransactions rows={txns.data ?? []} loading={txns.isLoading} />
            </div>
          </details>
        </>
      )}
    </div>
  );
}

/** Layer 1 — the signed-in role's actionable count + the oldest-waiting item + queue aging. */
function WorkHero({
  queue,
  count,
  items,
  extras,
  loading,
  actingHref,
}: {
  queue: { label: string; info: string };
  count: number;
  items: ApplicationView[];
  extras: QueueExtra[];
  loading: boolean;
  actingHref?: string;
}) {
  // Oldest-waiting: the application with the earliest created_at (V53) — falls back to id when
  // createdAt is somehow absent, since id is still monotonic. Deliberately ascending (oldest
  // first), unlike the staff queue tables, which sort newest-first — this hero exists specifically
  // to surface the file that's been waiting longest. Operates on applications only —
  // non-application sources (extras) have no created_at.
  const oldest = items.length
    ? [...items].sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? "") || a.id - b.id)[0]
    : null;

  // Queue aging: hours since the oldest item entered its CURRENT stage, plus how many items have
  // been sitting more than a day / two days. currentStageEnteredAt resets on every transition, so
  // this measures time-in-stage, not time-since-signup.
  const now = Date.now();
  const stageAgeHours = (a: ApplicationView) => {
    const at = a.currentStageEnteredAt ?? a.createdAt;
    if (!at) return null;
    return (now - new Date(at).getTime()) / 3_600_000;
  };
  const oldestAgeHours = oldest ? stageAgeHours(oldest) : null;
  const olderThan24h = items.filter((a) => (stageAgeHours(a) ?? 0) > 24).length;
  const olderThan48h = items.filter((a) => (stageAgeHours(a) ?? 0) > 48).length;

  return (
    <section className="mb-8 rounded-lg border border-gold-soft bg-white p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="mb-0 text-lg">Your work</h2>
            <InfoTooltip content={queue.info} />
          </div>
          <p className="mt-1 text-sm text-muted">{queue.label}</p>
          <div className="mt-3 flex items-baseline gap-2">
            {loading ? (
              <span className="inline-block h-9 w-12 animate-pulse rounded bg-grey-100" />
            ) : (
              <span className="font-serif text-4xl font-bold text-navy lg:text-5xl">{count}</span>
            )}
            <span className="text-sm text-muted">
              {count === 1 ? "item needs" : "items need"} your action
            </span>
          </div>
        </div>
        {actingHref && (
          <Link href={actingHref} className="btn btn-sm btn-navy">
            Open queue <ArrowRight size={15} />
          </Link>
        )}
      </div>

      {loading ? (
        <div className="mt-5 h-16 animate-pulse rounded border border-line bg-grey-50" />
      ) : oldest ? (
        <div className="mt-5 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-navy-tint px-2.5 py-1 text-xs font-semibold text-navy">
              <Clock size={12} /> Oldest waiting{oldestAgeHours != null ? ` · ${mins(oldestAgeHours * 60)} in stage` : ""}
            </span>
            {(olderThan24h > 0 || olderThan48h > 0) && (
              <span className="text-xs text-muted">
                {olderThan24h} item{olderThan24h === 1 ? "" : "s"} over 24h waiting
                {olderThan48h > 0 ? ` · ${olderThan48h} over 48h` : ""}
              </span>
            )}
          </div>
          <QueueTable apps={[oldest]} actions={() => null} showJourney={false} />
        </div>
      ) : count > 0 ? (
        // No applications, but non-application work is waiting (repayments / payouts / settlements / cases).
        <div className="mt-5 flex flex-wrap items-center gap-3 rounded border border-line bg-grey-50 p-4">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-navy-tint px-2.5 py-1 text-xs font-semibold text-navy">
            <Clock size={12} /> Waiting on you
          </span>
          <span className="min-w-0 text-sm text-muted">
            {extras.map((e) => `${e.count} ${e.label.toLowerCase()}`).join(" · ")}
          </span>
          {actingHref && (
            <Link href={actingHref} className="btn btn-sm btn-ghost ml-auto">
              Open queue <ArrowRight size={14} />
            </Link>
          )}
        </div>
      ) : (
        <p className="mt-4 rounded border border-line bg-grey-50 p-4 text-sm text-muted">
          You&apos;re all caught up — nothing waiting on you right now.
        </p>
      )}
    </section>
  );
}

/** Section 2 — Your decisions, off decisionStats(). */
function DecisionsSection({
  stats,
  loading,
  error,
  accountantExtra,
}: {
  stats: ReturnType<typeof decisionStats> | null;
  loading: boolean;
  error: boolean;
  accountantExtra?: { verifiedCount: number | null; verifiedPaise: number | null; rejectedPaymentCount: number | null };
}) {
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="mb-0 text-xl">Your decisions</h2>
          <InfoTooltip content="Everything you decided in the selected period. 'In queue now' is a live snapshot — it does not change with the period picker. A dash means the metric can't be measured, never a real zero." />
        </div>
        <Link href="/staff/my-decisions" className="inline-flex items-center gap-1 text-sm font-semibold text-navy hover:underline">
          Full history <ArrowRight size={14} />
        </Link>
      </div>
      {error && <p className="mb-3 text-sm text-error-700">Couldn&apos;t load your decision totals.</p>}
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="Decided" value={num(stats?.decided)} />
            <StatCard
              label="Approved / rejected"
              value={`${num(stats?.approved)} / ${num(stats?.rejected)}`}
              hint={`Approval rate ${pct(stats?.approvalRate)}`}
              accent="success"
            />
            <StatCard
              label="In queue now"
              value={num(stats?.pendingNow)}
              accent="gold"
              info="Files sitting with you right now — a live snapshot, not scoped to the period picker."
            />
            <StatCard label="Avg turnaround" value={mins(stats?.avgTurnaroundMinutes)} info="Mean time from a file being assigned to you until you acted on it." />
            <StatCard label="Value moved" value={paiseToINR(stats?.valuePaise ?? null)} />
            <StatCard
              label="Active days"
              value={num(stats?.activeDays)}
              hint={stats?.actionsPerActiveDay != null ? `${stats.actionsPerActiveDay.toFixed(1)} actions/day` : undefined}
            />
            <StatCard
              label="Busiest day"
              value={stats?.busiestDay ? `${formatDate(stats.busiestDay.date)}` : "—"}
              hint={stats?.busiestDay ? `${stats.busiestDay.actions} actions` : undefined}
            />
            <StatCard
              label="Working window"
              value={stats?.firstActionAt ? formatDate(stats.firstActionAt) : "—"}
              hint={stats?.lastActionAt ? `through ${formatDate(stats.lastActionAt)}` : undefined}
            />
            {stats?.callsTracked && (
              <StatCard label="Calls made" value={num(stats.callsMade)} />
            )}
            {accountantExtra && (
              <>
                <StatCard
                  label="Repayments verified"
                  value={num(accountantExtra.verifiedCount)}
                  hint={accountantExtra.verifiedPaise != null ? paiseToINR(accountantExtra.verifiedPaise) : undefined}
                  accent="success"
                />
                <StatCard label="Repayments rejected" value={num(accountantExtra.rejectedPaymentCount)} accent="error" />
              </>
            )}
          </div>
          {stats && stats.daily.length > 0 && (
            <div className="mt-4 rounded border border-line bg-white p-4 shadow-sm">
              <span className="text-xs font-semibold uppercase tracking-wide text-muted">Activity</span>
              <Sparkline values={stats.daily.map((d) => d.actions)} color="#0C2540" />
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** Section 3 — Your decision outcomes, off outcomeStats(). */
function OutcomesSection({ stats, loading }: { stats: ReturnType<typeof outcomeStats> | null; loading: boolean }) {
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <h2 className="mb-0 text-xl">Your decision outcomes</h2>
        <InfoTooltip content="What happened to the borrowers you approved in the selected period — the truest read on decision quality. A dash means there's nothing to measure yet, never a real zero." />
      </div>
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : !stats || stats.approvedCount === 0 ? (
        <div className="rounded border border-line bg-white p-6 text-center text-sm text-muted">
          No approvals in this period yet.
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="Now overdue"
            value={num(stats.nowOverdue)}
            hint={`${pct(stats.overdueRate)} of your approvals`}
            accent={stats.nowOverdue > 0 ? "error" : "navy"}
          />
          <StatCard label="Repaid clean" value={num(stats.repaidClean)} accent="success" />
          <StatCard label="Still live" value={num(stats.stillLive)} />
          <StatCard
            label="Your PAR"
            value={pct(stats.parPct)}
            hint={`${paiseToINR(stats.overdueOutstandingPaise)} overdue / ${paiseToINR(stats.liveOutstandingPaise)} live`}
            accent={stats.parPct != null && stats.parPct > 0 ? "error" : "navy"}
          />
          <StatCard label="Avg sanctioned" value={stats.avgSanctionedPaise != null ? paiseToINR(stats.avgSanctionedPaise) : "—"} />
          <StatCard
            label="Avg bureau score"
            value={`${score(stats.avgScoreApproved)} approved`}
            hint={`vs ${score(stats.avgScoreRejected)} rejected`}
          />
          <StatCard
            label="★ mix (approved)"
            value={([5, 4, 3, 2, 1] as const).map((s) => `${s}★ ${stats.starMix[s]}`).join(" · ")}
          />
        </div>
      )}
    </section>
  );
}

/** Section 4 — Your borrowers, off bookStats(). */
function BorrowersSection({ stats, loading }: { stats: ReturnType<typeof bookStats> | null; loading: boolean }) {
  const href = (seg?: CustomerSegment) => `/staff/customers?mine=1${seg ? `&seg=${seg}` : ""}`;
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Users size={16} className="text-navy" />
          <h2 className="mb-0 text-xl">Your borrowers</h2>
          <InfoTooltip content="Every customer allocated to you or that you've decided on — your book, not the company's. Tiles link straight into the matching filter on Customers." />
        </div>
        <Link href={href()} className="inline-flex items-center gap-1 text-sm font-semibold text-navy hover:underline">
          Open book <ArrowRight size={14} />
        </Link>
      </div>
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : !stats || stats.total === 0 ? (
        <div className="rounded border border-line bg-white p-6 text-center text-sm text-muted">
          Nothing allocated to you yet.
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Link href={href()} className="block">
            <StatCard label="Borrowers in your book" value={stats.total} />
          </Link>
          <Link href={href("active")} className="block">
            <StatCard label="Live" value={stats.counts.active} />
          </Link>
          <Link href={href("overdue")} className="block">
            <StatCard label="Overdue" value={stats.counts.overdue} accent={stats.counts.overdue > 0 ? "error" : "navy"} />
          </Link>
          <Link href={href("closed")} className="block">
            <StatCard label="Closed" value={stats.counts.closed} />
          </Link>
          <StatCard label="Outstanding" value={paiseToINR(stats.outstandingPaise)} />
          <StatCard label="At risk" value={paiseToINR(stats.atRiskPaise)} accent={stats.atRiskPaise > 0 ? "error" : "navy"} />
          <StatCard
            label="DPD split"
            value={`${stats.dpd.d1to30} / ${stats.dpd.d31to60} / ${stats.dpd.d60plus}`}
            hint="1-30 / 31-60 / 60+ days"
          />
          <StatCard label="Due next 7 days" value={stats.dueNext7Days} accent="gold" />
          <StatCard label="Avg ticket" value={stats.avgTicketPaise != null ? paiseToINR(stats.avgTicketPaise) : "—"} />
          <StatCard label="Largest exposure" value={paiseToINR(stats.largestExposurePaise)} />
          <StatCard label="Concentration" value={pct(stats.concentrationPct)} info="Largest single exposure as a share of your total outstanding." />
          <StatCard label="Repeat borrowers" value={stats.repeatBorrowers} />
          <StatCard label="Avg credit score" value={score(stats.avgCreditScore)} />
          <StatCard label="Thin file" value={stats.thinFile} />
          <Link href={href("incomplete")} className="block">
            <StatCard label="To chase" value={stats.toChase} info="Abandoned mid-onboarding (DRAFT) — nobody can act on these until the borrower comes back." />
          </Link>
        </div>
      )}
    </section>
  );
}

/** Section 5 — Collections desk, off collectionsStats(). */
function CollectionsSection({
  stats,
  isHead,
  loading,
}: {
  stats: ReturnType<typeof collectionsStats> | null;
  isHead: boolean;
  loading: boolean;
}) {
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <h2 className="mb-0 text-xl">Collections desk</h2>
        <InfoTooltip content="Your assigned cases, what you've recovered, and your settlement activity. Recovery rate is recovered ÷ outstanding across your cases." />
      </div>
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : !stats ? (
        <div className="rounded border border-line bg-white p-6 text-center text-sm text-muted">
          No collections data yet.
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="Open cases" value={stats.myCases.length} />
          <StatCard
            label="By DPD bucket"
            value={(Object.keys(stats.byBucket) as (keyof typeof stats.byBucket)[])
              .filter((b) => stats.byBucket[b].length > 0)
              .map((b) => `${b}: ${stats.byBucket[b].length}`)
              .join(" · ") || "—"}
          />
          <StatCard label="Outstanding (your cases)" value={paiseToINR(stats.myCasesOutstandingPaise)} accent="error" />
          <StatCard label="Recovered by you" value={paiseToINR(stats.recoveredPaise)} accent="success" />
          <StatCard label="Recovery rate" value={pct(stats.recoveryRate)} />
          <StatCard label="Awaiting validation" value={stats.awaitingValidation} accent="gold" />
          <StatCard
            label="Settlements proposed"
            value={num(stats.settlementsProposed.proposed)}
            hint={`${stats.settlementsProposed.approved} approved · ${stats.settlementsProposed.rejected} rejected`}
          />
          {isHead && (
            <>
              <StatCard label="Settlements you approved" value={stats.settlementsApproved} accent="success" />
              <StatCard label="Conceded" value={paiseToINR(stats.concededPaise)} />
            </>
          )}
        </div>
      )}
    </section>
  );
}

/** Section 6 — Team roster (heads only) — costs zero extra calls: summary.rows IS the team. */
function TeamSection({
  rows,
  loading,
  range,
}: {
  rows: import("@/lib/api/applications").StaffPerformanceRow[];
  loading: boolean;
  range: Range;
}) {
  const qs = new URLSearchParams();
  if (range.from) qs.set("from", range.from);
  if (range.to) qs.set("to", range.to);
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <h2 className="mb-0 text-xl">Team</h2>
        <InfoTooltip content="Everyone reporting to you in the selected period. Click a row to see their full decision history." />
      </div>
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : rows.length === 0 ? (
        <div className="rounded border border-line bg-white p-6 text-center text-sm text-muted">
          No team data yet.
        </div>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Role</th>
                <th className="text-right">Actions</th>
                <th className="text-right">Approval rate</th>
                <th className="text-right">Avg turnaround</th>
                <th className="text-right">In queue now</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const approvalRate = r.accepted + r.rejected > 0 ? r.accepted / (r.accepted + r.rejected) : null;
                const rowQs = new URLSearchParams(qs);
                rowQs.set("staffId", String(r.staffId));
                return (
                  <tr key={r.staffId} className="hover:bg-grey-50">
                    <td className="staff-cell">
                      <Link href={`/staff/my-decisions?${rowQs.toString()}`} className="font-semibold text-navy hover:underline">
                        {r.staffName}
                      </Link>
                    </td>
                    <td className="text-muted">{r.role}</td>
                    <td className="text-right">{r.totalActions}</td>
                    <td className="text-right">{pct(approvalRate)}</td>
                    <td className="text-right">{mins(r.avgTurnaroundMinutes)}</td>
                    <td className="text-right">{r.pendingNow}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Admin customer-book segment strip — totals match the customers page "All" count. */
function SegmentBar({
  counts,
  loading,
}: {
  counts: ReturnType<typeof segmentCounts>;
  loading: boolean;
}) {
  const chips: CustomerSegment[] = ["all", ...SEGMENTS];
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <h2 className="mb-0 text-xl">Customers by segment</h2>
        <InfoTooltip content="Client-side roll-up of every customer into lifecycle segments. Unallocated is tinted when the backlog is non-zero." />
      </div>
      {loading ? (
        <div className="h-24 animate-pulse rounded border border-line bg-white" />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {chips.map((seg) => {
            const href =
              seg === "all" ? "/staff/customers" : `/staff/customers?seg=${seg}`;
            const amber = seg === "unallocated" && counts.unallocated > 0;
            return (
              <Link key={seg} href={href} className="block transition hover:opacity-90">
                <StatCard
                  label={SEGMENT_LABEL[seg]}
                  value={counts[seg]}
                  accent={amber ? "gold" : seg === "overdue" && counts.overdue > 0 ? "error" : "navy"}
                />
              </Link>
            );
          })}
        </div>
      )}
    </section>
  );
}

/** Layer 2 row for a non-application actionable source (repayments / payouts / settlements / cases). */
function ExtraActionRow({ extra }: { extra: QueueExtra }) {
  return (
    <li className="transition hover:bg-grey-100">
      <Link
        href={extra.href}
        aria-label={`${extra.count} ${extra.label} — open queue`}
        className="flex items-center gap-4 px-4 py-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-navy focus-visible:ring-inset"
      >
        <span className="grid h-10 w-10 flex-shrink-0 place-items-center rounded-full bg-gold-50 font-serif text-sm font-bold text-gold-dark">
          {extra.count}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold text-ink">{extra.label}</span>
          <span className="block text-xs text-muted">
            {extra.count} {extra.count === 1 ? "item awaiting" : "items awaiting"} your action
          </span>
        </span>
        <span className="flex flex-shrink-0 items-center gap-1 text-xs font-semibold text-navy">
          Open queue <ArrowRight size={13} />
        </span>
      </Link>
    </li>
  );
}

/** 30-day activity trends — applications, disbursals and repayments per day with week-over-week deltas. */
function TrendsSection({ data, loading }: { data?: TrendResponse; loading: boolean }) {
  if (loading) {
    return <div className="mb-8 h-32 animate-pulse rounded border border-line bg-white" />;
  }
  if (!data || data.points.length === 0) return null;
  return (
    <div className="mb-8 grid gap-4 sm:grid-cols-3">
      <TrendCard
        title="Applications"
        color="#0C2540"
        points={data.points}
        pick={(p) => p.applications}
        thisWeek={data.applicationsThisWeek}
        lastWeek={data.applicationsLastWeek}
      />
      <TrendCard
        title="Disbursals"
        color="#14A06B"
        points={data.points}
        pick={(p) => p.disbursed}
        thisWeek={data.disbursedThisWeek}
        lastWeek={data.disbursedLastWeek}
      />
      <TrendCard
        title="Repayments"
        color="#2E9E6B"
        points={data.points}
        pick={(p) => p.repaid}
        thisWeek={data.repaidThisWeek}
        lastWeek={data.repaidLastWeek}
      />
    </div>
  );
}

function TrendCard({
  title,
  color,
  points,
  pick,
  thisWeek,
  lastWeek,
}: {
  title: string;
  color: string;
  points: TrendPoint[];
  pick: (p: TrendPoint) => number;
  thisWeek: number;
  lastWeek: number;
}) {
  const values = points.map(pick);
  const total = values.reduce((s, v) => s + v, 0);
  const delta = thisWeek - lastWeek;
  const pctDelta = lastWeek > 0 ? Math.round((delta / lastWeek) * 100) : null;
  return (
    <div className="rounded border border-line bg-white p-4 shadow-sm">
      <div className="flex items-baseline justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-muted">{title}</span>
        <span className="font-serif text-lg font-bold text-navy">{total}</span>
      </div>
      <Sparkline values={values} color={color} />
      <div className="mt-1 flex items-center justify-between text-[8.8px] text-muted">
        <span>Last 30 days</span>
        <span className={delta > 0 ? "text-success-700" : delta < 0 ? "text-error-700" : ""}>
          {delta >= 0 ? "▲" : "▼"} {Math.abs(delta)} vs last wk{pctDelta != null ? ` (${delta >= 0 ? "+" : ""}${pctDelta}%)` : ""}
        </span>
      </div>
    </div>
  );
}

/** Minimal inline SVG sparkline — a filled area under a smoothed polyline. */
function Sparkline({ values, color }: { values: number[]; color: string }) {
  const w = 240;
  const h = 40;
  const max = Math.max(1, ...values);
  const n = values.length;
  const pts = values.map((v, i) => {
    const x = n <= 1 ? 0 : (i / (n - 1)) * w;
    const y = h - (v / max) * (h - 4) - 2;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const line = pts.join(" ");
  const area = `0,${h} ${line} ${w},${h}`;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="mt-2 h-10 w-full" role="img" aria-label={`${values.length}-day trend`}>
      <polygon points={area} fill={color} opacity={0.1} />
      <polyline points={line} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/** Admin-only: company-wide money-movement summary + the latest transactions, with a link to the ledger. */
function AdminTransactions({ rows, loading }: { rows: TransactionView[]; loading: boolean }) {
  const totalIn = rows.filter((r) => r.direction === "INCOMING").reduce((s, r) => s + r.amountPaise, 0);
  const totalOut = rows.filter((r) => r.direction === "OUTGOING").reduce((s, r) => s + r.amountPaise, 0);
  const latest = rows.slice(0, 5);

  return (
    <div>
      <div className="mb-4 flex items-center justify-end">
        <Link href="/staff/accounting/transactions" className="inline-flex items-center gap-1 text-sm font-semibold text-navy hover:underline">
          View all <ArrowRight size={14} />
        </Link>
      </div>

      <div className="mb-4 grid grid-cols-2 gap-4 sm:max-w-md">
        <div className="rounded border border-success-100 bg-white p-4 shadow-sm">
          <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowDownLeft size={14} className="text-success-600" /> Incoming</div>
          <div className="mt-1 font-serif text-xl font-bold text-navy">{paiseToINR(totalIn)}</div>
        </div>
        <div className="rounded border border-line bg-white p-4 shadow-sm">
          <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowUpRight size={14} className="text-navy" /> Outgoing</div>
          <div className="mt-1 font-serif text-xl font-bold text-navy">{paiseToINR(totalOut)}</div>
        </div>
      </div>

      {loading ? (
        <div className="h-20 animate-pulse rounded bg-grey-100" />
      ) : latest.length === 0 ? (
        <p className="text-sm text-muted">No transactions yet.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {latest.map((t) => {
            const incoming = t.direction === "INCOMING";
            return (
              <li key={t.id} className="flex items-center justify-between gap-3 py-2">
                <span className="min-w-0">
                  <span className="text-ink">{t.borrowerName ?? "—"}</span>
                  <span className="block text-xs text-muted">
                    {t.type === "REPAYMENT" ? "Repayment" : "Disbursal"}{t.loanId != null ? ` · loan #${t.loanId}` : ""}{t.date ? ` · ${formatDate(t.date)}` : ""}
                  </span>
                </span>
                <span className={`flex-shrink-0 font-semibold ${incoming ? "text-success-700" : "text-ink"}`}>
                  {incoming ? "+" : "−"}{paiseToINR(t.amountPaise)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
