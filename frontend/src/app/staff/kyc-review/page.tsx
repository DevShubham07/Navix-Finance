"use client";

import { PageHeader, RefreshButton } from "@/components/staff/staff-ui";
import { StatusQueue, ReviewActions, PermissionGate, NoAccessNotice } from "@/components/staff/live-pipeline";

/**
 * Reborrow review queue (KYC Approver) — returning borrowers flagged for a PAST overdue. Kept
 * SEPARATE from the fresh-customer KYC queue: every reborrow by a borrower who was ever delinquent
 * lands here for a manual clear (→ pre-approved) or reject before it can proceed.
 */
export default function KycReviewPage() {
  return (
    <div>
      <PageHeader
        title="Reborrow reviews"
        subtitle="Returning borrowers with a past overdue. Review their history and clear them to borrow again, or reject."
      >
        <RefreshButton queryKeys={[["staff-queue"], ["staff-dashboard-stats"], ["staff-dashboard-queue"]]} />
      </PageHeader>
      <PermissionGate
        permission="kyc:approve"
        fallback={<NoAccessNotice message="Only KYC approvers can review returning borrowers." />}
      >
        <StatusQueue
          title="Returning borrowers awaiting review"
          status="REVIEW_PENDING"
          actions={(app) => <ReviewActions app={app} />}
          info="These borrowers had at least one overdue repayment in the past, so each new advance needs a KYC approver's sign-off before it proceeds to disbursement."
          // The clear/reject decision hinges on past delinquency — keep the loan
          // history inline on the decision surface (documented AppRow exception).
          withLoanHistory
        />
      </PermissionGate>
    </div>
  );
}
