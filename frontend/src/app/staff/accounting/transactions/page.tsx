"use client";

import * as React from "react";
import Link from "next/link";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, ArrowDownLeft, ArrowUpRight, ArrowLeft } from "lucide-react";
import { PageHeader } from "@/components/staff/staff-ui";
import { SearchBar } from "@/components/staff/search-bar";
import { PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { staffApi, paiseToINR, type TransactionDirection, type TransactionView } from "@/lib/api/applications";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { EmptyState, ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { formatDate } from "@/lib/utils";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { ledgerRowsCaption } from "@/lib/staff/transactions-ledger";

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
  const role = useStaffMe().data?.role;
  const [tab, setTab] = React.useState<"ALL" | TransactionDirection>("ALL");
  const [period, setPeriod] = React.useState<Period>("MONTH");
  const [query, setQuery] = React.useState("");
  // Server paging: the ledger is every money movement the company has ever made, so it was never a
  // list to fetch whole and slice in the browser.
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(25);

  const direction = tab === "ALL" ? undefined : tab;
  const range = React.useMemo(() => periodRange(period), [period]);

  // Any change to the filter puts you back on page 1 — an offset into the old result set means
  // nothing in the new one. The reset happens in the same handler as the filter change, not in an
  // effect after it: an effect let one render commit with the NEW filter and the OLD page, which
  // fired a request for that stale page before the reset fired the page-1 request.
  const choosePeriod = (next: Period) => {
    setPeriod(next);
    setPage(1);
  };
  const chooseTab = (next: "ALL" | TransactionDirection) => {
    setTab(next);
    setPage(1);
  };

  const q = useQuery({
    // Period filtering is now server-side (timezone-free), so the query keys on the range too.
    queryKey: ["staff-transactions", query, direction, range?.from ?? "", range?.to ?? "", page, pageSize],
    queryFn: () =>
      staffApi.transactions(
        query || undefined,
        direction,
        range ? { from: range.from, to: range.to } : undefined,
        { page, size: pageSize },
      ),
    // A minute, not ten seconds. The ledger is a read-only oversight view of movements that have
    // already settled; the one thing that adds a row from inside the console — an accountant
    // verifying a repayment — now invalidates `["staff-transactions"]` directly, so this poll only
    // has to catch disbursals made elsewhere.
    refetchInterval: 60_000,
    // Keep the table and the totals on screen while the next page loads.
    placeholderData: keepPreviousData,
  });

  const rows = q.data?.rows ?? [];
  const periodLabel = PERIODS.find((p) => p.key === period)?.label ?? "All time";
  // From the server, not a reduce over `rows`: `rows` is one page now, and these three cards claim
  // to describe the PERIOD. Summing the page would have made "Inflow · This month" mean "inflow in
  // the 25 movements you happen to be looking at".
  const totalIn = q.data?.totalInPaise ?? 0;
  const totalOut = q.data?.totalOutPaise ?? 0;
  const net = totalIn - totalOut;
  const total = q.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  // A new page or filter is loading behind `keepPreviousData`: the old rows stay on screen, so say
  // they are about to change rather than leave them looking current.
  const refreshing = q.isFetching && !q.isLoading;

  return (
    <div>
      <PageHeader title="Transactions ledger" subtitle="All money movement — disbursals out and repayments in, company-wide.">
        <ExportMenu
          title="Transactions ledger"
          subtitle={tab === "ALL" ? "All movements" : tab === "INCOMING" ? "Incoming" : "Outgoing"}
          fileBase="dhanboost-transactions"
          columns={[
            { header: "Date", value: (t: TransactionView) => (t.date ? formatDate(t.date) : "") },
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
          getAllRows={async () => {
            // `rows` is one page, so "Download all" has to walk the server under the SAME filter.
            // 100 a call (the ledger's page ceiling), stopping once we hold `total` rows — or the
            // moment a page comes back short, which is both the last page and the guard against
            // looping forever if the count and the rows ever disagree.
            const out: TransactionView[] = [];
            const dateWindow = range ? { from: range.from, to: range.to } : undefined;
            for (let p = 1; ; p += 1) {
              const chunk = await staffApi.transactions(query || undefined, direction, dateWindow, {
                page: p,
                size: 100,
              });
              out.push(...chunk.rows);
              if (chunk.rows.length < 100 || out.length >= chunk.total) break;
            }
            return out;
          }}
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

      <PermissionGate permission="loan:activate" fallback={<NoAccessNotice />}>
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
              onSearch={(t) => {
                setQuery(t);
                setPage(1);
              }}
              placeholder="Search borrower / mobile / loan #"
              ariaLabel="Search transactions by borrower"
              inputClassName="w-64"
            />
            <button
              onClick={() => q.refetch()}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
            >
              {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
            </button>
          </div>
        </div>

        <div className="mb-2 grid grid-cols-1 gap-4 sm:grid-cols-3 sm:max-w-2xl">
          <div className="rounded border border-success-100 bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowDownLeft size={14} className="text-success-600" /> Inflow · {periodLabel}</div>
            <div className="mt-1 font-serif text-xl font-bold text-success-700">{paiseToINR(totalIn)}</div>
          </div>
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted"><ArrowUpRight size={14} className="text-navy" /> Outflow · {periodLabel}</div>
            <div className="mt-1 font-serif text-xl font-bold text-navy">{paiseToINR(totalOut)}</div>
          </div>
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <div className="flex items-center gap-1.5 text-xs text-muted">Net · {periodLabel}</div>
            <div className={`mt-1 font-serif text-xl font-bold ${net >= 0 ? "text-success-700" : "text-error-700"}`}>
              {net < 0 ? "−" : "+"}{paiseToINR(Math.abs(net))}
            </div>
          </div>
        </div>
        {/* The cards total the whole period; the table below is one page of it. Only once the
            server has answered for THIS filter — a count of 0 before then, or the previous
            filter's count under the new period's label while `keepPreviousData` holds it on screen,
            would be a claim, not a measurement. */}
        <p className="mb-4 min-h-[1rem] text-xs text-muted">
          {q.data && !q.isPlaceholderData ? ledgerRowsCaption(periodLabel, total) : null}
        </p>

        <div className="rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <Skeleton variant="table" rows={8} cols={9} />
          ) : q.error ? (
            <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          ) : rows.length === 0 ? (
            <EmptyState
              title={query ? `No transactions for “${query}”.` : "No transactions."}
              hint={query ? "Try a different name, PAN or reference, or widen the period." : undefined}
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
                    <th scope="col">Date</th>
                    <th scope="col">Borrower</th>
                    <th scope="col">Type</th>
                    <th scope="col" className="num">Amount</th>
                    <th scope="col">Reference</th>
                    <th scope="col">Proof</th>
                    <th scope="col">Status</th>
                    <th scope="col">Loan</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <TxnRow key={r.id} t={r} sno={(page - 1) * pageSize + i + 1} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {total > 0 && (
            <PaginationBar
              page={page}
              pageCount={pageCount}
              setPage={setPage}
              total={total}
              pageSize={pageSize}
              setPageSize={(size) => {
                setPageSize(size);
                setPage(1);
              }}
            />
          )}
        </div>
      </PermissionGate>
    </div>
  );
}

function TxnRow({ t, sno }: { t: TransactionView; sno: number }) {
  const incoming = t.direction === "INCOMING";
  return (
    <tr>
      <td className="text-muted">{sno}</td>
      <td className="text-muted">{t.date ? formatDate(t.date) : "—"}</td>
      <td className="staff-cell" title={t.borrowerName ?? undefined}>
        <span className="font-medium text-ink">{t.borrowerName ?? "—"}</span>
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
