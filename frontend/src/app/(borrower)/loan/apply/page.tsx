"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, ArrowLeft, AlertTriangle } from "lucide-react";
import { AmountChooser } from "@/components/borrower/amount-chooser";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";

export default function LoanApplyPage() {
  const router = useRouter();
  const mounted = useMounted();
  const j = useBorrowerJourney();
  const limit = j.sanctionedLimit();
  const [amount, setAmount] = React.useState(0);

  React.useEffect(() => {
    if (mounted) setAmount(j.chosenAmount && j.chosenAmount <= limit ? j.chosenAmount : limit);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mounted, limit]);

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const eligibleToApply = j.status === "APPROVED" || j.status === "DOCS_SIGNED";
  const needsCoApplicant = j.coApplicantRequired && !j.applicant.coApplicant;

  if (!eligibleToApply) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Finish your review first</h1>
          <p className="mb-5 text-muted">You&apos;ll be able to choose an amount once your application is approved.</p>
          <Link href="/loan/status" className="btn btn-gold">Check application status <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const proceed = () => {
    j.chooseAmount(amount);
    router.push("/loan/documents");
  };

  return (
    <div className="container max-w-container py-10">
      <h1 className="mb-1">Choose your amount</h1>
      <p className="mb-7 text-muted">Drag to set your advance. The full cost updates live — no hidden charges.</p>

      {needsCoApplicant && (
        <div className="mb-6 flex items-start gap-3 rounded border border-warning-100 bg-warning-50 p-4 text-sm text-warning-800">
          <AlertTriangle size={18} className="mt-0.5 flex-shrink-0" />
          <p className="m-0">
            Your risk category requires a co-applicant before disbursal.{" "}
            <Link href="/signup/co-applicant" className="font-semibold underline">Add one now</Link> to continue.
          </p>
        </div>
      )}

      <AmountChooser limit={limit} salaryDay={j.applicant.salaryDay || 1} value={amount} onChange={setAmount} />

      <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Link href="/loan/status" className="btn btn-outline btn-sm order-2 sm:order-1"><ArrowLeft size={16} /> Back</Link>
        <button onClick={proceed} disabled={needsCoApplicant || amount <= 0} className="btn btn-gold order-1 sm:order-2">
          Accept &amp; review documents <ArrowRight size={16} />
        </button>
      </div>
    </div>
  );
}
