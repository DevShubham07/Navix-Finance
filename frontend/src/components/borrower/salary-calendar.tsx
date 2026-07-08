"use client";

import * as React from "react";
import { dueDateFromSalary, daysBetween } from "@/lib/calc/loan-math";

/**
 * Salary-linked repayment picker — a 1–31 day-of-month chip grid. The borrower
 * taps the day their salary is credited; that day-of-month becomes the real
 * `salaryCreditDay` the backend uses to compute the single-repayment due date.
 * The side panel previews the resolved due date using NAVIX's rule: the LATEST
 * salary occurrence strictly after disbursal and within ≤ 40 days (the earlier
 * 15-day floor is no longer a user-visible gate — every chip is selectable).
 * `dueDateFromSalary` clamps 29/30/31 to the month length, so those days are
 * safe to feed as-is.
 */
const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const DAYS = Array.from({ length: 31 }, (_, i) => i + 1);

/** Resolve the previewed due date for a chosen salary day, from today. */
function dueFor(day: number): Date {
  return dueDateFromSalary({ disbursedOn: new Date(), salaryDay: day });
}

export interface SalaryCalendarProps {
  /** Current salary credit day-of-month (1–31). */
  value: number;
  /**
   * Fired with the computed due date AND the picked day-of-month. Pages must store `day` as the
   * salary credit day — NOT `date.getDate()`: the due date is clamped to the landing month's
   * length (e.g. picked 31 → due 28 Feb), so deriving the day from it silently corrupts the pick.
   */
  onPick: (date: Date, day: number) => void;
}

export function SalaryCalendar({ value, onPick }: SalaryCalendarProps) {
  // Seed the selection from the saved salary day (default 1), read once.
  const initialDay = React.useMemo(() => {
    const v = Math.round(value);
    return v >= 1 && v <= 31 ? v : 1;
    // value intentionally read once for the initial selection only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [day, setDay] = React.useState<number>(initialDay);
  const due = React.useMemo(() => dueFor(day), [day]);

  // Push the default selection up on mount so the page's salaryDay matches.
  const pickedRef = React.useRef(false);
  React.useEffect(() => {
    if (!pickedRef.current) {
      pickedRef.current = true;
      onPick(due, day);
    }
  }, [due, day, onPick]);

  const choose = (d: number) => {
    setDay(d);
    onPick(dueFor(d), d);
  };

  const tenureDays = Math.max(1, daysBetween(new Date(), due));

  return (
    <div className="cal-card">
      <div className="cal-main">
        <div className="cal-sublabel">Step 1 · Your salary day</div>
        <div className="cal-head">
          <div className="cal-title">Which day are you paid?</div>
        </div>
        <div className="cal-grid" role="grid" aria-label="Pick the day-of-month your salary is credited">
          {DAYS.map((d) => (
            <button
              key={d}
              type="button"
              className={["cal-day", d === day ? "sel" : ""].filter(Boolean).join(" ")}
              aria-pressed={d === day}
              onClick={() => choose(d)}
            >
              {d}
            </button>
          ))}
        </div>
        <div className="cal-legend">
          <span><i className="lg-sel" /> Your salary day</span>
          <span className="cal-hintnote">Repaid on your next salary, within 40 days</span>
        </div>
      </div>

      <aside className="cal-side">
        <span className="eyebrow">Repaid in one instalment</span>
        <div className="cal-bigdate">{due.getDate()} {MON[due.getMonth()]}</div>
        <div className="cal-subdate">
          {WEEKDAYS[due.getDay()]} {due.getFullYear()} · ~{tenureDays} {tenureDays === 1 ? "day" : "days"} away
        </div>
        <p className="cal-note">
          We link your due date to your salary credit. Pick the day you&apos;re next paid — your
          advance is repaid in a single instalment on that day. Prepay anytime, no penalty.
        </p>
      </aside>
    </div>
  );
}
