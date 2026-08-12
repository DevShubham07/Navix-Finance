"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Bell, UserPlus, Send } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, errMessage, useStaffMe } from "@/components/staff/live-pipeline";
import { ApplicationInfoDialog } from "@/components/staff/application-info-dialog";
import { hasPermission } from "@/lib/auth/rbac";
import { customersApi, staffApi, statusLabel, type TelecallingView } from "@/lib/api/applications";

/**
 * Telecaller queue — every pre-`SANCTIONED` application, split into Unallocated (no
 * `customer_owner` row) and My customers (owned by the signed-in staffer), stale-first by default.
 * Distinct from the older DSA-style `/staff/leads` intake — this queue chases live applications
 * stuck in the funnel, not fresh walk-in leads.
 */
export default function TelecallingPage() {
  const me = useStaffMe().data;
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-telecalling"],
    queryFn: () => staffApi.telecalling(),
    refetchInterval: 15000,
  });

  const [infoId, setInfoId] = React.useState<number | null>(null);
  const [results, setResults] = React.useState<Record<number, string>>({});

  const myId = me?.id != null ? Number(me.id) : null;

  const assign = useMutation({
    mutationFn: (customerId: number) => customersApi.assignOwner(customerId, myId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["staff-telecalling"] }),
  });

  const remind = useMutation({
    mutationFn: (id: number) => staffApi.sendReminder(id),
    onSuccess: (res, id) =>
      setResults((r) => ({ ...r, [id]: res.sent ? "Reminder sent" : "Nothing pending" })),
    onError: (err, id) => setResults((r) => ({ ...r, [id]: errMessage(err) })),
  });

  if (me && !hasPermission(me.role, "leads:manage")) {
    return <NoAccessNotice message="Telecalling access only (TELECALLER / ADMIN)." />;
  }

  const rows = q.data ?? [];
  const unallocated = rows
    .filter((r) => r.ownerStaffId == null)
    .sort((a, b) => b.staleDays - a.staleDays);
  const mine = rows
    .filter((r) => myId != null && r.ownerStaffId === myId)
    .sort((a, b) => b.staleDays - a.staleDays);

  return (
    <div>
      <PageHeader
        title="Telecalling"
        subtitle="Pre-sanction applications that need a chase-up call — stalest first."
      >
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <div className="h-40 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="space-y-6">
          <TelecallingSection
            title="Unallocated"
            info="Pre-sanction applications with no assigned telecaller. Assign to yourself to start chasing them."
            rows={unallocated}
            onOpen={setInfoId}
            onAssignToMe={myId != null ? (customerId) => assign.mutate(customerId) : undefined}
            assigning={assign.isPending ? assign.variables : null}
            onRemind={(id) => remind.mutate(id)}
            reminding={remind.isPending ? remind.variables : null}
            results={results}
          />
          <TelecallingSection
            title="My customers"
            info="Applications currently assigned to you."
            rows={mine}
            onOpen={setInfoId}
            onRemind={(id) => remind.mutate(id)}
            reminding={remind.isPending ? remind.variables : null}
            results={results}
          />
        </div>
      )}

      <ApplicationInfoDialog applicationId={infoId} onClose={() => setInfoId(null)} />
    </div>
  );
}

function TelecallingSection({
  title,
  info,
  rows,
  onOpen,
  onAssignToMe,
  assigning,
  onRemind,
  reminding,
  results,
}: {
  title: string;
  info: string;
  rows: TelecallingView[];
  onOpen: (id: number) => void;
  onAssignToMe?: (customerId: number) => void;
  assigning?: number | null;
  onRemind: (id: number) => void;
  reminding?: number | null;
  results: Record<number, string>;
}) {
  const [selected, setSelected] = React.useState<Set<number>>(new Set());
  const [bulkBusy, setBulkBusy] = React.useState(false);
  const [bulkDone, setBulkDone] = React.useState(0);

  React.useEffect(() => setSelected(new Set()), [rows.length]);

  const toggle = (id: number) => {
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const toggleAll = () => {
    setSelected((s) => (s.size === rows.length ? new Set() : new Set(rows.map((r) => r.id))));
  };

  const sendToSelected = async () => {
    setBulkBusy(true);
    setBulkDone(0);
    for (const id of selected) {
      try {
        await staffApi.sendReminder(id);
      } catch {
        /* per-row failure is fine — keep going, no batch abort */
      }
      setBulkDone((n) => n + 1);
    }
    setBulkBusy(false);
    setSelected(new Set());
  };

  return (
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
        <div className="flex items-center gap-2">
          <h2 className="font-serif text-lg font-semibold text-navy">{title}</h2>
          <span className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">{rows.length}</span>
          <span className="hidden text-xs text-muted sm:inline" title={info}>
            {info}
          </span>
        </div>
        {selected.size > 0 && (
          <button
            type="button"
            onClick={sendToSelected}
            disabled={bulkBusy}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            <Send size={13} />
            {bulkBusy ? `Sending… (${bulkDone}/${selected.size})` : `Send to selected (${selected.size})`}
          </button>
        )}
      </header>

      {rows.length === 0 ? (
        <p className="px-5 py-6 text-center text-sm text-muted">Nothing here.</p>
      ) : (
        <div className="staff-table-scroll">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th className="staff-sticky-identity">
                  <input
                    type="checkbox"
                    checked={selected.size === rows.length}
                    onChange={toggleAll}
                    aria-label="Select all"
                  />
                </th>
                <th>Customer</th>
                <th>Mobile</th>
                <th>Email</th>
                <th>PAN</th>
                <th>Status</th>
                <th>Completeness</th>
                <th>Stale (days)</th>
                <th className="staff-sticky-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="staff-sticky-identity">
                    <input
                      type="checkbox"
                      checked={selected.has(r.id)}
                      onChange={() => toggle(r.id)}
                      aria-label={`Select application #${r.id}`}
                    />
                  </td>
                  <td className="staff-cell">
                    <button type="button" onClick={() => onOpen(r.id)} className="text-left font-semibold text-navy hover:underline">
                      {r.customerName || `Customer #${r.customerId}`}
                    </button>
                    <div className="text-xs text-muted">App #{r.id} · Cust #{r.customerId}</div>
                  </td>
                  <td className="font-mono text-muted">{r.mobile || "—"}</td>
                  <td className="text-muted">
                    {r.email || <span className="italic text-warning-700">no email</span>}
                  </td>
                  <td className="font-mono text-ink">{r.pan || "—"}</td>
                  <td>
                    <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-ink">
                      {statusLabel(r.status)}
                    </span>
                  </td>
                  <td className="whitespace-nowrap text-ink">{r.stepsCompleted}/{r.stepsRequired}</td>
                  <td>
                    <span className={r.staleDays >= 3 ? "font-semibold text-error-700" : "text-ink"}>
                      {r.staleDays}
                    </span>
                  </td>
                  <td className="staff-sticky-actions">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {onAssignToMe && (
                        <button
                          type="button"
                          onClick={() => onAssignToMe(r.customerId)}
                          disabled={assigning === r.customerId}
                          className="btn btn-sm btn-outline"
                          title="Assign this customer to me"
                        >
                          {assigning === r.customerId ? (
                            <Loader2 size={13} className="animate-spin" />
                          ) : (
                            <UserPlus size={13} />
                          )}
                          Assign to me
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => onRemind(r.id)}
                        disabled={reminding === r.id}
                        className="btn btn-sm btn-outline"
                        title="Send a reminder for outstanding steps"
                      >
                        {reminding === r.id ? <Loader2 size={13} className="animate-spin" /> : <Bell size={13} />}
                        Send reminder
                      </button>
                      {results[r.id] && <span className="text-xs text-muted">{results[r.id]}</span>}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
