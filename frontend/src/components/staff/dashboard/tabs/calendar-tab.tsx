"use client";

import * as React from "react";
import { CalendarDays, ChevronLeft, ChevronRight } from "lucide-react";
import { dashboardApi, paiseToINR, type DashCalendarMode, type DashMetric } from "@/lib/api/applications";
import { formatInrCompact } from "@/lib/staff/format-inr";
import { cn } from "@/lib/utils";
import { ChartCard } from "../chart-card";
import { NAVY, STAT_CHIP, SUNDAY_STRIPE, TONE_SOLID, WHITE, hexToRgb } from "../colors";
import { fmtDayLong, fmtMonth, nf, todayIso } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { Segmented, StatChip, selectCls } from "../ui-bits";

const rgbOf = (hex: string) => hexToRgb(hex).join(",");

const MODES: Record<DashCalendarMode, { label: string; color: string; rgb: string; metric: DashMetric; noun: string }> = {
  DUE: { label: "Due Amount", color: NAVY, rgb: rgbOf(NAVY), metric: "CALENDAR_DUE", noun: "due" },
  DISBURSED: { label: "Disbursed Amount", color: TONE_SOLID.emerald, rgb: rgbOf(TONE_SOLID.emerald), metric: "CALENDAR_DISBURSED", noun: "disbursed" },
  BIRTHDAY: { label: "Customer Birthday", color: TONE_SOLID.violet, rgb: rgbOf(TONE_SOLID.violet), metric: "CALENDAR_BIRTHDAY", noun: "birthdays" },
};

/** Five heat steps, light to strong; alpha over white keeps the dark text legible on every step. */
const HEAT_ALPHA = [0.07, 0.14, 0.24, 0.36, 0.5] as const;
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

/** 0 (no data) or 1..5. `max` is the busiest day of the month. */
export function heatStep(value: number, max: number): number {
  if (value <= 0 || max <= 0) return 0;
  return Math.min(5, Math.max(1, Math.ceil((value / max) * 5)));
}

function shiftMonth(m: string, by: number): string {
  const [y, mo] = m.split("-").map(Number);
  const d = new Date(y, mo - 1 + by, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** Admin "Calendar": month grid with Due / Disbursed / Birthday modes. Sundays are styled, never blocked. */
export function CalendarTab({ params, open }: DashTabProps) {
  const [month, setMonth] = React.useState(todayIso().slice(0, 7));
  const [mode, setMode] = React.useState<DashCalendarMode>("DUE");
  const q = useDashQuery("calendar", params, [month, mode], () => dashboardApi.calendar(params, month, mode));
  const cal = q.data;
  const cfg = MODES[mode];
  const today = todayIso();

  const byDate = new Map((cal?.days ?? []).map((d) => [d.date, d]));
  const value = (d: { count: number; amountPaise: number | null }) => (mode === "BIRTHDAY" ? d.count : (d.amountPaise ?? d.count));
  const max = Math.max(0, ...(cal?.days ?? []).map(value));

  const [y, mo] = month.split("-").map(Number);
  const lead = new Date(y, mo - 1, 1).getDay();
  const daysInMonth = new Date(y, mo, 0).getDate();
  const cells: (string | null)[] = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  const years = Array.from({ length: 7 }, (_, i) => Number(today.slice(0, 4)) - 3 + i);

  return (
    <ChartCard
      title={
        <span className="flex items-center gap-2">
          <CalendarDays size={16} aria-hidden /> Calendar View
        </span>
      }
      subtitle="Sundays are shaded for orientation only — loans can fall due on any day."
      info="Due: loans by due date (count and total repayable). Disbursed: loans by disbursal date (count and principal). Birthday: distinct customers with a disbursed loan, by birthday."
      accent={cfg.color}
      controls={
        <Segmented
          label="Calendar mode"
          value={mode}
          onChange={setMode}
          options={(Object.keys(MODES) as DashCalendarMode[]).map((k) => [k, MODES[k].label] as const)}
          colors={{ DUE: MODES.DUE.color, DISBURSED: MODES.DISBURSED.color, BIRTHDAY: MODES.BIRTHDAY.color }}
        />
      }
      loading={q.isLoading}
      error={q.error}
      onRetry={() => void q.refetch()}
    >
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          aria-label="Previous month"
          onClick={() => setMonth(shiftMonth(month, -1))}
          className="rounded border border-line p-1 hover:bg-grey-100"
        >
          <ChevronLeft size={14} />
        </button>
        <span className="min-w-[8.5rem] text-center font-serif text-base font-semibold text-navy">{fmtMonth(month)}</span>
        <button
          type="button"
          aria-label="Next month"
          onClick={() => setMonth(shiftMonth(month, 1))}
          className="rounded border border-line p-1 hover:bg-grey-100"
        >
          <ChevronRight size={14} />
        </button>
        <select
          aria-label="Year"
          className={selectCls}
          value={y}
          onChange={(e) => setMonth(`${e.target.value}-${String(mo).padStart(2, "0")}`)}
        >
          {years.map((yr) => (
            <option key={yr} value={yr}>
              {yr}
            </option>
          ))}
        </select>
        <span className="ml-auto flex flex-wrap gap-2">
          <StatChip {...STAT_CHIP.blue} label="Days with data" value={nf(cal?.daysWithData)} />
          <StatChip {...STAT_CHIP.amber} label={mode === "BIRTHDAY" ? "Customers" : "Total cases"} value={nf(cal?.totalCount)} />
          {mode !== "BIRTHDAY" && (
            <StatChip {...STAT_CHIP.green} label="Total amount" value={paiseToINR(cal?.totalAmountPaise)} />
          )}
        </span>
      </div>

      <div className="staff-table-scroll">
        <div className="grid min-w-[40rem] grid-cols-7 gap-1.5" role="grid" aria-label={`${fmtMonth(month)} ${cfg.label}`}>
          {WEEKDAYS.map((w) => (
            <div key={w} className="pb-1 text-center text-[11px] font-semibold uppercase tracking-wide text-muted">
              {w}
            </div>
          ))}
          {cells.map((date, i) => {
            if (!date) return <div key={`pad-${i}`} aria-hidden />;
            const day = Number(date.slice(8));
            const sunday = i % 7 === 0;
            const rec = byDate.get(date);
            const step = rec ? heatStep(value(rec), max) : 0;
            const isToday = date === today;
            const label = rec
              ? `${fmtDayLong(date)}: ${nf(rec.count)} ${cfg.noun}${rec.amountPaise != null ? `, ${paiseToINR(rec.amountPaise)}` : ""}`
              : `${fmtDayLong(date)}: nothing ${cfg.noun}`;
            return (
              <button
                key={date}
                type="button"
                role="gridcell"
                disabled={!rec}
                aria-label={label}
                title={label}
                onClick={() =>
                  rec &&
                  open({
                    metric: cfg.metric,
                    key: date,
                    title: `${cfg.label} · ${fmtDayLong(date)}`,
                    range: { from: date, to: date },
                  })
                }
                className={cn(
                  "flex min-h-[4.5rem] flex-col items-center rounded-lg border px-1 py-1.5 text-center transition",
                  rec ? "cursor-pointer hover:-translate-y-0.5 hover:shadow-lg" : "cursor-default",
                  isToday ? "ring-2 ring-offset-1" : "",
                  step === 0 ? "border-line" : "border-transparent",
                )}
                style={{
                  background: step > 0 ? `rgba(${cfg.rgb},${HEAT_ALPHA[step - 1]})` : sunday ? SUNDAY_STRIPE : WHITE,
                  ...(isToday ? ({ "--tw-ring-color": cfg.color } as React.CSSProperties) : {}),
                }}
              >
                <span className="self-start pl-1 text-[10px] font-semibold text-muted">{day}</span>
                {rec && (
                  <>
                    <span className="text-sm font-bold leading-tight text-ink tabular-nums">{nf(rec.count)}</span>
                    {rec.amountPaise != null && (
                      <span className="text-[10px] font-medium tabular-nums" style={{ color: cfg.color }}>
                        {formatInrCompact(rec.amountPaise)}
                      </span>
                    )}
                  </>
                )}
              </button>
            );
          })}
        </div>
      </div>

      <div className="mt-3 flex items-center gap-2 text-[11px] text-muted" aria-hidden>
        Less
        {HEAT_ALPHA.map((a) => (
          <span key={a} className="inline-block h-3 w-6 rounded" style={{ background: `rgba(${cfg.rgb},${a})` }} />
        ))}
        More
      </div>
    </ChartCard>
  );
}
