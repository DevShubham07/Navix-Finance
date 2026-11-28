"use client";

import * as React from "react";
import { Paperclip } from "lucide-react";
import { ProofViewer } from "@/components/ui/proof-viewer";

/**
 * A "Proof" button for an uploaded payment screenshot, shown wherever a repayment's transaction id
 * appears (borrower's own payment history, the accountant's verify queue, the collections queues,
 * staff loan-detail dialogs, the company transactions ledger). Clicking opens the in-app zoomable
 * viewer (`ProofViewer`); the viewer also offers "open in a new tab". `url` is a short-lived
 * presigned S3 GET — resolved server-side per read, so it is safe to use as-is.
 *
 * `footer` puts the checker's decision buttons under the image (it receives `close`).
 *
 * Renders nothing when there is no proof on file (e.g. a staff walk-in repayment recorded without
 * an upload, or a disbursal row which never has one).
 */
export function PaymentProofLink({
  url,
  className,
  label = "Proof",
  footer,
}: {
  url?: string | null;
  className?: string;
  label?: string;
  footer?: (close: () => void) => React.ReactNode;
}) {
  const [open, setOpen] = React.useState(false);
  const close = React.useCallback(() => setOpen(false), []);
  if (!url) return null;
  return (
    <>
      <button
        type="button"
        className={`inline-flex items-center gap-1 font-semibold text-navy underline-offset-2 hover:underline ${className ?? ""}`}
        title="View payment screenshot"
        onClick={(e) => {
          e.stopPropagation();
          setOpen(true);
        }}
      >
        <Paperclip size={11} /> {label}
      </button>
      <ProofViewer src={url} open={open} onClose={close} footer={footer?.(close)} />
    </>
  );
}
