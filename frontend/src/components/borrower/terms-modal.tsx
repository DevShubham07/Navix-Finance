"use client";

import * as React from "react";
import { Dialog } from "@/components/ui";
import { TERMS_DOC_HREF } from "@/lib/onboarding";

/**
 * The Terms & Conditions the borrower accepts on screen 1. Decline simply closes — the caller's
 * checkbox stays unticked and Continue stays disabled.
 *
 * The `!` size overrides are required: globals.css's un-layered `.modal { max-width: 460px }`
 * outranks plain Tailwind utilities (same trick as application-detail-dialog.tsx).
 */
export function TermsModal({
  open,
  onDecline,
  onAgree,
}: {
  open: boolean;
  onDecline: () => void;
  onAgree: () => void;
}) {
  const [text, setText] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open || text !== null) return;
    fetch(TERMS_DOC_HREF)
      .then((r) => (r.ok ? r.text() : Promise.reject(new Error(String(r.status)))))
      .then(setText)
      .catch(() => setText("Terms & Conditions could not be loaded. Please try again."));
  }, [open, text]);

  return (
    <Dialog
      open={open}
      onClose={onDecline}
      aria-label="Terms and Conditions"
      className="!max-w-3xl !w-[min(52rem,94vw)]"
    >
      <div className="border-b border-line pb-3">
        <p className="mb-1 text-xs font-semibold uppercase tracking-[0.14em] text-gold-dark">DhanBoost borrower agreement</p>
        <h3 className="font-serif text-xl text-navy">Terms &amp; Conditions</h3>
        <p className="mt-1 text-sm text-muted">Review the agreement before recording your consent.</p>
      </div>

      <div className="mt-3 max-h-[60vh] overflow-y-auto rounded-lg border border-line bg-ivory/40 p-4 pr-3 text-left">
        {text === null ? (
          <p className="py-8 text-center text-sm text-muted">Loading…</p>
        ) : (
          <pre className="whitespace-pre-wrap font-sans text-[13px] leading-7 text-ink">{text}</pre>
        )}
      </div>

      <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
        <span className="text-xs text-muted">Version: terms-and-conditions@1</span>
        <div className="flex gap-2">
        <button type="button" className="btn btn-outline" onClick={onDecline}>
          Decline
        </button>
        <button type="button" className="btn btn-gold" onClick={onAgree} disabled={text === null}>
          Agree
        </button>
        </div>
      </div>
    </Dialog>
  );
}
