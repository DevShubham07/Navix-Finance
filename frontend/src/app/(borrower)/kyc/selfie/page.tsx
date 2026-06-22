"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Camera, CheckCircle2, ArrowRight, Loader2 } from "lucide-react";
import { useBorrowerJourney } from "@/lib/mock/borrower";

export default function KycSelfiePage() {
  const router = useRouter();
  const { setKyc, startReview } = useBorrowerJourney();
  const [state, setState] = React.useState<"idle" | "capturing" | "done">("idle");

  const capture = () => {
    setState("capturing");
    setTimeout(() => {
      setKyc({ selfie: "VERIFIED" });
      setState("done");
    }, 1400);
  };

  const finish = () => {
    startReview();
    router.push("/loan/status");
  };

  return (
    <div className="container max-w-content py-12">
      <div className="mx-auto max-w-md text-center">
        <h1 className="text-2xl">Take a quick selfie</h1>
        <p className="mb-6 text-muted">We keep this on file to match your face to your PAN photo. Good lighting helps.</p>

        <div className="mx-auto mb-6 grid aspect-square w-full max-w-xs place-items-center overflow-hidden rounded-full border-4 border-dashed border-line bg-grey-100">
          {state === "done" ? (
            <CheckCircle2 size={72} className="text-success-600" />
          ) : state === "capturing" ? (
            <Loader2 size={56} className="animate-spin text-navy" />
          ) : (
            <Camera size={64} className="text-muted" />
          )}
        </div>

        {state === "done" ? (
          <button onClick={finish} className="btn btn-gold btn-block">
            Submit for review <ArrowRight size={16} />
          </button>
        ) : (
          <button onClick={capture} disabled={state === "capturing"} className="btn btn-navy btn-block">
            {state === "capturing" ? "Capturing…" : "Capture selfie"}
          </button>
        )}
        <p className="mt-3 text-xs text-muted">Demo — no camera is actually opened.</p>
      </div>
    </div>
  );
}
