"use client";

import * as React from "react";
import Link from "next/link";
import { ShieldCheck, ArrowRight } from "lucide-react";
import { KycProgress } from "@/components/borrower/kyc-progress";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";

export default function KycPage() {
  const mounted = useMounted();
  const kyc = useBorrowerJourney((s) => s.kyc);
  const done = mounted && kyc.aadhaar === "VERIFIED" && kyc.selfie === "VERIFIED";

  return (
    <div className="container max-w-content py-10">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gold-dark">
        <ShieldCheck size={14} /> Identity verification
      </div>
      <h1 className="mb-2">Complete your KYC</h1>
      <p className="lead mb-6 text-muted">
        A quick, government-backed identity check. Most of it is already done from your application — just
        Aadhaar and a selfie remain.
      </p>

      {mounted ? <KycProgress kyc={kyc} className="mb-6" /> : <div className="mb-6 h-64 rounded border border-line bg-white" />}

      <Link href={done ? "/loan/status" : "/kyc/digilocker"} className="btn btn-gold btn-block">
        {done ? "Continue" : "Verify with DigiLocker"} <ArrowRight size={16} />
      </Link>
      <p className="mt-3 text-center text-xs text-muted">
        Powered by DigiLocker · your documents are shared with consent and encrypted in transit.
      </p>
    </div>
  );
}
