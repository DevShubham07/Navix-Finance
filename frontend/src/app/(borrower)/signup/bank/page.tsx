"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, ShieldCheck } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";

const BANKS = [
  "HDFC Bank",
  "ICICI Bank",
  "State Bank of India",
  "Axis Bank",
  "Kotak Mahindra Bank",
  "Bank of Baroda",
  "Punjab National Bank",
  "Yes Bank",
  "IndusInd Bank",
  "IDFC FIRST Bank",
].map((b) => ({ value: b, label: b }));

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

export default function SignupBankPage() {
  const router = useRouter();
  const { applicant, updateApplicant } = useBorrowerJourney();
  const [bankName, setBankName] = usePersistedField(applicant.bankName || "HDFC Bank");
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = usePersistedField(applicant.ifsc);
  const [touched, setTouched] = React.useState(false);

  const accountOk = account.replace(/\s/g, "").length >= 9;
  const ifscOk = IFSC_RE.test(ifsc);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!accountOk || !ifscOk) { setTouched(true); return; }
    const digits = account.replace(/\D/g, "");
    updateApplicant({ bankName, accountLast4: digits.slice(-4), ifsc });
    router.push("/signup/financials");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Add the account where your salary is credited. This is also where your advance will be deposited.
        </p>
        <Select label="Bank" value={bankName} onChange={(e) => setBankName(e.target.value)} options={BANKS} />
        <Input
          label="Account number"
          required
          inputMode="numeric"
          value={account}
          onChange={(e) => setAccount(e.target.value.replace(/[^\d ]/g, ""))}
          placeholder="Enter your salary account number"
          leftIcon={<Landmark size={16} />}
          error={touched && !accountOk ? "Enter a valid account number" : undefined}
        />
        <Input
          label="IFSC code"
          required
          value={ifsc}
          onChange={(e) => setIfsc(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 11))}
          placeholder="HDFC0001234"
          inputClassName="uppercase tracking-wider"
          error={touched && !ifscOk ? "Enter a valid 11-character IFSC" : undefined}
        />
        <p className="mt-1 flex items-start gap-2 text-sm text-muted">
          <ShieldCheck size={16} className="mt-0.5 flex-shrink-0 text-success-600" />
          We confirm the account with a ₹1 penny-drop before disbursal — the name must match your PAN.
        </p>
      </div>
      <WizardActions backHref="/signup/email" submit continueLabel="Continue" />
      <Reassurance />
    </form>
  );
}
