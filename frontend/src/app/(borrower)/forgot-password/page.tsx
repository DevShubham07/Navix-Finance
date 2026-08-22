"use client";

import * as React from "react";
import Link from "next/link";
import { Mail, Smartphone, CheckCircle2 } from "lucide-react";
import { Input, Turnstile } from "@/components/ui";
import { Reassurance } from "@/components/borrower/reassurance";
import { normalizeMobile } from "@/lib/utils";
import { config } from "@/lib/config";
import { formatEnvelopeError, readEnvelopeError } from "@/lib/api/errors";

/**
 * Borrower forgot-password. The email + mobile must match the account on record; on a match the
 * backend emails a one-time reset link. The response is deliberately generic (it never reveals
 * whether the details matched).
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [sent, setSent] = React.useState(false);
  const [captcha, setCaptcha] = React.useState("");
  const [captchaKey, setCaptchaKey] = React.useState(0);
  // The widget failed to load/render (usually a domain not on the site key's allowlist). Submit is
  // unblocked rather than leaving a dead button — the backend still decides.
  const [captchaBroken, setCaptchaBroken] = React.useState(false);

  const submit = async () => {
    if (!email.includes("@")) { setError("Enter the email on your account."); return; }
    if (mobile.length < 10) { setError("Enter your 10-digit mobile number."); return; }
    setBusy(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/borrower/forgot-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, mobile, captchaToken: captcha }),
      });
      // This used to setSent(true) unconditionally, so a rejection (rate limit, failed captcha)
      // rendered as the success acknowledgement and the user just never got an email.
      if (res.ok) {
        setSent(true);
      } else {
        setError(formatEnvelopeError(await readEnvelopeError(res, "Something went wrong — please try again.")));
        setCaptcha("");
        setCaptchaKey((k) => k + 1); // the token is spent — a retry needs a fresh challenge
      }
    } catch {
      setError("Something went wrong — please try again.");
    }
    setBusy(false);
  };

  return (
    <div className="container flex min-h-[calc(100vh-180px)] max-w-content items-center py-12">
      <div className="mx-auto w-full max-w-md">
        <div className="mb-6 text-center">
          <h1 className="mb-1">Reset your password</h1>
          <p className="text-muted">
            Enter the email and mobile on your account and we&apos;ll email you a secure reset link.
          </p>
        </div>

        <div className="form-card">
          {sent ? (
            <div className="text-center">
              <CheckCircle2 size={32} className="mx-auto mb-2 text-success-600" />
              <p className="text-ink">
                If those details match an account, we&apos;ve emailed a reset link. It expires in 30 minutes.
              </p>
              <Link href="/login" className="btn btn-gold btn-block mt-4">Back to sign in</Link>
            </div>
          ) : (
            <>
              <Input
                label="Email"
                type="email"
                value={email}
                onChange={(e) => { setEmail(e.target.value); setError(undefined); }}
                placeholder="you@example.com"
                leftIcon={<Mail size={16} />}
                autoComplete="email"
              />
              <Input
                label="Mobile number"
                inputMode="numeric"
                value={mobile}
                onChange={(e) => { setMobile(normalizeMobile(e.target.value)); setError(undefined); }}
                placeholder="98765 43210"
                leftIcon={<Smartphone size={16} />}
                autoComplete="tel"
                error={error}
              />
              <Turnstile
                action="borrower-forgot-password"
                onToken={setCaptcha}
                onError={() => setCaptchaBroken(true)}
                resetKey={captchaKey}
              />
              <button
                onClick={submit}
                disabled={busy || (!!config.turnstileSiteKey && !captcha && !captchaBroken)}
                className="btn btn-gold btn-block"
              >
                {busy ? "Sending…" : "Email me a reset link"}
              </button>
              <p className="mt-3 text-center text-sm">
                <Link href="/login" className="font-semibold text-navy hover:underline">Back to sign in</Link>
              </p>
            </>
          )}
        </div>
        <div className="mt-6"><Reassurance /></div>
      </div>
    </div>
  );
}
