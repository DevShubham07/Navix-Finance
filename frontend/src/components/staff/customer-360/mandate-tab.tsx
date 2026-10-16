"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, ShieldOff } from "lucide-react";
import { EmptyState, Skeleton, toast } from "@/components/ui";
import { useCan, errMessage } from "@/components/staff/pipeline/hooks";
import { staffApi } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const STATUS_LABEL: Record<string, string> = {
  PENDING_AUTHORIZATION: "Awaiting borrower authorisation",
  ACTIVE: "Active",
  AUTHORIZATION_FAILED: "Authorisation failed",
  EXPIRED: "Expired",
  BANK_UNSUPPORTED: "Bank does not support online mandates",
};

/** Latest eMandate attempt. Only the last four of the account is ever shown; the auth link never is. */
export function MandateTab({
  applicationId,
  status,
  onChanged,
}: {
  applicationId: number | null;
  status: string | null;
  onChanged?: () => void;
}) {
  const can = useCan();
  const qc = useQueryClient();
  const [notes, setNotes] = React.useState("");
  const q = useQuery({
    queryKey: ["staff-mandate", applicationId],
    queryFn: () => staffApi.mandate(applicationId as number),
    enabled: applicationId != null,
  });
  const override = useMutation({
    mutationFn: () => staffApi.mandateOverride(applicationId as number, notes.trim()),
    onSuccess: () => {
      toast.success("Mandate overridden; moved to disbursement");
      setNotes("");
      qc.invalidateQueries({ queryKey: ["staff-mandate", applicationId] });
      onChanged?.();
    },
  });

  if (applicationId == null) {
    return <EmptyState icon={<ShieldOff size={28} />} title="No application yet." hint="A mandate is registered after sanction." />;
  }
  if (q.isLoading) return <Skeleton className="h-24 w-full" />;
  if (q.isError) return <p className="text-sm text-error-700">{errMessage(q.error)}</p>;
  const m = q.data;
  if (!m) {
    return <EmptyState icon={<ShieldOff size={28} />} title="No mandate started." hint="The borrower registers it in the offer journey." />;
  }

  const rows: [string, string][] = [
    ["Status", STATUS_LABEL[m.status] ?? m.status],
    ["UMRN", m.umrn ?? "—"],
    ["Bank", m.bankName ?? "—"],
    ["Account", m.accountLast4 ? `•••• ${m.accountLast4}` : "—"],
    ["Authorised at", m.authenticatedAt ? formatDateTime(m.authenticatedAt) : "—"],
    ["Attempt", String(m.attemptNo)],
  ];
  if (m.rejectReason) rows.push(["Reject reason", m.rejectReason]);

  return (
    <div className="space-y-4">
      <dl className="grid max-w-xl grid-cols-[10rem_1fr] gap-y-2 text-sm">
        {rows.map(([k, v]) => (
          <React.Fragment key={k}>
            <dt className="text-muted">{k}</dt>
            <dd className="font-medium text-navy">{v}</dd>
          </React.Fragment>
        ))}
      </dl>

      {m.overridable && status === "SANCTIONED" && can("mandate:override") && (
        <div className="max-w-md space-y-2 rounded border border-line bg-grey-50 p-3">
          <p className="text-xs text-muted">
            This bank cannot register an online mandate, so the borrower cannot proceed by retrying. Overriding
            sends the file to disbursement without a mandate. This action is logged.
          </p>
          <textarea
            aria-label="Reason for overriding the mandate"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Why you're overriding (required, goes in the audit trail)"
            rows={2}
            className="w-full rounded border border-line px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => override.mutate()}
            disabled={override.isPending || !notes.trim()}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {override.isPending && <Loader2 size={14} className="animate-spin" />} Override mandate
          </button>
          {override.isError && <p className="text-xs text-error-700">{errMessage(override.error)}</p>}
        </div>
      )}
    </div>
  );
}
