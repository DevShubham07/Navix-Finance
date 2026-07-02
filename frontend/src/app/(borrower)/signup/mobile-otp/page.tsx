"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Smartphone, CheckCircle2, Loader2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { ensureBorrowerSession, createOrResumeDraft, findResumableApp, requestBorrowerOtp, clearBorrowerClientState, fetchBorrowerSession, writeStoredAppId, type OtpRequestResult } from "@/lib/api/live-journey";
import { ApplicationApiError, type ApplicationView } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { normalizeMobile } from "@/lib/utils";

export default function SignupMobileOtpPage() {
  const router = useRouter();
  const { mounted, draft, setAppId } = useOnboarding();
  const [mobile, setMobile] = React.useState("");
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [sentInfo, setSentInfo] = React.useState<OtpRequestResult>();
  const [resendCount, setResendCount] = React.useState(0);
  // An already-logged-in borrower (e.g. arriving here via "Borrow again" → full re-KYC) shouldn't be
  // re-asked for an OTP — skip straight into the wizard. `checking` gates the OTP form so logged-in
  // users never flash it; a ran-once ref keeps the session probe from firing twice.
  const [checking, setChecking] = React.useState(true);
  const probedRef = React.useRef(false);

  React.useEffect(() => {
    if (!mounted || probedRef.current) return;
    probedRef.current = true;
    (async () => {
      const session = await fetchBorrowerSession();
      if (session) {
        // A returning borrower who already has an in-flight application resumes it from the dashboard
        // "Application in progress → Continue" card (which deep-links to their first unfinished step
        // and pre-fills from the saved profile) — never re-onboard them at step 2. Only a brand-new
        // borrower (no in-flight app) starts a fresh draft straight into the wizard.
        const resumable = await findResumableApp();
        if (resumable) {
          writeStoredAppId(resumable.id);
          setAppId(resumable.id);
          router.replace("/dashboard");
          return;
        }
        try {
          const app = await createOrResumeDraft(session);
          setAppId(app.id);
          writeStoredAppId(app.id);
          router.replace("/signup/email");
        } catch {
          // The backend blocks a new draft when a loan/application is already live or in flight
          // (one advance at a time) — there's nothing to onboard, so send them to their dashboard.
          router.replace("/dashboard");
        }
        return;
      }
      setChecking(false);
    })();
  }, [mounted, router, setAppId]);

  React.useEffect(() => {
    if (mounted && draft.mobile) setMobile(draft.mobile);
  }, [mounted, draft.mobile]);

  const mobileOk = mobile.length === 10;
  const MAX_RESENDS = 3;

  const sendOtp = async () => {
    if (!mobileOk) {
      setError("Enter a valid 10-digit mobile number");
      return;
    }
    if (resendCount >= MAX_RESENDS) {
      setError("Maximum resend attempts reached. Please try again later or contact support.");
      return;
    }
    // A different number than the persisted draft means a different customer is starting onboarding
    // on this browser — discard the previous customer's draft (name/PAN/Aadhaar/bank), app pointer,
    // and cached queries so none of their identity/PII carries over. Same number = the same person
    // resuming, so their draft is preserved.
    if (draft.mobile && draft.mobile !== mobile) {
      clearBorrowerClientState();
    }
    setBusy(true);
    setError(undefined);
    try {
      const info = await requestBorrowerOtp(mobile);
      setSentInfo(info);
      setResendCount((n) => n + 1);
      draft.patch({ mobile });
      setStage("verify");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not send the code.");
    }
    setBusy(false);
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
      // The backend validates the OTP and issues the JWT session cookie. Do NOT forward a name here:
      // at this first step the persisted draft name (if any) belongs to a *previous* customer on this
      // browser, and forwarding it would mint this user's session under that name. The backend resolves
      // the display name from the stored profile instead.
      const session = await ensureBorrowerSession(clean, code);
      if (!session) throw new ApplicationApiError("Incorrect code or session error — please try again.", "INVALID_OTP", 0);
      // A returning borrower who already has an in-flight application resumes it from the dashboard
      // rather than re-onboarding (mirrors this page's mount effect).
      const resumable = await findResumableApp();
      if (resumable) {
        writeStoredAppId(resumable.id);
        setAppId(resumable.id);
        router.replace("/dashboard");
        return;
      }
      // Otherwise create the DRAFT up front so every later step has a real id to persist against. If
      // the backend guard still blocks a new draft (a live loan exists), land on the dashboard.
      let app: ApplicationView;
      try {
        app = await createOrResumeDraft(session);
      } catch {
        router.replace("/dashboard");
        return;
      }
      setAppId(app.id);
      await saveProfileSlice(app.id, { mobile: clean });
      draft.patch({ mobile: clean });
      // Offer the optional "set a password" step before the rest of onboarding (skippable).
      router.push("/signup/set-password");
    } catch (e) {
      setError(formatApiError(e, "Something went wrong — please try again."));
      setBusy(false);
    }
  };

  if (checking) {
    return (
      <div>
        <div className="form-card">
          <p className="lead flex items-center gap-2">
            <Loader2 size={16} className="animate-spin" /> Checking your session…
          </p>
        </div>
        <Reassurance />
      </div>
    );
  }

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
            onChange={(e) => { setMobile(normalizeMobile(e.target.value)); setError(undefined); }}
            placeholder="98765 43210"
            leftIcon={<Smartphone size={16} />}
            autoComplete="tel"
            error={error}
            helperText="Indian mobile number linked to your bank and Aadhaar"
          />
          <WizardActions backHref="/login" continueLabel="Send code" onContinue={sendOtp} disabled={!mobileOk || busy} loading={busy} />
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
          ) : sentInfo?.devCode ? (
            <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
              <CheckCircle2 size={15} className="text-success-600" /> Dev code: <strong className="text-ink">{sentInfo.devCode}</strong>
            </p>
          ) : sentInfo?.sent ? (
            <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
              <CheckCircle2 size={15} className="text-success-600" /> Code sent.{" "}
              {resendCount < MAX_RESENDS ? (
                <button type="button" onClick={() => sendOtp()} disabled={busy} className="font-semibold text-navy hover:underline">
                  Resend ({MAX_RESENDS - resendCount} left)
                </button>
              ) : (
                <span className="text-muted">No more resends — contact support if you didn&apos;t receive your code.</span>
              )}
            </p>
          ) : (
            <p className="mt-3 text-sm text-muted">
              We couldn&apos;t send an SMS.{" "}
              {resendCount < MAX_RESENDS ? (
                <button type="button" onClick={() => sendOtp()} disabled={busy} className="font-semibold text-navy hover:underline">
                  Try again ({MAX_RESENDS - resendCount} left)
                </button>
              ) : (
                <span>Please contact support.</span>
              )}
            </p>
          )}
          <WizardActions onBack={() => setStage("enter")} continueLabel="Verify" onContinue={() => confirm()} loading={busy} disabled={otp.length !== 6 || busy} />
        </div>
      )}
      <Reassurance />
    </div>
  );
}
