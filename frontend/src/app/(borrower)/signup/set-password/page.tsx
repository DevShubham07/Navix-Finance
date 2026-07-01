"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Lock, ArrowRight } from "lucide-react";
import { Input } from "@/components/ui";
import { readEnvelopeError, formatEnvelopeError } from "@/lib/api/errors";

const policyOk = (pw: string) => pw.length >= 10 && /[A-Za-z]/.test(pw) && /[0-9]/.test(pw);

/**
 * Optional "set a password" step, offered right after the mobile-OTP step. Lets the borrower add a
 * password so they can later sign in without an OTP. Fully skippable — they can also add one from
 * their profile later. The session cookie already exists (from mobile-otp), so this posts to the
 * authenticated set-password endpoint.
 */
export default function SignupSetPasswordPage() {
  const router = useRouter();
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const save = async () => {
    if (!policyOk(password)) { setError("Password must be at least 10 characters and include letters and digits."); return; }
    if (password !== confirm) { setError("Passwords don't match."); return; }
    setBusy(true);
    setError(undefined);
    try {
      const res = await fetch("/api/auth/borrower/set-password", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      if (!res.ok) {
        setError(formatEnvelopeError(await readEnvelopeError(res, "Could not set your password.")));
        setBusy(false);
        return;
      }
      router.push("/signup/email");
    } catch {
      setError("Something went wrong — please try again.");
      setBusy(false);
    }
  };

  return (
    <div>
      <p className="mb-6 text-muted">
        Add a password so you can sign in without an OTP next time. This is optional — you can skip it
        now and add one later from your profile.
      </p>
      <div className="form-card">
        <Input
          label="Password"
          type="password"
          value={password}
          onChange={(e) => { setPassword(e.target.value); setError(undefined); }}
          placeholder="At least 10 characters"
          leftIcon={<Lock size={16} />}
          autoComplete="new-password"
          helperText="10+ characters, including letters and digits"
        />
        <Input
          label="Confirm password"
          type="password"
          value={confirm}
          onChange={(e) => { setConfirm(e.target.value); setError(undefined); }}
          placeholder="Re-enter your password"
          leftIcon={<Lock size={16} />}
          autoComplete="new-password"
          error={error}
        />
        <button onClick={save} disabled={busy} className="btn btn-gold btn-block">
          {busy ? "Saving…" : "Set password & continue"} <ArrowRight size={16} />
        </button>
        <button onClick={() => router.push("/signup/email")} className="btn btn-outline btn-block mt-2">
          Skip for now
        </button>
      </div>
    </div>
  );
}
