"use client";

import * as React from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Loader2, Play, ShieldAlert } from "lucide-react";
import {
  providerApi,
  type ProviderApiCatalogItem,
  type ProviderApiExecutionSummary,
  type ProviderApiHistoryFilters,
} from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";

const SOURCES = ["", "LIVE", "MANUAL"];
const STATUSES = ["", "SUCCESS", "FAILED"];
const PAGE_SIZE = 25;
const EMPTY_FILTERS: ProviderApiHistoryFilters = {};

function JsonBlock({ value }: { value: unknown }) {
  const text = JSON.stringify(value, null, 2);
  return <div className="relative"><button type="button" onClick={() => navigator.clipboard.writeText(text)} className="absolute right-2 top-2 rounded border border-line bg-white p-1 text-navy" aria-label="Copy JSON"><Copy size={13}/></button><pre className="max-h-80 overflow-auto rounded border border-line bg-grey-50 p-3 text-xs">{text}</pre></div>;
}

/**
 * One history row. The request/response payloads are fetched only when the row is opened — a page of
 * bureau calls carries a few hundred KB of Experian report each, which would be megabytes eagerly.
 */
function HistoryRow({ row }: { row: ProviderApiExecutionSummary }) {
  const [open, setOpen] = React.useState(false);
  const detailQ = useQuery({ queryKey: ["provider-api-detail", row.id], queryFn: () => providerApi.detail(row.id), enabled: open });
  const failed = row.status !== "SUCCESS";
  return (
    <details className="rounded border border-line p-3" onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
      <summary className="cursor-pointer text-sm">
        <span className={`mr-2 rounded px-1.5 py-0.5 text-xs ${row.source === "MANUAL" ? "bg-grey-100 text-muted" : "bg-teal-50 text-teal-800"}`}>{row.source ?? "LIVE"}</span>
        <span className="font-semibold">{row.operation} · {row.provider}</span>
        <span className={failed ? "ml-2 text-error-700" : "ml-2 text-muted"}>{row.status}{row.httpStatus ? ` ${row.httpStatus}` : ""}</span>
        <span className="ml-2 text-muted">{row.durationMs} ms</span>
        {row.applicationId ? <span className="ml-2 text-muted">app #{row.applicationId}</span> : null}
        <span className="ml-2 text-muted">{new Date(row.createdAt).toLocaleString("en-IN")}</span>
      </summary>
      <div className="mt-2 text-xs text-muted">{row.endpoint}{row.checkType ? ` · ${row.checkType}` : ""}{row.requestId ? ` · req ${row.requestId}` : ""}</div>
      {row.error ? <p className="mt-2 text-sm text-error-700">{row.error}</p> : null}
      {detailQ.isLoading ? <Loader2 className="mt-3 animate-spin" size={15}/> : null}
      {detailQ.data ? <div className="mt-3 grid gap-3 lg:grid-cols-2"><div><h3 className="mb-1 text-xs">Request</h3><JsonBlock value={detailQ.data.request}/></div><div><h3 className="mb-1 text-xs">Response</h3><JsonBlock value={detailQ.data.response ?? { error: detailQ.data.error }}/></div></div> : null}
    </details>
  );
}

export default function ProviderApiDashboardPage() {
  const session = useStaffSession();
  const qc = useQueryClient();
  const catalogQ = useQuery({ queryKey: ["provider-api-catalog"], queryFn: providerApi.catalog });
  const [operation, setOperation] = React.useState("");
  const [provider, setProvider] = React.useState("");
  const [input, setInput] = React.useState<Record<string, string>>({});
  const [filters, setFilters] = React.useState<ProviderApiHistoryFilters>(EMPTY_FILTERS);
  const [page, setPage] = React.useState(0);
  const historyQ = useQuery({
    queryKey: ["provider-api-history", filters, page],
    queryFn: () => providerApi.history({ ...filters, page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });
  const selected = (catalogQ.data ?? []).find((item) => item.operation === operation);
  const operations = (catalogQ.data ?? []).map((i) => i.operation);
  const providers = Array.from(new Set((catalogQ.data ?? []).flatMap((i) => i.providers)));
  React.useEffect(() => { const first = catalogQ.data?.[0]; if (first && !operation) { setOperation(first.operation); setProvider(first.providers[0]); } }, [catalogQ.data, operation]);
  // The operation is the reset boundary; provider changes must preserve typed values.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  React.useEffect(() => { if (selected && !selected.providers.includes(provider)) setProvider(selected.providers[0]); setInput({}); }, [operation]);
  const run = useMutation({ mutationFn: () => providerApi.execute(operation, provider, input), onSuccess: () => qc.invalidateQueries({ queryKey: ["provider-api-history"] }) });
  // Any filter change restarts paging — staying on page 4 of a narrower result set would show nothing.
  const setFilter = (key: keyof ProviderApiHistoryFilters, value: string) => { setFilters((f) => ({ ...f, [key]: value })); setPage(0); };
  const total = historyQ.data?.total ?? 0;
  const shownFrom = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const shownTo = Math.min((page + 1) * PAGE_SIZE, total);
  if (!session.loading && session.session?.role !== "ADMIN") return <div className="card p-6"><ShieldAlert className="mb-2 text-error-700"/>Administrator access is required.</div>;
  return <div className="space-y-6"><div><h1 className="text-3xl">Provider API dashboard</h1><p className="text-muted">Every Signzy and Digitap call the platform makes is recorded here with its exact request and response. Provider credentials remain server-side.</p></div>
    <section className="card p-5"><div className="grid gap-4 md:grid-cols-2"><label className="field"><span>API</span><select value={operation} onChange={(e)=>setOperation(e.target.value)}>{(catalogQ.data ?? []).map((i: ProviderApiCatalogItem)=><option key={i.operation}>{i.operation}</option>)}</select></label><label className="field"><span>Provider</span><select value={provider} onChange={(e)=>setProvider(e.target.value)}>{selected?.providers.map(p=><option key={p}>{p}</option>)}</select></label></div>
    <div className="mt-4 grid gap-3 md:grid-cols-2">{selected?.fields.map(field=><label key={field.key} className="field"><span>{field.label}{field.required ? " *" : ""}</span><input type={field.type} value={input[field.key] ?? ""} onChange={e=>setInput(v=>({...v,[field.key]:e.target.value}))}/></label>)}</div>
    {run.error && <p className="mt-3 text-sm text-error-700">{run.error instanceof Error ? run.error.message : "Provider call failed."}</p>}<button className="btn btn-navy mt-4" disabled={run.isPending || !operation || !provider} onClick={()=>run.mutate()}>{run.isPending ? <Loader2 className="animate-spin" size={15}/> : <Play size={15}/>} Run API{operation === "BUREAU" ? " (up to 95s)" : ""}</button>
    {run.data && <div className="mt-5 grid gap-4 lg:grid-cols-2"><div><h2 className="mb-2 text-sm">Request</h2><JsonBlock value={run.data.request}/></div><div><h2 className="mb-2 text-sm">Response · {run.data.durationMs} ms</h2><JsonBlock value={run.data.response}/></div></div>}</section>
    <section className="card p-5"><h2 className="mb-3 text-xl">Call history (90 days)</h2>
      <div className="mb-4 grid gap-3 md:grid-cols-3 lg:grid-cols-4">
        <label className="field"><span>Source</span><select value={filters.source ?? ""} onChange={(e)=>setFilter("source", e.target.value)}>{SOURCES.map(s=><option key={s} value={s}>{s || "All"}</option>)}</select></label>
        <label className="field"><span>API</span><select value={filters.operation ?? ""} onChange={(e)=>setFilter("operation", e.target.value)}><option value="">All</option>{operations.map(o=><option key={o}>{o}</option>)}</select></label>
        <label className="field"><span>Provider</span><select value={filters.provider ?? ""} onChange={(e)=>setFilter("provider", e.target.value)}><option value="">All</option>{providers.map(p=><option key={p}>{p}</option>)}</select></label>
        <label className="field"><span>Status</span><select value={filters.status ?? ""} onChange={(e)=>setFilter("status", e.target.value)}>{STATUSES.map(s=><option key={s} value={s}>{s || "All"}</option>)}</select></label>
        <label className="field"><span>Application id</span><input type="number" value={filters.applicationId ?? ""} onChange={(e)=>setFilter("applicationId", e.target.value)}/></label>
        <label className="field"><span>From</span><input type="date" value={filters.from?.slice(0,10) ?? ""} onChange={(e)=>setFilter("from", e.target.value ? `${e.target.value}T00:00:00Z` : "")}/></label>
        <label className="field"><span>To</span><input type="date" value={filters.to?.slice(0,10) ?? ""} onChange={(e)=>setFilter("to", e.target.value ? `${e.target.value}T23:59:59Z` : "")}/></label>
        <div className="flex items-end"><button className="btn" onClick={()=>{ setFilters(EMPTY_FILTERS); setPage(0); }}>Reset</button></div>
      </div>
      {historyQ.isLoading ? <Loader2 className="animate-spin"/> : <div className="space-y-3">{(historyQ.data?.rows ?? []).map((row)=><HistoryRow key={row.id} row={row}/>)}{total === 0 && <p className="text-sm text-muted">No provider calls match these filters.</p>}</div>}
      {total > 0 && <div className="mt-4 flex items-center justify-between text-sm text-muted"><span>Showing {shownFrom}–{shownTo} of {total}</span><span className="flex gap-2"><button className="btn" disabled={page === 0} onClick={()=>setPage(p=>Math.max(p-1,0))}>Previous</button><button className="btn" disabled={shownTo >= total} onClick={()=>setPage(p=>p+1)}>Next</button></span></div>}
    </section></div>;
}
