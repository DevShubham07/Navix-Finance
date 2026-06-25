"use client";

import * as React from "react";
import { Lock } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import type { StaffRole } from "@/lib/auth/rbac";
import {
  useStaffMe,
  ReviewLookup,
  StatusQueue,
  CreditQueuePanel,
  KycActions,
  ExecActions,
  HeadActions,
  DisbursementActions,
  AccountantActions,
  ROLE_LABEL,
  PIPELINE_ROLES,
} from "@/components/staff/live-pipeline";

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
  const isPipeline = PIPELINE_ROLES.includes(role) || role === "ADMIN";

  return (
    <div>
      <PageHeader
        title="Live applications"
        subtitle="Real backend state machine. Act on the queue for your role to walk loans to ACTIVE."
      >
        <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>
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
            above to open any application by ID and view the applicant&apos;s details and documents.
          </div>
        )}
      </div>
    </div>
  );
}

/** Role -> the queue panels it sees (ADMIN sees them all). */
function RoleQueues({ role }: { role: StaffRole }) {
  const showAll = role === "ADMIN";
  return (
    <div className="space-y-8">
      {(showAll || role === "KYC_APPROVER") && (
        <StatusQueue title="KYC pending" status="KYC_PENDING" actions={(app) => <KycActions app={app} />} />
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
    </div>
  );
}
