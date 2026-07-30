"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CheckCircle2, Loader2, ShieldCheck } from "lucide-react";
import { OtpInput } from "@/components/borrower/otp-input";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, nextAfterStep, BUREAU_CONSENT_TEXT } from "@/lib/onboarding";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { fetchBorrowerSession, requestBorrowerOtp, type OtpRequestResult } from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";

/**
 * Step-up consent for the credit-bureau enquiry: the borrower re-confirms their identity with a
 * one-time code AND explicitly authorises the bureau pull. Only once both land does the PAN get
 * fetched — so no external identity lookup happens before consent is on record.
 *
 * The OTP is verified SERVER-SIDE (inside the consent write). It is deliberately not checked here
 * via `ensureBorrowerSession`: that helper returns an existing session before it ever looks at the
 * code, and a session always exists by this point in the wizard — so using it would let any six
 * digits through and make the whole step decorative.
 */
export default function SignupPanConsentPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [mobile, setMobile] = React.useState("");
  const [otp, setOtp] = React.useState("");
  const [consent, setConsent] = React.useState(false);
  /** Consent accepted by the backend — the OTP is spent, so a PAN retry must not ask for another. */
  const [consentDone, setConsentDone] = React.useState(false);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [sentInfo, setSentInfo] = React.useState<OtpRequestResult | null>(null);
  const [resendCount, setResendCount] = React.useState(0);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  const requested = React.useRef(false);

  // The backend caps OTP sends at 5 per mobile per 15 minutes, counted across the WHOLE session —
  // and the mobile step already allows 1 + 3. Two here keeps the borrower inside that budget rather
  // than letting them walk into an opaque rate-limit wall.
  const MAX_RESENDS = 2;

  React.useEffect(() => {
    if (!mounted) return;
    if (appId == null) { router.replace("/signup/mobile-otp"); return; }
    // No PAN captured yet (deep link / refreshed past the previous step) — nothing to consent to.
    if (!draft.pan) router.replace("/signup/pan");
  }, [mounted, appId, draft.pan, router]);

  const sendOtp = React.useCallback(async (to?: string) => {
    const number = to ?? mobile;
    if (!number) return;
    setBusy(true);
    setError(undefined);
    try {
      const info = await requestBorrowerOtp(number);
      setSentInfo(info);
      setResendCount((n) => n + 1);
    } catch (e) {
      // Surface the backend's own message (it carries the rate-limit wording) rather than a generic one.
      setError(formatApiError(e, "Could not send the code — please try again."));
    } finally {
      setBusy(false);
    }
  }, [mobile]);

  // Resolve the mobile from the live session (authoritative — the draft is just a local cache) and
  // send the first code automatically, once.
  React.useEffect(() => {
    if (!mounted || appId == null || requested.current) return;
    requested.current = true;
    void (async () => {
      const session = await fetchBorrowerSession();
      const number = session?.mobile ?? draft.mobile;
      if (!number) { router.replace("/signup/mobile-otp"); return; }
      setMobile(number);
      await sendOtp(number);
    })();
  }, [mounted, appId, draft.mobile, router, sendOtp]);

  const confirm = async (code = otp) => {
    // Once consent is recorded the OTP is spent and the audit row exists, so a retry of a failed PAN
    // fetch must NOT demand a fresh code — the borrower would be asked to re-authorise something
    // already on file, and could be out of resends.
    if (!consentDone && (!consent || code.length !== 6)) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      if (!consentDone) {
        // Consent first: this call consumes the OTP, and the row it writes is the audit record.
        await verificationApi.bureauConsent(appId, code, BUREAU_CONSENT_TEXT);
        setConsentDone(true);
      }
      // Consent is on file — now the PAN may be fetched.
      const r = await verificationApi.pan(appId, draft.pan);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push(nextAfterStep("/signup/bureau"));
    } catch (e) {
      setError(formatApiError(e, "Could not confirm your consent — please try again."));
      // Only clear the code when it was actually spent on a failed consent call; if consent already
      // succeeded, the code is irrelevant to the retry.
      if (!consentDone) setOtp("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Confirm it&apos;s you</p>
        <p className="mb-5 text-sm text-muted">
          {consentDone
            ? "Consent confirmed. Finishing your PAN check…"
            : <>We&apos;ve sent a 6-digit code to <strong className="text-ink">{mobile || "your mobile"}</strong>. Entering it confirms the consent below.</>}
        </p>

        <OtpInput
          value={otp}
          onChange={(v) => { setOtp(v); setError(undefined); }}
          onComplete={(v) => void confirm(v)}
          disabled={busy || consentDone}
        />

        {error ? (
          <p className="mt-3 text-sm text-error-600">{error}</p>
        ) : sentInfo?.devCode ? (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            <CheckCircle2 size={15} className="text-success-600" /> Dev code: <strong className="text-ink">{sentInfo.devCode}</strong>
          </p>
        ) : sentInfo?.sent ? (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            <CheckCircle2 size={15} className="text-success-600" /> Code sent.{" "}
            {resendCount <= MAX_RESENDS ? (
              <button type="button" onClick={() => void sendOtp()} disabled={busy} className="font-semibold text-navy hover:underline">
                Resend ({MAX_RESENDS - resendCount + 1} left)
              </button>
            ) : (
              <span className="text-muted">No more resends — contact support if you didn&apos;t receive your code.</span>
            )}
          </p>
        ) : busy ? (
          <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Sending your code…
          </p>
        ) : null}

        <label className="checkbox mt-6">
          <input
            type="checkbox"
            checked={consent}
            onChange={(e) => { setConsent(e.target.checked); setTouched(false); }}
            disabled={consentDone}
            required
          />
          <span>{BUREAU_CONSENT_TEXT}</span>
        </label>
        {touched && !consent ? (
          <p className="mt-2 text-sm text-error-600">Please provide your consent to continue.</p>
        ) : null}

        <p className="mt-4 flex items-start gap-2 text-sm text-muted">
          <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
          This is a soft enquiry — it won&apos;t affect your credit score.
        </p>

        <StepResultBanner result={result} />
      </div>
      <WizardActions
        backHref="/signup/pan"
        continueLabel={consentDone || result?.status === "FAIL" ? "Try again" : "Confirm & continue"}
        onContinue={() => void confirm()}
        loading={busy}
        disabled={busy || (!consentDone && (otp.length !== 6 || !consent))}
      />
      <Reassurance />
    </div>
  );
}
