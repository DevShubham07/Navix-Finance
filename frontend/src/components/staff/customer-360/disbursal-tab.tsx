"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { BadgeCheck, Banknote, Landmark, XCircle } from "lucide-react";
import { Badge, EmptyState } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import { stageActionFor, stageActionTab } from "@/components/staff/customer-360/stage-actions";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { formatDate, formatDateTime } from "@/lib/utils";
import { staffApi, paiseToINR } from "@/lib/api/applications";

const NOTE = "mt-3 rounded border border-warning-100 bg-warning-50 px-3 py-2 text-[10.4px] text-warning-800";

export function DisbursalTab({ app, applicationId, detail }: TabCtx) {
  const verifQ = useQuery({
    queryKey: ["staff-verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId as number),
    enabled: applicationId != null,
  });
  if (applicationId == null || !app) return <EmptyState title="No application to show yet." />;

  const p = detail.profile;
  const step = (verifQ.data ?? []).find((s) => s.checkType === "PENNY_DROP");
  const d = step?.derived ?? {};
  const bankProofPending = d.bankProofPending === true;
  const changed = app.disbursalAccountChanged === true;
  // A verified account wins outright, whichever route got it there.
  const verified = app.disbursalAccountVerified === true || p?.pennyDropVerified === true || step?.status === "PASS";
  const failed = !verified && step?.status === "FAIL";
  const pending = !verified && !failed && (bankProofPending || changed || step?.status === "REVIEW");

  const pill = verified
    ? <Badge variant="success" size="sm">SUCCESS</Badge>
    : failed
      ? <Badge variant="error" size="sm">FAILED</Badge>
      : pending
        ? <Badge variant="warning" size="sm">PENDING</Badge>
        : <Badge variant="neutral" size="sm">Not run</Badge>;
  const dropIcon = failed ? XCircle : BadgeCheck;
  const dropTone = verified ? "text-success-700" : failed ? "text-error-700" : "text-muted";

  const score = typeof d.nameMatch === "number" ? d.nameMatch : (p?.nameMatchScore ?? null);
  const str = (k: string) => (typeof d[k] === "string" && d[k] !== "" ? (d[k] as string) : null);
  const action = stageActionTab(app.status) === "disbursal" ? stageActionFor(app) : null;
  const records = detail.loans.filter((l) => l.disbursedOn != null);

  return (
    <div className="space-y-3">
      {action && <div className="flex flex-wrap items-end gap-2">{action}</div>}

      <Section icon={Landmark} title="Bank details">
        <FieldGrid cols={3}>
          <Field label="Beneficiary">{app.disbursalHolderName ?? p?.fullName}</Field>
          <Field label="Account type">{null}</Field>
          <Field label="Bank">{app.disbursalBank ?? p?.salaryBank}</Field>
          <Field label="Account no" mono>{app.disbursalAccountNumber ?? p?.salaryAccountNumber}</Field>
          <Field label="IFSC" mono>{app.disbursalIfsc ?? p?.salaryIfsc}</Field>
          <Field label="Branch">{null}</Field>
        </FieldGrid>
      </Section>

      <Section
        icon={dropIcon}
        title={<span className={dropTone}>Penny-drop verification</span>}
        pill={<span className="ml-1">{pill}</span>}
      >
        <FieldGrid cols={4}>
          <Field label="Account exists">{typeof d.accountExists === "boolean" ? (d.accountExists ? "Yes" : "No") : null}</Field>
          <Field label="Beneficiary at bank">{str("beneficiaryName")}</Field>
          <Field label="Name match score" tone={verified ? "success" : "ink"}>
            {score != null ? `${Math.round(score <= 1 ? score * 100 : score)}%` : null}
          </Field>
          <Field label="Provider">{step?.provider}</Field>
          <Field label="Verified at">{step?.checkedAt ? formatDateTime(step.checkedAt) : null}</Field>
          <Field label="Bank RRN" mono>{str("bankRrn")}</Field>
          <Field label="Provider name match">{str("providerNameMatch")}</Field>
          <Field label="Reason">{str("reason")}</Field>
        </FieldGrid>
        {str("manualBy") && (
          <p className="mt-3 text-[10.4px] text-muted">
            Manually overridden by {str("manualBy")}
            {str("manualAt") ? ` on ${formatDateTime(str("manualAt") as string)}` : ""}.
          </p>
        )}
        {bankProofPending && !verified && (
          <p className={NOTE}>Awaiting cancelled cheque / passbook — verify the proof against the account above before releasing.</p>
        )}
        {!verified && !bankProofPending && !step && !changed && (
          <p className={NOTE}>
            Never penny-dropped — the borrower kept the salary account they typed at intake. Check the number against their
            payslips before releasing funds.
          </p>
        )}
        {!verified && !bankProofPending && (changed || step != null) && (
          <p className={NOTE}>The penny-drop check has not passed on this account. Confirm it before releasing funds.</p>
        )}
      </Section>

      <Section icon={Banknote} title="Disbursal records">
        {records.length === 0 ? (
          <EmptyState title="Nothing disbursed yet." className="py-4" />
        ) : (
          <div className="staff-table-scroll">
            <table className="staff-data-table staff-table-fit">
              <thead>
                <tr>
                  <th>S.No</th>
                  <th>Loan</th>
                  <th>Txn ref</th>
                  <th>Disbursed on</th>
                  <th className="num">Net disbursed</th>
                </tr>
              </thead>
              <tbody>
                {records.map((l, i) => (
                  <tr key={l.id}>
                    <td>{i + 1}</td>
                    <td>#{l.id}</td>
                    <td className="font-mono">{l.disbursalTxnRef ?? "—"}</td>
                    <td>{formatDate(l.disbursedOn as string)}</td>
                    <td className="num">{paiseToINR(l.netDisbursedPaise)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </div>
  );
}
