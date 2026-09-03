"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Send } from "lucide-react";
import { Badge } from "@/components/ui";
import { Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import {
  SEVERITY_BADGE_VARIANT,
  caseFailureDetail,
  caseFailureLabel,
  caseFailureRemedy,
  isNoFailure,
  type CaseFailureReason,
  type CaseFailureSeverity,
} from "@/components/staff/case-failure";
import { customersApi, staffApi } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

/**
 * Why one customer's file has no usable credit decision, and which providers were actually tried.
 *
 * A portal `Dialog` rather than the `InfoTooltip` the rest of the table uses for hints: the tooltip
 * is absolutely positioned and `.staff-table-scroll` is an `overflow-x: auto` clipping context, so a
 * panel this size would be cut off at the cell edge.
 *
 * Nothing here shows a provider's own error text. The reason is a machine name resolved to authored
 * copy in `case-failure.ts`, and an attempt shows only who was called, for what, and what status came
 * back — request and response bodies carry PAN, date of birth and consent OTPs and stay in the ADMIN
 * provider workbench.
 */
export function CaseFailureDialog({
  customerId,
  customerName,
  onClose,
}: {
  customerId: number;
  customerName?: string | null;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["customer-failure", customerId],
    queryFn: () => customersApi.caseFailure(customerId),
  });

  const detail = q.data;
  const reason = (detail?.reason ?? null) as CaseFailureReason | null;
  const severity = (detail?.severity ?? "INFO") as CaseFailureSeverity;
  const applicationId = detail?.applicationId ?? null;
  const isKba = reason === "BUREAU_KBA_PENDING" || reason === "BUREAU_KBA_SKIPPED";

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["customer-failure", customerId] });
    qc.invalidateQueries({ queryKey: ["customers"] });
    qc.invalidateQueries({ queryKey: ["customer", customerId] });
    qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
  };

  // Deliberately NOT automatic. Every bureau pull is billable and is a real credit inquiry on a real
  // person's file, so it is armed by a first click and only fires on the second.
  const [armed, setArmed] = React.useState(false);
  const retry = useMutation({
    mutationFn: () => staffApi.retryVerification(applicationId as number, "BUREAU", {}),
    onSuccess: () => {
      setArmed(false);
      invalidate();
    },
  });

  const notify = useMutation({
    mutationFn: () => customersApi.notifyBureauChallenge(applicationId as number),
    onSuccess: invalidate,
  });

  return (
    <Dialog open onClose={onClose} aria-label="Why this file is stuck">
      <DialogHeader>
        <DialogTitle>{customerName ? `${customerName} — why this file is stuck` : "Why this file is stuck"}</DialogTitle>
      </DialogHeader>

      {q.isLoading ? (
        <div className="h-24 animate-pulse rounded bg-grey-100" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : !detail || isNoFailure(reason) ? (
        <p className="text-sm text-muted">
          Nothing is outstanding on this customer&apos;s latest file.
        </p>
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={SEVERITY_BADGE_VARIANT[severity]} size="sm">
              {caseFailureLabel(reason)}
            </Badge>
            {applicationId != null && (
              <span className="font-mono text-xs text-muted">Application #{applicationId}</span>
            )}
          </div>

          <p className="text-sm text-ink">{caseFailureDetail(reason)}</p>

          <div className="rounded border border-line bg-grey-50 px-3 py-2">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted">What to do</p>
            <p className="mt-1 text-sm text-ink">{caseFailureRemedy(reason)}</p>
          </div>

          <div>
            <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">
              Providers tried
            </p>
            {detail.attempts.length === 0 ? (
              // An empty list is genuinely ambiguous, so say which it is. The oldest applications are
              // both the most likely to be investigated and the most likely to be past the window.
              <p className="text-sm text-muted">
                No provider call history retained. Calls are kept for {detail.attemptRetentionDays} days,
                so an older attempt may simply have aged out rather than never having been made.
              </p>
            ) : (
              <ul className="flex flex-col gap-1">
                {detail.attempts.map((a, i) => (
                  <li
                    key={`${a.provider}-${a.at}-${i}`}
                    className="flex flex-wrap items-center gap-x-2 gap-y-0.5 border-b border-line py-1 text-sm last:border-b-0"
                  >
                    <span className="font-mono text-xs font-semibold text-ink">{a.provider}</span>
                    <span className="text-xs text-muted">{a.operation}</span>
                    <span
                      className={
                        a.succeeded ? "text-xs text-success-700" : "text-xs text-error-700"
                      }
                    >
                      {a.httpStatus != null ? `HTTP ${a.httpStatus}` : "no response"}
                    </span>
                    <span className="ml-auto whitespace-nowrap text-xs text-muted">
                      {formatDateTime(a.at)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {(retry.error || notify.error) && (
            <p className="text-sm text-error-700">{errMessage(retry.error ?? notify.error)}</p>
          )}
          {retry.isSuccess && <p className="text-sm text-success-700">Credit check re-run.</p>}
          {notify.isSuccess && (
            <p className="text-sm text-success-700">
              {notify.data?.notified ? "Borrower notified." : "Borrower was already notified."}
            </p>
          )}
        </div>
      )}

      <DialogFooter>
        <button onClick={onClose} className="btn btn-sm btn-outline">
          Close
        </button>
        {isKba && applicationId != null && (
          <PermissionGate permission="customer:manage">
            <button
              onClick={() => notify.mutate()}
              disabled={notify.isPending}
              className="btn btn-sm btn-outline disabled:opacity-50"
            >
              {notify.isPending ? <Loader2 size={13} className="animate-spin" /> : <Send size={13} />}
              Ask the borrower
            </button>
          </PermissionGate>
        )}
        {detail?.retryable && applicationId != null && (
          <PermissionGate permission="customer:manage">
            <button
              onClick={() => (armed ? retry.mutate() : setArmed(true))}
              disabled={retry.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
              title="Runs a fresh, billable credit inquiry against this borrower's file"
            >
              {retry.isPending ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />}
              {armed ? "Confirm — this is a billable inquiry" : "Re-run credit check"}
            </button>
          </PermissionGate>
        )}
      </DialogFooter>
    </Dialog>
  );
}
