"use client";

import * as React from "react";
import { ErrorState, Skeleton } from "@/components/ui";
import { SectionTitle } from "../chart-card";
import { RatingFactors } from "../rating-factors";
import type { DashTabProps } from "../tab-props";
import { BoardPanel, RoleCards, RoleChart, findChart, useRoleView } from "./role-parts";

/** Telecaller (Sales Ops): leads, calls, callbacks, conversions, leads-vs-calls line, own board. */
export function TelecallerView({ params, open, periodLabel }: DashTabProps) {
  const q = useRoleView(params);
  const d = q.data;
  return (
    <div>
      <SectionTitle>Lead Overview</SectionTitle>
      {d ? (
        <RoleCards cards={d.cards} open={open} titlePrefix="Leads" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-6" />
      ) : (
        <Skeleton variant="line" rows={3} />
      )}

      <div className="mt-4">
        <RoleChart
          chart={findChart(d, "leadsVsCalls")}
          note="Leads created per day versus calls logged by you."
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
        />
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <BoardPanel params={params} open={open} board="TELECALLER" title="Sales Ops leaderboard" tone="royal" />
        <RatingFactors factors={d?.factors} score={d?.score} periodLabel={periodLabel} />
      </div>
    </div>
  );
}
