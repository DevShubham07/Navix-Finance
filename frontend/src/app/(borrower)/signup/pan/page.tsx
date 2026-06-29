"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice, nextAfterStep } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
const AADHAAR_RE = /^\d{12}$/;

export default function SignupPanPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [pan, setPan] = React.useState("");
  const [aadhaar, setAadhaar] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setPan(draft.pan);
    setAadhaar(draft.aadhaar);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const panOk = PAN_RE.test(pan);
  const aadhaarOk = AADHAAR_RE.test(aadhaar);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!panOk || !aadhaarOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ pan, aadhaar });
    try {
      await saveProfileSlice(appId, { pan, aadhaar });
      const r = await verificationApi.pan(appId, pan);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push(nextAfterStep("/signup/bureau"));
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your PAN — please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Your PAN confirms your identity against income-tax records, and your Aadhaar links your KYC. This is a
          soft check — it won&apos;t affect your credit score.
        </p>
        <Input
          label="PAN"
          required
          value={pan}
          onChange={(e) => setPan(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10))}
          placeholder="ABCDE1234F"
          inputClassName="tracking-[0.3em] uppercase"
          helperText="10 characters, e.g. ABCDE1234F"
          error={touched && !panOk ? "Enter a valid 10-character PAN" : undefined}
        />
        <Input
          label="Aadhaar number"
          required
          value={aadhaar}
          onChange={(e) => setAadhaar(e.target.value.replace(/\D/g, "").slice(0, 12))}
          placeholder="1234 5678 9012"
          inputMode="numeric"
          inputClassName="tracking-[0.2em]"
          helperText="12 digits — stored securely and masked on every screen"
          error={touched && !aadhaarOk ? "Enter a valid 12-digit Aadhaar number" : undefined}
        />
        <p className="mt-1 flex items-start gap-2 text-sm text-muted">
          <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
          Used only for identity and credit verification.
        </p>
        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/digilocker" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Verify & continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
