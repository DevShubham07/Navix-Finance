"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { UploadCloud, FileCheck2, ArrowRight, Loader2, ShieldCheck } from "lucide-react";
import { useMounted } from "@/hooks/use-mounted";
import { readStoredAppId } from "@/lib/api/live-journey";
import { borrowerApi, fileToBase64, ApplicationApiError } from "@/lib/api/applications";

/**
 * Returning-borrower salary-slip step (live). A pre-approved repeat borrower skips the whole signup
 * wizard — their verified KYC + identity carry over — and only re-shares their latest salary slip
 * before choosing an amount. The slip is stored as a document (for staff review); it deliberately
 * does NOT re-run salary verification, so the reborrow eligible limit (already reduced by any current
 * outstanding) is left untouched. Then we hand off to /loan/apply.
 */
export default function ReborrowSalaryPage() {
  const router = useRouter();
  const mounted = useMounted();
  const [appId, setAppId] = React.useState<number | null>(null);
  const [file, setFile] = React.useState<File | null>(null);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!mounted) return;
    const id = readStoredAppId();
    if (id == null) {
      router.replace("/reloan");
      return;
    }
    setAppId(id);
  }, [mounted, router]);

  if (!mounted || appId == null) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) {
      setError("File must be under 10 MB.");
      return;
    }
    setError(null);
    setFile(f);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      setTouched(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const dataBase64 = await fileToBase64(file);
      await borrowerApi.uploadDocument(appId, {
        docType: "SALARY_SLIP",
        fileName: file.name,
        contentType: file.type || "application/octet-stream",
        dataBase64,
      });
      router.push("/loan/apply");
    } catch (err) {
      setError(
        err instanceof ApplicationApiError
          ? `${err.message} (${err.code})`
          : "Could not upload your salary slip — please try again.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="container max-w-content py-10">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gold-dark">
        <ShieldCheck size={14} /> Pre-approved
      </div>
      <h1 className="mb-1">One last thing — your latest salary slip</h1>
      <p className="mb-6 text-muted">
        Welcome back. Your KYC and details carry over, so there&apos;s nothing to re-enter — just
        upload your most recent payslip and you&apos;ll go straight to choosing an amount.
      </p>

      <form onSubmit={submit}>
        <div className="rounded border border-line bg-white p-7 shadow-sm">
          <label
            className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-8 text-center transition ${
              file ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
            }`}
          >
            <input type="file" accept="image/*,application/pdf" className="sr-only" onChange={onPick} />
            {file ? (
              <>
                <FileCheck2 size={28} className="text-success-600" />
                <span className="text-sm font-semibold text-success-700">Latest payslip</span>
                <span className="text-xs text-muted">{file.name} · tap to replace</span>
              </>
            ) : (
              <>
                <UploadCloud size={28} className="text-navy" />
                <span className="text-sm font-semibold text-navy">Upload your latest payslip</span>
                <span className="text-xs text-muted">PDF or image up to 10 MB</span>
              </>
            )}
          </label>
          {touched && !file ? (
            <p className="mt-3 text-sm text-error-600">Please upload your latest salary slip to continue</p>
          ) : null}
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}

          <button type="submit" disabled={busy} className="btn btn-gold mt-5 w-full justify-center">
            {busy ? <Loader2 size={16} className="animate-spin" /> : <ArrowRight size={16} />}
            {busy ? "Uploading…" : "Continue to amount"}
          </button>
        </div>
      </form>
    </div>
  );
}
