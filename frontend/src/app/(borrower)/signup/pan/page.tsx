"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { ShieldCheck } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";

const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

export default function SignupPanPage() {
  const router = useRouter();
  const { applicant, updateApplicant, setKyc } = useBorrowerJourney();
  const [name, setName] = usePersistedField(applicant.fullName);
  const [pan, setPan] = usePersistedField(applicant.pan);
  const [touched, setTouched] = React.useState(false);

  const nameOk = name.trim().length > 2;
  const panOk = PAN_RE.test(pan);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!nameOk || !panOk) {
      setTouched(true);
      return;
    }
    updateApplicant({ fullName: name.trim(), pan });
    setKyc({ pan: "VERIFIED" });
    router.push("/signup/mobile-otp");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Your PAN confirms your identity and pulls your name from income-tax records. This is a soft
          check — it won&apos;t affect your credit score.
        </p>
        <Input
          label="Full name (as per PAN)"
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Aarav Sharma"
          autoComplete="name"
          error={touched && !nameOk ? "Enter your name exactly as printed on your PAN" : undefined}
        />
        <Input
          label="PAN"
          required
          value={pan}
          onChange={(e) => setPan(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10))}
          placeholder="ABCDE1234F"
          inputClassName="tracking-[0.3em] uppercase"
          helperText="10 characters, e.g. ABCDE1234F"
          error={touched && !panOk ? "Enter a valid 10-character PAN" : undefined}
        />
        <p className="mt-1 flex items-start gap-2 text-sm text-muted">
          <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
          Used only for identity and credit verification with our NBFC partner.
        </p>
      </div>
      <WizardActions submit continueLabel="Verify & continue" />
      <Reassurance />
    </form>
  );
}
