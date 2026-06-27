"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Smartphone, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboardingStore } from "@/stores/application-store";
import { normalizeMobile } from "@/lib/utils";

export default function LoginPage() {
  const router = useRouter();
  const draftName = useOnboardingStore((s) => s.fullName);
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [mobile, setMobile] = React.useState("");
  const [otp, setOtp] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const mobileOk = mobile.length === 10;

  const send = () => {
    if (!mobileOk) { setError("Enter a valid 10-digit mobile number"); return; }
    setError(undefined);
    setStage("verify");
  };

  const verify = async (code = otp) => {
    if (code.length !== 6) { setError("Enter the 6-digit code."); return; }
    setBusy(true);
    setError(undefined);
    try {
      // Live session: the backend validates the OTP and sets the httpOnly navix_borrower cookie.
      const res = await fetch("/api/auth/borrower/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mobile, otp: code, name: draftName || undefined }),
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
              <button onClick={send} disabled={!mobileOk} className="btn btn-gold btn-block">Send code</button>
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
              ) : (
                <p className="mt-3 flex items-center gap-1.5 text-sm text-muted">
                  <CheckCircle2 size={15} className="text-success-600" /> Demo code is <strong className="text-ink">123456</strong>
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
