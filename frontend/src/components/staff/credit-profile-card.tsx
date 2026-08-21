"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Gauge, Download, Loader2, FileText } from "lucide-react";
import { InfoTooltip } from "@/components/ui";
import { StarRating } from "@/components/ui/star-rating";
import { PdfPreviewDialog } from "@/components/staff/pdf-preview-dialog";
import { staffApi, type CreditBriefFacts, type CreditBriefDetail } from "@/lib/api/applications";
import { flattenProviderReport, type JsonValue } from "@/lib/credit/provider-report";
import { bureauStateLabel } from "@/components/staff/bureau-state";
import { TradelineTable, EnquiryTable } from "@/components/staff/credit/tradeline-table";

const inr = (rupees: number | null | undefined): string =>
  rupees == null
    ? "—"
    : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(rupees);

function verdictTone(rating: number | null): string {
  if (rating == null) return "bg-neutral-100 text-neutral-700";
  if (rating >= 3.5) return "bg-success-100 text-success-800";
  if (rating >= 2.5) return "bg-warning-100 text-warning-800";
  return "bg-error-100 text-error-800";
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="text-sm font-semibold tabular-nums text-ink">{value ?? "—"}</dd>
    </div>
  );
}

function Facts({ f }: { f: CreditBriefFacts }) {
  const pct = (part: number | null, total: number | null) =>
    part != null && total != null && total > 0 ? ` (${Math.round((part / total) * 100)}%)` : "";
  return (
    <div className="mt-4 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-3">
      <dl>
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">A · Identity</div>
        {/* Core identity comes from KYC; city/PIN are the bureau-reported values. */}
        <Row label="Name" value={f.name} />
        <Row label="PAN" value={f.pan} />
        <Row label="Mobile" value={f.mobile} />
        <Row label="DOB" value={f.dob} />
        <Row label="City" value={f.city} />
        <Row label="PIN" value={f.pin} />
      </dl>
      <dl>
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">B · Credit health</div>
        <Row label="Accounts" value={f.totalAccounts} />
        <Row label="Active" value={f.activeAccounts} />
        <Row label="Closed" value={f.closedAccounts} />
        <Row label="Defaults" value={f.defaults} />
      </dl>
      <dl>
        <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">C · Exposure</div>
        <Row label="Total" value={inr(f.totalBalance)} />
        <Row label="Secured" value={`${inr(f.securedBalance)}${pct(f.securedBalance, f.totalBalance)}`} />
        <Row label="Unsecured" value={`${inr(f.unsecuredBalance)}${pct(f.unsecuredBalance, f.totalBalance)}`} />
        <Row label="Inquiries (30d)" value={f.recentInquiries30d} />
      </dl>
    </div>
  );
}

/**
 * The parsed tradeline/enquiry detail behind the twelve `Facts` scalars — the "missing middle"
 * between the curated summary and the raw `CompleteProviderReport` dump. `detail` is null on any
 * brief generated before this parsing shipped (no backfill ran) — render nothing here in that case
 * rather than an empty table, so an older brief looks exactly like it did before this change.
 */
function ReportDetail({ detail }: { detail: CreditBriefDetail }) {
  return (
    <div className="mt-5 space-y-4">
      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">Tradelines</div>
        <TradelineTable tradelines={detail.tradelines} tradelineCount={detail.tradelineCount} />
      </div>
      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">Enquiries</div>
        <EnquiryTable enquiries={detail.enquiries} />
      </div>
    </div>
  );
}

function CompleteProviderReport({ report }: { report: JsonValue }) {
  const rows = React.useMemo(() => flattenProviderReport(report), [report]);
  return (
    <section className="mt-5 overflow-hidden rounded border border-line bg-white">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line bg-navy-tint/60 px-3 py-2.5">
        <div>
          <h3 className="text-sm font-semibold text-navy">Complete provider response</h3>
          <p className="text-xs text-muted">
            Exact payload for new pulls; older records may contain only the legacy verification snapshot.
          </p>
        </div>
        <span className="rounded-full border border-line bg-white px-2 py-0.5 text-xs font-semibold tabular-nums text-muted">
          {rows.length.toLocaleString("en-IN")} fields
        </span>
      </div>
      <div className="max-h-[48rem] overflow-auto">
        <table className="w-full table-fixed border-collapse text-left">
          <thead className="sticky top-0 z-10 bg-navy text-white">
            <tr>
              <th scope="col" className="w-[56%] px-3 py-2 text-xs font-semibold">Provider field path</th>
              <th scope="col" className="w-[44%] px-3 py-2 text-xs font-semibold">Provider value</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`${row.path}-${index}`} className="border-t border-line align-top odd:bg-neutral-50/60">
                <th scope="row" className="break-words px-3 py-2 text-xs font-medium leading-relaxed text-muted">
                  {row.path}
                </th>
                <td className="break-all whitespace-pre-wrap px-3 py-2 font-mono text-xs leading-relaxed text-ink">
                  {row.value}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * Staff-only credit brief card for one application: the 1–5★ recommendation + bureau score, the
 * categorized facts, the underwriter summary, and a one-click PDF download. Self-fetches the brief.
 */
export function CreditProfileCard({ applicationId }: { applicationId: number }) {
  const briefQ = useQuery({
    queryKey: ["credit-brief", applicationId],
    queryFn: () => staffApi.creditBrief(applicationId),
  });
  const [downloading, setDownloading] = React.useState(false);
  const [pdfOpen, setPdfOpen] = React.useState(false);
  const [pdfUrl, setPdfUrl] = React.useState<string | null>(null);

  const brief = briefQ.data;

  async function downloadPdf() {
    if (!brief?.documentId) return;
    setDownloading(true);
    try {
      const { url } = await staffApi.documentUrl(applicationId, brief.documentId);
      setPdfUrl(url);
      setPdfOpen(true);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">
        <Gauge size={16} /> Credit profile
        <InfoTooltip content="Bureau-derived credit score and a 1–5★ recommendation (should we lend?). Staff-only — never shown to the borrower." />
      </div>

      {briefQ.isLoading ? (
        <div className="flex items-center gap-2 text-sm text-muted">
          <Loader2 size={14} className="animate-spin" /> Loading…
        </div>
      ) : briefQ.isError ? (
        <div className="text-sm text-error-700">Couldn&apos;t load bureau data.</div>
      ) : !brief || brief.bureauState === "NOT_FETCHED" ? (
        <div className="text-sm text-muted">
          {bureauStateLabel("NOT_FETCHED", "long")} — the credit brief is generated automatically
          once the bureau report is pulled.
        </div>
      ) : brief.bureauState === "NO_RECORD" ? (
        <>
          <div className="text-sm text-muted">
            {bureauStateLabel("NO_RECORD", "long")} — the bureau reported no credit history for
            this customer.
          </div>
          {brief.providerResponse != null && <CompleteProviderReport report={brief.providerResponse} />}
        </>
      ) : brief.creditScore == null && brief.starRating == null ? (
        <div className="text-sm text-muted">
          The bureau pull completed but returned no usable rating (thin file).
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
            <div>
              <div className="text-xs text-muted">Credit score</div>
              <div className="font-serif text-2xl font-semibold tabular-nums text-navy">
                {brief.creditScore ?? "—"}
                <span className="ml-1 text-sm font-normal text-muted">/ 900</span>
              </div>
            </div>
            <div>
              <div className="text-xs text-muted">Recommendation</div>
              <div className="mt-0.5 flex items-center gap-2">
                <StarRating value={brief.starRating} size="0.88rem" />
                <span className="text-sm font-semibold tabular-nums text-ink">
                  {brief.starRating != null ? `${brief.starRating.toFixed(1)} / 5` : "—"}
                </span>
              </div>
            </div>
            {brief.recommendation && (
              <span
                className={`self-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${verdictTone(brief.starRating)}`}
              >
                {brief.recommendation}
              </span>
            )}
            <button
              type="button"
              onClick={downloadPdf}
              disabled={!brief.documentId || downloading}
              className="ml-auto inline-flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-navy hover:bg-navy-tint disabled:opacity-50"
            >
              {downloading ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
              Download brief (PDF)
            </button>
          </div>

          {brief.facts && <Facts f={brief.facts} />}

          {brief.facts?.detail && <ReportDetail detail={brief.facts.detail} />}

          {brief.summary && (
            <div className="mt-4 rounded bg-navy-tint/60 p-3">
              <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-muted">
                <FileText size={12} /> Underwriter summary
              </div>
              <p className="text-sm leading-relaxed text-ink">{brief.summary}</p>
            </div>
          )}

          {brief.providerResponse != null && <CompleteProviderReport report={brief.providerResponse} />}
        </>
      )}

      <PdfPreviewDialog open={pdfOpen} onClose={() => setPdfOpen(false)} url={pdfUrl} title="Credit brief" />
    </div>
  );
}
