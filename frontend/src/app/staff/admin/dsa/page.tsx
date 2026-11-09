"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Check, Ban, ArrowRightLeft } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton, toast } from "@/components/ui";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { type FilterColumn } from "@/components/staff/column-filter";
import { istDay } from "@/lib/staff/column-filter-match";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { OUTCOME_LABEL } from "@/components/staff/lead-outcome";
import { LeadCsvImport } from "@/components/staff/lead-csv-import";
import { OutcomeChip } from "@/components/staff/lead-outcome";
import { formatDateTime } from "@/lib/utils";
import {
  type DsaLeadAttribution,
  adminDsaApi,
  paiseToINR,
  type AdminDsaRosterView,
  type AdminDsaLeadView,
  type AdminDsaCommissionView,
  type AdminOutreachView,
  type DsaCommissionStatus,
} from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";

type Tab = "ROSTER" | "LEADS" | "COMMISSIONS" | "OUTREACH";

const COMMISSION_STATUSES: DsaCommissionStatus[] = ["ACCRUED", "PAYABLE", "PAID", "VOID"];

/** Per-table column declarations. Every table passes `url: false`: several registers on one page
 *  would overwrite each other's `sort=` / `f.*` in a single query string, so the URL stays with the
 *  page (its tab and server controls) and each table keeps only its own remembered view, namespaced
 *  by its registerId (admin-dsa-roster / -leads / -commissions / -outreach). */
const ROSTER_KEYS = ["name", "active", "leadsAdded", "leadsConverted", "accruedPaise", "payablePaise", "paidPaise"] as const;
const ROSTER_LABEL: Record<string, string> = {
  name: "DSA",
  active: "Status",
  leadsAdded: "Leads",
  leadsConverted: "Converted",
  accruedPaise: "Accrued",
  payablePaise: "Payable",
  paidPaise: "Paid",
};

/** DSA and Attribution (leads), DSA and Status (commissions) and DSA (outreach) are deliberately not
 *  here: a select above each table already narrows them server-side, and two controls for one field
 *  is a trap. Created has the From/To window for the same reason. */
const LEAD_KEYS = ["name", "leadOutcome", "dsaNote", "city"] as const;
const LEAD_LABEL: Record<string, string> = {
  name: "Name",
  leadOutcome: "Outcome",
  dsaNote: "Note for DSA",
  city: "City",
};

const COMMISSION_KEYS = ["netDisbursedPaise", "amountPaise"] as const;
const COMMISSION_LABEL: Record<string, string> = {
  netDisbursedPaise: "Net disbursed",
  amountPaise: "Commission",
};

const OUTREACH_KEYS = ["channel", "status", "createdAt"] as const;
const OUTREACH_LABEL: Record<string, string> = { channel: "Channel", status: "Status", createdAt: "Sent" };

/**
 * Admin · DSA program — the roster + roll-ups, the company-wide DSA lead register (full detail,
 * ADMIN sees everything), the commission ledger with Mark paid / Void / Reassign, and the outreach
 * audit log. ADMIN only; reaches DSA data exclusively through /api/admin/dsa (never the DSA's own
 * /api/dsa surface).
 */
export default function AdminDsaPage() {
  // The tables' views live in the URL, which needs a Suspense boundary above them.
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded border border-line bg-white" />}>
      <AdminDsaPageInner />
    </React.Suspense>
  );
}

function AdminDsaPageInner() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const staffId = me?.id;
  const can = useCan();
  const qc = useQueryClient();
  const [tab, setTab] = React.useState<Tab>("ROSTER");

  const roster = useQuery({ queryKey: ["admin-dsa-roster"], queryFn: adminDsaApi.roster });

  if (myRole && !can("staff:manage")) {
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
      {/* The tiles sum the whole roster. The tabs below each narrow their own table (selects, search,
          column filters), and none of that reaches these totals — say so, rather than leave a reader
          to wonder whether a filtered table should have moved them. */}
      <p className="mt-2 text-xs text-muted">
        Totals across every DSA — not narrowed by the tab, its filters or search.
      </p>

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

      {tab === "ROSTER" && (
        <RosterTab
          staffId={staffId}
          rows={rosterRows}
          loading={roster.isLoading}
          error={roster.error}
          onRetry={() => void roster.refetch()}
        />
      )}
      {tab === "LEADS" && <LeadsTab staffId={staffId} dsaOptions={rosterRows} />}
      {tab === "COMMISSIONS" && <CommissionsTab staffId={staffId} dsaOptions={rosterRows} />}
      {tab === "OUTREACH" && <OutreachTab staffId={staffId} dsaOptions={rosterRows} />}
    </div>
  );
}

function RosterTab({
  staffId,
  rows,
  loading,
  error,
  onRetry,
}: {
  staffId: string | undefined;
  rows: AdminDsaRosterView[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  const rq = useRegisterQuery({
    registerId: "admin-dsa-roster",
    url: false,
    staffId,
    sortKeys: ROSTER_KEYS,
    filterKeys: ROSTER_KEYS,
    defaults: { sort: { key: "name", dir: "asc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<AdminDsaRosterView>>>(
    () => [
      { key: "name", kind: "text", value: (r) => r.name ?? `#${r.dsaStaffId}` },
      { key: "active", value: (r) => (r.active ? "Active" : "Disabled"), raw: (r) => (r.active ? "ACTIVE" : "DISABLED") },
      // Counts, not money: the bound is typed as a plain number.
      { key: "leadsAdded", kind: "num", value: (r) => String(r.leadsAdded), num: (r) => r.leadsAdded },
      { key: "leadsConverted", kind: "num", value: (r) => String(r.leadsConverted), num: (r) => r.leadsConverted },
      // Rupees on the money columns: the bound is typed beside a rupee figure.
      { key: "accruedPaise", kind: "num", value: (r) => paiseToINR(r.accruedPaise), num: (r) => r.accruedPaise / 100 },
      { key: "payablePaise", kind: "num", value: (r) => paiseToINR(r.payablePaise), num: (r) => r.payablePaise / 100 },
      { key: "paidPaise", kind: "num", value: (r) => paiseToINR(r.paidPaise), num: (r) => r.paidPaise / 100 },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, ROSTER_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;
  return (
    <div>
      {error ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : (
        <div className="rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <Th th={th} col="name" />
                  <Th th={th} col="active" />
                  <Th th={th} col="leadsAdded" className="num text-right" />
                  <Th th={th} col="leadsConverted" className="num text-right" />
                  <Th th={th} col="accruedPaise" className="num text-right" />
                  <Th th={th} col="payablePaise" className="num text-right" />
                  <Th th={th} col="paidPaise" className="num text-right" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {pageRows.map((r, i) => (
                  <tr key={r.dsaStaffId}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td className="font-semibold text-ink">{r.name ?? `#${r.dsaStaffId}`}</td>
                    <td>
                      <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${r.active ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800"}`}>
                        {r.active ? "Active" : "Disabled"}
                      </span>
                    </td>
                    <td className="num text-right">{r.leadsAdded}</td>
                    <td className="num text-right">{r.leadsConverted}</td>
                    <td className="num text-right">{paiseToINR(r.accruedPaise)}</td>
                    <td className="num text-right">{paiseToINR(r.payablePaise)}</td>
                    <td className="num text-right font-semibold text-ink">{paiseToINR(r.paidPaise)}</td>
                  </tr>
                ))}
                {rows.length === 0 &&
                  (loading ? (
                    <tr>
                      <td colSpan={8}>
                        <Skeleton variant="table" rows={8} cols={8} />
                      </td>
                    </tr>
                  ) : (
                    <EmptyState title="No DSAs yet." inTable={8} />
                  ))}
                {rows.length > 0 && total === 0 && <EmptyState title="No DSAs match the column filters." inTable={8} />}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}
    </div>
  );
}

/** Who this lead belongs to: its owning DSA, else whoever uploaded the file it came from. */
function dsaLabel(l: AdminDsaLeadView): string {
  if (l.ownerDsaName) return l.ownerDsaName;
  if (l.ownerDsaId != null) return `#${l.ownerDsaId}`;
  return l.createdByStaffName ?? `#${l.createdByStaffId}`;
}

function LeadsTab({ staffId, dsaOptions }: { staffId: string | undefined; dsaOptions: AdminDsaRosterView[] }) {
  const qc = useQueryClient();
  const [dsaId, setDsaId] = React.useState<string>("");
  const [q, setQ] = React.useState("");
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  // Imported leads carry no owner_dsa_id (so a bulk upload can never manufacture commission), and
  // this register used to filter on exactly that column — which hid every uploaded lead from the one
  // tab meant to show DSA lead activity. Default to showing both.
  const [attribution, setAttribution] = React.useState<DsaLeadAttribution | "">("");

  const leads = useQuery({
    queryKey: ["admin-dsa-leads", dsaId, q, from, to, attribution],
    queryFn: () =>
      adminDsaApi.leads({
        dsaId: dsaId ? Number(dsaId) : undefined,
        q: q || undefined,
        from: from || undefined,
        to: to || undefined,
        attribution: attribution || undefined,
      }),
  });

  const rows = React.useMemo(() => leads.data ?? [], [leads.data]);

  const rq = useRegisterQuery({
    registerId: "admin-dsa-leads",
    url: false,
    staffId,
    sortKeys: LEAD_KEYS,
    filterKeys: LEAD_KEYS,
    defaults: { sort: { key: "name", dir: "asc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<AdminDsaLeadView>>>(
    () => [
      { key: "name", kind: "text", value: (l) => l.name },
      { key: "leadOutcome", value: (l) => OUTCOME_LABEL[l.leadOutcome] ?? l.leadOutcome, raw: (l) => l.leadOutcome },
      { key: "dsaNote", kind: "text", value: (l) => l.dsaNote ?? "" },
      // A blank city is a group worth ticking; the empty raw value is how the engine spells "none".
      { key: "city", value: (l) => l.city ?? "No city", raw: (l) => l.city ?? "" },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, LEAD_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select
          label="DSA"
          className="!mb-0"
          value={dsaId}
          onChange={(e) => {
            setDsaId(e.target.value);
            // The server-side set changed — back to page 1, column filters untouched.
            rq.apply({});
          }}
        >
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>
              {d.name ?? `#${d.dsaStaffId}`}
            </option>
          ))}
        </Select>
        <Select
          label="Attribution"
          className="!mb-0"
          value={attribution}
          onChange={(e) => {
            setAttribution(e.target.value as DsaLeadAttribution | "");
            rq.apply({});
          }}
        >
          <option value="">Entered + uploaded</option>
          <option value="ENTERED">Entered (earns commission)</option>
          <option value="UPLOADED">Uploaded from a file</option>
        </Select>
        <SearchBar
          initialValue={q}
          onSearch={(t) => {
            setQ(t);
            rq.apply({});
          }}
          placeholder="Name, PAN or mobile"
          ariaLabel="Search"
        />
        <Input label="From" type="date" value={from} onChange={(e) => { setFrom(e.target.value); rq.apply({}); }} className="!mb-0" />
        <Input label="To" type="date" value={to} onChange={(e) => { setTo(e.target.value); rq.apply({}); }} className="!mb-0" />
        <ExportMenu
          title="DSA lead register"
          subtitle={describeRegister(th) || undefined}
          fileBase="dhanboost-dsa-leads"
          columns={[
            { header: "DSA", value: (l: AdminDsaLeadView) => dsaLabel(l) },
            { header: "Attribution", value: (l) => (l.owned ? "Entered" : "Uploaded") },
            { header: "Outcome", value: (l) => l.leadOutcome },
            { header: "Note for DSA", value: (l) => l.dsaNote ?? "" },
            { header: "PAN", value: (l) => l.pan },
            { header: "Name", value: (l) => l.name },
            { header: "Mobile", value: (l) => l.mobile },
            { header: "Email", value: (l) => l.email ?? "" },
            { header: "City", value: (l) => l.city ?? "" },
            { header: "Employer", value: (l) => l.employer ?? "" },
            { header: "Created", value: (l) => formatDateTime(l.createdAt) },
          ]}
          rows={cr.rows}
          scope="all"
        />
        <LeadCsvImport
          onImported={() => {
            qc.invalidateQueries({ queryKey: ["admin-leads"] });
            qc.invalidateQueries({ queryKey: ["lead-stats"] });
            qc.invalidateQueries({ queryKey: ["admin-dsa-leads"] });
          }}
        />
      </div>

      {leads.isLoading ? (
        <Skeleton variant="table" rows={8} cols={10} className="rounded border border-line bg-white" />
      ) : leads.error ? (
        <ErrorState error={leads.error} onRetry={() => void leads.refetch()} />
      ) : (
        <div className="rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>DSA</th>
                  <th>Attribution</th>
                  <th>PAN</th>
                  <Th th={th} col="name" />
                  <th>Mobile</th>
                  <Th th={th} col="leadOutcome" />
                  <Th th={th} col="dsaNote" />
                  <Th th={th} col="city" />
                  <th>Created</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {pageRows.map((l, i) => (
                  <tr key={l.id}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                    <td className="text-ink">{dsaLabel(l)}</td>
                    <td>
                      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${
                        l.owned ? "bg-navy-tint text-navy" : "bg-grey-100 text-muted"}`}>
                        {l.owned ? "Entered" : "Uploaded"}
                      </span>
                    </td>
                    <td className="font-mono text-xs">{l.pan ?? "—"}</td>
                    <td className="text-ink">{l.name}</td>
                    <td className="font-mono text-xs">{l.mobile}</td>
                    <td><OutcomeChip outcome={l.leadOutcome} /></td>
                    <td className="max-w-[16rem] truncate text-muted" title={l.dsaNote ?? undefined}>
                      {l.dsaNote ?? "—"}
                    </td>
                    <td className="text-muted">{l.city ?? "—"}</td>
                    <td className="whitespace-nowrap text-muted">{formatDateTime(l.createdAt)}</td>
                  </tr>
                ))}
                {total === 0 && (
                  <EmptyState
                    title={rq.activeCount > 0 ? "No leads match the column filters." : "No leads match these filters."}
                    inTable={10}
                  />
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}
    </div>
  );
}

function CommissionsTab({ staffId, dsaOptions }: { staffId: string | undefined; dsaOptions: AdminDsaRosterView[] }) {
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
      toast.success("Commission marked paid");
    },
  });
  const voidMut = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => adminDsaApi.void(id, reason),
    onSuccess: (_d, vars) => {
      setVoidReasons((m) => ({ ...m, [vars.id]: "" }));
      invalidate();
      toast.success("Commission voided");
    },
  });
  const reassign = useMutation({
    mutationFn: ({ id, toDsaStaffId, reason }: { id: number; toDsaStaffId: number; reason: string }) =>
      adminDsaApi.reassign(id, toDsaStaffId, reason),
    onSuccess: (_d, vars) => {
      setReassignTo((m) => ({ ...m, [vars.id]: "" }));
      setReassignReason((m) => ({ ...m, [vars.id]: "" }));
      invalidate();
      toast.success("Commission reassigned");
    },
  });

  const rows = React.useMemo(() => commissions.data ?? [], [commissions.data]);

  const rq = useRegisterQuery({
    registerId: "admin-dsa-commissions",
    url: false,
    staffId,
    sortKeys: COMMISSION_KEYS,
    filterKeys: COMMISSION_KEYS,
    defaults: { sort: { key: "amountPaise", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<AdminDsaCommissionView>>>(
    () => [
      // Rupees on both money columns: the bound is typed beside a rupee figure.
      { key: "netDisbursedPaise", kind: "num", value: (c) => paiseToINR(c.netDisbursedPaise), num: (c) => c.netDisbursedPaise / 100 },
      { key: "amountPaise", kind: "num", value: (c) => paiseToINR(c.amountPaise), num: (c) => c.amountPaise / 100 },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COMMISSION_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select
          label="DSA"
          className="!mb-0"
          value={dsaId}
          onChange={(e) => {
            setDsaId(e.target.value);
            // The server-side set changed — back to page 1, column filters untouched.
            rq.apply({});
          }}
        >
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>
              {d.name ?? `#${d.dsaStaffId}`}
            </option>
          ))}
        </Select>
        <Select
          label="Status"
          className="!mb-0"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as DsaCommissionStatus | "");
            rq.apply({});
          }}
        >
          <option value="">All</option>
          {COMMISSION_STATUSES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </Select>
        <ExportMenu
          title="DSA commission ledger"
          subtitle={describeRegister(th) || undefined}
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
          rows={cr.rows}
          scope="all"
        />
      </div>

      {commissions.isLoading ? (
        <Skeleton variant="table" rows={8} cols={7} className="rounded border border-line bg-white" />
      ) : commissions.error ? (
        <ErrorState error={commissions.error} onRetry={() => void commissions.refetch()} />
      ) : (
        <div className="rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>DSA</th>
                  <th>PAN</th>
                  <Th th={th} col="netDisbursedPaise" className="num text-right" />
                  <Th th={th} col="amountPaise" className="num text-right" />
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {pageRows.map((c, i) => (
                  <tr key={c.id}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                  <td className="text-ink">{c.dsaName ?? `#${c.dsaStaffId}`}</td>
                  <td className="font-mono text-xs">{c.pan ?? "—"}</td>
                  <td className="num text-right">{paiseToINR(c.netDisbursedPaise)}</td>
                  <td className="num text-right font-semibold text-ink">{paiseToINR(c.amountPaise)}</td>
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
              {total === 0 && (
                <EmptyState
                  title={rq.activeCount > 0 ? "No commissions match the column filters." : "No commissions match these filters."}
                  inTable={7}
                />
              )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}
      {(pay.error || voidMut.error || reassign.error) && (
        <p className="mt-3 text-sm text-error-700">{errMessage(pay.error || voidMut.error || reassign.error)}</p>
      )}
    </div>
  );
}

function OutreachTab({ staffId, dsaOptions }: { staffId: string | undefined; dsaOptions: AdminDsaRosterView[] }) {
  const [dsaId, setDsaId] = React.useState<string>("");

  const outreach = useQuery({
    queryKey: ["admin-dsa-outreach", dsaId],
    queryFn: () => adminDsaApi.outreach({ dsaId: dsaId ? Number(dsaId) : undefined }),
  });

  const rows = React.useMemo<AdminOutreachView[]>(() => outreach.data ?? [], [outreach.data]);

  const rq = useRegisterQuery({
    registerId: "admin-dsa-outreach",
    url: false,
    staffId,
    sortKeys: OUTREACH_KEYS,
    filterKeys: OUTREACH_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<AdminOutreachView>>>(
    () => [
      { key: "channel", value: (o) => o.channel },
      { key: "status", value: (o) => o.status },
      // The IST business day, agreeing with what the date filter compares — never a UTC slice.
      { key: "createdAt", kind: "date", value: (o) => istDay(o.createdAt) ?? "—", date: (o) => istDay(o.createdAt) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, OUTREACH_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Select
          label="DSA"
          className="!mb-0"
          value={dsaId}
          onChange={(e) => {
            setDsaId(e.target.value);
            // The server-side set changed — back to page 1, column filters untouched.
            rq.apply({});
          }}
        >
          <option value="">All DSAs</option>
          {dsaOptions.map((d) => (
            <option key={d.dsaStaffId} value={d.dsaStaffId}>{d.name ?? `#${d.dsaStaffId}`}</option>
          ))}
        </Select>
      </div>

      {outreach.isLoading ? (
        <Skeleton variant="table" rows={8} cols={7} className="rounded border border-line bg-white" />
      ) : outreach.error ? (
        <ErrorState error={outreach.error} onRetry={() => void outreach.refetch()} />
      ) : (
        <div className="rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>S.No.</th>
                  <th>Lead #</th>
                  <Th th={th} col="channel" />
                  <th>To</th>
                  <th>Subject / body</th>
                  <Th th={th} col="status" />
                  <Th th={th} col="createdAt" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {pageRows.map((o, i) => (
                  <tr key={o.id}>
                    <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
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
                {total === 0 && (
                  <EmptyState
                    title={rq.activeCount > 0 ? "No outreach matches the column filters." : "No outreach sent yet."}
                    inTable={7}
                  />
                )}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={rq.setPage} total={total} pageSize={pageSize} setPageSize={rq.setSize} />
        </div>
      )}
    </div>
  );
}
