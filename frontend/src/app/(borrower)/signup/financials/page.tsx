"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Link2, CheckCircle2, Loader2, ArrowRight } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { useBorrowerJourney } from "@/lib/mock/borrower";

export default function SignupFinancialsPage() {
  const router = useRouter();
  const { applicant, updateApplicant } = useBorrowerJourney();
  const [state, setState] = React.useState<"idle" | "linking" | "linked">(
    applicant.financialsLinked ? "linked" : "idle",
  );

  const link = () => {
    setState("linking");
    setTimeout(() => {
      updateApplicant({ financialsLinked: true });
      setState("linked");
    }, 1200);
  };

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-5">
          Securely share read-only bank statements through an RBI-licensed Account Aggregator. It strengthens
          your application and can improve your offer. This step is optional.
        </p>

        <div className="rounded border border-line bg-grey-100 p-6 text-center">
          {state === "linked" ? (
            <>
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-success-50 text-success-600">
                <CheckCircle2 size={30} />
              </span>
              <h3 className="font-serif text-lg text-navy">Statements linked</h3>
              <p className="mx-auto max-w-sm text-sm text-muted">
                We received your consent via the Account Aggregator. You can continue.
              </p>
            </>
          ) : (
            <>
              <span className="mx-auto mb-3 grid h-14 w-14 place-items-center rounded-full bg-navy-tint text-navy">
                <Link2 size={28} />
              </span>
              <h3 className="font-serif text-lg text-navy">Link via Account Aggregator</h3>
              <p className="mx-auto mb-5 max-w-sm text-sm text-muted">
                You choose exactly which accounts to share. NAVIX gets read-only access — never your login
                credentials.
              </p>
              <button type="button" onClick={link} disabled={state === "linking"} className="btn btn-navy">
                {state === "linking" ? <Loader2 size={16} className="animate-spin" /> : <Link2 size={16} />}
                {state === "linking" ? "Connecting…" : "Connect securely"}
              </button>
            </>
          )}
        </div>
      </div>

      <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <a href="/signup/bank" className="btn btn-outline btn-sm order-2 sm:order-1">Back</a>
        <div className="order-1 flex flex-col gap-3 sm:order-2 sm:flex-row">
          {state !== "linked" && (
            <button type="button" onClick={() => router.push("/signup/co-applicant")} className="btn btn-outline btn-sm">
              Skip for now
            </button>
          )}
          <button
            type="button"
            onClick={() => router.push("/signup/co-applicant")}
            disabled={state === "linking"}
            className="btn btn-gold"
          >
            Continue <ArrowRight size={16} />
          </button>
        </div>
      </div>
      <Reassurance />
    </div>
  );
}
