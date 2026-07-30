"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";

const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

export default function SignupPanPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [pan, setPan] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setPan(draft.pan);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const panOk = PAN_RE.test(pan);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!panOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ pan });
    try {
      await saveProfileSlice(appId, { pan });
      // The PAN is only SAVED here — it is fetched on the next step, after the borrower has
      // OTP-verified their consent to the bureau enquiry. Forward the query string rather than
      // using nextAfterStep: `?return=review` has to survive to the consent step, which owns the
      // navigation decision once the fetch actually happens.
      router.push(`/signup/pan-consent${window.location.search}`);
    } catch (err) {
      setError(formatApiError(err, "Could not save your PAN — please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Your PAN confirms your identity against income-tax records. This is a soft check — it won&apos;t affect
          your credit score. Your Aadhaar is verified separately through DigiLocker.
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
        <p className="mt-1 flex items-start gap-2 text-sm text-muted">
          <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
          Used only for identity and credit verification.
        </p>
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/digilocker" submit continueLabel="Continue" loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
