"use client";

import * as React from "react";
import { ChevronLeft, ChevronRight, CalendarDays } from "lucide-react";

/**
 * Salary-linked repayment calendar — the borrower taps the date their salary is
 * next credited; that day-of-month becomes the real `salaryCreditDay` the backend
 * uses to compute the single-repayment due date (next salary credit strictly after
 * disbursal and ≤ 40 days). Selectable window is today+7 … today+40, mirroring
 * NAVIX's MAX_TERM_DAYS rule. Ported 1:1 from the "calendar" design variant.
 */
const DAY = 86_400_000;
// 15-day minimum cycle = SALARY_DUE_MIN_CYCLE_DAYS in loan-math. Every selectable
// date is then exactly what both the AmountChooser preview and the backend
// dueDateFromSalary will resolve to (the single salary occurrence within the
// 15–40 day window), so the borrower is never shown a date that later rolls.
const MIN_T = 15;
const MAX_T = 40;
const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];
const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
const DOW = ["S", "M", "T", "W", "T", "F", "S"];

function midnight(d: Date) {
  const c = new Date(d);
  c.setHours(0, 0, 0, 0);
  return c;
}
function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}
function firstOfMonth(d: Date) {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

export interface SalaryCalendarProps {
  /** Current salary credit day-of-month (1–31). */
  value: number;
  /** Fired with the chosen date; the page derives salaryDay = date.getDate(). */
  onPick: (date: Date) => void;
  /** Optional tenure label override; otherwise computed as “N days away”. */
}

export function SalaryCalendar({ value, onPick }: SalaryCalendarProps) {
  const today = React.useMemo(() => midnight(new Date()), []);
  const minDate = React.useMemo(() => new Date(today.getTime() + MIN_T * DAY), [today]);
  const maxDate = React.useMemo(() => new Date(today.getTime() + MAX_T * DAY), [today]);
  const minMonth = React.useMemo(() => firstOfMonth(minDate), [minDate]);
  const maxMonth = React.useMemo(() => firstOfMonth(maxDate), [maxDate]);

  const tenure = React.useCallback((d: Date) => Math.round((d.getTime() - today.getTime()) / DAY), [today]);
  const inWindow = React.useCallback(
    (d: Date) => {
      const t = tenure(d);
      return t >= MIN_T && t <= MAX_T;
    },
    [tenure],
  );

  // Default selection: the first in-window date whose day-of-month matches the
  // borrower's saved salary day; else today+30 clamped into the window.
  const initialSel = React.useMemo(() => {
    for (let t = minDate.getTime(); t <= maxDate.getTime(); t += DAY) {
      const d = midnight(new Date(t));
      if (d.getDate() === value) return d;
    }
    const f = midnight(new Date(today.getTime() + 30 * DAY));
    if (f > maxDate) return maxDate;
    if (f < minDate) return minDate;
    return f;
    // value intentionally read once for the initial selection only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [today, minDate, maxDate]);

  const [sel, setSel] = React.useState<Date>(initialSel);
  const [view, setView] = React.useState<Date>(() => firstOfMonth(initialSel));

  // Push the default selection up on mount so the page's salaryDay matches.
  const pickedRef = React.useRef(false);
  React.useEffect(() => {
    if (!pickedRef.current) {
      pickedRef.current = true;
      onPick(initialSel);
    }
  }, [initialSel, onPick]);

  const choose = (d: Date) => {
    if (!inWindow(d)) return;
    setSel(d);
    onPick(d);
  };

  // Build the month grid (leading blanks + days).
  const y = view.getFullYear();
  const m = view.getMonth();
  const startDow = new Date(y, m, 1).getDay();
  const dim = new Date(y, m + 1, 0).getDate();
  const cells: Array<Date | null> = [];
  for (let i = 0; i < startDow; i++) cells.push(null);
  for (let d = 1; d <= dim; d++) cells.push(midnight(new Date(y, m, d)));

  const tenureDays = tenure(sel);
  const atMinMonth = view <= minMonth;
  const atMaxMonth = view >= maxMonth;

  return (
    <div className="cal-card">
      <div className="cal-main">
        <div className="cal-sublabel">Step 1 · Your salary day</div>
        <div className="cal-head">
          <div className="cal-title">{MONTHS[m]} {y}</div>
          <div className="cal-nav">
            <button
              type="button"
              className="cal-iconbtn"
              aria-label="Previous month"
              disabled={atMinMonth}
              onClick={() => !atMinMonth && setView(new Date(y, m - 1, 1))}
            >
              <ChevronLeft size={18} />
            </button>
            <button
              type="button"
              className="cal-iconbtn"
              aria-label="Next month"
              disabled={atMaxMonth}
              onClick={() => !atMaxMonth && setView(new Date(y, m + 1, 1))}
            >
              <ChevronRight size={18} />
            </button>
          </div>
        </div>
        <div className="cal-dows" aria-hidden="true">
          {DOW.map((d, i) => (
            <span key={i}>{d}</span>
          ))}
        </div>
        <div className="cal-grid" role="grid" aria-label="Pick your salary credit day">
          {cells.map((date, i) =>
            date == null ? (
              <div key={`b${i}`} aria-hidden="true" />
            ) : (
              <button
                key={date.getTime()}
                type="button"
                className={[
                  "cal-day",
                  !inWindow(date) ? "muted" : "",
                  sameDay(date, today) ? "today" : "",
                  sameDay(date, sel) ? "sel" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                aria-pressed={sameDay(date, sel)}
                aria-disabled={!inWindow(date)}
                onClick={() => choose(date)}
              >
                {date.getDate()}
              </button>
            ),
          )}
        </div>
        <div className="cal-legend">
          <span><i className="lg-sel" /> Your salary day</span>
          <span><i style={{ boxShadow: "inset 0 0 0 1.5px var(--gold-500)" }} /> Today</span>
          <span className="cal-hintnote">Selectable: 15–40 days out</span>
        </div>
      </div>

      <aside className="cal-side">
        <span className="eyebrow">Repaid in one instalment</span>
        <div className="cal-bigdate">{sel.getDate()} {MON[sel.getMonth()]}</div>
        <div className="cal-subdate">
          {WEEKDAYS[sel.getDay()]} {sel.getFullYear()} · {tenureDays} {tenureDays === 1 ? "day" : "days"} away
        </div>
        <p className="cal-note">
          We link your due date to your salary credit. Pick the day you&apos;re next paid — your
          advance is repaid in a single instalment on that day. Prepay anytime, no penalty.
        </p>
        <div
          className="cal-note"
          style={{ display: "inline-flex", alignItems: "center", gap: 8, color: "var(--gold-400)", fontWeight: 600 }}
        >
          <CalendarDays size={15} /> Salary day: {sel.getDate()}{ordinal(sel.getDate())} of each month
        </div>
      </aside>
    </div>
  );
}

function ordinal(n: number) {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return s[(v - 20) % 10] || s[v] || s[0];
}
