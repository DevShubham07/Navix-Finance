"use client";

import * as React from "react";
import Link from "next/link";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Loader2, RefreshCw, Search, ArrowDownLeft, ArrowUpRight, ArrowLeft } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, ROLE_LABEL, useStaffMe, errMessage } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { staffApi, paiseToINR, type TransactionDirection, type TransactionView } from "@/lib/api/applications";
import { PaymentProofLink } from "@/components/ui/payment-proof-link";
import { formatDate } from "@/lib/utils";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import { useDebouncedValue } from "@/hooks/use-debounced-value";

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
  const [search, setSearch] = React.useState("");
  const debounced = useDebouncedValue(search.trim());
  // Server paging: the ledger is every money movement the company has ever made, so it was never a
  // list to fetch whole and slice in the browser.
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(25);

  const direction = tab === "ALL" ? undefined : tab;
  const range = React.useMemo(() => periodRange(period), [period]);

  // Any change to the filter puts you back on page 1 — an offset into the old result set means
  // nothing in the new one.
  React.useEffect(() => {
    setPage(1);
  }, [debounced, direction, period]);

  const q = useQuery({
    // Period filtering is now server-side (timezone-free), so the query keys on the range too.
    queryKey: ["staff-transactions", debounced, direction, range?.from ?? "", range?.to ?? "", page, pageSize],
    queryFn: () =>
      staffApi.transactions(
        debounced || undefined,
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
              const chunk = await staffApi.transactions(debounced || undefined, direction, dateWindow, {
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
          <div className="flex flex-wrap items-center gap-1 rounded-full border border-line bg-white p-1">
            {PERIODS.map((p) => (
              <button
                key={p.key}
                onClick={() => setPeriod(p.key)}
                className={`rounded-full px-3 py-1 text-sm font-semibold transition ${period === p.key ? "bg-gold text-white" : "text-muted hover:text-navy"}`}
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
                onClick={() => setTab(t.key)}
                className={`rounded-full px-3 py-1 text-sm font-semibold transition ${tab === t.key ? "bg-navy text-white" : "text-muted hover:text-navy"}`}
              >
                {t.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Input
              aria-label="Search transactions by borrower"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search borrower / mobile / loan #"
              leftIcon={<Search size={15} />}
              className="!mb-0"
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

        <div className="mb-4 grid grid-cols-1 gap-4 sm:grid-cols-3 sm:max-w-2xl">
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

        <div className="rounded border border-line bg-white shadow-sm">
          {q.isLoading ? (
            <div className="h-40 animate-pulse rounded bg-grey-100" />
          ) : q.error ? (
            <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
          ) : rows.length === 0 ? (
            <p className="px-5 py-8 text-center text-sm text-muted">
              No transactions{debounced ? ` for “${debounced}”` : ""}.
            </p>
          ) : (
            <div className="staff-table-scroll">
              <table className="staff-data-table">
                <thead>
                  <tr>
                    <th>S.No.</th>
                    <th>Date</th>
                    <th>Borrower</th>
                    <th>Type</th>
                    <th>Amount</th>
                    <th>Reference</th>
                    <th>Proof</th>
                    <th>Status</th>
                    <th>Loan</th>
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
      <td className={`font-semibold ${incoming ? "text-success-700" : "text-ink"}`}>
        {incoming ? "+" : "−"}{paiseToINR(t.amountPaise)}
      </td>
      <td className="staff-cell text-muted" title={t.txnRef || undefined}>{t.txnRef || "—"}</td>
      <td>
        <PaymentProofLink url={t.proofUrl} className="text-xs" />
        {!t.proofUrl && <span className="text-xs text-muted">—</span>}
      </td>
      <td className="text-muted">{t.status ?? "—"}</td>
      <td className="text-muted">{t.loanId != null ? `#${t.loanId}` : "—"}</td>
    </tr>
  );
}
