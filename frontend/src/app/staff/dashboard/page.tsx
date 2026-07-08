"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  ShieldCheck,
  ClipboardList,
  Banknote,
  Receipt,
  PhoneCall,
  RefreshCw,
  Loader2,
  ArrowDownLeft,
  ArrowUpRight,
  Clock,
  Route,
  ChevronRight,
} from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { InfoTooltip } from "@/components/ui";
import { ApplicationJourney } from "@/components/staff/application-journey";
import { PipelineBar } from "@/components/staff/pipeline-bar";
import { useStaffSession } from "@/lib/auth/staff-session";
import { STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import {
  staffApi,
  staffReferralApi,
  featureFlagsApi,
  collectionsApi,
  dashboardApi,
  paiseToINR,
  statusLabel,
  type ApplicationStatus,
  type ApplicationView,
  type TransactionView,
  type TrendPoint,
  type TrendResponse,
} from "@/lib/api/applications";
import { useMounted } from "@/hooks/use-mounted";
import { formatDate } from "@/lib/utils";

const REFRESH_MS = 10_000;

/** Per-role "your queue" label (+ an ⓘ explanation) and the live statuses that feed it. */
const QUEUE: Partial<Record<StaffRole, { label: string; info: string }>> = {
  KYC_APPROVER: {
    label: "KYC clearances & reborrow reviews",
    info: "Review fresh customers' KYC, plus returning borrowers flagged for a past overdue. Approve to advance, or reject to send it back.",
  },
  CREDIT_EXECUTIVE: {
    label: "Applications to review",
    info: "Assess income, employment and risk, then recommend or reject. Your recommendation goes to the Credit Head for final approval.",
  },
  CREDIT_HEAD: {
    label: "Decisions awaiting your approval",
    info: "Assign an executive, then give final approval. You cannot approve an application you recommended as executive (separation of duties).",
  },
  DISBURSEMENT_HEAD: {
    label: "Approved loans to release",
    info: "Release funds to the borrower's bank. Enter a transaction id to activate the loan immediately, or approve without one to send it to the accountant.",
  },
  ACCOUNTANT: {
    label: "Transfers to confirm",
    info: "Confirm the bank transfer landed (activates the loan) and verify borrower repayments. See all money movement under Accounting → all transactions.",
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
};

/** Deep-link from a role to the page where it acts on its queue. */
const ROLE_HREF: Partial<Record<StaffRole, string>> = {
  KYC_APPROVER: "/staff/kyc-approvals",
  CREDIT_EXECUTIVE: "/staff/credit/queue",
  CREDIT_HEAD: "/staff/credit/queue",
  DISBURSEMENT_HEAD: "/staff/disbursement",
  ACCOUNTANT: "/staff/accounting",
  COLLECTION_HEAD: "/staff/collections/settlements",
  COLLECTION_EXECUTIVE: "/staff/collections/buckets",
  ADMIN: "/staff/applications",
};

/** Fallback "your area" card for roles with no pipeline action queue. */
const FALLBACK_AREA: Partial<Record<StaffRole, { href: string; label: string; cta: string }>> = {
  DEVELOPER: {
    href: "/staff/applications",
    label: "Read-only oversight of the live application pipeline.",
    cta: "Open application queues",
  },
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

/** Mirrors the /staff/accounting repayment-verify queue. */
const pendingRepaymentCount = () => countOf(staffApi.pendingRepayments());

const repaymentsExtra = (count: number): QueueExtra =>
  ({ key: "repayments", label: "Repayments to verify", count, href: "/staff/accounting" });
const settlementsExtra = (count: number): QueueExtra =>
  ({ key: "settlements", label: "Settlements to approve", count, href: "/staff/collections/settlements" });

/**
 * The live items for a role's action queue — the union of everything the role's queue
 * page(s) actually list. Every source is individually fault-tolerant (`.catch`) so one
 * failing call can never zero the whole count.
 */
async function fetchRoleQueue(role: StaffRole): Promise<RoleQueue> {
  switch (role) {
    case "KYC_APPROVER": {
      // Mirrors /staff/kyc-approvals: KYC clearances + reborrow reviews + the instant-loan
      // credit fast-path (KYC-approved applications the borrower has already applied on).
      const [kyc, review, approved] = await Promise.all([
        safe(staffApi.listByStatus("KYC_PENDING")),
        safe(staffApi.listByStatus("REVIEW_PENDING")),
        safe(staffApi.listByStatus("KYC_APPROVED")),
      ]);
      const instant = approved.filter((a) => a.amountRequestedPaise != null);
      return { apps: [...kyc, ...review, ...instant], extras: [] };
    }
    case "CREDIT_EXECUTIVE":
      return { apps: await safe(staffApi.listByStatus("CREDIT_EXEC_PENDING")), extras: [] };
    case "CREDIT_HEAD": {
      const [queue, headPending] = await Promise.all([
        safe(staffApi.creditQueue()),
        safe(staffApi.listByStatus("CREDIT_HEAD_PENDING")),
      ]);
      return { apps: [...queue, ...headPending], extras: [] };
    }
    case "DISBURSEMENT_HEAD": {
      // Referral payouts — gated on the referral feature flag exactly as /staff/disbursement/referrals
      // gates it (feature is on unless the flag is explicitly false); skip the read when off. The flag
      // fetch runs alongside the app lists; only the payout count depends on it.
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
      return { apps: [...pending, ...failed], extras };
    }
    case "ACCOUNTANT": {
      const [apps, repayments] = await Promise.all([
        safe(staffApi.listByStatus("ACCOUNTANT_PENDING")),
        pendingRepaymentCount(),
      ]);
      return { apps, extras: repayments > 0 ? [repaymentsExtra(repayments)] : [] };
    }
    case "COLLECTION_HEAD": {
      const pending = await pendingSettlementCount();
      return { apps: [], extras: pending > 0 ? [settlementsExtra(pending)] : [] };
    }
    case "COLLECTION_EXECUTIVE": {
      // Mirrors /staff/collections/buckets: open collection cases (the buckets grid lists all of them).
      const cases = await countOf(collectionsApi.listCases());
      const extras: QueueExtra[] = [];
      if (cases > 0) extras.push({ key: "cases", label: "Open collection cases", count: cases, href: "/staff/collections/buckets" });
      return { apps: [], extras };
    }
    case "ADMIN": {
      // One wave — the six pipeline lists and the two extra counts are all independent.
      const [lists, repayments, settlements] = await Promise.all([
        Promise.all(
          (["KYC_PENDING", "REVIEW_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING", "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING"] as ApplicationStatus[]).map(
            (s) => safe(staffApi.listByStatus(s)),
          ),
        ),
        pendingRepaymentCount(),
        pendingSettlementCount(),
      ]);
      const extras: QueueExtra[] = [];
      if (repayments > 0) extras.push(repaymentsExtra(repayments));
      if (settlements > 0) extras.push(settlementsExtra(settlements));
      return { apps: lists.flat(), extras };
    }
    default:
      return { apps: [], extras: [] };
  }
}

/** Requested amount, or the "amount pending" placeholder for pre-amount applications. */
function amountText(a: ApplicationView): string {
  return a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "amount pending";
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

  // Layers 1 + 2 — the signed-in role's action queue.
  const queueQuery = useQuery({
    queryKey: ["staff-dashboard-queue", role],
    queryFn: () => fetchRoleQueue(role as StaffRole),
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

  // Admin oversight: a company-wide transactions summary (Layer 4, collapsed).
  const isAdmin = role === "ADMIN";
  const txns = useQuery({
    queryKey: ["admin-dashboard-txns"],
    queryFn: () => staffApi.transactions(),
    enabled: mounted && isAdmin,
    refetchInterval: REFRESH_MS,
  });

  // One shared Journey drawer for the whole page, driven by the open application id.
  const [openJourneyId, setOpenJourneyId] = React.useState<number | null>(null);

  if (!mounted || !session || !role) {
    return <div className="h-64 rounded border border-line bg-white" />;
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
    stats.isFetching || queueQuery.isFetching || trends.isFetching || (isAdmin && txns.isFetching);

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
            if (isAdmin) txns.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {fetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Layer 1 — "Your work" hero */}
      {queue ? (
        <WorkHero
          queue={queue}
          count={headlineCount}
          items={myApps}
          extras={activeExtras}
          loading={queueQuery.isLoading}
          actingHref={actingHref}
          onJourney={setOpenJourneyId}
        />
      ) : (
        <FallbackHero role={role} />
      )}

      {/* 30-day activity trends — applications, disbursals and repayments. */}
      <TrendsSection data={trends.data} loading={trends.isLoading} />

      {/* Layer 2 — Pending actions (main) + Layer 4 quick links (aside) */}
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          {queue ? (
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
                <ul className="divide-y divide-grey-200 rounded border border-line bg-white">
                  {myApps.map((a) => (
                    <PendingActionRow
                      key={a.id}
                      app={a}
                      actingHref={actingHref}
                      onJourney={setOpenJourneyId}
                    />
                  ))}
                  {activeExtras.map((e) => (
                    <ExtraActionRow key={e.key} extra={e} />
                  ))}
                </ul>
              ) : (
                <div className="rounded border border-line bg-white p-8 text-center text-sm text-muted">
                  You&apos;re all caught up — nothing in your queue.
                </div>
              )}
            </section>
          ) : (
            <section className="rounded border border-line bg-white p-6 text-sm text-muted">
              Use the navigation to open the queues your role can act on, or go to the{" "}
              <Link href="/staff/applications" className="font-semibold text-navy hover:underline">
                live application queues
              </Link>
              .
            </section>
          )}
        </div>

        {/* Layer 4 — collapsed extras */}
        <aside className="space-y-4">
          <details className="group rounded border border-line bg-white shadow-sm">
            <summary className="flex cursor-pointer items-center gap-2 px-5 py-3 font-serif text-base text-navy [&::-webkit-details-marker]:hidden">
              <ChevronRight size={15} className="transition-transform group-open:rotate-90" />
              More &amp; quick links
            </summary>
            <ul className="border-t border-line px-3 pb-3 pt-1 text-sm">
              <li><Link href="/staff/applications" className="-mx-0 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ClipboardList size={15} /> Live application queues</Link></li>
              <li><Link href="/staff/kyc-approvals" className="flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ShieldCheck size={15} /> KYC approvals</Link></li>
              <li><Link href="/staff/disbursement" className="flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Banknote size={15} /> Disbursement</Link></li>
              <li><Link href="/staff/accounting" className="flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Receipt size={15} /> Accounting</Link></li>
              <li><Link href="/staff/collections/buckets" className="flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><PhoneCall size={15} /> Collections</Link></li>
            </ul>
          </details>

          {stats.isError && (
            <div className="rounded border border-warning-100 bg-warning-50 p-4 text-xs text-warning-800">
              Couldn&apos;t load live counts — check that you&apos;re signed in to the staff console.
            </div>
          )}
        </aside>
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

      {/* Shared Journey drawer (Layer 1/2 rows open it; unmount restores focus to the trigger). */}
      {openJourneyId != null && (
        <ApplicationJourney
          applicationId={openJourneyId}
          open
          onClose={() => setOpenJourneyId(null)}
        />
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
  onJourney,
}: {
  queue: { label: string; info: string };
  count: number;
  items: ApplicationView[];
  extras: QueueExtra[];
  loading: boolean;
  actingHref?: string;
  onJourney: (id: number) => void;
}) {
  // Oldest-waiting proxy: the lowest application id. The loan_application aggregate
  // has no created_at column, so id-ascending stands in for arrival order (§10 risk).
  // Operates on applications only — non-application sources (extras) have no id order.
  const oldest = items.length ? [...items].sort((a, b) => a.id - b.id)[0] : null;

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
        <div className="mt-5 flex flex-wrap items-center gap-3 rounded border border-line bg-grey-50 p-4">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-navy-tint px-2.5 py-1 text-xs font-semibold text-navy">
            <Clock size={12} /> Oldest waiting
          </span>
          <span className="min-w-0 text-sm">
            <span className="font-semibold text-ink">App #{oldest.id}</span>
            <span className="text-muted"> · Customer #{oldest.customerId} · {amountText(oldest)}</span>
          </span>
          <span className="rounded-full bg-white px-2.5 py-0.5 text-xs font-semibold text-ink shadow-sm">
            {statusLabel(oldest.status)}
          </span>
          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={() => onJourney(oldest.id)}
              className="btn btn-sm btn-outline"
              aria-label={`Application #${oldest.id}, ${amountText(oldest)}, ${statusLabel(oldest.status)} — view journey`}
            >
              <Route size={14} /> Journey
            </button>
            {actingHref && (
              <Link href={actingHref} className="btn btn-sm btn-ghost">
                Open queue <ArrowRight size={14} />
              </Link>
            )}
          </div>
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

/** Layer 1 fallback for roles with no pipeline action queue (collections, developer). */
function FallbackHero({ role }: { role: StaffRole }) {
  const area = FALLBACK_AREA[role];
  return (
    <section className="mb-8 rounded-lg border border-gold-soft bg-white p-6 shadow-sm">
      <h2 className="mb-1 text-lg">Your work</h2>
      <p className="text-sm text-muted">{area?.label ?? "Use the navigation to open your area of the console."}</p>
      <Link href={area?.href ?? "/staff/applications"} className="btn btn-sm btn-navy mt-4">
        {area?.cta ?? "Open the console"} <ArrowRight size={15} />
      </Link>
    </section>
  );
}

/** Layer 2 row — the whole row opens the Journey drawer; a slim link deep-links to the acting page. */
function PendingActionRow({
  app,
  actingHref,
  onJourney,
}: {
  app: ApplicationView;
  actingHref?: string;
  onJourney: (id: number) => void;
}) {
  return (
    <li className="flex items-center gap-2 pr-3 transition hover:bg-grey-100">
      <button
        type="button"
        onClick={() => onJourney(app.id)}
        aria-label={`Application #${app.id}, ${amountText(app)}, ${statusLabel(app.status)} — view journey`}
        className="flex min-w-0 flex-1 items-center gap-4 px-4 py-3 text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-navy focus-visible:ring-inset"
      >
        <span className="grid h-10 w-10 flex-shrink-0 place-items-center rounded-full bg-navy-tint font-serif text-sm font-bold text-navy">
          #{app.id}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold text-ink">Customer #{app.customerId}</span>
          <span className="block text-xs text-muted">App #{app.id} · {amountText(app)}</span>
        </span>
        <span className="flex-shrink-0 rounded-full bg-grey-100 px-2.5 py-0.5 text-xs font-semibold text-ink">
          {statusLabel(app.status)}
        </span>
      </button>
      {actingHref && (
        <Link
          href={actingHref}
          aria-label={`Open queue for application #${app.id}`}
          className="flex flex-shrink-0 items-center gap-1 rounded px-2 py-1 text-xs font-semibold text-navy hover:bg-navy-tint"
        >
          Open queue <ArrowRight size={13} />
        </Link>
      )}
    </li>
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
      <div className="mt-1 flex items-center justify-between text-[11px] text-muted">
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
