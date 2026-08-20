"use client";

import { Paperclip } from "lucide-react";

/**
 * A link to a borrower-uploaded payment screenshot, shown wherever a repayment's transaction id
 * appears (borrower's own payment history, the accountant's verify queue, staff loan-detail dialogs,
 * the company transactions ledger). `url` is a short-lived presigned S3 GET — resolved server-side
 * per read, so it is safe to use as-is and never needs its own upload/auth handling here.
 *
 * Renders nothing when there is no proof on file (e.g. a staff walk-in repayment recorded without
 * an upload, or a disbursal row which never has one).
 */
export function PaymentProofLink({ url, className }: { url?: string | null; className?: string }) {
  if (!url) return null;
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className={`inline-flex items-center gap-1 font-semibold text-navy underline-offset-2 hover:underline ${className ?? ""}`}
      title="View payment screenshot"
      onClick={(e) => e.stopPropagation()}
    >
      <Paperclip size={11} /> Proof
    </a>
  );
}
