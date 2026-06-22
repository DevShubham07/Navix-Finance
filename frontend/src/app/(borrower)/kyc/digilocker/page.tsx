"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, Lock, ArrowRight, Loader2 } from "lucide-react";
import { useBorrowerJourney } from "@/lib/mock/borrower";

export default function KycDigiLockerPage() {
  const router = useRouter();
  const setKyc = useBorrowerJourney((s) => s.setKyc);
  const [redirecting, setRedirecting] = React.useState(false);

  const connect = () => {
    setRedirecting(true);
    setKyc({ aadhaar: "IN_PROGRESS" });
    setTimeout(() => router.push("/kyc/digilocker/callback"), 1100);
  };

  return (
    <div className="container max-w-content py-12">
      <div className="mx-auto max-w-md rounded-lg border border-line bg-white p-8 text-center shadow-md">
        <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy text-white">
          <Building2 size={32} />
        </span>
        <h1 className="text-2xl">Connect DigiLocker</h1>
        <p className="mb-6 text-muted">
          You&apos;ll head to DigiLocker to share your Aadhaar securely. We only receive the details you
          approve — never your password.
        </p>

        <ul className="mb-6 space-y-2 text-left text-sm">
          {["Government of India digital identity", "Consent-based, read-only access", "Bank-grade encryption"].map((t) => (
            <li key={t} className="flex items-center gap-2 text-ink">
              <Lock size={15} className="text-success-600" /> {t}
            </li>
          ))}
        </ul>

        <button onClick={connect} disabled={redirecting} className="btn btn-gold btn-block">
          {redirecting ? <Loader2 size={16} className="animate-spin" /> : null}
          {redirecting ? "Redirecting to DigiLocker…" : "Continue with DigiLocker"}
          {!redirecting && <ArrowRight size={16} />}
        </button>
        <button onClick={() => router.push("/kyc")} className="mt-3 text-sm text-muted hover:text-navy">
          Back to KYC
        </button>
      </div>
    </div>
  );
}
