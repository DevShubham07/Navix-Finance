"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Smartphone, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { OtpInput } from "@/components/borrower/otp-input";
import { Reassurance } from "@/components/borrower/reassurance";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { signInBorrower } from "@/lib/mock/session";

const DEMO_OTP = "123456";

export default function LoginPage() {
  const router = useRouter();
  const applicant = useBorrowerJourney((s) => s.applicant);
  const [stage, setStage] = React.useState<"enter" | "verify">("enter");
  const [mobile, setMobile] = React.useState("");
  const [otp, setOtp] = React.useState("");
  const [error, setError] = React.useState<string>();

  const mobileOk = mobile.replace(/\D/g, "").length === 10;

  const send = () => {
    if (!mobileOk) { setError("Enter a valid 10-digit mobile number"); return; }
    setError(undefined);
    setStage("verify");
  };

  const verify = async (code = otp) => {
    if (code !== DEMO_OTP) { setError("Incorrect code. For this demo, use 123456."); return; }
    const name = applicant.fullName || "Aarav Sharma";
    const mob = mobile || applicant.mobile || "98765 43210";
    // Mock session (localStorage) keeps the existing demo flow working.
    signInBorrower(name, mob);
    try {
      // Live session: httpOnly navix_borrower cookie used by the BFF proxies.
      await fetch("/api/auth/borrower/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mobile: mob, otp: code, name }),
      });
    } catch {
      // Non-fatal: the dashboard resolves the live application on its own.
    }
    // The dashboard reflects the live application state (start / in-progress / active).
    router.push("/dashboard");
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
                value={mobile}
                onChange={(e) => { setMobile(e.target.value.replace(/[^\d ]/g, "").slice(0, 11)); setError(undefined); }}
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
              <button onClick={() => verify()} disabled={otp.length !== 6} className="btn btn-gold btn-block mt-4">Sign in</button>
            </>
          )}
        </div>

        <p className="mt-5 text-center text-sm text-muted">
          New to NAVIX? <Link href="/signup/pan" className="font-semibold text-navy hover:underline">Apply for an advance</Link>
        </p>
        <div className="mt-6"><Reassurance /></div>
      </div>
    </div>
  );
}
