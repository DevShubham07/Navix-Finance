"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Clock } from "lucide-react";
import { Input } from "@/components/ui";
import { ApplicationApiError, borrowerApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { useBorrowerLogout, useBorrowerSession, useOnboardingGate } from "@/lib/api/live-journey";
import { normalizeMobile } from "@/lib/utils";

const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
const MOBILE_RE = /^[6-9]\d{9}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const CODE_MESSAGES: Record<string, string> = {
  DUPLICATE_PAN:
    "This PAN is already registered with us — please sign in with the mobile number it was registered under.",
  DUPLICATE_AADHAAR:
    "This Aadhaar is already registered with us — please sign in with the mobile number it was registered under.",
  INVALID_EMAIL: "Enter a valid email address.",
  INVALID_MOBILE: "Enter a valid 10-digit mobile number.",
  INVALID_PAN: "Enter a valid 10-character PAN.",
  INVALID_AADHAAR: "Enter a valid 12-digit Aadhaar number.",
};

/**
 * Onboarding-paused screen. FORM = the one details form; SUBMITTED = "under review". Outside the signup
 * layout (no step counter). OPEN means onboarding is back on — straight to the dashboard.
 */
export default function WaitlistPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const logout = useBorrowerLogout();
  const { data: session } = useBorrowerSession();
  const { data: gate, isLoading } = useOnboardingGate();

  const [fullName, setFullName] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [pan, setPan] = React.useState("");
  const [aadhaar, setAadhaar] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (session?.mobile) setMobile((m) => m || normalizeMobile(session.mobile));
  }, [session?.mobile]);

  React.useEffect(() => {
    if (gate?.gate === "OPEN") router.replace("/dashboard");
  }, [gate?.gate, router]);

  // Signed out (the gate query never runs without a session): sign in first.
  React.useEffect(() => {
    if (session === null) router.replace("/login");
  }, [session, router]);

  const nameOk = fullName.trim().length >= 2;
  const emailOk = EMAIL_RE.test(email.trim());
  const mobileOk = MOBILE_RE.test(mobile);
  const panOk = PAN_RE.test(pan);
  const aadhaarOk = /^\d{12}$/.test(aadhaar);
  const formOk = nameOk && emailOk && mobileOk && panOk && aadhaarOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await borrowerApi.submitWaitlist({ fullName: fullName.trim(), email: email.trim(), mobile, pan, aadhaar });
      await qc.invalidateQueries({ queryKey: ["onboarding-gate"] });
    } catch (err) {
      const code = err instanceof ApplicationApiError ? err.code : undefined;
      if (code === "ONBOARDING_OPEN") {
        await qc.invalidateQueries({ queryKey: ["onboarding-gate"] });
      } else {
        setError((code && CODE_MESSAGES[code]) || formatApiError(err));
      }
    } finally {
      setBusy(false);
    }
  };

  if (isLoading || !gate || gate.gate === "OPEN") {
    return <div className="container max-w-content py-16 text-center text-sm text-muted">Loading…</div>;
  }

  if (gate.gate === "SUBMITTED") {
    const s = gate.submission;
    return (
      <div className="bg-ivory">
        <div className="container max-w-content py-16">
          <div className="form-card text-center">
            <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-gold-50 text-gold-700">
              <Clock size={34} />
            </span>
            <h1 className="font-serif text-2xl text-navy">Your application is under review</h1>
            <p className="mt-3 text-sm text-muted">
              Thanks — we have your details and will get back to you soon.
            </p>
            {s ? (
              <dl className="mx-auto mt-5 max-w-xs space-y-1 text-left text-sm">
                <div className="flex justify-between gap-4"><dt className="text-muted">Name</dt><dd className="text-navy">{s.fullName}</dd></div>
                <div className="flex justify-between gap-4"><dt className="text-muted">Email</dt><dd className="text-navy">{s.email}</dd></div>
                <div className="flex justify-between gap-4"><dt className="text-muted">Mobile</dt><dd className="text-navy">{s.mobile}</dd></div>
                <div className="flex justify-between gap-4"><dt className="text-muted">PAN</dt><dd className="text-navy">{s.pan}</dd></div>
                <div className="flex justify-between gap-4"><dt className="text-muted">Aadhaar</dt><dd className="text-navy">••••{s.aadhaarLast4}</dd></div>
              </dl>
            ) : null}
            <div className="mt-6 flex flex-col gap-2 sm:flex-row sm:justify-center">
              <button type="button" onClick={() => router.push("/support")} className="btn btn-outline">
                Contact support
              </button>
              <button type="button" onClick={() => void logout()} className="btn btn-gold">
                Sign out
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-ivory">
      <div className="container max-w-content py-12">
        <form onSubmit={submit} noValidate className="form-card">
          <h1 className="font-serif text-2xl text-navy">Tell us about yourself</h1>
          <p className="lead mb-4 mt-2">
            We&apos;re not opening new applications right now. Share your details and we&apos;ll get back to you.
          </p>
          <Input label="Full name" required value={fullName} onChange={(e) => setFullName(e.target.value)} autoComplete="name"
            error={touched && !nameOk ? "Enter your full name" : undefined} />
          <Input label="Email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email"
            error={touched && !emailOk ? "Enter a valid email address" : undefined} />
          <Input label="Mobile number" required inputMode="numeric" value={mobile} onChange={(e) => setMobile(normalizeMobile(e.target.value))}
            autoComplete="tel" error={touched && !mobileOk ? "Enter a valid 10-digit mobile number" : undefined} />
          <Input label="PAN" required value={pan} onChange={(e) => setPan(e.target.value.toUpperCase().slice(0, 10))}
            placeholder="ABCDE1234F" autoCapitalize="characters" error={touched && !panOk ? "Enter a valid 10-character PAN" : undefined} />
          <Input label="Aadhaar number" required inputMode="numeric" value={aadhaar}
            onChange={(e) => setAadhaar(e.target.value.replace(/\D/g, "").slice(0, 12))}
            error={touched && !aadhaarOk ? "Enter a valid 12-digit Aadhaar number" : undefined} />
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
          <button type="submit" disabled={busy} className="btn btn-gold mt-5 w-full">
            {busy ? "Submitting…" : "Submit"}
          </button>
        </form>
      </div>
    </div>
  );
}
