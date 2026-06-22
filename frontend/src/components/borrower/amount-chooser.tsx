"use client";

import * as React from "react";
import { buildCostBreakdown, dueDateFromSalary, MIN_LOAN_AMOUNT } from "@/lib/calc/loan-math";
import { LoanCostBreakdown } from "@/components/borrower/loan-cost-breakdown";
import { formatINR0, formatDate } from "@/lib/utils";

/**
 * Amount picker for the salary-linked advance: a slider clamped to the
 * sanctioned limit, quick presets, and a live cost breakdown (net to bank,
 * interest, single-repayment total) that updates as you drag.
 */
export interface AmountChooserProps {
  limit: number;
  salaryDay: number;
  value: number;
  onChange: (value: number) => void;
}

const STEP = 500;

export function AmountChooser({ limit, salaryDay, value, onChange }: AmountChooserProps) {
  const min = Math.min(MIN_LOAN_AMOUNT, limit);
  const due = React.useMemo(() => dueDateFromSalary({ disbursedOn: new Date(), salaryDay }), [salaryDay]);
  const tenureDays = Math.max(1, Math.round((due.getTime() - Date.now()) / 864e5));
  const breakdown = React.useMemo(() => buildCostBreakdown(value, tenureDays), [value, tenureDays]);
  const pct = limit > min ? ((value - min) / (limit - min)) * 100 : 100;

  const presets = [0.25, 0.5, 0.75, 1].map((f) => Math.max(min, Math.round((limit * f) / STEP) * STEP));

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="rounded border border-line bg-white p-6 shadow-sm">
        <div className="flex items-baseline justify-between">
          <span className="text-sm font-semibold text-ink">Advance amount</span>
          <span className="font-serif text-2xl font-bold text-navy">{formatINR0(value)}</span>
        </div>
        <input
          type="range"
          min={min}
          max={limit}
          step={STEP}
          value={value}
          aria-label="Advance amount"
          onChange={(e) => onChange(Number(e.target.value))}
          className="mt-4 w-full"
          style={{
            background: `linear-gradient(to right, var(--gold) ${pct}%, var(--grey-200) ${pct}%)`,
          }}
        />
        <div className="mt-1 flex justify-between text-xs text-muted">
          <span>{formatINR0(min)}</span>
          <span>Limit {formatINR0(limit)}</span>
        </div>
        <div className="mt-4 grid grid-cols-4 gap-2 max-[360px]:grid-cols-2">
          {presets.map((p, i) => (
            <button
              key={i}
              type="button"
              onClick={() => onChange(p)}
              className={`rounded border px-2 py-2.5 text-sm font-semibold transition ${
                value === p ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy hover:text-navy"
              }`}
            >
              {[25, 50, 75, 100][i]}%
            </button>
          ))}
        </div>
        <p className="mt-4 text-xs text-muted">
          Repaid in one instalment on your salary day, <strong className="text-ink">{formatDate(due)}</strong> (~{tenureDays} days).
          Prepay anytime with no penalty.
        </p>
      </div>
      <LoanCostBreakdown breakdown={breakdown} dueDate={formatDate(due)} />
    </div>
  );
}
