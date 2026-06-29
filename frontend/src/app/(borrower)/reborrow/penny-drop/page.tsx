"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, ShieldCheck, ArrowRight, Loader2 } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useMounted } from "@/hooks/use-mounted";
import { readStoredAppId } from "@/lib/api/live-journey";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

/**
 * Returning-borrower bank verification (live). A pre-approved repeat borrower confirms the salary
 * account their advance will be deposited into via the real ₹1 penny-drop name-match — the same check
 * as fresh onboarding (`verify/penny-drop`), run against this reborrow application's carried-over
 * profile. Standalone (outside the signup wizard layout); on PASS/REVIEW we hand off to /loan/apply.
 */
const BANKS = [
  "HDFC Bank",
  "ICICI Bank",
  "State Bank of India",
  "Axis Bank",
  "Kotak Mahindra Bank",
  "Bank of Baroda",
  "Punjab National Bank",
  "Yes Bank",
  "IndusInd Bank",
  "IDFC FIRST Bank",
].map((b) => ({ value: b, label: b }));

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

export default function ReborrowPennyDropPage() {
  const router = useRouter();
  const mounted = useMounted();
  const [appId, setAppId] = React.useState<number | null>(null);
  const [bankName, setBankName] = React.useState("HDFC Bank");
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    const id = readStoredAppId();
    if (id == null) {
      router.replace("/reloan");
      return;
    }
    setAppId(id);
  }, [mounted, router]);

  if (!mounted || appId == null) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  const accountClean = account.replace(/\s/g, "");
  const accountOk = accountClean.length >= 9;
  const ifscOk = IFSC_RE.test(ifsc);
  const formOk = accountOk && ifscOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const r = await verificationApi.pennyDrop(appId, accountClean, ifsc);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push("/loan/apply");
    } catch (err) {
      setError(
        err instanceof ApplicationApiError
          ? `${err.message} (${err.code})`
          : "Could not verify your account — please try again.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="container max-w-content py-10">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gold-dark">
        <ShieldCheck size={14} /> Pre-approved
      </div>
      <h1 className="mb-1">Confirm your salary account</h1>
      <p className="mb-6 text-muted">
        We send a ₹1 penny-drop to confirm the account is yours — the name must match your PAN. This is
        also where your advance is deposited.
      </p>

      <form onSubmit={submit} noValidate>
        <div className="rounded border border-line bg-white p-7 shadow-sm">
          <Select label="Bank" value={bankName} onChange={(e) => setBankName(e.target.value)} options={BANKS} />
          <Input
            label="Account number"
            required
            inputMode="numeric"
            value={account}
            onChange={(e) => setAccount(e.target.value.replace(/[^\d ]/g, ""))}
            placeholder="Enter your salary account number"
            leftIcon={<Landmark size={16} />}
            error={touched && !accountOk ? "Enter a valid account number" : undefined}
          />
          <Input
            label="IFSC code"
            required
            value={ifsc}
            onChange={(e) => setIfsc(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 11))}
            placeholder="HDFC0001234"
            inputClassName="uppercase tracking-wider"
            error={touched && !ifscOk ? "Enter a valid 11-character IFSC" : undefined}
          />
          <p className="mt-1 flex items-start gap-2 text-sm text-muted">
            <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
            A ₹1 verification credit confirms the account before any advance is disbursed.
          </p>
          <StepResultBanner result={result} />
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}

          <button type="submit" disabled={busy} className="btn btn-gold mt-5 w-full justify-center">
            {busy ? <Loader2 size={16} className="animate-spin" /> : <ArrowRight size={16} />}
            {busy ? "Verifying…" : "Verify & continue to amount"}
          </button>
        </div>
      </form>
    </div>
  );
}
