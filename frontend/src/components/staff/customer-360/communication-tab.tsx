"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Loader2 } from "lucide-react";
import { EmptyState, ErrorState, Select, Skeleton, toast } from "@/components/ui";
import { CallLogRow, RemarksTab, Section } from "@/components/staff/detail-parts";
import { errMessage } from "@/components/staff/live-pipeline";
import { customersApi, type LoanView } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

export const CALL_TYPES = [
  { value: "OUTBOUND", label: "Outbound" },
  { value: "INBOUND", label: "Inbound" },
  { value: "MISSED", label: "Missed" },
];

export const CALL_OUTCOMES = [
  { value: "CONNECTED", label: "Connected" },
  { value: "NO_ANSWER", label: "No answer" },
  { value: "CALLBACK", label: "Callback" },
  { value: "REFUSED", label: "Refused" },
  { value: "WRONG_NUMBER", label: "Wrong number" },
];

/** The log-call form alone, shared by the Communication tab and the Follow-ups tab. */
export function LogCallForm({
  customerId,
  loans,
  defaultOutcome = "CONNECTED",
  focusCallback = false,
  onDone,
}: {
  customerId: number;
  loans: LoanView[];
  defaultOutcome?: string;
  focusCallback?: boolean;
  onDone?: () => void;
}) {
  const qc = useQueryClient();
  const [callType, setCallType] = React.useState("OUTBOUND");
  const [outcome, setOutcome] = React.useState(defaultOutcome);
  const [callbackOn, setCallbackOn] = React.useState("");
  const [notes, setNotes] = React.useState("");
  // "Relates to loan": defaults to the customer's one loan when there is only one to pick;
  // otherwise the caller has to choose, since guessing wrong would mistag the call.
  const [loanId, setLoanId] = React.useState(loans.length === 1 ? String(loans[0].id) : "");

  const add = useMutation({
    mutationFn: () =>
      customersApi.addCallLog(customerId, {
        callType,
        outcome,
        callbackOn: outcome === "CALLBACK" && callbackOn ? callbackOn : null,
        notes: notes.trim() || null,
        loanId: loanId ? Number(loanId) : undefined,
      }),
    onSuccess: () => {
      setNotes("");
      setCallbackOn("");
      qc.invalidateQueries({ queryKey: ["customer-call-logs", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-activity", customerId] });
      toast.success("Call logged");
      onDone?.();
    },
  });

  const loanOptions = [
    { value: "", label: "— not loan-specific —" },
    ...loans.map((l) => ({
      value: String(l.id),
      label: `#${l.id} · disbursed ${l.disbursedOn ? formatDate(l.disbursedOn) : "—"}`,
    })),
  ];

  return (
    <div className="space-y-3">
      <div className="grid gap-2 sm:grid-cols-2">
        <Select
          label="Call type"
          value={callType}
          onChange={(e) => setCallType(e.target.value)}
          options={CALL_TYPES}
          className="!mb-0"
        />
        <Select
          label="Outcome"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value)}
          options={CALL_OUTCOMES}
          className="!mb-0"
        />
      </div>
      {loans.length > 0 && (
        <Select
          label="Relates to loan"
          value={loanId}
          onChange={(e) => setLoanId(e.target.value)}
          options={loanOptions}
          className="!mb-0"
        />
      )}
      {outcome === "CALLBACK" && (
        <label className="block text-xs text-muted">
          Callback on
          <input
            type="date"
            value={callbackOn}
            autoFocus={focusCallback}
            onChange={(e) => setCallbackOn(e.target.value)}
            className="mt-1 w-full rounded border border-line px-3 py-2 text-sm text-ink"
          />
        </label>
      )}
      <textarea
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        rows={3}
        placeholder="Call notes…"
        className="w-full rounded border border-line px-3 py-2 text-sm"
      />
      <button
        onClick={() => add.mutate()}
        disabled={add.isPending}
        className="btn btn-sm btn-navy disabled:opacity-50"
      >
        {add.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Log call
      </button>
      {add.error && <p className="text-xs text-error-700">{errMessage(add.error)}</p>}
    </div>
  );
}

/** Calls + remarks. */
export function CommunicationTab({ customerId, loans }: { customerId: number; loans: LoanView[] }) {
  const q = useQuery({
    queryKey: ["customer-call-logs", customerId],
    queryFn: () => customersApi.callLogs(customerId),
  });
  const logs = q.data ?? [];
  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <LogCallForm customerId={customerId} loans={loans} />
        {q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.isLoading ? (
          <div className="space-y-2">
            <Skeleton variant="row" />
            <Skeleton variant="row" />
            <Skeleton variant="row" />
          </div>
        ) : logs.length === 0 ? (
          <EmptyState title="No call logs yet." />
        ) : (
          <ul className="space-y-2">
            {logs.map((r) => (
              <CallLogRow key={r.id} log={r} />
            ))}
          </ul>
        )}
      </div>
      <Section title="Remarks">
        <RemarksTab customerId={customerId} />
      </Section>
    </div>
  );
}
