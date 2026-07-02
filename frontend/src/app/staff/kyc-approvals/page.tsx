"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import {
  StatusQueue,
  ReviewLookup,
  KycActions,
  KycCreditActions,
  PermissionGate,
  NoAccessNotice,
} from "@/components/staff/live-pipeline";

/** KYC Approver queue — clear or reject customers' KYC + fast-path credit approval (live backend). */
export default function KycApprovalsPage() {
  return (
    <div>
      <PageHeader
        title="KYC approvals"
        subtitle="Review the customer's details and documents, clear KYC, then approve instant loans for disbursement."
      />
      <div className="space-y-8">
        <PermissionGate permission="kyc:approve" fallback={<NoAccessNotice message="Only KYC approvers can clear KYC." />}>
          <StatusQueue
            title="Applications awaiting KYC clearance"
            status="KYC_PENDING"
            actions={(app) => <KycActions app={app} />}
          />
          {/* Instant-loan credit fast-path: KYC-approved applications the borrower has applied on. */}
          <StatusQueue
            title="Approve instant loans (credit clearance)"
            status="KYC_APPROVED"
            info="KYC-approved applications the borrower has chosen an amount on. Approve to send straight to the Disbursement Head — no separate credit review needed."
            filter={(app) => app.amountRequestedPaise != null}
            actions={(app) => <KycCreditActions app={app} />}
          />
        </PermissionGate>
        <ReviewLookup />
      </div>
    </div>
  );
}
