"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Phone, Star } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { hasPermission } from "@/lib/auth/rbac";
import { normalizeMobile } from "@/lib/utils";
import {
  leadsApi,
  rupeesToPaise,
  paiseToINR,
  type LeadCallStatus,
  type LeadSource,
  type LeadView,
  type CreateLeadInput,
} from "@/lib/api/applications";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

const CALL_STATUSES: LeadCallStatus[] = [
  "NOT_CALLED",
  "CALLED",
  "CALLBACK",
  "NO_ANSWER",
  "NOT_INTERESTED",
  "WRONG_NUMBER",
  "CONNECTED",
];

const SOURCES: LeadSource[] = ["DSA", "REFERRAL", "WALK_IN", "OTHER"];

/**
 * Telecaller work queue — create DSA-style leads and update call disposition (status + ★ + remarks).
 */
export default function StaffLeadsPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const [q, setQ] = React.useState("");
  const [callStatus, setCallStatus] = React.useState<LeadCallStatus | "">("");
  const [selectedId, setSelectedId] = React.useState<number | null>(null);

  const list = useQuery({
    queryKey: ["leads", q, callStatus],
    queryFn: () =>
      leadsApi.list({
        q: q || undefined,
        callStatus: callStatus || undefined,
      }),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["leads"] });

  const rows = list.data ?? [];
  const selected = rows.find((r) => r.id === selectedId) ?? null;
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  if (myRole && !hasPermission(myRole, "leads:manage")) {
    return <NoAccessNotice message="Telecaller or Admin access required." />;
  }

  return (
    <div>
      <PageHeader
        title="Leads"
        subtitle="Enter new leads (name + mobile required). Update call status, quality ★ and remarks."
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

      <NewLeadForm onCreated={invalidate} />

      <div className="mt-6 flex flex-wrap items-end gap-3">
        <Input
          label="Search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Name or mobile"
          className="!mb-0"
        />
        <Select
          label="Call status"
          className="!mb-0"
          value={callStatus}
          onChange={(e) => setCallStatus(e.target.value as LeadCallStatus | "")}
        >
          <option value="">All</option>
          {CALL_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace(/_/g, " ")}
            </option>
          ))}
        </Select>
      </div>

      {list.isError && (
        <p className="mt-3 text-sm text-red-700">{errMessage(list.error)}</p>
      )}

      <div className="mt-4 grid gap-4 lg:grid-cols-[1fr_320px]">
        <div className="staff-table-scroll rounded-lg border border-navy/10 bg-white">
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>S.No.</th>
                <th>Name</th>
                <th>Mobile</th>
                <th>Source</th>
                <th>Status</th>
                <th>★</th>
                <th>City</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-navy/40">
                    {list.isLoading ? "Loading…" : "No leads yet — add one above."}
                  </td>
                </tr>
              )}
              {pageRows.map((row, i) => (
                <tr
                  key={row.id}
                  onClick={() => setSelectedId(row.id)}
                  className={`cursor-pointer hover:bg-ivory/80 ${selectedId === row.id ? "bg-gold/10" : ""}`}
                >
                  <td className="text-navy/60">{(page - 1) * pageSize + i + 1}</td>
                  <td className="staff-cell font-medium text-navy">{row.name}</td>
                  <td className="font-mono text-xs">{row.mobile}</td>
                  <td className="staff-cell text-navy/70">
                    {row.source ? row.source.replace(/_/g, " ") : "—"}
                    {row.sourceDetail ? ` · ${row.sourceDetail}` : ""}
                  </td>
                  <td>
                    <StatusChip status={row.callStatus} />
                  </td>
                  <td>{row.qualityRating ? `${row.qualityRating}★` : "—"}</td>
                  <td className="staff-cell text-navy/70">{row.city || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
        </div>

        <DispositionPanel lead={selected} onSaved={invalidate} />
      </div>
    </div>
  );
}

function NewLeadForm({ onCreated }: { onCreated: () => void }) {
  const [open, setOpen] = React.useState(false);
  const [name, setName] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [city, setCity] = React.useState("");
  const [employer, setEmployer] = React.useState("");
  const [salary, setSalary] = React.useState("");
  const [amount, setAmount] = React.useState("");
  const [source, setSource] = React.useState<LeadSource | "">("");
  const [sourceDetail, setSourceDetail] = React.useState("");
  const [notes, setNotes] = React.useState("");

  const canSubmit = name.trim().length > 0 && /^[0-9]{10}$/.test(mobile.trim());

  const create = useMutation({
    mutationFn: () => {
      const body: CreateLeadInput = {
        name: name.trim(),
        mobile: mobile.trim(),
      };
      if (email.trim()) body.email = email.trim();
      if (city.trim()) body.city = city.trim();
      if (employer.trim()) body.employer = employer.trim();
      if (salary.trim() && Number(salary) > 0) body.monthlySalaryPaise = rupeesToPaise(Number(salary));
      if (amount.trim() && Number(amount) > 0) {
        body.loanAmountInterestedPaise = rupeesToPaise(Number(amount));
      }
      if (source) body.source = source;
      if (sourceDetail.trim()) body.sourceDetail = sourceDetail.trim();
      if (notes.trim()) body.notes = notes.trim();
      return leadsApi.create(body);
    },
    onSuccess: () => {
      setName("");
      setMobile("");
      setEmail("");
      setCity("");
      setEmployer("");
      setSalary("");
      setAmount("");
      setSource("");
      setSourceDetail("");
      setNotes("");
      setOpen(false);
      onCreated();
    },
  });

  return (
    <div className="mt-4 rounded-lg border border-navy/10 bg-white p-4">
      <button
        type="button"
        className="btn btn-sm btn-gold"
        onClick={() => setOpen((v) => !v)}
      >
        <Phone size={14} />
        {open ? "Hide form" : "New lead"}
      </button>

      {open && (
        <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
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
          <Select
            label="Source"
            className="!mb-0"
            value={source}
            onChange={(e) => setSource(e.target.value as LeadSource | "")}
          >
            <option value="">—</option>
            {SOURCES.map((s) => (
              <option key={s} value={s}>
                {s.replace(/_/g, " ")}
              </option>
            ))}
          </Select>
          <Input
            label="Source detail (DSA name…)"
            value={sourceDetail}
            onChange={(e) => setSourceDetail(e.target.value)}
            className="!mb-0"
          />
          <Input label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} className="!mb-0 sm:col-span-2 lg:col-span-3" />
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
              <p className="mt-2 text-sm text-red-700">{errMessage(create.error)}</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function DispositionPanel({
  lead,
  onSaved,
}: {
  lead: LeadView | null;
  onSaved: () => void;
}) {
  const [status, setStatus] = React.useState<LeadCallStatus>("NOT_CALLED");
  const [rating, setRating] = React.useState<number | "">("");
  const [remarks, setRemarks] = React.useState("");

  React.useEffect(() => {
    if (!lead) return;
    setStatus(lead.callStatus);
    setRating(lead.qualityRating ?? "");
    setRemarks(lead.remarks ?? "");
  }, [lead]);

  const save = useMutation({
    mutationFn: () =>
      leadsApi.disposition(lead!.id, {
        callStatus: status,
        qualityRating: rating === "" ? null : Number(rating),
        remarks: remarks.trim() || undefined,
      }),
    onSuccess: onSaved,
  });

  if (!lead) {
    return (
      <div className="rounded-lg border border-dashed border-navy/20 bg-ivory/50 p-4 text-sm text-navy/50">
        Select a lead to update call status, quality ★ and remarks.
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-navy/10 bg-white p-4">
      <h3 className="font-serif text-lg text-navy">{lead.name}</h3>
      <p className="font-mono text-xs text-navy/60">{lead.mobile}</p>
      {(lead.monthlySalaryPaise != null || lead.loanAmountInterestedPaise != null) && (
        <p className="mt-1 text-xs text-navy/60">
          {lead.monthlySalaryPaise != null && `Salary ${paiseToINR(lead.monthlySalaryPaise)}`}
          {lead.monthlySalaryPaise != null && lead.loanAmountInterestedPaise != null && " · "}
          {lead.loanAmountInterestedPaise != null &&
            `Wants ${paiseToINR(lead.loanAmountInterestedPaise)}`}
        </p>
      )}

      <Select
        label="Call status"
        value={status}
        onChange={(e) => setStatus(e.target.value as LeadCallStatus)}
      >
        {CALL_STATUSES.map((s) => (
          <option key={s} value={s}>
            {s.replace(/_/g, " ")}
          </option>
        ))}
      </Select>

      <div className="mt-3">
        <span className="mb-1 block text-sm font-semibold text-ink">Quality</span>
        <div className="mt-1 flex gap-1">
          {[1, 2, 3, 4, 5].map((n) => (
            <button
              key={n}
              type="button"
              className={`rounded p-1 ${rating === n ? "text-gold" : "text-navy/25 hover:text-gold/70"}`}
              onClick={() => setRating(rating === n ? "" : n)}
              aria-label={`${n} stars`}
            >
              <Star size={20} fill={rating !== "" && n <= Number(rating) ? "currentColor" : "none"} />
            </button>
          ))}
        </div>
      </div>

      <div className="field mt-3">
        <label htmlFor="lead-remarks">Remarks</label>
        <textarea
          id="lead-remarks"
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
        />
      </div>

      <button
        type="button"
        className="btn btn-navy btn-block mt-3 disabled:opacity-50"
        disabled={save.isPending}
        onClick={() => save.mutate()}
      >
        {save.isPending ? <Loader2 size={14} className="animate-spin" /> : null}
        Save disposition
      </button>
      {save.isError && <p className="mt-2 text-sm text-red-700">{errMessage(save.error)}</p>}
      {lead.notes && (
        <p className="mt-3 text-xs text-navy/50">
          <span className="font-semibold">Intake notes:</span> {lead.notes}
        </p>
      )}
    </div>
  );
}

function StatusChip({ status }: { status: LeadCallStatus }) {
  const tone =
    status === "NOT_CALLED"
      ? "bg-navy/10 text-navy"
      : status === "NOT_INTERESTED" || status === "WRONG_NUMBER"
        ? "bg-red-50 text-red-800"
        : status === "CALLBACK" || status === "NO_ANSWER"
          ? "bg-amber-50 text-amber-900"
          : "bg-emerald-50 text-emerald-800";
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-[8px] font-semibold uppercase ${tone}`}>
      {status.replace(/_/g, " ")}
    </span>
  );
}
