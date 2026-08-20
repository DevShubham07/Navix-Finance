"use client";

/**
 * Shared credit-report detail rendering — tradelines, enquiries, delinquency aggregates and the
 * payment-history strip — used by both the credit decision card (`application-detail-dialog.tsx`,
 * `CreditFocus`) and the full credit-profile card (`credit-profile-card.tsx`). Kept in one place so
 * the two surfaces cannot drift on the code lookups / null-handling rules below.
 *
 * Every rule here traces back to a real case seen across 6,417 production tradelines — see the
 * per-rule comments. Getting one of these wrong is worse than not showing the field: a reviewer
 * sanctions loans off this screen.
 */

import * as React from "react";
import { KV } from "@/components/staff/detail-parts";
import type {
  Tradeline,
  Enquiry,
  DelinquencySummary,
  EnquiryVelocity,
} from "@/lib/api/applications";

// ---------------------------------------------------------------------------
// Money / null formatting
// ---------------------------------------------------------------------------

/**
 * Tradeline/enquiry amounts are RUPEES (the bureau's native unit), never paise — do not run these
 * through `paiseToINR`. Mirrors the `inr()` helper in credit-profile-card.tsx exactly so the two
 * surfaces agree. `Intl.NumberFormat` renders a leading minus for negative values on its own, which
 * is exactly what we want for the real negative-balance tradelines (rule 6) — never clamp to 0.
 */
export function formatRupees(rupees: number | null | undefined): string {
  return rupees == null
    ? "—"
    : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(
        rupees,
      );
}

/**
 * Absent ≠ zero. A `null` aggregate (DPD count, enquiry count, …) must render as "—", never "0" —
 * "0 accounts past due" on a file where the bureau simply didn't say is how a delinquent borrower
 * gets waved through. Every caller of this file that touches a `number | null` field goes through
 * this helper rather than a bare template literal, so the rule can't be silently dropped later.
 */
export function formatCount(n: number | null | undefined): string {
  return n == null ? "—" : String(n);
}

/**
 * DPD/default-age readings run to 900+ in real data (900 occurs 981 times — a genuine long-default
 * signal, not a sentinel to hide) and as high as 999. Render "900+ days" rather than a bare number
 * so it reads as "very delinquent" instead of looking like a data error, but never suppress it.
 */
export function formatDpdDays(days: number | null | undefined): string {
  if (days == null) return "—";
  if (days >= 900) return "900+ days";
  return `${days} day${days === 1 ? "" : "s"}`;
}

function formatDateStr(d: string | null | undefined): string {
  if (!d) return "—";
  const parsed = new Date(d);
  if (Number.isNaN(parsed.getTime())) return d;
  return parsed.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

// ---------------------------------------------------------------------------
// Code lookups — never guess a label for an unmapped code
// ---------------------------------------------------------------------------

const ACCOUNT_TYPE_LABELS: Record<number, string> = {
  0: "Others",
  1: "Auto",
  2: "Housing",
  3: "Property",
  4: "Loan vs Shares",
  5: "Personal Loan",
  6: "Consumer Loan",
  7: "Gold Loan",
  8: "Education",
  9: "Loan to Professional",
  10: "Credit Card",
  11: "Leasing",
  12: "Overdraft",
  13: "Two-Wheeler",
  31: "Secured Credit Card",
  35: "Corporate Credit Card",
  41: "Microfinance – Personal",
  45: "P2P Personal Loan",
  61: "Business Loan – Unsecured",
  69: "Short Term Personal Loan",
  71: "Temporary Overdraft",
};

const ACCOUNT_STATUS_LABELS: Record<number, string> = {
  11: "Active",
  21: "Active",
  13: "Closed",
  15: "Closed",
  30: "Restructured",
  32: "Settled",
  43: "Written-off",
  45: "Post-WO Settled",
  71: "Delinquent",
  78: "Delinquent",
  80: "Delinquent",
  82: "Delinquent",
  84: "Delinquent",
  97: "Delinquent",
};

/** Codes arrive zero-padded ("05", "01") — strip leading zeros by parsing as an int before lookup. */
function codeToInt(code: string | null | undefined): number | null {
  if (code == null) return null;
  const trimmed = code.trim();
  if (trimmed === "") return null;
  const n = parseInt(trimmed, 10);
  return Number.isNaN(n) ? null : n;
}

/** Falls back to `Type <code>` for anything unmapped — never guesses a label. */
export function accountTypeLabel(code: string | null | undefined): string {
  const n = codeToInt(code);
  if (n == null) return code ? `Type ${code}` : "—";
  return ACCOUNT_TYPE_LABELS[n] ?? `Type ${n}`;
}

/** Falls back to `Status <code>` for anything unmapped — never guesses a label. */
export function accountStatusLabel(code: string | null | undefined): string {
  const n = codeToInt(code);
  if (n == null) return code ? `Status ${code}` : "—";
  return ACCOUNT_STATUS_LABELS[n] ?? `Status ${n}`;
}

export type StatusBucket = "delinquent" | "written-off" | "settled" | "restructured" | "active" | "closed" | "unknown";

const DELINQUENT_CODES = new Set([71, 78, 80, 82, 84, 97]);
const WRITTEN_OFF_CODES = new Set([43, 45]);
const SETTLED_CODES = new Set([32]);
const RESTRUCTURED_CODES = new Set([30]);
const ACTIVE_CODES = new Set([11, 21]);
const CLOSED_CODES = new Set([13, 15]);

/** Buckets a status code for sort ordering and tone — deliberately separate from the display label,
 *  since several codes (e.g. 43/45) share a display family but not necessarily a tone. */
export function classifyStatus(code: string | null | undefined): StatusBucket {
  const n = codeToInt(code);
  if (n == null) return "unknown";
  if (DELINQUENT_CODES.has(n)) return "delinquent";
  if (WRITTEN_OFF_CODES.has(n)) return "written-off";
  if (SETTLED_CODES.has(n)) return "settled";
  if (RESTRUCTURED_CODES.has(n)) return "restructured";
  if (ACTIVE_CODES.has(n)) return "active";
  if (CLOSED_CODES.has(n)) return "closed";
  return "unknown";
}

function statusTone(bucket: StatusBucket): string {
  switch (bucket) {
    case "delinquent":
    case "written-off":
      return "bg-error-100 text-error-800";
    case "restructured":
      return "bg-warning-100 text-warning-800";
    case "active":
      return "bg-success-100 text-success-800";
    case "settled":
    case "closed":
      return "bg-neutral-100 text-neutral-700";
    default:
      return "bg-neutral-100 text-neutral-700";
  }
}

// ---------------------------------------------------------------------------
// Payment history strip
// ---------------------------------------------------------------------------

type HistoryTone = "good" | "warn" | "bad" | "neutral";

const HISTORY_CHAR_INFO: Record<string, { label: string; tone: HistoryTone }> = {
  "0": { label: "0–29 dpd", tone: "good" },
  "1": { label: "30–59 dpd", tone: "warn" },
  "2": { label: "60–89 dpd", tone: "warn" },
  "3": { label: "90–119 dpd", tone: "bad" },
  "4": { label: "120–149 dpd", tone: "bad" },
  "5": { label: "150–179 dpd", tone: "bad" },
  "6": { label: "180+ dpd", tone: "bad" },
  S: { label: "Standard", tone: "good" },
  B: { label: "Substandard", tone: "warn" },
  D: { label: "Doubtful", tone: "bad" },
  M: { label: "Special Mention", tone: "warn" },
  "?": { label: "No data", tone: "neutral" },
  N: { label: "No data", tone: "neutral" },
};

const HISTORY_TONE_CLASS: Record<HistoryTone, string> = {
  good: "bg-success-100 text-success-800",
  warn: "bg-warning-100 text-warning-800",
  bad: "bg-error-100 text-error-800",
  neutral: "bg-neutral-100 text-neutral-500",
};

/**
 * `paymentHistory` is either the single character `"N"` (no history reported at all — render the
 * sentence, never a one-cell strip) or exactly 36 characters, one per month, newest-first.
 *
 * Any character outside the documented set — including `"L"`, seen 180 times in production and
 * still undocumented by the bureau — MUST render as a neutral "unknown" cell. It must never crash
 * (a bad index/char shouldn't take down the credit report) and must never be coloured as good,
 * since we have no idea what it actually means.
 */
export function PaymentHistoryStrip({ value }: { value: string | null | undefined }) {
  if (value == null || value === "") return <span className="text-muted">—</span>;
  if (value === "N") {
    return <span className="text-xs italic text-muted">No payment history reported</span>;
  }
  const chars = value.split("");
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex flex-wrap gap-[2px]">
        {chars.map((ch, i) => {
          const info = HISTORY_CHAR_INFO[ch] ?? { label: `Unrecognized code "${ch}"`, tone: "neutral" as const };
          return (
            <span
              key={i}
              title={`Month -${i}: ${info.label}`}
              className={`flex h-4 w-4 items-center justify-center rounded-[2px] font-mono text-[9px] font-semibold ${HISTORY_TONE_CLASS[info.tone]}`}
            >
              {ch}
            </span>
          );
        })}
      </div>
      <span className="text-[10px] text-muted">Newest → oldest, one cell per month</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tradeline table
// ---------------------------------------------------------------------------

/** Worst-first, deterministic: delinquent/written-off, then currently past due, then open (by
 *  balance desc), then everything else (closed/settled/restructured/unknown). */
function tradelinePriority(t: Tradeline): number {
  const bucket = classifyStatus(t.accountStatusCode);
  if (bucket === "delinquent" || bucket === "written-off") return 0;
  if ((t.amountPastDueRupees ?? 0) > 0) return 1;
  if (bucket === "active") return 2;
  return 3;
}

function sortTradelines(rows: Tradeline[]): Tradeline[] {
  return [...rows].sort((a, b) => {
    const pa = tradelinePriority(a);
    const pb = tradelinePriority(b);
    if (pa !== pb) return pa - pb;
    const ba = a.currentBalanceRupees ?? Number.NEGATIVE_INFINITY;
    const bb = b.currentBalanceRupees ?? Number.NEGATIVE_INFINITY;
    if (ba !== bb) return bb - ba;
    return (b.closedOn ?? "").localeCompare(a.closedOn ?? "");
  });
}

/** Rows that matter by default: open, delinquent, written-off, restructured, or anything currently
 *  past due — everything closed/settled with nothing outstanding is folded behind "Show all". */
function isDefaultVisible(t: Tradeline): boolean {
  if ((t.amountPastDueRupees ?? 0) > 0) return true;
  const bucket = classifyStatus(t.accountStatusCode);
  return bucket !== "closed" && bucket !== "settled";
}

export function TradelineTable({
  tradelines,
  tradelineCount,
}: {
  tradelines: Tradeline[];
  tradelineCount: number | null;
}) {
  const [showAll, setShowAll] = React.useState(false);
  const sorted = React.useMemo(() => sortTradelines(tradelines), [tradelines]);
  const visible = showAll ? sorted : sorted.filter(isDefaultVisible);
  const hiddenCount = sorted.length - visible.length;
  // The backend caps the payload at 1000 rows for display — `tradelineCount` is the TRUE count in
  // the report and can exceed what we actually received, so truncation must stay visible.
  const truncated = tradelineCount != null && tradelineCount > tradelines.length;

  if (tradelines.length === 0) {
    return <p className="text-sm text-muted">No tradelines parsed for this report.</p>;
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted">
          {truncated
            ? `Showing ${tradelines.length.toLocaleString("en-IN")} of ${tradelineCount!.toLocaleString("en-IN")} tradelines in the report`
            : `${tradelines.length.toLocaleString("en-IN")} tradeline${tradelines.length === 1 ? "" : "s"}`}
        </span>
        {hiddenCount > 0 && !showAll && (
          <button
            type="button"
            onClick={() => setShowAll(true)}
            className="text-xs font-semibold text-navy underline decoration-dotted underline-offset-2 hover:text-navy/80"
          >
            Show all {sorted.length.toLocaleString("en-IN")} (+{hiddenCount} closed/settled)
          </button>
        )}
        {showAll && (
          <button
            type="button"
            onClick={() => setShowAll(false)}
            className="text-xs font-semibold text-navy underline decoration-dotted underline-offset-2 hover:text-navy/80"
          >
            Show open + delinquent only
          </button>
        )}
      </div>

      <div className="max-h-[28rem] overflow-auto rounded border border-line">
        <table className="w-full min-w-[64rem] table-fixed border-collapse text-left text-xs">
          <thead className="sticky top-0 z-10 bg-navy text-white">
            <tr>
              <th className="w-[14%] px-2 py-1.5 font-semibold">Lender</th>
              <th className="w-[10%] px-2 py-1.5 font-semibold">Type</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Status</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Opened</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Closed</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Balance</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Past due</th>
              <th className="w-[9%] px-2 py-1.5 font-semibold">Worst DPD</th>
              <th className="w-[22%] px-2 py-1.5 font-semibold">Payment history</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((t, i) => {
              const bucket = classifyStatus(t.accountStatusCode);
              return (
                <tr key={`${t.accountNumberMasked ?? "tl"}-${i}`} className="border-t border-line align-top odd:bg-neutral-50/60">
                  <td className="break-words px-2 py-1.5 text-ink">
                    <div className="font-medium">{t.lender ?? "—"}</div>
                    <div className="font-mono text-[10px] text-muted">{t.accountNumberMasked ?? ""}</div>
                  </td>
                  <td className="break-words px-2 py-1.5 text-ink">{accountTypeLabel(t.accountTypeCode)}</td>
                  <td className="px-2 py-1.5">
                    <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${statusTone(bucket)}`}>
                      {accountStatusLabel(t.accountStatusCode)}
                    </span>
                    {t.writtenOffSettledStatus && (
                      <div className="mt-0.5 text-[10px] text-muted">{t.writtenOffSettledStatus}</div>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-ink">{formatDateStr(t.openedOn)}</td>
                  <td className="px-2 py-1.5 text-ink">{formatDateStr(t.closedOn)}</td>
                  <td className="px-2 py-1.5 font-mono text-ink">{formatRupees(t.currentBalanceRupees)}</td>
                  <td className={`px-2 py-1.5 font-mono ${(t.amountPastDueRupees ?? 0) > 0 ? "font-semibold text-error-700" : "text-ink"}`}>
                    {formatRupees(t.amountPastDueRupees)}
                  </td>
                  <td className="px-2 py-1.5 text-ink">
                    {t.worstDpdMonths != null && t.worstDpdMonths >= 900 ? (
                      <span className="font-semibold text-error-700">{formatDpdDays(t.worstDpdMonths)}</span>
                    ) : (
                      formatDpdDays(t.worstDpdMonths)
                    )}
                  </td>
                  <td className="px-2 py-1.5">
                    <PaymentHistoryStrip value={t.paymentHistory} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Enquiry table
// ---------------------------------------------------------------------------

function sortEnquiries(rows: Enquiry[]): Enquiry[] {
  return [...rows].sort((a, b) => (b.requestedOn ?? "").localeCompare(a.requestedOn ?? ""));
}

export function EnquiryTable({ enquiries }: { enquiries: Enquiry[] }) {
  const sorted = React.useMemo(() => sortEnquiries(enquiries), [enquiries]);
  if (sorted.length === 0) {
    return <p className="text-sm text-muted">No enquiries parsed for this report.</p>;
  }
  return (
    <div className="max-h-[20rem] overflow-auto rounded border border-line">
      <table className="w-full min-w-[40rem] table-fixed border-collapse text-left text-xs">
        <thead className="sticky top-0 z-10 bg-navy text-white">
          <tr>
            <th className="w-[16%] px-2 py-1.5 font-semibold">Date</th>
            <th className="w-[34%] px-2 py-1.5 font-semibold">Lender</th>
            <th className="w-[20%] px-2 py-1.5 font-semibold">Reason</th>
            <th className="w-[15%] px-2 py-1.5 font-semibold">Amount financed</th>
            <th className="w-[15%] px-2 py-1.5 font-semibold">Duration</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((e, i) => (
            <tr key={i} className="border-t border-line align-top odd:bg-neutral-50/60">
              <td className="px-2 py-1.5 text-ink">{formatDateStr(e.requestedOn)}</td>
              <td className="break-words px-2 py-1.5 text-ink">{e.subscriber ?? "—"}</td>
              <td className="px-2 py-1.5 text-ink">{e.reasonCode ?? "—"}</td>
              <td className="px-2 py-1.5 font-mono text-ink">{formatRupees(e.amountFinancedRupees)}</td>
              <td className="px-2 py-1.5 text-ink">
                {e.durationMonths != null ? `${e.durationMonths} mo` : "—"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Delinquency + enquiry-velocity summary blocks
// ---------------------------------------------------------------------------

/** A `null` renders neutral (we couldn't tell); a real non-zero value gets warning/error tone. A
 *  real zero stays neutral too — "0 accounts ever 30+" is good news, not a flag. */
function toneFor(n: number | null, severe = false): string {
  if (n == null || n === 0) return "text-ink";
  return severe ? "text-error-700" : "text-warning-800";
}

export function DelinquencySummaryBlock({ d }: { d: DelinquencySummary | null | undefined }) {
  if (!d) return <p className="text-sm text-muted">No delinquency aggregates parsed for this report.</p>;
  return (
    <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
      <KV k="Worst DPD (12m)" v={<span className={toneFor(d.worstDpd12m, true)}>{formatDpdDays(d.worstDpd12m)}</span>} mono />
      <KV k="Worst DPD (24m)" v={<span className={toneFor(d.worstDpd24m, true)}>{formatDpdDays(d.worstDpd24m)}</span>} mono />
      <KV k="Worst DPD (36m)" v={<span className={toneFor(d.worstDpd36m)}>{formatDpdDays(d.worstDpd36m)}</span>} mono />
      <KV
        k="Accounts ever 30+ dpd"
        v={<span className={toneFor(d.accountsEver30Plus)}>{formatCount(d.accountsEver30Plus)}</span>}
        mono
      />
      <KV
        k="Currently past due"
        v={<span className={toneFor(d.accountsCurrentlyPastDue, true)}>{formatCount(d.accountsCurrentlyPastDue)}</span>}
        mono
      />
      <KV
        k="Written-off / settled"
        v={<span className={toneFor(d.accountsWrittenOffOrSettled, true)}>{formatCount(d.accountsWrittenOffOrSettled)}</span>}
        mono
      />
      <KV k="Oldest account opened" v={formatDateStr(d.oldestAccountOpenedOn)} />
    </dl>
  );
}

export function EnquiryVelocityBlock({ v }: { v: EnquiryVelocity | null | undefined }) {
  if (!v) return <p className="text-sm text-muted">No enquiry-velocity data parsed for this report.</p>;
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-1 sm:grid-cols-4">
      <KV k="Last 7d" v={<span className={toneFor(v.last7)}>{formatCount(v.last7)}</span>} mono />
      <KV k="Last 30d" v={<span className={toneFor(v.last30)}>{formatCount(v.last30)}</span>} mono />
      <KV k="Last 90d" v={<span className={toneFor(v.last90)}>{formatCount(v.last90)}</span>} mono />
      <KV k="Last 180d" v={<span className={toneFor(v.last180)}>{formatCount(v.last180)}</span>} mono />
    </dl>
  );
}
