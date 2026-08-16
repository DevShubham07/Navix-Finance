"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ShieldCheck, Lock, Mail } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { Input, Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui";
import { loginStaff, StaffLoginError } from "@/lib/auth/staff-session";

function LoginInner() {
  const router = useRouter();
  const params = useSearchParams();
  const redirect = params.get("redirect") || "/staff/dashboard";
  const superseded = params.get("reason") === "superseded";
  const [email, setEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  // Only one console session is allowed per account (all roles). A second sign-in while one is
  // live is refused with SESSION_CONFLICT; this dialog is the "continue there / continue here" choice.
  const [conflict, setConflict] = React.useState(false);

  const canSubmit = email.trim().length > 0 && password.length > 0 && !busy;

  const doLogin = async (force: boolean) => {
    setBusy(true);
    setError(undefined);
    try {
      // Authenticates against the backend; the JWT lands in the httpOnly navix_staff cookie.
      await loginStaff(email.trim(), password, force);
      router.push(redirect);
    } catch (err) {
      if (err instanceof StaffLoginError && err.code === "SESSION_CONFLICT") {
        setConflict(true);
        setBusy(false);
      } else {
        setError(err instanceof Error ? err.message : "Sign-in failed. Please try again.");
        setBusy(false);
      }
    }
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    void doLogin(false);
  };

  return (
    <div className="grid min-h-screen place-items-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <div className="mb-4 flex justify-center"><Brand href="/" tag="Staff Console" /></div>
          <h1 className="mb-1">Sign in to the console</h1>
          <p className="text-muted">Internal DhanBoost back office. Authorised staff only.</p>
        </div>

        <form onSubmit={submit} className="form-card">
          <div className="mb-4 flex items-center gap-2 text-sm font-semibold text-navy">
            <ShieldCheck size={16} /> Staff sign in
          </div>
          {superseded ? (
            <p className="mb-4 rounded border border-warning-100 bg-warning-50 px-3 py-2 text-sm text-warning-800">
              You were signed out because this account signed in on another device or browser.
            </p>
          ) : null}
          <Input
            label="Email"
            type="email"
            required
            value={email}
            onChange={(e) => { setEmail(e.target.value); setError(undefined); }}
            placeholder="you@navix.example"
            leftIcon={<Mail size={16} />}
            autoComplete="username"
          />
          <Input
            label="Password"
            type="password"
            required
            value={password}
            onChange={(e) => { setPassword(e.target.value); setError(undefined); }}
            placeholder="••••••••"
            leftIcon={<Lock size={16} />}
            autoComplete="current-password"
            error={error}
          />
          <div className="mb-3 text-right">
            <Link href="/staff/forgot-password" className="text-sm font-semibold text-navy hover:underline">
              Forgot password?
            </Link>
          </div>
          <button type="submit" disabled={!canSubmit} className="btn btn-navy btn-block">
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-muted">
          <Lock size={13} /> Separation of duties is enforced on every decision.
        </p>
      </div>

      <Dialog open={conflict} onClose={() => setConflict(false)} aria-labelledby="session-conflict-title">
        <DialogHeader>
          <DialogTitle id="session-conflict-title">You&apos;re signed in elsewhere</DialogTitle>
          <p className="text-sm text-muted">
            Only one DhanBoost console session is allowed per account. Choose where you want to stay
            signed in.
          </p>
        </DialogHeader>
        <DialogFooter>
          <button type="button" className="btn btn-outline" onClick={() => setConflict(false)} disabled={busy}>
            Continue there
          </button>
          <button
            type="button"
            className="btn btn-navy"
            onClick={() => { setConflict(false); void doLogin(true); }}
            disabled={busy}
          >
            {busy ? "Signing in…" : "Continue here"}
          </button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

export default function StaffLoginPage() {
  return (
    <Suspense fallback={<div className="grid min-h-screen place-items-center text-muted">Loading…</div>}>
      <LoginInner />
    </Suspense>
  );
}
