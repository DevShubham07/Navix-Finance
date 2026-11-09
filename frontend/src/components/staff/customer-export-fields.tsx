"use client";

import * as React from "react";
import { ChevronDown, ChevronRight, Lock } from "lucide-react";
import type { ExportCatalog, ExportField } from "@/lib/api/customer-export";

/** What a new export starts with: who, how to reach them, where they stand and what they owe. */
export const DEFAULT_FIELD_KEYS = [
  "customer_id",
  "full_name",
  "mobile",
  "application_status",
  "loan_status",
  "dpd_days",
  "outstanding_live",
];

/** A field is pickable unless it is an ID number and full ID numbers are not switched on. */
const pickable = (f: ExportField, includeIds: boolean) => includeIds || !f.sensitive;

function GroupCheckbox({
  checked,
  indeterminate,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  indeterminate: boolean;
  onChange: (next: boolean) => void;
  label: React.ReactNode;
  disabled?: boolean;
}) {
  const ref = React.useRef<HTMLInputElement>(null);
  React.useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <label className="flex min-h-[36px] items-center gap-2 font-semibold text-navy">
      <input
        ref={ref}
        type="checkbox"
        className="h-4 w-4 accent-[#0C2540]"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}

/**
 * Step 2 of the customer export: pick the columns. Searchable, collapsible by group, with Select all
 * and Clear. ID numbers (full Aadhaar, PAN, UAN, bank accounts) sit behind their own amber switch:
 * they start unticked and Select all leaves them out unless that switch is on.
 */
export function CustomerExportFields({
  catalog,
  selected,
  onSelected,
  includeIds,
  onIncludeIds,
}: {
  catalog: ExportCatalog;
  selected: Set<string>;
  onSelected: (next: Set<string>) => void;
  includeIds: boolean;
  onIncludeIds: (next: boolean) => void;
}) {
  const [search, setSearch] = React.useState("");
  const [open, setOpen] = React.useState<Set<string>>(() => new Set([catalog.groups[0]]));
  const needle = search.trim().toLowerCase();

  const byGroup = React.useMemo(() => {
    const m = new Map<string, ExportField[]>();
    for (const g of catalog.groups) m.set(g, []);
    for (const f of catalog.fields) m.get(f.group)?.push(f);
    return m;
  }, [catalog]);

  const toggle = (keys: string[], on: boolean) => {
    const next = new Set(selected);
    for (const k of keys) {
      if (on) next.add(k);
      else next.delete(k);
    }
    onSelected(next);
  };

  const setIds = (on: boolean) => {
    onIncludeIds(on);
    if (!on) onSelected(new Set([...selected].filter((k) => !catalog.fields.find((f) => f.key === k)?.sensitive)));
  };

  const selectAll = () => onSelected(new Set(catalog.fields.filter((f) => pickable(f, includeIds)).map((f) => f.key)));

  const toggleOpen = (g: string) => {
    const next = new Set(open);
    if (next.has(g)) next.delete(g);
    else next.add(g);
    setOpen(next);
  };

  const idCount = catalog.fields.filter((f) => f.sensitive).length;
  const visibleGroups = catalog.groups.filter((g) => (byGroup.get(g) ?? []).some((f) => !needle || `${f.label} ${f.key} ${g}`.toLowerCase().includes(needle)));

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm text-muted">
          <span className="font-semibold text-navy">{selected.size}</span> of {catalog.fields.length} fields selected
        </div>
        <div className="flex gap-2">
          <button type="button" onClick={selectAll} className="min-h-[36px] rounded border border-navy px-3 text-sm font-semibold text-navy hover:bg-grey-50">
            Select all
          </button>
          <button type="button" onClick={() => onSelected(new Set())} className="min-h-[36px] rounded border border-line px-3 text-sm hover:bg-grey-50">
            Clear
          </button>
        </div>
      </div>

      <input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        aria-label="Search fields"
        placeholder="Search any field, for example salary or IFSC"
        className="min-h-[42px] w-full rounded border border-line px-3 text-sm"
      />

      <div className="rounded-lg border border-amber-400 bg-amber-50 p-3 text-amber-950">
        <label className="flex min-h-[36px] items-center gap-2 font-bold">
          <input type="checkbox" className="h-4 w-4 accent-[#0C2540]" checked={includeIds} onChange={(e) => setIds(e.target.checked)} />
          Include full ID numbers
        </label>
        <p className="pl-6 text-[13px]">
          Aadhaar, PAN and bank account numbers in full. Off by default and left out of Select all. Each export records who pulled it.
          {includeIds ? ` ${idCount} ID fields are now available below.` : ""}
        </p>
      </div>

      {visibleGroups.length === 0 ? (
        <p className="rounded border border-line p-4 text-sm text-muted">No field matches that search.</p>
      ) : (
        <div className="space-y-2">
          {visibleGroups.map((g) => {
            const all = byGroup.get(g) ?? [];
            const shown = all.filter((f) => !needle || `${f.label} ${f.key} ${g}`.toLowerCase().includes(needle));
            const usable = all.filter((f) => pickable(f, includeIds));
            const on = usable.filter((f) => selected.has(f.key)).length;
            const expanded = needle ? true : open.has(g);
            return (
              <div key={g} className="rounded-lg border border-line bg-white px-3 py-1">
                <div className="flex items-center justify-between gap-2">
                  <GroupCheckbox
                    checked={usable.length > 0 && on === usable.length}
                    indeterminate={on > 0 && on < usable.length}
                    disabled={usable.length === 0}
                    onChange={(v) => toggle(usable.map((f) => f.key), v)}
                    label={
                      <>
                        {g} <span className="font-normal text-muted">({on}/{all.length})</span>
                      </>
                    }
                  />
                  <button
                    type="button"
                    onClick={() => toggleOpen(g)}
                    aria-expanded={expanded}
                    aria-label={`${expanded ? "Collapse" : "Expand"} ${g}`}
                    className="flex min-h-[36px] min-w-[36px] items-center justify-center rounded text-muted hover:text-ink"
                  >
                    {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                  </button>
                </div>
                {expanded && (
                  <div className="grid gap-x-4 gap-y-1 pb-3 pl-6 sm:grid-cols-2 lg:grid-cols-3">
                    {shown.map((f) => {
                      const locked = !pickable(f, includeIds);
                      return (
                        <label key={f.key} className={`flex min-h-[32px] items-start gap-2 text-sm ${locked ? "text-muted" : ""}`}>
                          <input
                            type="checkbox"
                            className="mt-1 h-[15px] w-[15px] shrink-0 accent-[#0C2540]"
                            checked={selected.has(f.key)}
                            disabled={locked}
                            onChange={(e) => toggle([f.key], e.target.checked)}
                          />
                          <span>
                            {f.label}
                            {f.sensitive && (
                              <span className="ml-1 inline-flex items-center gap-0.5 text-[11px] font-semibold text-amber-800" title="Full ID number">
                                <Lock size={10} /> ID
                              </span>
                            )}
                          </span>
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
