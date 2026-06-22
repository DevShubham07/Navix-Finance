"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft, Building2, ShieldCheck, AlertTriangle, CheckCircle2 } from "lucide-react";
import { Button, Input, Select, Badge } from "@/components/ui";
import { InfoRow } from "@/components/borrower/summary";
import { MakerCheckerBar } from "@/components/staff/maker-checker-bar";
import { ApprovalTrail } from "@/components/staff/approval-trail";
import { PageHeader, StageBadge, KycStatusBadge, StatCard } from "@/components/staff/staff-ui";
import { useMockDb } from "@/lib/mock/store";
import { useStaffSession } from "@/lib/mock/session";
import { evaluateSoD } from "@/lib/auth/rbac";
import { RISK_PROFILE } from "@/lib/mock/types";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

const KYC_ROWS: Array<{ key: "pan" | "aadhaar" | "selfie" | "address" | "bank"; label: string }> = [
  { key: "pan", label: "PAN" },
  { key: "aadhaar", label: "Aadhaar" },
  { key: "selfie", label: "Selfie" },
  { key: "address", label: "Address" },
  { key: "bank", label: "Bank" },
];

export default function CreditReviewPage() {
  const mounted = useMounted();
  const { applicationId } = useParams<{ applicationId: string }>();
  const { session } = useStaffSession();
  const app = useMockDb((s) => s.applications.find((a) => a.id === applicationId));
  const staff = useMockDb((s) => s.staff);
  const executives = React.useMemo(() => staff.filter((m) => m.role === "CREDIT_EXECUTIVE" && m.active), [staff]);
  const { recommend, decideCredit, assignExecutive } = useMockDb();

  const [assignTo, setAssignTo] = React.useState("");
  const [approvedAmount, setApprovedAmount] = React.useState(0);

  React.useEffect(() => {
    if (app) setApprovedAmount(app.requestedAmount);
    if (executives[0]) setAssignTo(executives[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [app?.id, executives.length]);

  if (!mounted || !session) return <div className="h-64 rounded border border-line bg-white" />;

  if (!app) {
    return (
      <div className="rounded border border-line bg-white p-8 text-center">
        <h1 className="text-2xl">Application not found</h1>
        <p className="mb-4 text-muted">{applicationId} doesn&apos;t exist or has been cleared.</p>
        <Link href="/staff/credit/queue" className="btn btn-navy">Back to queue</Link>
      </div>
    );
  }

  const actor = { id: session.userId, name: session.name, role: session.role };
  const risk = RISK_PROFILE[app.riskCategory];

  return (
    <div>
      <Link href="/staff/credit/queue" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> Credit queue
      </Link>
      <PageHeader title={app.applicantName} subtitle={`${app.id} · ${app.employer}`}>
        <StageBadge stage={app.stage} />
      </PageHeader>

      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,380px)]">
        {/* Left: applicant + risk + KYC */}
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <StatCard label="Requested" value={formatINR0(app.requestedAmount)} />
            <StatCard label="Eligible (25%)" value={formatINR0(app.eligibleLimit)} />
            <StatCard label={`Risk ${app.riskCategory}`} value={risk.label} accent={app.riskCategory === "A" || app.riskCategory === "B" ? "success" : "error"} />
            <StatCard label="Credit score" value={app.creditScore} />
          </div>

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <h3 className="mb-1 font-serif text-base text-navy">Applicant</h3>
            <InfoRow label="Mobile" value={app.mobile} />
            <InfoRow label="Email" value={app.email} />
            <InfoRow label="PAN" value={app.panMasked} />
            <InfoRow label="Employer" value={`${app.employer} · ${app.designation}`} />
            <InfoRow label="Monthly salary" value={formatINR0(app.monthlySalary)} />
            <InfoRow label="Salary day" value={`${app.salaryDay} of each month`} />
            <InfoRow label="Co-applicant required" value={app.coApplicantRequired ? "Yes" : "No"} />
          </div>

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-3 flex items-center gap-2"><ShieldCheck size={16} className="text-navy" /><h3 className="mb-0 font-serif text-base text-navy">KYC checks</h3></div>
            <div className="flex flex-wrap gap-2">
              {KYC_ROWS.map(({ key, label }) => (
                <span key={key} className="inline-flex items-center gap-2 rounded border border-line px-3 py-1.5">
                  <span className="text-sm text-ink">{label}</span>
                  <KycStatusBadge status={app.kyc[key]} />
                </span>
              ))}
            </div>
          </div>

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-3 flex items-center gap-2"><Building2 size={16} className="text-navy" /><h3 className="mb-0 font-serif text-base text-navy">Disbursement bank</h3></div>
            <InfoRow label="Bank" value={app.bank.bankName} />
            <InfoRow label="Account" value={app.bank.accountMasked} />
            <InfoRow label="IFSC" value={app.bank.ifsc} />
            <InfoRow label="Penny-drop" value={app.bank.pennyDropVerified ? "Verified" : "Pending"} />
          </div>
        </div>

        {/* Right: recommendation + action + trail */}
        <div className="space-y-6">
          {app.recommendation && (
            <div className="rounded border border-info-100 bg-info-50/50 p-5">
              <div className="mb-1 text-xs font-bold uppercase tracking-wider text-info-700">Executive recommendation</div>
              <div className="mb-1 flex items-center gap-2">
                <Badge variant={app.recommendation.decision === "APPROVE" ? "success" : "error"}>{app.recommendation.decision}</Badge>
                <span className="text-sm text-muted">by {app.recommendation.by}</span>
              </div>
              <p className="m-0 text-sm text-ink/90">{app.recommendation.notes}</p>
            </div>
          )}

          {/* Stage-specific maker-checker action */}
          {app.stage === "CREDIT_QUEUE" && (
            <div className="rounded border border-line bg-white p-5 shadow-sm">
              <h4 className="mb-3 font-serif text-base font-semibold text-navy">Assign for review</h4>
              <Select label="Credit executive" value={assignTo} onChange={(e) => setAssignTo(e.target.value)}
                options={executives.map((e) => ({ value: e.id, label: e.name }))} />
              <Button variant="primary" block onClick={() => { const ex = executives.find((e) => e.id === assignTo); if (ex) assignExecutive(app.id, ex, actor); }}>
                Assign to executive
              </Button>
            </div>
          )}

          {app.stage === "CREDIT_REVIEW" && (() => {
            const sod = evaluateSoD({ step: "review", role: session.role, actorId: session.userId, trail: app.approvalTrail });
            return (
              <MakerCheckerBar
                title="Credit review — recommend"
                approveLabel="Recommend approval"
                rejectLabel="Recommend rejection"
                requireNotes
                disabled={!sod.allowed}
                disabledReason={sod.reason}
                onApprove={(notes) => recommend(app.id, "APPROVE", notes, actor)}
                onReject={(notes) => recommend(app.id, "REJECT", notes, actor)}
              />
            );
          })()}

          {app.stage === "CREDIT_DECISION" && (() => {
            const sod = evaluateSoD({ step: "approve", role: session.role, actorId: session.userId, trail: app.approvalTrail });
            return (
              <div className="space-y-3">
                {sod.allowed && (
                  <div className="rounded border border-line bg-white p-5 shadow-sm">
                    <Input label="Approved amount" inputMode="numeric" value={String(approvedAmount)}
                      onChange={(e) => setApprovedAmount(Number(e.target.value.replace(/\D/g, "")) || 0)}
                      helperText={`Up to the eligible limit ${formatINR0(app.eligibleLimit)}`} />
                  </div>
                )}
                <MakerCheckerBar
                  title="Final credit decision"
                  approveLabel="Approve loan"
                  rejectLabel="Reject"
                  requireNotes
                  disabled={!sod.allowed}
                  disabledReason={sod.reason}
                  onApprove={(notes) => decideCredit(app.id, true, notes, actor, Math.min(approvedAmount, app.eligibleLimit))}
                  onReject={(notes) => decideCredit(app.id, false, notes, actor)}
                />
              </div>
            );
          })()}

          {(app.stage === "DISBURSEMENT" || app.stage === "ACCOUNTING") && (
            <div className="flex items-start gap-3 rounded border border-info-100 bg-info-50/50 p-4 text-sm text-info-700">
              <CheckCircle2 size={18} className="mt-0.5 flex-shrink-0" />
              <span>Credit-approved. Now with {app.stage === "DISBURSEMENT" ? "Disbursement" : "Accounting"} — track it on the {app.stage === "DISBURSEMENT" ? <Link href="/staff/disbursement" className="font-semibold underline">disbursement</Link> : <Link href="/staff/accounting" className="font-semibold underline">accounting</Link>} queue.</span>
            </div>
          )}

          {app.stage === "ACTIVE" && (
            <div className="flex items-center gap-3 rounded border border-success-100 bg-success-50/50 p-4 text-sm text-success-700">
              <CheckCircle2 size={18} /> Loan is live and being serviced.
            </div>
          )}

          {app.stage === "REJECTED" && (
            <div className="flex items-center gap-3 rounded border border-error-100 bg-error-50/50 p-4 text-sm text-error-700">
              <AlertTriangle size={18} /> Application rejected.
            </div>
          )}

          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <h3 className="mb-4 font-serif text-base text-navy">Maker-checker trail</h3>
            <ApprovalTrail entries={app.approvalTrail} />
          </div>
        </div>
      </div>
    </div>
  );
}
