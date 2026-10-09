"use client";

import * as React from "react";
import { ErrorState, Skeleton } from "@/components/ui";
import { SectionTitle } from "../chart-card";
import type { DashTabProps } from "../tab-props";
import { RateTable } from "../tabs/business-snapshot";
import { InterestPf } from "../tabs/snapshot-revenue";
import { RoleCards, useRoleView } from "./role-parts";

/** Disbursement Head: queue cards, Interest & PF (daily), PF-wise table. */
export function DisbursementView(props: DashTabProps) {
  const { params, open } = props;
  const q = useRoleView(params);
  const d = q.data;
  return (
    <div>
      <SectionTitle>Disbursement</SectionTitle>
      {d ? (
        <RoleCards cards={d.cards} open={open} variant="gradient" titlePrefix="Disbursement" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-6" />
      ) : (
        <Skeleton variant="line" rows={3} />
      )}

      <div className="mt-6">
        <InterestPf {...props} />
      </div>

      <div className="mt-4">
        <RateTable
          title="PF-wise Table"
          info="Loans disbursed in the period grouped by processing-fee percentage."
          rows={d?.table ?? undefined}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => void q.refetch()}
          metric="PF_RATE"
          open={open}
        />
      </div>
    </div>
  );
}
