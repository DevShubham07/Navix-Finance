"use client";

/** Loan tab (design §3.2): the current loan's card, sanction details and disbursal details. */

import * as React from "react";
import { Landmark } from "lucide-react";
import { Badge, EmptyState } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import {
  FinancialSummaryStrip,
  LoanCardHeader,
  LoanSelector,
  useLoanData,
  useSelectedLoanId,
} from "@/components/staff/customer-360/loan-card";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { daysBetween } from "@/lib/calc/loan-math";
import { isoDayToLocalDate, istCalendarToday } from "@/lib/customers/customer-360";
import { formatDate, formatDateTime } from "@/lib/utils";
import { paiseToINR, statusLabel } from "@/lib/api/applications";

const inr = (p: number | null | undefined) => (p == null ? null : paiseToINR(p));

export function LoanTab({ listing, ...ctx }: TabCtx & { listing?: React.ReactNode }) {
  const [loanId, setLoanId] = useSelectedLoanId(ctx);
  const [collapsed, setCollapsed] = React.useState(false);
  const { loan, outstanding } = useLoanData(ctx, loanId);
  const { app, detail } = ctx;

  return (
    <div className="space-y-4">
      {loan == null ? (
        <EmptyState
          icon={<Landmark size={20} />}
          title={`No loan on this application yet — it is at ${app ? statusLabel(app.status) : "no application"}.`}
          action={
            <button type="button" className="btn btn-sm btn-outline" onClick={() => ctx.onTabChange?.("sanction")}>
              Go to Sanction
            </button>
          }
        />
      ) : (
        <Section title="Loan">
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <LoanSelector loans={detail.loans} value={loan.id} onChange={setLoanId} />
            </div>
            <LoanCardHeader ctx={ctx} loan={loan} collapsed={collapsed} onToggle={() => setCollapsed((c) => !c)} />
            {!collapsed && (
              <>
                <FinancialSummaryStrip loan={loan} outstanding={outstanding} />
                <LoanFacts ctx={ctx} loanId={loan.id} />
              </>
            )}
          </div>
        </Section>
      )}
      {listing}
    </div>
  );
}

function LoanFacts({ ctx, loanId }: { ctx: TabCtx; loanId: number }) {
  const { app, detail } = ctx;
  const loan = detail.loans.find((l) => l.id === loanId)!;
  const loanApp = detail.applications.find((a) => a.loanId === loan.id) ?? app;
  const due = isoDayToLocalDate(loan.dueDate);
  const disbursed = isoDayToLocalDate(loan.disbursedOn);
  const overdue = loan.status === "OVERDUE";
  const tenure = loanApp?.sanctionTenureDays ?? (due && disbursed ? daysBetween(disbursed, due) : null);
  const daysLate = overdue && due ? Math.max(0, daysBetween(due, istCalendarToday())) : 0;
  const salaryDay = loanApp?.salaryCreditDay;
  return (
    <>
      <FieldGrid cols={4} className="border-y border-line py-2.5">
        <Field label="Loan number" keyLabel>#{loan.id}</Field>
        <Field label="Customer no" keyLabel>#{detail.customerId}</Field>
        <Field label="Max eligible">{inr(loanApp?.eligibleLimitPaise)}</Field>
        <Field label="Requested amount" keyLabel>{inr(loanApp?.amountRequestedPaise)}</Field>
      </FieldGrid>

      <h4 className="text-[9.6px] font-bold text-ink">Sanction details</h4>
      <FieldGrid cols={6}>
        <Field label="Net disbursed" keyLabel>{inr(loan.netDisbursedPaise)}</Field>
        <Field label="Sanctioned on">{loanApp?.sanctionedAt ? formatDate(loanApp.sanctionedAt) : null}</Field>
        <Field label="Interest rate">1% / day</Field>
        <Field label="Processing fee" caption="(10%)">{inr(loan.processingFeePaise)}</Field>
        <Field label="GST on fee" caption="(18%)">{inr(loan.gstPaise)}</Field>
        <Field label="Contracted repayable" keyLabel tone="navy">{inr(loan.totalRepayablePaise)}</Field>
        <Field
          keyLabel label="Repayment date"
          tone={overdue ? "error" : "ink"}
          caption={salaryDay ? `on the borrower's salary day (day ${salaryDay})` : "on the borrower's salary day"}
        >
          {due ? formatDate(due) : null}
          {overdue && daysLate > 0 && <Badge variant="error" className="ml-1">+{daysLate}d</Badge>}
        </Field>
        <Field label="Tenure" tone="warning">{tenure != null ? `${tenure} days` : null}</Field>
        <Field label="Interest at term" keyLabel tone="warning">{inr(loan.totalRepayablePaise - loan.principalPaise)}</Field>
        <Field label="Sanctioned amount" keyLabel>{inr(loanApp?.sanctionedAmountPaise)}</Field>
        <Field label="Sanctioned by">{loanApp?.creditDecidedByName}</Field>
        <Field label="Total deductions">{inr(loan.processingFeePaise + loan.gstPaise)}</Field>
        <Field label="Purpose">{loanApp?.purpose}</Field>
        <Field label="Salary day">{salaryDay ? `Day ${salaryDay}` : null}</Field>
        <Field label="Decided at">{loanApp?.sanctionedAt ? formatDateTime(loanApp.sanctionedAt) : null}</Field>
        <Field label="Remarks">{loanApp?.sanctionRemarks}</Field>
        <Field label="Risk category">{detail.profile?.riskCategory}</Field>
      </FieldGrid>

      <h4 className="text-[9.6px] font-bold text-ink">Disbursal details</h4>
      <FieldGrid cols={7}>
        <Field label="Disbursed on" keyLabel>{loan.disbursedOn ? formatDate(loan.disbursedOn) : null}</Field>
        <Field label="Txn ref" keyLabel mono>{loan.disbursalTxnRef}</Field>
        <Field label="Payable account" mono caption={loanApp?.disbursalBank ?? undefined}>
          {loanApp?.disbursalAccountNumber}
        </Field>
        <Field label="Payment mode">Manual bank transfer</Field>
        <Field label="Released by">{loanApp?.disbursedByName}</Field>
        <Field label="Written off" tone={loan.status === "WRITTEN_OFF" ? "error" : "ink"}>
          {loan.status === "WRITTEN_OFF" ? "Yes" : "No"}
        </Field>
        <Field label="Disbursed" tone="success">{loan.disbursedOn ? "✓ Yes" : "No"}</Field>
      </FieldGrid>
    </>
  );
}
