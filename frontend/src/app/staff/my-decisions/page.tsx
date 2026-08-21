"use client";

/**
 * Decision history (revamp.md Phase 2, decision 32).
 *
 * Everyone sees their own decisions; a Head can switch to a team member's; ADMIN can open anyone's.
 * The switcher only lists staff the server will actually allow — it's a convenience over the same
 * rule the backend enforces, not the gate itself.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { PageHeader, StatCard } from "@/components/staff/staff-ui";
import { Select } from "@/components/ui";
import Link from "next/link";
import { PeriodPicker } from "@/components/staff/period-picker";
import { rangeFor, presetMatching, type Range } from "@/lib/period";
import { staffApi, statusLabel, paiseToINR, type ApplicationStatus } from "@/lib/api/applications";
import { useStaffMe, errMessage } from "@/components/staff/pipeline/hooks";
import { customerPageHref } from "@/lib/customers/customer-page";
import { formatDate } from "@/lib/utils";
import { usePagination, PaginationBar } from "@/components/staff/pipeline/pagination";

/** Verb per audited action, so the log reads as a sentence rather than an enum dump. */
const ACTION_LABEL: Record<string, string> = {
  ASSIGN: "Assigned to an executive",
  SANCTION: "Accepted lead (sanctioned)",
  REJECT_LEAD: "Rejected lead",
  MARK_PENDING: "Marked lead pending",
  KYC_APPROVE: "Cleared KYC",
  KYC_REJECT: "Rejected KYC",
  // Retired with the credit maker-checker (V45) — historical rows still carry them.
  EXEC_APPROVE: "Recommended (legacy)",
  EXEC_REJECT: "Rejected at credit review (legacy)",
  HEAD_APPROVE: "Approved as Credit Head (legacy)",
  HEAD_REJECT: "Rejected as Credit Head (legacy)",
  DISB_ACCEPT: "Released for disbursal",
  DISB_REJECT: "Rejected at disbursement",
  VALIDATE_SUCCESS: "Confirmed transfer",
  VALIDATE_FAIL: "Marked transfer failed",
  RETRY: "Retried disbursement",
  CANCEL: "Cancelled",
  REASSIGN: "Reassigned to another executive",
};

export default function MyDecisionsPage() {
  const me = useStaffMe();
  const [staffId, setStaffId] = React.useState("");
  const [preset, setPreset] = React.useState("all");
  const [custom, setCustom] = React.useState<Range>({});

  // Seeded from `?staffId=&from=&to=` so the staff-performance table can link straight into one
  // person's history *for the period it was showing* — land on a different window and the totals
  // there would look wrong here. Read off `location` in an effect rather than via
  // `useSearchParams`, which would drag this page behind a Suspense boundary for static
  // prerendering; the controls below take over afterwards. The server still enforces who may be
  // opened.
  React.useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requested = params.get("staffId");
    if (requested) setStaffId(requested);
    const from = params.get("from") ?? undefined;
    const to = params.get("to") ?? undefined;
    if (from || to) {
      setCustom({ from, to });
      setPreset(presetMatching({ from, to }));
    }
  }, []);

  const range: Range = React.useMemo(() => rangeFor(preset, custom), [preset, custom]);

  const team = useQuery({
    queryKey: ["decisions-inspectable"],
    queryFn: () => staffApi.inspectableStaff(),
    staleTime: 60_000,
  });

  const q = useQuery({
    queryKey: ["decisions", staffId, range.from ?? "", range.to ?? ""],
    queryFn: () => staffApi.decisions(staffId ? Number(staffId) : undefined, range.from, range.to),
  });

  // The same aggregate the performance dashboard renders, narrowed to this one person, so the two
  // pages can never disagree about what someone did.
  const summaryQ = useQuery({
    queryKey: ["decisions-summary", staffId, range.from ?? "", range.to ?? ""],
    queryFn: () =>
      staffApi.performance(range.from, range.to, staffId ? Number(staffId) : undefined),
    retry: false,
  });
  const stats = summaryQ.data?.rows?.[0];
  const callTrackingSince = summaryQ.data?.callTrackingSince;
  const callsUntracked = !!callTrackingSince && !!range.to && range.to < callTrackingSince;
  const callsNote = callTrackingSince
    ? `Telecaller + collections calls. Calls have only been attributed to a person since ${callTrackingSince}; anything earlier isn't counted.`
    : undefined;

  const rows = q.data ?? [];
  const others = (team.data ?? []).filter((s) => String(s.id) !== String(me.data?.id));
  const { pageRows, page, setPage, pageSize, setPageSize, pageCount, total } = usePagination(rows);

  // Name the person on screen: arriving from the performance table you have picked someone
  // specific, and a page headed only "Decision history" gives no confirmation you opened the
  // right one.
  const whose = staffId ? others.find((s) => String(s.id) === staffId)?.name : null;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Decision history"
        subtitle={whose
          ? `${whose} — every lifecycle decision, newest first.`
          : "Every lifecycle decision you've made, newest first."}
      />

      {others.length > 0 && (
        <div className="flex items-center gap-3">
          <Select
            aria-label="Whose decisions to show"
            className="!mb-0"
            value={staffId}
            onChange={(e) => setStaffId(e.target.value)}
          >
            <option value="">My decisions</option>
            {others.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          {q.isFetching && <Loader2 size={15} className="animate-spin text-muted" />}
        </div>
      )}

      <PeriodPicker preset={preset} onPreset={setPreset} custom={custom} onCustom={setCustom} />

      {/* Totals for whoever is selected. Deliberately the same figures as the performance
          dashboard: this page is what that page links into, so a reader arrives expecting them. */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="Approved" value={stats ? stats.accepted : "—"} accent="success" />
        <StatCard label="Rejected" value={stats ? stats.rejected : "—"} accent="error" />
        <StatCard
          label="Total actions"
          value={stats ? stats.totalActions : "—"}
          info="Every logged action in this period. The list below shows decisions only, so it can be shorter than this."
        />
        <StatCard
          label="In queue now"
          value={stats ? stats.pendingNow : "—"}
          accent="gold"
          info={`Files sitting with ${staffId ? "them" : "you"} right now. A live snapshot — it does not change with the selected period.`}
        />
        <StatCard
          label="Calls"
          value={stats && !callsUntracked ? stats.callsMade : "—"}
          info={callsNote}
        />
      </div>
      {summaryQ.isError && (
        <p className="text-sm text-error-700">{errMessage(summaryQ.error)}</p>
      )}

      <section className="staff-table-scroll rounded border border-line bg-white shadow-sm">
        {q.error ? (
          <p className="px-5 py-4 text-sm text-error-700">{errMessage(q.error)}</p>
        ) : q.isLoading ? (
          <p className="px-5 py-6 text-center text-sm text-muted">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="px-5 py-6 text-center text-sm text-muted">No decisions recorded yet.</p>
        ) : (
          <>
          <table className="staff-data-table">
            <thead>
              {/* What was decided, broken out of the raw event payload. The backend used to hand
                  this page strings like "amountPaise=500000 salaryCreditDay=1" and they were
                  rendered verbatim; DecisionNotes now parses them, so each value gets a real
                  column and "Remark" carries only what a human actually typed. */}
              <tr>
                <th>S.No.</th>
                <th>When</th>
                <th>Application</th>
                <th>Customer ID</th>
                <th>Customer</th>
                <th>PAN</th>
                <th>Decision</th>
                <th>Outcome</th>
                <th className="text-right">Amount</th>
                <th>Repayment date</th>
                <th>Assignee</th>
                <th>Txn ref</th>
                <th>Remark</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r, i) => (
                <tr key={`${r.applicationId}-${r.at}-${i}`}>
                  <td className="text-muted">{(page - 1) * pageSize + i + 1}</td>
                  <td className="whitespace-nowrap text-muted">{formatDate(r.at)}</td>
                  <td className="font-mono">#{r.applicationId}</td>
                  <td className="font-mono text-muted">
                    {r.customerId != null ? `#${r.customerId}` : "—"}
                  </td>
                  <td className="staff-cell">
                    {r.customerId != null && r.customerName ? (
                      <Link href={customerPageHref(r.customerId)} className="font-semibold text-navy hover:underline">
                        {r.customerName}
                      </Link>
                    ) : (
                      (r.customerName ?? "—")
                    )}
                  </td>
                  <td className="font-mono text-ink">{r.pan || "—"}</td>
                  <td className="font-semibold text-navy">{ACTION_LABEL[r.action] ?? r.action}</td>
                  <td>{statusLabel(r.toStatus as ApplicationStatus)}</td>
                  <td className="whitespace-nowrap text-right font-mono text-ink">
                    {r.amountPaise != null ? paiseToINR(r.amountPaise) : "—"}
                  </td>
                  <td className="whitespace-nowrap">
                    {r.repaymentDate ? formatDate(r.repaymentDate) : "—"}
                  </td>
                  <td className="staff-cell">
                    {r.assigneeName ?? (r.assigneeId != null ? `#${r.assigneeId}` : "—")}
                  </td>
                  <td className="font-mono text-muted">{r.txnRef || "—"}</td>
                  {/* The raw event payload stays as the tooltip — audit trail, not a column. */}
                  <td className="staff-cell text-muted" title={r.notes ?? undefined}>
                    {r.remark ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <PaginationBar
            page={page}
            pageCount={pageCount}
            setPage={setPage}
            total={total}
            pageSize={pageSize}
            setPageSize={setPageSize}
          />
          </>
        )}
      </section>
    </div>
  );
}
