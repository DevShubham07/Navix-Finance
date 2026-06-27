import * as React from "react";
import { CheckCircle2, AlertTriangle, XCircle, Loader2 } from "lucide-react";
import type { StepResult } from "@/lib/api/applications";

/**
 * Renders a verification {@link StepResult} with the right tone:
 *  - PASS  → green ("verified, continue")
 *  - REVIEW→ amber ("flagged for manual review — you can still proceed")
 *  - FAIL  → red ("couldn't verify — retry")
 *  - PENDING → neutral ("in progress")
 */
export function StepResultBanner({ result }: { result: StepResult | null | undefined }) {
  if (!result) return null;

  const tone =
    result.status === "PASS"
      ? { box: "border-success-100 bg-success-50 text-success-700", Icon: CheckCircle2 }
      : result.status === "REVIEW"
        ? { box: "border-warning-100 bg-warning-50 text-warning-800", Icon: AlertTriangle }
        : result.status === "FAIL"
          ? { box: "border-error-100 bg-error-50 text-error-700", Icon: XCircle }
          : { box: "border-line bg-grey-100 text-ink", Icon: Loader2 };

  const fallback =
    result.status === "PASS"
      ? "Verified."
      : result.status === "REVIEW"
        ? "Flagged for manual review — you can still continue."
        : result.status === "FAIL"
          ? "We couldn't verify this — please try again."
          : "Verifying…";

  const Icon = tone.Icon;
  return (
    <div className={`mt-4 flex items-start gap-2 rounded border p-4 text-sm ${tone.box}`}>
      <Icon size={16} className={`mt-0.5 flex-shrink-0 ${result.status === "PENDING" ? "animate-spin" : ""}`} />
      <span>{result.message || fallback}</span>
    </div>
  );
}

/** Status pill (PASS/REVIEW/FAIL/PENDING) for the review summary board. */
export function StepStatusPill({ status }: { status: StepResult["status"] }) {
  const cls =
    status === "PASS"
      ? "bg-success-50 text-success-700"
      : status === "REVIEW"
        ? "bg-warning-50 text-warning-800"
        : status === "FAIL"
          ? "bg-error-50 text-error-700"
          : "bg-grey-200 text-muted";
  const label = status === "PASS" ? "Verified" : status === "REVIEW" ? "In review" : status === "FAIL" ? "Failed" : "Pending";
  return <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`}>{label}</span>;
}
