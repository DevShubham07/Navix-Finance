"use client";

import * as React from "react";
import Image from "next/image";
import {
  Check,
  CircleCheck,
  Fingerprint,
  IdCard,
  Landmark,
  PenLine,
} from "lucide-react";
import { Avatar, CHART_COLORS, CapsuleBarChart, DonutChart, GoalProgress } from "@/components/kit";
import { cn } from "@/lib/utils";
import { CalendarTile, IconTile } from "./primitives";

/**
 * Product fragments — small, static pieces of the real DhanBoost product used as decoration on the
 * marketing pages (floating hero tiles, bento illustrations). Every figure is the worked example
 * from the product's own loan maths (₹10,000 principal → fee ₹1,000, GST ₹180, net ₹8,820;
 * disbursed 3 Jun, salary day 30 → 27 days at 1%/day → repay ₹12,700) or copy already on the site.
 * All of them render inside `aria-hidden` / `inert` wrappers: they illustrate, they don't inform.
 */

/* ------------------------------------------------------------------ floating hero fragments */

/** Yellow sticky note with a pin. */
export function StickyNote({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div
      className={cn("relative w-[196px] rounded-[6px] px-5 pb-6 pt-7", className)}
      style={{
        background: "rgb(var(--c-chart-sun))",
        boxShadow: "0 1px 1px rgb(var(--c-shadow) / .06), 0 18px 32px -16px rgb(var(--c-shadow) / .35)",
      }}
    >
      <span
        className="absolute left-1/2 top-2.5 h-3.5 w-3.5 -translate-x-1/2 rounded-full bg-gold"
        style={{ boxShadow: "inset -2px -2px 0 rgb(var(--c-gold-700) / .5), 0 3px 5px rgb(var(--c-shadow) / .3)" }}
      />
      <p className="m-0 text-[17px] font-medium italic leading-[1.3] tracking-[-0.02em] text-ink">{children}</p>
    </div>
  );
}

/** A white app tile holding an accent check. */
export function CheckTile({ size = 72, className }: { size?: number; className?: string }) {
  return (
    <IconTile size={size} className={className}>
      <span className="grid place-items-center rounded-[12px] bg-gold text-ink" style={{ width: size * 0.5, height: size * 0.5 }}>
        <Check size={size * 0.3} strokeWidth={3} />
      </span>
    </IconTile>
  );
}

/** "Repayment date" card: a calendar tile with the salary day. */
export function RepaymentCard({ className }: { className?: string }) {
  return (
    <div className={cn("lp-tile w-[248px] rounded-[22px] p-4", className)}>
      <div className="flex items-center gap-3">
        <CalendarTile day="30" month="Jun" size={52} />
        <div className="min-w-0">
          <p className="m-0 text-[11.5px] text-slate">Repayment date</p>
          <p className="m-0 text-[15px] font-medium tracking-[-0.02em] text-ink">Salary day · 30 Jun</p>
        </div>
      </div>
      <div className="mt-4 flex items-end justify-between rounded-[14px] bg-grey-50 px-3.5 py-3">
        <span className="text-[11.5px] leading-tight text-slate">
          Single
          <br />
          repayment
        </span>
        <span className="lp-fig text-[22px] text-ink">₹12,700</span>
      </div>
    </div>
  );
}

const JOURNEY = [
  { label: "Apply", w: 100, color: CHART_COLORS.mint },
  { label: "Verify", w: 100, color: CHART_COLORS.mint },
  { label: "Accept", w: 64, color: CHART_COLORS.ember },
  { label: "Funds", w: 0, color: CHART_COLORS.ember },
];

/** "Your application" card with step progress bars. */
export function JourneyCard({ className }: { className?: string }) {
  return (
    <div className={cn("lp-tile w-[256px] rounded-[22px] p-4", className)}>
      <div className="flex items-center justify-between">
        <p className="m-0 text-[14px] font-medium tracking-[-0.02em] text-ink">Your application</p>
        <span className="rounded-full bg-grey-100 px-2 py-0.5 text-[10.5px] text-slate">Step 3 of 4</span>
      </div>
      <ul className="m-0 mt-3.5 grid list-none gap-2.5 p-0">
        {JOURNEY.map((s) => (
          <li key={s.label} className="grid grid-cols-[52px_1fr] items-center gap-2">
            <span className="text-[11.5px] text-slate">{s.label}</span>
            <span className="h-2 overflow-hidden rounded-full" style={{ background: CHART_COLORS.track }}>
              <span className="block h-full rounded-full" style={{ width: `${s.w}%`, background: s.color }} />
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

const VERIFY = [
  { icon: IdCard, label: "PAN" },
  { icon: Fingerprint, label: "Aadhaar" },
  { icon: Landmark, label: "Bank" },
  { icon: PenLine, label: "eSign" },
];

/** "Verified digitally" card with a row of app-icon tiles. */
export function VerifiedCard({ className }: { className?: string }) {
  return (
    <div className={cn("lp-tile w-[264px] rounded-[22px] p-4", className)}>
      <p className="m-0 text-[14px] font-medium tracking-[-0.02em] text-ink">Verified digitally</p>
      <p className="m-0 text-[11.5px] text-slate">No paperwork, no branch visits</p>
      <div className="mt-3.5 grid grid-cols-4 gap-2">
        {VERIFY.map(({ icon: Icon, label }) => (
          <span key={label} className="flex flex-col items-center gap-1.5">
            <IconTile size={46} className="text-ink">
              <Icon size={19} strokeWidth={1.8} />
            </IconTile>
            <span className="text-[10.5px] text-slate">{label}</span>
          </span>
        ))}
      </div>
    </div>
  );
}

/** "Credited to your bank account" amount card. */
export function AmountCard({ className }: { className?: string }) {
  return (
    <div className={cn("lp-tile w-[232px] rounded-[22px] p-4", className)}>
      <div className="flex items-center gap-2 text-[11.5px] text-slate">
        <CircleCheck size={15} className="text-success-600" strokeWidth={2.2} />
        Credited to your bank account
      </div>
      <p className="lp-fig m-0 mt-2.5 text-[30px] text-ink">₹8,820</p>
      <p className="m-0 mt-1 text-[11px] text-slate">Net disbursed · ₹10,000 less fee &amp; GST</p>
    </div>
  );
}

/** The app icon in a white tile (hero centrepiece, integrations hub). */
export function AppIcon({ size = 76, className }: { size?: number; className?: string }) {
  return (
    <span
      className={cn("lp-tile inline-grid place-items-center", className)}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.29), padding: Math.round(size * 0.13) }}
    >
      <Image src="/navix-mark.png" alt="" width={size} height={size} className="h-full w-full rounded-[22%] object-contain" />
    </span>
  );
}

/* ------------------------------------------------------------------ bento illustrations */

const DOCS = [
  { icon: IdCard, title: "PAN card", sub: "Identity & credit check" },
  { icon: Fingerprint, title: "Aadhaar", sub: "Paperless e-KYC" },
  { icon: Landmark, title: "Bank details", sub: "Salary check & disbursal" },
];

/** Minimal documentation: the three documents, each ticked. */
export function DocsArt() {
  return (
    <div className="absolute inset-0 grid content-center gap-2.5 px-6">
      {DOCS.map(({ icon: Icon, title, sub }, i) => (
        <div
          key={title}
          className="lp-tile flex items-center gap-3 rounded-[16px] px-3.5 py-2.5"
          style={{ marginLeft: i * 14, marginRight: (2 - i) * 14 }}
        >
          <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-gold-50 text-gold-dark">
            <Icon size={17} strokeWidth={1.9} />
          </span>
          <span className="min-w-0 flex-1 leading-tight">
            <span className="block text-[13px] font-medium text-ink">{title}</span>
            <span className="block truncate text-[11px] text-slate">{sub}</span>
          </span>
          <span className="grid h-6 w-6 place-items-center rounded-full bg-success-50 text-success-700">
            <Check size={13} strokeWidth={3} />
          </span>
        </div>
      ))}
    </div>
  );
}

const CHECKS = [
  { k: "PAN", t: "Identity verified" },
  { k: "AADHAAR", t: "e-KYC complete" },
  { k: "BANK", t: "Salary confirmed" },
];

/** Instant digital verification: the check rows + the decision badge, with a progress bar. */
export function VerifyArt() {
  return (
    <div className="absolute inset-0 flex flex-col justify-center gap-2 px-6">
      {CHECKS.map((c) => (
        <div key={c.k} className="flex items-center gap-2.5 rounded-[12px] border border-line bg-paper px-3 py-2 text-[12.5px] text-ink">
          <span className="w-[62px] rounded-md bg-navy px-1.5 py-0.5 text-center text-[9.5px] font-semibold tracking-[0.06em] text-white">{c.k}</span>
          <span className="flex-1">{c.t}</span>
          <CircleCheck size={16} className="text-success-600" strokeWidth={2.2} />
        </div>
      ))}
      <GoalProgress ratio={1} label="Eligibility check" trailing="Instant decision" className="mt-1.5" />
    </div>
  );
}

/** Key Fact Statement: principal vs interest on the worked example (kit donut). */
export function KfsArt() {
  return (
    <div className="absolute inset-0 flex items-center gap-2 pl-3 pr-5">
      <div className="w-[230px] shrink-0 origin-left scale-[.74]">
        <DonutChart
          data={[
            { name: "Principal", value: 10000, color: CHART_COLORS.ink },
            { name: "Interest", value: 2700, color: CHART_COLORS.ember },
          ]}
          totalLabel="you repay"
          format={(v) => "₹" + v.toLocaleString("en-IN")}
        />
      </div>
      <div className="-ml-12 grid min-w-0 flex-1 gap-1.5 text-[11.5px]">
        <Row k="You borrow" v="₹10,000" />
        <Row k="Interest · 27 days" v="₹2,700" />
        <Row k="You repay" v="₹12,700" strong />
        <span className="mt-1 inline-flex w-fit items-center gap-1 rounded-full bg-success-50 px-2 py-0.5 text-[10.5px] font-medium text-success-700">
          <PenLine size={11} /> e-signed
        </span>
      </div>
    </div>
  );
}

function Row({ k, v, strong }: { k: string; v: string; strong?: boolean }) {
  return (
    <span className={cn("flex justify-between gap-2 border-b border-line pb-1.5", strong && "border-0 font-semibold text-ink")}>
      <span className={strong ? "text-ink" : "text-slate"}>{k}</span>
      <span className="tabular-nums text-ink">{v}</span>
    </span>
  );
}

/** Pay early, pay less: interest on ₹10,000 at 1%/day by days held (kit capsule bars). */
export function InterestArt() {
  return (
    <div className="absolute inset-x-4 bottom-1 top-0">
      <CapsuleBarChart
        ariaLabel="Interest on ₹10,000 by days held"
        height={118}
        data={[
          { label: "7 days", value: 700 },
          { label: "14 days", value: 1400 },
          { label: "21 days", value: 2100 },
          { label: "27 days", value: 2700 },
        ]}
        format={(v) => "₹" + v.toLocaleString("en-IN")}
        className="pt-12"
      />
      <span className="absolute left-1 top-4 text-[11px] text-slate">Interest on ₹10,000 · 1% per day</span>
    </div>
  );
}

/** Salary-linked single repayment: June with disbursal day and the salary day marked. */
export function SalaryArt() {
  // June 2026 starts on a Monday.
  const days = Array.from({ length: 30 }, (_, i) => i + 1);
  return (
    <div className="absolute inset-0 grid place-items-center px-5">
      <div className="w-full max-w-[300px] rounded-[16px] border border-line bg-paper p-3 shadow-xs">
        <div className="mb-2 flex items-center justify-between text-[12px]">
          <span className="font-medium text-ink">June</span>
          <span className="flex items-center gap-1.5 text-[10.5px] text-slate">
            <span className="h-2 w-2 rounded-full bg-gold" /> Salary day
          </span>
        </div>
        <div className="grid grid-cols-7 gap-[3px] text-center text-[10.5px] tabular-nums">
          {["M", "T", "W", "T", "F", "S", "S"].map((d, i) => (
            <span key={i} className="pb-0.5 text-[9.5px] text-muted">
              {d}
            </span>
          ))}
          {days.map((d) => (
            <span
              key={d}
              className={cn(
                "grid h-[19px] place-items-center rounded-[6px] text-slate",
                d > 3 && d < 30 && "bg-gold-50",
                d === 3 && "border border-navy text-ink",
                d === 30 && "bg-gold font-semibold text-ink",
              )}
            >
              {d}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

/** Real human support: a short exchange + the support hours. */
export function SupportArt({ hours }: { hours: string }) {
  return (
    <div className="absolute inset-0 flex flex-col justify-center gap-2.5 px-5">
      <div className="ml-auto max-w-[78%] rounded-[16px] rounded-br-[6px] bg-navy px-3.5 py-2 text-[12px] text-white">Can I repay my loan early?</div>
      <div className="flex items-end gap-2">
        <Avatar name="DhanBoost Support" size={28} />
        <div className="max-w-[80%] rounded-[16px] rounded-bl-[6px] border border-line bg-paper px-3.5 py-2 text-[12px] text-ink">
          Yes — and there are no pre-closure or prepayment charges.
        </div>
      </div>
      <span className="mx-auto mt-1 rounded-full border border-line bg-paper px-3 py-1 text-[10.5px] text-slate">{hours}</span>
    </div>
  );
}
