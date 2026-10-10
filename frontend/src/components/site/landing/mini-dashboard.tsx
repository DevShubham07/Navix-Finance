"use client";

import * as React from "react";
import Image from "next/image";
import { Bell, CircleCheck, PenLine, ShieldCheck, UserRound } from "lucide-react";
import {
  CHART_COLORS,
  CapsuleBarChart,
  DayStrip,
  DonutChart,
  Figure,
  FooterCard,
  GoalProgress,
  Panel,
} from "@/components/kit";

/**
 * A static, scaled-down DhanBoost borrower dashboard built from the real UI kit — the "product
 * screenshot" inside the accent frame on the home page. It is laid out at a fixed 1120px and scaled
 * by the parent (CSS var `--s`), so it reads as one picture at every width. The numbers are the
 * product's worked example: ₹10,000 disbursed 3 Jun with salary day 30 → 27 days → ₹12,700.
 */
export function MiniDashboard() {
  return (
    <div className="w-[1120px] rounded-[26px] border border-line bg-ivory p-5 text-ink shadow-lg">
      {/* top bar */}
      <div className="flex items-center justify-between rounded-full border border-line bg-paper py-2 pl-2.5 pr-2 shadow-xs">
        <span className="flex items-center gap-2.5">
          <Image src="/navix-mark-64.png" alt="" width={32} height={32} className="rounded-[9px]" />
          <span className="text-[15px] font-semibold tracking-[-0.02em]">DhanBoost</span>
        </span>
        <span className="flex items-center gap-1.5 text-[13px]">
          <span className="rounded-full bg-navy px-4 py-2 font-medium text-white">Overview</span>
          {["My loan", "Repay", "Documents", "Support"].map((t) => (
            <span key={t} className="rounded-full px-4 py-2 text-slate">
              {t}
            </span>
          ))}
        </span>
        <span className="flex items-center gap-2">
          <span className="icon-pill">
            <Bell size={15} strokeWidth={1.8} />
          </span>
          <span className="grid h-[38px] w-[38px] place-items-center rounded-full bg-navy text-white">
            <UserRound size={17} strokeWidth={1.8} />
          </span>
        </span>
      </div>

      <div className="mt-6 flex items-end justify-between px-1">
        <div>
          <p className="m-0 text-[13px] text-slate">Your loan</p>
          <p className="lp-fig m-0 mt-1 text-[34px]">Active · due 30 Jun</p>
        </div>
        <span className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-success-50 px-3 py-1.5 text-[12.5px] font-medium text-success-700">
          <CircleCheck size={15} /> Disbursed 3 Jun
        </span>
      </div>

      <div className="mt-5 grid grid-cols-12 gap-4">
        <Panel className="col-span-5">
          <p className="m-0 text-[13px] text-slate">Net disbursed to your bank</p>
          <Figure size="lg" className="mt-2">₹8,820</Figure>
          <div className="mt-5 grid gap-2.5 text-[13px]">
            {[
              ["Principal", "₹10,000"],
              ["Processing fee · 10%", "− ₹1,000"],
              ["GST on fee · 18%", "− ₹180"],
            ].map(([k, v]) => (
              <div key={k} className="flex justify-between border-b border-line pb-2.5">
                <span className="text-slate">{k}</span>
                <span className="tabular-nums">{v}</span>
              </div>
            ))}
            <div className="flex justify-between font-semibold">
              <span>You receive</span>
              <span className="tabular-nums">₹8,820</span>
            </div>
          </div>
        </Panel>

        <Panel className="col-span-4">
          <div className="flex items-center justify-between">
            <p className="m-0 text-[13px] text-slate">Total repayable</p>
            <span className="rounded-full bg-grey-100 px-2.5 py-1 text-[11px] text-slate">27 days</span>
          </div>
          <DonutChart
            className="mt-1"
            data={[
              { name: "Principal", value: 10000, color: CHART_COLORS.ink },
              { name: "Interest", value: 2700, color: CHART_COLORS.ember },
            ]}
            totalLabel="on 30 Jun"
            format={(v) => "₹" + v.toLocaleString("en-IN")}
          />
        </Panel>

        <div className="col-span-3 flex flex-col gap-4">
          <FooterCard footer="Single repayment, on your salary day">
            <p className="m-0 text-[12px] text-white/70">Repayment date</p>
            <p className="lp-fig m-0 mb-3 mt-1 text-[26px] text-white">30 June</p>
            <DayStrip days={[26, 27, 28, 29, 30, 1, 2]} selected={30} />
          </FooterCard>
          <Panel className="flex-1">
            <p className="m-0 flex items-center gap-2 text-[13px] text-ink">
              <ShieldCheck size={16} className="text-success-600" /> KYC verified
            </p>
            <p className="m-0 mt-2.5 flex items-center gap-2 text-[13px] text-ink">
              <PenLine size={16} className="text-gold-dark" /> Key Fact Statement e-signed
            </p>
          </Panel>
        </div>

        <Panel className="col-span-7">
          <div className="flex items-center justify-between">
            <p className="m-0 text-[13px] text-slate">Interest so far · 1% per day on principal</p>
            <span className="text-[12px] text-slate">No prepayment charges</span>
          </div>
          <CapsuleBarChart
            ariaLabel="Interest by days held"
            height={150}
            data={[
              { label: "Day 7", value: 700 },
              { label: "Day 14", value: 1400 },
              { label: "Day 21", value: 2100 },
              { label: "Day 27", value: 2700 },
            ]}
            format={(v) => "₹" + v.toLocaleString("en-IN")}
          />
        </Panel>
        <Panel className="col-span-5">
          <p className="m-0 text-[13px] text-slate">Until your salary day</p>
          <Figure size="lg" className="mt-2">15 days</Figure>
          <GoalProgress ratio={12 / 27} label="Tenure used" trailing="12 of 27 days" tone="dark" className="mt-5" />
        </Panel>
      </div>
    </div>
  );
}
