"use client";

import * as React from "react";
import Link from "next/link";
import { Sparkles, XCircle, ArrowRight, Loader2, Phone, UserPlus } from "lucide-react";
import { LoanStatusTracker } from "@/components/borrower/loan-status-tracker";
import { useBorrowerJourney, type RiskCategory } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";
import { BRAND } from "@/lib/brand";

const RISK_LABEL: Record<RiskCategory, string> = {
  A: "Category A · low risk",
  B: "Category B · moderate-low risk",
  C: "Category C · moderate-high risk",
  D: "Category D · high risk",
};

export default function LoanStatusPage() {
  const mounted = useMounted();
  const j = useBorrowerJourney();
  const [deciding, setDeciding] = React.useState(false);

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const { status, riskCategory, coApplicantRequired, applicant, declineReason } = j;
  const inReview = status === "APPLIED" || status === "UNDER_REVIEW";
  const needsCoApplicant = coApplicantRequired && !applicant.coApplicant;

  const decide = () => {
    setDeciding(true);
    setTimeout(() => {
      if (riskCategory === "D") {
        j.decline("Income and bureau profile do not meet current credit policy.");
      } else {
        j.approve();
      }
      setDeciding(false);
    }, 1500);
  };

  return (
    <div className="container max-w-content py-10">
      <h1 className="mb-1">Application status</h1>
      <p className="mb-7 text-muted">
        {applicant.fullName ? `${applicant.fullName} · ` : ""}
        {status === "DECLINED" ? "Decision complete" : RISK_LABEL[riskCategory]}
      </p>

      <div className="grid gap-8 lg:grid-cols-[1fr_minmax(0,360px)]">
        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <LoanStatusTracker status={status} />
        </div>

        <div>
          {inReview && (
            <div className="rounded border border-line bg-white p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
                {deciding ? <Loader2 size={26} className="animate-spin" /> : <Sparkles size={26} />}
              </span>
              <h3 className="font-serif text-lg text-navy">{deciding ? "Running checks…" : "Under review"}</h3>
              <p className="text-sm text-muted">
                {deciding
                  ? "Verifying income, bureau and policy rules."
                  : "Our credit team is reviewing your application."}
              </p>
              {!deciding && (
                <button onClick={decide} className="btn btn-gold btn-block mt-4">
                  Simulate credit decision
                </button>
              )}
            </div>
          )}

          {status === "APPROVED" && (
            <div className="rounded border border-success-100 bg-success-50/60 p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
                <Sparkles size={26} />
              </span>
              <h3 className="font-serif text-lg text-navy">You&apos;re approved</h3>
              <p className="text-sm text-muted">Sanctioned up to</p>
              <p className="mb-4 font-serif text-3xl font-bold text-navy">{formatINR0(j.sanctionedLimit())}</p>
              {needsCoApplicant ? (
                <>
                  <p className="mb-3 flex items-center justify-center gap-1.5 text-sm font-semibold text-warning-700">
                    <UserPlus size={15} /> A co-applicant is required before disbursal
                  </p>
                  <Link href="/signup/co-applicant" className="btn btn-gold btn-block">
                    Add co-applicant <ArrowRight size={16} />
                  </Link>
                </>
              ) : (
                <Link href="/loan/apply" className="btn btn-gold btn-block">
                  Choose your amount <ArrowRight size={16} />
                </Link>
              )}
            </div>
          )}

          {status === "DECLINED" && (
            <div className="rounded border border-error-100 bg-error-50/50 p-6 text-center shadow-sm">
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-error-50 text-error-600">
                <XCircle size={26} />
              </span>
              <h3 className="font-serif text-lg text-navy">Not approved this time</h3>
              <p className="text-sm text-muted">{declineReason ?? "Your application did not meet current credit policy."}</p>
              <p className="mt-3 text-sm text-muted">
                You can reapply after 90 days. Questions?{" "}
                <a href={BRAND.phoneHref} className="inline-flex items-center gap-1 font-semibold text-navy">
                  <Phone size={13} /> {BRAND.phone}
                </a>
              </p>
            </div>
          )}

          {(status === "DOCS_SIGNED" || status === "BANK_VERIFIED" || status === "DISBURSING") && (
            <div className="rounded border border-line bg-white p-6 text-center shadow-sm">
              <h3 className="font-serif text-lg text-navy">Almost there</h3>
              <p className="mb-4 text-sm text-muted">Finish the remaining steps to receive your funds.</p>
              <Link href={status === "DOCS_SIGNED" ? "/loan/bank-verify" : "/loan/bank-verify"} className="btn btn-gold btn-block">
                Continue <ArrowRight size={16} />
              </Link>
            </div>
          )}

          {(status === "ACTIVE" || status === "REPAID" || status === "OVERDUE") && (
            <div className="rounded border border-line bg-white p-6 text-center shadow-sm">
              <h3 className="font-serif text-lg text-navy">Loan {status === "REPAID" ? "closed" : "active"}</h3>
              <p className="mb-4 text-sm text-muted">Manage everything from your dashboard.</p>
              <Link href="/dashboard" className="btn btn-gold btn-block">
                Go to dashboard <ArrowRight size={16} />
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
