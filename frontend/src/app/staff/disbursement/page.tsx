"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import { StatusQueue, DisbursementActions, ROLE_LABEL, useStaffMe } from "@/components/staff/live-pipeline";

/**
 * Disbursement Head queue (live). Accept credit-approved applications for
 * release — the final maker-checker step before the accountant validates the
 * transfer. Failed transfers can be retried back into the accountant queue.
 */
export default function DisbursementPage() {
  const me = useStaffMe();
  const role = me.data?.role;
  return (
    <div>
      <PageHeader title="Disbursement authorisation" subtitle="Authorise release of credit-approved loans for payout.">
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
      </PageHeader>
      <div className="space-y-8">
        <StatusQueue title="Disbursement pending" status="DISBURSEMENT_PENDING" actions={(app) => <DisbursementActions app={app} />} />
        <StatusQueue title="Disbursement failed — retry" status="DISBURSEMENT_FAILED" actions={(app) => <DisbursementActions app={app} />} />
      </div>
    </div>
  );
}
