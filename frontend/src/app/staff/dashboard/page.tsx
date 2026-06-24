"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, ShieldCheck, ClipboardList, Banknote, Receipt, PhoneCall, RefreshCw, Loader2 } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { InfoTooltip } from "@/components/ui";
import { useStaffSession, STAFF_ROLE_LABELS } from "@/lib/mock/session";
import type { StaffRole } from "@/lib/auth/rbac";
import {
  staffApi,
  paiseToINR,
  statusLabel,
  type ApplicationStatus,
  type ApplicationView,
} from "@/lib/api/applications";
import { useMounted } from "@/hooks/use-mounted";

const REFRESH_MS = 10_000;

/** Per-role "your queue" label (+ an ⓘ explanation) and the live statuses that feed it. */
const QUEUE: Partial<Record<StaffRole, { label: string; info: string }>> = {
  KYC_APPROVER: {
    label: "Applications awaiting KYC clearance",
    info: "Review each applicant's KYC details and documents, then approve to advance to credit review, or reject to send it back.",
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

/** Statuses we count for the headline stat cards. */
const COUNT_STATUSES: ApplicationStatus[] = [
  "KYC_PENDING",
  "CREDIT_EXEC_PENDING",
  "CREDIT_EXEC_APPROVED",
  "CREDIT_HEAD_PENDING",
  "CREDIT_HEAD_APPROVED",
  "DISBURSEMENT_PENDING",
  "ACCOUNTANT_PENDING",
  "ACTIVE",
  "OVERDUE",
];

/** Count a status list defensively — a role lacking list permission shouldn't break the board. */
async function countOf(status: ApplicationStatus): Promise<number> {
  try {
    return (await staffApi.listByStatus(status)).length;
  } catch {
    return 0;
  }
}

/** The live items for a role's action queue. */
async function fetchRoleQueue(role: StaffRole): Promise<ApplicationView[]> {
  const safe = (p: Promise<ApplicationView[]>) => p.catch(() => [] as ApplicationView[]);
  switch (role) {
    case "KYC_APPROVER":
      return safe(staffApi.listByStatus("KYC_PENDING"));
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
        (["KYC_PENDING", "CREDIT_EXEC_PENDING", "CREDIT_HEAD_PENDING", "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING"] as ApplicationStatus[]).map(
          (s) => safe(staffApi.listByStatus(s)),
        ),
      );
      return lists.flat();
    }
    default:
      return [];
  }
}

export default function StaffDashboardPage() {
  const mounted = useMounted();
  const { session } = useStaffSession();

  const counts = useQuery({
    queryKey: ["staff-dashboard-counts"],
    queryFn: async () => {
      const entries = await Promise.all(COUNT_STATUSES.map(async (s) => [s, await countOf(s)] as const));
      return Object.fromEntries(entries) as Record<ApplicationStatus, number>;
    },
    enabled: mounted && !!session,
    refetchInterval: REFRESH_MS,
  });

  const role = session?.role;
  const queueQuery = useQuery({
    queryKey: ["staff-dashboard-queue", role],
    queryFn: () => fetchRoleQueue(role as StaffRole),
    enabled: mounted && !!role && !!QUEUE[role as StaffRole],
    refetchInterval: REFRESH_MS,
  });

  if (!mounted || !session) {
    return <div className="h-64 rounded border border-line bg-white" />;
  }

  const c = counts.data;
  const n = (s: ApplicationStatus) => c?.[s] ?? 0;
  const inCredit =
    n("CREDIT_EXEC_PENDING") + n("CREDIT_EXEC_APPROVED") + n("CREDIT_HEAD_PENDING") + n("CREDIT_HEAD_APPROVED");
  const queue = QUEUE[session.role];
  const myItems = queueQuery.data ?? [];
  const loading = counts.isLoading || (!!queue && queueQuery.isLoading);

  return (
    <div>
      <PageHeader
        title={`Welcome, ${session.name.split(" ")[0]}`}
        subtitle={`${STAFF_ROLE_LABELS[session.role]} · live operations overview`}
      >
        <button
          onClick={() => { counts.refetch(); queueQuery.refetch(); }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {loading ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-8 grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard label="KYC review" value={n("KYC_PENDING")} accent="gold"
          info="Applications whose identity (PAN/Aadhaar) and documents are waiting for a KYC Approver to clear before they enter credit." />
        <StatCard label="In credit" value={inCredit}
          info="Applications being assessed by the Credit Executive (recommend) and Credit Head (final approve, SoD-checked)." />
        <StatCard label="To release" value={n("DISBURSEMENT_PENDING")}
          info="Approved loans waiting for the Disbursement Head to release funds. Entering a transaction id activates the loan immediately." />
        <StatCard label="To confirm" value={n("ACCOUNTANT_PENDING")}
          info="Disbursals released without a transaction id, waiting for the Accountant to confirm the bank transfer (activates the loan)." />
        <StatCard label="Active loans" value={n("ACTIVE")} accent="success"
          info="Live loans currently being repaid (disbursed and not yet closed)." />
        <StatCard label="In collections" value={n("OVERDUE")} accent="error"
          info="Loans past their due date. Collections officers work these by DPD bucket; settlements need Collection Head approval." />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <div className="mb-3 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h2 className="mb-0 text-xl">{queue ? queue.label : "Pipeline"}</h2>
              {queue ? <InfoTooltip content={queue.info} /> : null}
            </div>
            {queue ? (
              <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{myItems.length} pending</span>
            ) : null}
          </div>

          {!queue ? (
            <div className="rounded border border-line bg-white p-6 text-sm text-muted">
              Use the navigation to open the queues your role can act on, or go to the{" "}
              <Link href="/staff/applications" className="font-semibold text-navy hover:underline">live application queues</Link>.
            </div>
          ) : queueQuery.isLoading ? (
            <div className="h-40 animate-pulse rounded border border-line bg-white" />
          ) : myItems.length ? (
            <ul className="divide-y divide-grey-200 rounded border border-line bg-white">
              {myItems.map((a) => (
                <li key={a.id}>
                  <Link href="/staff/applications" className="flex items-center gap-4 px-4 py-3 transition hover:bg-grey-100">
                    <span className="grid h-10 w-10 flex-shrink-0 place-items-center rounded-full bg-navy-tint font-serif text-sm font-bold text-navy">
                      #{a.id}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-semibold text-ink">Applicant #{a.applicantId}</span>
                      <span className="block text-xs text-muted">
                        App #{a.id} · {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "amount pending"}
                      </span>
                    </span>
                    <span className="flex-shrink-0 rounded-full bg-grey-100 px-2.5 py-0.5 text-xs font-semibold text-ink">
                      {statusLabel(a.status)}
                    </span>
                    <ArrowRight size={16} className="flex-shrink-0 text-muted" />
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <div className="rounded border border-line bg-white p-8 text-center text-sm text-muted">
              You&apos;re all caught up — nothing in your queue.
            </div>
          )}
        </div>

        <aside className="space-y-4">
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <h3 className="mb-2 font-serif text-base text-navy">Quick links</h3>
            <ul className="text-sm">
              <li><Link href="/staff/applications" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ClipboardList size={15} /> Live application queues</Link></li>
              <li><Link href="/staff/kyc-approvals" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><ShieldCheck size={15} /> KYC approvals</Link></li>
              <li><Link href="/staff/disbursement" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Banknote size={15} /> Disbursement</Link></li>
              <li><Link href="/staff/accounting" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><Receipt size={15} /> Accounting</Link></li>
              <li><Link href="/staff/collections/buckets" className="-mx-2 flex items-center gap-2 rounded px-2 py-2 text-ink hover:bg-grey-100 hover:text-navy"><PhoneCall size={15} /> Collections</Link></li>
            </ul>
          </div>

          {counts.isError && (
            <div className="rounded border border-warning-100 bg-warning-50 p-4 text-xs text-warning-800">
              Couldn&apos;t load live counts — check that you&apos;re signed in to the staff console.
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
