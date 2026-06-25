"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, ArrowLeft, Loader2, AlertTriangle } from "lucide-react";
import { AmountChooser } from "@/components/borrower/amount-chooser";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useLiveApplication, applyForAmount, canChooseAmount } from "@/lib/api/live-journey";
import { borrowerApi, rupeesToPaise, ApplicationApiError } from "@/lib/api/applications";
import { eligibleLimit } from "@/lib/calc/loan-math";

export default function LoanApplyPage() {
  const router = useRouter();
  const { appId, app, isLoading } = useLiveApplication();
  const j = useBorrowerJourney();
  const [amount, setAmount] = React.useState(0);
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string>();

  // Eligible limit = 25% of monthly salary (the backend gate). Prefer the
  // persisted profile; fall back to the in-browser wizard value.
  const profileQuery = useQuery({
    queryKey: ["live-profile", appId],
    queryFn: () => borrowerApi.getProfile(appId as number),
    enabled: appId != null,
  });
  const salaryPaise =
    profileQuery.data?.monthlySalaryPaise ??
    (j.applicant.monthlySalary ? rupeesToPaise(j.applicant.monthlySalary) : 0);
  const salaryRupees = Math.round(salaryPaise / 100);
  const limit = eligibleLimit(salaryRupees);
  const salaryDay = j.applicant.salaryDay || 1;

  React.useEffect(() => {
    if (limit > 0) setAmount((prev) => (prev > 0 && prev <= limit ? prev : limit));
  }, [limit]);

  // Still resolving the live application.
  if (appId == null || (isLoading && !app)) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 animate-pulse rounded border border-line bg-white" />
      </div>
    );
  }

  // Only KYC-approved-and-not-yet-applied applications can choose an amount.
  if (!canChooseAmount(app)) {
    const active = app?.status === "ACTIVE";
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">{active ? "Your advance is active" : "Almost there"}</h1>
          <p className="mb-5 text-muted">
            {active
              ? "Manage your advance from your dashboard."
              : "You'll be able to choose an amount once your KYC is approved. Track progress on your status page."}
          </p>
          <Link href={active ? "/dashboard" : "/loan/status"} className="btn btn-gold">
            {active ? "Go to dashboard" : "Check application status"} <ArrowRight size={16} />
          </Link>
        </div>
      </div>
    );
  }

  const proceed = async () => {
    if (amount <= 0 || appId == null) return;
    setSubmitting(true);
    setError(undefined);
    try {
      await applyForAmount(appId, { amountRupees: amount, salaryDay, monthlySalary: salaryRupees });
      j.chooseAmount(amount); // keep the mock store in sync for cosmetic pages
      router.push("/loan/status");
    } catch (e) {
      setError(
        e instanceof ApplicationApiError
          ? `${e.message} (${e.code})`
          : e instanceof Error
            ? e.message
            : "Could not submit your amount.",
      );
      setSubmitting(false);
    }
  };

  return (
    <div className="container max-w-container py-10">
      <h1 className="mb-1">Choose your amount</h1>
      <p className="mb-7 text-muted">Drag to set your advance. The full cost updates live — no hidden charges.</p>

      {limit <= 0 ? (
        <div className="mb-6 flex items-start gap-3 rounded border border-warning-100 bg-warning-50 p-4 text-sm text-warning-800">
          <AlertTriangle size={18} className="mt-0.5 flex-shrink-0" />
          <p className="m-0">We couldn&apos;t determine your eligible limit. Please ensure your salary details are filled in.</p>
        </div>
      ) : (
        <AmountChooser limit={limit} salaryDay={salaryDay} value={amount} onChange={setAmount} />
      )}

      {error && (
        <div className="mt-6 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /> {error}
        </div>
      )}

      <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Link href="/loan/status" className="btn btn-outline btn-sm order-2 sm:order-1">
          <ArrowLeft size={16} /> Back
        </Link>
        <button
          onClick={proceed}
          disabled={submitting || amount <= 0 || limit <= 0}
          className="btn btn-gold order-1 sm:order-2"
        >
          {submitting ? <Loader2 size={16} className="animate-spin" /> : null}
          Submit amount <ArrowRight size={16} />
        </button>
      </div>
    </div>
  );
}
