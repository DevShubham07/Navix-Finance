"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ShieldCheck, Lock, CheckCircle2 } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { Input } from "@/components/ui";

const policyOk = (pw: string) => pw.length >= 10 && /[A-Za-z]/.test(pw) && /[0-9]/.test(pw);

function StaffResetInner() {
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [done, setDone] = React.useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token) { setError("This reset link is invalid or has expired."); return; }
    if (!policyOk(password)) { setError("Password must be at least 10 characters and include letters and digits."); return; }
    if (password !== confirm) { setError("Passwords don't match."); return; }
    setBusy(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/staff/reset-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token, password }),
      });
      if (!res.ok) {
        let msg = "Could not reset your password.";
        try { const env = await res.json(); msg = env?.error?.message ?? env?.error ?? msg; } catch { /* keep default */ }
        setError(typeof msg === "string" ? msg : "Could not reset your password.");
        setBusy(false);
        return;
      }
      setDone(true);
    } catch {
      setError("Something went wrong — please try again.");
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto w-full max-w-sm">
      <div className="mb-6 text-center">
        <Brand />
        <div className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-navy">
          <ShieldCheck size={14} /> Set a new password
        </div>
      </div>

      {done ? (
        <div className="rounded-lg border border-line bg-white p-6 text-center shadow-sm">
          <CheckCircle2 size={30} className="mx-auto mb-2 text-success-600" />
          <p className="text-ink">Your password has been updated. You can now sign in with it.</p>
          <Link href="/staff/login" className="btn btn-navy btn-block mt-4">Go to staff sign-in</Link>
        </div>
      ) : (
        <form onSubmit={submit} className="rounded-lg border border-line bg-white p-6 shadow-sm">
          <Input
            label="New password"
            type="password"
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(undefined); }}
            placeholder="At least 10 characters"
            leftIcon={<Lock size={16} />}
            autoComplete="new-password"
            helperText="10+ characters, including letters and digits"
          />
          <Input
            label="Confirm new password"
            type="password"
            value={confirm}
            onChange={(e) => { setConfirm(e.target.value); setError(undefined); }}
            placeholder="Re-enter your password"
            leftIcon={<Lock size={16} />}
            autoComplete="new-password"
            error={error}
          />
          <button type="submit" disabled={busy} className="btn btn-navy btn-block">
            {busy ? "Updating…" : "Update password"}
          </button>
          <p className="mt-3 text-center text-sm">
            <Link href="/staff/login" className="font-semibold text-navy hover:underline">Back to staff sign-in</Link>
          </p>
        </form>
      )}
    </div>
  );
}

export default function StaffResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <StaffResetInner />
    </Suspense>
  );
}
