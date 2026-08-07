"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { FileCheck2, UploadCloud } from "lucide-react";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, completeStep, useSavedProfile } from "@/lib/onboarding";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const LABELS = ["Most recent month", "Previous month", "Month before that"] as const;
const ACCEPT = "application/pdf,image/jpeg,image/png";
const MAX_BYTES = 10 * 1024 * 1024;
type Slips = [File | null, File | null, File | null];

export default function SignupPayslipsPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [files, setFiles] = React.useState<Slips>([null, null, null]);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const allUploaded = files.every(Boolean);

  const pick = (i: 0 | 1 | 2) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (!ACCEPT.split(",").includes(f.type)) {
      setError("Upload a PDF, JPG or PNG.");
      return;
    }
    if (f.size > MAX_BYTES) {
      setError("File must be under 10 MB.");
      return;
    }
    setError(undefined);
    setFiles((prev) => {
      const next = [...prev] as Slips;
      next[i] = f;
      return next;
    });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allUploaded) {
      setTouched(true);
      return;
    }
    if (appId == null || saved?.monthlySalaryPaise == null) return;
    setBusy(true);
    setError(undefined);
    try {
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
      const r = await verificationApi.salary(appId, saved.monthlySalaryPaise, keys);
      setResult(r);
      if (r.status !== "FAIL") await completeStep(appId, "PAYSLIPS", router, "/signup/consent");
    } catch (err) {
      setError(formatApiError(err, "Could not upload your payslips — please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Upload your last 3 months&apos; salary slips so we can verify your income.</p>

        <div className="space-y-3">
          {([0, 1, 2] as const).map((i) => (
            <label
              key={i}
              className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-5 text-center transition ${
                files[i] ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
              }`}
            >
              <input type="file" accept={ACCEPT} className="sr-only" onChange={pick(i)} />
              {files[i] ? (
                <>
                  <FileCheck2 size={24} className="text-success-600" />
                  <span className="text-sm font-semibold text-success-700">{LABELS[i]}</span>
                  <span className="text-xs text-muted">{files[i]!.name} · tap to replace</span>
                </>
              ) : (
                <>
                  <UploadCloud size={24} className="text-navy" />
                  <span className="text-sm font-semibold text-navy">{LABELS[i]}</span>
                  <span className="text-xs text-muted">PDF, JPG or PNG up to 10 MB</span>
                </>
              )}
            </label>
          ))}
          {touched && !allUploaded ? (
            <p className="text-sm text-error-600">All 3 payslips are required to continue.</p>
          ) : null}
        </div>

        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      {/* Exactly three payslips, no exceptions (revamp.md decision 17). Gating only the submit
          handler left Continue looking available with zero of three attached — the borrower found
          out it was refused only after clicking. */}
      <WizardActions
        backHref="/signup/bank"
        submit
        continueLabel={result?.status === "FAIL" ? "Try again" : "Continue"}
        loading={busy}
        disabled={busy || !allUploaded}
      />
      <Reassurance />
    </form>
  );
}
