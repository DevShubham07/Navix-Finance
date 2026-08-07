"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Mail } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignupEmailPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [personalEmail, setPersonalEmail] = React.useState("");
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!saved) return;
    if (saved.email) setPersonalEmail(saved.email);
    if (saved.officialEmail) setOfficialEmail(saved.officialEmail);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const personalOk = EMAIL_RE.test(personalEmail);
  const officialOk = EMAIL_RE.test(officialEmail);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!personalOk || !officialOk) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      // Personal is the contact address (approvals, reset links, statements); the work address is
      // what the verification API checks on the consent screen (revamp.md decision 15).
      await saveProfileSlice(appId, {
        email: personalEmail.trim(),
        officialEmail: officialEmail.trim(),
      });
      await completeStep(appId, "EMAIL", router, "/signup/bank");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          We send your sanction letter and statements to your personal email, and confirm your employer
          from your official one.
        </p>
        <Input
          label="Personal email address"
          required
          type="email"
          value={personalEmail}
          onChange={(e) => setPersonalEmail(e.target.value)}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !personalOk ? "Enter a valid email address" : undefined}
        />
        <Input
          label="Official work email address"
          required
          type="email"
          value={officialEmail}
          onChange={(e) => setOfficialEmail(e.target.value)}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Used to confirm your employer. We never email your workplace."
          error={touched && !officialOk ? "Enter your valid work email" : undefined}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/employer" submit loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
