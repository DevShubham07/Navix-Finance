"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import { StatusQueue, ReviewLookup, KycActions } from "@/components/staff/live-pipeline";

/** KYC Approver queue — clear or reject applicants' KYC (live backend). */
export default function KycApprovalsPage() {
  return (
    <div>
      <PageHeader
        title="KYC approvals"
        subtitle="Review the applicant's details and documents, then clear KYC to move them into credit."
      />
      <div className="space-y-8">
        <StatusQueue
          title="Applications awaiting KYC clearance"
          status="KYC_PENDING"
          actions={(app) => <KycActions app={app} />}
        />
        <ReviewLookup />
      </div>
    </div>
  );
}
