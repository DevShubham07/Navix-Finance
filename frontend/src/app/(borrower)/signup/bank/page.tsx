"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, Phone } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { formatApiError } from "@/lib/api/errors";
import { INDIAN_BANKS, isListedIndianBank } from "@/lib/indian-banks";
import { normalizeMobile } from "@/lib/utils";

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

export default function SignupBankPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [bankChoice, setBankChoice] = React.useState("");
  const [otherBank, setOtherBank] = React.useState("");
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!saved) return;
    if (saved.salaryBank) {
      if (isListedIndianBank(saved.salaryBank)) setBankChoice(saved.salaryBank);
      else {
        setBankChoice("Other bank");
        setOtherBank(saved.salaryBank);
      }
    }
    if (saved.salaryAccountNumber) setAccount(saved.salaryAccountNumber);
    if (saved.salaryIfsc) setIfsc(saved.salaryIfsc);
    if (saved.salaryAccountMobile) setMobile(saved.salaryAccountMobile);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const bank = bankChoice === "Other bank" ? otherBank : bankChoice;
  const bankOk = bank.trim().length > 1 && (bankChoice === "Other bank" || isListedIndianBank(bankChoice));
  const accountOk = /^\d{9,18}$/.test(account);
  const ifscOk = IFSC_RE.test(ifsc);
  const mobileOk = /^[6-9]\d{9}$/.test(mobile);
  const formOk = bankOk && accountOk && ifscOk && mobileOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await saveProfileSlice(appId, {
        salaryBank: bank.trim(),
        salaryAccountNumber: account,
        salaryIfsc: ifsc,
        salaryAccountMobile: mobile,
      });
      await completeStep(appId, "BANK", router, "/signup/payslips");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Add the salary account your employer credits each month.</p>
        <Input
          label="Salary bank"
          required
          value={bankChoice}
          onChange={(e) => setBankChoice(e.target.value)}
          placeholder="Search or select a bank"
          list="indian-bank-options"
          leftIcon={<Landmark size={16} />}
          error={touched && !bankOk ? "Select a listed bank or choose Other bank" : undefined}
        />
        <datalist id="indian-bank-options">
          {INDIAN_BANKS.map((name) => <option key={name} value={name} />)}
          <option value="Other bank" />
        </datalist>
        {bankChoice === "Other bank" ? (
          <Input
            label="Other bank name"
            required
            value={otherBank}
            onChange={(e) => setOtherBank(e.target.value)}
            placeholder="Enter your bank's name"
            error={touched && otherBank.trim().length < 2 ? "Enter your bank's name" : undefined}
          />
        ) : null}
        <Input
          label="Account number"
          required
          inputMode="numeric"
          value={account}
          onChange={(e) => setAccount(e.target.value.replace(/\D/g, "").slice(0, 18))}
          placeholder="00123456789012"
          error={touched && !accountOk ? "Enter a valid account number" : undefined}
        />
        <Input
          label="IFSC code"
          required
          value={ifsc}
          onChange={(e) => setIfsc(e.target.value.toUpperCase().replace(/\s/g, "").slice(0, 11))}
          placeholder="HDFC0001234"
          autoCapitalize="characters"
          error={touched && !ifscOk ? "Enter a valid 11-character IFSC" : undefined}
        />
        <Input
          label="Mobile number linked to this account"
          required
          inputMode="numeric"
          value={mobile}
          onChange={(e) => setMobile(normalizeMobile(e.target.value))}
          placeholder="9876543210"
          leftIcon={<Phone size={16} />}
          error={touched && !mobileOk ? "Enter a valid 10-digit mobile number" : undefined}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/email" submit loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
