"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Landmark, CheckCircle2, Loader2, ArrowRight, BadgeIndianRupee } from "lucide-react";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

type Phase = "verify" | "verified" | "disbursing";

export default function LoanBankVerifyPage() {
  const router = useRouter();
  const mounted = useMounted();
  const j = useBorrowerJourney();
  const [phase, setPhase] = React.useState<Phase>(j.bankVerified ? "verified" : "verify");
  const [busy, setBusy] = React.useState(false);

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  if (!j.documentsSigned) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Sign your documents first</h1>
          <Link href="/loan/documents" className="btn btn-gold mt-3">Review &amp; sign <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const a = j.applicant;
  const verify = () => {
    setBusy(true);
    setTimeout(() => {
      j.verifyBank();
      setPhase("verified");
      setBusy(false);
    }, 1500);
  };
  const disburse = () => {
    setPhase("disbursing");
    setTimeout(() => {
      j.disburse();
      router.push("/dashboard");
    }, 1800);
  };

  return (
    <div className="container max-w-content py-10">
      <h1 className="mb-1">Confirm your bank account</h1>
      <p className="mb-7 text-muted">We send a ₹1 penny-drop to confirm the account is yours before disbursal.</p>

      <div className="rounded border border-line bg-white p-6 shadow-sm">
        <div className="flex items-center gap-4 border-b border-grey-200 pb-5">
          <span className="grid h-12 w-12 flex-shrink-0 place-items-center rounded bg-navy-tint text-navy">
            <Landmark size={22} />
          </span>
          <div>
            <div className="font-semibold text-ink">{a.bankName || "Salary account"}</div>
            <div className="text-sm text-muted">A/C •••• {a.accountLast4 || "0000"} · {a.ifsc || "IFSC"}</div>
          </div>
        </div>

        <div className="pt-5">
          {phase === "verify" && (
            <button onClick={verify} disabled={busy} className="btn btn-navy btn-block">
              {busy ? <Loader2 size={16} className="animate-spin" /> : null}
              {busy ? "Sending ₹1 penny-drop…" : "Verify account"}
            </button>
          )}

          {phase === "verified" && (
            <>
              <div className="mb-5 flex items-center gap-3 rounded bg-success-50/70 p-4 text-success-700">
                <CheckCircle2 size={22} className="flex-shrink-0" />
                <div>
                  <div className="text-sm font-semibold">Account verified — name matches {a.fullName || "your PAN"}</div>
                  <div className="text-xs">Ready to receive {formatINR0(j.chosenAmount)}</div>
                </div>
              </div>
              <button onClick={disburse} className="btn btn-gold btn-block">
                <BadgeIndianRupee size={16} /> Receive {formatINR0(j.chosenAmount)} now
              </button>
            </>
          )}

          {phase === "disbursing" && (
            <div className="py-6 text-center">
              <Loader2 size={36} className="mx-auto mb-3 animate-spin text-navy" />
              <div className="font-serif text-lg text-navy">Releasing your funds…</div>
              <p className="text-sm text-muted">Our partner NBFC is transferring the money to your account.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
