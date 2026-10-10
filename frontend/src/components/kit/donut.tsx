"use client";

import * as React from "react";
import { Cell, Pie, PieChart, Sector } from "recharts";
import { cn } from "@/lib/utils";
import { ChartContainer } from "./chart";
import { Figure } from "./figures";

/**
 * <DonutChart> — rounded, gapped ring with the total in the centre. Hover/focus a segment and the
 * CENTRE becomes its readout (value, label, share) while the segment lifts — no floating tooltip
 * to collide with the centre figure. A legend of tiles below doubles as the keyboard path.
 */
export interface DonutDatum {
  name: string;
  value: number;
  color: string;
}

interface SectorProps {
  cx: number;
  cy: number;
  innerRadius: number;
  outerRadius: number;
  startAngle: number;
  endAngle: number;
  fill: string;
  cornerRadius?: number;
}

function ActiveSector(props: unknown) {
  const p = props as SectorProps;
  return <Sector {...p} outerRadius={p.outerRadius + 6} innerRadius={p.innerRadius - 2} />;
}

export function DonutChart({
  data,
  totalLabel = "total",
  format = (v) => v.toLocaleString("en-IN"),
  className,
}: {
  data: DonutDatum[];
  totalLabel?: string;
  format?: (v: number) => string;
  className?: string;
}) {
  const [active, setActive] = React.useState<number | null>(null);
  const total = data.reduce((s, d) => s + d.value, 0);
  const cur = active == null ? null : data[active];

  return (
    <div className={className}>
      <div className="relative">
        <ChartContainer config={{}} className="h-52">
          <PieChart margin={{ top: 8, right: 8, bottom: 8, left: 8 }}>
            <Pie
              data={data}
              dataKey="value"
              nameKey="name"
              innerRadius="66%"
              outerRadius="92%"
              paddingAngle={3}
              cornerRadius={8}
              stroke="none"
              activeIndex={active ?? undefined}
              activeShape={ActiveSector}
              onMouseEnter={(_, i) => setActive(i)}
              onMouseLeave={() => setActive(null)}
              animationDuration={900}
              animationEasing="ease-out"
            >
              {data.map((d, i) => (
                <Cell
                  key={d.name}
                  fill={d.color}
                  style={{ transition: "opacity .25s", opacity: active == null || active === i ? 1 : 0.35, outline: "none" }}
                />
              ))}
            </Pie>
          </PieChart>
        </ChartContainer>
        <div className="pointer-events-none absolute inset-0 grid place-items-center text-center" aria-live="polite">
          <div key={cur?.name ?? "total"} className="animate-rise">
            <Figure size="md">{format(cur ? cur.value : total)}</Figure>
            <p className="m-0 text-[10px] text-muted">
              {cur ? `${cur.name} · ${total ? Math.round((cur.value / total) * 100) : 0}%` : totalLabel}
            </p>
          </div>
        </div>
      </div>
      <ul className="m-0 mt-4 grid list-none grid-cols-2 gap-2 p-0">
        {data.map((d, i) => (
          <li key={d.name}>
            <button
              type="button"
              onMouseEnter={() => setActive(i)}
              onMouseLeave={() => setActive(null)}
              onFocus={() => setActive(i)}
              onBlur={() => setActive(null)}
              className={cn(
                "flex w-full items-center justify-between rounded-xl px-3 py-2 text-[11px] transition-colors",
                active === i ? "bg-paper shadow-pill" : "bg-grey-100",
              )}
            >
              <span className="flex items-center gap-1.5 text-muted">
                <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: d.color }} />
                {d.name}
              </span>
              <span className="font-semibold tabular-nums text-ink">{format(d.value)}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
