"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Mail, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { verificationApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignupEmailPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [personalEmail, setPersonalEmail] = React.useState("");
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  // Personal-email OTP — additive to the official-email employer-match check below.
  const [otpSent, setOtpSent] = React.useState(false);
  const [otp, setOtp] = React.useState("");
  const [verified, setVerified] = React.useState(false);
  const [otpBusy, setOtpBusy] = React.useState(false);
  const [otpError, setOtpError] = React.useState<string>();

  React.useEffect(() => {
    if (!saved) return;
    if (saved.email) setPersonalEmail(saved.email);
    if (saved.officialEmail) setOfficialEmail(saved.officialEmail);
    if (saved.personalEmailVerified) setVerified(true);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const personalOk = EMAIL_RE.test(personalEmail);
  const officialOk = EMAIL_RE.test(officialEmail);

  const sendOtp = async () => {
    if (!personalOk || appId == null) {
      setTouched(true);
      return;
    }
    setOtpBusy(true);
    setOtpError(undefined);
    try {
      // The server resolves the OTP target from the saved profile, so the save must land first.
      await saveProfileSlice(appId, { email: personalEmail.trim() });
      await verificationApi.requestPersonalEmailOtp(appId);
      setOtpSent(true);
    } catch (err) {
      setOtpError(formatApiError(err, "Could not send the code — please try again."));
    }
    setOtpBusy(false);
  };

  const confirmOtp = async (code = otp) => {
    if (appId == null || code.length !== 6) return;
    setOtpBusy(true);
    setOtpError(undefined);
    try {
      await verificationApi.confirmPersonalEmailOtp(appId, code);
      setVerified(true);
    } catch (err) {
      setOtpError(formatApiError(err, "Incorrect code — please try again."));
    }
    setOtpBusy(false);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!personalOk || !officialOk) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      // Personal is the contact address (approvals, reset links, statements); the work address is
      // what the verification API checks on the consent screen (revamp.md decision 15).
      await saveProfileSlice(appId, {
        email: personalEmail.trim(),
        officialEmail: officialEmail.trim(),
      });
      await completeStep(appId, "EMAIL", router, "/signup/bank");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          We send your sanction letter and statements to your personal email, and confirm your employer
          from your official one.
        </p>
        <Input
          label="Personal email address"
          required
          type="email"
          value={personalEmail}
          onChange={(e) => {
            setPersonalEmail(e.target.value);
            if (verified || otpSent) {
              setVerified(false);
              setOtpSent(false);
              setOtp("");
            }
          }}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !personalOk ? "Enter a valid email address" : undefined}
        />
        {verified ? (
          <p className="-mt-2 mb-4 flex items-center gap-1.5 text-sm font-semibold text-success-700">
            <CheckCircle2 size={15} /> Email verified
          </p>
        ) : otpSent ? (
          <div className="-mt-2 mb-4">
            <p className="mb-2 text-sm text-muted">Enter the 6-digit code sent to your personal email</p>
            <OtpInput
              value={otp}
              onChange={(v) => {
                setOtp(v);
                setOtpError(undefined);
              }}
              onComplete={confirmOtp}
              disabled={otpBusy}
            />
            {otpError ? <p className="mt-2 text-sm text-error-600">{otpError}</p> : null}
            <button
              type="button"
              onClick={sendOtp}
              disabled={otpBusy}
              className="mt-2 text-sm font-semibold text-navy hover:underline"
            >
              Resend code
            </button>
          </div>
        ) : (
          <div className="-mt-2 mb-4">
            <button
              type="button"
              onClick={sendOtp}
              disabled={otpBusy || !personalOk}
              className="btn btn-outline btn-sm"
            >
              {otpBusy ? "Sending…" : "Send code to verify"}
            </button>
            {otpError ? <p className="mt-2 text-sm text-error-600">{otpError}</p> : null}
          </div>
        )}
        <Input
          label="Official work email address"
          required
          type="email"
          value={officialEmail}
          onChange={(e) => setOfficialEmail(e.target.value)}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Used to confirm your employer. We never email your workplace."
          error={touched && !officialOk ? "Enter your valid work email" : undefined}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions
        backHref="/signup/employer"
        submit
        loading={busy}
        disabled={busy || !verified}
      />
      <Reassurance />
    </form>
  );
}
