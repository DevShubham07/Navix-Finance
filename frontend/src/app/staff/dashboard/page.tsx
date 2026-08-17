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
} from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/live-pipeline";
import { InfoTooltip } from "@/components/ui";
import { PipelineBar } from "@/components/staff/pipeline-bar";
import { QueueTable } from "@/components/staff/pipeline/status-queue";
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
} from "@/lib/api/applications";
import { segmentCounts, SEGMENT_LABEL, SEGMENTS, type CustomerSegment } from "@/lib/customers/segments";
import { useMounted } from "@/hooks/use-mounted";
import { formatDate } from "@/lib/utils";

const REFRESH_MS = 10_000;

/** Per-role "your queue" label (+ an ⓘ explanation) and the live statuses that feed it. */
const QUEUE: Partial<Record<StaffRole, { label: string; info: string }>> = {
  CREDIT_EXECUTIVE: {
    label: "Leads to decide",
    info: "Verify the file, then accept it with a sanctioned amount and repayment date, reject it, or park it as pending. Your decision is final — it goes straight to disbursement.",
  },
  CREDIT_HEAD: {
    label: "Leads to assign",
    info: "Hand each submitted intake to an ACTIVE credit executive. You can also decide a lead yourself; either way that decision is the final one.",
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
    info: "Work overdue loans in your DPD buckets and log borrower interactions. Open a case for any collectible loan, then follow up.",
  },
  ADMIN: {
    label: "Live pipeline",
    info: "Oversight across every queue — ADMIN can act in any role.",
  },
  DEVELOPER: {
    label: "Read-only oversight",
    info: "Browse the live application pipeline and your allocated customers. Developer is read-only — no maker-checker actions.",
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
  DEVELOPER: "/staff/applications",
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
      const cases = await countOf(collectionsApi.listCases());
      const extras: QueueExtra[] = [];
      if (cases > 0) extras.push({ key: "cases", label: "Open collection cases", count: cases, href: "/staff/applications" });
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
    case "DEVELOPER":
    default:
      base = { apps: [], extras: [] };
      break;
  }

  if (staffId != null && staffId !== "") {
    const sid = Number(staffId);
    if (Number.isFinite(sid)) {
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
  }
  return base;
}

export default function StaffDashboardPage() {
  const mounted = useMounted();
  const { session } = useStaffSession();
  const role = session?.role;

  // Layer 3 — per-status pipeline counts (one call, was 10 parallel list calls).
  const stats = useQuery({
    queryKey: ["staff-dashboard-stats"],
    queryFn: () => staffApi.stats(),
    enabled: mounted && !!session,
    refetchInterval: REFRESH_MS,
  });

  // Layers 1 + 2 — the signed-in role's action queue (+ book-of-business extras).
  const queueQuery = useQuery({
    queryKey: ["staff-dashboard-queue", role, session?.id],
    queryFn: () => fetchRoleQueue(role as StaffRole, session?.id),
    enabled: mounted && !!role && !!QUEUE[role as StaffRole],
    refetchInterval: REFRESH_MS,
  });

  // 30-day activity trends (applications / disbursals / repayments).
  const trends = useQuery({
    queryKey: ["staff-dashboard-trends"],
    queryFn: () => dashboardApi.trends(30),
    enabled: mounted && !!session,
    refetchInterval: REFRESH_MS,
  });

  // Admin oversight: segment roll-up + company-wide transactions.
  const isAdmin = role === "ADMIN";
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
  // Refresh spinner (RQ v5): isLoading is first-load only — key the spinner off isFetching
  // across every dashboard query so a manual refresh gives visible feedback.
  const fetching =
    stats.isFetching ||
    queueQuery.isFetching ||
    trends.isFetching ||
    (isAdmin && (txns.isFetching || customersQ.isFetching));

  return (
    <div>
      <PageHeader
        title={`Welcome, ${session.name.split(" ")[0]}`}
        subtitle={`${STAFF_ROLE_LABELS[role]} · live operations overview`}
      >
        <button
          onClick={() => {
            stats.refetch();
            queueQuery.refetch();
            trends.refetch();
            if (isAdmin) {
              txns.refetch();
              customersQ.refetch();
            }
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {fetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Layer 1 — "Your work" hero (every role has a QUEUE entry now, incl. DEVELOPER). */}
      {queue && (
        <WorkHero
          queue={queue}
          count={headlineCount}
          items={myApps}
          extras={activeExtras}
          loading={queueQuery.isLoading}
          actingHref={actingHref}
        />
      )}

      {/* 30-day activity trends — applications, disbursals and repayments. */}
      <TrendsSection data={trends.data} loading={trends.isLoading} />

      {/* Layer 2 — Pending actions (full width; the nav already carries every destination, so the
          former "More & quick links" aside was pure duplication). */}
      <div>
        <div>
          {queue && (
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
        </div>

        {stats.isError && (
          <div className="mt-4 rounded border border-warning-100 bg-warning-50 p-4 text-xs text-warning-800">
            Couldn&apos;t load live counts — check that you&apos;re signed in to the staff console.
          </div>
        )}
      </div>

      {/* Layer 3 — Pipeline at a glance (full width) */}
      <section className="mt-8">
        <div className="mb-3 flex items-center gap-2">
          <Route size={16} className="text-navy" />
          <h2 className="mb-0 text-xl">Pipeline at a glance</h2>
          <InfoTooltip content="Live application load across the loan lifecycle. Your role's stage is highlighted; terminal (closed) loans are shown subdued." />
        </div>
        {stats.isLoading ? (
          <div className="h-24 animate-pulse rounded border border-line bg-white" />
        ) : (
          <PipelineBar stats={stats.data ?? {}} role={role} />
        )}
      </section>

      {/* Admin — customer book by segment */}
      {isAdmin && (
        <SegmentBar
          counts={segmentCounts(customersQ.data ?? [])}
          loading={customersQ.isLoading}
        />
      )}

      {/* Layer 4 — Admin transactions summary (collapsed) */}
      {isAdmin && (
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
      )}

    </div>
  );
}

/** Layer 1 — the signed-in role's actionable count + the oldest-waiting item. */
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
          <span className="inline-flex items-center gap-1.5 rounded-full bg-navy-tint px-2.5 py-1 text-xs font-semibold text-navy">
            <Clock size={12} /> Oldest waiting
          </span>
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
  const pct = lastWeek > 0 ? Math.round((delta / lastWeek) * 100) : null;
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
          {delta >= 0 ? "▲" : "▼"} {Math.abs(delta)} vs last wk{pct != null ? ` (${delta >= 0 ? "+" : ""}${pct}%)` : ""}
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
