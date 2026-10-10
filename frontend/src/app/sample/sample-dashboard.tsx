"use client";

import * as React from "react";
import Image from "next/image";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  Bell,
  CircleHelp,
  Gauge,
  LayoutGrid,
  Percent,
  Plus,
  Settings2,
  Sparkles,
  Users,
  Wallet,
} from "lucide-react";
import {
  AvatarStack,
  CHART_COLORS,
  CapsuleBarChart,
  ChartContainer,
  ChartLegend,
  ChartTooltipContent,
  DayStrip,
  DonutChart,
  DotMatrix,
  FilterPill,
  Figure,
  FooterCard,
  GoalProgress,
  IconPill,
  MetricTile,
  PagerDots,
  Panel,
  PanelHeader,
  PillNav,
  PillSelect,
  SegmentBars,
  StatBlock,
  TargetColumns,
  Tile,
  UnderlineTabs,
  UserChip,
  chartAxis,
  chartGrid,
  chartMargin,
  chartYAxis,
  type ChartConfig,
} from "@/components/kit";
import { Badge, StatusBadge } from "@/components/ui";

/* ------------------------------------------------------------------ demo data */

const PERIODS = [
  { value: "week", label: "Week" },
  { value: "month", label: "Month" },
  { value: "quarter", label: "Quarter" },
] as const;
type Period = (typeof PERIODS)[number]["value"];

const DISBURSED = [
  { label: "Jan", value: 1_82_000, delta: 24 },
  { label: "Feb", value: 2_96_000, delta: 36 },
  { label: "Mar", value: 2_51_000, delta: 26 },
  { label: "Apr", value: 1_74_000, delta: 19 },
  { label: "May", value: 2_43_000, delta: 25 },
  { label: "Jun", value: 1_38_000, delta: 14 },
  { label: "Jul", value: 2_88_000, delta: 25 },
  { label: "Aug", value: 2_12_000, delta: 18 },
  { label: "Sep", value: 3_04_000, delta: 31 },
];

const TREND = [
  { d: "1 Oct", fresh: 42, reloan: 18 },
  { d: "2 Oct", fresh: 51, reloan: 22 },
  { d: "3 Oct", fresh: 47, reloan: 27 },
  { d: "4 Oct", fresh: 63, reloan: 25 },
  { d: "5 Oct", fresh: 58, reloan: 31 },
  { d: "6 Oct", fresh: 71, reloan: 29 },
  { d: "7 Oct", fresh: 66, reloan: 36 },
  { d: "8 Oct", fresh: 79, reloan: 34 },
  { d: "9 Oct", fresh: 74, reloan: 41 },
  { d: "10 Oct", fresh: 88, reloan: 39 },
];
const TREND_CONFIG: ChartConfig = {
  fresh: { label: "Fresh", color: CHART_COLORS.ember },
  reloan: { label: "Re-loan", color: CHART_COLORS.ink },
};

const DPD = [
  { name: "Running", value: 412, color: CHART_COLORS.mint },
  { name: "1–30", value: 86, color: CHART_COLORS.sun },
  { name: "31–60", value: 34, color: CHART_COLORS.ember },
  { name: "60+", value: 18, color: CHART_COLORS.violet },
];

const COLLECTIONS = [
  { m: "May", due: 64, collected: 58 },
  { m: "Jun", due: 71, collected: 62 },
  { m: "Jul", due: 68, collected: 69 },
  { m: "Aug", due: 82, collected: 71 },
  { m: "Sep", due: 77, collected: 72 },
  { m: "Oct", due: 85, collected: 79 },
];

const inr = (n: number) => `₹${n.toLocaleString("en-IN")}`;

/* ------------------------------------------------------------------ page */

export function SampleDashboard() {
  const [period, setPeriod] = React.useState<Period>("week");
  const [period2, setPeriod2] = React.useState<Period>("week");
  const [tab, setTab] = React.useState<"revenue" | "kpi">("revenue");
  const [day, setDay] = React.useState(16);
  const [slide, setSlide] = React.useState(0);

  return (
    <div className="app-backdrop">
      <div className="app-frame mx-auto max-w-[1360px] px-4 pb-8 pt-4 sm:px-6 lg:px-7">
        {/* ---------------------------------------------------- top bar */}
        <header className="flex flex-wrap items-center gap-3 py-2">
          <Image src="/navix-mark-64.png" alt="DhanBoost" width={44} height={44} className="rounded-full shadow-sm" />
          <PillNav
            className="order-3 w-full md:order-none md:mx-auto md:w-auto"
            activeHref="/sample"
            items={[
              { href: "/sample", label: "Dashboard" },
              { href: "/sample#applications", label: "Applications" },
              { href: "/sample#loans", label: "Loans" },
              { href: "/sample#components", label: "Components" },
            ]}
          />
          <div className="ml-auto flex items-center gap-2 md:ml-0">
            <IconPill label="Notifications"><Bell size={16} strokeWidth={1.8} /></IconPill>
            <IconPill label="Help"><CircleHelp size={16} strokeWidth={1.8} /></IconPill>
            <span aria-hidden className="mx-1 hidden h-8 w-px bg-line sm:block" />
            <UserChip name="Meera Krishnan" role="Administrator" />
          </div>
        </header>

        {/* ---------------------------------------------------- title row */}
        <div className="mb-5 mt-6 flex flex-wrap items-end justify-between gap-3">
          <h1 className="m-0">Welcome back, Meera</h1>
          <div className="flex gap-2">
            <button type="button" className="nav-pill"><Gauge size={15} /> Analytics</button>
            <button type="button" className="nav-pill"><Settings2 size={15} /> Settings</button>
          </div>
        </div>

        {/* ---------------------------------------------------- row 1 */}
        <div className="grid gap-3 lg:grid-cols-[1.65fr_1fr]">
          <Panel>
            <PanelHeader
              title="Overall disbursed"
              controls={
                <>
                  <PillSelect label="Period" value={period} onChange={setPeriod} options={PERIODS} />
                  <FilterPill />
                </>
              }
            />
            <Figure size="lg" className="-mt-2">₹ 25,63,420</Figure>
            <CapsuleBarChart data={DISBURSED} format={inr} ariaLabel="Disbursed amount per month" />
          </Panel>

          <Panel>
            <PanelHeader
              title="Source"
              controls={
                <>
                  <PillSelect label="Period" value={period2} onChange={setPeriod2} options={PERIODS} />
                  <FilterPill />
                </>
              }
            />
            <StatBlock label="Net interest income" value="₹ 8,43,000" size="md" className="-mt-2 mb-8" />
            <SegmentBars
              format={inr}
              rows={[
                {
                  label: "Fresh vs re-loan applications",
                  segments: [
                    { label: "Fresh", value: 620, color: CHART_COLORS.sun },
                    { label: "Re-loan", value: 260, color: CHART_COLORS.mint },
                  ],
                },
                {
                  label: "Processing fee vs interest",
                  segments: [
                    { label: "Interest", value: 470, color: CHART_COLORS.violet },
                    { label: "Processing fee", value: 230, color: CHART_COLORS.ember },
                  ],
                },
              ]}
            />
            <p className="m-0 mt-8 max-w-xs text-xs text-muted">
              Net interest margin improved by 4.2% compared to last month.
            </p>
          </Panel>
        </div>

        {/* ---------------------------------------------------- row 2 */}
        <div className="mt-3 grid gap-3 xl:grid-cols-[1fr_1.45fr]">
          <Panel>
            <PanelHeader
              title={<span className="text-xl">Metrics</span>}
              controls={
                <>
                  <PillSelect label="Period" value={period} onChange={setPeriod} options={PERIODS} />
                  <FilterPill />
                </>
              }
            />
            <UnderlineTabs
              value={tab}
              onChange={setTab}
              tabs={[
                { value: "revenue", label: "Revenue metrics", count: 10 },
                { value: "kpi", label: "KPI widgets", count: 8 },
              ]}
            />
            <Tile className="mt-4 grid gap-4 sm:grid-cols-2 sm:divide-x sm:divide-line">
              <div>
                <div className="flex items-start justify-between">
                  <AvatarStack names={["Arjun Mehta", "Kavya Rao"]} />
                  <Sparkles size={15} className="text-muted" aria-hidden />
                </div>
                <div className="mt-4 flex items-end gap-3">
                  <Figure size="md">₹24.6</Figure>
                  <span className="pb-1 text-xs leading-tight text-muted">Cost per<br />acquisition</span>
                </div>
              </div>
              <div className="sm:pl-4">
                <div className="flex items-start justify-between">
                  <p className="m-0 text-xs text-muted">Gross revenue</p>
                  <Percent size={14} className="text-muted" aria-hidden />
                </div>
                <Figure size="sm" className="mt-1.5">₹ 2,48,900</Figure>
                <GoalProgress ratio={0.52} trailing="150 days left" className="mt-3" />
              </div>
            </Tile>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <FooterCard footer="Reminders">
                <div className="flex items-start justify-between">
                  <span className="grid h-7 w-9 place-items-center rounded-full bg-white/10"><Bell size={13} /></span>
                  <button type="button" aria-label="Add reminder" className="grid h-7 w-7 place-items-center rounded-full bg-white text-ink"><Plus size={15} /></button>
                </div>
                <p className="m-0 mt-2 text-sm font-medium">Today</p>
                <p className="m-0 text-[10px] text-white/50">7 follow-ups due</p>
                <DayStrip className="mt-3" days={[12, 13, 14, 15, 16, 17]} selected={day} onSelect={setDay} />
              </FooterCard>
              <FooterCard footer="Automations">
                <div className="flex items-start justify-between">
                  <span className="flex gap-1">
                    <span className="grid h-7 w-7 place-items-center rounded-full bg-white/10"><Percent size={12} /></span>
                    <span className="grid h-7 w-7 place-items-center rounded-full bg-white/10"><LayoutGrid size={12} /></span>
                  </span>
                  <button type="button" aria-label="Add automation" className="grid h-7 w-7 place-items-center rounded-full bg-white text-ink"><Plus size={15} /></button>
                </div>
                <p className="m-0 mt-2 text-[10px] text-white/50">Reminders: <span className="text-white">Active</span></p>
                <div className="mt-2 flex items-end justify-between gap-2">
                  <span className="flex items-baseline gap-1.5">
                    <Figure size="sm">150/256</Figure>
                    <span className="text-[10px] text-white/50">Sent</span>
                  </span>
                  <button type="button" className="rounded-full bg-white px-3.5 py-2 text-[11px] font-medium text-ink">View all</button>
                </div>
              </FooterCard>
            </div>
          </Panel>

          <div className="grid gap-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <MetricTile value="8.6%" delta={6} label="Approval rate" icon={<Users size={15} strokeWidth={1.8} />} />
              <MetricTile value="₹62.4K" delta={4} label="Average ticket size" icon={<Wallet size={15} strokeWidth={1.8} />} />
              <Panel className="flex min-h-[9.5rem] flex-col justify-between">
                <div className="flex items-start justify-between">
                  <p className="m-0 text-xs leading-snug text-muted">Manage<br />team</p>
                  <FilterPill />
                </div>
                <div className="flex items-center justify-end gap-2">
                  <IconPill label="Add member"><Plus size={15} /></IconPill>
                  <AvatarStack names={["Rohan Das", "Priya Nair", "Sana Iqbal", "Vikram Shah"]} size={38} max={2} />
                </div>
              </Panel>
            </div>

            <div className="grid gap-3 md:grid-cols-[1.35fr_1fr]">
              <Panel className="flex flex-col">
                <PanelHeader title="Total repayments" controls={<button type="button" className="nav-pill">Settings</button>} />
                <StatBlock label="Verified this month" value="15,842" delta={10} size="lg" className="-mt-2" />
                <DotMatrix
                  className="mt-6"
                  columns={[2, 3, 1, 2, 2, 3, 2, 3, 3, 1, 2, 2]}
                  groupEvery={3}
                  ariaLabel="Repayments per day, last two weeks"
                />
                <p className="m-0 mt-auto max-w-xs pt-6 text-xs text-muted">Total repayments grew by 9% compared to last month.</p>
              </Panel>

              <Panel tone="dark" className="flex min-h-[18rem] flex-col items-center justify-between overflow-hidden p-6 text-center">
                {/* curtain texture + ghost numeral — the reference's black feature card */}
                <span aria-hidden className="pointer-events-none absolute inset-0 opacity-60 [background:repeating-linear-gradient(90deg,rgb(255_255_255/.05)_0_2px,transparent_2px_26px)]" />
                <span aria-hidden className="figure-display pointer-events-none absolute -bottom-8 left-1/2 -translate-x-1/2 text-[9rem] text-white/[.05]">84</span>
                <p className="relative m-0 text-base font-medium">Collection efficiency</p>
                <div className="relative flex items-start">
                  <Figure size="xl">84</Figure>
                  <span className="figure-display mt-1 text-2xl">%</span>
                </div>
                <button type="button" className="relative rounded-full bg-white px-4 py-2 text-[11px] font-medium text-ink shadow-md">View all</button>
                <PagerDots count={3} active={slide} onSelect={setSlide} className="absolute right-5 top-1/2 -translate-y-1/2" />
              </Panel>
            </div>
          </div>
        </div>

        {/* ---------------------------------------------------- charts */}
        <h2 id="loans" className="mb-3 mt-10 text-base font-medium">Charts</h2>
        <div className="grid gap-3 lg:grid-cols-3">
          <Panel className="lg:col-span-2">
            <PanelHeader title="Applications" subtitle="Daily, fresh vs re-loan" controls={<ChartLegend config={TREND_CONFIG} />} />
            <ChartContainer config={TREND_CONFIG} className="h-64">
              <AreaChart data={TREND} margin={chartMargin}>
                <defs>
                  <linearGradient id="fillFresh" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--color-fresh)" stopOpacity={0.28} />
                    <stop offset="100%" stopColor="var(--color-fresh)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid {...chartGrid} />
                <XAxis dataKey="d" {...chartAxis} />
                <YAxis {...chartYAxis} />
                <Tooltip cursor={{ stroke: CHART_COLORS.line }} content={<ChartTooltipContent />} />
                <Area type="monotone" dataKey="reloan" stroke="var(--color-reloan)" strokeWidth={2} fill="transparent" strokeDasharray="4 4" dot={false} />
                <Area type="monotone" dataKey="fresh" stroke="var(--color-fresh)" strokeWidth={2.5} fill="url(#fillFresh)" dot={false} activeDot={{ r: 5, strokeWidth: 3, stroke: "#fff" }} />
              </AreaChart>
            </ChartContainer>
          </Panel>

          <Panel>
            <PanelHeader title="Book by DPD" subtitle="Active loans" />
            <DonutChart data={DPD} totalLabel="loans" />
          </Panel>

          <Panel className="lg:col-span-3">
            <PanelHeader
              title="Collections"
              subtitle="Collected against amount due, by month"
              controls={<FilterPill />}
            />
            <div className="grid gap-6 lg:grid-cols-[220px_1fr] lg:items-end">
              <div className="flex flex-col gap-5">
                <StatBlock label="Collected, 6 months" value="₹4.06Cr" delta={7} size="lg" />
                <StatBlock label="Collection rate" value="89.6%" size="md" caption="1 of 6 months met the full amount due." />
              </div>
              <TargetColumns
                ariaLabel="Collected versus due per month"
                format={(v) => `₹${v}L`}
                data={COLLECTIONS.map((c) => ({ label: c.m, target: c.due, actual: c.collected }))}
              />
            </div>
          </Panel>
        </div>

        {/* ---------------------------------------------------- primitives */}
        <h2 id="components" className="mb-3 mt-10 text-base font-medium">Components</h2>
        <div className="grid gap-3 lg:grid-cols-2">
          <Panel>
            <PanelHeader title="Buttons & badges" />
            <div className="flex flex-wrap gap-2">
              <button type="button" className="btn btn-navy">Sanction</button>
              <button type="button" className="btn btn-gold">Apply now</button>
              <button type="button" className="btn btn-outline">Mark pending</button>
              <button type="button" className="btn btn-outline btn-sm">Small</button>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <StatusBadge kind="application" value="SANCTIONED" />
              <StatusBadge kind="application" value="KYC_PENDING" />
              <StatusBadge kind="application" value="ACTIVE" />
              <StatusBadge kind="application" value="REJECTED" />
              <Badge variant="primary">Admin</Badge>
              <span className="pill">New</span>
            </div>
          </Panel>

          <Panel>
            <PanelHeader title="Form fields" />
            <div className="field-row">
              <div className="field">
                <label htmlFor="s-amt">Loan amount</label>
                <input id="s-amt" defaultValue="₹25,000" />
              </div>
              <div className="field">
                <label htmlFor="s-day">Salary day</label>
                <select id="s-day" defaultValue="30">
                  <option value="1">1st</option>
                  <option value="30">30th</option>
                </select>
              </div>
            </div>
            <div className="field mb-0">
              <label htmlFor="s-note">Note</label>
              <input id="s-note" placeholder="Ask for the latest salary slip" />
            </div>
          </Panel>

          <Panel id="applications" className="p-0 lg:col-span-2">
            <div className="p-5 pb-0">
              <PanelHeader title="Register table" subtitle="The shared staff-table treatment" />
            </div>
            <div className="navix-crm staff-table-scroll">
              <table className="staff-data-table staff-table-fit">
                <thead>
                  <tr><th>Applicant</th><th>Status</th><th className="num">Amount</th><th className="num">Net disbursed</th><th>Due</th></tr>
                </thead>
                <tbody>
                  {[
                    ["Arjun Mehta", "ACTIVE", 25000, "30 Oct"],
                    ["Kavya Rao", "SANCTIONED", 18000, "—"],
                    ["Rohan Das", "OVERDUE", 12000, "28 Sep"],
                    ["Priya Nair", "CLOSED", 30000, "30 Sep"],
                  ].map(([name, status, amt, due]) => (
                    <tr key={String(name)}>
                      <td className="font-medium text-ink">{name}</td>
                      <td><StatusBadge kind="application" value={String(status)} /></td>
                      <td className="num font-mono">{inr(Number(amt))}</td>
                      <td className="num font-mono">{inr(Math.round(Number(amt) * 0.882))}</td>
                      <td>{due}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}
