"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Badge, Dialog, DialogHeader, DialogTitle } from "@/components/ui";
import { LoanDetailBody } from "@/components/borrower/loan-detail-body";
import { borrowerApi } from "@/lib/api/applications";

/** Colour a loan/application status: active=green, bad=red, terminal-neutral=grey, in-flight=blue. */
function statusVariant(status: string): React.ComponentProps<typeof Badge>["variant"] {
  switch (status) {
    case "ACTIVE":
      return "success";
    case "CLOSED":
    case "REPAID":
    case "CANCELLED":
      return "neutral";
    case "OVERDUE":
    case "DEFAULTED":
    case "WRITTEN_OFF":
    case "REJECTED":
    case "KYC_REJECTED":
    case "DISBURSEMENT_FAILED":
      return "error";
    default:
      return "info";
  }
}

/**
 * A reusable popup wrapping `LoanDetailBody` in dialog chrome. Wired into the dashboard card, the
 * applications rows, `/loans` and `/transactions` — each just drives `loanId`/`open`.
 */
export function LoanDetailsDialog({
  loanId,
  open,
  onClose,
}: {
  loanId: number | null;
  open: boolean;
  onClose: () => void;
}) {
  const enabled = open && loanId != null;

  // Shares the body's cache entry, so the header badge costs no extra request.
  const loanQuery = useQuery({
    queryKey: ["my-loan", loanId],
    queryFn: () => borrowerApi.loan(loanId as number),
    enabled,
  });
  const loan = loanQuery.data;

  return (
    <Dialog open={open} onClose={onClose} className="!max-w-xl">
      <DialogHeader>
        <DialogTitle>Loan details {loanId != null ? `· #${loanId}` : ""}</DialogTitle>
        {loan && (
          <Badge variant={statusVariant(loan.status)} className="self-start">
            {loan.status}
          </Badge>
        )}
      </DialogHeader>

      <LoanDetailBody loanId={loanId} enabled={enabled} onNavigate={onClose} />
    </Dialog>
  );
}
