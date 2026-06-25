"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import { StatusQueue, DisbursementActions, PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe } from "@/components/staff/live-pipeline";

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
      <PermissionGate permission="loan:disburse" fallback={<NoAccessNotice />}>
        <div className="space-y-8">
          <StatusQueue
            title="Disbursement pending"
            status="DISBURSEMENT_PENDING"
            actions={(app) => <DisbursementActions app={app} />}
            info="Credit-approved loans awaiting release. Enter the bank/UPI transaction id to release & activate the loan immediately; approve without one to route it to the accountant to confirm the transfer."
          />
          <StatusQueue
            title="Disbursement failed — retry"
            status="DISBURSEMENT_FAILED"
            actions={(app) => <DisbursementActions app={app} />}
            info="Transfers the accountant marked as failed. Re-release them here once the bank issue is resolved."
          />
        </div>
      </PermissionGate>
    </div>
  );
}
