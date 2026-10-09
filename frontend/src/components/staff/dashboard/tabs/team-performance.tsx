"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Bar, BarChart, CartesianGrid, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Mail, ShieldCheck, ShieldOff, Users } from "lucide-react";
import { adminApi, dashboardApi, type StaffStatus } from "@/lib/api/applications";
import { STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import { cn } from "@/lib/utils";
import { ChartCard } from "../chart-card";
import { ChartTooltip } from "../chart-tooltip";
import { AXIS_TICK, CURSOR, GRID_STROKE, activeRow } from "../chart-parts";
import { CHART, NAVY, PCT_TONE_COLOR, STAT_CHIP, TEXT, TONE_SOLID, TONE_TINT, WHITE, pctTone } from "../colors";
import { nf } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { IconSquare, Segmented, StatChip, chartBox } from "../ui-bits";
import { fmtScore } from "../rating-factors";

type RowKey = "total" | "active" | "inactive" | "invited";

const ROWS: { key: RowKey; label: string; icon: typeof Users; tone: keyof typeof TONE_SOLID; status: StaffStatus | null }[] = [
  { key: "total", label: "Total Employees", icon: Users, tone: "blue", status: null },
  { key: "active", label: "Active Employees", icon: ShieldCheck, tone: "emerald", status: "ACTIVE" },
  { key: "inactive", label: "Inactive Employees", icon: ShieldOff, tone: "red", status: "DISABLED" },
  { key: "invited", label: "Invited (not yet joined)", icon: Mail, tone: "amber", status: "INVITED" },
];

const BOARDS = {
  CREDIT_HEAD: { label: "Credit", metric: "STAFF_FILES" as const },
  COLLECTION_HEAD: { label: "Collection", metric: "STAFF_CASES" as const },
};

/** Admin "Team & Performance": Employee Snapshot + Head Performance. */
export function TeamPerformance({ params, open }: DashTabProps) {
  const q = useDashQuery("team", params, [], () => dashboardApi.team(params));
  const t = q.data;
  const [picked, setPicked] = React.useState<RowKey | null>(null);
  const [board, setBoard] = React.useState<keyof typeof BOARDS>("CREDIT_HEAD");

  const pickedRow = ROWS.find((r) => r.key === picked);
  const staffQ = useQuery({
    queryKey: ["staff-dashboard-team-staff"],
    queryFn: () => adminApi.listStaff(),
    enabled: picked != null,
    staleTime: 60_000,
  });
  const listed = (staffQ.data ?? []).filter((s) => !pickedRow?.status || s.status === pickedRow.status);

  const heads = (t?.heads ?? []).filter((h) => h.role === board);
  const data = heads.map((h) => ({ staffId: h.staffId, name: h.name, score: h.score ?? 0, measured: h.score != null }));

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <ChartCard
        title="Employee Snapshot"
        info="Staff accounts by status. Click a row to list those people."
        accent={TONE_SOLID.blue}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={!t}
      >
        {t && (
          <>
            <ul className="m-0 list-none space-y-2 p-0">
              {ROWS.map((r) => {
                const Icon = r.icon;
                const on = picked === r.key;
                return (
                  <li key={r.key}>
                    <button
                      type="button"
                      aria-pressed={on}
                      onClick={() => setPicked(on ? null : r.key)}
                      title={`${r.label}: ${nf(t[r.key])} — click to list`}
                      className={cn(
                        "flex w-full items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition hover:-translate-y-0.5 hover:shadow",
                        on ? "border-navy" : "border-line",
                      )}
                      style={{ background: on ? TONE_TINT[r.tone] : WHITE }}
                    >
                      <IconSquare color={TONE_SOLID[r.tone]}>
                        <Icon size={18} />
                      </IconSquare>
                      <span className="flex-1 text-sm font-medium text-ink">{r.label}</span>
                      <span className="font-serif text-2xl font-bold tabular-nums" style={{ color: TONE_SOLID[r.tone] }}>
                        {nf(t[r.key])}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>

            {picked && (
              <div className="mt-3 rounded-lg border border-line p-3">
                <h4 className="m-0 mb-2 text-xs font-semibold text-navy">{pickedRow?.label}</h4>
                {staffQ.isLoading ? (
                  <p className="m-0 text-xs text-muted">Loading staff…</p>
                ) : staffQ.isError ? (
                  <p className="m-0 text-xs" style={{ color: TEXT.bad }}>Could not load the staff list.</p>
                ) : listed.length === 0 ? (
                  <p className="m-0 text-xs text-muted">No one in this group.</p>
                ) : (
                  <ul className="m-0 grid max-h-56 list-none grid-cols-1 gap-1 overflow-y-auto p-0 sm:grid-cols-2">
                    {listed.map((s) => (
                      <li key={s.id} className="flex items-center justify-between gap-2 rounded bg-grey-50 px-2 py-1 text-xs">
                        <span className="truncate font-medium text-ink">{s.name}</span>
                        <span className="shrink-0 text-[10px] text-muted">{STAFF_ROLE_LABELS[s.role as StaffRole] ?? s.role}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}

            <h4 className="mb-2 mt-4 text-xs font-semibold uppercase tracking-wide text-muted">By role</h4>
            <div className="flex flex-wrap gap-2">
              {t.byRole.map((r) => (
                <StatChip
                  key={r.role}
                  {...STAT_CHIP.navy}
                  label={STAFF_ROLE_LABELS[r.role as StaffRole] ?? r.role}
                  value={nf(r.count)}
                />
              ))}
            </div>
          </>
        )}
      </ChartCard>

      <ChartCard
        title="Head Performance"
        info="Each head's score (0-10) from their own leaderboard: the weighted average of the factors that can be measured in the period. Click a bar to see their files or cases."
        accent={TONE_SOLID.blue}
        controls={
          <Segmented
            label="Board"
            value={board}
            onChange={setBoard}
            options={[
              ["CREDIT_HEAD", "Credit"],
              ["COLLECTION_HEAD", "Collection"],
            ]}
            colors={{ CREDIT_HEAD: TONE_SOLID.emerald, COLLECTION_HEAD: TONE_SOLID.red }}
          />
        }
        loading={q.isLoading}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={data.length === 0}
        emptyTitle={`No ${BOARDS[board].label} heads`}
      >
        <div className={chartBox}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart
              data={data}
              margin={{ top: 22, right: 8, left: 0, bottom: 0 }}
              onClick={(s) => {
                const row = activeRow<{ staffId: number; name: string }>(s);
                if (row)
                  open({ metric: BOARDS[board].metric, key: String(row.staffId), title: `${row.name} · ${BOARDS[board].label} head` });
              }}
            >
              <CartesianGrid stroke={GRID_STROKE} vertical={false} />
              <XAxis dataKey="name" tick={AXIS_TICK} interval={0} />
              <YAxis domain={[0, 10]} tick={AXIS_TICK} width={28} />
              <Tooltip
                cursor={CURSOR}
                content={
                  <ChartTooltip
                    kinds={{ score: "count" }}
                    labels={{ score: "Score / 10" }}
                    extra={(row) => [{ label: "Measured", value: row.measured ? fmtScore(Number(row.score)) : "Not enough data" }]}
                  />
                }
              />
              <Bar dataKey="score" name="Score" radius={[6, 6, 0, 0]} cursor="pointer">
                {data.map((d) => (
                  <Cell key={d.staffId} fill={d.measured ? PCT_TONE_COLOR[pctTone(d.score / 10)] : CHART.unmeasured} />
                ))}
                <LabelList dataKey="score" position="top" formatter={(v: number) => v.toFixed(2)} style={{ fontSize: 11, fontWeight: 700, fill: NAVY }} />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </ChartCard>
    </div>
  );
}
