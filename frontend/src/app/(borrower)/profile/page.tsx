"use client";

import { useRouter } from "next/navigation";
import { LogOut, ShieldCheck } from "lucide-react";
import { InfoRow, SummarySection } from "@/components/borrower/summary";
import { KycProgress } from "@/components/borrower/kyc-progress";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { signOutBorrower } from "@/lib/mock/session";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

export default function ProfilePage() {
  const router = useRouter();
  const mounted = useMounted();
  const j = useBorrowerJourney();

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const a = j.applicant;
  const signOut = () => {
    signOutBorrower();
    router.push("/login");
  };

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="mb-0">{a.fullName || "Your profile"}</h1>
          <p className="mt-1 text-muted">{a.email || "Verified borrower"}</p>
        </div>
        {/* Risk category (A/B/C/D) and credit score are staff-only signals — never shown to the
            borrower (product rule: one price for all). */}
      </div>

      <div className="grid gap-4">
        <SummarySection title="Identity">
          <InfoRow label="Full name" value={a.fullName} />
          <InfoRow label="PAN" value={a.pan} />
          <InfoRow label="Mobile" value={<span className="flex items-center justify-end gap-1.5">{a.mobile} {a.mobileVerified && <ShieldCheck size={14} className="text-success-600" />}</span>} />
        </SummarySection>

        <SummarySection title="Employment & income">
          <InfoRow label="Employer" value={a.employer} />
          <InfoRow label="Designation" value={a.designation} />
          <InfoRow label="Monthly salary" value={a.monthlySalary ? formatINR0(a.monthlySalary) : "—"} />
          <InfoRow label="Salary day" value={a.salaryDay ? `${a.salaryDay} of each month` : "—"} />
        </SummarySection>

        <SummarySection title="Bank & address">
          <InfoRow label="Bank" value={a.bankName} />
          <InfoRow label="Account" value={a.accountLast4 ? `•••• ${a.accountLast4}` : "—"} />
          <InfoRow label="IFSC" value={a.ifsc} />
          <InfoRow label="Address" value={[a.addressLine, a.city, a.pin].filter(Boolean).join(", ")} />
        </SummarySection>

        <div>
          <div className="mb-2 text-sm font-semibold text-navy">KYC status</div>
          <KycProgress kyc={j.kyc} />
        </div>
      </div>

      <button onClick={signOut} className="btn btn-outline mt-7">
        <LogOut size={16} /> Sign out
      </button>
    </div>
  );
}
