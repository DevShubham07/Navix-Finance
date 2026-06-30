"use client";

import * as React from "react";
import { Download, ChevronDown, FileText, FileSpreadsheet } from "lucide-react";
import { useStaffMe, ROLE_LABEL } from "@/components/staff/live-pipeline";
import { exportCsv, exportPdf, type ExportColumn, type ExportMeta } from "@/lib/export/exporters";

/**
 * "Export ▾" dropdown for staff dashboards — offers CSV and a NAVIX-branded PDF of the rows
 * currently on screen. The PDF is stamped with the signed-in staffer (provenance). Generic over the
 * row type so each page declares its columns once.
 */
export function ExportMenu<Row>({
  title,
  subtitle,
  fileBase,
  columns,
  rows,
  disabled,
  meta,
}: {
  title: string;
  subtitle?: string;
  fileBase: string;
  columns: ExportColumn<Row>[];
  rows: Row[];
  disabled?: boolean;
  /** Optional statement-period metadata stamped onto the PDF. */
  meta?: ExportMeta;
}) {
  const me = useStaffMe().data;
  const [open, setOpen] = React.useState(false);
  const isDisabled = disabled || rows.length === 0;

  const actor = {
    name: me?.name ?? "Unknown",
    role: me ? ROLE_LABEL[me.role] : "Staff",
  };

  const doCsv = () => {
    exportCsv(fileBase, columns, rows);
    setOpen(false);
  };
  const doPdf = () => {
    exportPdf({
      fileBase,
      title,
      subtitle,
      columns,
      rows,
      actor,
      generatedAt: new Date().toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }),
      meta,
    });
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        onClick={() => setOpen((o) => !o)}
        disabled={isDisabled}
        className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs font-semibold text-navy hover:bg-grey-100 disabled:cursor-not-allowed disabled:opacity-50"
        title={isDisabled ? "Nothing to export" : "Export the rows on screen"}
      >
        <Download size={13} /> Export <ChevronDown size={12} className={open ? "rotate-180 transition" : "transition"} />
      </button>
      {open && !isDisabled && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} aria-hidden />
          <div className="absolute right-0 z-20 mt-1 w-44 overflow-hidden rounded border border-line bg-white shadow-lg">
            <button onClick={doCsv} className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink hover:bg-grey-100">
              <FileSpreadsheet size={15} className="text-success-600" /> Export CSV
            </button>
            <button onClick={doPdf} className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink hover:bg-grey-100">
              <FileText size={15} className="text-error-600" /> Export PDF
            </button>
          </div>
        </>
      )}
    </div>
  );
}
