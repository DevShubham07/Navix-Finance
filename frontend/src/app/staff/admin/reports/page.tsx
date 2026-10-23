"use client";

import * as React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { CalendarClock, Eye, FileSpreadsheet, FileText, Loader2, Search, X } from "lucide-react";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { CustomRangeInputs } from "@/components/staff/custom-range-inputs";
import { reportsApi, type ReportData, type ReportDef } from "@/lib/api/applications";
import type { Range } from "@/lib/period";
import { formatDate } from "@/lib/utils";
import { REPORT_PRESETS, reportRange } from "@/lib/reports/periods";
import { REPORT_CATEGORIES, reportCategoryLabel } from "@/lib/reports/categories";
import { downloadReport, formatReportCell, periodLine, reportTotals } from "@/lib/export/report-export";

/** Rows the on-screen preview renders; the downloads always carry every row. */
const PREVIEW_ROWS = 200;

type Format = "csv" | "pdf";

/**
 * Admin · Reports — one place to pull any report about the platform (customers, loans, repayments,
 * collections, staff, finance, partners) for a chosen period, preview it, and download it as a CSV
 * for a spreadsheet or a branded PDF to share. Both files are written from the same server table, so
 * they carry identical information.
 */
export default function AdminReportsPage() {
  return (
    <React.Suspense fallback={<div className="h-40 animate-pulse rounded bg-grey-100" />}>
      <ReportsCentre />
    </React.Suspense>
  );
}

function ReportsCentre() {
  const me = useStaffMe().data;
  // `?seg=` is the sidebar's report group (Reports → Risk & portfolio quality, …); absent = every group.
  const category = reportCategoryLabel(useSearchParams().get("seg"));
  const can = useCan();
  const qc = useQueryClient();

  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});
  const [search, setSearch] = React.useState("");
  const [previewKey, setPreviewKey] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const previewRef = React.useRef<HTMLDivElement>(null);

  const range = React.useMemo(() => reportRange(preset, custom), [preset, custom]);
  const catalog = useQuery({ queryKey: ["reports-catalog"], queryFn: () => reportsApi.catalog() });

  const reportQuery = (key: string) => ({
    queryKey: ["report", key, range.from ?? "", range.to ?? ""],
    queryFn: () => reportsApi.run(key, range),
    staleTime: 60_000,
  });
  const preview = useQuery({ ...reportQuery(previewKey ?? ""), enabled: previewKey != null });

  const actor = { name: me?.name ?? "Unknown", role: me ? ROLE_LABEL[me.role] : "Staff" };

  const download = async (key: string, format: Format) => {
    setBusy(`${key}:${format}`);
    setError(null);
    try {
      downloadReport(await qc.fetchQuery(reportQuery(key)), format, actor);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Download failed");
    } finally {
      setBusy(null);
    }
  };

  const openPreview = (key: string) => {
    setPreviewKey(key);
    requestAnimationFrame(() => previewRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }));
  };

  const groups = React.useMemo(() => {
    const needle = search.trim().toLowerCase();
    const out = new Map<string, ReportDef[]>();
    for (const d of catalog.data ?? []) {
      if (category && d.category !== category) continue;
      if (needle && !`${d.title} ${d.description} ${d.category}`.toLowerCase().includes(needle)) continue;
      out.set(d.category, [...(out.get(d.category) ?? []), d]);
    }
    return [...out.entries()];
  }, [catalog.data, search, category]);

  if (me && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;

  const pill = (active: boolean) =>
    `rounded px-2.5 py-1 text-xs font-semibold transition-colors ${active ? "bg-navy text-white" : "text-muted hover:text-ink"}`;

  return (
    <div>
      <PageHeader
        title={category ? `Reports · ${category}` : "Reports"}
        subtitle="Pick a period, then download any report as a CSV for spreadsheets or a formatted PDF to share. Every report says in one line what it contains."
      />

      <nav aria-label="Report groups" className="mb-4 flex flex-wrap gap-1.5">
        <Link
          href="/staff/admin/reports"
          className={`rounded-full border px-3 py-1 text-xs font-semibold ${!category ? "border-navy bg-navy text-white" : "border-line text-muted hover:text-ink"}`}
        >
          All reports
        </Link>
        {REPORT_CATEGORIES.map((c) => (
          <Link
            key={c.seg}
            href={`/staff/admin/reports?seg=${c.seg}`}
            className={`rounded-full border px-3 py-1 text-xs font-semibold ${category === c.label ? "border-navy bg-navy text-white" : "border-line text-muted hover:text-ink"}`}
          >
            {c.label}
          </Link>
        ))}
      </nav>

      <div className="z-10 -mx-1 mb-6 rounded border lg:sticky lg:top-0 border-line bg-white/95 p-3 shadow-sm backdrop-blur">
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-muted">
            <CalendarClock size={13} /> Period
          </span>
          <div className="flex flex-wrap gap-1 rounded border border-line bg-grey-50 p-1">
            {REPORT_PRESETS.map((p) => (
              <button key={p.key} type="button" aria-pressed={preset === p.key} onClick={() => setPreset(p.key)} className={pill(preset === p.key)}>
                {p.label}
              </button>
            ))}
            <button type="button" aria-pressed={preset === "custom"} onClick={() => setPreset("custom")} className={pill(preset === "custom")}>
              Custom
            </button>
          </div>
          {preset === "custom" && <CustomRangeInputs value={custom} onApply={setCustom} />}
        </div>
        <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-muted">
            Selected:{" "}
            <span className="font-semibold text-ink">
              {range.from && range.to ? `${formatDate(range.from)} – ${formatDate(range.to)}` : "All time"}
            </span>
            {" · "}Reports marked <em>live snapshot</em> ignore the period.
          </p>
          <label className="relative">
            <Search size={14} className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 text-muted" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Find a report…"
              className="w-56 rounded border border-line py-1.5 pl-7 pr-2 text-sm"
              aria-label="Find a report"
            />
          </label>
        </div>
      </div>

      {error && (
        <p role="alert" className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {catalog.isLoading ? (
        <Skeleton variant="table" rows={6} cols={3} className="rounded border border-line bg-white" />
      ) : catalog.error ? (
        <ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} />
      ) : groups.length === 0 ? (
        <EmptyState title="No report matches that search." className="rounded border border-line bg-white" />
      ) : (
        <div className="space-y-8">
          {groups.map(([category, defs]) => (
            <section key={category}>
              <h2 className="mb-3 text-lg">{category}</h2>
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                {defs.map((d) => (
                  <ReportCard
                    key={d.key}
                    def={d}
                    active={previewKey === d.key}
                    busy={busy}
                    onPreview={() => openPreview(d.key)}
                    onDownload={(f) => void download(d.key, f)}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      <div ref={previewRef} className="scroll-mt-28">
        {previewKey && (
          <ReportPreview
            data={preview.data}
            loading={preview.isFetching && !preview.data}
            error={preview.error}
            onRetry={() => void preview.refetch()}
            onClose={() => setPreviewKey(null)}
            busy={busy}
            onDownload={(f) => void download(previewKey, f)}
            previewKey={previewKey}
          />
        )}
      </div>
    </div>
  );
}

function DownloadButtons({ reportKey, busy, onDownload }: { reportKey: string; busy: string | null; onDownload: (f: Format) => void }) {
  const btn = "flex items-center gap-1.5 rounded border px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50";
  return (
    <>
      <button type="button" disabled={busy != null} onClick={() => onDownload("csv")} className={`${btn} border-line text-ink hover:bg-grey-100`}>
        {busy === `${reportKey}:csv` ? <Loader2 size={13} className="animate-spin" /> : <FileSpreadsheet size={13} />} CSV
      </button>
      <button type="button" disabled={busy != null} onClick={() => onDownload("pdf")} className={`${btn} border-navy bg-navy text-white hover:opacity-90`}>
        {busy === `${reportKey}:pdf` ? <Loader2 size={13} className="animate-spin" /> : <FileText size={13} />} PDF
      </button>
    </>
  );
}

function ReportCard({
  def,
  active,
  busy,
  onPreview,
  onDownload,
}: {
  def: ReportDef;
  active: boolean;
  busy: string | null;
  onPreview: () => void;
  onDownload: (f: Format) => void;
}) {
  return (
    <div className={`flex flex-col rounded border bg-white p-4 shadow-sm ${active ? "border-navy ring-1 ring-navy" : "border-line"}`}>
      <div className="mb-1 font-semibold text-navy">{def.title}</div>
      <p className="mb-3 flex-1 text-sm text-muted">{def.description}</p>
      <div className="mb-3 text-[11px] uppercase tracking-wide text-muted">
        {def.dateBasis ? `Period by ${def.dateBasis.toLowerCase()}` : "Live snapshot"}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" onClick={onPreview} className="flex items-center gap-1.5 rounded border border-line px-2.5 py-1.5 text-xs font-semibold text-muted hover:bg-grey-100 hover:text-ink">
          <Eye size={13} /> Preview
        </button>
        <DownloadButtons reportKey={def.key} busy={busy} onDownload={onDownload} />
      </div>
    </div>
  );
}

function ReportPreview({
  data,
  loading,
  error,
  onRetry,
  onClose,
  busy,
  onDownload,
  previewKey,
}: {
  data: ReportData | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  onClose: () => void;
  busy: string | null;
  onDownload: (f: Format) => void;
  previewKey: string;
}) {
  const totals = data ? reportTotals(data) : null;
  return (
    <section className="mt-10 rounded border border-line bg-white shadow-sm" aria-label="Report preview">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line p-4">
        <div>
          <div className="text-xs uppercase tracking-wide text-muted">{data?.category ?? "Preview"}</div>
          <h2 className="mb-0 text-xl">{data?.title ?? "Loading…"}</h2>
          {data && <p className="mt-1 text-sm text-muted">{periodLine(data)} · {data.rows.length} {data.rows.length === 1 ? "row" : "rows"}</p>}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <DownloadButtons reportKey={previewKey} busy={busy} onDownload={onDownload} />
          <button type="button" onClick={onClose} aria-label="Close preview" className="rounded p-1.5 text-muted hover:bg-grey-100 hover:text-ink">
            <X size={16} />
          </button>
        </div>
      </div>

      {loading ? (
        <Skeleton variant="table" rows={6} cols={6} className="m-4" />
      ) : error ? (
        <div className="p-4">
          <ErrorState error={error} onRetry={onRetry} />
        </div>
      ) : data ? (
        <div className="p-4">
          {data.summary.length > 0 && (
            <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {data.summary.map((f) => (
                <div key={f.label} className="rounded border-l-4 border-gold bg-ivory px-3 py-2">
                  <div className="text-[11px] uppercase tracking-wide text-muted">{f.label}</div>
                  <div className="font-mono text-lg font-semibold text-navy">{formatReportCell(f.type, f.value, "screen") || "0"}</div>
                </div>
              ))}
            </div>
          )}
          {data.rows.length === 0 ? (
            <EmptyState title="No records for this period." className="rounded border border-line" />
          ) : (
            <div className="overflow-hidden rounded border border-line">
              <div className="staff-table-scroll">
                <table className="staff-data-table">
                  <thead>
                    <tr>
                      {data.columns.map((c) => (
                        <th key={c.key} className={c.type === "MONEY" || c.type === "NUMBER" ? "num" : undefined}>
                          {c.label.replace(" (Rs.)", " (₹)")}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {data.rows.slice(0, PREVIEW_ROWS).map((row, i) => (
                      <tr key={i} className="hover:bg-grey-50">
                        {data.columns.map((c, j) => (
                          <td key={c.key} className={c.type === "MONEY" || c.type === "NUMBER" ? "num" : undefined}>
                            {formatReportCell(c.type, row[j], "screen") || <span className="text-muted">—</span>}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                  {totals && (
                    <tfoot>
                      <tr className="bg-grey-50 font-semibold">
                        {data.columns.map((c, j) => (
                          <td key={c.key} className={c.type === "MONEY" ? "num" : undefined}>
                            {j === 0 ? "Total" : formatReportCell(c.type, totals[j], "screen")}
                          </td>
                        ))}
                      </tr>
                    </tfoot>
                  )}
                </table>
              </div>
            </div>
          )}
          {data.rows.length > PREVIEW_ROWS && (
            <p className="mt-2 text-xs text-muted">
              Showing the first {PREVIEW_ROWS} of {data.rows.length} rows — the CSV and PDF include all of them.
            </p>
          )}
          {data.notes.length > 0 && (
            <ul className="mt-3 list-disc space-y-1 pl-5 text-xs text-muted">
              {data.notes.map((n) => (
                <li key={n}>{n}</li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}
