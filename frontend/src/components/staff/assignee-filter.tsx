"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Select } from "@/components/ui";
import { collectionsApi, paiseToINR } from "@/lib/api/applications";

export type AssigneeOption = {
  /** `null` = the Unassigned bucket (when it comes from a chip strip). */
  id: number | null;
  name: string;
  count?: number;
  outstandingPaise?: number;
};

/** ACTIVE collections executives — same query key as collections-assign.tsx so the cache is shared. */
export function useCollectionOfficerOptions() {
  return useQuery({
    queryKey: ["collection-officers"],
    queryFn: collectionsApi.listOfficers,
    staleTime: 60_000,
  });
}

/**
 * Assignee filter: `value` null = All, 0 = Unassigned, else a staff id. Optionally a chip strip
 * (count + outstanding) under the select; clicking the active chip clears the filter.
 */
export function AssigneeFilter({
  label,
  value,
  onChange,
  options,
  strip,
  stripHint,
}: {
  label: string;
  value: number | null;
  onChange: (v: number | null) => void;
  options: AssigneeOption[];
  strip?: boolean;
  stripHint?: string;
}) {
  // Unassigned is the fixed `0` option, so a null-id entry is only its count, not a second option.
  const named = options.filter((o) => o.id != null);
  const unassigned = options.find((o) => o.id == null);
  const pick = (id: number) => onChange(value === id ? null : id);
  const chipText = (o: AssigneeOption) =>
    `${o.name} (${o.count ?? 0})${o.outstandingPaise != null ? ` · ${paiseToINR(o.outstandingPaise)}` : ""}`;

  return (
    <div className={strip ? "w-full" : "min-w-[11rem]"}>
      <div className="min-w-[11rem] max-w-[16rem]">
      <Select
        label={label}
        className="!mb-0"
        value={value == null ? "" : String(value)}
        onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
      >
        <option value="">All</option>
        <option value="0">
          Unassigned{unassigned?.count != null ? ` · ${unassigned.count}` : ""}
        </option>
        {named.map((o) => (
          <option key={o.id} value={o.id as number}>
            {o.name}
            {o.count != null ? ` · ${o.count}` : ""}
          </option>
        ))}
      </Select>
      </div>
      {strip && (
        <div className="mt-1.5 flex flex-wrap gap-1.5" title={stripHint}>
          {unassigned && (
            <button type="button" className={`cal-preset${value === 0 ? " on" : ""}`} onClick={() => pick(0)}>
              {chipText({ ...unassigned, name: "Unassigned" })}
            </button>
          )}
          {named.map((o) => (
            <button
              key={o.id}
              type="button"
              className={`cal-preset${value === o.id ? " on" : ""}`}
              onClick={() => pick(o.id as number)}
            >
              {chipText(o)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
