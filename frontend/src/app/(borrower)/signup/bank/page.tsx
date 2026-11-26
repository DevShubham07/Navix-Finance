"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, Phone } from "lucide-react";
import { Input, Combobox } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { verificationApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { INDIAN_BANKS } from "@/lib/indian-banks";
import { normalizeMobile } from "@/lib/utils";
import { BankStatementUpload, uploadBankStatements } from "@/components/borrower/bank-statement-upload";

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;


export default function SignupBankPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [bank, setBank] = React.useState("");
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  // Single flexible upload: any number of bank statements (1+), not a fixed count of 6.
  const [statements, setStatements] = React.useState<File[]>([]);
  // Banks mail statements as encrypted PDFs; without the key a reviewer simply cannot open the file.
  const [statementPassword, setStatementPassword] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  // Account Aggregator on => the statement upload moves to the AA step's fallback. Until the view
  // lands (or if it fails) behave exactly as before: statements required.
  const [aaOn, setAaOn] = React.useState(false);
  React.useEffect(() => {
    if (appId == null) return;
    verificationApi.aaView(appId).then((v) => setAaOn(v.enabled)).catch(() => {});
  }, [appId]);

  React.useEffect(() => {
    if (!saved) return;
    if (saved.salaryBank) setBank(saved.salaryBank);
    if (saved.salaryAccountNumber) setAccount(saved.salaryAccountNumber);
    if (saved.salaryIfsc) setIfsc(saved.salaryIfsc);
    if (saved.salaryAccountMobile) setMobile(saved.salaryAccountMobile);
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const bankOk = bank.trim().length > 1;
  const accountOk = /^\d{9,18}$/.test(account);
  const ifscOk = IFSC_RE.test(ifsc);
  const mobileOk = /^[6-9]\d{9}$/.test(mobile);
  const hasStatements = statements.length > 0;
  const needStatements = !aaOn;
  const formOk = bankOk && accountOk && ifscOk && mobileOk && (hasStatements || !needStatements);

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
      if (needStatements) await uploadBankStatements(appId, statements, statementPassword);
      await completeStep(appId, "BANK", router, aaOn ? "/signup/account-aggregator" : "/signup/payslips");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Add the salary account your employer credits each month, and upload your bank statements
          (ideally the last 6 months).
        </p>
        <Combobox
          label="Salary bank"
          required
          options={INDIAN_BANKS}
          value={bank}
          onChange={setBank}
          placeholder="Search or select a bank"
          leftIcon={<Landmark size={16} />}
          error={touched && !bankOk ? "Enter your salary bank" : undefined}
        />
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

        {needStatements ? (
          <BankStatementUpload
            files={statements}
            onFiles={setStatements}
            password={statementPassword}
            onPassword={setStatementPassword}
            onError={setError}
            showRequired={touched}
          />
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/email" submit loading={busy} disabled={busy || (needStatements && !hasStatements)} />
      <Reassurance />
    </form>
  );
}
