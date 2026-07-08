"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { TrendingUp, UploadCloud, FileCheck2, Loader2, ArrowRight, ArrowLeft, AlertTriangle } from "lucide-react";
import { Input } from "@/components/ui";
import { SalaryCalendar } from "@/components/borrower/salary-calendar";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useLiveApplication } from "@/lib/api/live-journey";
import { borrowerApi, verificationApi, rupeesToPaise, ApplicationApiError, type StepResult } from "@/lib/api/applications";
import { eligibleLimit } from "@/lib/calc/loan-math";
import { formatINR0 } from "@/lib/utils";

/**
 * Reborrow salary step (Issue 4). A returning borrower who tapped "Borrow again" lands here for BOTH
 * forks: they re-confirm the day their salary is credited on the <SalaryCalendar>, re-declare their
 * monthly salary and re-upload 3 months of payslips (income may have changed since their last advance).
 * The salary-credit day is persisted in the same verify call. On success we route from the live status:
 *  - PRE_APPROVED (clean history) → /loan/apply to choose an amount (salary day now set = no re-pick).
 *  - REVIEW_PENDING (past overdue) → /loan/status to track the KYC approver's re-review.
 *
 * Only reborrow forks belong here — anyone else is bounced to their dashboard.
 */

const SLIP_LABELS = [
  "Latest payslip (this month)",
  "Previous month's payslip",
  "Payslip from 2 months ago",
] as const;

type SlipFiles = [File | null, File | null, File | null];

export default function ReborrowSalaryPage() {
  const router = useRouter();
  const { appId, app, isLoading } = useLiveApplication();
  const [salary, setSalary] = React.useState(0);
  const [salaryDay, setSalaryDay] = React.useState(1);
  const [files, setFiles] = React.useState<SlipFiles>([null, null, null]);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  const isReborrow = app?.status === "PRE_APPROVED" || app?.status === "REVIEW_PENDING";

  // Prefill the declared salary from the carried-over KYC profile (they can still edit it if it changed).
  const profileQuery = useQuery({
    queryKey: ["live-profile", appId],
    queryFn: () => borrowerApi.getProfile(appId as number),
    enabled: appId != null,
  });
  const seededRef = React.useRef(false);
  React.useEffect(() => {
    if (seededRef.current) return;
    const paise = profileQuery.data?.monthlySalaryPaise;
    if (paise != null && paise > 0) {
      seededRef.current = true;
      setSalary(Math.round(paise / 100));
    }
  }, [profileQuery.data]);

  // Only a reborrow (PRE_APPROVED / REVIEW_PENDING) belongs here. Once the live application resolves to
  // anything else — or there's none — send them to their dashboard.
  React.useEffect(() => {
    if (isLoading) return;
    if (!app || !isReborrow) router.replace("/dashboard");
  }, [isLoading, app, isReborrow, router]);

  const salaryOk = salary >= 10000;
  const limit = salaryOk ? eligibleLimit(salary) : 0;
  const allFilesUploaded = files.every((f) => f !== null);
  const formOk = salaryOk && allFilesUploaded;

  // Store the PICKED day, not the due date's day-of-month — the due date is clamped to the
  // landing month's length (picked 31 → due 28 Feb), so getDate() would corrupt the pick.
  const handlePickDay = React.useCallback((_due: Date, day: number) => setSalaryDay(day), []);

  const setFile = (i: 0 | 1 | 2) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) {
      setError("File must be under 10 MB.");
      return;
    }
    setError(undefined);
    setFiles((prev) => {
      const next = [...prev] as SlipFiles;
      next[i] = f;
      return next;
    });
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
    const monthlySalaryPaise = rupeesToPaise(salary);
    try {
      // Upload each payslip to S3, collecting its object key.
      const keys: string[] = [];
      for (const f of files) {
        if (!f) continue;
        const contentType = f.type || "application/octet-stream";
        const { key, url } = await verificationApi.presignUpload(appId, {
          docType: "SALARY_SLIP",
          fileName: f.name,
          contentType,
        });
        await verificationApi.putToPresignedUrl(url, f, contentType);
        keys.push(key);
      }
      // Re-verify salary AND persist the confirmed salary-credit day in the same call.
      const r = await verificationApi.salary(appId, monthlySalaryPaise, keys, salaryDay);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") {
        // Clean history → choose an amount; held for re-review → track the review on the status page.
        router.push(app?.status === "PRE_APPROVED" ? "/loan/apply" : "/loan/status");
      }
    } catch (err) {
      setError(
        err instanceof ApplicationApiError
          ? `${err.message} (${err.code})`
          : "Could not verify your salary — please try again.",
      );
    } finally {
      setBusy(false);
    }
  };

  // Still resolving the live application (before the redirect effect can decide).
  if (appId == null || (isLoading && !app)) {
    return (
      <div className="container max-w-container py-10">
        <div className="h-72 animate-pulse rounded border border-line bg-white" />
      </div>
    );
  }

  // Not a reborrow — the redirect effect is taking them to /dashboard; render nothing meanwhile.
  if (!isReborrow) {
    return (
      <div className="container max-w-container py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  return (
    <div className="container max-w-container py-10">
      <h1 className="mb-1">Confirm your salary</h1>
      <p className="mb-7 text-muted">
        Welcome back. Confirm the day your salary lands and re-share 3 months of payslips — we&apos;ll link
        your due date to that day, repaid in one instalment.
      </p>

      <form onSubmit={submit} noValidate className="space-y-8">
        <SalaryCalendar value={salaryDay} onPick={handlePickDay} />

        <div className="rounded border border-line bg-white p-6 shadow-sm">
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
          {error ? (
            <div className="mt-3 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
              <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" /> {error}
            </div>
          ) : null}
        </div>

        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Link href="/reloan" className="btn btn-outline btn-sm order-2 sm:order-1">
            <ArrowLeft size={16} /> Back
          </Link>
          <button type="submit" disabled={busy} className="btn btn-gold order-1 sm:order-2">
            {busy ? <Loader2 size={16} className="animate-spin" /> : null}
            {result?.status === "FAIL" ? "Try again" : "Confirm & continue"} <ArrowRight size={16} />
          </button>
        </div>
      </form>
    </div>
  );
}
