"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Lock, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";

const policyOk = (pw: string) => pw.length >= 10 && /[A-Za-z]/.test(pw) && /[0-9]/.test(pw);

function ResetInner() {
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [done, setDone] = React.useState(false);

  const submit = async () => {
    if (!token) { setError("This reset link is invalid or has expired."); return; }
    if (!policyOk(password)) { setError("Password must be at least 10 characters and include letters and digits."); return; }
    if (password !== confirm) { setError("Passwords don't match."); return; }
    setBusy(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/borrower/reset-password", {
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
    <div className="container flex min-h-[calc(100vh-180px)] max-w-content items-center py-12">
      <div className="mx-auto w-full max-w-md">
        <div className="mb-6 text-center">
          <h1 className="mb-1">Set a new password</h1>
          <p className="text-muted">Choose a password of at least 10 characters with letters and digits.</p>
        </div>
        <div className="form-card">
          {done ? (
            <div className="text-center">
              <CheckCircle2 size={32} className="mx-auto mb-2 text-success-600" />
              <p className="text-ink">Your password has been updated. You can now sign in with it.</p>
              <Link href="/login" className="btn btn-gold btn-block mt-4">Go to sign in</Link>
            </div>
          ) : (
            <>
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
              <button onClick={submit} disabled={busy} className="btn btn-gold btn-block">
                {busy ? "Updating…" : "Update password"}
              </button>
              <p className="mt-3 text-center text-sm">
                <Link href="/login" className="font-semibold text-navy hover:underline">Back to sign in</Link>
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetInner />
    </Suspense>
  );
}
