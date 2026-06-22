"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";

export default function SignupEmploymentPage() {
  const router = useRouter();
  const { applicant, updateApplicant } = useBorrowerJourney();
  const [employer, setEmployer] = usePersistedField(applicant.employer);
  const [designation, setDesignation] = usePersistedField(applicant.designation);
  const [uan, setUan] = usePersistedField(applicant.uan);
  const [touched, setTouched] = React.useState(false);

  const ok = employer.trim().length > 1 && designation.trim().length > 1;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!ok) { setTouched(true); return; }
    updateApplicant({ employer: employer.trim(), designation: designation.trim(), uan });
    router.push("/signup/salary");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Salaried employment is the basis of your advance. We verify it before disbursal.</p>
        <Input
          label="Employer name"
          required
          value={employer}
          onChange={(e) => setEmployer(e.target.value)}
          placeholder="Infosys Limited"
          autoComplete="organization"
          error={touched && employer.trim().length <= 1 ? "Enter your employer's name" : undefined}
        />
        <Input
          label="Designation"
          required
          value={designation}
          onChange={(e) => setDesignation(e.target.value)}
          placeholder="Senior Software Engineer"
          autoComplete="organization-title"
          error={touched && designation.trim().length <= 1 ? "Enter your job title" : undefined}
        />
        <Input
          label="UAN (Universal Account Number)"
          value={uan}
          onChange={(e) => setUan(e.target.value.replace(/\D/g, "").slice(0, 12))}
          placeholder="12-digit EPFO UAN"
          inputMode="numeric"
          helperText="Optional — speeds up employment verification. Leave blank if unknown."
        />
      </div>
      <WizardActions backHref="/signup/mobile-otp" submit continueLabel="Continue" />
      <Reassurance />
    </form>
  );
}
