"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CheckCircle2, Loader2, ShieldCheck } from "lucide-react";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, completeStep, saveProfileSlice, useSavedProfile, BUREAU_CONSENT_TEXT } from "@/lib/onboarding";
import { verificationApi, type OtpRequestResult } from "@/lib/api/applications";
import { fetchBorrowerSession } from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";

/**
 * Screen 9 — the only place external APIs fire (revamp.md decisions 6, 7). The borrower re-confirms
 * their identity with a one-time code AND explicitly authorises the bureau enquiry; only then do PAN,
 * work-email verification and the credit pull run.
 *
 * The OTP is verified SERVER-SIDE inside the consent write — deliberately not via
 * `ensureBorrowerSession`, which returns the existing session before it ever looks at the code and
 * would make this step decorative.
 *
 * A FAILED check does not block: the application still goes to the credit team, flagged
 * (revamp.md decision 10). Only a step that never ran at all stops submission.
 */
export default function SignupConsentPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [mobile, setMobile] = React.useState("");
  const [dob, setDob] = React.useState("");
  const [otp, setOtp] = React.useState("");
  const [consent, setConsent] = React.useState(false);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [verifying, setVerifying] = React.useState(false);
  const [sent, setSent] = React.useState<OtpRequestResult | null>(null);
  const [resends, setResends] = React.useState(0);
  const [error, setError] = React.useState<string>();
  const requested = React.useRef(false);

  const MAX_RESENDS = 3;

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  React.useEffect(() => {
    if (saved?.dob) setDob(saved.dob);
  }, [saved?.dob]);

  // Sends the bureau-consent OTP — a purpose-dedicated code, isolated server-side from the login OTP.
  // Application-scoped (the backend resolves the mobile from the profile), so no mobile is passed here.
  const sendOtp = React.useCallback(async (isResend = true) => {
    if (appId == null) return;
    if (isResend && resends >= MAX_RESENDS) return;
    setBusy(true);
    setError(undefined);
    try {
      setSent(await verificationApi.requestBureauConsentOtp(appId));
      if (isResend) setResends((n) => n + 1);
    } catch (e) {
      setError(formatApiError(e, "Could not send the code — please try again."));
    } finally {
      setBusy(false);
    }
  }, [appId, resends]);

  // The live session is authoritative for the mobile — a returning borrower who skipped screens 1–2
  // has no locally-typed number, and the backend back-fills the profile for exactly this case. This is
  // display-only now (the OTP send itself no longer needs the mobile — it's resolved server-side).
  React.useEffect(() => {
    if (!mounted || appId == null || requested.current) return;
    requested.current = true;
    void (async () => {
      const session = await fetchBorrowerSession();
      const number = session?.mobile ?? saved?.mobile ?? "";
      if (!number) {
        setError("We couldn't find your mobile number. Please sign in again.");
        return;
      }
      setMobile(number);
      await sendOtp(false);
    })();
  }, [mounted, appId, saved?.mobile, sendOtp]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (otp.length !== 6 || !consent || !dob) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await saveProfileSlice(appId, { dob });
      await verificationApi.bureauConsent(appId, otp, BUREAU_CONSENT_TEXT);
      setVerifying(true);
      // All four run here. A FAIL is recorded and passed on to the credit team, so failures are
      // caught per-call and never stop the borrower.
      const pan = saved?.pan ?? "";
      if (pan) await verificationApi.pan(appId, pan).catch(() => {});
      if (saved?.officialEmail) await verificationApi.email(appId, saved.officialEmail).catch(() => {});
      await verificationApi.bureau(appId, otp).catch(() => {});
      // EPFO employment, alongside the bureau pull: both need only PAN/mobile/name/DOB, all of which
      // are on the profile by now, and both are read by the same credit reviewer. Advisory — it is
      // absent from the submit-kyc REQUIRED set, so a no-record result cannot trap the borrower here.
      await verificationApi.employment(appId).catch(() => {});
      await completeStep(appId, "CONSENT", router, "/signup/submitted");
    } catch (err) {
      setVerifying(false);
      setError(formatApiError(err, "Incorrect or expired code — please try again."));
      setBusy(false);
    }
  };

  if (verifying) {
    return (
      <div className="form-card text-center">
        <Loader2 size={28} className="mx-auto animate-spin text-navy" />
        <p className="mt-4 font-serif text-lg text-navy">Verifying your details…</p>
        <p className="mt-1 text-sm text-muted">This takes a few seconds. Please don&apos;t close this page.</p>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-1 flex items-center gap-2">
          <ShieldCheck size={18} className="text-success-600" /> One last confirmation
        </p>
        <p className="mb-5 text-sm text-muted">
          Enter the code we sent to <strong className="text-ink">{mobile || "your mobile"}</strong> and
          authorise the credit check.
        </p>

        <OtpInput
          value={otp}
          onChange={(v) => {
            setOtp(v);
            setError(undefined);
          }}
          disabled={busy}
        />

        <label className="field mt-5">
          <span>Date of birth</span>
          <input
            type="date"
            value={dob}
            onChange={(e) => {
              setDob(e.target.value);
              setError(undefined);
            }}
            disabled={busy}
            required
          />
        </label>
        {touched && !dob ? (
          <p className="mt-1 text-sm text-error-600">Your date of birth is required for the credit check.</p>
        ) : null}

        {sent?.devCode ? (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            <CheckCircle2 size={15} className="text-success-600" /> Dev code:{" "}
            <strong className="text-ink">{sent.devCode}</strong>
          </p>
        ) : (
          <p className="mt-3 text-sm text-muted">
            {resends < MAX_RESENDS ? (
              <button type="button" onClick={() => void sendOtp(true)} disabled={busy} className="font-semibold text-navy hover:underline">
                Resend code ({MAX_RESENDS - resends} left)
              </button>
            ) : (
              <span>No more resends — please contact support if you didn&apos;t receive your code.</span>
            )}
          </p>
        )}

        <label className="checkbox mt-5">
          <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
          <span>{BUREAU_CONSENT_TEXT}</span>
        </label>
        {touched && !consent ? (
          <p className="mt-1 text-sm text-error-600">Your consent is required to continue.</p>
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      {/* The bureau enquiry is the one irreversible thing on this screen — it pulls a credit report
          and leaves a footprint. The button must not look available until the borrower has actually
          entered the code AND given consent, which is what the submit handler already required. */}
      <WizardActions
        backHref="/signup/payslips"
        submit
        continueLabel="Confirm & submit"
        loading={busy}
        disabled={busy || otp.length !== 6 || !consent || !dob}
      />
      <Reassurance />
    </form>
  );
}
