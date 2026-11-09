"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Building2, FileSearch, IndianRupee, Loader2, TrendingUp } from "lucide-react";
import { Badge, ConfirmDialog, EmptyState, Skeleton, toast } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { DocCard } from "@/components/staff/doc-card";
import { Section, docTypeLabel, useCustomerDocumentGroups, viewDocument } from "@/components/staff/detail-parts";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { CrifAnalysisSection } from "@/components/staff/customer-360/crif-analysis-panel";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import { bankAnalysisApi } from "@/lib/api/bank-analysis";
import { paiseToINR } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

const VERDICT = {
  ACCEPT: { banner: "bg-success-50 border-success-600", badge: "success" },
  REVIEW: { banner: "bg-warning-50 border-warning-800", badge: "warning" },
  REJECT: { banner: "bg-error-50 border-error-600", badge: "error" },
} as const;

export function BankingTab({ detail, customerId, applicationId }: TabCtx) {
  const [busy, setBusy] = React.useState<string | null>(null);
  const openDoc = async (key: string, appId: number, doc: Parameters<typeof viewDocument>[1]) => {
    if (busy) return;
    setBusy(key);
    try {
      await viewDocument(appId, doc);
    } catch {
      toast.error("Could not open the document");
    } finally {
      setBusy(null);
    }
  };
  const p = detail.profile;
  const qc = useQueryClient();
  const analysisQ = useQuery({
    queryKey: ["bank-analysis", customerId],
    queryFn: () => bankAnalysisApi.get(customerId),
    // Signzy's analysis is asynchronous; the backend advances the stored row on each read, so while
    // one is running the tab keeps asking. It stops the moment the report lands or fails.
    refetchInterval: (q) => (q.state.data?.status === "PENDING" ? 15_000 : false),
  });
  const [confirmRun, setConfirmRun] = React.useState(false);
  const runAnalysis = useMutation({
    mutationFn: () => bankAnalysisApi.run(customerId),
    onSuccess: () => {
      setConfirmRun(false);
      toast.success("Statements submitted. Signzy is analysing them now.");
      void qc.invalidateQueries({ queryKey: ["bank-analysis", customerId] });
    },
    onError: (e) => {
      setConfirmRun(false);
      toast.error(errMessage(e));
      // A failed submission is still recorded server-side; show it.
      void qc.invalidateQueries({ queryKey: ["bank-analysis", customerId] });
    },
  });
  const { groupsQ, groups } = useCustomerDocumentGroups(customerId);
  const docsOf = (type: string) =>
    groups.flatMap((g) =>
      g.documents.filter((d) => d.docType.toUpperCase() === type).map((doc) => ({ appId: g.applicationId, doc })),
    );
  const a = analysisQ.data ?? null;
  const latestApp = detail.applications.find((x) => x.id === applicationId) ?? detail.applications[0];
  const salaryDay = latestApp?.salaryCreditDay != null ? `Day ${latestApp.salaryCreditDay}` : null;
  const declared = p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null;

  const docGrid = (items: ReturnType<typeof docsOf>, empty: string) =>
    groupsQ.isLoading ? (
      <Skeleton variant="line" rows={2} />
    ) : items.length === 0 ? (
      <EmptyState title={empty} className="py-4" />
    ) : (
      <div className="grid gap-3 md:grid-cols-3">
        {items.map(({ appId, doc }, i) => (
          <DocCard
            key={`${appId}-${doc.id}`}
            n={i + 1}
            type={docTypeLabel(doc.docType)}
            date={formatDateTime(doc.uploadedAt)}
            meta={`Application #${appId}`}
            password={doc.filePassword}
            onView={() => void openDoc(`${appId}-${doc.id}`, appId, doc)}
            busy={busy === `${appId}-${doc.id}`}
          />
        ))}
      </div>
    );

  const analysisPill =
    a?.status === "ANALYSED" ? (
      <Badge variant="success" size="sm">ANALYSED</Badge>
    ) : a?.status === "FAILED" ? (
      <Badge variant="error" size="sm">FAILED</Badge>
    ) : (
      <Badge variant="neutral" size="sm">{a ? "PENDING" : "Analysis pending"}</Badge>
    );

  return (
    <div className="space-y-4">
      <Section title="Salary bank" icon={Building2}>
        <FieldGrid cols={3}>
          <Field label="Bank">{p?.salaryBank}</Field>
          <Field label="Account" mono>{p?.salaryAccountNumber}</Field>
          <Field label="IFSC" mono>{p?.salaryIfsc}</Field>
          <Field label="Account mobile" mono>{p?.salaryAccountMobile}</Field>
          <Field label="Penny drop" tone={p?.pennyDropVerified ? "success" : "ink"}>
            {p?.pennyDropVerified ? "✓ Yes" : "No"}
          </Field>
          <Field label="Source">KYC profile</Field>
        </FieldGrid>
      </Section>

      <Section
        title="Bank statement analysis"
        icon={TrendingUp}
        pill={analysisPill}
        action={
          <div className="flex items-center gap-2">
            {a?.excelUrl ? (
              <a href={a.excelUrl} target="_blank" rel="noreferrer" className="btn btn-sm btn-outline">
                ⬇ Download Excel
              </a>
            ) : null}
            {a?.jsonUrl ? (
              <a href={a.jsonUrl} target="_blank" rel="noreferrer" className="btn btn-sm btn-outline">
                ⬇ JSON
              </a>
            ) : null}
            {/* Billed per statement, so it is never automatic — and never a second run while one is
                still in flight (the backend refuses that too). */}
            <PermissionGate permission={["kyc:approve", "loan:approve"]}>
              <button
                type="button"
                className="btn btn-sm btn-primary"
                disabled={runAnalysis.isPending || a?.status === "PENDING"}
                onClick={() => setConfirmRun(true)}
              >
                {runAnalysis.isPending ? <Loader2 size={14} className="animate-spin" /> : null}
                {a ? "Re-run analysis" : "Run analysis"}
              </button>
            </PermissionGate>
          </div>
        }
      >
        <ConfirmDialog
          open={confirmRun}
          title="Run bank statement analysis?"
          body="Signzy charges for every statement analysed. This sends every bank statement on file for this customer."
          confirmLabel="Run analysis"
          busy={runAnalysis.isPending}
          onClose={() => setConfirmRun(false)}
          onConfirm={() => runAnalysis.mutate()}
        />
        {analysisQ.isLoading ? (
          <Skeleton variant="line" rows={3} />
        ) : !a ? (
          <EmptyState
            icon={<FileSearch size={22} />}
            title="Not analysed"
            hint="The bank-statement analyser has not run for this customer yet."
          />
        ) : a.status !== "ANALYSED" ? (
          <div className="space-y-2 text-sm">
            <p className="text-muted">
              {a.status === "PENDING"
                ? `Signzy is analysing the statements${a.reportStatusDescription ? ` — ${a.reportStatusDescription}` : ""}. This page refreshes itself.`
                : a.message || a.reportStatusDescription || "Signzy could not analyse these statements."}
            </p>
            <FieldGrid cols={3}>
              <Field label="Report">{a.reportId}</Field>
              <Field label="Run by">{a.runByName}</Field>
              <Field label="Started">{formatDateTime(a.createdAt)}</Field>
            </FieldGrid>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="cal-preset on">{a.bank}</span>
              {a.bankScore ? (
                <Badge variant="info" size="sm">{`Bank score ${a.bankScore}`}</Badge>
              ) : null}
              {a.accounts.map((ac) => (
                <span key={ac.maskedNumber} className="rounded-full bg-navy-tint px-2.5 py-0.5 text-xs font-semibold text-navy">
                  {`${ac.maskedNumber} · ${ac.type} · ${ac.txnCount} txns`}
                </span>
              ))}
            </div>
            {a.accounts.map((ac) => (
              <FieldGrid key={ac.maskedNumber} cols={3}>
                <Field label="Account" mono>{ac.maskedNumber}</Field>
                <Field label="Type">{ac.type}</Field>
                <Field label="Status">{ac.status}</Field>
                <Field label="IFSC" mono>{ac.ifsc}</Field>
                <Field label="MICR" mono>{ac.micr}</Field>
                <Field label="Facility">{ac.facility}</Field>
                <Field label="Branch">{ac.branch}</Field>
                <Field label="Period">
                  {ac.periodFrom && ac.periodTo ? `${formatDate(ac.periodFrom)} – ${formatDate(ac.periodTo)}` : null}
                </Field>
                <Field label="Transactions">{ac.txnCount}</Field>
                <Field
                  label="Document authenticity"
                  tone={ac.authenticity === "ORIGINAL" ? "success" : ac.authenticity ? "warning" : "ink"}
                >
                  {ac.authenticity}
                </Field>
                <Field label="Opening balance">
                  {ac.openingBalancePaise != null ? paiseToINR(ac.openingBalancePaise) : null}
                </Field>
                <Field label="Closing balance">
                  {ac.closingBalancePaise != null ? paiseToINR(ac.closingBalancePaise) : null}
                </Field>
              </FieldGrid>
            ))}
          </div>
        )}
      </Section>

      {/* Until the analyser API is live, the CRIF report is the third-party read on employment. */}
      <CrifAnalysisSection detail={detail} applicationId={applicationId} />

      <Section
        title="Salary identification"
        icon={IndianRupee}
        pill={a?.salary ? undefined : <Badge variant="neutral" size="sm">Declared only</Badge>}
      >
        {a?.salary ? (
          <div className="space-y-3">
            <div className={`flex items-center gap-2 border-l-4 p-3 text-sm ${VERDICT[a.salary.verdict].banner}`}>
              <Badge variant={VERDICT[a.salary.verdict].badge}>{a.salary.verdict}</Badge>
              <span>{a.salary.rule}</span>
            </div>
            <FieldGrid cols={4}>
              <Field label="Primary source">{a.salary.primarySource}</Field>
              <Field label="Consecutive months">{a.salary.consecutiveMonths}</Field>
              <Field label="Minimum salary">{paiseToINR(a.salary.minSalaryPaise)}</Field>
              <Field label="Unique salary credits">{a.salary.uniqueCredits}</Field>
              <Field label="Employer on statement">{a.salary.employerOnStatement}</Field>
              <Field label="Declared salary">{declared}</Field>
              <Field label="Declared salary day">{salaryDay}</Field>
              <Field label="Analysed at">{formatDateTime(a.salary.analysedAt)}</Field>
            </FieldGrid>
          </div>
        ) : (
          <FieldGrid cols={4}>
            <Field label="Declared salary">{declared}</Field>
            <Field label="Salary day">{salaryDay}</Field>
            <Field label="Last salary received">
              {p?.previousSalaryDate ? formatDate(p.previousSalaryDate) : null}
            </Field>
            <Field label="Salary slips">{docsOf("SALARY_SLIP").length}</Field>
          </FieldGrid>
        )}
      </Section>

      <Section title="Bank statements">{docGrid(docsOf("BANK_STATEMENT"), "No bank statements uploaded.")}</Section>
      <Section title="Cancelled cheque / passbook">
        {docGrid(docsOf("BANK_PROOF"), "No cancelled cheque or passbook uploaded.")}
      </Section>

      <Section title="Disbursal txn refs">
        {detail.loans.length === 0 ? (
          <EmptyState title="No loans." className="py-4" />
        ) : (
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead><tr><th>S.No.</th><th>Loan ID</th><th>Transaction reference</th></tr></thead>
              <tbody>
                {detail.loans.map((loan, i) => (
                  <tr key={loan.id}>
                    <td className="text-muted">{i + 1}</td>
                    <td>#{loan.id}</td>
                    <td className="font-mono">{loan.disbursalTxnRef ?? "—"}</td>
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
