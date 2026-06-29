"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Loader2, RefreshCw, User, Banknote, Receipt, Workflow, Pencil, Ban, XCircle, History } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage } from "@/components/staff/live-pipeline";
import { CreditBadge } from "@/components/staff/credit-badge";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import {
  customersApi,
  staffApi,
  adminApi,
  paiseToINR,
  rupeesToPaise,
  statusLabel,
  type CustomerDetail,
  type LoanView,
  type PaymentView,
  type ApplicationView,
  type ApplicationStatus,
  type BlocklistType,
} from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

/** Application statuses that can still be cancelled (pre-disbursement). */
const CANCELLABLE: Set<ApplicationStatus> = new Set([
  "DRAFT", "KYC_PENDING", "KYC_APPROVED", "PRE_APPROVED", "REVIEW_PENDING",
  "CREDIT_EXEC_PENDING", "CREDIT_EXEC_APPROVED", "CREDIT_HEAD_PENDING", "CREDIT_HEAD_APPROVED",
  "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING", "DISBURSEMENT_FAILED",
]);

/** Loan statuses that mean the loan is still live (vs. a past/closed loan). */
const OPEN_LOAN = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

export default function CustomerDetailPage() {
  const { applicantId } = useParams<{ applicantId: string }>();
  const id = Number(applicantId);
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["customer", id], queryFn: () => customersApi.get(id), enabled: Number.isFinite(id) });
  const invalidate = () => qc.invalidateQueries({ queryKey: ["customer", id] });

  const c = q.data;
  const currentLoan = c?.loans.find((l) => OPEN_LOAN.has(l.status)) ?? null;
  const pastLoans = c ? c.loans.filter((l) => l !== currentLoan) : [];

  return (
    <div>
      <Link href="/staff/customers" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> All customers
      </Link>
      <PageHeader
        title={c?.profile?.fullName ?? `Customer #${id}`}
        subtitle={`Applicant #${id} · borrower history`}
      >
        <button onClick={() => q.refetch()} className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink">
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        {q.isLoading ? (
          <div className="h-48 animate-pulse rounded border border-line bg-white" />
        ) : q.error || !c ? (
          <p className="text-sm text-error-700">{q.error ? errMessage(q.error) : "Customer not found."}</p>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,340px)]">
            <div className="space-y-6">
              {c.applications[0] && <CreditProfileCard applicationId={c.applications[0].id} />}
              <CurrentLoanCard loan={currentLoan} />
              <PastLoansCard loans={pastLoans} />
              <PaymentsCard payments={c.payments} />
              <ApplicationsCard applications={c.applications} onChanged={invalidate} />
              <ChangeHistoryCard applicantId={id} />
            </div>
            <div className="space-y-6">
              <ProfileCard detail={c} />
              <PermissionGate permission="customer:manage">
                <AdminEditCard detail={c} onSaved={invalidate} />
                <BlocklistCard applicantId={id} />
              </PermissionGate>
            </div>
          </div>
        )}
      </PermissionGate>
    </div>
  );
}

function Card({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">{icon} {title}</div>
      {children}
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 py-1.5 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className={mono ? "text-right font-mono text-ink" : "text-right text-ink"}>{value ?? "—"}</dd>
    </div>
  );
}

function ProfileCard({ detail }: { detail: CustomerDetail }) {
  const p = detail.profile;
  return (
    <Card title="Profile" icon={<User size={16} />}>
      {p && (p.starRating != null || p.creditScore != null) && (
        <div className="mb-3 flex items-center gap-2 border-b border-line pb-3">
          <span className="text-xs text-muted">#{detail.applicantId}</span>
          <CreditBadge starRating={p.starRating} creditScore={p.creditScore} recommendation={p.recommendation} />
        </div>
      )}
      {!p ? (
        <p className="text-sm text-muted">No KYC profile on file.</p>
      ) : (
        <dl className="divide-y divide-line">
          <Row label="Full name" value={p.fullName} />
          <Row label="PAN" value={p.pan} mono />
          <Row label="Aadhaar" value={p.aadhaar} mono />
          <Row label="Mobile" value={p.mobile} mono />
          <Row label="Email" value={p.email} />
          <Row label="Date of birth" value={p.dob} />
          <Row label="Address" value={p.address} />
          <Row label="Employer" value={p.employer} />
          <Row label="Employment" value={p.employmentStatus} />
          <Row label="Monthly salary" value={p.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
          <Row label="Annual salary" value={p.annualSalaryPaise != null ? paiseToINR(p.annualSalaryPaise) : null} />
          <Row label="Salary %" value={p.salaryPercentage != null ? `${p.salaryPercentage}%` : null} />
          <Row label="Increment %" value={p.incrementPercentage != null ? `${p.incrementPercentage}%` : null} />
          <Row label="Salary bank" value={p.salaryBank} />
          <Row label="CIBIL score" value={p.creditScore != null ? String(p.creditScore) : null} mono />
          <Row label="Risk category" value={p.riskCategory} />
          <Row label="Bureau" value={p.bureauSource} />
          <Row label="Identity match" value={p.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null} />
        </dl>
      )}
    </Card>
  );
}

function CurrentLoanCard({ loan }: { loan: LoanView | null }) {
  return (
    <Card title="Current loan" icon={<Banknote size={16} />}>
      {!loan ? (
        <p className="text-sm text-muted">No live loan.</p>
      ) : (
        <dl className="grid grid-cols-2 gap-x-6">
          <Row label="Loan" value={`#${loan.id}`} />
          <Row label="Status" value={<span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{loan.status}</span>} />
          <Row label="Principal" value={paiseToINR(loan.principalPaise)} />
          <Row label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
          <Row label="Total repayable" value={paiseToINR(loan.totalRepayablePaise)} />
          <Row label="Outstanding" value={<span className="font-semibold">{paiseToINR(loan.outstandingPaise)}</span>} />
          <Row label="Disbursed on" value={loan.disbursedOn ? formatDate(loan.disbursedOn) : null} />
          <Row label="Due date" value={loan.dueDate ? formatDate(loan.dueDate) : null} />
        </dl>
      )}
    </Card>
  );
}

function PastLoansCard({ loans }: { loans: LoanView[] }) {
  return (
    <Card title={`Past loans (${loans.length})`} icon={<Banknote size={16} />}>
      {loans.length === 0 ? (
        <p className="text-sm text-muted">No past loans.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {loans.map((l) => (
            <li key={l.id} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0">
                <span className="text-ink">Loan #{l.id} · {paiseToINR(l.principalPaise)}</span>
                <span className="block text-xs text-muted">net {paiseToINR(l.netDisbursedPaise)} · disbursed {l.disbursedOn ? formatDate(l.disbursedOn) : "—"} · due {l.dueDate ? formatDate(l.dueDate) : "—"} · outstanding {paiseToINR(l.outstandingPaise)}</span>
              </span>
              <span className="flex-shrink-0 rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-ink">{l.status}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function PaymentsCard({ payments }: { payments: PaymentView[] }) {
  return (
    <Card title={`Payments (${payments.length})`} icon={<Receipt size={16} />}>
      {payments.length === 0 ? (
        <p className="text-sm text-muted">No payments recorded.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {payments.map((p) => (
            <li key={p.id} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0">
                <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>
                <span className="block text-xs text-muted">
                  loan #{p.loanId} · {p.method === "UPI" ? "UPI" : "Bank"}{p.txnRef ? ` · ${p.txnRef}` : ""}{p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
                </span>
              </span>
              <span className={`flex-shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold ${p.status === "VERIFIED" ? "bg-success-50 text-success-700" : p.status === "REJECTED" ? "bg-error-50 text-error-700" : "bg-gold-50 text-gold-dark"}`}>
                {p.status === "PENDING_VERIFICATION" ? "Pending" : p.status === "VERIFIED" ? "Verified" : "Rejected"}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function ApplicationsCard({ applications, onChanged }: { applications: ApplicationView[]; onChanged: () => void }) {
  return (
    <Card title={`Applications (${applications.length})`} icon={<Workflow size={16} />}>
      {applications.length === 0 ? (
        <p className="text-sm text-muted">No applications.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {applications.map((a) => (
            <li key={a.id} className="flex items-center justify-between gap-3 py-2">
              <span className="min-w-0">
                <span className="text-ink">App #{a.id}</span>
                <span className="block text-xs text-muted">
                  {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "no amount"}{a.loanId != null ? ` · loan #${a.loanId}` : ""}
                </span>
              </span>
              <div className="flex flex-shrink-0 items-center gap-2">
                <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-ink">{statusLabel(a.status)}</span>
                {CANCELLABLE.has(a.status) && (
                  <PermissionGate permission="customer:manage">
                    <CancelButton appId={a.id} onDone={onChanged} />
                  </PermissionGate>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function CancelButton({ appId, onDone }: { appId: number; onDone: () => void }) {
  const m = useMutation({
    mutationFn: () => staffApi.cancel(appId, "Cancelled by admin from customer page"),
    onSuccess: onDone,
  });
  return (
    <button
      onClick={() => m.mutate()}
      disabled={m.isPending}
      className="flex items-center gap-1 rounded border border-error-100 px-2 py-1 text-xs font-semibold text-error-700 hover:bg-error-50 disabled:opacity-50"
      title="Cancel this application (admin)"
    >
      {m.isPending ? <Loader2 size={12} className="animate-spin" /> : <XCircle size={12} />} Cancel
    </button>
  );
}

/** Human label for an audited profile field (camelCase → words; salary/percentage friendly). */
function humanizeField(field: string): string {
  const map: Record<string, string> = {
    monthlySalaryPaise: "Monthly salary",
    annualSalaryPaise: "Annual salary",
    salaryPercentage: "Salary %",
    incrementPercentage: "Increment %",
    salaryBank: "Salary bank",
    fullName: "Full name",
    employmentStatus: "Employment status",
  };
  return map[field] ?? field.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());
}

/** Render an audited old/new value: paise → ₹, percentages → %, else as-is. */
function formatChangeValue(field: string, value: string | null): string {
  if (value == null || value === "") return "—";
  if (field.endsWith("Paise")) {
    const n = Number(value);
    return Number.isFinite(n) ? paiseToINR(n) : value;
  }
  if (field.endsWith("Percentage")) return `${value}%`;
  return value;
}

/** Audited profile/salary change history (Phase 2.1): previous→new, who, when. */
function ChangeHistoryCard({ applicantId }: { applicantId: number }) {
  const q = useQuery({
    queryKey: ["customer-changes", applicantId],
    queryFn: () => customersApi.changes(applicantId),
    enabled: Number.isFinite(applicantId),
  });
  const rows = q.data ?? [];
  return (
    <Card title={`Change history (${rows.length})`} icon={<History size={16} />}>
      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted">No profile edits recorded.</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {rows.map((c) => (
            <li key={c.id} className="py-2">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium text-ink">{humanizeField(c.field)}</span>
                <span className="text-xs text-muted">{c.modifiedAt ? formatDateTime(c.modifiedAt) : ""}</span>
              </div>
              <div className="mt-0.5 text-xs text-muted">
                <span className="line-through">{formatChangeValue(c.field, c.oldValue)}</span>
                {" → "}
                <span className="font-medium text-ink">{formatChangeValue(c.field, c.newValue)}</span>
                {c.modifiedBy ? ` · by ${c.modifiedBy}` : ""}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function AdminEditCard({ detail, onSaved }: { detail: CustomerDetail; onSaved: () => void }) {
  const p = detail.profile;
  const [fullName, setFullName] = React.useState(p?.fullName ?? "");
  const [employer, setEmployer] = React.useState(p?.employer ?? "");
  const [employmentStatus, setEmploymentStatus] = React.useState(p?.employmentStatus ?? "");
  const [salary, setSalary] = React.useState(p?.monthlySalaryPaise != null ? String(Math.round(p.monthlySalaryPaise / 100)) : "");
  const [annualSalary, setAnnualSalary] = React.useState(p?.annualSalaryPaise != null ? String(Math.round(p.annualSalaryPaise / 100)) : "");
  const [salaryPct, setSalaryPct] = React.useState(p?.salaryPercentage != null ? String(p.salaryPercentage) : "");
  const [incrementPct, setIncrementPct] = React.useState(p?.incrementPercentage != null ? String(p.incrementPercentage) : "");
  const [salaryBank, setSalaryBank] = React.useState(p?.salaryBank ?? "");
  const [address, setAddress] = React.useState(p?.address ?? "");

  const m = useMutation({
    mutationFn: () =>
      customersApi.updateProfile(detail.applicantId, {
        fullName: fullName.trim() || null,
        employer: employer.trim() || null,
        employmentStatus: employmentStatus.trim() || null,
        monthlySalaryPaise: salary ? rupeesToPaise(Number(salary.replace(/[^\d.]/g, ""))) : null,
        annualSalaryPaise: annualSalary ? rupeesToPaise(Number(annualSalary.replace(/[^\d.]/g, ""))) : null,
        salaryPercentage: salaryPct ? Number(salaryPct) : null,
        incrementPercentage: incrementPct ? Number(incrementPct) : null,
        salaryBank: salaryBank.trim() || null,
        address: address.trim() || null,
      }),
    onSuccess: onSaved,
  });

  return (
    <Card title="Edit KYC / salary (admin)" icon={<Pencil size={16} />}>
      <p className="mb-3 text-xs text-muted">Identity (PAN/Aadhaar/mobile) is locked. Salary edits are audited and recompute the eligible limit.</p>
      <Input label="Full name" value={fullName} onChange={(e) => setFullName(e.target.value)} className="!mb-2" />
      <Input label="Employer" value={employer} onChange={(e) => setEmployer(e.target.value)} className="!mb-2" />
      <Input label="Employment status" value={employmentStatus} onChange={(e) => setEmploymentStatus(e.target.value)} className="!mb-2" />
      <Input label="Monthly salary (₹)" inputMode="numeric" value={salary} onChange={(e) => setSalary(e.target.value.replace(/[^\d]/g, ""))} className="!mb-2" />
      <Input label="Annual salary (₹)" inputMode="numeric" value={annualSalary} onChange={(e) => setAnnualSalary(e.target.value.replace(/[^\d]/g, ""))} className="!mb-2" />
      <Input label="Salary percentage (%)" inputMode="decimal" value={salaryPct} onChange={(e) => setSalaryPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Increment percentage (%)" inputMode="decimal" value={incrementPct} onChange={(e) => setIncrementPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Salary bank" value={salaryBank} onChange={(e) => setSalaryBank(e.target.value)} className="!mb-2" />
      <Input label="Address" value={address} onChange={(e) => setAddress(e.target.value)} className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      {m.isSuccess && <p className="mb-2 text-sm text-success-700">Saved.</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save changes
      </button>
    </Card>
  );
}

const BLOCKLIST_TYPES: BlocklistType[] = ["PAN", "AADHAAR_REF", "PHONE", "DEVICE", "BANK_ACCOUNT"];

function BlocklistCard({ applicantId }: { applicantId: number }) {
  const [type, setType] = React.useState<BlocklistType>("PAN");
  const [value, setValue] = React.useState("");
  const [reason, setReason] = React.useState("");
  const m = useMutation({
    mutationFn: () => adminApi.addBlocklist({ type, value: value.trim(), reason: reason.trim() || `Flagged from customer #${applicantId}` }),
    onSuccess: () => { setValue(""); setReason(""); },
  });
  return (
    <Card title="Add to blocklist (admin)" icon={<Ban size={16} />}>
      <p className="mb-3 text-xs text-muted">Flag a PAN / phone / device against fraud. Enter the full value to block.</p>
      <Select label="Type" value={type} onChange={(e) => setType(e.target.value as BlocklistType)} options={BLOCKLIST_TYPES.map((t) => ({ value: t, label: t }))} className="!mb-2" />
      <Input label="Value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. ABCDE1234F" className="!mb-2" />
      <Input label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="optional" className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      {m.isSuccess && <p className="mb-2 text-sm text-success-700">Added to blocklist.</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending || !value.trim()} className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Ban size={13} />} Add to blocklist
      </button>
    </Card>
  );
}
