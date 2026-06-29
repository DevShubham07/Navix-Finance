"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { UploadCloud, FileCheck2, ArrowRight, Loader2, ShieldCheck } from "lucide-react";
import { useMounted } from "@/hooks/use-mounted";
import { readStoredAppId } from "@/lib/api/live-journey";
import { borrowerApi, fileToBase64, ApplicationApiError } from "@/lib/api/applications";

/**
 * Returning-borrower salary-slip step (live). A pre-approved repeat borrower skips the whole signup
 * wizard — their verified KYC + identity carry over — and only re-shares their latest 3 months of
 * payslips before the bank check and choosing an amount. The slips are stored as documents (for staff
 * review); this deliberately does NOT re-run salary verification, so the reborrow eligible limit
 * (already the full 25% of salary) is left untouched. Then we hand off to /reborrow/penny-drop.
 */
const SLIP_LABELS = [
  "Latest payslip (this month)",
  "Previous month's payslip",
  "Payslip from 2 months ago",
] as const;

type SlipFiles = [File | null, File | null, File | null];

export default function ReborrowSalaryPage() {
  const router = useRouter();
  const mounted = useMounted();
  const [appId, setAppId] = React.useState<number | null>(null);
  const [files, setFiles] = React.useState<SlipFiles>([null, null, null]);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  // Slots already persisted, so a retry after a mid-batch failure doesn't re-upload duplicates.
  const uploaded = React.useRef<Set<number>>(new Set());

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

  const allFilesUploaded = files.every((f) => f !== null);

  const setFile = (i: 0 | 1 | 2) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > 10 * 1024 * 1024) {
      setError("File must be under 10 MB.");
      return;
    }
    setError(null);
    uploaded.current.delete(i); // a replaced slip must be re-uploaded
    setFiles((prev) => {
      const next = [...prev] as SlipFiles;
      next[i] = f;
      return next;
    });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!allFilesUploaded) {
      setTouched(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      for (let i = 0; i < files.length; i += 1) {
        const f = files[i];
        if (!f || uploaded.current.has(i)) continue;
        const dataBase64 = await fileToBase64(f);
        await borrowerApi.uploadDocument(appId, {
          docType: "SALARY_SLIP",
          fileName: f.name,
          contentType: f.type || "application/octet-stream",
          dataBase64,
        });
        uploaded.current.add(i);
      }
      router.push("/reborrow/penny-drop");
    } catch (err) {
      setError(
        err instanceof ApplicationApiError
          ? `${err.message} (${err.code})`
          : "Could not upload your salary slips — please try again.",
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
      <h1 className="mb-1">Re-share your latest 3 payslips</h1>
      <p className="mb-6 text-muted">
        Welcome back. Your KYC and details carry over, so there&apos;s nothing to re-enter — just
        upload your last 3 months of payslips, confirm your bank, and you&apos;ll go straight to
        choosing an amount.
      </p>

      <form onSubmit={submit}>
        <div className="rounded border border-line bg-white p-7 shadow-sm">
          <p className="mb-3 text-sm font-semibold text-ink">Upload payslips (3 months required)</p>
          <div className="space-y-3">
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
          </div>
          {touched && !allFilesUploaded ? (
            <p className="mt-3 text-sm text-error-600">Upload all 3 payslips to continue</p>
          ) : null}
          {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}

          <button type="submit" disabled={busy} className="btn btn-gold mt-5 w-full justify-center">
            {busy ? <Loader2 size={16} className="animate-spin" /> : <ArrowRight size={16} />}
            {busy ? "Uploading…" : "Continue to bank verification"}
          </button>
        </div>
      </form>
    </div>
  );
}
