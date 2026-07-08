"use client";

import { PageHeader, RefreshButton } from "@/components/staff/staff-ui";
import { StatusQueue, ReviewLookup, KycActions, PermissionGate, NoAccessNotice } from "@/components/staff/live-pipeline";

/** KYC Approver queue — clear or reject customers' KYC (live backend). */
export default function KycApprovalsPage() {
  return (
    <div>
      <PageHeader
        title="KYC approvals"
        subtitle="Review the customer's details and documents, then clear KYC to move them into credit."
      >
        <RefreshButton queryKeys={[["staff-queue"], ["staff-dashboard-stats"], ["staff-dashboard-queue"]]} />
      </PageHeader>
      <div className="space-y-8">
        <PermissionGate permission="kyc:approve" fallback={<NoAccessNotice message="Only KYC approvers can clear KYC." />}>
          <StatusQueue
            title="Applications awaiting KYC clearance"
            status="KYC_PENDING"
            actions={(app) => <KycActions app={app} />}
          />
        </PermissionGate>
        <ReviewLookup />
      </div>
    </div>
  );
}
