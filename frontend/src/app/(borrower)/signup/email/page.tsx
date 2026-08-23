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

/**
 * The send / enter / resend block for one address. Both emails are OTP-verified now and the block
 * owns five pieces of transient state, so it is parameterised rather than copied — the two uses
 * differ only in which endpoints they call and whether an undelivered code is survivable.
 *
 * `verified` lives in the parent because it gates Continue; the transient state lives here. Changing
 * the address resets this block, and only this block, back to its unsent state.
 */
function EmailOtpBlock({
  appId,
  email,
  valid,
  verified,
  request,
  confirm,
  onVerified,
  onUndeliverable,
  save,
}: {
  appId: number | null;
  email: string;
  valid: boolean;
  verified: boolean;
  request: (id: number) => Promise<{ sent?: boolean }>;
  confirm: (id: number, otp: string) => Promise<unknown>;
  onVerified: () => void;
  /** Called when the provider could not deliver. Absent = this address must be verified to proceed. */
  onUndeliverable?: () => void;
  /** Persist the address first — the server resolves the OTP target from the saved profile. */
  save: (email: string) => Promise<void>;
}) {
  const [sent, setSent] = React.useState(false);
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [undelivered, setUndelivered] = React.useState(false);

  React.useEffect(() => {
    setSent(false);
    setOtp("");
    setError(undefined);
    setUndelivered(false);
  }, [email]);

  const sendOtp = async () => {
    if (!valid || appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await save(email.trim());
      const res = await request(appId);
      setSent(true);
      if (res?.sent === false) {
        setUndelivered(true);
        onUndeliverable?.();
      }
    } catch (err) {
      setError(formatApiError(err, "Could not send the code — please try again."));
    }
    setBusy(false);
  };

  const confirmOtp = async (code = otp) => {
    if (appId == null || code.length !== 6) return;
    setBusy(true);
    setError(undefined);
    try {
      await confirm(appId, code);
      onVerified();
    } catch (err) {
      setError(formatApiError(err, "Incorrect code — please try again."));
    }
    setBusy(false);
  };

  if (verified) {
    return (
      <p className="-mt-2 mb-4 flex items-center gap-1.5 text-sm font-semibold text-success-700">
        <CheckCircle2 size={15} /> Email verified
      </p>
    );
  }

  if (!sent) {
    return (
      <div className="-mt-2 mb-4">
        <button
          type="button"
          onClick={sendOtp}
          disabled={busy || !valid}
          className="btn btn-outline btn-sm"
        >
          {busy ? "Sending…" : "Send code to verify"}
        </button>
        {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      </div>
    );
  }

  return (
    <div className="-mt-2 mb-4">
      {undelivered ? (
        <p className="mb-2 text-sm text-muted">
          We couldn&apos;t deliver a code to this address — some workplaces block outside email. You
          can carry on; our team will confirm it with you.
        </p>
      ) : (
        <p className="mb-2 text-sm text-muted">Enter the 6-digit code we sent to this address</p>
      )}
      <OtpInput
        value={otp}
        onChange={(v) => {
          setOtp(v);
          setError(undefined);
        }}
        onComplete={confirmOtp}
        disabled={busy}
      />
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      <button
        type="button"
        onClick={sendOtp}
        disabled={busy}
        className="mt-2 text-sm font-semibold text-navy hover:underline"
      >
        Resend code
      </button>
    </div>
  );
}

export default function SignupEmailPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [personalEmail, setPersonalEmail] = React.useState("");
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const [personalVerified, setPersonalVerified] = React.useState(false);
  const [officialVerified, setOfficialVerified] = React.useState(false);
  // A work address we could not reach stops gating Continue — see EmailOtpBlock's onUndeliverable.
  // The personal address has no such escape: it is the contact channel for the sanction letter and
  // every statement, so an unreachable one has to be replaced, not waved through.
  const [officialUndeliverable, setOfficialUndeliverable] = React.useState(false);

  React.useEffect(() => {
    if (!saved) return;
    if (saved.email) setPersonalEmail(saved.email);
    if (saved.officialEmail) setOfficialEmail(saved.officialEmail);
    if (saved.personalEmailVerified) setPersonalVerified(true);
    if (saved.officialEmailOtpVerified) setOfficialVerified(true);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const personalOk = EMAIL_RE.test(personalEmail);
  const officialOk = EMAIL_RE.test(officialEmail);

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
      // also what the provider check corroborates on the consent screen (revamp.md decision 15).
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
          We send your sanction letter and statements to your personal email, and confirm your
          employer from your official one. We&apos;ll email a code to each to check it reaches you.
        </p>
        <Input
          label="Personal email address"
          required
          type="email"
          value={personalEmail}
          onChange={(e) => {
            setPersonalEmail(e.target.value);
            setPersonalVerified(false);
          }}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !personalOk ? "Enter a valid email address" : undefined}
        />
        <EmailOtpBlock
          appId={appId}
          email={personalEmail}
          valid={personalOk}
          verified={personalVerified}
          request={verificationApi.requestPersonalEmailOtp}
          confirm={verificationApi.confirmPersonalEmailOtp}
          onVerified={() => setPersonalVerified(true)}
          save={(email) => saveProfileSlice(appId as number, { email })}
        />
        <Input
          label="Official work email address"
          required
          type="email"
          value={officialEmail}
          onChange={(e) => {
            setOfficialEmail(e.target.value);
            setOfficialVerified(false);
            setOfficialUndeliverable(false);
          }}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Used to confirm your employer. We send a 6-digit code to this address."
          error={touched && !officialOk ? "Enter your valid work email" : undefined}
        />
        <EmailOtpBlock
          appId={appId}
          email={officialEmail}
          valid={officialOk}
          verified={officialVerified}
          request={verificationApi.requestOfficialEmailOtp}
          confirm={verificationApi.confirmOfficialEmailOtp}
          onVerified={() => setOfficialVerified(true)}
          onUndeliverable={() => setOfficialUndeliverable(true)}
          save={(email) => saveProfileSlice(appId as number, { officialEmail: email })}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions
        backHref="/signup/employer"
        submit
        loading={busy}
        disabled={busy || !personalVerified || !(officialVerified || officialUndeliverable)}
      />
      <Reassurance />
    </form>
  );
}
