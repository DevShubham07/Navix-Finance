"use client";

import * as React from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, Phone, Star } from "lucide-react";
import { EmptyState, ErrorState, Input, Select, Skeleton, StatusBadge, toast } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { OutcomeChip, OUTCOME_LABEL } from "@/components/staff/lead-outcome";
import { normalizeMobile } from "@/lib/utils";
import {
  leadsApi,
  paiseToINR,
  type LeadCallStatus,
  type LeadOutcome,
  SETTABLE_LEAD_OUTCOMES,
  type LeadSource,
  type LeadView,
  type CreateLeadInput,
  type DispositionInput,
} from "@/lib/api/applications";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import { retryUnlessClientError } from "@/lib/staff/admin-all-applications";
import { type FilterColumn } from "@/components/staff/column-filter";
import { Th, thBag } from "@/lib/staff/register-th";
import { LEAD_RUPEE_ERROR, parseLeadRupees } from "@/lib/leads/lead-money-input";
import {
  leadOutcomePatch,
  leadSaveToast,
  settableOutcome,
  type LeadOutcomePatch,
} from "@/lib/leads/lead-disposition-save";

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
 * What each header offers — the server's `LeadBookQuery.FILTER_COLUMNS`. Name, mobile and city are
 * text-only (identity-ish: never a dropdown of tens of thousands of them); source and status are
 * small enums, so checklists; ★ is a number in stars. The server answers in id order only, so none
 * of them sorts. Outcome and S.No. are plain headers: the shown outcome overlays a derived CONFIRMED
 * the stored column cannot filter on, so a filter there would disagree with the cell.
 */
const FILTER_COLUMNS: Array<FilterColumn<LeadView>> = [
  { key: "name", kind: "text", sortable: false, value: (r) => r.name },
  { key: "mobile", kind: "text", sortable: false, value: (r) => r.mobile },
  { key: "source", facet: true, sortable: false, value: (r) => r.source?.replace(/_/g, " ") ?? "—", raw: (r) => r.source ?? "" },
  { key: "callStatus", facet: true, sortable: false, value: (r) => r.callStatus.replace(/_/g, " "), raw: (r) => r.callStatus },
  { key: "rating", kind: "num", sortable: false, value: (r) => (r.qualityRating ? `${r.qualityRating}★` : "—"), num: (r) => r.qualityRating },
  { key: "city", kind: "text", sortable: false, value: (r) => r.city ?? "" },
];
const FILTER_KEYS = FILTER_COLUMNS.map((c) => c.key);
const COLUMN_LABEL: Record<string, string> = {
  name: "Name",
  mobile: "Mobile",
  source: "Source",
  callStatus: "Status",
  rating: "★",
  city: "City",
};
/** The server's RAW values → the words on screen. A filter travels as the raw value, never the label. */
const FACET_LABELS: Record<string, Record<string, string>> = {
  source: Object.fromEntries(SOURCES.map((s) => [s, s.replace(/_/g, " ")])),
  callStatus: Object.fromEntries(CALL_STATUSES.map((s) => [s, s.replace(/_/g, " ")])),
};

/**
 * Telecaller work queue — create DSA-style leads and update call disposition (status + ★ + remarks).
 */
export default function StaffLeadsPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <StaffLeadsPageInner />
    </React.Suspense>
  );
}

function StaffLeadsPageInner() {
  const me = useStaffMe();
  const myRole = me.data?.role;
  const can = useCan();
  // The same answer the page's own gate (below) acts on. Until it resolves, the list waits: it used
  // to fire before the gate had decided, so a role without leads:manage sent a request it could
  // never use.
  const canManage = myRole != null && can("leads:manage");
  const qc = useQueryClient();
  // Search, page and page size live in the register view, which also reads the global-search
  // deep link (`?q=`) and keeps the view in the URL. No `staffId`: a remembered view could restore a
  // `q` after the SearchBar below has already mounted with its draft, leaving box and query apart.
  // Column filters run on the SERVER (`useServerRegister` below): this table is tens of thousands of
  // leads and the page holds 25, so a client-side filter would narrow only what is on screen and read
  // as "no match".
  const rq = useRegisterQuery({
    registerId: "leads",
    sortKeys: ["createdAt"],
    filterKeys: FILTER_KEYS,
    defaults: { sort: { key: "createdAt", dir: "desc" } },
  });
  const q = rq.state.q;
  // Bumped to remount the SearchBar, whose draft text is its own state, when the empty state's
  // "Clear" resets the search from outside it.
  const [searchKey, setSearchKey] = React.useState(0);
  const [selectedId, setSelectedId] = React.useState<number | null>(null);
  // Server paging — the lead table grows without limit and was being fetched whole to show 25 rows.
  const page = rq.state.page;
  const pageSize = rq.state.size;

  // Search, column filters and paging are all in `rq.queryParams`/`rq.state`; the key prefix is the
  // `["leads"]` every mutation below invalidates. No facet is fetched on mount — a header's panel
  // opening calls `cr.requestFacet`.
  const cr = useServerRegister<LeadView>(rq, {
    key: ["leads"],
    fetchPage: (params, pg, size) => leadsApi.page({ extra: params, page: pg, size }),
    fetchFacet: (column, params) => leadsApi.facet(column, params),
    labels: FACET_LABELS,
    // Not before the role gate has answered; and a 403 is final, so no retries.
    enabled: canManage,
    retry: retryUnlessClientError,
  });
  const th = thBag(rq, cr, FILTER_COLUMNS, COLUMN_LABEL, cr.requestFacet);

  // Signed-in state could not be confirmed (no session, or the lookup failed), so the list never
  // runs. Saying so beats an empty table that reads as "no leads".
  const sessionUnknown = myRole == null && !me.isPending;
  const filtered = rq.activeCount > 0;
  const clearFilters = () => {
    rq.clearAll();
    setSearchKey((k) => k + 1);
  };

  // Every mutation below invalidates the whole `["leads"]` prefix: the pages and the facets.
  const invalidate = () => qc.invalidateQueries({ queryKey: ["leads"] });

  const rows = cr.pageRows;
  const selected = rows.find((r) => r.id === selectedId) ?? null;
  // `total` is every lead matching the filter, counted by the server — not `rows.length`, which is
  // just this page.
  const total = cr.total;
  const pageCount = cr.pageCount;

  if (myRole && !can("leads:manage")) {
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
          onClick={() => cr.refetch()}
          className="btn btn-sm btn-outline"
          // `refetch` ignores `enabled`, so Refresh waits on the same leads:manage answer as the list.
          disabled={cr.isPlaceholderData || !canManage}
        >
          {cr.isPlaceholderData ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
          Refresh
        </button>
      </PageHeader>

      <NewLeadForm onCreated={invalidate} />

      <div className="mt-6 flex flex-wrap items-end gap-3">
        <SearchBar
          key={searchKey}
          initialValue={q}
          onSearch={rq.setQ}
          placeholder="Name or mobile"
          ariaLabel="Search"
        />
      </div>

      {cr.isError && (
        <ErrorState error={cr.error} onRetry={() => cr.refetch()} className="mt-3 py-4" />
      )}
      {sessionUnknown && (
        <ErrorState
          error={me.error}
          title="Couldn't confirm your staff session, so leads weren't loaded. Sign in again if this persists."
          onRetry={() => void me.refetch()}
          className="mt-3 py-4"
        />
      )}

      <div className="mt-4 grid gap-4 lg:grid-cols-[1fr_320px]">
        {/* `min-w-0`: the grid item used to be the scroller itself, which a grid lets shrink below
            its content; the panel wrapping it now is not a scroll container, so without this the
            `1fr` track would grow to the table's full width instead of scrolling. */}
        <div className="min-w-0 rounded-lg border border-navy/10 bg-white">
          {/* `staff-register-scroll` bounds the scroller so the sticky `thead` has something to
              stick to; PaginationBar sits after it so it no longer scrolls away with the rows.
              Offset clears the shell header, PageHeader, the collapsed New-lead card and the
              search row. */}
          <div className="staff-table-scroll staff-register-scroll" style={{ "--register-offset": "28rem" } as React.CSSProperties}>
          {/* `staff-table-fit`: eight columns beside a 320px panel fit without the wide-register
              84rem floor, which forced a sideways scroll at every common laptop width. */}
          <table className="staff-data-table staff-table-fit">
            <caption className="sr-only">Leads</caption>
            <thead>
              <tr>
                <th scope="col">S.No.</th>
                <Th th={th} col="name" />
                <Th th={th} col="mobile" />
                <Th th={th} col="source" />
                <Th th={th} col="callStatus" />
                <th scope="col">Outcome</th>
                <Th th={th} col="rating" />
                <Th th={th} col="city" />
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 &&
                (cr.isLoading || (myRole == null && me.isPending) ? (
                  <tr>
                    <td colSpan={8}>
                      <Skeleton variant="table" rows={8} cols={8} />
                    </td>
                  </tr>
                ) : cr.isError || sessionUnknown ? null : filtered ? (
                  // "Nothing exists yet" and "your search matched nothing" are different facts; the
                  // first used to show for both, telling a telecaller with a typo there were no leads.
                  <EmptyState
                    title="No leads match your search or filter"
                    action={
                      <button type="button" className="btn btn-sm btn-outline" onClick={clearFilters}>
                        Clear search and filter
                      </button>
                    }
                    inTable={8}
                  />
                ) : (
                  <EmptyState title="No leads yet — add one above." inTable={8} />
                ))}
              {rows.map((row, i) => (
                // `aria-current` drives BOTH the paint and the announcement (not `aria-selected`,
                // which is only valid on a row inside a grid/treegrid — this is a plain table).
                // The previous
                // `bg-gold/10` on the `<tr>` was invisible on every even row, because the zebra is
                // painted on the `td` (globals.css) — so on half the rows the only feedback that a
                // lead was selected was the DispositionPanel changing beside the table. Hover is
                // dropped for the same reason: the shared rule now paints it on the cells.
                // Focusable, and Enter/Space select, so the panel is reachable without a mouse.
                <tr
                  key={row.id}
                  tabIndex={0}
                  onClick={() => setSelectedId(row.id)}
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return;
                    if (e.key === "Enter" || e.key === " ") {
                      // Space would otherwise scroll the register.
                      e.preventDefault();
                      setSelectedId(row.id);
                    }
                  }}
                  aria-current={selectedId === row.id ? true : undefined}
                  // Ring drawn inside the row: the register's scroller would clip an outer one.
                  className="cursor-pointer focus-visible:-outline-offset-2"
                >
                  <td className="text-navy/60">{(page - 1) * pageSize + i + 1}</td>
                  <td className="staff-cell font-medium text-navy">{row.name}</td>
                  <td className="font-mono text-xs">{row.mobile}</td>
                  <td className="staff-cell text-navy/70">
                    {row.source ? row.source.replace(/_/g, " ") : "—"}
                    {row.sourceDetail ? ` · ${row.sourceDetail}` : ""}
                  </td>
                  <td>
                    <StatusBadge kind="leadCall" value={row.callStatus} />
                  </td>
                  <td><OutcomeChip outcome={row.leadOutcome} /></td>
                  <td>{row.qualityRating ? `${row.qualityRating}★` : "—"}</td>
                  <td className="staff-cell text-navy/70">{row.city || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={rq.setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={rq.setSize}
          />
        </div>

        {/* Keyed by lead: a save error or in-flight Save belongs to the lead it was raised for and
            must not carry over to the next one selected. */}
        <DispositionPanel key={selected?.id ?? "none"} lead={selected} onSaved={invalidate} />
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

  // "25,000" and "2,50,000" are accepted; "-500", "abc" and "0" are errors that block Save. They
  // used to be dropped from the request without a word by a `Number(x) > 0` gate.
  const salaryParse = parseLeadRupees(salary);
  const amountParse = parseLeadRupees(amount);
  const moneyValid = salaryParse.kind !== "invalid" && amountParse.kind !== "invalid";

  const canSubmit = name.trim().length > 0 && /^[0-9]{10}$/.test(mobile.trim()) && moneyValid;

  const create = useMutation({
    mutationFn: () => {
      const body: CreateLeadInput = {
        name: name.trim(),
        mobile: mobile.trim(),
      };
      if (email.trim()) body.email = email.trim();
      if (city.trim()) body.city = city.trim();
      if (employer.trim()) body.employer = employer.trim();
      // Still integer paise, as before; a blank box is still left out.
      if (salaryParse.kind === "ok") body.monthlySalaryPaise = salaryParse.paise;
      if (amountParse.kind === "ok") body.loanAmountInterestedPaise = amountParse.paise;
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
      toast.success("Lead saved");
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
          <RupeeInput
            label="Monthly salary (₹)"
            value={salary}
            onChange={setSalary}
            invalid={salaryParse.kind === "invalid"}
          />
          <RupeeInput
            label="Loan amount interested (₹)"
            value={amount}
            onChange={setAmount}
            invalid={amountParse.kind === "invalid"}
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

/**
 * A rupee box whose error appears once the staffer leaves the field, not while they type: the
 * intermediate states of "2,50,000" ("2,", "2,5") are not valid amounts, and the error sits above
 * the input, so showing it per keystroke would make the box jump while typing. Save stays blocked
 * whenever the value is invalid, focused or not.
 */
function RupeeInput({
  label,
  value,
  onChange,
  invalid,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  invalid: boolean;
}) {
  const [editing, setEditing] = React.useState(false);
  return (
    <Input
      label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onFocus={() => setEditing(true)}
      onBlur={() => setEditing(false)}
      error={invalid && !editing ? LEAD_RUPEE_ERROR : undefined}
      inputMode="decimal"
      placeholder="e.g. 25,000"
      className="!mb-0"
    />
  );
}

/** Toasts, once, the failures a lead panel was holding when it went away. */
function raiseFailures(failures: React.RefObject<string[]>) {
  for (const message of failures.current) toast.error(message);
  failures.current = [];
}

function DispositionPanel({
  lead,
  onSaved,
}: {
  lead: LeadView | null;
  onSaved: () => Promise<unknown> | void;
}) {
  const [status, setStatus] = React.useState<LeadCallStatus>("NOT_CALLED");
  const [rating, setRating] = React.useState<number | "">("");
  const [remarks, setRemarks] = React.useState("");
  const [outcome, setOutcome] = React.useState<LeadOutcome>("NEW");
  const [dsaNote, setDsaNote] = React.useState("");
  const [saving, setSaving] = React.useState(false);
  const qualityLabelId = React.useId();
  // The panel is keyed by lead, so it unmounts when another lead is picked — mid-Save, too — and
  // when the post-save refetch drops this lead from a filtered list (the usual case: filter on
  // NOT_CALLED, set a status, Save). The inline errors go with it, so the failures of the last Save
  // are kept here and raised as toasts if the panel goes away while holding them.
  const mounted = React.useRef(true);
  const unsavedFailures = React.useRef<string[]>([]);
  React.useEffect(() => {
    mounted.current = true;
    const failures = unsavedFailures;
    return () => {
      mounted.current = false;
      raiseFailures(failures);
    };
  }, []);

  // Re-synced from the server per field group, by value rather than by object: every list refetch
  // hands over a new `lead` object, and syncing on that would wipe an unsaved outcome/note the
  // moment a partial Save's invalidation landed — exactly when its error says it was NOT saved.
  const callStatusSaved = lead?.callStatus;
  const ratingSaved = lead?.qualityRating;
  const remarksSaved = lead?.remarks;
  React.useEffect(() => {
    if (callStatusSaved === undefined) return;
    setStatus(callStatusSaved);
    setRating(ratingSaved ?? "");
    setRemarks(remarksSaved ?? "");
  }, [callStatusSaved, ratingSaved, remarksSaved]);

  const outcomeSaved = lead?.leadOutcome;
  const dsaNoteSaved = lead?.dsaNote;
  React.useEffect(() => {
    if (outcomeSaved === undefined) return;
    // CONFIRMED is derived, never settable — fall back to the nearest settable value in the picker.
    setOutcome(settableOutcome(outcomeSaved));
    setDsaNote(dsaNoteSaved ?? "");
  }, [outcomeSaved, dsaNoteSaved]);

  const save = useMutation({
    mutationFn: (v: { id: number; body: DispositionInput }) => leadsApi.disposition(v.id, v.body),
  });

  // A SEPARATE request from the disposition on purpose: `disposition` is replace-semantics and would
  // null these two on every call, so they keep their own (PATCH-semantics) endpoint.
  const saveOutcome = useMutation({
    mutationFn: (v: { id: number; body: LeadOutcomePatch }) => leadsApi.setOutcome(v.id, v.body),
  });

  /**
   * The one Save. The disposition PUT always goes; the outcome PUT goes only when the outcome or the
   * DSA note changed, carrying just the changed field(s). Sequential, not parallel: both endpoints
   * load, modify and save the same lead row, which has no version column, so two in flight at once
   * could each write back the other's columns stale. Each request keeps its own error state (shown
   * below, or toasted if the panel goes away first); one success toast names what the server
   * confirmed.
   */
  const onSave = async () => {
    if (!lead) return;
    const { id, name } = lead;
    const patch = leadOutcomePatch(lead, outcome, dsaNote);
    setSaving(true);
    save.reset();
    saveOutcome.reset();
    unsavedFailures.current = [];
    let dispositionError: unknown = null;
    const dispositionOk = await save
      .mutateAsync({
        id,
        body: {
          callStatus: status,
          qualityRating: rating === "" ? null : Number(rating),
          remarks: remarks.trim() || undefined,
        },
      })
      .then(
        () => true,
        (e: unknown) => {
          dispositionError = e;
          return false;
        },
      );
    let outcomeError: unknown = null;
    const outcomeOk = patch
      ? await saveOutcome.mutateAsync({ id, body: patch }).then(
          () => true,
          (e: unknown) => {
            outcomeError = e;
            return false;
          },
        )
      : false;
    const message = leadSaveToast(dispositionOk, outcomeOk ? patch : null);
    if (message) toast.success(message);
    const failures: string[] = [];
    if (!dispositionOk) {
      failures.push(`${name}: call status, rating & remarks not saved — ${errMessage(dispositionError)}`);
    }
    if (patch && !outcomeOk) {
      failures.push(`${name}: outcome & note not saved — ${errMessage(outcomeError)}`);
    }
    unsavedFailures.current = failures;
    // Already gone (another lead was picked mid-Save): the unmount found nothing to raise then.
    if (!mounted.current) raiseFailures(unsavedFailures);
    try {
      // Awaited, as before, so Save stays busy until the list has been invalidated.
      if (dispositionOk || outcomeOk) await onSaved();
    } finally {
      setSaving(false);
    }
  };

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
        <span id={qualityLabelId} className="mb-1 block text-sm font-semibold text-ink">
          Quality
        </span>
        <div role="group" aria-labelledby={qualityLabelId} className="mt-1 flex items-center gap-1">
          {[1, 2, 3, 4, 5].map((n) => (
            // Clicking the chosen star again keeps it; it used to clear the rating silently.
            // Clearing is the explicit button after the stars.
            <button
              key={n}
              type="button"
              className={`rounded p-1 ${rating === n ? "text-gold" : "text-navy/25 hover:text-gold/70"}`}
              onClick={() => setRating(n)}
              aria-label={`${n} star${n === 1 ? "" : "s"}`}
              aria-pressed={rating === n}
            >
              <Star size={20} fill={rating !== "" && n <= Number(rating) ? "currentColor" : "none"} />
            </button>
          ))}
          <button
            type="button"
            className="ml-2 text-xs text-muted underline hover:text-ink disabled:no-underline disabled:opacity-50"
            onClick={() => setRating("")}
            disabled={rating === ""}
          >
            Clear rating
          </button>
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

      <div className="mt-4 border-t border-line pt-3">
        <div className="mb-2 flex items-center justify-between gap-2">
          <span className="text-sm font-semibold text-ink">Outcome</span>
          {lead.leadOutcome === "CONFIRMED" && <OutcomeChip outcome="CONFIRMED" />}
        </div>
        {lead.leadOutcome === "CONFIRMED" ? (
          <p className="mb-2 text-xs text-navy/60">
            This lead has an application against it, so it shows as Confirmed wherever it appears.
            That is derived and overrides whatever is set below.
          </p>
        ) : null}
        <Select
          label="Set outcome"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value as LeadOutcome)}
        >
          {SETTABLE_LEAD_OUTCOMES.map((o) => (
            <option key={o} value={o}>
              {OUTCOME_LABEL[o]}
            </option>
          ))}
        </Select>

        <div className="field mt-3">
          <label htmlFor="lead-dsa-note">Note for the DSA</label>
          <textarea
            id="lead-dsa-note"
            value={dsaNote}
            onChange={(e) => setDsaNote(e.target.value)}
            placeholder="What should the agent who sent us this lead know?"
          />
          {/* Staff must know who reads this before they write in it — it is the whole reason this is
              a separate field from Remarks rather than a reuse of it. */}
          <p className="mt-1 text-[8.8px] text-navy/60">
            Visible to the DSA who uploaded this lead. Remarks above stay internal.
          </p>
        </div>

      </div>

      <button
        type="button"
        className="btn btn-navy btn-block mt-3 disabled:opacity-50"
        disabled={saving}
        onClick={() => void onSave()}
      >
        {saving ? <Loader2 size={14} className="animate-spin" /> : null}
        Save
      </button>
      {save.isError && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          Call status, rating &amp; remarks not saved: {errMessage(save.error)}
        </p>
      )}
      {saveOutcome.isError && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          Outcome &amp; note not saved: {errMessage(saveOutcome.error)}
        </p>
      )}

      {lead.notes && (
        <p className="mt-3 text-xs text-navy/50">
          <span className="font-semibold">Intake notes:</span> {lead.notes}
        </p>
      )}
    </div>
  );
}
