"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Smartphone, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { ensureBorrowerSession, createOrResumeDraft } from "@/lib/api/live-journey";
import { ApplicationApiError } from "@/lib/api/applications";
import { normalizeMobile } from "@/lib/utils";

export default function SignupMobileOtpPage() {
  const router = useRouter();
  const { mounted, draft, setAppId } = useOnboarding();
  const [mobile, setMobile] = React.useState("");
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && draft.mobile) setMobile(draft.mobile);
  }, [mounted, draft.mobile]);

  const mobileOk = mobile.length === 10;

  const sendOtp = () => {
    if (!mobileOk) {
      setError("Enter a valid 10-digit mobile number");
      return;
    }
    setError(undefined);
    draft.patch({ mobile });
    setStage("verify");
  };

  const confirm = async (code = otp) => {
    if (code.length !== 6) {
      setError("Enter the 6-digit code.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const clean = mobile.replace(/\s/g, "");
      // The backend validates the OTP and issues the JWT session cookie.
      const session = await ensureBorrowerSession(clean, code, draft.fullName || undefined);
      if (!session) throw new ApplicationApiError("Incorrect code or session error — please try again.", "INVALID_OTP", 0);
      // Create the DRAFT application up front so every later step has a real id to persist against.
      const app = await createOrResumeDraft(session);
      setAppId(app.id);
      await saveProfileSlice(app.id, { mobile: clean });
      draft.patch({ mobile: clean });
      router.push("/signup/email");
    } catch (e) {
      setError(
        e instanceof ApplicationApiError ? `${e.message} (${e.code})` : "Something went wrong — please try again.",
      );
      setBusy(false);
    }
  };

  return (
    <div>
      {stage === "enter" ? (
        <div className="form-card">
          <p className="lead mb-4">We&apos;ll text a one-time code to confirm this is your number.</p>
          <Input
            label="Mobile number"
            required
            inputMode="numeric"
            maxLength={10}
            value={mobile}
            onChange={(e) => { setMobile(normalizeMobile(e.target.value)); setError(undefined); }}
            placeholder="98765 43210"
            leftIcon={<Smartphone size={16} />}
            autoComplete="tel"
            error={error}
            helperText="Indian mobile number linked to your bank and Aadhaar"
          />
          <WizardActions backHref="/login" continueLabel="Send code" onContinue={sendOtp} disabled={!mobileOk} />
        </div>
      ) : (
        <div className="form-card">
          <p className="lead mb-1">Enter the 6-digit code</p>
          <p className="mb-5 text-sm text-muted">
            Sent to <strong className="text-ink">{mobile}</strong> ·{" "}
            <button type="button" onClick={() => setStage("enter")} className="font-semibold text-navy hover:underline">
              Change number
            </button>
          </p>
          <OtpInput value={otp} onChange={(v) => { setOtp(v); setError(undefined); }} onComplete={confirm} disabled={busy} />
          {error ? (
            <p className="mt-3 text-sm text-error-600">{error}</p>
          ) : (
            <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
              <CheckCircle2 size={15} className="text-success-600" /> Demo code is <strong className="text-ink">123456</strong>
            </p>
          )}
          <WizardActions onBack={() => setStage("enter")} continueLabel="Verify" onContinue={() => confirm()} loading={busy} disabled={otp.length !== 6 || busy} />
        </div>
      )}
      <Reassurance />
    </div>
  );
}
