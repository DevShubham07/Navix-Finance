"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { ChevronDown, ChevronRight, ShieldCheck } from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { StatCard } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { mandatesApi, paiseToINR, type MandateDebitRow, type MandateListRow } from "@/lib/api/applications";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { cn, formatDateTime } from "@/lib/utils";
import { SERIES } from "@/components/staff/dashboard/colors";
import { MANDATE_STATUS } from "./labels";
import { DebitTable } from "./debit-table";
import { Pager } from "./pager";

const FILTERS: { id: string; label: string }[] = [
  { id: "ACTIVE", label: "Active" },
  { id: "PENDING", label: "Awaiting authorisation" },
  { id: "FAILED", label: "Failed / expired" },
  { id: "CANCELLED", label: "Cancelled" },
  { id: "BANK_UNSUPPORTED", label: "Bank not supported" },
  { id: "ALL", label: "All" },
];

/** Tiles + 30-day chart + searchable mandate cards; a card expands to every debit on its UMRN. */
export function MandatesView({ onOpenActions }: { onOpenActions: (applicationId: number) => void }) {
  const [status, setStatus] = React.useState("ACTIVE");
  const [q, setQ] = React.useState("");
  const [page, setPage] = React.useState(0);
  const [open, setOpen] = React.useState<number | null>(null);

  const summaryQ = useQuery({ queryKey: ["mandate-summary"], queryFn: () => mandatesApi.summary() });
  const listQ = useQuery({
    queryKey: ["mandate-list", q, status, page],
    queryFn: () => mandatesApi.list(q, status, page),
    placeholderData: (prev) => prev,
  });
  const s = summaryQ.data;
  const chart = (s?.debitedByDay ?? []).map((p) => ({ ...p, label: p.date.slice(5) }));

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="Active mandates" value={s?.activeMandates} hint={s ? `${s.activeCustomers} customers` : undefined} accent="success" />
        <StatCard label="Active mandate limit" value={s ? paiseToINR(s.activeLimitPaise) : undefined} info="Sum of the maximum amount every active mandate allows per debit." />
        <StatCard label="Total debited" value={s ? paiseToINR(s.debitedPaise) : undefined} hint={s ? `${s.debitsSucceeded} successful debits` : undefined} accent="gold" />
        <StatCard
          label="Debits in progress"
          value={s?.debitsInFlight}
          hint={s ? paiseToINR(s.inFlightPaise) : undefined}
          info="Debits sent to the bank that have not settled yet. While one is in progress its mandate cannot be cancelled."
        />
        <StatCard label="Awaiting authorisation" value={s?.pendingAuthorisation} info="The borrower has started a mandate but not finished authorising it with their bank." />
        <StatCard label="Debits failed" value={s?.debitsFailed} accent={s && s.debitsFailed > 0 ? "error" : undefined} />
        <StatCard label="Cancelled mandates" value={s?.cancelled} />
        <StatCard label="Refunds owed" value={s ? paiseToINR(s.refundsOwedPaise) : undefined} accent={s && s.refundsOwedPaise > 0 ? "error" : undefined} info="Money a debit collected after the loan was already settled." />
      </div>

      <div className="rounded border border-line bg-white p-4 shadow-sm">
        <div className="mb-2 text-sm font-semibold text-navy">Amount debited · last 30 days</div>
        <div className="h-48">
          {summaryQ.isLoading ? (
            <Skeleton className="h-full w-full" />
          ) : (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chart} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid stroke="#eef1f5" vertical={false} />
                <XAxis dataKey="label" tick={{ fontSize: 10 }} minTickGap={12} />
                <YAxis tick={{ fontSize: 10 }} tickFormatter={(v: number) => formatInrCompact(v)} width={58} />
                <Tooltip
                  formatter={(v: number) => [paiseToINR(v), "Debited"]}
                  labelFormatter={(l: string) => l}
                />
                <Bar dataKey="debitedPaise" fill={SERIES.collected} radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-1.5">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              onClick={() => { setStatus(f.id); setPage(0); setOpen(null); }}
              className={cn(
                "rounded-full border px-3 py-1 text-xs font-semibold",
                status === f.id ? "border-navy bg-navy text-white" : "border-line bg-white text-navy hover:bg-grey-50",
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
        <SearchBar
          onSearch={(t) => { setQ(t); setPage(0); setOpen(null); }}
          placeholder="Name, mobile, UMRN, bank or customer ID"
          ariaLabel="Search mandates"
        />
      </div>

      {listQ.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : listQ.isError ? (
        <ErrorState error={listQ.error} onRetry={() => void listQ.refetch()} />
      ) : (listQ.data?.rows.length ?? 0) === 0 ? (
        <EmptyState title="No mandates match." hint="Try another status or search." />
      ) : (
        <div className="space-y-2">
          {listQ.data?.rows.map((m) => (
            <MandateCard
              key={m.id}
              m={m}
              open={open === m.id}
              onToggle={() => setOpen(open === m.id ? null : m.id)}
              onOpenActions={onOpenActions}
            />
          ))}
          <Pager page={page} size={listQ.data?.size ?? 25} total={listQ.data?.total ?? 0} onPage={setPage} />
        </div>
      )}
    </div>
  );
}

function MandateCard({
  m,
  open,
  onToggle,
  onOpenActions,
}: {
  m: MandateListRow;
  open: boolean;
  onToggle: () => void;
  onOpenActions: (applicationId: number) => void;
}) {
  const st = MANDATE_STATUS[m.status];
  const debitsQ = useQuery({
    queryKey: ["mandate-debits", m.id],
    queryFn: () => mandatesApi.debitsFor(m.id),
    enabled: open,
  });
  return (
    <div className={cn("rounded-lg border bg-white shadow-sm", open ? "border-navy" : "border-line")}>
      <button type="button" onClick={onToggle} aria-expanded={open} className="flex w-full flex-wrap items-center gap-x-6 gap-y-2 p-4 text-left">
        <span className="text-muted">{open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}</span>
        <div className="min-w-[11rem] flex-1">
          <div className="font-semibold text-navy">{m.customerName ?? `Customer #${m.customerId}`}</div>
          <div className="text-xs text-muted">
            {m.mobile ?? "—"} · #{m.customerId}
            {m.applicationStatus ? ` · loan ${m.applicationStatus.toLowerCase().replace(/_/g, " ")}` : ""}
          </div>
        </div>
        <div className="min-w-[10rem]">
          <div className="text-[11px] uppercase tracking-wide text-muted">UMRN</div>
          <div className="font-mono text-xs text-navy">{m.umrn ?? "—"}</div>
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">Limit</div>
          <div className="font-mono text-sm font-semibold text-navy">{paiseToINR(m.maximumAmountPaise)}</div>
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted">Debited</div>
          <div className="font-mono text-sm font-semibold text-navy">{paiseToINR(m.debitedPaise)}</div>
          <div className="text-[11px] text-muted">
            {m.debitCount} attempt{m.debitCount === 1 ? "" : "s"}
            {m.debitsInFlight > 0 ? ` · ${m.debitsInFlight} in progress` : ""}
          </div>
        </div>
        <Badge variant={st?.tone ?? "neutral"}>{st?.label ?? m.status}</Badge>
      </button>

      {open && (
        <div className="space-y-3 border-t border-line p-4">
          <div className="grid grid-cols-1 gap-x-6 gap-y-1 text-xs sm:grid-cols-2 lg:grid-cols-3">
            <Field k="Bank" v={`${m.bankName ?? "—"}${m.accountLast4 ? ` · •••• ${m.accountLast4}` : ""}`} />
            <Field k="Mode" v={m.authSubMode ?? "—"} />
            <Field k="Attempt" v={`#${m.attemptNo}`} />
            <Field k="Started" v={m.createdAt ? formatDateTime(m.createdAt) : "—"} />
            <Field k="Authorised" v={m.authenticatedAt ? formatDateTime(m.authenticatedAt) : "—"} />
            <Field k="Last debit" v={m.lastDebitAt ? formatDateTime(m.lastDebitAt) : "—"} />
            {m.cancelledAt && (
              <Field
                k="Cancelled"
                v={`${formatDateTime(m.cancelledAt)}${m.cancelledBy ? ` by ${m.cancelledBy}` : ""}${m.cancelReason ? ` — ${m.cancelReason}` : ""}`}
              />
            )}
            {m.rejectReason && <Field k="Bank reason" v={m.rejectReason} />}
          </div>
          {m.applicationId != null && (
            <button type="button" className="btn btn-sm btn-navy" onClick={() => onOpenActions(m.applicationId as number)}>
              <ShieldCheck size={14} /> Open mandate actions
            </button>
          )}
          <div>
            <h4 className="mb-2 text-sm font-semibold text-navy">Debit attempts on this mandate</h4>
            {debitsQ.isLoading ? (
              <Skeleton className="h-16 w-full" />
            ) : debitsQ.isError ? (
              <ErrorState error={debitsQ.error} onRetry={() => void debitsQ.refetch()} />
            ) : (debitsQ.data?.length ?? 0) === 0 ? (
              <p className="text-xs text-muted">No debit has been presented on this mandate.</p>
            ) : (
              <DebitTable
                rows={debitsQ.data as MandateDebitRow[]}
                onOpenActions={(d) => d.applicationId != null && onOpenActions(d.applicationId)}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Field({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="flex gap-2">
      <span className="w-24 shrink-0 text-muted">{k}</span>
      <span className="font-medium text-navy">{v}</span>
    </div>
  );
}
