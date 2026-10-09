"use client";

import * as React from "react";
import { Download, MapPin, Search, X } from "lucide-react";
import { dashboardApi, paiseToINR, type DashGeoRow } from "@/lib/api/applications";
import { exportCsv } from "@/lib/export/exporters";
import { formatInrCompact } from "@/lib/staff/format-inr";
import {
  SEGMENTS,
  SEGMENT_BY_KEY,
  classifySegment,
  normaliseState,
  stateLabel,
  type LocationSegment,
} from "@/lib/staff/india-states";
import { cn } from "@/lib/utils";
import { ChartCard } from "../chart-card";
import { ProgressBar } from "../chart-parts";
import { PCT_TONE_COLOR, STAT_CHIP, TONE_SOLID, fmtPct, pctTone } from "../colors";
import { nf } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { Segmented, StatChip } from "../ui-bits";
import { IndiaMap, type MapStateStat } from "./india-map";

type Level = "STATE" | "PINCODE";
const ROWS_SHOWN = 100;

interface Loc extends DashGeoRow {
  canonical: string;
  segment: LocationSegment;
}

/** Admin "Map": choropleth + segment chips + location table. */
export function MapTab({ params, open }: DashTabProps) {
  const q = useDashQuery("geo", params, [], () => dashboardApi.geo(params));
  const [level, setLevel] = React.useState<Level>("STATE");
  const [state, setState] = React.useState<string | null>(null);
  const [seg, setSeg] = React.useState<LocationSegment | null>(null);
  const [search, setSearch] = React.useState("");
  const [all, setAll] = React.useState(false);

  const locs = React.useMemo<Loc[]>(() => {
    const src = (level === "STATE" ? q.data?.states : q.data?.pincodes) ?? [];
    return src
      .map((r) => ({
        ...r,
        canonical: level === "STATE" ? (normaliseState(r.label) ?? stateLabel(r.state)) : stateLabel(r.state),
        segment: classifySegment(r, level),
      }))
      .sort((a, b) => b.principalPaise - a.principalPaise);
  }, [q.data, level]);

  // Map stats: always state-level, merged by canonical name (several backend spellings can collapse).
  const stats = React.useMemo<MapStateStat[]>(() => {
    const m = new Map<string, { cases: number; principal: number; closed: number; due: number }>();
    for (const r of q.data?.states ?? []) {
      const k = normaliseState(r.label) ?? stateLabel(r.state);
      const a = m.get(k) ?? { cases: 0, principal: 0, closed: 0, due: 0 };
      a.cases += r.cases;
      a.principal += r.principalPaise;
      a.closed += r.closedCount;
      a.due += r.dueCount;
      m.set(k, a);
    }
    return [...m].map(([k, a]) => ({
      state: k,
      cases: a.cases,
      principalPaise: a.principal,
      closeRate: a.due > 0 ? a.closed / a.due : null,
    }));
  }, [q.data]);

  const term = search.trim().toLowerCase();
  const inScope = locs.filter(
    (l) =>
      (!state || l.canonical === state) &&
      (!term || `${l.label} ${l.key} ${l.state}`.toLowerCase().includes(term)),
  );
  const shownAll = seg ? inScope.filter((l) => l.segment === seg) : inScope;
  const shown = all ? shownAll : shownAll.slice(0, ROWS_SHOWN);
  const countOf = (k: LocationSegment) => inScope.filter((l) => l.segment === k).length;

  const totalCases = locs.reduce((n, l) => n + l.cases, 0);
  const totalPaise = locs.reduce((n, l) => n + l.principalPaise, 0);

  const openRow = (l: Loc) =>
    open({ metric: level, key: l.key, title: `${level === "STATE" ? "State" : "Pincode"} · ${l.label}${level === "PINCODE" ? ` (${l.state})` : ""}` });

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <ChartCard
        title={
          <span className="flex items-center gap-2">
            <MapPin size={16} aria-hidden /> India — principal by state
          </span>
        }
        info="Loans disbursed in the period, shaded emerald (little) to navy (most) by principal. Click a state to filter the table; click it again to clear."
        accent={TONE_SOLID.teal}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={!q.data}
        controls={
          state ? (
            <button
              type="button"
              onClick={() => setState(null)}
              className="flex items-center gap-1 rounded-full bg-navy px-2.5 py-1 text-[11px] font-semibold text-white"
            >
              {state} <X size={11} aria-hidden /> <span className="sr-only">Clear state filter</span>
            </button>
          ) : null
        }
      >
        <IndiaMap stats={stats} selected={state} onSelect={setState} />
      </ChartCard>

      <ChartCard
        title="Locations"
        info="Each location is classed by size and close rate (closed ÷ due). Thresholds live in lib/staff/india-states.ts."
        accent={TONE_SOLID.orange}
        controls={
          <>
            <Segmented
              label="Location level"
              value={level}
              onChange={(v) => {
                setLevel(v);
                setSeg(null);
                setAll(false);
              }}
              options={[
                ["STATE", "State"],
                ["PINCODE", "Pincode"],
              ]}
            />
            <button
              type="button"
              disabled={shownAll.length === 0}
              onClick={() =>
                exportCsv(
                  `dashboard-locations-${level.toLowerCase()}`,
                  [
                    { header: level === "STATE" ? "State" : "Pincode", value: (l: Loc) => l.label },
                    { header: "State", value: (l: Loc) => l.canonical },
                    { header: "Segment", value: (l: Loc) => SEGMENT_BY_KEY[l.segment].label },
                    { header: "Cases", value: (l: Loc) => l.cases },
                    { header: "Principal (INR)", value: (l: Loc) => l.principalPaise / 100 },
                    { header: "Closed", value: (l: Loc) => l.closedCount },
                    { header: "Due", value: (l: Loc) => l.dueCount },
                    { header: "Close rate %", value: (l: Loc) => (l.closeRate == null ? "" : (l.closeRate * 100).toFixed(1)) },
                  ],
                  shownAll,
                )
              }
              className="flex items-center gap-1 rounded bg-navy px-2.5 py-1 text-[11px] font-semibold text-white disabled:opacity-50"
            >
              <Download size={12} aria-hidden /> Export CSV
            </button>
          </>
        }
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={!q.data}
      >
        <div className="mb-3 flex flex-wrap gap-2">
          <StatChip {...STAT_CHIP.blue} label="Locations" value={nf(locs.length)} />
          <StatChip {...STAT_CHIP.amber} label="Cases" value={nf(totalCases)} />
          <StatChip {...STAT_CHIP.green} label="Principal" value={formatInrCompact(totalPaise)} />
        </div>

        <label className="relative mb-3 flex items-center">
          <Search size={13} aria-hidden className="absolute left-2 text-muted" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search pincode, city, state"
            aria-label="Search locations"
            className="w-full rounded border border-line py-1.5 pl-7 pr-2 text-xs"
          />
        </label>

        <div className="mb-3 flex flex-wrap gap-1.5" role="group" aria-label="Segment filter">
          <button
            type="button"
            aria-pressed={seg == null}
            onClick={() => setSeg(null)}
            className={cn("rounded-full border px-3 py-1 text-xs font-semibold", seg == null ? "border-navy bg-navy text-white" : "border-line text-ink")}
          >
            All · {nf(inScope.length)}
          </button>
          {SEGMENTS.map((s) => (
            <button
              key={s.key}
              type="button"
              aria-pressed={seg === s.key}
              onClick={() => setSeg(seg === s.key ? null : s.key)}
              className={cn(
                "flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition hover:-translate-y-0.5",
                seg === s.key ? "border-navy bg-grey-100 shadow" : "border-line",
              )}
            >
              <span aria-hidden className="inline-block h-2 w-2 rounded-full" style={{ background: s.color }} />
              {s.label} <b className="tabular-nums text-muted">{nf(countOf(s.key))}</b>
            </button>
          ))}
        </div>

        {shownAll.length === 0 ? (
          <p className="m-0 py-6 text-center text-sm text-muted">No locations match.</p>
        ) : (
          <div className="staff-table-scroll max-h-[28rem] overflow-y-auto">
            <table className="w-full min-w-[26rem] text-left text-xs">
              <thead className="sticky top-0 bg-navy text-white">
                <tr>
                  <th className="px-3 py-2 font-semibold">Location</th>
                  <th className="px-3 py-2 text-right font-semibold">Cases</th>
                  <th className="px-3 py-2 text-right font-semibold">Amt</th>
                  <th className="px-3 py-2 font-semibold">Close</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((l) => {
                  const info = SEGMENT_BY_KEY[l.segment];
                  const tone = pctTone(l.closeRate);
                  return (
                    <tr
                      key={`${l.key}-${l.state}`}
                      tabIndex={0}
                      onClick={() => openRow(l)}
                      onKeyDown={(e) => e.key === "Enter" && openRow(l)}
                      title={`${l.label}: ${nf(l.cases)} cases · ${paiseToINR(l.principalPaise)} · close ${fmtPct(l.closeRate, 1)} · ${info.label}`}
                      className="cursor-pointer border-b border-line hover:bg-grey-50"
                    >
                      <td className="px-3 py-1.5">
                        <span className="flex items-center gap-2">
                          <span aria-hidden className="inline-block h-2 w-2 shrink-0 rounded-full" style={{ background: info.color }} />
                          <span>
                            <span className="block font-semibold text-ink">{l.label}</span>
                            <span className="block text-[10px] text-muted">
                              {level === "PINCODE" ? `${l.key} · ${l.state}` : info.label}
                            </span>
                          </span>
                        </span>
                      </td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{nf(l.cases)}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{formatInrCompact(l.principalPaise)}</td>
                      <td className="w-28 px-3 py-1.5">
                        <span className="flex items-center gap-1.5">
                          <ProgressBar ratio={l.closeRate} color={PCT_TONE_COLOR[tone]} className="w-14" />
                          <span className="font-bold tabular-nums" style={{ color: PCT_TONE_COLOR[tone] }}>
                            {fmtPct(l.closeRate, 1)}
                          </span>
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {shownAll.length > shown.length && (
              <button
                type="button"
                onClick={() => setAll(true)}
                className="my-2 w-full rounded border border-line py-1.5 text-xs font-semibold text-navy hover:bg-grey-100"
              >
                Show all {nf(shownAll.length)} locations
              </button>
            )}
          </div>
        )}
      </ChartCard>
    </div>
  );
}
