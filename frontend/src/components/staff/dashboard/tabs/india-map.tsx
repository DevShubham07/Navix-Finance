"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { geoMercator, geoPath } from "d3-geo";
import { feature } from "topojson-client";
import type { Topology, GeometryCollection } from "topojson-specification";
import type { Feature, FeatureCollection, Geometry } from "geojson";
import { Maximize2, Minimize2 } from "lucide-react";
import { paiseToINR } from "@/lib/api/applications";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { cn } from "@/lib/utils";
import { MAP, fmtPct, hexToRgb } from "../colors";
import { nf } from "../fmt";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui";

export interface MapStateStat {
  /** Canonical state name (ST_NM). */
  state: string;
  cases: number;
  principalPaise: number;
  closeRate: number | null;
}

const TOPO_URL = "/maps/india-states.topo.json";
const W = 640;
const H = 700;
/** Colour ramp: no loans (pale grey) then emerald -> navy by principal. */
const NO_DATA = MAP.noData;
const RAMP_FROM = hexToRgb(MAP.rampFrom);
const RAMP_TO = hexToRgb(MAP.rampTo);
const rampColor = (t: number) =>
  `rgb(${RAMP_FROM.map((c, i) => Math.round(c + (RAMP_TO[i] - c) * t)).join(",")})`;

type StateFeature = Feature<Geometry, { ST_NM: string }>;

function useIndiaTopology() {
  return useQuery({
    queryKey: ["staff-dashboard-india-topology"],
    queryFn: async (): Promise<FeatureCollection<Geometry, { ST_NM: string }>> => {
      const res = await fetch(TOPO_URL);
      if (!res.ok) throw new Error(`Map data failed to load (${res.status})`);
      const topo = (await res.json()) as Topology<{ states: GeometryCollection<{ ST_NM: string }> }>;
      return feature(topo, topo.objects.states) as FeatureCollection<Geometry, { ST_NM: string }>;
    },
    staleTime: Infinity,
    retry: 1,
  });
}

/**
 * Choropleth of India by principal (emerald -> navy). Hover = state, cases, ₹, close %; click selects
 * the state (click it again to clear). Boundaries: DataMeet, CC BY 4.0.
 */
export function IndiaMap({
  stats,
  selected,
  onSelect,
}: {
  stats: MapStateStat[];
  selected: string | null;
  onSelect: (state: string | null) => void;
}) {
  const topo = useIndiaTopology();
  const [hover, setHover] = React.useState<{ name: string; x: number; y: number } | null>(null);
  const [full, setFull] = React.useState(false);
  const boxRef = React.useRef<HTMLDivElement>(null);

  const byState = React.useMemo(() => new Map(stats.map((s) => [s.state, s])), [stats]);
  // sqrt scale: a few big states must not flatten every other state to the palest shade.
  const maxP = Math.max(1, ...stats.map((s) => s.principalPaise));

  const paths = React.useMemo(() => {
    if (!topo.data) return [];
    const projection = geoMercator().fitSize([W, H], topo.data);
    const gen = geoPath(projection);
    return (topo.data.features as StateFeature[]).map((f) => ({ name: f.properties.ST_NM, d: gen(f) ?? "" }));
  }, [topo.data]);

  React.useEffect(() => {
    if (!full) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setFull(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [full]);

  const tip = hover ? byState.get(hover.name) : undefined;

  return (
    <div className={cn(full ? "fixed inset-0 z-50 flex flex-col bg-white p-4" : "relative")}>
      <button
        type="button"
        onClick={() => setFull((f) => !f)}
        aria-label={full ? "Exit full screen" : "Full screen"}
        className="absolute right-2 top-2 z-10 flex items-center gap-1 rounded-full border border-line bg-paper px-2.5 py-1 text-[11px] font-medium text-ink shadow-pill hover:bg-grey-100"
      >
        {full ? <Minimize2 size={12} /> : <Maximize2 size={12} />} {full ? "Exit" : "Full screen"}
      </button>

      {topo.isError ? (
        <ErrorState error={topo.error} onRetry={() => void topo.refetch()} className="py-8" />
      ) : topo.isLoading ? (
        <Skeleton variant="line" rows={8} />
      ) : paths.length === 0 ? (
        <EmptyState title="Map not available" className="py-8" />
      ) : (
        <div ref={boxRef} className={cn("relative mx-auto w-full", full ? "min-h-0 flex-1" : "max-w-[34rem]")}>
          <svg
            viewBox={`0 0 ${W} ${H}`}
            role="group"
            aria-label="India map: principal disbursed by state"
            className={cn("w-full", full ? "h-full" : "h-auto")}
            onMouseLeave={() => setHover(null)}
          >
            {paths.map((p) => {
              const s = byState.get(p.name);
              const on = selected === p.name;
              const fill = s && s.principalPaise > 0 ? rampColor(Math.sqrt(s.principalPaise / maxP)) : NO_DATA;
              return (
                <path
                  key={p.name}
                  d={p.d}
                  fill={fill}
                  stroke={on ? MAP.highlight : MAP.border}
                  strokeWidth={on ? 2.5 : 0.7}
                  tabIndex={0}
                  role="button"
                  aria-pressed={on}
                  aria-label={`${p.name}: ${nf(s?.cases ?? 0)} cases, ${paiseToINR(s?.principalPaise ?? 0)}, close ${fmtPct(s?.closeRate, 1)}`}
                  className="cursor-pointer outline-none transition-opacity hover:opacity-80 focus-visible:stroke-[color:var(--map-highlight)]"
                  style={{ "--map-highlight": MAP.highlight } as React.CSSProperties}
                  onClick={() => onSelect(on ? null : p.name)}
                  onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onSelect(on ? null : p.name)}
                  onMouseMove={(e) => {
                    const r = boxRef.current?.getBoundingClientRect();
                    if (r) setHover({ name: p.name, x: e.clientX - r.left, y: e.clientY - r.top });
                  }}
                />
              );
            })}
          </svg>

          {hover && (
            <div
              role="tooltip"
              className="pointer-events-none absolute z-20 min-w-[11rem] rounded-xl border border-line bg-paper px-3 py-2 text-[11px] text-ink shadow-md"
              style={{ left: Math.min(hover.x + 12, (boxRef.current?.clientWidth ?? 400) - 190), top: hover.y + 12 }}
            >
              <p className="m-0 mb-1 font-semibold text-ink">{hover.name}</p>
              {tip ? (
                <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
                  <dt className="text-slate">Cases</dt>
                  <dd className="m-0 text-right tabular-nums">{nf(tip.cases)}</dd>
                  <dt className="text-slate">Principal</dt>
                  <dd className="m-0 text-right tabular-nums">{paiseToINR(tip.principalPaise)}</dd>
                  <dt className="text-slate">Close rate</dt>
                  <dd className="m-0 text-right tabular-nums">{fmtPct(tip.closeRate, 1)}</dd>
                </dl>
              ) : (
                <p className="m-0 text-slate">No loans in this period</p>
              )}
            </div>
          )}
        </div>
      )}

      <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-muted">
        <span>{formatInrCompact(0)}</span>
        <span
          aria-hidden
          className="inline-block h-2.5 w-40 rounded-full"
          style={{ background: `linear-gradient(90deg, ${rampColor(0)}, ${rampColor(0.5)}, ${rampColor(1)})` }}
        />
        <span>{formatInrCompact(maxP === 1 ? 0 : maxP)}</span>
        <span className="ml-2 inline-flex items-center gap-1">
          <span aria-hidden className="inline-block h-2.5 w-4 rounded" style={{ background: NO_DATA, border: `1px solid ${MAP.noDataBorder}` }} /> no loans
        </span>
        <span className="ml-auto">State boundaries: DataMeet (CC BY 4.0)</span>
      </div>
    </div>
  );
}
