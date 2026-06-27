"use client";

import * as React from "react";
import { Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ShieldCheck, Lock, Mail } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { Input } from "@/components/ui";
import { loginStaff } from "@/lib/auth/staff-session";

function LoginInner() {
  const router = useRouter();
  const params = useSearchParams();
  const redirect = params.get("redirect") || "/staff/dashboard";
  const [email, setEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const canSubmit = email.trim().length > 0 && password.length > 0 && !busy;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(undefined);
    try {
      // Authenticates against the backend; the JWT lands in the httpOnly navix_staff cookie.
      await loginStaff(email.trim(), password);
      router.push(redirect);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign-in failed. Please try again.");
      setBusy(false);
    }
  };

  return (
    <div className="grid min-h-screen place-items-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <div className="mb-4 flex justify-center"><Brand href="/" tag="Staff Console" /></div>
          <h1 className="mb-1">Sign in to the console</h1>
          <p className="text-muted">Internal NAVIX Finance back office. Authorised staff only.</p>
        </div>

        <form onSubmit={submit} className="form-card">
          <div className="mb-4 flex items-center gap-2 text-sm font-semibold text-navy">
            <ShieldCheck size={16} /> Staff sign in
          </div>
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
          <button type="submit" disabled={!canSubmit} className="btn btn-navy btn-block">
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-muted">
          <Lock size={13} /> Separation of duties is enforced on every decision.
        </p>
      </div>
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
