"use client";

import * as React from "react";
import {
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  Tooltip,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  LineChart,
  Line,
} from "recharts";
import type { LeadStats } from "@/lib/api/applications";
import { ChartTooltipContent, chartAxis, chartGrid, chartYAxis } from "@/components/kit";
import { CHART, NAVY, SERIES, TEXT, TONE_SOLID, WHITE } from "@/components/staff/dashboard/colors";

/** Ink for the primary series, ember for the second; the pie walks the chart companions. */
const EMBER = TONE_SOLID.orange;
const PALETTE = [NAVY, EMBER, TONE_SOLID.emerald, TONE_SOLID.amber, TONE_SOLID.violet, TONE_SOLID.sky, SERIES.target];
const TICK = { fontSize: 11, fill: TEXT.axis } as const;
const CURSOR = { fill: CHART.cursor, radius: 10 } as const;
const tip = <ChartTooltipContent />;

/** Admin tracker charts for lead stats. */
export function LeadsTracker({ stats }: { stats: LeadStats }) {
  const statusData = stats.byCallStatus.map((r) => ({
    name: r.status.replace(/_/g, " "),
    value: r.count,
  }));
  const sourceData = stats.bySource.map((r) => ({
    name: r.source.replace(/_/g, " "),
    value: r.count,
  }));
  const ratingData = [1, 2, 3, 4, 5].map((rating) => ({
    rating: `${rating}★`,
    count: stats.byQualityRating.find((r) => r.rating === rating)?.count ?? 0,
  }));
  const staffData = stats.byStaff.map((r) => ({
    name: r.staffName || `#${r.staffId}`,
    count: r.count,
  }));
  const trend = stats.byDay.map((d) => ({
    date: d.date.slice(5),
    created: d.created,
    called: d.called,
  }));

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Total leads" value={String(stats.total)} />
        <StatCard
          label="Avg quality"
          value={
            stats.avgQualityRating != null
              ? `${stats.avgQualityRating.toFixed(1)}★`
              : "—"
          }
        />
        <StatCard label="Unrated" value={String(stats.unratedCount)} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <ChartCard title="Call status">
          {statusData.length === 0 ? (
            <EmptyChart />
          ) : (
            <>
              <ResponsiveContainer width="100%" height={196}>
                <PieChart>
                  <Pie
                    data={statusData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={58}
                    outerRadius={86}
                    paddingAngle={3}
                    cornerRadius={8}
                    stroke="none"
                  >
                    {statusData.map((_, i) => (
                      <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
                    ))}
                  </Pie>
                  <Tooltip content={tip} />
                </PieChart>
              </ResponsiveContainer>
              <DotLegend items={statusData.map((d, i): [string, string] => [d.name, PALETTE[i % PALETTE.length]])} />
            </>
          )}
        </ChartCard>

        <ChartCard title="Source mix">
          {sourceData.length === 0 ? (
            <EmptyChart />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={sourceData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid {...chartGrid} stroke={CHART.grid} />
                <XAxis dataKey="name" {...chartAxis} tick={TICK} />
                <YAxis allowDecimals={false} {...chartYAxis} width={32} tick={TICK} />
                <Tooltip cursor={CURSOR} content={tip} />
                <Bar dataKey="value" fill={NAVY} name="Leads" radius={8} maxBarSize={34} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Lead intake trend">
          {trend.length === 0 ? (
            <EmptyChart />
          ) : (
            <>
              <ResponsiveContainer width="100%" height={196}>
                <LineChart data={trend} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                  <CartesianGrid {...chartGrid} stroke={CHART.grid} />
                  <XAxis dataKey="date" {...chartAxis} tick={TICK} />
                  <YAxis allowDecimals={false} {...chartYAxis} width={32} tick={TICK} />
                  <Tooltip cursor={{ stroke: CHART.grid }} content={tip} />
                  <Line type="monotone" dataKey="created" stroke={NAVY} strokeWidth={2.5} name="Created" dot={false} activeDot={{ r: 5, strokeWidth: 3, stroke: WHITE }} />
                  <Line type="monotone" dataKey="called" stroke={EMBER} strokeWidth={2.5} name="Called" dot={false} activeDot={{ r: 5, strokeWidth: 3, stroke: WHITE }} />
                </LineChart>
              </ResponsiveContainer>
              <DotLegend items={[["Created", NAVY], ["Called", EMBER]]} />
            </>
          )}
        </ChartCard>

        <ChartCard title="Quality ★ distribution">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={ratingData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid {...chartGrid} stroke={CHART.grid} />
              <XAxis dataKey="rating" {...chartAxis} tick={TICK} />
              <YAxis allowDecimals={false} {...chartYAxis} width={32} tick={TICK} />
              <Tooltip cursor={CURSOR} content={tip} />
              <Bar dataKey="count" fill={EMBER} name="Leads" radius={8} maxBarSize={34} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Per-telecaller volume" className="lg:col-span-2">
          {staffData.length === 0 ? (
            <EmptyChart />
          ) : (
            <ResponsiveContainer width="100%" height={Math.max(180, staffData.length * 36)}>
              <BarChart data={staffData} layout="vertical" margin={{ top: 0, right: 8, left: 8, bottom: 0 }}>
                <CartesianGrid horizontal={false} strokeDasharray="0" stroke={CHART.grid} />
                <XAxis type="number" allowDecimals={false} {...chartAxis} tick={TICK} />
                <YAxis type="category" dataKey="name" {...chartAxis} width={120} tick={TICK} />
                <Tooltip cursor={CURSOR} content={tip} />
                <Bar dataKey="count" fill={NAVY} name="Leads" radius={8} maxBarSize={22} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>
      </div>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="surface px-5 py-4">
      <div className="text-xs font-medium text-slate">{label}</div>
      <div className="figure-display mt-2 text-[2.2rem] text-ink">{value}</div>
    </div>
  );
}

/** Dot + label legend (the kit's ChartLegend look) under a chart. */
function DotLegend({ items }: { items: [string, string][] }) {
  return (
    <ul className="m-0 mt-2 flex list-none flex-wrap justify-center gap-x-4 gap-y-1 p-0 text-[11px] text-slate">
      {items.map(([label, color]) => (
        <li key={label} className="flex items-center gap-1.5">
          <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: color }} />
          {label}
        </li>
      ))}
    </ul>
  );
}

function ChartCard({
  title,
  children,
  className,
}: {
  title: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={`surface p-5 ${className ?? ""}`}>
      <h3 className="mb-3 font-sans text-base font-medium tracking-tight text-ink">{title}</h3>
      {children}
    </div>
  );
}

function EmptyChart() {
  return <p className="flex h-[220px] items-center justify-center text-sm text-muted">No data in range</p>;
}
