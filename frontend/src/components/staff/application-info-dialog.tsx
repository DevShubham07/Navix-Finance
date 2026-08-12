"use client";

/**
 * Compact, read-only ⓘ quick-summary modal — the answer to "staff can't triage a row without
 * opening the full `ApplicationDetailDialog`". Four stacked sections (Customer & KYC basics ·
 * CIBIL/bureau · Bank account · Loan disbursement details), no action buttons at all: this is a
 * glance, not a workbench. Deliberately its own dialog rather than a slimmed-down
 * `ApplicationDetailDialog` (decision in the plan) so it stays cheap to open from every queue row.
 *
 * Shares query keys with `application-detail-dialog.tsx` (`["staff-profile", id]`,
 * `["credit-brief", id]`, `["staff-loan", loanId]`) so opening ⓘ then `Open` on the same row never
 * refetches.
 *
 * Accepts either `applicationId` directly (queue rows already know it) or `customerId` (the
 * Customers page only has a customer row — this resolves to that customer's newest application via
 * the same `["customer-detail", customerId]` cache `CustomerDetailDialog`/`ApplicationDetailDialog`
 * already populate).
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { X, Loader2, User, Landmark, Banknote, Gauge } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { CreditBadge } from "@/components/staff/credit-badge";
import { KV } from "@/components/staff/detail-parts";
import { formatDate } from "@/lib/utils";
import { buildCostBreakdown, dueDateFromSalary, daysBetween } from "@/lib/calc/loan-math";
import {
  staffApi,
  customersApi,
  statusLabel,
  paiseToINR,
  type ApplicationView,
  type LoanView,
} from "@/lib/api/applications";
import { errMessage } from "@/components/staff/pipeline/hooks";

export interface ApplicationInfoDialogProps {
  /** Open directly against a known application id (queue rows, the applications register). */
  applicationId?: number | null;
  /** Open against a customer — resolves to that customer's newest application (Customers page). */
  customerId?: number | null;
  onClose: () => void;
}

export function ApplicationInfoDialog({ applicationId, customerId, onClose }: ApplicationInfoDialogProps) {
  const open = applicationId != null || customerId != null;

  // Resolve customerId -> latest application id. Same cache key as CustomerDetailDialog /
  // ApplicationDetailDialog's "Full customer page" so this never costs a second fetch there.
  const resolveQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open && applicationId == null && customerId != null,
  });
  const resolvedId = applicationId ?? resolveQ.data?.applications[0]?.id ?? null;
  const id = resolvedId ?? 0;
  const idReady = resolvedId != null;

  const appQ = useQuery({
    queryKey: ["staff-application", id],
    queryFn: () => staffApi.get(id),
    enabled: open && idReady,
  });
  const profileQ = useQuery({
    queryKey: ["staff-profile", id],
    queryFn: () => staffApi.getProfile(id),
    enabled: open && idReady,
    retry: false,
  });
  const app = appQ.data;
  const briefQ = useQuery({
    queryKey: ["credit-brief", id],
    queryFn: () => staffApi.creditBrief(id),
    enabled: open && idReady && app != null && app.status !== "DRAFT",
  });
  const hasLoan = app?.loanId != null;
  const loanQ = useQuery({
    queryKey: ["staff-loan", app?.loanId],
    queryFn: () => staffApi.loan(app!.loanId as number),
    enabled: open && hasLoan,
    retry: false,
  });
  const verificationQ = useQuery({
    queryKey: ["customer-verifications", id],
    queryFn: () => staffApi.verifications(id),
    enabled: open && idReady,
  });
  const kycAssigneesQ = useQuery({
    queryKey: ["staff-picker", "KYC_APPROVER"],
    queryFn: () => staffApi.creditExecutives("KYC_APPROVER"),
    enabled: open && idReady && app?.amountRequestedPaise == null,
    staleTime: 60_000,
  });

  const p = profileQ.data;
  const pennyDerived = ((verificationQ.data ?? []).find((row) => row.checkType === "PENNY_DROP")?.derived ?? {}) as Record<string, unknown>;
  const assignedName = kycAssigneesQ.data?.find((staff) => staff.id === app?.assignedExecutiveId)?.name;
  const loading = (applicationId == null && resolveQ.isLoading) || (idReady && appQ.isLoading);

  const displayName = p?.fullName ?? app?.customerName ?? (app ? `Customer #${app.customerId}` : "Application");

  return (
    <Dialog open={open} onClose={onClose} className="!max-w-[96vw] !w-[96vw]" aria-label="Quick summary">
      <div className="border-b border-line pb-3">
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <h3 className="font-serif text-lg text-navy">
              {displayName}
              {idReady && <span className="text-sm font-normal text-muted"> — Application #{id}</span>}
            </h3>
            <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
              {app && <span>#{app.customerId}</span>}
              {(p?.mobile ?? app?.customerMobile) && <span>· {p?.mobile ?? app?.customerMobile}</span>}
              {app && (
                <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">
                  {statusLabel(app.status)}
                </span>
              )}
              {briefQ.data?.available && (
                <CreditBadge
                  starRating={briefQ.data.starRating}
                  creditScore={briefQ.data.creditScore}
                  recommendation={briefQ.data.recommendation}
                />
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="-mt-1 flex-shrink-0 rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>
      </div>

      <div className="mt-3 grid h-[92vh] grid-cols-2 grid-rows-2 gap-3 overflow-hidden text-[10px]">
        {loading ? (
          <p className="flex items-center gap-2 py-8 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Loading…
          </p>
        ) : appQ.error ? (
          <p className="py-8 text-sm text-error-700">{errMessage(appQ.error)}</p>
        ) : !app ? (
          <p className="py-8 text-sm text-muted">No application found.</p>
        ) : (
          <>
            <InfoSection icon={User} title="Customer & KYC basics">
              <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
                <KV k="Name" v={p?.fullName ?? app.customerName} />
                <KV k="Mobile" v={p?.mobile ?? app.customerMobile} mono />
                <KV k="PAN" v={p?.pan ?? app.pan} mono />
                <KV k="Email" v={p?.email} />
                <KV k="DOB" v={p?.dob} />
                <KV k="Address" v={p?.address} />
                <KV k="Employer" v={p?.employer} />
                <KV k="Monthly salary" v={p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
              </dl>
            </InfoSection>

            <InfoSection icon={Gauge} title="CIBIL / bureau">
              {briefQ.isLoading ? (
                <p className="text-sm text-muted">Loading…</p>
              ) : briefQ.data?.available ? (
                <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
                  <KV k="CIBIL score" v={briefQ.data.creditScore != null ? String(briefQ.data.creditScore) : null} mono />
                  <KV k="Star rating" v={briefQ.data.starRating != null ? `${briefQ.data.starRating.toFixed(1)}★` : null} />
                  <KV k="Recommendation" v={briefQ.data.recommendation} />
                  {briefQ.data.summary && (
                    <div className="sm:col-span-2">
                      <p className="mt-1 rounded border border-line bg-grey-50 px-3 py-2 text-xs text-muted">
                        {briefQ.data.summary}
                      </p>
                    </div>
                  )}
                </dl>
              ) : (
                <p className="text-sm text-muted">No bureau pull yet.</p>
              )}
            </InfoSection>

            <InfoSection icon={Landmark} title="Bank account">
              <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
                <KV k="Account" v={app.disbursalAccountNumber ?? app.salaryAccountNumber ?? p?.salaryAccountNumber} mono />
                <KV k="Account source" v={app.disbursalAccountNumber ? "Confirmed disbursal account" : app.salaryAccountNumber ? "Application salary account" : "KYC profile salary account"} />
                <KV k="IFSC" v={app.disbursalIfsc ?? app.salaryIfsc ?? p?.salaryIfsc} mono />
                <KV k="IFSC source" v={app.disbursalIfsc ? "Confirmed disbursal account" : app.salaryIfsc ? "Application salary account" : "KYC profile salary account"} />
                <KV k="Account holder" v={app.disbursalHolderName ?? p?.fullName} />
                <KV k="Bank" v={app.disbursalBank ?? p?.salaryBank} />
                <KV k="Beneficiary name" v={pennyDerived.beneficiaryName != null ? String(pennyDerived.beneficiaryName) : null} />
                <KV k="Bank RRN" v={pennyDerived.bankRrn != null ? String(pennyDerived.bankRrn) : null} mono />
                <KV
                  k="Penny drop"
                  v={
                    app.disbursalAccountVerified === true || p?.pennyDropVerified === true
                      ? "Verified"
                      : app.disbursalAccountChanged === true
                        ? "Not verified — needs review"
                        : "Not run — kept salary account"
                  }
                />
              </dl>
            </InfoSection>

            <InfoSection icon={Banknote} title="Loan disbursement details">
              {hasLoan ? (
                loanQ.isLoading ? (
                  <p className="text-sm text-muted">Loading…</p>
                ) : loanQ.data ? (
                  <ActualDisbursement loan={loanQ.data} />
                ) : (
                  <p className="text-sm text-error-700">Could not load the loan.</p>
                )
              ) : app.amountRequestedPaise == null ? (
                <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
                  <KV k="Eligible limit" v={app.eligibleLimitPaise != null ? paiseToINR(app.eligibleLimitPaise) : null} mono />
                  <KV
                    k="KYC assignment"
                    v={`Assigned to ${assignedName ?? (app.assignedExecutiveId != null ? `staff #${app.assignedExecutiveId}` : "no one")} for KYC`}
                  />
                </dl>
              ) : (
                <ExpectedDisbursement app={app} />
              )}
            </InfoSection>
          </>
        )}
      </div>
    </Dialog>
  );
}

function InfoSection({
  icon: Icon,
  title,
  children,
}: {
  icon: typeof User;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-0 flex-col rounded border border-line bg-white p-4">
      <h4 className="mb-3 flex items-center gap-2 font-serif text-sm font-semibold text-navy">
        <Icon size={15} aria-hidden /> {title}
      </h4>
      <div className="min-h-0 flex-1 overflow-auto">{children}</div>
    </div>
  );
}

/** Actual figures once a loan is minted — same breakdown shape as `DisbursementFocus`. */
function ActualDisbursement({ loan }: { loan: LoanView }) {
  return (
    <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
      <KV k="Principal" v={paiseToINR(loan.principalPaise)} mono />
      <KV k="Processing fee" v={paiseToINR(loan.processingFeePaise)} mono />
      <KV k="GST" v={paiseToINR(loan.gstPaise)} mono />
      <KV k="Net disbursed" v={paiseToINR(loan.netDisbursedPaise)} mono />
      <KV k="Transaction ref" v={loan.disbursalTxnRef} mono />
      <KV k="Disbursed on" v={loan.disbursedOn ? formatDate(loan.disbursedOn) : null} />
      <KV k="Repayment due" v={loan.dueDate ? formatDate(loan.dueDate) : null} />
      <KV k="Contracted repayable (on-time)" v={paiseToINR(loan.totalRepayablePaise)} mono />
    </dl>
  );
}

/**
 * Pre-disbursement projection from `amountRequested`/`sanctionedAmount`, mirroring
 * `ProjectedCostBreakdown` (loan-breakdown.tsx) and `DisbursementFocus`'s "Amount to transfer"
 * caption — same `buildCostBreakdown` engine so the figures always agree.
 */
function ExpectedDisbursement({ app }: { app: ApplicationView }) {
  const amount = app.sanctionedAmountPaise ?? app.amountRequestedPaise;
  if (amount == null) {
    return <p className="text-sm text-muted">No amount requested or sanctioned yet.</p>;
  }

  let tenureDays = 30;
  let dueDate: Date | null = null;
  if (app.salaryCreditDay != null) {
    const today = new Date();
    const due = dueDateFromSalary({ disbursedOn: today, salaryDay: app.salaryCreditDay });
    dueDate = due;
    const d = daysBetween(today, due);
    if (d > 0) tenureDays = d;
  }
  const b = buildCostBreakdown(amount, tenureDays);

  return (
    <>
      <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
        <KV k="Principal" v={paiseToINR(b.principal)} mono />
        <KV k="Processing fee" v={paiseToINR(b.processingFee)} mono />
        <KV k="GST" v={paiseToINR(b.gstOnFee)} mono />
        <KV k="Net disbursed (expected)" v={paiseToINR(b.netDisbursed)} mono />
        <KV k={`Interest (${tenureDays}d, estimated)`} v={paiseToINR(b.interest)} mono />
        <KV k="Repayment due (estimated)" v={dueDate ? formatDate(dueDate.toISOString().slice(0, 10)) : null} />
        <KV k="Contracted repayable (expected)" v={paiseToINR(b.totalRepayable)} mono />
      </dl>
      <p className="mt-2 text-xs text-muted">
        Projection at {app.sanctionedAmountPaise != null ? "the sanctioned amount" : "the requested amount"} over an
        estimated {tenureDays}-day tenure. Final figures are set at disbursal.
      </p>
    </>
  );
}
