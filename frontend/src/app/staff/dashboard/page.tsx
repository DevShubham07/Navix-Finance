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
  paiseToINR,
  statusLabel,
  type ApplicationStatus,
  type ApplicationView,
  type TransactionView,
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
  ADMIN: "/staff/applications",
};

/** Fallback "your area" card for roles with no pipeline action queue. */
const FALLBACK_AREA: Partial<Record<StaffRole, { href: string; label: string; cta: string }>> = {
  COLLECTION_HEAD: {
    href: "/staff/collections/buckets",
    label: "Work overdue loans by DPD bucket and approve settlements from the collections desk.",
    cta: "Open collections",
  },
  COLLECTION_EXECUTIVE: {
    href: "/staff/collections/buckets",
    label: "Work your assigned overdue cases and log borrower interactions.",
    cta: "Open collections",
  },
  DEVELOPER: {
    href: "/staff/applications",
    label: "Read-only oversight of the live application pipeline.",
    cta: "Open application queues",
  },
};

/** The live items for a role's action queue. */
async function fetchRoleQueue(role: StaffRole): Promise<ApplicationView[]> {
  const safe = (p: Promise<ApplicationView[]>) => p.catch(() => [] as ApplicationView[]);
  switch (role) {
    case "KYC_APPROVER": {
      const [kyc, review] = await Promise.all([
        safe(staffApi.listByStatus("KYC_PENDING")),
        safe(staffApi.listByStatus("REVIEW_PENDING")),
      ]);
      return [...kyc, ...review];
    }
    case "CREDIT_EXECUTIVE":
      return safe(staffApi.listByStatus("CREDIT_EXEC_PENDING"));
    case "CREDIT_HEAD": {
      const [queue, headPending] = await Promise.all([
        safe(staffApi.creditQueue()),
        safe(staffApi.listByStatus("CREDIT_HEAD_PENDING")),
      ]);
      return [...queue, ...headPending];
    }
    case "DISBURSEMENT_HEAD": {
      const [pending, failed] = await Promise.all([
        safe(staffApi.listByStatus("DISBURSEMENT_PENDING")),
        safe(staffApi.listByStatus("DISBURSEMENT_FAILED")),
      ]);
      return [...pending, ...failed];
    }
    case "ACCOUNTANT":
      return safe(staffApi.listByStatus("ACCOUNTANT_PENDING"));
    case "ADMIN": {
      const lists = await Promise.all(
        (["KYC_PENDING", "REVIEW_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING", "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING"] as ApplicationStatus[]).map(
          (s) => safe(staffApi.listByStatus(s)),
        ),
      );
      return lists.flat();
    }
    default:
      return [];
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
  const myItems = queueQuery.data ?? [];
  const actingHref = ROLE_HREF[role];
  const loading = stats.isLoading || (!!queue && queueQuery.isLoading);

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
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {loading ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {/* Layer 1 — "Your work" hero */}
      {queue ? (
        <WorkHero
          queue={queue}
          items={myItems}
          loading={queueQuery.isLoading}
          actingHref={actingHref}
          onJourney={setOpenJourneyId}
        />
      ) : (
        <FallbackHero role={role} />
      )}

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
                  {myItems.length} pending
                </span>
              </div>

              {queueQuery.isLoading ? (
                <div className="h-40 animate-pulse rounded border border-line bg-white" />
              ) : myItems.length ? (
                <ul className="divide-y divide-grey-200 rounded border border-line bg-white">
                  {myItems.map((a) => (
                    <PendingActionRow
                      key={a.id}
                      app={a}
                      actingHref={actingHref}
                      onJourney={setOpenJourneyId}
                    />
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
  items,
  loading,
  actingHref,
  onJourney,
}: {
  queue: { label: string; info: string };
  items: ApplicationView[];
  loading: boolean;
  actingHref?: string;
  onJourney: (id: number) => void;
}) {
  const count = items.length;
  // Oldest-waiting proxy: the lowest application id. The loan_application aggregate
  // has no created_at column, so id-ascending stands in for arrival order (§10 risk).
  const oldest = count ? [...items].sort((a, b) => a.id - b.id)[0] : null;

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
