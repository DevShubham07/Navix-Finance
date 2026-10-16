"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, UserPlus, Mail, MessageSquare } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton, toast } from "@/components/ui";
import { Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { normalizeMobile } from "@/lib/utils";
import {
  ApplicationApiError,
  dsaApi,
  paiseToINR,
  rupeesToPaise,
  type DsaLeadStatus,
  type DsaLeadView,
  type CreateDsaLeadInput,
  type OutreachChannel,
} from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { OutcomeChip, OUTCOME_LABEL } from "@/components/staff/lead-outcome";
import { type FilterColumn } from "@/components/staff/column-filter";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useClientRegister } from "@/lib/staff/use-client-register";
import { Th, thBag } from "@/lib/staff/register-th";

const PAN_REGEX = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

const STATUSES: DsaLeadStatus[] = ["NOT_APPLIED", "APPLIED", "IN_PROGRESS", "DISBURSED", "REPAID", "DECLINED"];

/** Sortable + filterable columns. PAN and mobile are identity, so they get no dropdown. Status is
 *  absent on purpose: the Status select above the table already narrows it (server-side), and two
 *  controls for one field is a trap. */
const SORT_KEYS = ["name", "uploaded", "leadOutcome", "dsaNote", "netDisbursedPaise", "commissionPaise"] as const;

const COLUMN_LABEL: Record<string, string> = {
  name: "Name",
  uploaded: "Source",
  leadOutcome: "Outcome",
  dsaNote: "Note from us",
  netDisbursedPaise: "Net disbursed",
  commissionPaise: "Commission",
};

/** The fixed SMS template every DSA lead invite goes out as — the DSA cannot change the wording. */
const SMS_TEMPLATE = (name: string) =>
  `Hi ${name || "there"}, DhanBoost can get you a quick salary-linked advance. Reply YES and we'll call you, or apply directly at dhanboost.com.`;

/**
 * DSA portal · My leads — add a lead (PAN + name + mobile required) and track its live conversion
 * status. Firewalled: `dsaApi` only ever reads/writes leads the caller owns (server-enforced via the
 * JWT). No customer/KYC data is ever rendered here — only what the DSA typed, plus a coarse status
 * and (once disbursed) the net disbursed amount + commission.
 */
export default function DsaLeadsPage() {
  // useRegisterQuery reads the URL, which needs a Suspense boundary above it.
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded border border-line bg-white" />}>
      <DsaLeadsPageInner />
    </React.Suspense>
  );
}

function DsaLeadsPageInner() {
  const me = useStaffMe().data;
  const myRole = me?.role;
  const can = useCan();
  const qc = useQueryClient();
  const [q, setQ] = React.useState("");
  const [status, setStatus] = React.useState<DsaLeadStatus | "">("");
  const [outreachLead, setOutreachLead] = React.useState<DsaLeadView | null>(null);

  const list = useQuery({
    queryKey: ["dsa-leads", q, status],
    queryFn: () => dsaApi.listLeads({ q: q || undefined, status: status || undefined }),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["dsa-leads"] });

  const rows = React.useMemo(() => list.data ?? [], [list.data]);

  const rq = useRegisterQuery({
    registerId: "dsa-leads",
    staffId: me?.id,
    sortKeys: SORT_KEYS,
    filterKeys: SORT_KEYS,
    defaults: { sort: { key: "name", dir: "asc" } },
  });

  const filterColumns = React.useMemo<Array<FilterColumn<DsaLeadView>>>(
    () => [
      { key: "name", kind: "text", value: (l) => l.name },
      // The chip reads "Entered"/"Uploaded"; the filter travels as ENTERED/UPLOADED, not the label.
      { key: "uploaded", value: (l) => (l.uploaded ? "Uploaded" : "Entered"), raw: (l) => (l.uploaded ? "UPLOADED" : "ENTERED") },
      { key: "leadOutcome", value: (l) => OUTCOME_LABEL[l.leadOutcome] ?? l.leadOutcome, raw: (l) => l.leadOutcome },
      { key: "dsaNote", kind: "text", value: (l) => l.dsaNote ?? "" },
      // Rupees on both money columns, null (not yet disbursed) stays null rather than 0.
      { key: "netDisbursedPaise", kind: "num", value: (l) => paiseToINR(l.netDisbursedPaise), num: (l) => (l.netDisbursedPaise != null ? l.netDisbursedPaise / 100 : null) },
      { key: "commissionPaise", kind: "num", value: (l) => paiseToINR(l.commissionPaise), num: (l) => (l.commissionPaise != null ? l.commissionPaise / 100 : null) },
    ],
    [],
  );

  const cr = useClientRegister(rows, filterColumns, rq);
  const { pageRows, total, pageCount } = cr;
  const th = thBag(rq, cr, filterColumns, COLUMN_LABEL);
  const page = rq.state.page;
  const pageSize = rq.state.size;

  if (myRole && !can("dsa:portal")) {
    return <NoAccessNotice message="DSA access required." />;
  }

  return (
    <div>
      <PageHeader
        title="My leads"
        subtitle="Add a lead (PAN, name and mobile required). Track its status as it moves through DhanBoost."
      >
        <button
          type="button"
          onClick={() => list.refetch()}
          className="btn btn-sm btn-outline"
          disabled={list.isFetching}
        >
          {list.isFetching ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          Refresh
        </button>
      </PageHeader>

      <NewDsaLeadForm onCreated={invalidate} />

      <div className="mt-6 flex flex-wrap items-end gap-3">
        <SearchBar
          initialValue={q}
          onSearch={(t) => {
            setQ(t);
            // The query key changed, so "what matches" did — back to page 1, filters untouched.
            rq.apply({});
          }}
          placeholder="Name, PAN or mobile"
          ariaLabel="Search"
        />
        <Select
          label="Status"
          className="!mb-0"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as DsaLeadStatus | "");
            rq.apply({});
          }}
        >
          <option value="">All</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace(/_/g, " ")}
            </option>
          ))}
        </Select>
      </div>

      {list.isError && <ErrorState error={list.error} onRetry={() => void list.refetch()} className="mt-3 py-4" />}

      <div className="staff-table-scroll mt-4 rounded-lg border border-navy/10 bg-white">
        <table className="staff-data-table">
          <thead>
            <tr>
              <th>S.No.</th>
              <Th th={th} col="name" />
              <Th th={th} col="uploaded" />
              <th>PAN</th>
              <th>Mobile</th>
              <th>Status</th>
              <Th th={th} col="leadOutcome" />
              <Th th={th} col="dsaNote" />
              <Th th={th} col="netDisbursedPaise" className="num text-right" />
              <Th th={th} col="commissionPaise" className="num text-right" />
              <th className="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 &&
              (list.isLoading ? (
                <tr>
                  <td colSpan={11}>
                    <Skeleton variant="table" rows={8} cols={11} />
                  </td>
                </tr>
              ) : (
                <EmptyState inTable={11} title="No leads yet — add one above." />
              ))}
            {rows.length > 0 && total === 0 ? (
              <EmptyState inTable={11} title="No leads match the column filters." />
            ) : null}
            {pageRows.map((row, i) => (
              <tr key={row.id}>
                <td className="text-navy/60">{(page - 1) * pageSize + i + 1}</td>
                <td className="staff-cell font-medium text-navy">{row.name}</td>
                <td>
                  <span className={`whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ${
                    row.uploaded ? "bg-grey-100 text-muted" : "bg-navy-tint text-navy"}`}>
                    {row.uploaded ? "Uploaded" : "Entered"}
                  </span>
                </td>
                <td className="font-mono text-xs">{row.pan ?? "—"}</td>
                <td className="font-mono text-xs">{row.mobile}</td>
                <td>
                  <StatusChip status={row.status} />
                </td>
                <td><OutcomeChip outcome={row.leadOutcome} /></td>
                <td className="staff-cell max-w-[16rem] text-navy/70" title={row.dsaNote ?? undefined}>
                  {row.dsaNote ?? "—"}
                </td>
                <td className="num text-right">{paiseToINR(row.netDisbursedPaise)}</td>
                <td className="num text-right font-semibold text-navy">{paiseToINR(row.commissionPaise)}</td>
                <td className="text-right">
                  {row.uploaded ? (
                    <span className="text-xs text-navy/40" title="Leads from an uploaded list are view-only">
                      View only
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="btn btn-sm btn-outline"
                      onClick={() => setOutreachLead(row)}
                    >
                      <MessageSquare size={13} /> Outreach
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <PaginationBar
          page={page}
          pageCount={pageCount}
          setPage={rq.setPage}
          total={total}
          pageSize={pageSize}
          setPageSize={rq.setSize}
        />
      </div>

      {outreachLead && (
        <OutreachDialog lead={outreachLead} onClose={() => setOutreachLead(null)} />
      )}
    </div>
  );
}

/** LEAD_ALREADY_KNOWN must never disclose whether the PAN belongs to another DSA's lead or an
 *  existing customer — both render identically. */
function createLeadErrorMessage(e: unknown): string {
  if (e instanceof ApplicationApiError && e.code === "LEAD_ALREADY_KNOWN") {
    return "This person is already known to us.";
  }
  return errMessage(e);
}

function NewDsaLeadForm({ onCreated }: { onCreated: () => void }) {
  const [open, setOpen] = React.useState(false);
  const [pan, setPan] = React.useState("");
  const [name, setName] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [city, setCity] = React.useState("");
  const [employer, setEmployer] = React.useState("");
  const [salary, setSalary] = React.useState("");
  const [amount, setAmount] = React.useState("");
  const [notes, setNotes] = React.useState("");

  const panValid = PAN_REGEX.test(pan.trim());
  const canSubmit = panValid && name.trim().length > 0 && /^[0-9]{10}$/.test(mobile.trim());

  const create = useMutation({
    mutationFn: () => {
      const body: CreateDsaLeadInput = {
        pan: pan.trim().toUpperCase(),
        name: name.trim(),
        mobile: mobile.trim(),
      };
      if (email.trim()) body.email = email.trim();
      if (city.trim()) body.city = city.trim();
      if (employer.trim()) body.employer = employer.trim();
      if (salary.trim() && Number(salary) > 0) body.monthlySalaryPaise = rupeesToPaise(Number(salary));
      if (amount.trim() && Number(amount) > 0) body.loanAmountInterestedPaise = rupeesToPaise(Number(amount));
      if (notes.trim()) body.notes = notes.trim();
      return dsaApi.createLead(body);
    },
    onSuccess: () => {
      setPan("");
      setName("");
      setMobile("");
      setEmail("");
      setCity("");
      setEmployer("");
      setSalary("");
      setAmount("");
      setNotes("");
      setOpen(false);
      onCreated();
      toast.success("Lead saved");
    },
  });

  return (
    <div className="mt-4 rounded-lg border border-navy/10 bg-white p-4">
      <button type="button" className="btn btn-sm btn-gold" onClick={() => setOpen((v) => !v)}>
        <UserPlus size={14} />
        {open ? "Hide form" : "New lead"}
      </button>

      {open && (
        <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Input
            label="PAN"
            required
            value={pan}
            onChange={(e) => setPan(e.target.value.toUpperCase())}
            placeholder="ABCDE1234F"
            maxLength={10}
            error={pan.trim().length > 0 && !panValid ? "Enter a valid PAN, e.g. ABCDE1234F" : undefined}
            className="!mb-0"
          />
          <Input label="Name" required value={name} onChange={(e) => setName(e.target.value)} className="!mb-0" />
          <Input
            label="Mobile"
            required
            value={mobile}
            onChange={(e) => setMobile(normalizeMobile(e.target.value))}
            inputMode="numeric"
            placeholder="10 digits"
            className="!mb-0"
          />
          <Input label="Email" value={email} onChange={(e) => setEmail(e.target.value)} type="email" className="!mb-0" />
          <Input label="City" value={city} onChange={(e) => setCity(e.target.value)} className="!mb-0" />
          <Input label="Employer" value={employer} onChange={(e) => setEmployer(e.target.value)} className="!mb-0" />
          <Input
            label="Monthly salary (₹)"
            value={salary}
            onChange={(e) => setSalary(e.target.value)}
            inputMode="decimal"
            className="!mb-0"
          />
          <Input
            label="Loan amount interested (₹)"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="decimal"
            className="!mb-0"
          />
          <Input label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} className="!mb-0 sm:col-span-2 lg:col-span-1" />
          <div className="sm:col-span-2 lg:col-span-3">
            <button
              type="button"
              className="btn btn-navy disabled:opacity-50"
              disabled={!canSubmit || create.isPending}
              onClick={() => create.mutate()}
            >
              {create.isPending ? <Loader2 size={14} className="animate-spin" /> : null}
              Save lead
            </button>
            {create.isError && (
              <p className="mt-2 text-sm text-red-700">{createLeadErrorMessage(create.error)}</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function OutreachDialog({ lead, onClose }: { lead: DsaLeadView; onClose: () => void }) {
  const qc = useQueryClient();
  const [channel, setChannel] = React.useState<OutreachChannel>("EMAIL");
  const [template, setTemplate] = React.useState<string>("");
  // Only asked when the lead has no email on file; the backend saves it onto the lead.
  const [email, setEmail] = React.useState("");

  const templates = useQuery({
    queryKey: ["dsa-email-templates", lead.id],
    queryFn: () => dsaApi.emailTemplates(lead.id),
  });
  const chosen = templates.data?.find((t) => t.key === template) ?? templates.data?.[0];

  const send = useMutation({
    mutationFn: () =>
      dsaApi.outreach(lead.id, {
        channel,
        template: channel === "EMAIL" ? chosen?.key : undefined,
        email: channel === "EMAIL" && !lead.email ? email.trim() : undefined,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dsa-leads"] }),
  });

  const emailOk = !!lead.email || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());
  const canSend = channel === "SMS" || (!!chosen && emailOk);

  return (
    <Dialog open onClose={onClose} aria-labelledby="dsa-outreach-title" size="lg">
      <DialogHeader>
        <DialogTitle id="dsa-outreach-title">Contact {lead.name}</DialogTitle>
        <p className="text-sm text-navy/60">{lead.mobile}{lead.email ? ` · ${lead.email}` : ""}</p>
      </DialogHeader>

      <div className="flex gap-2">
        {(["EMAIL", "SMS"] as OutreachChannel[]).map((c) => (
          <button
            key={c}
            type="button"
            onClick={() => setChannel(c)}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold ${
              channel === c ? "bg-navy text-white" : "border border-navy/20 text-navy/60 hover:bg-navy/5"
            }`}
          >
            {c === "EMAIL" ? <Mail size={13} className="mr-1 inline" /> : <MessageSquare size={13} className="mr-1 inline" />}
            {c === "EMAIL" ? "Email" : "SMS"}
          </button>
        ))}
      </div>

      {channel === "EMAIL" ? (
        <div className="mt-4 space-y-3">
          {!lead.email && (
            <Input
              label="Lead's email"
              required
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="name@example.com"
              helperText="This lead has no email on file — it will be saved to the lead."
              className="!mb-0"
            />
          )}
          {templates.isLoading ? (
            <Skeleton variant="table" rows={3} cols={3} />
          ) : templates.isError ? (
            <ErrorState error={templates.error} onRetry={() => void templates.refetch()} className="py-4" />
          ) : (
            <>
              <div className="grid gap-2 sm:grid-cols-3" role="radiogroup" aria-label="Email template">
                {templates.data?.map((t) => (
                  <button
                    key={t.key}
                    type="button"
                    role="radio"
                    aria-checked={chosen?.key === t.key}
                    onClick={() => setTemplate(t.key)}
                    className={`rounded-lg border p-3 text-left transition ${
                      chosen?.key === t.key
                        ? "border-navy bg-navy/5 ring-2 ring-gold"
                        : "border-navy/15 hover:border-navy/40"
                    }`}
                  >
                    <span className="block text-sm font-semibold text-navy">{t.name}</span>
                    <span className="mt-0.5 block text-xs text-navy/60">{t.subject}</span>
                  </button>
                ))}
              </div>
              {chosen && (
                <iframe
                  title={`Preview: ${chosen.name}`}
                  srcDoc={chosen.html}
                  sandbox=""
                  className="h-[26rem] w-full rounded-lg border border-navy/10 bg-white"
                />
              )}
            </>
          )}
        </div>
      ) : (
        <div className="mt-4">
          <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-navy/50">
            Fixed SMS template — cannot be edited
          </p>
          <p className="rounded border border-dashed border-navy/20 bg-ivory/60 p-3 text-sm text-navy/80">
            {SMS_TEMPLATE(lead.name)}
          </p>
        </div>
      )}

      {send.isError && <p className="mt-3 text-sm text-red-700">{errMessage(send.error)}</p>}
      {send.isSuccess && (
        <p className={`mt-3 text-sm ${send.data.status === "SENT" ? "text-emerald-700" : "text-red-700"}`}>
          {send.data.status === "SENT"
            ? "Sent."
            : `Not sent (${send.data.status.toLowerCase()})${send.data.error ? ` — ${send.data.error}` : ""}`}
        </p>
      )}

      <DialogFooter>
        <button type="button" className="btn btn-outline" onClick={onClose}>
          Close
        </button>
        <button
          type="button"
          className="btn btn-navy disabled:opacity-50"
          disabled={!canSend || send.isPending}
          onClick={() => send.mutate()}
        >
          {send.isPending ? <Loader2 size={14} className="animate-spin" /> : null}
          Send {channel === "EMAIL" ? "email" : "SMS"}
        </button>
      </DialogFooter>
    </Dialog>
  );
}

function StatusChip({ status }: { status: DsaLeadStatus }) {
  const tone =
    status === "NOT_APPLIED"
      ? "bg-navy/10 text-navy"
      : status === "DECLINED"
        ? "bg-red-50 text-red-800"
        : status === "APPLIED" || status === "IN_PROGRESS"
          ? "bg-amber-50 text-amber-900"
          : "bg-emerald-50 text-emerald-800";
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${tone}`}>
      {status.replace(/_/g, " ")}
    </span>
  );
}
