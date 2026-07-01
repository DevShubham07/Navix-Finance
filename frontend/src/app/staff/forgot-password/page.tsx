"use client";

import * as React from "react";
import Link from "next/link";
import { ShieldCheck, Mail, Smartphone, CheckCircle2 } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { Input } from "@/components/ui";

/**
 * Staff forgot-password. The email + mobile must match an active staff account; on a match the
 * backend emails a one-time reset link. Generic response (no account enumeration). Renders bare
 * (StaffShell treats this as a public path).
 */
export default function StaffForgotPasswordPage() {
  const [email, setEmail] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [sent, setSent] = React.useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim().includes("@")) { setError("Enter your staff email."); return; }
    if (mobile.replace(/\D/g, "").length < 10) { setError("Enter your registered mobile number."); return; }
    setBusy(true);
    setError(undefined);
    try {
      await fetch("/api/auth/staff/forgot-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email.trim(), mobile: mobile.replace(/\D/g, "") }),
      });
      setSent(true);
    } catch {
      setError("Something went wrong — please try again.");
    }
    setBusy(false);
  };

  return (
    <div className="mx-auto w-full max-w-sm">
      <div className="mb-6 text-center">
        <Brand />
        <div className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-navy">
          <ShieldCheck size={14} /> Staff password reset
        </div>
      </div>

      {sent ? (
        <div className="rounded-lg border border-line bg-white p-6 text-center shadow-sm">
          <CheckCircle2 size={30} className="mx-auto mb-2 text-success-600" />
          <p className="text-ink">If those details match a staff account, we&apos;ve emailed a reset link (valid 30 minutes).</p>
          <Link href="/staff/login" className="btn btn-navy btn-block mt-4">Back to staff sign-in</Link>
        </div>
      ) : (
        <form onSubmit={submit} className="rounded-lg border border-line bg-white p-6 shadow-sm">
          <Input
            label="Work email"
            type="email"
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(undefined); }}
            placeholder="you@navix.example"
            leftIcon={<Mail size={16} />}
            autoComplete="email"
          />
          <Input
            label="Registered mobile"
            inputMode="numeric"
            value={mobile}
            onChange={(e) => { setMobile(e.target.value); setError(undefined); }}
            placeholder="90000 00000"
            leftIcon={<Smartphone size={16} />}
            autoComplete="tel"
            error={error}
          />
          <button type="submit" disabled={busy} className="btn btn-navy btn-block">
            {busy ? "Sending…" : "Email me a reset link"}
          </button>
          <p className="mt-3 text-center text-sm">
            <Link href="/staff/login" className="font-semibold text-navy hover:underline">Back to staff sign-in</Link>
          </p>
        </form>
      )}
    </div>
  );
}
