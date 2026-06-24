"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import { StatusQueue, AccountantActions, ROLE_LABEL, useStaffMe } from "@/components/staff/live-pipeline";

/**
 * Accountant queue (live). Validate that the bank transfer went out — success
 * mints the loan and activates it (DISBURSED → ACTIVE); failure routes back to
 * the disbursement head for retry.
 */
export default function AccountingPage() {
  const me = useStaffMe();
  const role = me.data?.role;
  return (
    <div>
      <PageHeader title="Accounting · transfer confirmation" subtitle="Confirm completed bank transfers to activate loans.">
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
      </PageHeader>
      <div className="space-y-8">
        <StatusQueue title="Transfers to confirm" status="ACCOUNTANT_PENDING" actions={(app) => <AccountantActions app={app} />} />
      </div>
    </div>
  );
}
