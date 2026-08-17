"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Check, Ban, ArrowRightLeft } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { hasPermission } from "@/lib/auth/rbac";
import { formatDateTime } from "@/lib/utils";
import {
  adminDsaApi,
  paiseToINR,
  type AdminDsaRosterView,
  type AdminDsaLeadView,
  type AdminDsaCommissionView,
  type AdminOutreachView,
  type DsaCommissionStatus,
} from "@/lib/api/applications";

type Tab = "ROSTER" | "LEADS" | "COMMISSIONS" | "OUTREACH";

const COMMISSION_STATUSES: DsaCommissionStatus[] = ["ACCRUED", "PAYABLE", "PAID", "VOID"];

/**
 * Admin · DSA program — the roster + roll-ups, the company-wide DSA lead register (full detail,
 * ADMIN sees everything), the commission ledger with Mark paid / Void / Reassign, and the outreach
 * audit log. ADMIN only; reaches DSA data exclusively through /api/admin/dsa (never the DSA's own
 * /api/dsa surface).
 */
export default function AdminDsaPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const [tab, setTab] = React.useState<Tab>("ROSTER");

  const roster = useQuery({ queryKey: ["admin-dsa-roster"], queryFn: adminDsaApi.roster });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const rosterRows = roster.data ?? [];
  const totals = rosterRows.reduce(
    (acc, r) => ({
      leads: acc.leads + r.leadsAdded,
      converted: acc.converted + r.leadsConverted,
      accrued: acc.accrued + r.accruedPaise,
      payable: acc.payable + r.payablePaise,
      paid: acc.paid + r.paidPaise,
    }),
    { leads: 0, converted: 0, accrued: 0, payable: 0, paid: 0 },
  );

  return (
    <div>
      <PageHeader title="DSA program" subtitle="External commission agents — roster, leads, commission ledger and outreach audit.">
        <button
          type="button"
          onClick={() => qc.invalidateQueries({ queryKey: ["admin-dsa-roster"] })}
          className="btn btn-sm btn-outline"
          disabled={roster.isFetching}
        >
          {roster.isFetching ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          Refresh
        </button>
      </PageHeader>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        <StatCard label="DSAs" value={rosterRows.length} />
        <StatCard label="Leads" value={totals.leads} hint={`${totals.converted} converted`} />
        <StatCard label="Accrued" value={paiseToINR(totals.accrued)} accent="gold" />
        <StatCard label="Payable" value={paiseToINR(totals.payable)} accent="success" />
        <StatCard label="Paid" value={paiseToINR(totals.paid)} accent="success" />
      </div>

      <div className="mb-4 mt-6 flex gap-2">
        {([
          ["ROSTER", "Roster"],
          ["LEADS", "Leads"],
          ["COMMISSIONS", "Commissions"],
          ["OUTREACH", "Outreach"],
        ] as [Tab, string][]).map(([t, label]) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold ${
              tab === t ? "bg-navy text-white" : "border border-line text-muted hover:bg-grey-100 hover:text-ink"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "ROSTER" && <RosterTab rows={rosterRows} loading={roster.isLoading} error={roster.error} />}
      {tab === "LEADS" && <LeadsTab dsaOptions={rosterRows} />}
      {tab === "COMMISSIONS" && <CommissionsTab dsaOptions={rosterRows} />}
      {tab === "OUTREACH" && <OutreachTab dsaOptions={rosterRows} />}
    </div>
  );
}

function RosterTab({
  rows,
  loading,
  error,
}: {
  rows: AdminDsaRosterView[];
  loading: boolean;
  error: unknown;
}) {
  return (
    <div>
      {error ? (
        <p className="text-sm text-error-700">{errMessage(error)}</p>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>DSA</th>
                <th>Status</th>
                <th className="text-right">Leads</th>
                <th className="text-right">Converted</th>
                <th className="text-right">Accrued</th>
                <th className="text-right">Payable</th>
                <th className="text-right">Paid</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((r) => (
                <tr key={r.dsaStaffId}>
                  <td className="font-semibold text-ink">{r.name ?? `#${r.dsaStaffId}`}</td>
                  <td>
                    <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${r.active ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800"}`}>
                      {r.active ? "Active" : "Disabled"}
                    </span>
                  </td>
                  <td className="text-right">{r.leadsAdded}</td>
                  <td className="text-right">{r.leadsConverted}</td>
                  <td className="text-right">{paiseToINR(r.accruedPaise)}</td>
                  <td className="text-right">{paiseToINR(r.payablePaise)}</td>
                  <td className="text-right font-semibold text-ink">{paiseToINR(r.paidPaise)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="text-center text-muted">
                    {loading ? "Loading…" : "No DSAs yet."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function LeadsTab({ dsaOptions }: { dsaOptions: AdminDsaRosterView[] }) {
  const [dsaId, setDsaId] = React.useState<string>("");
  const [q, setQ] = React.useState("");
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");

  const leads = useQuery({
    queryKey: ["admin-dsa-leads", dsaId, q, from, to],
    queryFn: () =>
      adminDsaApi.leads({
        dsaId: dsaId ? Number(dsaId) : undefined,
        q: q || undefined,
        from: from || undefined,
        to: to || undefined,
      }),
  });

  const rows = leads.data ?? [];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select label="DSA" className="!mb-0" value={dsaId} onChange={(e) => setDsaId(e.target.value)}>
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>
              {d.name ?? `#${d.dsaStaffId}`}
            </option>
          ))}
        </Select>
        <Input label="Search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, PAN or mobile" className="!mb-0" />
        <Input label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="!mb-0" />
        <Input label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="!mb-0" />
        <ExportMenu
          title="DSA lead register"
          fileBase="dhanboost-dsa-leads"
          columns={[
            { header: "DSA", value: (l: AdminDsaLeadView) => l.ownerDsaName ?? `#${l.ownerDsaId}` },
            { header: "PAN", value: (l) => l.pan },
            { header: "Name", value: (l) => l.name },
            { header: "Mobile", value: (l) => l.mobile },
            { header: "Email", value: (l) => l.email ?? "" },
            { header: "City", value: (l) => l.city ?? "" },
            { header: "Employer", value: (l) => l.employer ?? "" },
            { header: "Created", value: (l) => formatDateTime(l.createdAt) },
          ]}
          rows={rows}
        />
      </div>

      {leads.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : leads.error ? (
        <p className="text-sm text-error-700">{errMessage(leads.error)}</p>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>DSA</th>
                <th>PAN</th>
                <th>Name</th>
                <th>Mobile</th>
                <th>Email</th>
                <th>City</th>
                <th>Employer</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {rows.map((l) => (
                <tr key={l.id}>
                  <td className="text-ink">{l.ownerDsaName ?? `#${l.ownerDsaId}`}</td>
                  <td className="font-mono text-xs">{l.pan}</td>
                  <td className="text-ink">{l.name}</td>
                  <td className="font-mono text-xs">{l.mobile}</td>
                  <td className="text-muted">{l.email ?? "—"}</td>
                  <td className="text-muted">{l.city ?? "—"}</td>
                  <td className="text-muted">{l.employer ?? "—"}</td>
                  <td className="whitespace-nowrap text-muted">{formatDateTime(l.createdAt)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={8} className="text-center text-muted">No leads match these filters.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function CommissionsTab({ dsaOptions }: { dsaOptions: AdminDsaRosterView[] }) {
  const qc = useQueryClient();
  const [dsaId, setDsaId] = React.useState<string>("");
  const [status, setStatus] = React.useState<DsaCommissionStatus | "">("");
  const [txnRefs, setTxnRefs] = React.useState<Record<number, string>>({});
  const [voidReasons, setVoidReasons] = React.useState<Record<number, string>>({});
  const [reassignTo, setReassignTo] = React.useState<Record<number, string>>({});
  const [reassignReason, setReassignReason] = React.useState<Record<number, string>>({});

  const commissions = useQuery({
    queryKey: ["admin-dsa-commissions", dsaId, status],
    queryFn: () => adminDsaApi.commissions({ dsaId: dsaId ? Number(dsaId) : undefined, status: status || undefined }),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["admin-dsa-commissions"] });

  const pay = useMutation({
    mutationFn: ({ id, txnRef }: { id: number; txnRef: string }) => adminDsaApi.pay(id, txnRef),
    onSuccess: (_d, vars) => {
      setTxnRefs((m) => ({ ...m, [vars.id]: "" }));
      invalidate();
    },
  });
  const voidMut = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => adminDsaApi.void(id, reason),
    onSuccess: (_d, vars) => {
      setVoidReasons((m) => ({ ...m, [vars.id]: "" }));
      invalidate();
    },
  });
  const reassign = useMutation({
    mutationFn: ({ id, toDsaStaffId, reason }: { id: number; toDsaStaffId: number; reason: string }) =>
      adminDsaApi.reassign(id, toDsaStaffId, reason),
    onSuccess: (_d, vars) => {
      setReassignTo((m) => ({ ...m, [vars.id]: "" }));
      setReassignReason((m) => ({ ...m, [vars.id]: "" }));
      invalidate();
    },
  });

  const rows = commissions.data ?? [];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select label="DSA" className="!mb-0" value={dsaId} onChange={(e) => setDsaId(e.target.value)}>
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>
              {d.name ?? `#${d.dsaStaffId}`}
            </option>
          ))}
        </Select>
        <Select label="Status" className="!mb-0" value={status} onChange={(e) => setStatus(e.target.value as DsaCommissionStatus | "")}>
          <option value="">All</option>
          {COMMISSION_STATUSES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </Select>
        <ExportMenu
          title="DSA commission ledger"
          fileBase="dhanboost-dsa-commissions"
          columns={[
            { header: "DSA", value: (c: AdminDsaCommissionView) => c.dsaName ?? `#${c.dsaStaffId}` },
            { header: "PAN", value: (c) => c.pan ?? "" },
            { header: "Net disbursed (₹)", value: (c) => (c.netDisbursedPaise / 100).toFixed(2) },
            { header: "Commission (₹)", value: (c) => (c.amountPaise / 100).toFixed(2) },
            { header: "Status", value: (c) => c.status },
            { header: "Txn ref", value: (c) => c.txnRef ?? "" },
            { header: "Paid by", value: (c) => c.paidBy ?? "" },
          ]}
          rows={rows}
        />
      </div>

      {commissions.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : commissions.error ? (
        <p className="text-sm text-error-700">{errMessage(commissions.error)}</p>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>DSA</th>
                <th>PAN</th>
                <th className="text-right">Net disbursed</th>
                <th className="text-right">Commission</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line align-top">
              {rows.map((c) => (
                <tr key={c.id}>
                  <td className="text-ink">{c.dsaName ?? `#${c.dsaStaffId}`}</td>
                  <td className="font-mono text-xs">{c.pan ?? "—"}</td>
                  <td className="text-right">{paiseToINR(c.netDisbursedPaise)}</td>
                  <td className="text-right font-semibold text-ink">{paiseToINR(c.amountPaise)}</td>
                  <td>
                    <span className="inline-block rounded bg-navy-tint px-2 py-0.5 text-[8px] font-semibold uppercase text-navy">
                      {c.status}
                    </span>
                    {c.status === "PAID" && c.txnRef && <div className="mt-1 font-mono text-[10px] text-muted">{c.txnRef}</div>}
                    {c.status === "VOID" && c.voidReason && <div className="mt-1 text-[10px] text-muted">{c.voidReason}</div>}
                  </td>
                  <td>
                    <div className="flex flex-col gap-2">
                      {c.status === "PAYABLE" && (
                        <div className="flex items-center gap-1.5">
                          <Input
                            value={txnRefs[c.id] ?? ""}
                            onChange={(e) => setTxnRefs((m) => ({ ...m, [c.id]: e.target.value }))}
                            placeholder="Txn id"
                            className="!mb-0"
                            inputClassName="w-32"
                          />
                          <button
                            onClick={() => pay.mutate({ id: c.id, txnRef: (txnRefs[c.id] ?? "").trim() })}
                            disabled={pay.isPending || !(txnRefs[c.id] ?? "").trim()}
                            className="btn btn-sm btn-gold disabled:opacity-50"
                          >
                            {pay.isPending && pay.variables?.id === c.id ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />} Pay
                          </button>
                        </div>
                      )}
                      {c.status !== "PAID" && c.status !== "VOID" && (
                        <div className="flex items-center gap-1.5">
                          <Input
                            value={voidReasons[c.id] ?? ""}
                            onChange={(e) => setVoidReasons((m) => ({ ...m, [c.id]: e.target.value }))}
                            placeholder="Void reason"
                            className="!mb-0"
                            inputClassName="w-32"
                          />
                          <button
                            onClick={() => voidMut.mutate({ id: c.id, reason: (voidReasons[c.id] ?? "").trim() })}
                            disabled={voidMut.isPending || !(voidReasons[c.id] ?? "").trim()}
                            className="btn btn-sm btn-outline disabled:opacity-50"
                          >
                            {voidMut.isPending && voidMut.variables?.id === c.id ? <Loader2 size={12} className="animate-spin" /> : <Ban size={12} />} Void
                          </button>
                        </div>
                      )}
                      {c.status !== "PAID" && (
                        <div className="flex items-center gap-1.5">
                          <Select
                            className="!mb-0"
                            value={reassignTo[c.id] ?? ""}
                            onChange={(e) => setReassignTo((m) => ({ ...m, [c.id]: e.target.value }))}
                          >
                            <option value="">Reassign to…</option>
                            {dsaOptions.filter((d) => d.dsaStaffId !== c.dsaStaffId).map((d) => (
                              <option key={d.dsaStaffId} value={d.dsaStaffId}>{d.name ?? `#${d.dsaStaffId}`}</option>
                            ))}
                          </Select>
                          <Input
                            value={reassignReason[c.id] ?? ""}
                            onChange={(e) => setReassignReason((m) => ({ ...m, [c.id]: e.target.value }))}
                            placeholder="Reason"
                            className="!mb-0"
                            inputClassName="w-28"
                          />
                          <button
                            onClick={() =>
                              reassign.mutate({
                                id: c.id,
                                toDsaStaffId: Number(reassignTo[c.id]),
                                reason: (reassignReason[c.id] ?? "").trim(),
                              })
                            }
                            disabled={
                              reassign.isPending ||
                              !reassignTo[c.id] ||
                              !(reassignReason[c.id] ?? "").trim()
                            }
                            className="btn btn-sm btn-outline disabled:opacity-50"
                          >
                            {reassign.isPending && reassign.variables?.id === c.id ? (
                              <Loader2 size={12} className="animate-spin" />
                            ) : (
                              <ArrowRightLeft size={12} />
                            )}
                          </button>
                        </div>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center text-muted">No commissions match these filters.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      {(pay.error || voidMut.error || reassign.error) && (
        <p className="mt-3 text-sm text-error-700">{errMessage(pay.error || voidMut.error || reassign.error)}</p>
      )}
    </div>
  );
}

function OutreachTab({ dsaOptions }: { dsaOptions: AdminDsaRosterView[] }) {
  const [dsaId, setDsaId] = React.useState<string>("");

  const outreach = useQuery({
    queryKey: ["admin-dsa-outreach", dsaId],
    queryFn: () => adminDsaApi.outreach({ dsaId: dsaId ? Number(dsaId) : undefined }),
  });

  const rows: AdminOutreachView[] = outreach.data ?? [];

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select label="DSA" className="!mb-0" value={dsaId} onChange={(e) => setDsaId(e.target.value)}>
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>{d.name ?? `#${d.dsaStaffId}`}</option>
          ))}
        </Select>
      </div>

      {outreach.isLoading ? (
        <div className="h-32 animate-pulse rounded border border-line bg-white" />
      ) : outreach.error ? (
        <p className="text-sm text-error-700">{errMessage(outreach.error)}</p>
      ) : (
        <div className="staff-table-scroll rounded border border-line bg-white shadow-sm">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>Lead #</th>
                <th>Channel</th>
                <th>To</th>
                <th>Subject / body</th>
                <th>Status</th>
                <th>Sent</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line align-top">
              {rows.map((o) => (
                <tr key={o.id}>
                  <td className="text-ink">#{o.leadId}</td>
                  <td className="text-muted">{o.channel}</td>
                  <td className="font-mono text-xs">{o.address}</td>
                  <td className="max-w-xs text-muted">
                    {o.subject && <div className="font-semibold text-ink">{o.subject}</div>}
                    <div className="truncate">{o.body ?? "—"}</div>
                    {o.error && <div className="text-error-700">{o.error}</div>}
                  </td>
                  <td>
                    <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${o.status === "SENT" ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800"}`}>
                      {o.status}
                    </span>
                  </td>
                  <td className="whitespace-nowrap text-muted">{formatDateTime(o.createdAt)}</td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-center text-muted">No outreach sent yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
