"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Lock, Loader2, RefreshCw, AlertTriangle, ChevronDown, ChevronRight } from "lucide-react";
import { PageHeader, RefreshButton } from "@/components/staff/staff-ui";
import type { StaffRole } from "@/lib/auth/rbac";
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import {
  useStaffMe,
  ReviewLookup,
  StatusQueue,
  CreditQueuePanel,
  KycActions,
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
        subtitle="Real backend state machine. Act on the queue for your role to walk loans to ACTIVE."
      >
        <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>
        <RefreshButton queryKeys={[["staff-queue"], ["staff-dashboard-stats"], ["staff-dashboard-queue"]]} />
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

/** Role -> the queue panels it sees (ADMIN sees them all). */
function RoleQueues({ role }: { role: StaffRole }) {
  const showAll = role === "ADMIN";

  // "Awaiting repayment" (ACTIVE+OVERDUE) and "Closed" are back-office, not credit-pipeline,
  // panels: the show-all view (ADMIN/DEVELOPER) and ACCOUNTANT get both; collections roles only
  // need to see what's still owed, not the closed archive.
  const showBothRepaymentPanels = showAll || role === "DEVELOPER" || role === "ACCOUNTANT";
  const showAwaitingRepayment = showBothRepaymentPanels || role === "COLLECTION_HEAD" || role === "COLLECTION_EXECUTIVE";

  return (
    <div className="space-y-8">
      {(showAll || role === "KYC_APPROVER") && (
        <>
          <StatusQueue title="KYC pending" status="KYC_PENDING" actions={(app) => <KycActions app={app} />} />
          <StatusQueue
            title="Reborrow reviews (past delinquency)"
            status="REVIEW_PENDING"
            actions={(app) => <ReviewActions app={app} />}
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
        <StatusQueue title="Disbursement pending" status="DISBURSEMENT_PENDING" actions={(app) => <DisbursementActions app={app} />} />
      )}

      {(showAll || role === "ACCOUNTANT") && (
        <StatusQueue title="Accountant validation" status="ACCOUNTANT_PENDING" actions={(app) => <AccountantActions app={app} />} />
      )}

      {showAwaitingRepayment && <AwaitingRepaymentPanel />}
      {showBothRepaymentPanels && <ClosedPanel />}
    </div>
  );
}

/**
 * Merged ACTIVE + OVERDUE queue — "awaiting repayment" — one panel instead of two adjacent
 * `StatusQueue`s, with OVERDUE rows visibly flagged by a red strip above the row (AppRow itself
 * already renders the status pill, so this adds the extra at-a-glance signal a busy back-office
 * queue needs). No actions here (read-only follow-up view — collection/repayment actions live on
 * the loan-detail page), same as the other read-only queues on this console.
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

  // OVERDUE rows surface first (most urgent), then ACTIVE; within each group, sort by
  // application id ascending — this codebase's established "oldest waiting" proxy (the aggregate
  // has no created_at; see staff/dashboard's WorkHero comment).
  const overdueApps = [...(overdueQ.data ?? [])].sort((a, b) => a.id - b.id);
  const activeApps = [...(activeQ.data ?? [])].sort((a, b) => a.id - b.id);
  const apps: ApplicationView[] = [...overdueApps, ...activeApps];
  const isLoading = activeQ.isLoading || overdueQ.isLoading;

  const activeFailed = !!activeQ.error;
  const overdueFailed = !!overdueQ.error;
  // Only collapse to a full-panel error when BOTH sources failed and we have nothing (not even
  // stale cached rows) to show; otherwise render whatever rows we have and flag the failed
  // source(s) with a slim inline banner instead of hiding valid data.
  const showFullError = activeFailed && overdueFailed && apps.length === 0;

  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">Awaiting repayment (active &amp; overdue)</h2>
          {isLoading && <Loader2 size={15} className="animate-spin text-muted" />}
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
            {[
              overdueApps.length > 0 && `${overdueApps.length} overdue`,
              activeApps.length > 0 && `${activeApps.length} active`,
            ]
              .filter(Boolean)
              .join(" · ") || "0"}
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

      {showFullError ? (
        <p className="px-5 py-4 text-sm text-error-700">{errMessage(activeQ.error ?? overdueQ.error)}</p>
      ) : (
        <>
          {overdueFailed && (
            <p className="flex items-center gap-1.5 border-b border-line bg-warning-50 px-5 py-2 text-xs font-semibold text-warning-800">
              <AlertTriangle size={12} /> Couldn&apos;t load overdue loans — retrying
            </p>
          )}
          {activeFailed && (
            <p className="flex items-center gap-1.5 border-b border-line bg-warning-50 px-5 py-2 text-xs font-semibold text-warning-800">
              <AlertTriangle size={12} /> Couldn&apos;t load active loans — retrying
            </p>
          )}
          {apps.length === 0 ? (
            <p className="px-5 py-6 text-center text-sm text-muted">No loans currently awaiting repayment.</p>
          ) : (
            <ul className="divide-y divide-line">
              {apps.map((app) => (
                <React.Fragment key={app.id}>
                  {app.status === "OVERDUE" && (
                    <li className="flex items-center gap-1.5 bg-error-50 px-5 py-1.5 text-xs font-semibold text-error-700">
                      <AlertTriangle size={12} /> Overdue
                    </li>
                  )}
                  <AppRow app={app} actions={() => null} />
                </React.Fragment>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
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
