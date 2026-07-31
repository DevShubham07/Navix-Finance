"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Lock, Loader2, RefreshCw, AlertTriangle, ChevronDown, ChevronRight, Receipt } from "lucide-react";
import { PageHeader, RefreshButton } from "@/components/staff/staff-ui";
import { hasPermission, type StaffRole } from "@/lib/auth/rbac";
import { staffApi, isLoanOverdue, type ApplicationView } from "@/lib/api/applications";
import {
  useStaffMe,
  ReviewLookup,
  StatusQueue,
  CreditQueuePanel,
  KycActions,
  KycCreditActions,
  ReviewActions,
  ExecActions,
  HeadActions,
  DisbursementActions,
  AccountantActions,
  AppRow,
  errMessage,
  ROLE_LABEL,
  PIPELINE_ROLES,
} from "@/components/staff/live-pipeline";
import { RepaymentVerifyQueue } from "@/components/staff/repayment-verify-queue";
import { CollectionAssignActions } from "@/components/staff/pipeline/collection-actions";
import { DpdBucketsPanel } from "@/components/staff/pipeline/dpd-buckets-panel";
import { InfoTooltip } from "@/components/ui";

/** Roles that don't drive the credit/disbursement pipeline but do need the repayment/closed
 * back-office panels (see `RoleQueues`) — previously left with "no application-pipeline queue". */
const BACK_OFFICE_ROLES: StaffRole[] = ["DEVELOPER", "COLLECTION_HEAD", "COLLECTION_EXECUTIVE"];

export default function StaffApplicationsPage() {
  const me = useStaffMe();

  if (me.isLoading) {
    return <div className="h-64 rounded border border-line bg-white" />;
  }

  const session = me.data;
  if (!session) {
    return (
      <div className="rounded border border-warning-100 bg-warning-50 p-6 text-sm text-warning-800">
        No live staff session. Sign in via the staff console to use the live application queues.
      </div>
    );
  }

  const role = session.role;
  const isPipeline = PIPELINE_ROLES.includes(role) || role === "ADMIN" || BACK_OFFICE_ROLES.includes(role);

  return (
    <div>
      <PageHeader
        title="Live applications"
        subtitle="Every queue your role can act on, in one place. Real backend state machine — walk loans through to ACTIVE."
      >
        <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>
        <RefreshButton
          queryKeys={[["staff-queue"], ["staff-dashboard-stats"], ["staff-dashboard-queue"], ["staff-pending-repayments"]]}
        />
        {/* Carried over from the removed accounting page's header — the ledger is the accountant's
            other destination and has no other entry point outside the Administration nav group. */}
        {hasPermission(role, "loan:activate") && (
          <Link
            href="/staff/accounting/transactions"
            className="inline-flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-sm font-semibold text-navy hover:bg-grey-100"
          >
            <Receipt size={15} /> All transactions
          </Link>
        )}
      </PageHeader>

      <div className="space-y-8">
        {/* Available to EVERY staff role. */}
        <ReviewLookup />

        {isPipeline ? (
          <RoleQueues role={role} />
        ) : (
          <div className="flex items-start gap-2 rounded border border-line bg-grey-50 p-4 text-sm text-muted">
            <Lock size={16} className="mt-0.5 flex-shrink-0" />
            The {ROLE_LABEL[role]} role has no application-pipeline queue. Use &ldquo;Review an application&rdquo;
            above to open any application by ID and view the customer&apos;s details and documents.
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Role -> the queue panels it sees (ADMIN sees them all).
 *
 * This is now the single workbench: every panel that used to live on the dedicated
 * /staff/kyc-approvals and /staff/kyc-review pages (both since removed) is rendered here, with
 * the same titles, info text and actions — including the instant-loan credit fast-path and the
 * reborrow queue's inline loan history. The disbursement fast-track/standard/failed split and the
 * accountant's repayment-verify queue are mirrored here too, so a role never has to visit a second
 * page to see its own work.
 */
function RoleQueues({ role }: { role: StaffRole }) {
  const showAll = role === "ADMIN";

  // "Awaiting repayment" (ACTIVE+OVERDUE) and "Closed" are back-office, not credit-pipeline,
  // panels: the show-all view (ADMIN/DEVELOPER) and ACCOUNTANT get both; collections roles only
  // need to see what's still owed, not the closed archive.
  const showBothRepaymentPanels = showAll || role === "DEVELOPER" || role === "ACCOUNTANT";
  const isCollections = role === "COLLECTION_HEAD" || role === "COLLECTION_EXECUTIVE";
  const showAwaitingRepayment = showBothRepaymentPanels || isCollections;
  // DPD buckets moved here off the removed /staff/collections/buckets page.
  const showDpdBuckets = showAll || role === "DEVELOPER" || isCollections;

  return (
    <div className="space-y-8">
      {(showAll || role === "KYC_APPROVER") && (
        <>
          <StatusQueue
            title="Applications awaiting KYC clearance"
            status="KYC_PENDING"
            actions={(app) => <KycActions app={app} />}
            info="Fresh customers who have completed onboarding. Review their details and documents, then clear or reject KYC."
          />
          {/* Instant-loan credit fast-path: KYC-approved applications the borrower has applied on.
              Approving routes straight to the Disbursement Head, skipping the credit exec→head
              maker-checker (deliberate — the limit is a flat 25%-of-salary formula). */}
          <StatusQueue
            title="Approve instant loans (credit clearance)"
            status="KYC_APPROVED"
            info="KYC-approved applications the borrower has chosen an amount on. Approve to send straight to the Disbursement Head — no separate credit review needed."
            filter={(app) => app.amountRequestedPaise != null}
            actions={(app) => <KycCreditActions app={app} />}
          />
          <StatusQueue
            title="Reborrow reviews — returning borrowers"
            status="REVIEW_PENDING"
            actions={(app) => <ReviewActions app={app} />}
            info="These borrowers had at least one overdue repayment in the past, so each new advance needs a KYC approver's sign-off before it proceeds to disbursement."
            // The clear/reject decision hinges on past delinquency — keep the loan history inline
            // on the decision surface (documented AppRow exception).
            withLoanHistory
          />
        </>
      )}

      {(showAll || role === "CREDIT_HEAD") && (
        <>
          <CreditQueuePanel />
          <StatusQueue title="Credit head decision" status="CREDIT_HEAD_PENDING" actions={(app) => <HeadActions app={app} />} />
        </>
      )}

      {(showAll || role === "CREDIT_EXECUTIVE") && (
        <StatusQueue title="Credit executive review" status="CREDIT_EXEC_PENDING" actions={(app) => <ExecActions app={app} />} />
      )}

      {(showAll || role === "DISBURSEMENT_HEAD") && (
        <>
          <StatusQueue
            title="Pre-approved — fast-track release"
            status="DISBURSEMENT_PENDING"
            filter={(app) => app.fastTrack === true}
            actions={(app) => <DisbursementActions app={app} />}
            info="Returning borrowers pre-approved on a clean repayment history — these skipped credit review and came straight to you. Release the funds as usual."
          />
          <StatusQueue
            title="Standard disbursement"
            status="DISBURSEMENT_PENDING"
            filter={(app) => app.fastTrack !== true}
            actions={(app) => <DisbursementActions app={app} />}
            info="Credit-approved loans awaiting release. Enter the bank/UPI transaction id to release & activate the loan immediately; approve without one to route it to the accountant to confirm the transfer."
          />
          <StatusQueue
            title="Disbursement failed — retry"
            status="DISBURSEMENT_FAILED"
            actions={(app) => <DisbursementActions app={app} />}
            info="Transfers the accountant marked as failed. Re-release them here once the bank issue is resolved."
          />
        </>
      )}

      {(showAll || role === "ACCOUNTANT") && (
        <>
          <StatusQueue
            title="Transfers to confirm"
            status="ACCOUNTANT_PENDING"
            actions={(app) => <AccountantActions app={app} />}
            info="Disbursals the Disbursement Head released without a transaction id. Confirm the bank transfer landed to mint & activate the loan, or mark it failed to send it back for retry."
          />
          <RepaymentVerifyQueue />
        </>
      )}

      {showAwaitingRepayment && <AwaitingRepaymentPanel />}
      {showDpdBuckets && <DpdBucketsPanel />}
      {showBothRepaymentPanels && <ClosedPanel />}
    </div>
  );
}

/**
 * "Awaiting repayment" — ACTIVE and OVERDUE side by side in **separate columns**, not one
 * interleaved list: the two populations are worked differently (an overdue loan is accruing a
 * 2%/day penalty and needs collections; an active one only needs a pre-due reminder), so mixing
 * them into a single scroll made the urgent set impossible to size at a glance.
 *
 * Rows carry the collections action cluster ({@link CollectionAssignActions}), so a Collection
 * Head can put an executive on a loan straight from here — no detour through a "collectible
 * loans" list to create a case first.
 */
function AwaitingRepaymentPanel() {
  const activeQ = useQuery({
    queryKey: ["staff-queue", "ACTIVE"],
    queryFn: () => staffApi.listByStatus("ACTIVE"),
    refetchInterval: 8000,
  });
  const overdueQ = useQuery({
    queryKey: ["staff-queue", "OVERDUE"],
    queryFn: () => staffApi.listByStatus("OVERDUE"),
    refetchInterval: 8000,
  });

  // Split on the LOAN's due date, not the application status. LoanStatus.OVERDUE is
  // compute-on-read — nothing in the codebase ever writes it, and the aggregate's own status stays
  // ACTIVE for the whole repayment window — so grouping by `app.status` put every past-due loan in
  // the "active" bucket and left "overdue" permanently empty. Both queues are still fetched: an
  // application CAN legitimately sit in the OVERDUE status, it just usually doesn't.
  const all = [...(overdueQ.data ?? []), ...(activeQ.data ?? [])];
  // Overdue first within each column, then by application id ascending — this codebase's
  // established "oldest waiting" proxy (the aggregate has no created_at; see WorkHero).
  const byId = (a: ApplicationView, b: ApplicationView) => a.id - b.id;
  const overdueApps = all.filter((a) => isLoanOverdue(a)).sort(byId);
  const activeApps = all.filter((a) => !isLoanOverdue(a)).sort(byId);
  const isLoading = activeQ.isLoading || overdueQ.isLoading;

  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">Awaiting repayment</h2>
          {isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
            {overdueApps.length + activeApps.length}
          </span>
        </div>
        <button
          onClick={() => {
            activeQ.refetch();
            overdueQ.refetch();
          }}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          <RefreshCw size={13} /> Refresh
        </button>
      </header>

      {/* Both queries feed both columns (the split is by due date, not by source), so a failure in
          either is reported once here rather than blanking a column that has valid rows. */}
      {(overdueQ.error || activeQ.error) && (
        <p className="flex items-center gap-1.5 border-b border-line bg-warning-50 px-5 py-2 text-xs font-semibold text-warning-800">
          <AlertTriangle size={12} /> Couldn&apos;t load part of this queue —{" "}
          {errMessage(overdueQ.error ?? activeQ.error)}
        </p>
      )}

      <div className="grid xl:grid-cols-2 xl:divide-x xl:divide-line">
        <RepaymentColumn
          title="Overdue"
          tone="error"
          note="Past the due date — the 2%/day late penalty is accruing (after a 1-day salary grace, capped at 30 days). Put a collections executive on these."
          apps={overdueApps}
          emptyText="Nothing overdue."
        />
        <RepaymentColumn
          title="Active"
          tone="navy"
          note="Disbursed and still inside the due date. Interest is accruing at 1%/day; no penalty yet."
          apps={activeApps}
          emptyText="No active loans."
        />
      </div>
    </section>
  );
}

/** One side of {@link AwaitingRepaymentPanel} — its own header, count and rows. */
function RepaymentColumn({
  title,
  tone,
  note,
  apps,
  emptyText,
}: {
  title: string;
  tone: "error" | "navy";
  note: string;
  apps: ApplicationView[];
  emptyText: string;
}) {
  return (
    <div className="min-w-0">
      <div
        className={`flex items-center gap-2 border-b border-line px-5 py-2 ${
          tone === "error" ? "bg-error-50" : "bg-grey-50"
        }`}
      >
        {tone === "error" && <AlertTriangle size={13} className="text-error-700" />}
        <h3 className={`text-sm font-semibold ${tone === "error" ? "text-error-700" : "text-navy"}`}>{title}</h3>
        <span
          className={`rounded-full px-2 py-0.5 text-xs font-semibold ${
            tone === "error" ? "bg-error-100 text-error-700" : "bg-navy-tint text-navy"
          }`}
        >
          {apps.length}
        </span>
        <InfoTooltip content={note} />
      </div>
      {apps.length === 0 ? (
        <p className="px-5 py-6 text-center text-sm text-muted">{emptyText}</p>
      ) : (
        <ul className="divide-y divide-line">
          {apps.map((app) => (
            <AppRow key={app.id} app={app} actions={(a) => <CollectionAssignActions app={a} />} />
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * "Closed (fully repaid)" queue — collapsed by default via a native `<details>`. The `useQuery`
 * stays `enabled: false` (and its 8s poll off) until the staffer expands it, so this console never
 * polls the whole closed-application archive in the background — only the panels a role actually
 * looks at should cost a request every 8s.
 */
function ClosedPanel() {
  const [open, setOpen] = React.useState(false);
  const q = useQuery({
    queryKey: ["staff-queue", "CLOSED"],
    queryFn: () => staffApi.listByStatus("CLOSED"),
    refetchInterval: open ? 8000 : false,
    enabled: open,
  });
  const apps = q.data ?? [];

  return (
    <details
      className="rounded border border-line bg-white shadow-sm"
      onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}
    >
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-3 [&::-webkit-details-marker]:hidden">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">Closed (fully repaid)</h2>
          {open && q.isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          {open && (
            <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{apps.length}</span>
          )}
        </div>
        {open ? <ChevronDown size={16} className="text-muted" /> : <ChevronRight size={16} className="text-muted" />}
      </summary>

      {open && (
        <div className="border-t border-line">
          {q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : apps.length === 0 ? (
            <p className="px-5 py-6 text-center text-sm text-muted">
              Nothing in the <code className="text-xs">CLOSED</code> queue.
            </p>
          ) : (
            <ul className="divide-y divide-line">
              {apps.map((app) => (
                <AppRow key={app.id} app={app} actions={() => null} />
              ))}
            </ul>
          )}
        </div>
      )}
    </details>
  );
}
