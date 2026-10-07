"use client";

/**
 * Skip Tracer — the on-demand Digitap Skip Tracing Lite lookup for a customer whose contacts have
 * gone stale. Shared by the customer page, the application pop-up (collections worklist) and the
 * loan pop-up, so the three can't drift.
 *
 * Every run is a billable vendor call, so the Run button sits behind `collections:manage`
 * (COLLECTION_HEAD + ADMIN) and a confirm; the backend enforces the same rule. Results are kept
 * per customer: the latest is shown, earlier runs are a click away.
 */

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Radar, ExternalLink } from "lucide-react";
import { Badge, ConfirmDialog, EmptyState, ErrorState, Skeleton, toast } from "@/components/ui";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import { KV, Section } from "@/components/staff/detail-parts";
import { skipTraceApi, type SkipTraceAddress, type SkipTraceRun } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

const QUALITY_VARIANT: Record<string, "success" | "info" | "warning" | "error" | "neutral"> = {
  EXCELLENT: "success",
  "VERY GOOD": "success",
  GOOD: "info",
  BAD: "warning",
  "VERY BAD": "error",
};
const RUN_VARIANT: Record<SkipTraceRun["status"], "success" | "neutral" | "error"> = {
  SUCCESS: "success",
  NO_RECORD: "neutral",
  FAILED: "error",
};
const RUN_LABEL: Record<SkipTraceRun["status"], string> = {
  SUCCESS: "Found",
  NO_RECORD: "No record",
  FAILED: "Failed",
};

export function SkipTracePanel({ customerId }: { customerId: number }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["skip-trace", customerId],
    queryFn: () => skipTraceApi.history(customerId),
    retry: false,
  });
  const [confirm, setConfirm] = React.useState(false);
  const [selectedId, setSelectedId] = React.useState<number | null>(null);
  const m = useMutation({
    mutationFn: () => skipTraceApi.run(customerId),
    onSuccess: (run) => {
      setConfirm(false);
      setSelectedId(null);
      toast.success(run.status === "SUCCESS" ? "Skip trace complete." : run.status === "NO_RECORD" ? "Digitap has no record for this customer." : "Skip trace recorded.");
      void qc.invalidateQueries({ queryKey: ["skip-trace", customerId] });
    },
    onError: () => {
      setConfirm(false);
      // The failed attempt is still stored server-side; refresh so it shows in the history.
      void qc.invalidateQueries({ queryKey: ["skip-trace", customerId] });
    },
  });

  if (q.isLoading) return <Skeleton variant="line" rows={6} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const runs = q.data ?? [];
  const latest = runs[0] ?? null;
  const shown = runs.find((r) => r.id === selectedId) ?? latest;

  const runButton = (
    <PermissionGate permission="collections:manage">
      <button onClick={() => setConfirm(true)} disabled={m.isPending} className="btn btn-sm btn-navy disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Radar size={13} />}
        {latest ? "Run again" : "Find contacts"}
      </button>
    </PermissionGate>
  );

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="m-0 text-sm text-muted">
          {latest
            ? <>Last run by <span className="text-ink">{latest.runByName ?? "staff"}</span>{latest.runByRole ? ` (${latest.runByRole})` : ""} on {formatDateTime(latest.createdAt)}.</>
            : "Looks up alternate mobiles, emails and addresses from Digitap for this customer. Each run is a paid lookup."}
        </p>
        {runButton}
      </div>
      {m.error ? <p className="m-0 text-sm text-error-700">{errMessage(m.error)}</p> : null}

      {!shown ? (
        <EmptyState title="No skip trace has been run for this customer." hint="Use Find contacts when their registered details stop working." />
      ) : (
        <RunView run={shown} />
      )}

      {runs.length > 1 ? (
        <Section title={`Previous runs (${runs.length - 1})`}>
          <ul className="m-0 list-none divide-y divide-line p-0 text-sm">
            {runs.map((r) => (
              <li key={r.id} className="flex items-center justify-between gap-3 py-1.5">
                <button
                  type="button"
                  onClick={() => setSelectedId(r.id)}
                  className={`text-left hover:underline ${r.id === shown?.id ? "font-semibold text-ink" : "text-navy"}`}
                >
                  {formatDateTime(r.createdAt)} · {r.runByName ?? "staff"}
                </button>
                <Badge variant={RUN_VARIANT[r.status]} size="sm">{RUN_LABEL[r.status]}</Badge>
              </li>
            ))}
          </ul>
        </Section>
      ) : null}

      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => m.mutate()}
        busy={m.isPending}
        title={latest ? "Run the skip trace again?" : "Run a skip trace for this customer?"}
        confirmLabel="Run skip trace"
        body={
          <p className="m-0 text-sm text-muted">
            This calls Digitap Skip Tracing Lite and is billed per lookup, including when nothing is found.
            It sends the PAN, mobile, name and address on file; the result is stored and visible to staff.
          </p>
        }
      />
    </div>
  );
}

function RunView({ run }: { run: SkipTraceRun }) {
  const r = run.response?.result;
  const sent = run.request;
  const sentLine = [sent.pan && `PAN ${sent.pan}`, sent.mobile && `mobile ${sent.mobile}`, sent.name, sent.address?.[0]]
    .filter(Boolean)
    .join(" · ");

  if (run.status === "FAILED") {
    return (
      <ErrorState
        title={`Digitap could not complete this lookup${run.message ? `: ${run.message}` : "."}`}
        error={null}
      />
    );
  }
  if (run.status === "NO_RECORD" || !r) {
    return <EmptyState title="Digitap has no record for this PAN / mobile." hint={sentLine ? `Searched with ${sentLine}.` : undefined} />;
  }

  const p = r.profile ?? {};
  const c = r.contactability ?? {};
  const ins = r.insights ?? {};
  const meta = r.metadata ?? {};
  return (
    <div className="space-y-3">
      <p className="m-0 text-xs text-muted">
        Searched with {sentLine || "the details on file"}
        {run.providerRequestId ? <> · Digitap ref <span className="font-mono">{run.providerRequestId}</span></> : null}
      </p>

      <div className="grid gap-3 md:grid-cols-2">
        <Section title="Identity returned">
          <dl className="m-0 text-sm">
            <KV k="Name" v={p.full_name} />
            <KV k="Father's name" v={p.father_name} />
            <KV k="Date of birth" v={p.date_of_birth} />
            <KV k="Gender" v={p.gender} />
            <KV
              k="Name match"
              v={ins.name_match == null ? null : `${ins.name_match ? "Yes" : "No"}${ins.name_match_score != null ? ` (${ins.name_match_score})` : ""}`}
            />
            {(r.official_documents ?? []).map((d, i) => (
              <KV key={i} k={d.document ?? "Document"} v={d.document_id} mono />
            ))}
          </dl>
        </Section>

        <Section title="Contacts">
          <dl className="m-0 text-sm">
            <KV k="Primary mobile" v={c.mobile_number} mono />
            {(c.alternate_mobiles ?? []).length === 0 ? <KV k="Alternate mobiles" v={null} /> : null}
            {(c.alternate_mobiles ?? []).map((a, i) => (
              <KV key={`m${i}`} k={i === 0 ? "Alternate mobiles" : ""} v={<>{a.mobile_number}{a.reported_date ? <span className="text-muted"> · reported {formatDate(a.reported_date)}</span> : null}</>} mono />
            ))}
            {(c.emails ?? []).length === 0 ? <KV k="Emails" v={null} /> : null}
            {(c.emails ?? []).map((e, i) => (
              <KV key={`e${i}`} k={i === 0 ? "Emails" : ""} v={<>{e.email_id}{e.reported_date ? <span className="text-muted"> · reported {formatDate(e.reported_date)}</span> : null}</>} />
            ))}
          </dl>
        </Section>
      </div>

      <Section title={`Addresses (${r.addresses?.length ?? 0} found${ins.total_unique_addresses != null ? `, ${ins.total_unique_addresses} unique` : ""})`}>
        {(r.addresses ?? []).length === 0 ? (
          <p className="m-0 text-sm text-muted">No addresses returned.</p>
        ) : (
          <ol className="m-0 list-none space-y-2 p-0">
            {(r.addresses ?? []).map((a) => <AddressRow key={a.address_rank} a={a} />)}
          </ol>
        )}
      </Section>

      <Section title="Data confidence">
        <div className="flex flex-wrap gap-2 text-xs">
          {Object.entries(meta).map(([k, v]) => (
            <Badge key={k} variant={v?.status === "FOUND" ? "success" : v?.status === "NOT_FOUND" ? "error" : "warning"} size="sm" title={v?.description || undefined}>
              {k.replace(/_/g, " ")}: {v?.status ?? "—"}
            </Badge>
          ))}
          {r.report ? (
            <a href={r.report} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-navy hover:underline">
              Digitap PDF report <ExternalLink size={12} /> <span className="text-muted">(link expires within an hour of the run)</span>
            </a>
          ) : null}
        </div>
      </Section>
    </div>
  );
}

function AddressRow({ a }: { a: SkipTraceAddress }) {
  const d = a.address_details ?? {};
  const i = a.address_insights ?? {};
  const line = [d.address, d.locality, d.city, d.district, d.state, d.pincode].filter(Boolean).join(", ");
  const level = i.address_quality?.level;
  return (
    <li className="rounded border border-line p-2 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <span className="mr-2 font-semibold text-ink">#{a.address_rank}</span>
          <span className="text-ink">{line || "—"}</span>
        </div>
        {level ? <Badge variant={QUALITY_VARIANT[level] ?? "neutral"} size="sm" title={(i.address_quality?.missing ?? []).join(", ") || undefined}>{level}</Badge> : null}
      </div>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-muted">
        {i.address_match_score != null ? <span>Match vs our address: {i.address_match_score}</span> : null}
        {i.address_visit_conversion != null ? <span>Visit likelihood: {i.address_visit_conversion}</span> : null}
        {i.reported_date ? <span>Reported {formatDate(i.reported_date)}</span> : null}
        {i.last_activity_date ? <span>Last activity {formatDate(i.last_activity_date)}</span> : null}
        {(i.address_quality?.missing ?? []).length ? <span>Missing: {(i.address_quality?.missing ?? []).join(", ").toLowerCase()}</span> : null}
      </div>
    </li>
  );
}
