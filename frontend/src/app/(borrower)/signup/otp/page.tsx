"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CheckCircle2 } from "lucide-react";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep } from "@/lib/onboarding";
import {
  ensureBorrowerSession,
  createOrResumeDraft,
  requestBorrowerOtp,
  routeForBlockedStart,
  writeStoredAppId,
  type OtpRequestResult,
} from "@/lib/api/live-journey";
import { ApplicationApiError, type ApplicationView } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const MAX_RESENDS = 3;

export default function SignupOtpPage() {
  const router = useRouter();
  const { mounted, draft, setAppId } = useOnboarding();
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [sent, setSent] = React.useState<OtpRequestResult>();
  const [resends, setResends] = React.useState(0);
  const requested = React.useRef(false);

  const mobile = draft.mobile;

  React.useEffect(() => {
    if (!mounted) return;
    if (!mobile) {
      router.replace("/signup/start");
      return;
    }
    if (requested.current) return;
    requested.current = true;
    send();
  }, [mounted, mobile]); // eslint-disable-line react-hooks/exhaustive-deps

  async function send() {
    if (resends >= MAX_RESENDS) {
      setError("Maximum resend attempts reached. Please contact support.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      setSent(await requestBorrowerOtp(mobile));
      setResends((n) => n + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not send the code.");
    }
    setBusy(false);
  }

  async function confirm(code = otp) {
    if (code.length !== 6) {
      setError("Enter the 6-digit code.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const session = await ensureBorrowerSession(mobile, code);
      if (!session) throw new ApplicationApiError("Incorrect code — please try again.", "INVALID_OTP", 0);

      let app: ApplicationView;
      try {
        app = await createOrResumeDraft(session);
      } catch (e) {
        // One advance at a time: a live loan/application blocks a new one. Send them to the
        // matching screen rather than surfacing a raw error.
        const dest = e instanceof ApplicationApiError ? routeForBlockedStart(e.code) : null;
        router.replace(dest ?? "/dashboard");
        return;
      }
      setAppId(app.id);
      writeStoredAppId(app.id);
      // Screen 1's inputs land now — this is the first moment there is an application to hold them.
      await saveProfileSlice(app.id, {
        mobile,
        pan: draft.pan,
        termsVersion: draft.termsVersion,
        pepDeclared: draft.pepDeclared,
      });
      await completeStep(app.id, "OTP", router, "/signup/employment");
    } catch (e) {
      setError(formatApiError(e, "Something went wrong — please try again."));
      setBusy(false);
    }
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Enter the 6-digit code</p>
        <p className="mb-5 text-sm text-muted">
          Sent to <strong className="text-ink">{mobile}</strong>
        </p>
        <OtpInput
          value={otp}
          onChange={(v) => {
            setOtp(v);
            setError(undefined);
          }}
          onComplete={confirm}
          disabled={busy}
        />
        {error ? (
          <p className="mt-3 text-sm text-error-600">{error}</p>
        ) : sent?.devCode ? (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            <CheckCircle2 size={15} className="text-success-600" /> Dev code:{" "}
            <strong className="text-ink">{sent.devCode}</strong>
          </p>
        ) : busy && !sent ? (
          /* The first send is still in flight. Without this arm `sent` is undefined and the
             else-branch below rendered "We couldn't send an SMS." — telling the borrower the send
             had FAILED before it had even finished. */
          <p className="mt-3 text-sm text-muted">Sending your code…</p>
        ) : (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            {sent?.sent ? <CheckCircle2 size={15} className="text-success-600" /> : null}
            {sent?.sent ? "Code sent." : "We couldn't send an SMS."}{" "}
            {resends < MAX_RESENDS ? (
              <button type="button" onClick={() => send()} disabled={busy} className="font-semibold text-navy hover:underline">
                Resend ({MAX_RESENDS - resends} left)
              </button>
            ) : (
              <span>Please contact support.</span>
            )}
          </p>
        )}
        <WizardActions
          backHref="/signup/start"
          continueLabel="Verify"
          onContinue={() => confirm()}
          loading={busy}
          disabled={otp.length !== 6 || busy}
        />
      </div>
      <Reassurance />
    </div>
  );
}
