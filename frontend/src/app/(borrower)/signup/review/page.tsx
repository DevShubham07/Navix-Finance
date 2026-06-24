"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle } from "lucide-react";
import { InfoRow, SummarySection } from "@/components/borrower/summary";
import { Button } from "@/components/ui";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { signInBorrower } from "@/lib/mock/session";
import { useMounted } from "@/hooks/use-mounted";
import { eligibleLimit } from "@/lib/calc/loan-math";
import { formatINR0 } from "@/lib/utils";
import { submitOnboarding } from "@/lib/api/live-journey";
import { ApplicationApiError } from "@/lib/api/applications";

export default function SignupReviewPage() {
  const router = useRouter();
  const mounted = useMounted();
  const { applicant } = useBorrowerJourney();
  const [consent, setConsent] = React.useState(false);
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const submit = async () => {
    if (!consent) return;
    setSubmitting(true);
    setError(undefined);
    signInBorrower(applicant.fullName || "Applicant", applicant.mobile || "98765 43210");
    try {
      // Persist to the real backend: create DRAFT -> save KYC profile -> submit KYC.
      await submitOnboarding(applicant);
      router.push("/kyc");
    } catch (e) {
      setError(
        e instanceof ApplicationApiError
          ? `${e.message} (${e.code})`
          : e instanceof Error
            ? e.message
            : "Something went wrong submitting your application.",
      );
      setSubmitting(false);
    }
  };

  if (!mounted) {
    return <div className="form-card text-muted">Loading your application…</div>;
  }

  const a = applicant;
  const acct = a.accountLast4 ? `•••• ${a.accountLast4}` : "—";

  return (
    <div>
      <p className="lead mb-5">Check everything is right before you submit. You can edit any section.</p>

      <div className="grid gap-4">
        <SummarySection title="Identity" editHref="/signup/pan">
          <InfoRow label="Full name" value={a.fullName} />
          <InfoRow label="PAN" value={a.pan} />
          <InfoRow label="Mobile" value={a.mobile} />
        </SummarySection>

        <SummarySection title="Employment" editHref="/signup/employment">
          <InfoRow label="Employer" value={a.employer} />
          <InfoRow label="Designation" value={a.designation} />
          <InfoRow label="UAN" value={a.uan} />
        </SummarySection>

        <SummarySection title="Income" editHref="/signup/salary">
          <InfoRow label="Monthly salary" value={a.monthlySalary ? formatINR0(a.monthlySalary) : "—"} />
          <InfoRow label="Salary day" value={a.salaryDay ? `${a.salaryDay} of each month` : "—"} />
          <InfoRow
            label="Eligible limit (25%)"
            value={a.monthlySalary ? <span className="text-navy">{formatINR0(eligibleLimit(a.monthlySalary))}</span> : "—"}
          />
        </SummarySection>

        <SummarySection title="Contact & bank" editHref="/signup/bank">
          <InfoRow label="Email" value={a.email} />
          <InfoRow label="Bank" value={a.bankName} />
          <InfoRow label="Account" value={acct} />
          <InfoRow label="IFSC" value={a.ifsc} />
        </SummarySection>

        <SummarySection title="Co-applicant" editHref="/signup/co-applicant">
          <InfoRow
            label="Co-applicant"
            value={a.coApplicant ? `${a.coApplicant.fullName} (${a.coApplicant.relationship})` : "None"}
          />
        </SummarySection>

        <SummarySection title="Address" editHref="/signup/address-proof">
          <InfoRow label="Address" value={[a.addressLine, a.city, a.pin].filter(Boolean).join(", ")} />
        </SummarySection>
      </div>

      <label className="checkbox mt-5">
        <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
        <span>
          I confirm these details are accurate and authorise NAVIX and its NBFC partner to verify them and run a
          credit check.
        </span>
      </label>

      {error && (
        <div className="mt-4 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /> {error}
        </div>
      )}

      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <a href="/signup/address-proof" className="btn btn-outline btn-sm order-2 sm:order-1">Back</a>
        <Button variant="gold" onClick={submit} disabled={!consent} isLoading={submitting} className="order-1 sm:order-2">
          Submit application
        </Button>
      </div>
    </div>
  );
}
