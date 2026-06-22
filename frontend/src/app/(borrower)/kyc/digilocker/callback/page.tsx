"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2, CheckCircle2, ArrowRight } from "lucide-react";
import { useBorrowerJourney } from "@/lib/mock/borrower";

export default function KycDigiLockerCallbackPage() {
  const router = useRouter();
  const setKyc = useBorrowerJourney((s) => s.setKyc);
  const [done, setDone] = React.useState(false);

  React.useEffect(() => {
    const t = setTimeout(() => {
      setKyc({ aadhaar: "VERIFIED" });
      setDone(true);
    }, 1600);
    return () => clearTimeout(t);
  }, [setKyc]);

  return (
    <div className="container max-w-content py-16">
      <div className="mx-auto max-w-md rounded-lg border border-line bg-white p-10 text-center shadow-md">
        {done ? (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
              <CheckCircle2 size={34} />
            </span>
            <h1 className="text-2xl">Aadhaar verified</h1>
            <p className="mb-6 text-muted">Your identity is confirmed via DigiLocker. One last step — a quick selfie.</p>
            <button onClick={() => router.push("/kyc/selfie")} className="btn btn-gold btn-block">
              Continue to selfie <ArrowRight size={16} />
            </button>
          </>
        ) : (
          <>
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy-tint text-navy">
              <Loader2 size={34} className="animate-spin" />
            </span>
            <h1 className="text-2xl">Confirming your consent</h1>
            <p className="text-muted">Fetching your Aadhaar details from DigiLocker. This takes a moment…</p>
          </>
        )}
      </div>
    </div>
  );
}
