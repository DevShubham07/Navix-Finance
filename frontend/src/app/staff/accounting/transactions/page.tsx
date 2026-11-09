"use client";

import * as React from "react";
import Link from "next/link";
import { Loader2, RefreshCw, ArrowDownLeft, ArrowUpRight, ArrowLeft } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe, useCan } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { staffApi, paiseToINR, loanGraceDays, type TransactionDirection, type TransactionView } from "@/lib/api/applications";
import { type FilterColumn } from "@/components/staff/column-filter";
import { Th, thBag, describeRegister } from "@/lib/staff/register-th";
import { useRegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister } from "@/lib/staff/use-server-register";
import { retryUnlessClientError } from "@/lib/staff/admin-all-applications";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { EmptyState, ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { formatDate } from "@/lib/utils";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { ledgerRowsCaption } from "@/lib/staff/transactions-ledger";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";

const TABS: { key: "ALL" | TransactionDirection; label: string }[] = [
  { key: "ALL", label: "All" },
  { key: "INCOMING", label: "Incoming" },
  { key: "OUTGOING", label: "Outgoing" },
];

type Period = "DAY" | "WEEK" | "MONTH" | "QUARTER" | "YEAR" | "ALL";

const PERIODS: { key: Period; label: string }[] = [
  { key: "DAY", label: "Today" },
  { key: "WEEK", label: "Last 7 days" },
  { key: "MONTH", label: "This month" },
  { key: "QUARTER", label: "This quarter" },
  { key: "YEAR", label: "This year" },
  { key: "ALL", label: "All time" },
];

/**
 * What each header offers — the server's `TransactionLedgerQuery.FILTER_COLUMNS` / `SORTS`. `amount`
 * is in RUPEES (the server scales the bound to paise). `status` is a TEXT filter on purpose: the
 * column holds a loan status on a disbursal row and a payment status on a repayment row, and one
 * checklist mixing two enums would be meaningless. Borrower / reference / loan are identity columns,
 * text-only, so there is never a dropdown of names or txn refs; only `type` (two values) is faceted.
 * Proof and S.No. are plain headers: nothing to sort or filter them by server-side.
 */
const FILTER_COLUMNS: Array<FilterColumn<TransactionView>> = [
  { key: "date", kind: "date", value: (t) => (t.date ? formatDate(t.date) : "—"), date: (t) => t.date },
  { key: "repaymentDate", kind: "date", value: (t) => (t.repaymentDate ? formatDate(t.repaymentDate) : "—"), date: (t) => t.repaymentDate },
  { key: "borrower", kind: "text", value: (t) => t.borrowerName ?? "" },
  { key: "type", facet: true, value: (t) => (t.type === "REPAYMENT" ? "Repayment" : "Disbursal"), raw: (t) => t.type },
  { key: "amount", kind: "num", value: (t) => paiseToINR(t.amountPaise), num: (t) => t.amountPaise / 100 },
  { key: "reference", kind: "text", value: (t) => t.txnRef ?? "" },
  { key: "status", kind: "text", value: (t) => t.status ?? "" },
  { key: "loan", kind: "text", value: (t) => (t.loanId != null ? String(t.loanId) : "") },
];
const KEYS = FILTER_COLUMNS.map((c) => c.key);
const COLUMN_LABEL: Record<string, string> = {
  date: "Date",
  repaymentDate: "Repayment date",
  borrower: "Borrower",
  type: "Type",
  amount: "Amount",
  reference: "Reference",
  status: "Status",
  loan: "Loan",
};
/** The server's raw `type` values → the words the Type column shows. */
const FACET_LABELS: Record<string, Record<string, string>> = {
  type: { DISBURSAL: "Disbursal", REPAYMENT: "Repayment" },
};
/** The server caps a page at 100 (`TransactionService.MAX_PAGE_SIZE`); "Download all" walks it. */
const EXPORT_CHUNK = 100;
/** Local-time ISO yyyy-mm-dd for a Date (timezone-safe — never UTC-shifts at day boundaries). */
function toLocalISO(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * The inclusive {@code [from, to]} ISO date window for a period (current period to today, in local
 * time), or null for "all time". Returning ISO strings lets us filter by string comparison against the
 * backend's {@code yyyy-mm-dd} dates — no {@code new Date("yyyy-mm-dd")} UTC-midnight off-by-one.
 */
function periodRange(period: Period): { from: string; to: string } | null {
  const now = new Date();
  const to = toLocalISO(now);
  switch (period) {
    case "DAY":
      return { from: to, to };
    case "WEEK": {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6); // rolling 7 days incl. today
      return { from: toLocalISO(d), to };
    }
    case "MONTH":
      return { from: toLocalISO(new Date(now.getFullYear(), now.getMonth(), 1)), to };
    case "QUARTER":
      return { from: toLocalISO(new Date(now.getFullYear(), Math.floor(now.getMonth() / 3) * 3, 1)), to };
    case "YEAR":
      return { from: toLocalISO(new Date(now.getFullYear(), 0, 1)), to };
    case "ALL":
    default:
      return null;
  }
}

/** Human "30 Jun 2026" from a local ISO yyyy-mm-dd (parsed by parts to avoid UTC drift). */
function humanISO(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

/**
 * Accountant transactions ledger: every money movement company-wide — OUTGOING disbursals and
 * INCOMING repayments — with a borrower search and direction tabs. Read-only oversight.
 */
export default function TransactionsPage() {
  const [openLoanId, setOpenLoanId] = React.useState<number | null>(null);
  const me = useStaffMe().data;
  const role = me?.role;
  const can = useCan();
  const allowed = role ? can("loan:activate") || can("staff:manage") : false;
  const [tab, setTab] = React.useState<"ALL" | TransactionDirection>("ALL");
  const [period, setPeriod] = React.useState<Period>("MONTH");

  const direction = tab === "ALL" ? undefined : tab;
  const range = React.useMemo(() => periodRange(period), [period]);
  // The ledger's own server params (not column filters), carried beside `rq.queryParams`.
  const extra = React.useMemo(() => {
    const e: Record<string, string> = {};
    if (direction) e.direction = direction;
    if (range) {
      e.from = range.from;
      e.to = range.to;
    }
    return e;
  }, [direction, range]);

  // One owner for search, column filters, sort, page and size; this is the page's only register, so
  // it owns the URL. A change to any of them returns to page 1 in the same update.
  const rq = useRegisterQuery({
    registerId: "transactions",
    staffId: me?.id,
    sortKeys: KEYS,
    filterKeys: KEYS,
    defaults: { sort: { key: "date", dir: "desc" } },
  });
  const query = rq.state.q;
  const page = rq.state.page;
  const pageSize = rq.state.size;

  // Direction and period are server params of their own, so changing one has to go back to page 1
  // too: `rq.apply({})` is that reset, in the same update as the state change — never an effect after
  // it, which let one request go out for the new filter at the old page.
  const choosePeriod = (next: Period) => {
    rq.apply({});
    setPeriod(next);
  };
  const chooseTab = (next: "ALL" | TransactionDirection) => {
    rq.apply({});
    setTab(next);
  };

  const cr = useServerRegister<TransactionView, Awaited<ReturnType<typeof staffApi.transactionsPage>>>(rq, {
    // Direction and period are not in `rq.queryParams`, so they are in the key prefix — without them a
    // tab change would be answered by the previous tab's cached page.
    key: ["staff-transactions", extra],
    fetchPage: (params, pg, size) => staffApi.transactionsPage({ filters: params, page: pg, size, extra }),
    fetchFacet: (column, params) => staffApi.transactionsFacet(column, { ...extra, ...params }),
    labels: FACET_LABELS,
    // A minute, not ten seconds. The ledger is a read-only oversight view of movements that have
    // already settled; the one thing that adds a row from inside the console — an accountant
    // verifying a repayment — invalidates `["staff-transactions"]` directly, so this poll only has
    // to catch disbursals made elsewhere.
    refetchInterval: 60_000,
    // Nothing is asked before the session says who is signed in, or for a role the gate turns away.
    enabled: allowed,
    retry: retryUnlessClientError,
  });
  const th = thBag(rq, cr, FILTER_COLUMNS, COLUMN_LABEL, cr.requestFacet);

  const rows = cr.pageRows;
  const periodLabel = PERIODS.find((p) => p.key === period)?.label ?? "All time";
  // From the server, not a reduce over `rows`: `rows` is one page, and these three cards claim to
  // describe the PERIOD. Summing the page would have made "Inflow · This month" mean "inflow in the
  // 25 movements you happen to be looking at".
  // Undefined until the first page lands: the cards then read "—", never a ₹0 that is not a measurement.
  const totalIn = cr.page?.totalInPaise;
  const totalOut = cr.page?.totalOutPaise;
  const pendingIn = cr.page?.pendingInPaise ?? 0;
  const net = totalIn != null && totalOut != null ? totalIn - totalOut : null;
  const total = cr.total;
  const pageCount = cr.pageCount;
  // A new page or filter is loading behind `keepPreviousData`: the old rows stay on screen, so say
  // they are about to change rather than leave them looking current.
  const refreshing = cr.isPlaceholderData;
  const filtered = query !== "" || rq.activeCount > 0;

  // "Download all": `rows` is one page, so the menu walks the server under the SAME view — the
  // table's own params plus direction/period — a page (the server's ceiling) at a time, stopping at
  // `total` or on a short page, which is both the last page and the guard against looping forever
  // if the count and the rows ever disagree.
  const getAllRows = async () => {
    const out: TransactionView[] = [];
    for (let p = 1; ; p += 1) {
      const chunk = await staffApi.transactionsPage({ filters: rq.queryParams, page: p, size: EXPORT_CHUNK, extra });
      out.push(...chunk.rows);
      if (chunk.rows.length < EXPORT_CHUNK || out.length >= chunk.total) break;
    }
    return out;
  };

  const exportSubtitle = [
    tab === "ALL" ? "All movements" : tab === "INCOMING" ? "Incoming" : "Outgoing",
    query ? `Search: “${query}”` : "",
    describeRegister(th),
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div>
      <PageHeader title="Transactions ledger" subtitle="All money movement — disbursals out and repayments in, company-wide.">
        <ExportMenu
          title="Transactions ledger"
          subtitle={exportSubtitle}
          fileBase="dhanboost-transactions"
          columns={[
            { header: "Date", value: (t: TransactionView) => (t.date ? formatDate(t.date) : "") },
            { header: "Repayment date", value: (t) => (t.repaymentDate ? formatDate(t.repaymentDate) : "") },
            { header: "Borrower", value: (t) => t.borrowerName ?? "" },
            { header: "PAN", value: (t) => t.pan ?? "" },
            { header: "Type", value: (t) => (t.type === "REPAYMENT" ? "Repayment" : "Disbursal") },
            { header: "Direction", value: (t) => t.direction },
            { header: "Amount (₹)", value: (t) => (t.amountPaise / 100).toFixed(2) },
            { header: "Reference", value: (t) => t.txnRef ?? "" },
            { header: "Proof attached", value: (t) => (t.proofUrl ? "Yes" : "No") },
            { header: "Status", value: (t) => t.status ?? "" },
            { header: "Loan", value: (t) => (t.loanId != null ? `#${t.loanId}` : "") },
          ]}
          rows={rows}
          // `rows` is one page, so the menu says "this page"; the filtered whole is `getAllRows`.
          scope="page"
          getAllRows={getAllRows}
          allLabel="Download all transactions (CSV)"
          meta={{
            periodLabel,
            from: range ? humanISO(range.from) : undefined,
            to: range ? humanISO(range.to) : undefined,
            timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          }}
        />
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
      </PageHeader>

      <PermissionGate permission={["loan:activate", "staff:manage"]} fallback={<NoAccessNotice />}>
        <div className="mb-4">
          <Link href="/staff/applications" className="inline-flex items-center gap-1 text-sm text-navy hover:underline">
            <ArrowLeft size={14} /> Back to live applications
          </Link>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold uppercase tracking-wide text-muted">Period</span>
          {/* The console's period control: square navy pills on a grey track (QueueDateFilter /
              PeriodPicker), so the one toolbar no longer carries two active colours. */}
          <div className="flex flex-wrap gap-1 rounded border border-line bg-grey-50 p-1">
            {PERIODS.map((p) => (
              <button
                key={p.key}
                type="button"
                aria-pressed={period === p.key}
                onClick={() => choosePeriod(p.key)}
                className={`rounded px-2.5 py-1 text-xs font-semibold transition-colors ${
                  period === p.key ? "bg-navy text-white" : "text-muted hover:text-ink"
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-1 rounded-full border border-line bg-white p-1">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                // The selected direction is otherwise told by colour alone.
                aria-pressed={tab === t.key}
                onClick={() => chooseTab(t.key)}
                className={`rounded-full px-3 py-1 text-sm font-semibold transition ${tab === t.key ? "bg-navy text-white" : "text-muted hover:text-navy"}`}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <SearchBar
              initialValue={query}
              onSearch={rq.setQ}
              placeholder="Search borrower / mobile / loan #"
              ariaLabel="Search transactions by borrower"
              inputClassName="w-64"
            />
            <button
              onClick={() => cr.refetch()}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
            >
              {refreshing ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
            </button>
          </div>
        </div>

        <div className="mb-2 grid grid-cols-1 gap-4 sm:grid-cols-3 sm:max-w-2xl">
          <div className="rounded border border-success-100 bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowDownLeft size={14} className="text-success-600" /> Inflow · {periodLabel}</div>
            <div className="mt-1 font-serif text-xl font-bold text-success-700">{totalIn != null ? paiseToINR(totalIn) : "—"}</div>
            {/* Verified money only; rejected repayments are not listed at all. */}
            <div className="mt-0.5 text-xs text-muted">
              {pendingIn > 0 ? `Verified · ${paiseToINR(pendingIn)} awaiting verification` : "Verified repayments"}
            </div>
          </div>
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowUpRight size={14} className="text-navy" /> Outflow · {periodLabel}</div>
            <div className="mt-1 font-serif text-xl font-bold text-navy">{totalOut != null ? paiseToINR(totalOut) : "—"}</div>
          </div>
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted">Net · {periodLabel}</div>
            <div className={`mt-1 font-serif text-xl font-bold ${net == null || net >= 0 ? "text-success-700" : "text-error-700"}`}>
              {net == null ? "—" : `${net < 0 ? "−" : "+"}${paiseToINR(Math.abs(net))}`}
            </div>
          </div>
        </div>
        {/* The cards total the whole period; the table below is one page of it. Only once the
            server has answered for THIS filter — a count of 0 before then, or the previous
            filter's count under the new period's label while `keepPreviousData` holds it on screen,
            would be a claim, not a measurement. */}
        <p className="mb-4 min-h-[1rem] text-xs text-muted">
          {allowed && !cr.isLoading && !cr.isError && !cr.isPlaceholderData ? ledgerRowsCaption(periodLabel, total) : null}
        </p>

        <div className="rounded border border-line bg-white shadow-sm">
          {cr.isLoading ? (
            <Skeleton variant="table" rows={8} cols={9} />
          ) : cr.isError ? (
            <ErrorState error={cr.error} onRetry={() => cr.refetch()} />
          ) : rows.length === 0 ? (
            <EmptyState
              title={query ? `No transactions for “${query}”.` : filtered ? "No transactions match these filters." : "No transactions."}
              hint={query ? "Try a different name, PAN or reference, or widen the period." : filtered ? "Clear a column filter, or widen the period." : undefined}
            />
          ) : (
            // `staff-register-scroll` bounds the wrapper so the sticky `thead` has something to
            // stick to (see globals.css). Safe on this page specifically because `PaginationBar`
            // is a sibling *after* this div — on customers/loans/collections/my-decisions/leads/
            // settlements it sits inside the scroller and would scroll away with the rows, so
            // those need the bar lifted out before they can adopt this class.
            // The offset clears the shell header, PageHeader, the period/direction toolbar, the
            // search row, the three stat cards and the period · rows caption under them.
            <div
              className={`staff-table-scroll staff-register-scroll transition-opacity ${refreshing ? "opacity-60" : ""}`}
              style={{ "--register-offset": "27.5rem" } as React.CSSProperties}
            >
              <table className="staff-data-table" aria-busy={refreshing}>
                <caption className="sr-only">Transactions ledger</caption>
                <thead>
                  <tr>
                    <th scope="col">S.No.</th>
                    <Th th={th} col="date" />
                    <Th th={th} col="repaymentDate" />
                    <Th th={th} col="borrower" />
                    <Th th={th} col="type" />
                    <Th th={th} col="amount" className="num" />
                    <Th th={th} col="reference" />
                    <th scope="col">Proof</th>
                    <Th th={th} col="status" />
                    <Th th={th} col="loan" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <TxnRow key={r.id} t={r} sno={(page - 1) * pageSize + i + 1} onOpen={setOpenLoanId} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {total > 0 && (
            <PaginationBar
              page={page}
              pageCount={pageCount}
              setPage={rq.setPage}
              total={total}
              pageSize={pageSize}
              setPageSize={rq.setSize}
            />
          )}
        </div>
      </PermissionGate>

      {/* The customer pop-up, on the application that carries this loan, opened on its Repayment tab. */}
      <ApplicationDetailDialog applicationId={null} loanId={openLoanId} initialTab="repayment" onClose={() => setOpenLoanId(null)} />
    </div>
  );
}

function TxnRow({ t, sno, onOpen }: { t: TransactionView; sno: number; onOpen: (loanId: number) => void }) {
  const incoming = t.direction === "INCOMING";
  // A repayment that came in after the loan's repayment date (and its grace day, on loans that have one) reads red.
  const paidLate =
    t.type === "REPAYMENT" && t.date != null && t.repaymentDate != null &&
    new Date(`${t.date}T00:00:00`).getTime() > new Date(`${t.repaymentDate}T00:00:00`).getTime() + loanGraceDays(t) * 86_400_000;
  return (
    <tr>
      <td className="text-muted">{sno}</td>
      <td className="text-muted">{t.date ? formatDate(t.date) : "—"}</td>
      <td className={paidLate ? "font-semibold text-error-700" : "text-muted"} title={paidLate ? "Paid after the repayment date" : undefined}>
        {t.repaymentDate ? formatDate(t.repaymentDate) : "—"}
      </td>
      <td className="staff-cell" title={t.borrowerName ?? undefined}>
        {t.loanId != null ? (
          <button
            type="button"
            onClick={() => onOpen(t.loanId as number)}
            className="font-medium text-navy hover:underline"
            aria-label={`Open ${t.borrowerName ?? `loan #${t.loanId}`}`}
          >
            {t.borrowerName ?? "—"}
          </button>
        ) : (
          <span className="font-medium text-ink">{t.borrowerName ?? "—"}</span>
        )}
        {t.pan && <span className="ml-1 font-mono text-xs text-muted">{t.pan}</span>}
      </td>
      <td>
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${incoming ? "bg-success-50 text-success-700" : "bg-navy-tint text-navy"}`}>
          {incoming ? <ArrowDownLeft size={12} /> : <ArrowUpRight size={12} />}
          {t.type === "REPAYMENT" ? "Repayment" : "Disbursal"}
        </span>
      </td>
      {/* `num` right-aligns tabular figures (globals.css). The signed +/− stays, so direction is
          readable without relying on the colour alone. */}
      <td className={`num font-mono font-semibold ${incoming ? "text-success-700" : "text-ink"}`}>
        {incoming ? "+" : "−"}{paiseToINR(t.amountPaise)}
      </td>
      <td className="staff-cell text-muted" title={t.txnRef || undefined}>{t.txnRef || "—"}</td>
      {/* A disbursal row can never carry a payment proof, so printing "—" there made the column
          read as "missing" on ~half the ledger. Blank for disbursals; "—" only when a repayment
          genuinely has no proof attached. */}
      <td>
        {t.type === "REPAYMENT" ? (
          <>
            <PaymentProofLink url={t.proofUrl} className="text-xs" />
            {!t.proofUrl && <span className="text-xs text-muted">—</span>}
          </>
        ) : null}
      </td>
      {/* The two row kinds carry different enums under one header — a disbursal row's status is a
          LoanStatus, a repayment row's is a PaymentStatus. Keying StatusBadge on `t.type` keeps
          them visually distinct instead of printing raw enum names side by side. */}
      <td>
        <StatusBadge kind={t.type === "REPAYMENT" ? "payment" : "application"} value={t.status} />
      </td>
      <td className="text-muted">{t.loanId != null ? `#${t.loanId}` : "—"}</td>
    </tr>
  );
}
