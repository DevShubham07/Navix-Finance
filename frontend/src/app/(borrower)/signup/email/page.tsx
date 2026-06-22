"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Mail } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignupEmailPage() {
  const router = useRouter();
  const { applicant, updateApplicant } = useBorrowerJourney();
  const [email, setEmail] = usePersistedField(applicant.email);
  const [officialEmail, setOfficialEmail] = React.useState("");
  const [touched, setTouched] = React.useState(false);

  const ok = EMAIL_RE.test(email);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!ok) { setTouched(true); return; }
    updateApplicant({ email: email.trim() });
    router.push("/signup/bank");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          We send your sanction letter and statements to your personal email, and verify employment via your
          official one.
        </p>
        <Input
          label="Personal email"
          required
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="you@example.com"
          leftIcon={<Mail size={16} />}
          autoComplete="email"
          error={touched && !ok ? "Enter a valid email address" : undefined}
        />
        <Input
          label="Official / work email"
          type="email"
          value={officialEmail}
          onChange={(e) => setOfficialEmail(e.target.value)}
          placeholder="you@company.com"
          leftIcon={<Mail size={16} />}
          helperText="Optional — used to confirm your employer. We never email your workplace."
        />
      </div>
      <WizardActions backHref="/signup/salary" submit continueLabel="Continue" />
      <Reassurance />
    </form>
  );
}
