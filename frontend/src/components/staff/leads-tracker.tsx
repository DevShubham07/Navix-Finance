"use client";

import * as React from "react";
import {
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  Tooltip,
  Legend,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  LineChart,
  Line,
} from "recharts";
import type { LeadStats } from "@/lib/api/applications";

const NAVY = "#0C2540";
const GOLD = "#E9B53A";
const CREAM = "#FDFBF6";
const MUTED = ["#0C2540", "#E9B53A", "#3D5A80", "#98C1D9", "#EE6C4D", "#293241", "#8B7355"];

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
            <ResponsiveContainer width="100%" height={220}>
              <PieChart>
                <Pie
                  data={statusData}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={50}
                  outerRadius={80}
                  paddingAngle={2}
                >
                  {statusData.map((_, i) => (
                    <Cell key={i} fill={MUTED[i % MUTED.length]} />
                  ))}
                </Pie>
                <Tooltip />
                <Legend />
              </PieChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Source mix">
          {sourceData.length === 0 ? (
            <EmptyChart />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={sourceData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e8e4dc" />
                <XAxis dataKey="name" tick={{ fontSize: 8.8 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 8.8 }} />
                <Tooltip />
                <Bar dataKey="value" fill={NAVY} name="Leads" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Lead intake trend">
          {trend.length === 0 ? (
            <EmptyChart />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={trend}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e8e4dc" />
                <XAxis dataKey="date" tick={{ fontSize: 8 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 8.8 }} />
                <Tooltip />
                <Legend />
                <Line type="monotone" dataKey="created" stroke={NAVY} strokeWidth={2} name="Created" dot={false} />
                <Line type="monotone" dataKey="called" stroke={GOLD} strokeWidth={2} name="Called" dot={false} />
              </LineChart>
            </ResponsiveContainer>
          )}
        </ChartCard>

        <ChartCard title="Quality ★ distribution">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={ratingData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#e8e4dc" />
              <XAxis dataKey="rating" tick={{ fontSize: 8.8 }} />
              <YAxis allowDecimals={false} tick={{ fontSize: 8.8 }} />
              <Tooltip />
              <Bar dataKey="count" fill={GOLD} name="Leads" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="Per-telecaller volume" className="lg:col-span-2">
          {staffData.length === 0 ? (
            <EmptyChart />
          ) : (
            <ResponsiveContainer width="100%" height={Math.max(180, staffData.length * 36)}>
              <BarChart data={staffData} layout="vertical" margin={{ left: 24 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e8e4dc" />
                <XAxis type="number" allowDecimals={false} tick={{ fontSize: 8.8 }} />
                <YAxis type="category" dataKey="name" width={120} tick={{ fontSize: 8.8 }} />
                <Tooltip />
                <Bar dataKey="count" fill={NAVY} name="Leads" radius={[0, 4, 4, 0]} />
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
    <div className="rounded-lg border border-navy/10 bg-ivory px-4 py-3" style={{ background: CREAM }}>
      <div className="text-xs uppercase tracking-wide text-navy/50">{label}</div>
      <div className="mt-1 font-serif text-2xl text-navy">{value}</div>
    </div>
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
    <div className={`rounded-lg border border-navy/10 bg-white p-4 ${className ?? ""}`}>
      <h3 className="mb-2 font-serif text-base text-navy">{title}</h3>
      {children}
    </div>
  );
}

function EmptyChart() {
  return <p className="flex h-[220px] items-center justify-center text-sm text-navy/40">No data in range</p>;
}
