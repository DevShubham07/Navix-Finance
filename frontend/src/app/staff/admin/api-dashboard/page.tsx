"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Loader2, Play, ShieldAlert } from "lucide-react";
import { providerApi, type ProviderApiCatalogItem, type ProviderApiExecution } from "@/lib/api/applications";
import { useStaffSession } from "@/lib/auth/staff-session";

function JsonBlock({ value }: { value: unknown }) {
  const text = JSON.stringify(value, null, 2);
  return <div className="relative"><button type="button" onClick={() => navigator.clipboard.writeText(text)} className="absolute right-2 top-2 rounded border border-line bg-white p-1 text-navy" aria-label="Copy JSON"><Copy size={13}/></button><pre className="max-h-80 overflow-auto rounded border border-line bg-grey-50 p-3 text-xs">{text}</pre></div>;
}

export default function ProviderApiDashboardPage() {
  const session = useStaffSession();
  const qc = useQueryClient();
  const catalogQ = useQuery({ queryKey: ["provider-api-catalog"], queryFn: providerApi.catalog });
  const historyQ = useQuery({ queryKey: ["provider-api-history"], queryFn: providerApi.history });
  const [operation, setOperation] = React.useState("");
  const [provider, setProvider] = React.useState("");
  const [input, setInput] = React.useState<Record<string, string>>({});
  const selected = (catalogQ.data ?? []).find((item) => item.operation === operation);
  React.useEffect(() => { const first = catalogQ.data?.[0]; if (first && !operation) { setOperation(first.operation); setProvider(first.providers[0]); } }, [catalogQ.data, operation]);
  // The operation is the reset boundary; provider changes must preserve typed values.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  React.useEffect(() => { if (selected && !selected.providers.includes(provider)) setProvider(selected.providers[0]); setInput({}); }, [operation]);
  const run = useMutation({ mutationFn: () => providerApi.execute(operation, provider, input), onSuccess: () => qc.invalidateQueries({ queryKey: ["provider-api-history"] }) });
  if (!session.loading && session.session?.role !== "ADMIN") return <div className="card p-6"><ShieldAlert className="mb-2 text-error-700"/>Administrator access is required.</div>;
  return <div className="space-y-6"><div><h1 className="text-3xl">Provider API dashboard</h1><p className="text-muted">Run direct Signzy and Digitap checks. Provider credentials remain server-side.</p></div>
    <section className="card p-5"><div className="grid gap-4 md:grid-cols-2"><label className="field"><span>API</span><select value={operation} onChange={(e)=>setOperation(e.target.value)}>{(catalogQ.data ?? []).map((i: ProviderApiCatalogItem)=><option key={i.operation}>{i.operation}</option>)}</select></label><label className="field"><span>Provider</span><select value={provider} onChange={(e)=>setProvider(e.target.value)}>{selected?.providers.map(p=><option key={p}>{p}</option>)}</select></label></div>
    <div className="mt-4 grid gap-3 md:grid-cols-2">{selected?.fields.map(field=><label key={field.key} className="field"><span>{field.label}{field.required ? " *" : ""}</span><input type={field.type} value={input[field.key] ?? ""} onChange={e=>setInput(v=>({...v,[field.key]:e.target.value}))}/></label>)}</div>
    {run.error && <p className="mt-3 text-sm text-error-700">{run.error instanceof Error ? run.error.message : "Provider call failed."}</p>}<button className="btn btn-navy mt-4" disabled={run.isPending || !operation || !provider} onClick={()=>run.mutate()}>{run.isPending ? <Loader2 className="animate-spin" size={15}/> : <Play size={15}/>} Run API (5s timeout)</button>
    {run.data && <div className="mt-5 grid gap-4 lg:grid-cols-2"><div><h2 className="mb-2 text-sm">Request</h2><JsonBlock value={run.data.request}/></div><div><h2 className="mb-2 text-sm">Response · {run.data.durationMs} ms</h2><JsonBlock value={run.data.response}/></div></div>}</section>
    <section className="card p-5"><h2 className="mb-3 text-xl">History (90 days)</h2>{historyQ.isLoading ? <Loader2 className="animate-spin"/> : <div className="space-y-3">{(historyQ.data ?? []).map((row: ProviderApiExecution)=><details key={row.id} className="rounded border border-line p-3"><summary className="cursor-pointer font-semibold">{row.operation} · {row.provider} · {row.status} · {row.durationMs} ms</summary><div className="mt-3 grid gap-3 lg:grid-cols-2"><JsonBlock value={row.request}/><JsonBlock value={row.response ?? { error: row.error }}/></div></details>)}</div>}</section></div>;
}
