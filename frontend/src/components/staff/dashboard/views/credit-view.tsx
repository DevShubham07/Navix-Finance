"use client";

import * as React from "react";
import type { DashTabProps } from "../tab-props";
import { RatingFactors } from "../rating-factors";
import { SectionTitle } from "../chart-card";
import { ErrorState, Skeleton } from "@/components/ui";
import { BoardPanel, RoleCards, RoleChart, findChart, useOwnStaffKey, useRoleView } from "./role-parts";
import { fmtDayLong } from "../fmt";

/** Credit Head (own + executive boards) and Credit Executive (own board) views. */
export function CreditView({ params, open, periodLabel }: DashTabProps) {
  const head = params.view === "CREDIT_HEAD";
  const q = useRoleView(params);
  const staffKey = useOwnStaffKey(params);
  const d = q.data;
  const err = { loading: q.isLoading, error: q.error, onRetry: () => void q.refetch() };
  const pointRange = (title: string) => (date: string) =>
    open({ metric: "STAFF_FILES", key: staffKey, title: `${title} · ${fmtDayLong(date)}`, range: { from: date, to: date } });

  return (
    <div>
      <div className={head ? "grid grid-cols-1 gap-4 xl:grid-cols-2" : ""}>
        <BoardPanel
          params={params}
          open={open}
          board={head ? "CREDIT_HEAD" : "CREDIT_EXECUTIVE"}
          title={head ? "Credit Head leaderboard" : "Credit Executive leaderboard"}
          tone="royal"
          metric="STAFF_FILES"
        />
        {head && (
          <BoardPanel params={params} open={open} board="CREDIT_EXECUTIVE" title="Credit Executive leaderboard" tone="teal" metric="STAFF_FILES" />
        )}
      </div>

      <div className="mt-4">
        <RatingFactors factors={d?.factors} score={d?.score} periodLabel={periodLabel} />
      </div>

      <SectionTitle>Lead Overview</SectionTitle>
      {d ? (
        <RoleCards cards={d.cards} open={open} staffKey={staffKey} titlePrefix="Lead overview" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-6" />
      ) : (
        <Skeleton variant="line" rows={3} />
      )}

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <RoleChart
          chart={findChart(d, "assignedVsFollowups")}
          note="Files assigned per day versus follow-ups (call logs and 'mark pending' decisions) made by the same staff."
          onPoint={pointRange("Assigned files")}
          {...err}
        />
        <RoleChart
          chart={findChart(d, "assignedVsDisbursed")}
          note="Disbursed = loan disbursed on that day (the disbursal date), not the same as the lead stage 'Closed'."
          onPoint={pointRange("Assigned files")}
          {...err}
        />
      </div>
      <div className="mt-4">
        <RoleChart
          chart={findChart(d, "sanctionClosure")}
          note="Loans sanctioned on each day: how many are already closed (repaid) versus still open."
          onPoint={pointRange("Sanctioned files")}
          {...err}
        />
      </div>
    </div>
  );
}
