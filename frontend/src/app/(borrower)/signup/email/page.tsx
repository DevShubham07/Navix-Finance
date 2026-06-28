"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Mail, Building2 } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { updateBorrowerName } from "@/lib/api/live-journey";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignupEmailPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { mounted, draft, appId } = useOnboarding();
  const [fullName, setFullName] = React.useState("");
  const [employer, setEmployer] = React.useState("");
  const [personalEmail, setPersonalEmail] = React.useState("");
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setFullName(draft.fullName);
    setEmployer(draft.employer);
    setPersonalEmail(draft.personalEmail);
    setOfficialEmail(draft.officialEmail);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const nameOk = fullName.trim().length > 2;
  const employerOk = employer.trim().length > 1;
  const officialOk = EMAIL_RE.test(officialEmail);
  const personalOk = personalEmail === "" || EMAIL_RE.test(personalEmail);
  const formOk = nameOk && employerOk && officialOk && personalOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ fullName: fullName.trim(), employer: employer.trim(), personalEmail: personalEmail.trim(), officialEmail: officialEmail.trim() });
    // Contact email for notifications (sanction letter + statements): prefer the personal
    // inbox the borrower actually reads, fall back to the verified work email.
    const contactEmail = (personalEmail.trim() || officialEmail.trim());
    try {
      await saveProfileSlice(appId, { fullName: fullName.trim(), employer: employer.trim(), employmentStatus: "SALARIED", email: contactEmail });
      // Map the typed name onto the live session so the header/dashboard greet them by name.
      await updateBorrowerName(fullName.trim());
      queryClient.invalidateQueries({ queryKey: ["borrower-me"] });
      const r = await verificationApi.email(appId, officialEmail.trim());
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push("/signup/address");
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your email — please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          We send your sanction letter and statements to your personal email, and confirm your employer from your
          official one.
        </p>
        <Input
          label="Full name (as per PAN)"
          required
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          placeholder="Aarav Sharma"
          autoComplete="name"
          error={touched && !nameOk ? "Enter your name as printed on your PAN" : undefined}
        />
        <Input
          label="Employer name"
          required
          value={employer}
          onChange={(e) => setEmployer(e.target.value)}
          placeholder="Infosys Limited"
          leftIcon={<Building2 size={16} />}
          autoComplete="organization"
          error={touched && !employerOk ? "Enter your employer's name" : undefined}
        />
        <Input
          label="Personal email"
          type="email"
          value={personalEmail}
          onChange={(e) => setPersonalEmail(e.target.value)}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !personalOk ? "Enter a valid email address" : undefined}
        />
        <Input
          label="Official / work email"
          required
          type="email"
          value={officialEmail}
          onChange={(e) => setOfficialEmail(e.target.value)}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Used to confirm your employer. We never email your workplace."
          error={touched && !officialOk ? "Enter your valid work email" : undefined}
        />
        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/mobile-otp" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
