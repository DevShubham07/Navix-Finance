"use client";

import { useRouter } from "next/navigation";
import { LogOut, ShieldCheck } from "lucide-react";
import { InfoRow, SummarySection } from "@/components/borrower/summary";
import { KycProgress } from "@/components/borrower/kyc-progress";
import { useOnboarding } from "@/lib/onboarding";
import { useLiveApplication, logoutBorrower } from "@/lib/api/live-journey";
import type { ApplicationStatus } from "@/lib/api/applications";
import type { KycCheck, KycState } from "@/lib/domain/borrower";
import { formatINR0 } from "@/lib/utils";

/** Statuses at/after KYC clearance — all checks read as verified. */
const KYC_CLEARED: ApplicationStatus[] = [
  "KYC_APPROVED", "PRE_APPROVED", "CREDIT_EXEC_PENDING", "CREDIT_EXEC_APPROVED",
  "CREDIT_HEAD_PENDING", "CREDIT_HEAD_APPROVED", "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING",
  "DISBURSEMENT_FAILED", "DISBURSED", "ACTIVE", "OVERDUE", "DEFAULTED", "CLOSED", "WRITTEN_OFF",
];

/** Map the live application lifecycle stage to the 5-check KYC widget state. */
function kycFromStatus(status: ApplicationStatus | undefined): KycState {
  let check: KycCheck = "PENDING";
  if (status === "KYC_PENDING" || status === "REVIEW_PENDING") check = "IN_PROGRESS";
  else if (status === "KYC_REJECTED") check = "FAILED";
  else if (status && KYC_CLEARED.includes(status)) check = "VERIFIED";
  return { pan: check, aadhaar: check, selfie: check, address: check, bank: check };
}

export default function ProfilePage() {
  const router = useRouter();
  const { mounted, draft } = useOnboarding();
  const { app } = useLiveApplication();

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const accountLast4 = draft.accountNumber ? draft.accountNumber.replace(/\D/g, "").slice(-4) : "";
  const signOut = async () => {
    await logoutBorrower();
    router.push("/login");
  };

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="mb-0">{draft.fullName || "Your profile"}</h1>
          <p className="mt-1 text-muted">{draft.personalEmail || "Verified borrower"}</p>
        </div>
        {/* Risk category (A/B/C/D) and credit score are staff-only signals — never shown to the
            borrower (product rule: one price for all). */}
      </div>

      <div className="grid gap-4">
        <SummarySection title="Identity">
          <InfoRow label="Full name" value={draft.fullName} />
          <InfoRow label="PAN" value={draft.pan} />
          <InfoRow label="Mobile" value={<span className="flex items-center justify-end gap-1.5">{draft.mobile} {draft.mobile && <ShieldCheck size={14} className="text-success-600" />}</span>} />
        </SummarySection>

        <SummarySection title="Employment & income">
          <InfoRow label="Employer" value={draft.employer} />
          <InfoRow label="Monthly salary" value={draft.monthlySalary ? formatINR0(draft.monthlySalary) : "—"} />
          <InfoRow label="Salary day" value={draft.salaryDay ? `${draft.salaryDay} of each month` : "—"} />
        </SummarySection>

        <SummarySection title="Bank & address">
          <InfoRow label="Bank" value={draft.bankName} />
          <InfoRow label="Account" value={accountLast4 ? `•••• ${accountLast4}` : "—"} />
          <InfoRow label="IFSC" value={draft.ifsc} />
          <InfoRow label="Address" value={draft.address} />
        </SummarySection>

        <div>
          <div className="mb-2 text-sm font-semibold text-navy">KYC status</div>
          <KycProgress kyc={kycFromStatus(app?.status)} />
        </div>
      </div>

      <button onClick={signOut} className="btn btn-outline mt-7">
        <LogOut size={16} /> Sign out
      </button>
    </div>
  );
}
