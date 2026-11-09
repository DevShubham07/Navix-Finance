"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { FileSpreadsheet, FileText, Loader2, X } from "lucide-react";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { CustomRangeInputs } from "@/components/staff/custom-range-inputs";
import { CustomerExportFields, DEFAULT_FIELD_KEYS } from "@/components/staff/customer-export-fields";
import { useStaffMe, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { customerExportApi, type ExportGrain } from "@/lib/api/customer-export";
import { SEGMENTS, SEGMENT_LABEL, type CustomerSegment } from "@/lib/customers/segments";
import {
  CSV_MAX_ROWS,
  PDF_MAX_ROWS,
  PDF_TABLE_MAX_FIELDS,
  PREVIEW_ROWS,
  displayCell,
  downloadCsv,
  downloadPdf,
  exportFileBase,
  pdfLayout,
  type FetchOptions,
} from "@/lib/export/customer-data-export";
import type { Range } from "@/lib/period";
import { REPORT_PRESETS, reportRange } from "@/lib/reports/periods";
import { DPD_BUCKET_OPTIONS } from "@/lib/staff/filter-bands";
import { formatDate } from "@/lib/utils";

type Step = 1 | 2 | 3;
const STEPS: Array<{ n: Step; label: string }> = [
  { n: 1, label: "Customers" },
  { n: 2, label: "Fields" },
  { n: 3, label: "Download" },
];

/** The Customers tab's segment keys, worded the way the export asks for them. */
const GROUP_WORDING: Partial<Record<CustomerSegment, string>> = {
  review: "In review",
  disbursementPending: "Disbursement pending",
  active: "Active loans",
  overdue: "Overdue loans",
  hold: "On hold",
  rejected: "Rejected loans",
};
const groupLabel = (seg: string) => (seg === "all" ? "All customers" : GROUP_WORDING[seg as CustomerSegment] ?? SEGMENT_LABEL[seg as CustomerSegment] ?? seg);

const COUNT = new Intl.NumberFormat("en-IN");
const btnPrimary = "min-h-[44px] rounded bg-navy px-5 font-bold text-white hover:opacity-90 disabled:opacity-50";
const btnQuiet = "min-h-[44px] rounded border border-line px-4 font-semibold text-ink hover:bg-grey-50 disabled:opacity-50";
const label = "text-sm font-semibold text-ink";
const select = "min-h-[44px] w-full rounded border border-line bg-white px-3 text-sm";

/**
 * Admin customer data export, in three steps: who (date, group, days past due, one row per), which
 * fields, then download. The server returns the matching ids once and then one page of rows at a time;
 * the CSV and PDF are built here in the browser, one request at a time with progress and cancel.
 */
export function CustomerExportPanel() {
  const me = useStaffMe().data;
  const actor = { name: me?.name ?? "Unknown", role: me ? ROLE_LABEL[me.role] : "Staff" };

  const [step, setStep] = React.useState<Step>(1);
  const [preset, setPreset] = React.useState("this-month");
  const [custom, setCustom] = React.useState<Range>({});
  const [segment, setSegment] = React.useState("all");
  const [dpd, setDpd] = React.useState<string[]>([]);
  const [grain, setGrain] = React.useState<ExportGrain>("customer");
  const [selected, setSelected] = React.useState<Set<string>>(() => new Set(DEFAULT_FIELD_KEYS));
  const [includeIds, setIncludeIds] = React.useState(false);

  const range = React.useMemo(() => reportRange(preset, custom), [preset, custom]);
  const scopeText = `${groupLabel(segment)}${dpd.length > 0 ? `, ${dpd.map((d) => DPD_BUCKET_OPTIONS.find((o) => o.raw === d)?.label ?? d).join(", ")} past due` : ""}. ${
    range.from && range.to ? `Came between ${formatDate(range.from)} and ${formatDate(range.to)}.` : "Any first-application date."
  }`;

  const match = useQuery({
    queryKey: ["customer-export-match", range.from ?? "", range.to ?? "", segment, [...dpd].sort().join(","), grain],
    queryFn: ({ signal }) => customerExportApi.match({ from: range.from, to: range.to, segment, dpd, grain }, signal),
    retry: false,
    staleTime: 30_000,
  });
  const catalog = useQuery({ queryKey: ["customer-export-fields"], queryFn: () => customerExportApi.fields(), retry: false, staleTime: 300_000 });

  const ids = match.data?.ids ?? [];
  const fields = React.useMemo(() => (catalog.data?.fields ?? []).filter((f) => selected.has(f.key)), [catalog.data, selected]);
  const fieldKeys = React.useMemo(() => fields.map((f) => f.key), [fields]);

  const preview = useQuery({
    queryKey: ["customer-export-preview", grain, ids.slice(0, PREVIEW_ROWS).join(","), fieldKeys.join(","), includeIds],
    queryFn: ({ signal }) => customerExportApi.rows({ grain, ids: ids.slice(0, PREVIEW_ROWS), fields: fieldKeys, includeIdNumbers: includeIds }, signal),
    enabled: step === 3 && ids.length > 0 && fieldKeys.length > 0,
    retry: false,
  });

  // ── download ──
  const [progress, setProgress] = React.useState<{ kind: "csv" | "pdf"; done: number; total: number } | null>(null);
  const [message, setMessage] = React.useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const abortRef = React.useRef<AbortController | null>(null);
  const lastKind = React.useRef<"csv" | "pdf">("csv");
  React.useEffect(() => () => abortRef.current?.abort(), []);

  const run = async (kind: "csv" | "pdf") => {
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    lastKind.current = kind;
    const total = kind === "pdf" ? Math.min(ids.length, PDF_MAX_ROWS) : Math.min(ids.length, CSV_MAX_ROWS);
    setProgress({ kind, done: 0, total });
    setMessage(null);
    const opts: FetchOptions = {
      grain,
      ids,
      fields: fieldKeys,
      includeIdNumbers: includeIds,
      signal: ctrl.signal,
      onProgress: (done) => setProgress({ kind, done: Math.min(done, total), total }),
    };
    const ctx = { subtitle: groupLabel(segment), summary: scopeText, actor, fileBase: exportFileBase() };
    try {
      const n = kind === "csv" ? await downloadCsv(opts, ctx) : await downloadPdf(opts, ctx, fields);
      setMessage({ tone: "ok", text: `Downloaded ${COUNT.format(n)} row${n === 1 ? "" : "s"} as ${kind.toUpperCase()}.` });
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") setMessage({ tone: "error", text: "Download cancelled. Nothing was saved." });
      else setMessage({ tone: "error", text: e instanceof Error ? e.message : "The download failed." });
    } finally {
      setProgress(null);
      abortRef.current = null;
    }
  };

  const matched = match.data;
  const countText = matched
    ? grain === "customer"
      ? `${COUNT.format(matched.customers)} customer${matched.customers === 1 ? "" : "s"} match`
      : `${COUNT.format(matched.count)} application${matched.count === 1 ? "" : "s"} from ${COUNT.format(matched.customers)} customer${matched.customers === 1 ? "" : "s"} match`
    : "";
  const none = !!matched && matched.count === 0;
  const pdfTooBig = ids.length > PDF_MAX_ROWS;
  const busy = progress != null;

  const toggleDpd = (raw: string) => setDpd((cur) => (cur.includes(raw) ? cur.filter((d) => d !== raw) : [...cur, raw]));

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <ol className="flex gap-2" aria-label="Steps">
        {STEPS.map((s) => (
          <li
            key={s.n}
            aria-current={step === s.n ? "step" : undefined}
            className={`flex-1 rounded-lg border px-3 py-3 text-sm ${step === s.n ? "border-navy bg-navy font-bold text-white" : "border-line bg-white font-semibold text-muted"}`}
          >
            {s.n}. {s.label}
          </li>
        ))}
      </ol>

      {step === 1 && (
        <section className="space-y-5 rounded-lg border border-line bg-white p-4 sm:p-5">
          <h2 className="mb-0 text-lg text-navy">Who should be in this export?</h2>

          <div className="space-y-1.5">
            <label htmlFor="ce-period" className={label}>
              Came between <span className="font-normal text-muted">(first application date)</span>
            </label>
            <select id="ce-period" value={preset} onChange={(e) => setPreset(e.target.value)} className={select}>
              {REPORT_PRESETS.map((p) => (
                <option key={p.key} value={p.key}>
                  {p.label}
                </option>
              ))}
              <option value="custom">Custom dates</option>
            </select>
            {preset === "custom" && <CustomRangeInputs value={custom} onApply={setCustom} />}
            <p className="text-xs text-muted">
              {range.from && range.to ? `${formatDate(range.from)} to ${formatDate(range.to)}` : "All time"}. A customer counts by the date of their first application, not their latest.
            </p>
          </div>

          <div className="space-y-1.5">
            <label htmlFor="ce-group" className={label}>
              Group
            </label>
            <select id="ce-group" value={segment} onChange={(e) => setSegment(e.target.value)} className={select}>
              <option value="all">{groupLabel("all")}</option>
              {SEGMENTS.map((s) => (
                <option key={s} value={s}>
                  {groupLabel(s)}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1.5">
            <div className={label}>Days past due</div>
            <div className="flex flex-wrap gap-2">
              {DPD_BUCKET_OPTIONS.map((o) => {
                const on = dpd.includes(o.raw);
                return (
                  <button
                    key={o.raw}
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggleDpd(o.raw)}
                    className={`min-h-[44px] rounded-full border px-4 text-sm font-semibold ${on ? "border-navy bg-navy text-white" : "border-line bg-white text-ink hover:bg-grey-50"}`}
                  >
                    {o.label}
                  </button>
                );
              })}
            </div>
            <p className="text-xs text-muted">Leave all off for any. A customer with no open loan has no days past due, so choosing a band leaves them out.</p>
          </div>

          <fieldset className="space-y-1.5">
            <legend className={label}>One row per</legend>
            {(
              [
                ["customer", "Customer, with earlier loans summarised"],
                ["application", "Application, so a returning customer appears more than once"],
              ] as const
            ).map(([value, text]) => (
              <label key={value} className={`flex min-h-[44px] items-center gap-2.5 rounded border px-3 text-sm ${grain === value ? "border-navy" : "border-line"}`}>
                <input type="radio" name="ce-grain" className="accent-[#0C2540]" checked={grain === value} onChange={() => setGrain(value)} />
                {text}
              </label>
            ))}
          </fieldset>
        </section>
      )}

      {step === 1 && (
        <div className="space-y-3">
          {match.isLoading || (match.isFetching && !matched) ? (
            <Skeleton className="h-10 w-64" />
          ) : match.error ? (
            <ErrorState error={match.error} onRetry={() => void match.refetch()} />
          ) : matched ? (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className={`font-serif text-xl font-bold ${match.isFetching ? "text-muted" : "text-navy"}`} aria-live="polite">
                  {countText}
                </div>
                {matched.truncated && <p className="text-xs text-amber-800">More than {COUNT.format(CSV_MAX_ROWS)} match. Only the first {COUNT.format(CSV_MAX_ROWS)} are included; narrow the group to get the rest.</p>}
              </div>
              <button type="button" disabled={none || match.isFetching} onClick={() => setStep(2)} className={btnPrimary}>
                Next: choose fields
              </button>
            </div>
          ) : null}
          {none && <EmptyState title="Nobody matches these choices." className="rounded border border-line bg-white" />}
        </div>
      )}

      {step === 2 && (
        <>
          <section className="rounded-lg border border-line bg-white p-4 sm:p-5">
            <h2 className="mb-3 text-lg text-navy">Which fields do you want?</h2>
            {catalog.isLoading ? (
              <Skeleton variant="table" rows={6} cols={3} />
            ) : catalog.error ? (
              <ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} />
            ) : catalog.data ? (
              <CustomerExportFields
                catalog={catalog.data}
                selected={selected}
                onSelected={setSelected}
                includeIds={includeIds}
                onIncludeIds={setIncludeIds}
              />
            ) : null}
          </section>
          <div className="flex flex-wrap justify-between gap-3">
            <button type="button" onClick={() => setStep(1)} className={btnQuiet}>
              Back
            </button>
            <button type="button" disabled={selected.size === 0} onClick={() => { setMessage(null); setStep(3); }} className={btnPrimary}>
              Next: preview and download
            </button>
          </div>
        </>
      )}

      {step === 3 && (
        <>
          <section className="rounded-lg border border-line bg-white p-4 sm:p-5">
            <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="mb-0 text-lg text-navy">Preview</h2>
              <p className="text-xs text-muted">
                First {Math.min(PREVIEW_ROWS, ids.length)} of {COUNT.format(ids.length)} rows · {fieldKeys.length} field{fieldKeys.length === 1 ? "" : "s"}
              </p>
            </div>
            {preview.isLoading || (preview.isFetching && !preview.data) ? (
              <Skeleton variant="table" rows={5} cols={6} />
            ) : preview.error ? (
              <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />
            ) : !preview.data || preview.data.rows.length === 0 ? (
              <EmptyState title="No rows to preview." className="rounded border border-line" />
            ) : (
              <div className="overflow-hidden rounded border border-line">
                <div className="staff-table-scroll">
                  <table className="staff-data-table">
                    <thead>
                      <tr>
                        {preview.data.columns.map((c) => (
                          <th key={c.key} className={c.type === "MONEY" || c.type === "NUMBER" ? "num" : undefined}>
                            {c.label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-line">
                      {preview.data.rows.map((row, i) => (
                        <tr key={i} className="hover:bg-grey-50">
                          {preview.data.columns.map((c, j) => (
                            <td key={c.key} className={c.type === "MONEY" || c.type === "NUMBER" ? "num" : undefined}>
                              {displayCell(c.type, row[j] ?? null) || <span className="text-muted">—</span>}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </section>

          <section className="space-y-3 rounded-lg bg-navy p-4 text-white sm:p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div className="font-serif text-xl font-bold">{countText}</div>
                <p className="text-xs text-white/75">
                  CSV holds up to {COUNT.format(CSV_MAX_ROWS)} rows. PDF holds up to {COUNT.format(PDF_MAX_ROWS)} customers
                  {pdfLayout(fieldKeys.length) === "cards"
                    ? `, one card each (more than ${PDF_TABLE_MAX_FIELDS} fields), and leaves out fields with no value`
                    : ` as a table (${PDF_TABLE_MAX_FIELDS} fields or fewer)`}
                  .
                </p>
              </div>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  disabled={busy || ids.length === 0 || fieldKeys.length === 0}
                  onClick={() => void run("csv")}
                  className="flex min-h-[44px] items-center gap-2 rounded border border-gold bg-gold px-4 font-bold text-navy hover:opacity-90 disabled:opacity-50"
                >
                  {progress?.kind === "csv" ? <Loader2 size={15} className="animate-spin" /> : <FileSpreadsheet size={15} />} Download CSV
                </button>
                <button
                  type="button"
                  disabled={busy || ids.length === 0 || fieldKeys.length === 0 || pdfTooBig}
                  onClick={() => void run("pdf")}
                  className="flex min-h-[44px] items-center gap-2 rounded border border-white bg-transparent px-4 font-bold text-white hover:bg-white/10 disabled:opacity-50"
                >
                  {progress?.kind === "pdf" ? <Loader2 size={15} className="animate-spin" /> : <FileText size={15} />} Download PDF
                </button>
              </div>
            </div>

            {pdfTooBig && (
              <p role="note" className="rounded bg-white/10 px-3 py-2 text-sm">
                This export has {COUNT.format(ids.length)} rows, more than a PDF holds ({COUNT.format(PDF_MAX_ROWS)}). Download the CSV, or go back and narrow the group.
              </p>
            )}

            {progress && (
              <div className="space-y-1.5" aria-live="polite">
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span>
                    Preparing {progress.kind.toUpperCase()}: {COUNT.format(progress.done)} of {COUNT.format(progress.total)}
                  </span>
                  <button type="button" onClick={() => abortRef.current?.abort()} className="flex min-h-[36px] items-center gap-1 rounded border border-white/60 px-3 text-sm font-semibold hover:bg-white/10">
                    <X size={14} /> Cancel
                  </button>
                </div>
                <div
                  className="h-2 overflow-hidden rounded bg-white/20"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={progress.total}
                  aria-valuenow={progress.done}
                >
                  <div className="h-full bg-gold transition-all" style={{ width: `${progress.total ? (progress.done / progress.total) * 100 : 0}%` }} />
                </div>
              </div>
            )}

            {message && (
              <div role={message.tone === "error" ? "alert" : "status"} className={`flex flex-wrap items-center justify-between gap-2 rounded px-3 py-2 text-sm ${message.tone === "error" ? "bg-red-50 text-red-800" : "bg-white/10"}`}>
                <span>{message.text}</span>
                {message.tone === "error" && (
                  <button type="button" disabled={busy} onClick={() => void run(lastKind.current)} className="min-h-[36px] rounded border border-red-300 px-3 font-semibold hover:bg-red-100">
                    Try again
                  </button>
                )}
              </div>
            )}
          </section>

          <div>
            <button type="button" disabled={busy} onClick={() => setStep(2)} className={btnQuiet}>
              Back to fields
            </button>
          </div>
        </>
      )}
    </div>
  );
}
