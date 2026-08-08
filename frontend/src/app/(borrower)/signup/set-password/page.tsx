"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Lock, ArrowRight } from "lucide-react";
import { Input } from "@/components/ui";
import { readEnvelopeError, formatEnvelopeError } from "@/lib/api/errors";
import { useOnboarding, completeStep } from "@/lib/onboarding";
import { passwordOk, PASSWORD_HINT } from "@/lib/password";

/**
 * Optional "set a password" step. Skippable — the borrower can add one later from their profile.
 * The session cookie already exists, so this posts to the authenticated set-password endpoint.
 */
export default function SignupSetPasswordPage() {
  const router = useRouter();
  const { appId } = useOnboarding();
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  const go = async () => {
    if (appId == null) return;
    await completeStep(appId, "SET_PASSWORD", router, "/signup/employment");
  };

  const save = async () => {
    if (!passwordOk(password)) {
      setError(`Password must be ${PASSWORD_HINT}.`);
      return;
    }
    if (password !== confirm) {
      setError("Passwords don't match.");
      return;
    }
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
      await go();
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
        <button type="button" onClick={() => router.push("/signup/otp")} className="mb-4 text-sm font-semibold text-navy hover:underline">
          Back
        </button>
        <Input
          label="Password"
          type="password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
            setError(undefined);
          }}
          placeholder={`${PASSWORD_HINT}`}
          leftIcon={<Lock size={16} />}
          autoComplete="new-password"
          helperText={PASSWORD_HINT}
        />
        <Input
          label="Confirm password"
          type="password"
          value={confirm}
          onChange={(e) => {
            setConfirm(e.target.value);
            setError(undefined);
          }}
          placeholder="Re-enter your password"
          leftIcon={<Lock size={16} />}
          autoComplete="new-password"
          error={error}
        />
        <button onClick={save} disabled={busy} className="btn btn-gold btn-block">
          {busy ? "Saving…" : "Set password & continue"} <ArrowRight size={16} />
        </button>
        <button onClick={go} className="btn btn-outline btn-block mt-2">
          Skip for now
        </button>
      </div>
    </div>
  );
}
