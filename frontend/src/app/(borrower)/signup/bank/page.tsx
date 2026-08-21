"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Landmark, Phone, FileCheck2, UploadCloud, X } from "lucide-react";
import { Input, Combobox } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { verificationApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { INDIAN_BANKS } from "@/lib/indian-banks";
import { normalizeMobile } from "@/lib/utils";

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

const STATEMENT_ACCEPT = "application/pdf,image/jpeg,image/png";
const STATEMENT_MAX_BYTES = 10 * 1024 * 1024;

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
  const formOk = bankOk && accountOk && ifscOk && mobileOk && hasStatements;

  /** Appends every valid file from the picker/drop to the existing selection — one upload or many. */
  const pickStatements = (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? []);
    e.target.value = ""; // allow re-picking the same file(s) after a removal
    if (picked.length === 0) return;
    const accepted: File[] = [];
    for (const f of picked) {
      if (!STATEMENT_ACCEPT.split(",").includes(f.type)) {
        setError("Upload PDF, JPG or PNG files only.");
        continue;
      }
      if (f.size > STATEMENT_MAX_BYTES) {
        setError("Each file must be under 10 MB.");
        continue;
      }
      accepted.push(f);
    }
    if (accepted.length > 0) {
      setError(undefined);
      setStatements((prev) => [...prev, ...accepted]);
    }
  };

  const removeStatement = (i: number) => {
    setStatements((prev) => prev.filter((_, idx) => idx !== i));
  };

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
      const keys: string[] = [];
      for (const f of statements) {
        const contentType = f.type || "application/octet-stream";
        const { key, url } = await verificationApi.presignUpload(appId, {
          docType: "BANK_STATEMENT",
          fileName: f.name,
          contentType,
        });
        await verificationApi.putToPresignedUrl(url, f, contentType);
        keys.push(key);
      }
      await verificationApi.uploadedDocuments(appId, {
        docType: "BANK_STATEMENT",
        objectKeys: keys,
        filePassword: statementPassword.trim() || undefined,
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

        <div className="mt-4">
          <label
            className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-5 text-center transition ${
              hasStatements ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
            }`}
          >
            <input
              type="file"
              accept={STATEMENT_ACCEPT}
              multiple
              className="sr-only"
              onChange={pickStatements}
            />
            {hasStatements ? (
              <>
                <FileCheck2 size={24} className="text-success-600" />
                <span className="text-sm font-semibold text-success-700">
                  {statements.length} bank statement{statements.length === 1 ? "" : "s"} added
                </span>
                <span className="text-xs text-muted">Tap to add more</span>
              </>
            ) : (
              <>
                <UploadCloud size={24} className="text-navy" />
                <span className="text-sm font-semibold text-navy">Bank statements (last 6 months)</span>
                <span className="text-xs text-muted">PDF, JPG or PNG up to 10 MB each — upload one or several</span>
              </>
            )}
          </label>
          {hasStatements ? (
            <ul className="mt-2 space-y-1">
              {statements.map((f, i) => (
                <li
                  key={`${f.name}-${f.lastModified}-${i}`}
                  className="flex items-center justify-between gap-2 rounded border border-line bg-white px-3 py-1.5 text-xs"
                >
                  <span className="truncate text-ink">{f.name}</span>
                  <button
                    type="button"
                    onClick={() => removeStatement(i)}
                    className="flex-shrink-0 rounded p-0.5 text-muted hover:bg-grey-100 hover:text-error-700"
                    aria-label={`Remove ${f.name}`}
                  >
                    <X size={14} />
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {touched && !hasStatements ? (
            <p className="mt-2 text-sm text-error-600">Upload at least one bank statement to continue.</p>
          ) : null}
        </div>

        {hasStatements ? (
          <Input
            label="PDF password (optional)"
            value={statementPassword}
            onChange={(e) => setStatementPassword(e.target.value)}
            autoComplete="off"
            helperText="If your statement is password-protected, enter the password so our team can open it."
          />
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/email" submit loading={busy} disabled={busy || !hasStatements} />
      <Reassurance />
    </form>
  );
}
