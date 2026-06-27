"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, ShieldCheck } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

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

export default function SignupPennyDropPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [bankName, setBankName] = React.useState("HDFC Bank");
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setBankName(draft.bankName || "HDFC Bank");
    setAccount(draft.accountNumber);
    setIfsc(draft.ifsc);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const accountClean = account.replace(/\s/g, "");
  const accountOk = accountClean.length >= 9;
  const ifscOk = IFSC_RE.test(ifsc);
  const formOk = accountOk && ifscOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ bankName, accountNumber: accountClean, ifsc });
    try {
      await saveProfileSlice(appId, { salaryBank: bankName });
      const r = await verificationApi.pennyDrop(appId, accountClean, ifsc);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push("/signup/selfie");
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your account — please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Add the account where your salary is credited. We send a ₹1 penny-drop to confirm it&apos;s yours — the
          name must match your PAN. This is also where your advance is deposited.
        </p>
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
      </div>
      <WizardActions backHref="/signup/salary" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Verify & continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
