"use client";

import * as React from "react";
import { ErrorState, Skeleton } from "@/components/ui";
import { SectionTitle } from "../chart-card";
import type { DashTabProps } from "../tab-props";
import { InterestPf, Revenue } from "../tabs/snapshot-revenue";
import { RoleCards, useRoleView } from "./role-parts";

/** Accountant: repayment verification cards, revenue area, Interest & PF (daily). */
export function AccountantView(props: DashTabProps) {
  const { params, open } = props;
  const q = useRoleView(params);
  const d = q.data;
  return (
    <div>
      <SectionTitle>Repayments</SectionTitle>
      {d ? (
        <RoleCards cards={d.cards} open={open} variant="gradient" titlePrefix="Repayments" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-6" />
      ) : (
        <Skeleton variant="line" rows={3} />
      )}

      <div className="mt-6 grid grid-cols-1 gap-4 xl:grid-cols-2">
        <Revenue {...props} />
        <InterestPf {...props} />
      </div>
    </div>
  );
}
