"use client";

import * as React from "react";
import { Banknote, CalendarCheck, Handshake } from "lucide-react";
import { ErrorState, Skeleton } from "@/components/ui";
import { paiseToINR } from "@/lib/api/applications";
import { SectionTitle } from "../chart-card";
import { TONE_TEXT } from "../colors";
import { fmtDayLong } from "../fmt";
import { RatingFactors } from "../rating-factors";
import type { DashTabProps } from "../tab-props";
import { IconSquare } from "../ui-bits";
import { CollectionAllocationTab } from "../tabs/collection-allocation-tab";
import { PreclosureWeek } from "../tabs/snapshot-revenue";
import { SnapshotMonthly } from "../tabs/snapshot-monthly";
import { BoardPanel, RoleCards, RoleChart, findChart, useOwnStaffKey, useRoleView } from "./role-parts";

/** Collection Head (own + executive boards, allocation) and Collection Executive views. */
export function CollectionView(props: DashTabProps) {
  const { params, open, periodLabel } = props;
  const head = params.view === "COLLECTION_HEAD";
  const q = useRoleView(params);
  const staffKey = useOwnStaffKey(params);
  const d = q.data;
  const ptp = d?.ptp;

  const ptpCards = [
    { label: "Total after assignment", value: paiseToINR(ptp?.totalAfterAssignmentPaise), hint: "Verified payments on assigned cases, paid on or after a promise-to-pay logged by this officer.", icon: Banknote, tone: "emerald" as const },
    { label: "Same-day collection", value: paiseToINR(ptp?.sameDayPaise), hint: "Of those, money that arrived on the very day the promise was logged.", icon: Handshake, tone: "blue" as const },
    { label: "Latest payment", value: ptp?.latestPaymentOn ? fmtDayLong(ptp.latestPaymentOn) : "—", hint: "Date of the most recent verified payment on a promised case.", icon: CalendarCheck, tone: "violet" as const },
  ];

  return (
    <div>
      <div className={head ? "grid grid-cols-1 gap-4 xl:grid-cols-2" : ""}>
        <BoardPanel
          params={params}
          open={open}
          board={head ? "COLLECTION_HEAD" : "COLLECTION_EXECUTIVE"}
          title={head ? "Collection Head leaderboard" : "Collection Executive leaderboard"}
          tone="royal"
          metric="STAFF_CASES"
        />
        {head && (
          <BoardPanel params={params} open={open} board="COLLECTION_EXECUTIVE" title="Collection Executive leaderboard" tone="teal" metric="STAFF_CASES" />
        )}
      </div>

      <div className="mt-4">
        <RatingFactors factors={d?.factors} score={d?.score} periodLabel={periodLabel} />
      </div>

      <SectionTitle>Collection Overview</SectionTitle>
      {d ? (
        <RoleCards cards={d.cards} open={open} staffKey={staffKey} variant="gradient" titlePrefix="Collection" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-6" />
      ) : (
        <Skeleton variant="line" rows={3} />
      )}

      <div className="mt-6">
        <SnapshotMonthly {...props} topOnly />
      </div>

      <div className="mt-4">
        <PreclosureWeek {...props} />
      </div>

      <SectionTitle>Promise to Pay</SectionTitle>
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[2fr_1fr]">
        <RoleChart
          chart={findChart(d, "ptpCollected")}
          note="Verified money collected on your assigned cases, paid on or after a promise-to-pay you logged."
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          onPoint={(date) =>
            open({ metric: "STAFF_CASES", key: staffKey, title: `PTP collected · ${fmtDayLong(date)}`, range: { from: date, to: date } })
          }
        />
        <div className="grid grid-cols-1 gap-3">
          {ptpCards.map((c) => {
            const Icon = c.icon;
            return (
              <div
                key={c.label}
                title={`${c.label}: ${c.value}. ${c.hint}`}
                className="surface flex items-center gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-md"
              >
                <IconSquare color={TONE_TEXT[c.tone]}>
                  <Icon size={16} />
                </IconSquare>
                <div>
                  <p className="m-0 text-xs font-medium text-slate">{c.label}</p>
                  <p className="figure-display m-0 mt-1 text-[1.6rem] text-ink">{c.value}</p>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {head && (
        <>
          <SectionTitle>Collection Allocation</SectionTitle>
          <CollectionAllocationTab {...props} />
        </>
      )}
    </div>
  );
}
