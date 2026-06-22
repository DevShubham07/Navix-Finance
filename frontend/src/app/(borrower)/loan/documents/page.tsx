"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FileText, ArrowRight, ArrowLeft, Eye, PenLine, Loader2 } from "lucide-react";
import { LoanCostBreakdown } from "@/components/borrower/loan-cost-breakdown";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { buildCostBreakdown, dueDateFromSalary } from "@/lib/calc/loan-math";
import { formatDate } from "@/lib/utils";

const DOCS = [
  { type: "Loan Agreement", desc: "Terms between you and the NBFC lender" },
  { type: "Sanction Letter", desc: "Your approved amount and conditions" },
  { type: "Key Fact Statement (KFS)", desc: "RBI-mandated summary of all charges" },
];

export default function LoanDocumentsPage() {
  const router = useRouter();
  const mounted = useMounted();
  const j = useBorrowerJourney();
  const [consent, setConsent] = React.useState(false);
  const [signing, setSigning] = React.useState(false);

  const due = React.useMemo(
    () => dueDateFromSalary({ disbursedOn: new Date(), salaryDay: j.applicant.salaryDay || 1 }),
    [j.applicant.salaryDay],
  );
  const tenureDays = Math.max(1, Math.round((due.getTime() - Date.now()) / 864e5));
  const breakdown = buildCostBreakdown(j.chosenAmount || 0, tenureDays);

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  if (!j.chosenAmount) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Choose an amount first</h1>
          <Link href="/loan/apply" className="btn btn-gold mt-3">Choose amount <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const sign = () => {
    if (!consent) return;
    setSigning(true);
    setTimeout(() => {
      j.signDocuments();
      router.push("/loan/bank-verify");
    }, 1400);
  };

  return (
    <div className="container max-w-container py-10">
      <h1 className="mb-1">Review &amp; sign</h1>
      <p className="mb-7 text-muted">Here&apos;s your full cost, with nothing hidden. Read your documents, then e-sign.</p>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,360px)_1fr]">
        <div>
          <LoanCostBreakdown breakdown={breakdown} dueDate={formatDate(due)} />
        </div>

        <div>
          <ul className="mb-5 divide-y divide-grey-200 rounded border border-line bg-white">
            {DOCS.map((d) => (
              <li key={d.type} className="flex items-center gap-3 p-4">
                <span className="grid h-10 w-10 flex-shrink-0 place-items-center rounded bg-navy-tint text-navy">
                  <FileText size={18} />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-semibold text-ink">{d.type}</div>
                  <div className="truncate text-xs text-muted">{d.desc}</div>
                </div>
                <button type="button" className="flex items-center gap-1 text-sm font-semibold text-navy hover:text-navy-700">
                  <Eye size={15} /> View
                </button>
              </li>
            ))}
          </ul>

          <label className="checkbox mb-5">
            <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
            <span>I have read and accept the Loan Agreement, Sanction Letter and Key Fact Statement.</span>
          </label>

          <button onClick={sign} disabled={!consent || signing} className="btn btn-gold btn-block">
            {signing ? <Loader2 size={16} className="animate-spin" /> : <PenLine size={16} />}
            {signing ? "e-Signing with Aadhaar OTP…" : "e-Sign all documents"}
          </button>

          <Link href="/loan/apply" className="mt-4 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
            <ArrowLeft size={15} /> Change amount
          </Link>
        </div>
      </div>
    </div>
  );
}
