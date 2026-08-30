"use client";

/**
 * Dashboard-level search-and-fix for a customer's salary-credit day (admin). The Customers detail
 * page carries the full `SalaryDayCard` (with the projected-due-date preview); this panel is the
 * fast path for "someone's due date looks wrong, fix their salary day" without leaving the
 * dashboard — search, pick the day, save, done.
 */

import * as React from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Save, CalendarClock } from "lucide-react";
import { Input, InfoTooltip } from "@/components/ui";
import { customersApi, type CustomerSummary } from "@/lib/api/applications";
import { errMessage } from "@/components/staff/pipeline/hooks";

const MIN_QUERY_LENGTH = 2;
const MAX_RESULTS = 25;

function matches(c: CustomerSummary, needle: string): boolean {
  return (
    (c.name?.toLowerCase().includes(needle) ?? false) ||
    (c.mobile?.includes(needle) ?? false) ||
    (c.pan?.toLowerCase().includes(needle) ?? false)
  );
}

export function SalaryDaysPanel({ rows, loading }: { rows: CustomerSummary[]; loading: boolean }) {
  const [q, setQ] = React.useState("");
  const needle = q.trim().toLowerCase();
  const results = needle.length >= MIN_QUERY_LENGTH
    ? rows.filter((c) => matches(c, needle)).slice(0, MAX_RESULTS)
    : [];

  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center gap-2">
        <CalendarClock size={16} className="text-navy" />
        <h2 className="mb-0 text-xl">Salary dates</h2>
        <InfoTooltip content="Search a customer to view or correct the salary-credit day their due dates are computed from. Saving also moves a pending sanctioned offer's repayment date; an already-disbursed loan keeps its due date." />
      </div>
      <div className="rounded border border-line bg-white p-5 shadow-sm">
        <Input
          label="Search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Name, mobile, or PAN"
          className="!mb-3 max-w-sm"
        />
        {loading ? (
          <div className="h-20 animate-pulse rounded bg-grey-100" />
        ) : needle.length < MIN_QUERY_LENGTH ? (
          <p className="text-sm text-muted">Search for a customer to view or change their salary date.</p>
        ) : results.length === 0 ? (
          <p className="text-sm text-muted">No customers match &ldquo;{q.trim()}&rdquo;.</p>
        ) : (
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Mobile</th>
                  <th>PAN</th>
                  <th>Loan status</th>
                  <th>Salary day</th>
                  <th>Save</th>
                </tr>
              </thead>
              <tbody>
                {results.map((c) => (
                  <SalaryDayRow key={c.customerId} c={c} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

function SalaryDayRow({ c }: { c: CustomerSummary }) {
  const qc = useQueryClient();
  const [day, setDay] = React.useState(String(c.salaryCreditDay ?? 1));

  const save = useMutation({
    mutationFn: ({ customerId, day }: { customerId: number; day: number }) =>
      customersApi.changeSalaryDay(customerId, day),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-dashboard-customers"] });
      qc.invalidateQueries({ queryKey: ["customers"] });
    },
  });

  const unchanged = Number(day) === (c.salaryCreditDay ?? 0);

  return (
    <tr className="hover:bg-grey-50">
      <td className="staff-cell">{c.name ?? "—"}</td>
      <td>{c.mobile ?? "—"}</td>
      <td>{c.pan ?? "—"}</td>
      <td>{c.loanStatus ?? c.latestStatus ?? "—"}</td>
      <td>
        <select
          value={day}
          onChange={(e) => setDay(e.target.value)}
          className="rounded border border-line-2 bg-cream-50 px-2 py-1 text-xs"
          aria-label={`Salary day for ${c.name ?? `customer #${c.customerId}`}`}
        >
          {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => (
            <option key={d} value={d}>{d}</option>
          ))}
        </select>
        {c.salaryCreditDay == null && <span className="ml-1 text-muted">(unset)</span>}
      </td>
      <td>
        <button
          onClick={() => save.mutate({ customerId: c.customerId, day: Number(day) })}
          disabled={save.isPending || unchanged}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {save.isPending ? <Loader2 size={13} className="animate-spin" /> : <Save size={13} />} Save
        </button>
        {save.error && <p className="mt-1 text-xs text-error-700">{errMessage(save.error)}</p>}
      </td>
    </tr>
  );
}
