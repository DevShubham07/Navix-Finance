"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Loader2 } from "lucide-react";
import { InfoRow, SummarySection } from "@/components/borrower/summary";
import { Button } from "@/components/ui";
import { StepStatusPill } from "@/components/borrower/step-result-banner";
import { useOnboarding } from "@/lib/onboarding";
import { borrowerApi, verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";
import { eligibleLimit } from "@/lib/calc/loan-math";
import { formatINR0 } from "@/lib/utils";

/** Friendly label for a backend check type (e.g. "PENNY_DROP" → "Bank verification"). */
function checkLabel(checkType: string): string {
  const map: Record<string, string> = {
    PAN: "PAN verification",
    EMAIL: "Email & employer",
    ADDRESS: "Address",
    DIGILOCKER: "DigiLocker KYC",
    BUREAU: "Credit check",
    SALARY: "Salary & payslip",
    PENNY_DROP: "Bank verification",
    SELFIE: "Selfie",
    AGREEMENT: "Agreements",
  };
  return (
    map[checkType] ??
    checkType
      .toLowerCase()
      .split("_")
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(" ")
  );
}

/** The wizard step a verification check maps to — so a failed check can be retried on its own. */
function stepRoute(checkType: string): string | null {
  const map: Record<string, string> = {
    PAN: "/signup/pan",
    EMAIL: "/signup/email",
    ADDRESS: "/signup/address",
    DIGILOCKER: "/signup/digilocker",
    AADHAAR: "/signup/digilocker", // Aadhaar is fetched via DigiLocker
    BUREAU: "/signup/bureau",
    SALARY: "/signup/salary",
    PENNY_DROP: "/signup/penny-drop",
    SELFIE: "/signup/selfie",
    AGREEMENT: "/signup/agreement",
  };
  return map[checkType] ?? null;
}

export default function SignupReviewPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [incomplete, setIncomplete] = React.useState<string[]>([]); // pending check types

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const summaryQuery = useQuery({
    queryKey: ["verify-summary", appId],
    queryFn: () => verificationApi.summary(appId as number),
    enabled: appId != null,
  });
  const summary: StepResult[] = summaryQuery.data ?? [];

  const submit = async () => {
    if (appId == null) return;
    setSubmitting(true);
    setError(undefined);
    setIncomplete([]);
    try {
      await borrowerApi.submitKyc(appId);
      router.push("/loan/status");
    } catch (e) {
      if (e instanceof ApplicationApiError && e.code === "KYC_INCOMPLETE") {
        const fresh = await verificationApi.summary(appId).catch(() => summary);
        const pending = fresh.filter((s) => s.status !== "PASS" && s.status !== "REVIEW").map((s) => s.checkType);
        setIncomplete(pending);
        setError(
          pending.length
            ? "A few steps still need attention — retry just those below, then submit again."
            : "Please complete the remaining verification steps before submitting.",
        );
      } else {
        setError(e instanceof ApplicationApiError ? `${e.message} (${e.code})` : "Something went wrong submitting your application.");
      }
      setSubmitting(false);
    }
  };

  if (!mounted) {
    return <div className="form-card text-muted">Loading your application…</div>;
  }

  const acct = draft.accountNumber ? `•••• ${draft.accountNumber.slice(-4)}` : "—";

  return (
    <div>
      <p className="lead mb-5">Check everything is right before you submit. You can edit any section.</p>

      <div className="grid gap-4">
        <SummarySection title="Identity" editHref="/signup/pan?return=review">
          <InfoRow label="Full name" value={draft.fullName} />
          <InfoRow label="PAN" value={draft.pan} />
          <InfoRow label="Aadhaar" value={draft.aadhaar} />
          <InfoRow label="Mobile" value={draft.mobile} />
        </SummarySection>

        <SummarySection title="Employment & email" editHref="/signup/email?return=review">
          <InfoRow label="Employer" value={draft.employer} />
          <InfoRow label="Personal email" value={draft.personalEmail} />
          <InfoRow label="Official email" value={draft.officialEmail} />
        </SummarySection>

        <SummarySection title="Income" editHref="/signup/salary?return=review">
          <InfoRow label="Monthly salary" value={draft.monthlySalary ? formatINR0(draft.monthlySalary) : "—"} />
          <InfoRow label="Salary day" value={draft.salaryDay ? `${draft.salaryDay} of each month` : "—"} />
          <InfoRow
            label="Eligible limit (25%)"
            value={draft.monthlySalary ? <span className="text-navy">{formatINR0(eligibleLimit(draft.monthlySalary))}</span> : "—"}
          />
        </SummarySection>

        <SummarySection title="Bank" editHref="/signup/penny-drop?return=review">
          <InfoRow label="Bank" value={draft.bankName} />
          <InfoRow label="Account" value={acct} />
          <InfoRow label="IFSC" value={draft.ifsc} />
        </SummarySection>

        <SummarySection title="Address" editHref="/signup/address?return=review">
          <InfoRow label="Address" value={draft.address || "—"} />
        </SummarySection>
      </div>

      <div className="mt-5 rounded border border-line bg-white">
        <div className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">Verification status</div>
        <ul className="divide-y divide-grey-200">
          {summaryQuery.isLoading ? (
            <li className="flex items-center gap-2 px-4 py-3 text-sm text-muted">
              <Loader2 size={14} className="animate-spin" /> Loading your verification status…
            </li>
          ) : summary.length === 0 ? (
            <li className="px-4 py-3 text-sm text-muted">No verification steps recorded yet.</li>
          ) : (
            summary.map((s) => {
              const route = stepRoute(s.checkType);
              const needsRetry = s.status !== "PASS" && s.status !== "REVIEW";
              return (
                <li key={s.checkType} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                  <span className="text-ink">{checkLabel(s.checkType)}</span>
                  <div className="flex items-center gap-3">
                    {needsRetry && route && (
                      <a href={`${route}?return=review`} className="text-xs font-semibold text-gold-dark hover:underline">
                        Retry this step
                      </a>
                    )}
                    <StepStatusPill status={s.status} />
                  </div>
                </li>
              );
            })
          )}
        </ul>
        <p className="border-t border-line px-4 py-3 text-xs text-muted">
          Couldn&apos;t finish DigiLocker? You can still submit — our team will verify your Aadhaar
          manually. Any other step that didn&apos;t go through can be retried on its own above.
        </p>
      </div>

      {incomplete.length > 0 && (
        <div className="mt-4 rounded border border-warning-100 bg-warning-50 p-4 text-sm text-warning-800">
          <div className="mb-2 flex items-center gap-2 font-semibold"><AlertTriangle size={16} /> A few steps still need attention</div>
          <ul className="divide-y divide-warning-100/70">
            {incomplete.map((ct) => {
              const route = stepRoute(ct);
              return (
                <li key={ct} className="flex items-center justify-between gap-3 py-1.5">
                  <span>{checkLabel(ct)}</span>
                  {route && (
                    <a href={`${route}?return=review`} className="font-semibold text-warning-800 underline hover:no-underline">
                      Retry this step
                    </a>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}

      {error && incomplete.length === 0 && (
        <div className="mt-4 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /> {error}
        </div>
      )}

      <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <a href="/signup/agreement" className="btn btn-outline btn-sm order-2 sm:order-1">Back</a>
        <Button variant="gold" onClick={submit} isLoading={submitting} className="order-1 sm:order-2">
          Submit application
        </Button>
      </div>
    </div>
  );
}
