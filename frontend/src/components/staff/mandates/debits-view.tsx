"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { SearchBar } from "@/components/staff/search-bar";
import { mandatesApi, paiseToINR } from "@/lib/api/applications";
import { DebitTable } from "./debit-table";
import { Pager } from "./pager";

const STATUSES = [
  { id: "ALL", label: "All statuses" },
  { id: "SUCCEEDED", label: "Succeeded" },
  { id: "IN_FLIGHT", label: "In progress" },
  { id: "FAILED", label: "Failed" },
  { id: "CANCELLED", label: "Cancelled" },
  { id: "SKIPPED", label: "Skipped" },
  { id: "REFUND_OWED", label: "Refund owed" },
];

/** Every debit across the book, with who presented it, filterable by status, trigger, staff and date. */
export function DebitsView({ onOpenActions }: { onOpenActions: (applicationId: number) => void }) {
  const [q, setQ] = React.useState("");
  const [status, setStatus] = React.useState("ALL");
  const [trigger, setTrigger] = React.useState("ALL");
  const [actorId, setActorId] = React.useState<number | null>(null);
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  const [page, setPage] = React.useState(0);

  const actorsQ = useQuery({ queryKey: ["mandate-debit-actors"], queryFn: () => mandatesApi.actors() });
  const f = { q, status, trigger, actorId, from, to, page };
  const listQ = useQuery({
    queryKey: ["mandate-debits-all", f],
    queryFn: () => mandatesApi.debits(f),
    placeholderData: (prev) => prev,
  });
  const reset = (fn: () => void) => { fn(); setPage(0); };
  const select = "rounded border border-line bg-white px-2 py-1.5 text-sm";

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <label className="text-xs text-muted">
          Status
          <select className={`${select} mt-1 block`} value={status} onChange={(e) => reset(() => setStatus(e.target.value))}>
            {STATUSES.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
          </select>
        </label>
        <label className="text-xs text-muted">
          Trigger
          <select className={`${select} mt-1 block`} value={trigger} onChange={(e) => reset(() => setTrigger(e.target.value))}>
            <option value="ALL">Manual + scheduled</option>
            <option value="MANUAL">Manual (by staff)</option>
            <option value="SCHEDULED">Scheduled sweep</option>
          </select>
        </label>
        <label className="text-xs text-muted">
          Presented by
          <select
            className={`${select} mt-1 block`}
            value={actorId ?? ""}
            onChange={(e) => reset(() => setActorId(e.target.value ? Number(e.target.value) : null))}
          >
            <option value="">Anyone</option>
            {(actorsQ.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </label>
        <label className="text-xs text-muted">
          From
          <input type="date" className={`${select} mt-1 block`} value={from} onChange={(e) => reset(() => setFrom(e.target.value))} />
        </label>
        <label className="text-xs text-muted">
          To
          <input type="date" className={`${select} mt-1 block`} value={to} onChange={(e) => reset(() => setTo(e.target.value))} />
        </label>
        <div className="ml-auto">
          <SearchBar
            onSearch={(t) => reset(() => setQ(t))}
            placeholder="Name, mobile, UMRN, UTR or loan ID"
            ariaLabel="Search debits"
          />
        </div>
      </div>

      {listQ.data && (
        <p className="text-xs text-muted">
          {listQ.data.total} debit{listQ.data.total === 1 ? "" : "s"} · {listQ.data.succeededCount} succeeded ·{" "}
          <span className="font-semibold text-navy">{paiseToINR(listQ.data.succeededPaise)}</span> collected
        </p>
      )}

      {listQ.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : listQ.isError ? (
        <ErrorState error={listQ.error} onRetry={() => void listQ.refetch()} />
      ) : (listQ.data?.rows.length ?? 0) === 0 ? (
        <EmptyState title="No debits match." hint="Change the filters or search." />
      ) : (
        <>
          <DebitTable
            rows={listQ.data?.rows ?? []}
            showCustomer
            onOpenActions={(d) => d.applicationId != null && onOpenActions(d.applicationId)}
          />
          <Pager page={page} size={listQ.data?.size ?? 25} total={listQ.data?.total ?? 0} onPage={setPage} />
        </>
      )}
    </div>
  );
}
