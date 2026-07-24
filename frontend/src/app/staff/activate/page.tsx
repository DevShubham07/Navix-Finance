"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Loader2, KeyRound, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { readEnvelopeError, formatEnvelopeError } from "@/lib/api/errors";

const policyOk = (pw: string) => pw.length >= 10 && /[A-Za-z]/.test(pw) && /[0-9]/.test(pw);

/**
 * Activate a staff account from a one-time invite token (the link in the invite email) and set a
 * password. PUBLIC — the invitee has no session; the token is the credential. On success the backend
 * signs them in (staff cookie) and we land them in the console. The token can also be pasted manually.
 */
function ActivateInner() {
  const router = useRouter();
  const params = useSearchParams();
  const [token, setToken] = React.useState(params.get("token") ?? "");
  const [name, setName] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [done, setDone] = React.useState(false);

  const submit = async () => {
    if (!token.trim()) { setError("Your invite token is missing — use the link from your invite email, or paste the token."); return; }
    if (!name.trim()) { setError("Please enter your name."); return; }
    if (!policyOk(password)) { setError("Password must be at least 10 characters and include letters and digits."); return; }
    if (password !== confirm) { setError("Passwords don't match."); return; }
    setBusy(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/staff/accept-invite", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: token.trim(), name: name.trim(), password, mobile: mobile.trim() || undefined }),
      });
      if (!res.ok) {
        setError(formatEnvelopeError(await readEnvelopeError(res, "Could not activate your account.")));
        setBusy(false);
        return;
      }
      setDone(true);
      // The backend signed us in (staff cookie is set) — go straight to the console.
      setTimeout(() => router.push("/staff/dashboard"), 900);
    } catch {
      setError("Something went wrong — please try again.");
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-md">
      <PageHeader title="Activate your account" subtitle="Set a password to finish setting up your DhanBoost staff access." />

      {done ? (
        <div className="rounded border border-success-100 bg-success-50 p-6 text-center shadow-sm">
          <CheckCircle2 size={28} className="mx-auto mb-2 text-success-600" />
          <h3 className="font-serif text-lg text-navy">Account activated</h3>
          <p className="mb-4 text-sm text-success-800">Signing you in…</p>
          <Link href="/staff/dashboard" className="btn btn-gold">Go to the console</Link>
        </div>
      ) : (
        <div className="form-card">
          <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><KeyRound size={16} /> Set up your access</div>
          <Input label="Invite token" value={token} onChange={(e) => setToken(e.target.value)} placeholder="paste your one-time token" />
          <Input label="Your name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Aanya Rao" />
          <Input label="Mobile (optional, for password recovery)" value={mobile} onChange={(e) => setMobile(e.target.value)} placeholder="9876543210" />
          <Input label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="at least 10 chars, letters + digits" />
          <Input label="Confirm password" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder="re-enter your password" />
          {error && <p className="mb-2 text-sm text-error-700">{error}</p>}
          <button
            onClick={submit}
            disabled={busy || !token.trim() || !name.trim() || !password || !confirm}
            className="btn btn-gold btn-block disabled:opacity-50"
          >
            {busy ? <Loader2 size={16} className="animate-spin" /> : null} Activate & sign in
          </button>
          <p className="mt-3 text-center text-xs text-muted">
            Already activated? <Link href="/staff/login" className="text-navy underline">Sign in</Link>
          </p>
        </div>
      )}
    </div>
  );
}

export default function StaffActivatePage() {
  return (
    <Suspense fallback={<div className="py-12 text-center text-muted">Loading…</div>}>
      <ActivateInner />
    </Suspense>
  );
}
