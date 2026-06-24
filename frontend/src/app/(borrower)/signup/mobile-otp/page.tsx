"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Smartphone, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { ensureBorrowerSession } from "@/lib/api/live-journey";

const DEMO_OTP = "123456";

export default function SignupMobileOtpPage() {
  const router = useRouter();
  const { applicant, updateApplicant, verifyMobile } = useBorrowerJourney();
  const [mobile, setMobile] = usePersistedField(applicant.mobile);
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [otp, setOtp] = React.useState("");
  const [error, setError] = React.useState<string>();

  const mobileOk = mobile.replace(/\D/g, "").length === 10;

  const sendOtp = () => {
    if (!mobileOk) {
      setError("Enter a valid 10-digit mobile number");
      return;
    }
    setError(undefined);
    updateApplicant({ mobile });
    setStage("verify");
  };

  const confirm = async (code = otp) => {
    if (code !== DEMO_OTP) {
      setError("Incorrect code. For this demo, use 123456.");
      return;
    }
    verifyMobile();
    // Establish the real backend session (httpOnly navix_borrower cookie) now so
    // later steps — and the final submit — can persist against a stable applicantId.
    try {
      await ensureBorrowerSession(mobile.replace(/\s/g, ""), applicant.fullName);
    } catch {
      // Non-fatal: submitOnboarding will retry establishing the session at submit.
    }
    router.push("/signup/employment");
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
            value={mobile}
            onChange={(e) => { setMobile(e.target.value.replace(/[^\d ]/g, "").slice(0, 11)); setError(undefined); }}
            placeholder="98765 43210"
            leftIcon={<Smartphone size={16} />}
            autoComplete="tel"
            error={error}
            helperText="Indian mobile number linked to your bank and Aadhaar"
          />
          <WizardActions backHref="/signup/pan" continueLabel="Send code" onContinue={sendOtp} disabled={!mobileOk} />
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
          <OtpInput value={otp} onChange={(v) => { setOtp(v); setError(undefined); }} onComplete={confirm} />
          {error ? (
            <p className="mt-3 text-sm text-error-600">{error}</p>
          ) : (
            <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
              <CheckCircle2 size={15} className="text-success-600" /> Demo code is <strong className="text-ink">123456</strong>
            </p>
          )}
          <WizardActions onBack={() => setStage("enter")} continueLabel="Verify" onContinue={() => confirm()} disabled={otp.length !== 6} />
        </div>
      )}
      <Reassurance />
    </div>
  );
}
