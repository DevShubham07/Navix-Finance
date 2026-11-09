"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ban, Loader2 } from "lucide-react";
import { Dialog, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "@/components/ui/toast";
import { customersApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

/** Who the prompt is about — set by a call site right after a repayment verifies. */
export interface PostVerifyTarget {
  customerId: number;
  /** Shown in the question; falls back to "Customer #id". */
  customerName?: string | null;
}

/**
 * The "should this customer be able to take another loan?" prompt, opened by BOTH repayment-verify
 * call sites (the accountant queue and the customer pop-up's Repayment tab) after a verify succeeds.
 *
 * The default answer is yes: "Yes, keep eligible" is the primary button, holds focus, and is what
 * Enter does — it only closes. Blocking is the quiet secondary path, reveals a required reason, and
 * is confirmed by a second, destructive-styled click. A customer already blocked gets a one-line
 * notice instead of the question, so a second verify on a blocked file cannot re-ask it.
 *
 * `customersApi.block` writes an indefinite rejection-register row (`BLOCKED_BY_STAFF`) that stops
 * every future application and reborrow server-side; this dialog only decides, it never enforces.
 */
export function PostVerifyBlockDialog({
  target,
  onClose,
  onBlocked,
}: {
  target: PostVerifyTarget | null;
  onClose: () => void;
  /** After a successful block, before the dialog closes — call sites refresh their queries here. */
  onBlocked?: () => void;
}) {
  const open = target != null;
  const customerId = target?.customerId ?? null;
  const qc = useQueryClient();
  const titleId = React.useId();
  const reasonId = React.useId();
  const [blocking, setBlocking] = React.useState(false);
  const [reason, setReason] = React.useState("");
  const [touched, setTouched] = React.useState(false);

  // The pop-up's Repayment tab already holds the customer detail, but the queue row does not, so the
  // dialog reads the block state itself — through the shared key, so a warm cache answers at once.
  const detailQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open && customerId != null,
  });
  const alreadyBlocked = detailQ.data?.blocked === true;

  // Reset the inner state whenever a new target opens the dialog.
  React.useEffect(() => {
    if (open) {
      setBlocking(false);
      setReason("");
      setTouched(false);
    }
  }, [open, customerId]);

  const block = useMutation({
    mutationFn: () => customersApi.block(customerId as number, reason.trim()),
    onSuccess: () => {
      toast.success(`${displayName} blocked from future loans`);
      qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
      qc.invalidateQueries({ queryKey: ["customer", customerId] });
      qc.invalidateQueries({ queryKey: ["customers-page"] });
      qc.invalidateQueries({ queryKey: ["customers-summary"] });
      onBlocked?.();
      onClose();
    },
  });

  const displayName = target?.customerName?.trim() || `Customer #${target?.customerId ?? ""}`;
  const reasonValue = reason.trim();
  const reasonMissing = reasonValue.length === 0;
  const reasonTooLong = reasonValue.length > 300;
  const busy = block.isPending;

  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} size="md" aria-labelledby={titleId}>
      <DialogHeader>
        <DialogTitle id={titleId}>Payment verified</DialogTitle>
      </DialogHeader>
      {alreadyBlocked ? (
        <p className="text-sm text-slate" data-testid="already-blocked">
          <Ban size={14} className="mr-1 inline -mt-0.5 text-error-700" aria-hidden />
          Already blocked{detailQ.data?.blockedReason ? ` · ${detailQ.data.blockedReason}` : ""}
        </p>
      ) : (
        <div className="text-sm text-slate">
          <p>
            Should <span className="font-semibold text-ink">{displayName}</span> be able to take another loan?
          </p>
          {blocking && (
            <div className="mt-3">
              <label htmlFor={reasonId} className="mb-1 block text-xs font-semibold text-ink">
                Reason for blocking <span className="text-error-700">*</span>
              </label>
              <textarea
                id={reasonId}
                className="w-full rounded border border-line px-3 py-2 text-sm"
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                onBlur={() => setTouched(true)}
                maxLength={300}
                placeholder="e.g. Repeated bounced repayments; paid only after legal notice"
                autoFocus
                disabled={busy}
              />
              <div className="mt-1 flex items-center justify-between text-xs">
                <span className={touched && reasonMissing ? "text-error-700" : "text-muted"}>
                  {touched && reasonMissing ? "A reason is required." : "Recorded on the customer's file; staff-only."}
                </span>
                <span className="text-muted">{reasonValue.length}/300</span>
              </div>
            </div>
          )}
          {block.error ? <p className="mt-2 text-sm text-error-700">{formatApiError(block.error)}</p> : null}
        </div>
      )}
      {/* Hand-rolled footer (not DialogFooter): the Dialog's focus trap lands on the FIRST focusable
          in DOM order, so the primary "Yes, keep eligible" is first in the DOM — Enter keeps the
          customer eligible — and CSS `order-*` puts it last visually, where a primary belongs. */}
      <div className="mt-6 flex justify-end gap-2">
        {alreadyBlocked ? (
          <button type="button" className="btn btn-sm btn-navy" onClick={onClose}>
            Done
          </button>
        ) : !blocking ? (
          <>
            <button type="button" className="btn btn-sm btn-navy order-2" onClick={onClose}>
              Yes, keep eligible
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline order-1"
              onClick={() => setBlocking(true)}
              disabled={detailQ.isLoading}
            >
              No — block future loans
            </button>
          </>
        ) : (
          <>
            <button type="button" className="btn btn-sm btn-outline" onClick={() => setBlocking(false)} disabled={busy}>
              Back
            </button>
            <button
              type="button"
              className="btn btn-sm border-error-600 bg-error-600 text-white hover:bg-error-700 disabled:opacity-50"
              onClick={() => {
                setTouched(true);
                if (reasonMissing || reasonTooLong) return;
                block.mutate();
              }}
              disabled={busy || reasonMissing || reasonTooLong}
            >
              {busy ? <Loader2 size={13} className="animate-spin" /> : <Ban size={13} />} Block customer
            </button>
          </>
        )}
      </div>
    </Dialog>
  );
}
