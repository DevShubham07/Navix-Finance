"use client";

import * as React from "react";
import { Cell, Pie, PieChart } from "recharts";
import { cn } from "@/lib/utils";
import { NAVY, PCT_HIGH, PCT_LOW, PCT_TONE_COLOR, PCT_TONE_TINT, SERIES, WHITE, fmtPct, pctTone } from "./colors";
import { nf } from "./fmt";

export interface GaugeMonth {
  key: string;
  label: string;
  /** 0..1, or null when it cannot be measured. */
  pct: number | null;
  loans: number;
}

const W = 260;
const H = 150;
const CX = W / 2;
const CY = 132;
const INNER = 82;
const OUTER = 118;

/** Red -> sun -> mint arc split on the shared percent-tone thresholds. */
const ARCS = [
  { name: "low", value: PCT_LOW * 100, fill: SERIES.bad },
  { name: "mid", value: (PCT_HIGH - PCT_LOW) * 100, fill: SERIES.pending },
  { name: "high", value: (1 - PCT_HIGH) * 100, fill: SERIES.good },
];

/**
 * Semicircle gauge with a needle dot at the selected month's value. Month chips sit below it:
 * hover shows that month's loans and %, click moves the gauge there. Clicking the centre figure
 * calls `onOpen` (the records drawer for the selected month).
 */
export function Gauge({
  months,
  selected,
  onSelect,
  onOpen,
  caption,
}: {
  months: GaugeMonth[];
  selected: string | undefined;
  onSelect: (key: string) => void;
  onOpen?: (key: string) => void;
  /** e.g. "of loans due were closed before their due date". */
  caption?: string;
}) {
  const current = months.find((m) => m.key === selected) ?? months[months.length - 1];
  const value = current?.pct ?? 0;
  const angle = Math.PI * (1 - Math.max(0, Math.min(1, value)));
  const dotR = (INNER + OUTER) / 2;
  const dx = CX + dotR * Math.cos(angle);
  const dy = CY - dotR * Math.sin(angle);

  return (
    <div>
      <div className="relative mx-auto" style={{ width: W, height: H }}>
        <PieChart width={W} height={H}>
          <Pie
            data={ARCS}
            dataKey="value"
            startAngle={180}
            endAngle={0}
            cx={CX}
            cy={CY}
            innerRadius={INNER}
            outerRadius={OUTER}
            stroke={WHITE}
            strokeWidth={3}
            cornerRadius={5}
            isAnimationActive={false}
          >
            {ARCS.map((a) => (
              <Cell key={a.name} fill={a.fill} />
            ))}
          </Pie>
        </PieChart>
        {current?.pct != null && (
          <svg width={W} height={H} className="pointer-events-none absolute left-0 top-0" aria-hidden>
            <circle cx={dx} cy={dy} r={9} fill={WHITE} stroke={NAVY} strokeWidth={3} />
          </svg>
        )}
        <button
          type="button"
          disabled={!onOpen || !current}
          onClick={() => current && onOpen?.(current.key)}
          aria-label={`${current?.label ?? ""}: ${fmtPct(current?.pct, 2)} across ${nf(current?.loans)} loans. View loans.`}
          className="absolute left-1/2 top-[62px] flex -translate-x-1/2 flex-col items-center rounded px-2 text-center hover:bg-grey-100 disabled:cursor-default"
        >
          <span className="text-[11px] text-muted">{nf(current?.loans)} loans</span>
          <span className="figure-display text-[2.2rem] text-ink">{fmtPct(current?.pct, 2)}</span>
          <span className="text-[11px] text-muted">{current?.label ?? ""}</span>
        </button>
      </div>
      {caption && <p className="m-0 mt-1 text-center text-xs text-muted">{caption}</p>}

      <div className="mt-3 flex flex-wrap justify-center gap-2">
        {months.map((m) => {
          const tone = pctTone(m.pct);
          const on = m.key === current?.key;
          return (
            <button
              key={m.key}
              type="button"
              aria-pressed={on}
              onClick={() => onSelect(m.key)}
              title={`${m.label}: ${nf(m.loans)} loans · ${fmtPct(m.pct, 2)}`}
              className={cn(
                "min-w-[5.5rem] rounded-xl border px-3 py-1.5 text-center shadow-xs transition hover:-translate-y-0.5 hover:shadow-md",
                on ? "border-navy" : "border-line",
              )}
              style={{ background: on ? PCT_TONE_TINT[tone] : WHITE }}
            >
              <span className="block text-[11px] font-semibold text-muted">{m.label}</span>
              <span className="block text-sm font-bold" style={{ color: PCT_TONE_COLOR[tone] }}>
                {fmtPct(m.pct, 2)}
              </span>
              <span className="block text-[10px] text-slate">{nf(m.loans)} loans</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
