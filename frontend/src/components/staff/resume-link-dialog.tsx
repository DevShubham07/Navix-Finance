"use client";

/**
 * "Send the customer a link" — lets credit staff route a borrower straight back to a failed or
 * abandoned Phase-3 step (eSign, DigiLocker/Aadhaar, selfie, address) or the bureau security
 * question, since there is no magic link: the borrower still logs in via `/login?next=<step>`.
 *
 * Built on {@link Dialog}/{@link DialogHeader}/{@link DialogTitle}/{@link DialogFooter} the same
 * way `OverrideDialog`/`RetryDialog` in `verification-checks.tsx` are. Opened from a per-card
 * button there; see {@link LINKABLE_CHECKS}.
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Mail, Copy, Check, MessageSquareOff } from "lucide-react";
import { ErrorState, Input, Skeleton, toast } from "@/components/ui";
import { Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { staffApi } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import { errMessage } from "@/components/staff/pipeline/hooks";

export function ResumeLinkDialog({
  applicationId,
  checkType,
  onClose,
}: {
  applicationId: number;
  checkType: string;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const titleId = `resume-link-title-${applicationId}-${checkType}`;
  const [copied, setCopied] = React.useState(false);

  const preview = useQuery({
    queryKey: ["resume-link", applicationId, checkType],
    queryFn: () => staffApi.resumeLink(applicationId, checkType),
  });

  const invalidateAfterShare = () => {
    qc.invalidateQueries({ queryKey: ["staff-verifications", applicationId] });
    qc.invalidateQueries({ queryKey: ["staff-verification-progress", applicationId] });
    qc.invalidateQueries({ queryKey: ["resume-link", applicationId, checkType] });
  };

  // An EMAIL share publishes a notification event rather than delivering the mail itself, so the
  // toast says "queued". COPY only records the send (the clipboard write is local).
  const share = useMutation({
    mutationFn: (channel: "EMAIL" | "COPY") => staffApi.shareResumeLink(applicationId, checkType, channel),
    onSuccess: (sent, channel) => {
      invalidateAfterShare();
      if (channel === "COPY") toast.success("Link copied and logged.");
      else toast.success(sent.maskedEmail ? `Link queued for email to ${sent.maskedEmail}` : "Link queued for email");
    },
  });

  const copyLink = async () => {
    const url = preview.data?.url;
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard unavailable (permissions/insecure context) — the link stays in the input below
      // for a manual select-and-copy. Still record the send so the reopen + audit trail happen.
    }
    share.mutate("COPY");
  };

  const link = preview.data;

  return (
    <Dialog open onClose={onClose} className="!max-w-md" aria-labelledby={titleId}>
      <DialogHeader>
        <DialogTitle id={titleId}>Send link to customer</DialogTitle>
      </DialogHeader>

      {preview.isLoading ? (
        <Skeleton variant="line" rows={4} />
      ) : preview.error ? (
        <ErrorState error={preview.error} onRetry={() => void preview.refetch()} className="py-4" />
      ) : link ? (
        <>
          <p className="text-sm text-ink">
            The customer will be sent to <span className="font-semibold text-navy">{link.stepLabel}</span>.
          </p>
          {link.willReopen && (
            <p className="mt-2 rounded border border-warning-300 bg-warning-50 px-3 py-2 text-xs text-warning-800">
              This reopens the {link.stepLabel} step — their earlier attempt is set aside and they redo it.
            </p>
          )}
          <div className="mt-3 space-y-1 text-xs text-muted">
            {link.maskedEmail && <p>Email: {link.maskedEmail}</p>}
            {link.maskedMobile && <p>Mobile: {link.maskedMobile}</p>}
            {link.lastSentAt && <p>Last sent {formatDateTime(link.lastSentAt)}</p>}
          </div>
          <Input
            readOnly
            value={link.url}
            onFocus={(e) => e.currentTarget.select()}
            aria-label="Resume link"
            className="!mb-0 mt-3"
            inputClassName="font-mono text-xs"
          />

          {share.error && <p className="mt-2 text-xs text-error-700">{errMessage(share.error)}</p>}

          <p className="mt-3 text-xs text-muted">
            SMS can&apos;t carry a per-customer link until a DLT template is approved.
          </p>

          <DialogFooter>
            <button type="button" onClick={onClose} className="btn btn-sm btn-outline">
              Close
            </button>
            <button
              type="button"
              disabled={!link.maskedEmail}
              title={link.maskedEmail ? undefined : "No email on file for this customer"}
              onClick={() => share.mutate("EMAIL")}
              className="btn btn-sm btn-outline disabled:opacity-50"
            >
              {share.isPending && share.variables === "EMAIL" ? (
                <Loader2 size={14} className="animate-spin" />
              ) : (
                <Mail size={14} />
              )}
              Email link
            </button>
            <button type="button" onClick={copyLink} className="btn btn-sm btn-navy">
              {copied ? <Check size={14} /> : <Copy size={14} />}
              {copied ? "Copied" : "Copy link"}
            </button>
            <button
              type="button"
              disabled
              title="SMS can't carry a per-customer link until a DLT template is approved"
              className="btn btn-sm btn-outline opacity-50"
            >
              <MessageSquareOff size={14} /> SMS
            </button>
          </DialogFooter>
        </>
      ) : null}
    </Dialog>
  );
}
