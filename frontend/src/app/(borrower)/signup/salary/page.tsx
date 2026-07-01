"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { TrendingUp, UploadCloud, FileCheck2 } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, saveProfileSlice, nextAfterStep } from "@/lib/onboarding";
import { verificationApi, rupeesToPaise, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { eligibleLimit } from "@/lib/calc/loan-math";
import { formatINR0 } from "@/lib/utils";

const DAY_OPTIONS = Array.from({ length: 31 }, (_, i) => ({ value: i + 1, label: `${i + 1}` }));

const SLIP_LABELS = [
  "Latest payslip (this month)",
  "Previous month's payslip",
  "Payslip from 2 months ago",
] as const;

type SlipFiles = [File | null, File | null, File | null];

export default function SignupSalaryPage() {
  const router = useRouter();
  const { mounted, draft, appId } = useOnboarding();
  const [salary, setSalary] = React.useState(0);
  const [salaryDay, setSalaryDay] = React.useState(1);
  const [files, setFiles] = React.useState<SlipFiles>([null, null, null]);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setSalary(draft.monthlySalary);
    setSalaryDay(draft.salaryDay || 1);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const salaryOk = salary >= 10000;
  const limit = salaryOk ? eligibleLimit(salary) : 0;
  const allFilesUploaded = files.every((f) => f !== null);
  const formOk = salaryOk && allFilesUploaded;

  const setFile = (i: 0 | 1 | 2) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) { setError("File must be under 10 MB."); return; }
    setError(undefined);
    setFiles((prev) => {
      const next = [...prev] as SlipFiles;
      next[i] = f;
      return next;
    });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    draft.patch({ monthlySalary: salary, salaryDay });
    const monthlySalaryPaise = rupeesToPaise(salary);
    try {
      await saveProfileSlice(appId, { monthlySalaryPaise });
      // Upload all 3 slips to S3, collecting their keys.
      const keys: string[] = [];
      for (const f of files) {
        if (!f) continue;
        const contentType = f.type || "application/octet-stream";
        const { key, url } = await verificationApi.presignUpload(appId, { docType: "SALARY_SLIP", fileName: f.name, contentType });
        await verificationApi.putToPresignedUrl(url, f, contentType);
        keys.push(key);
      }
      const r = await verificationApi.salary(appId, monthlySalaryPaise, keys);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") router.push(nextAfterStep("/signup/penny-drop"));
    } catch (err) {
      setError(formatApiError(err, "Could not verify your salary — please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Your net monthly salary sets your eligible limit (up to 25%) and your salary-day repayment date. Upload 3
          months of payslips so we can verify your income.
        </p>
        <Input
          label="Net monthly salary"
          required
          inputMode="numeric"
          value={salary ? String(salary) : ""}
          onChange={(e) => setSalary(Number(e.target.value.replace(/\D/g, "")) || 0)}
          placeholder="84000"
          leftIcon={<span className="font-serif text-muted">₹</span>}
          error={touched && !salaryOk ? "Enter your monthly salary (minimum ₹10,000)" : undefined}
          helperText="The amount credited to your salary account each month"
        />
        <Select
          label="Salary credit day"
          value={salaryDay}
          onChange={(e) => setSalaryDay(Number(e.target.value))}
          options={DAY_OPTIONS}
          helperText="Day of the month your salary lands — your repayment date"
        />

        <div className="mt-4 space-y-3">
          <p className="text-sm font-semibold text-ink">Upload payslips (3 months required)</p>
          {([0, 1, 2] as const).map((i) => (
            <label
              key={i}
              className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-5 text-center transition ${
                files[i] ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
              }`}
            >
              <input type="file" accept="image/*,application/pdf" className="sr-only" onChange={setFile(i)} />
              {files[i] ? (
                <>
                  <FileCheck2 size={24} className="text-success-600" />
                  <span className="text-sm font-semibold text-success-700">{SLIP_LABELS[i]}</span>
                  <span className="text-xs text-muted">{files[i]!.name} · tap to replace</span>
                </>
              ) : (
                <>
                  <UploadCloud size={24} className="text-navy" />
                  <span className="text-sm font-semibold text-navy">{SLIP_LABELS[i]}</span>
                  <span className="text-xs text-muted">PDF or image up to 10 MB</span>
                </>
              )}
            </label>
          ))}
          {touched && !allFilesUploaded ? (
            <p className="text-sm text-error-600">Upload all 3 payslips to continue</p>
          ) : null}
        </div>

        {salaryOk && (
          <div className="mt-4 flex items-center gap-4 rounded border border-gold-soft bg-gold-50/60 p-5">
            <span className="grid h-12 w-12 flex-shrink-0 place-items-center rounded-full bg-gold-soft text-gold-dark">
              <TrendingUp size={22} />
            </span>
            <div>
              <div className="text-sm text-muted">Your eligible limit (25% of salary)</div>
              <div className="font-serif text-2xl font-bold text-navy">{formatINR0(limit)}</div>
            </div>
          </div>
        )}

        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/bureau" submit continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"} loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
