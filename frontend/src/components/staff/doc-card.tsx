import * as React from "react";
import { Loader2, Trash2 } from "lucide-react";
import { Field } from "@/components/ui/field-grid";
import { DocPassword } from "@/components/staff/detail-parts";

export function DocCard({
  n,
  type,
  date,
  meta,
  password,
  onView,
  onDelete,
  deleting,
  busy,
}: {
  n: number;
  type: React.ReactNode;
  date: React.ReactNode;
  meta?: React.ReactNode;
  password?: string | null;
  onView: () => void;
  onDelete?: () => void;
  deleting?: boolean;
  busy?: boolean;
}) {
  return (
    <div className="flex flex-col gap-2 rounded-xl border border-line bg-white p-3">
      <div className="flex items-center gap-2">
        <span className="grid h-6 w-6 place-items-center rounded-full bg-navy-tint text-[9.6px] font-bold text-navy">
          {n}
        </span>
        <span className="min-w-0 flex-1 truncate text-[9.6px] font-bold uppercase text-ink">{type}</span>
        {onDelete && (
          <button
            type="button"
            aria-label="Delete document"
            disabled={deleting}
            onClick={onDelete}
            className="rounded p-1 text-error-600 hover:bg-error-50 disabled:opacity-50"
          >
            {deleting ? <Loader2 size={14} className="animate-spin" /> : <Trash2 size={14} />}
          </button>
        )}
      </div>
      <Field label="Upload date">{date}</Field>
      {meta != null && <Field label="Remarks">{meta}</Field>}
      <DocPassword password={password} />
      <button type="button" className="btn btn-navy btn-sm w-full" onClick={onView} disabled={busy}>
        {busy && <Loader2 size={14} className="animate-spin" />}
        View document
      </button>
    </div>
  );
}
