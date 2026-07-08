"use client";

import Link from "next/link";
import { Receipt } from "lucide-react";
import { PageHeader, RefreshButton } from "@/components/staff/staff-ui";
import { StatusQueue, AccountantActions, PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe } from "@/components/staff/live-pipeline";
import { RepaymentVerifyQueue } from "@/components/staff/repayment-verify-queue";

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
      <PageHeader title="Accounting · transfers & repayments" subtitle="Confirm bank transfers to activate loans, and verify borrower repayments.">
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
        <RefreshButton queryKeys={[["staff-queue"], ["staff-dashboard-stats"], ["staff-dashboard-queue"], ["staff-pending-repayments"]]} />
        <Link href="/staff/accounting/transactions" className="inline-flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-sm font-semibold text-navy hover:bg-grey-100">
          <Receipt size={15} /> All transactions
        </Link>
      </PageHeader>
      <PermissionGate permission="loan:activate" fallback={<NoAccessNotice />}>
        <div className="space-y-8">
          <StatusQueue
            title="Transfers to confirm"
            status="ACCOUNTANT_PENDING"
            actions={(app) => <AccountantActions app={app} />}
            info="Disbursals the Disbursement Head released without a transaction id. Confirm the bank transfer landed to mint & activate the loan, or mark it failed to send it back for retry."
          />
          <RepaymentVerifyQueue />
        </div>
      </PermissionGate>
    </div>
  );
}
