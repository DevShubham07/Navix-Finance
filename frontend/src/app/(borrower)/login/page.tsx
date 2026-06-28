"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Smartphone, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { Reassurance } from "@/components/borrower/reassurance";
import { requestBorrowerOtp, clearBorrowerClientState, type OtpRequestResult } from "@/lib/api/live-journey";
import { normalizeMobile } from "@/lib/utils";

export default function LoginPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [mobile, setMobile] = React.useState("");
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [sentInfo, setSentInfo] = React.useState<OtpRequestResult>();
  const [resendCount, setResendCount] = React.useState(0);

  const mobileOk = mobile.length === 10;
  const MAX_RESENDS = 3;

  const send = async () => {
    if (!mobileOk) { setError("Enter a valid 10-digit mobile number"); return; }
    if (resendCount >= MAX_RESENDS) {
      setError("Maximum resend attempts reached. Please try again later or contact support.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const info = await requestBorrowerOtp(mobile);
      setSentInfo(info);
      setResendCount((n) => n + 1);
      setStage("verify");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not send the code.");
    }
    setBusy(false);
  };

  const verify = async (code = otp) => {
    if (code.length !== 6) { setError("Enter the 6-digit code."); return; }
    setBusy(true);
    setError(undefined);
    try {
      // Live session: the backend validates the OTP and sets the httpOnly navix_borrower cookie.
      // A returning borrower's display name comes from their stored profile (resolved server-side),
      // never from a leftover onboarding draft on this device — that would leak across users.
      const res = await fetch("/api/auth/borrower/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mobile, otp: code }),
      });
      if (!res.ok) {
        let msg = "Incorrect code — please try again.";
        try {
          const env = await res.json();
          msg = env?.error?.message ?? env?.error ?? msg;
        } catch { /* keep default */ }
        setError(typeof msg === "string" ? msg : "Incorrect code — please try again.");
        setBusy(false);
        return;
      }
      // Drop every trace of a prior user on this browser before entering the app — the persisted
      // onboarding draft (name/PAN/Aadhaar/bank, which the profile page renders verbatim), the
      // in-flight app-id pointer, and the React Query cache — so the new session can never render
      // the previous borrower's identity/PII. (Logout already does this; do it on login too, since a
      // different user can sign in without the previous one ever signing out.)
      clearBorrowerClientState();
      queryClient.clear();
      // The dashboard reflects the live application state (start / in-progress / active).
      router.push("/dashboard");
    } catch {
      setError("Something went wrong — please try again.");
      setBusy(false);
    }
  };

  return (
    <div className="container flex min-h-[calc(100vh-180px)] max-w-content items-center py-12">
      <div className="mx-auto w-full max-w-md">
        <div className="mb-6 text-center">
          <h1 className="mb-1">Welcome back</h1>
          <p className="text-muted">Sign in to manage your advance or finish your application.</p>
        </div>

        <div className="form-card">
          {stage === "enter" ? (
            <>
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
              />
              <button onClick={send} disabled={!mobileOk || busy} className="btn btn-gold btn-block">{busy ? "Sending…" : "Send code"}</button>
            </>
          ) : (
            <>
              <p className="mb-1 text-sm font-semibold text-ink">Enter the 6-digit code</p>
              <p className="mb-4 text-sm text-muted">
                Sent to <strong className="text-ink">{mobile}</strong> ·{" "}
                <button type="button" onClick={() => setStage("enter")} className="font-semibold text-navy hover:underline">Change</button>
              </p>
              <OtpInput value={otp} onChange={(v) => { setOtp(v); setError(undefined); }} onComplete={verify} />
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
                    <button type="button" onClick={() => send()} disabled={busy} className="font-semibold text-navy hover:underline">
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
                    <button type="button" onClick={() => send()} disabled={busy} className="font-semibold text-navy hover:underline">
                      Try again ({MAX_RESENDS - resendCount} left)
                    </button>
                  ) : (
                    <span>Please contact support.</span>
                  )}
                </p>
              )}
              <button onClick={() => verify()} disabled={otp.length !== 6 || busy} className="btn btn-gold btn-block mt-4">Sign in</button>
            </>
          )}
        </div>

        <p className="mt-5 text-center text-sm text-muted">
          New to NAVIX? <Link href="/signup/mobile-otp" className="font-semibold text-navy hover:underline">Apply for an advance</Link>
        </p>
        <div className="mt-6"><Reassurance /></div>
      </div>
    </div>
  );
}
