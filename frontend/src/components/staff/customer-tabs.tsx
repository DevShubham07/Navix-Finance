"use client";

/**
 * Shared customer CRM tabs — used by both the list dialog and the full detail page so they
 * cannot drift apart (same precedent as detail-parts.tsx).
 */

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Check, XCircle } from "lucide-react";
import { Select } from "@/components/ui";
import { type TabDef } from "@/components/ui/tabs";
import { formatDate, formatDateTime } from "@/lib/utils";
import { LoanBreakdown, ProjectedCostBreakdown } from "@/components/staff/loan-breakdown";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import { Section, KV, Bool, DocumentsTab, RemarksTab } from "@/components/staff/detail-parts";
import {
  customersApi,
  staffApi,
  paiseToINR,
  statusLabel,
  type CustomerDetail,
  type ApplicationView,
  type ActivityEntry,
  type LoanView,
  type ApplicationStatus,
  type StaffSummary,
} from "@/lib/api/applications";

export const CUSTOMER_TABS: TabDef[] = [
  { key: "personal", label: "Personal Details" },
  { key: "employment", label: "Employment" },
  { key: "bank", label: "Bank Accounts" },
  { key: "credit", label: "Credit Report" },
  { key: "documents", label: "Documents" },
  { key: "loans", label: "Loan Applications" },
  { key: "calls", label: "Customer Call Logs" },
  { key: "audit", label: "Audit Logs" },
];

const CANCELLABLE: Set<ApplicationStatus> = new Set([
  "DRAFT", "KYC_PENDING", "KYC_APPROVED", "PRE_APPROVED", "REVIEW_PENDING",
  "CREDIT_EXEC_PENDING", "CREDIT_EXEC_APPROVED", "CREDIT_HEAD_PENDING", "CREDIT_HEAD_APPROVED",
  "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING", "DISBURSEMENT_FAILED",
]);

const CALL_TYPES = [
  { value: "OUTBOUND", label: "Outbound" },
  { value: "INBOUND", label: "Inbound" },
  { value: "MISSED", label: "Missed" },
];

const CALL_OUTCOMES = [
  { value: "CONNECTED", label: "Connected" },
  { value: "NO_ANSWER", label: "No answer" },
  { value: "CALLBACK", label: "Callback" },
  { value: "REFUSED", label: "Refused" },
  { value: "WRONG_NUMBER", label: "Wrong number" },
];

const TYPE_STYLE: Record<string, string> = {
  LIFECYCLE: "bg-navy-tint text-navy",
  PROFILE: "bg-gold-50 text-gold-dark",
  REVERIFY: "bg-warning-100 text-warning-800",
  REMARK: "bg-grey-100 text-muted",
  CALL: "bg-success-50 text-success-700",
};

export function CustomerTabBody({
  tab,
  detail,
  customerId,
  onChanged,
}: {
  tab: string;
  detail: CustomerDetail;
  customerId: number;
  /** Fired after cancel / owner assign so parents can refetch. */
  onChanged?: () => void;
}) {
  const latestAppId = detail.applications[0]?.id ?? null;

  switch (tab) {
    case "personal":
      return <PersonalTab c={detail} onChanged={onChanged} />;
    case "employment":
      return <EmploymentTab c={detail} />;
    case "bank":
      return <BankTab c={detail} latestAppId={latestAppId} />;
    case "credit":
      return <CreditTab c={detail} latestAppId={latestAppId} />;
    case "documents":
      return latestAppId != null ? (
        <DocumentsTab applicationId={latestAppId} />
      ) : (
        <p className="py-6 text-sm text-muted">No application to attach documents to.</p>
      );
    case "loans":
      return <LoansTab c={detail} onChanged={onChanged} />;
    case "calls":
      return (
        <div className="space-y-6">
          <CallLogsTab customerId={customerId} />
          <Section title="Remarks">
            <RemarksTab customerId={customerId} />
          </Section>
        </div>
      );
    case "audit":
      return <AuditLogsTab customerId={customerId} />;
    default:
      return null;
  }
}

// ---------------------------------------------------------------------------
// Personal + Owner
// ---------------------------------------------------------------------------

function PersonalTab({ c, onChanged }: { c: CustomerDetail; onChanged?: () => void }) {
  const p = c.profile;
  const currentLoan =
    c.loans.find((l) =>
      ["ACTIVE", "OVERDUE", "DISBURSED", "IN_COLLECTIONS", "DEFAULTED"].includes(l.status),
    ) ??
    c.loans[0] ??
    null;
  const latestApp = c.applications[0] ?? null;

  const verQ = useQuery({
    queryKey: ["customer-verifications-personal", latestApp?.id],
    queryFn: () => staffApi.verifications(latestApp!.id),
    enabled: latestApp != null,
  });
  const panDerived = ((verQ.data ?? []).find((s) => s.checkType === "PAN")?.derived ?? {}) as Record<
    string,
    unknown
  >;
  const emailDerived = ((verQ.data ?? []).find((s) => s.checkType === "EMAIL")?.derived ?? {}) as Record<
    string,
    unknown
  >;

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title="Identity & profile">
        <KV k="Full name" v={p?.fullName} />
        <KV k="PAN" v={p?.pan} mono />
        <KV k="Mobile" v={p?.mobile} mono />
        <KV k="Email" v={p?.email} />
        <KV k="Date of birth" v={p?.dob} />
        <KV k="Address" v={p?.address} />
      </Section>

      <Section title="Verification">
        <KV k="PAN verified" v={<Bool on={p?.panVerified} />} />
        <KV k="Aadhaar (DigiLocker)" v={<Bool on={p?.aadhaarVerified} />} />
        <KV k="Aadhaar linked" v={<Bool on={p?.aadhaarLinked} />} />
        <KV k="Email verified" v={<Bool on={p?.emailVerified} />} />
        <KV k="Address verified" v={<Bool on={p?.addressVerified} />} />
        <KV k="Penny drop" v={<Bool on={p?.pennyDropVerified} />} />
        <KV
          k="Identity match"
          v={p?.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null}
        />
      </Section>

      {latestApp != null && (
        <Section title="PAN (provider)">
          <KV k="Name on PAN" v={str(panDerived.fullName)} />
          <KV k="Gender" v={str(panDerived.gender)} />
          <KV k="DOB on PAN" v={str(panDerived.dob)} />
          <KV k="PAN status" v={str(panDerived.panStatus)} />
          <KV k="Allotment date" v={str(panDerived.panAllotmentDate)} />
          <KV k="Compliant" v={str(panDerived.compliant)} />
          <KV k="State" v={str(panDerived.addressState)} />
          <KV k="PIN" v={str(panDerived.addressZip)} mono />
        </Section>
      )}

      {latestApp != null && (
        <Section title="Email (provider)">
          <KV k="Status" v={str(emailDerived.status)} />
          <KV k="Domain" v={str(emailDerived.domain)} />
          <KV k="MX" v={str(emailDerived.mxRecord)} mono />
          <KV k="SMTP" v={str(emailDerived.smtpProvider)} />
          <KV k="Person name" v={str(emailDerived.personName)} />
          <KV k="Company" v={str(emailDerived.companyName ?? emailDerived.matchedEstablishment)} />
          <KV k="Did you mean" v={str(emailDerived.didYouMean)} />
          <KV
            k="Individual score"
            v={emailDerived.individualScore != null ? String(emailDerived.individualScore) : null}
          />
        </Section>
      )}

      {latestApp != null && <AadhaarCard applicationId={latestApp.id} />}

      <Section title="Emergency contact">
        <KV k="Name" v={p?.emergencyContactName} />
        <KV k="Phone" v={p?.emergencyContactPhone} mono />
        <KV k="Relation" v={p?.emergencyContactRelation} />
      </Section>

      <OwnerCard c={c} onChanged={onChanged} />

      <div className="md:col-span-2">
        <Section title="Loan cost calculation">
          {currentLoan ? (
            <LoanBreakdown loan={currentLoan} />
          ) : latestApp?.amountRequestedPaise != null ? (
            <ProjectedCostBreakdown app={latestApp} />
          ) : (
            <p className="text-sm text-muted">No loan or requested amount yet.</p>
          )}
        </Section>
      </div>
    </div>
  );
}

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function OwnerCard({ c, onChanged }: { c: CustomerDetail; onChanged?: () => void }) {
  const qc = useQueryClient();
  const [staffId, setStaffId] = React.useState<string>(
    c.ownerStaffId != null ? String(c.ownerStaffId) : "",
  );

  React.useEffect(() => {
    setStaffId(c.ownerStaffId != null ? String(c.ownerStaffId) : "");
  }, [c.ownerStaffId, c.customerId]);

  const execs = useQuery({
    queryKey: ["staff-picker", "CREDIT_EXECUTIVE"],
    queryFn: () => staffApi.creditExecutives("CREDIT_EXECUTIVE"),
  });
  const collectors = useQuery({
    queryKey: ["staff-picker", "COLLECTION_EXECUTIVE"],
    queryFn: () => staffApi.creditExecutives("COLLECTION_EXECUTIVE"),
  });
  const telecallers = useQuery({
    queryKey: ["staff-picker", "TELECALLER"],
    queryFn: () => staffApi.creditExecutives("TELECALLER"),
  });

  const options = React.useMemo(() => {
    const map = new Map<number, StaffSummary>();
    for (const s of [...(execs.data ?? []), ...(collectors.data ?? []), ...(telecallers.data ?? [])]) {
      map.set(s.id, s);
    }
    return Array.from(map.values()).sort((a, b) => a.name.localeCompare(b.name));
  }, [execs.data, collectors.data, telecallers.data]);

  const assign = useMutation({
    mutationFn: () =>
      customersApi.assignOwner(c.customerId, staffId ? Number(staffId) : null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["customer", c.customerId] });
      qc.invalidateQueries({ queryKey: ["customer-detail", c.customerId] });
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customer-activity", c.customerId] });
      onChanged?.();
    },
  });

  return (
    <Section title="Owner">
      <KV k="Current owner" v={c.ownerName ?? "Unallocated"} />
      <PermissionGate permission="customer:assign">
        <div className="mt-2 space-y-2">
          <Select
            label="Assign to"
            value={staffId}
            onChange={(e) => setStaffId(e.target.value)}
            options={[
              { value: "", label: "Unallocated" },
              ...options.map((s) => ({
                value: String(s.id),
                label: `${s.name} (${s.role})`,
              })),
            ]}
            className="!mb-0"
          />
          <button
            type="button"
            onClick={() => assign.mutate()}
            disabled={assign.isPending}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            {assign.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
            Save owner
          </button>
          {assign.error && (
            <p className="text-xs text-error-700">{errMessage(assign.error)}</p>
          )}
        </div>
      </PermissionGate>
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Employment
// ---------------------------------------------------------------------------

function EmploymentTab({ c }: { c: CustomerDetail }) {
  const p = c.profile;
  const latestApp = c.applications[0] ?? null;
  return (
    <Section title="Employment & salary">
      <KV k="Employer" v={p?.employer} />
      <KV k="Employment status" v={p?.employmentStatus} />
      <KV k="Salary bank" v={p?.salaryBank} />
      <KV
        k="Monthly salary"
        v={p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null}
      />
      <KV
        k="Annual salary"
        v={p?.annualSalaryPaise != null ? paiseToINR(p.annualSalaryPaise) : null}
      />
      <KV
        k="Salary %"
        v={p?.salaryPercentage != null ? `${p.salaryPercentage}%` : null}
      />
      <KV
        k="Increment %"
        v={p?.incrementPercentage != null ? `${p.incrementPercentage}%` : null}
      />
      <KV
        k="Eligible limit"
        v={
          latestApp?.eligibleLimitPaise != null
            ? paiseToINR(latestApp.eligibleLimitPaise)
            : null
        }
      />
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Bank (synthesized — no bank-account entity)
// ---------------------------------------------------------------------------

function BankTab({ c, latestAppId }: { c: CustomerDetail; latestAppId: number | null }) {
  const p = c.profile;
  const pennyQ = useQuery({
    queryKey: ["customer-verifications", latestAppId],
    queryFn: () => staffApi.verifications(latestAppId as number),
    enabled: latestAppId != null,
  });
  const penny = (pennyQ.data ?? []).find((s) => s.checkType === "PENNY_DROP");
  const derived = (penny?.derived ?? {}) as Record<string, unknown>;

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted">
        No dedicated bank-account entity — synthesized from the salary bank on the KYC profile,
        the latest penny-drop verification, and disbursal transaction refs on loans.
      </p>
      <Section title="Salary bank">
        <KV k="Bank" v={p?.salaryBank} />
        <KV k="Penny drop verified" v={<Bool on={p?.pennyDropVerified} />} />
      </Section>
      <Section title="Penny-drop derived">
        {pennyQ.isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (
          <>
            <KV k="Account" v={derived.accountNumber != null ? String(derived.accountNumber) : (derived.account != null ? String(derived.account) : null)} mono />
            <KV k="IFSC" v={derived.ifsc != null ? String(derived.ifsc) : null} mono />
            <KV k="Bank" v={derived.bank != null ? String(derived.bank) : null} />
            <KV k="Name match" v={derived.nameMatch != null ? String(derived.nameMatch) : (derived.nameMatched != null ? String(derived.nameMatched) : null)} />
            <KV k="Provider name match" v={derived.providerNameMatch != null ? String(derived.providerNameMatch) : null} />
            <KV k="Beneficiary name" v={derived.beneficiaryName != null ? String(derived.beneficiaryName) : (derived.name != null ? String(derived.name) : null)} />
            <KV k="Bank RRN" v={derived.bankRrn != null ? String(derived.bankRrn) : null} mono />
            <KV k="Reason" v={derived.reason != null ? String(derived.reason) : null} />
            <KV k="Account exists" v={derived.accountExists != null ? String(derived.accountExists) : null} />
          </>
        )}
      </Section>
      <Section title="Disbursal txn refs">
        {c.loans.length === 0 ? (
          <p className="text-sm text-muted">No loans.</p>
        ) : (
          <ul className="divide-y divide-line text-sm">
            {c.loans.map((l) => (
              <li key={l.id} className="flex justify-between gap-2 py-1.5">
                <span>Loan #{l.id}</span>
                <span className="font-mono text-muted">{l.disbursalTxnRef ?? "—"}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Credit
// ---------------------------------------------------------------------------

function CreditTab({ c, latestAppId }: { c: CustomerDetail; latestAppId: number | null }) {
  const p = c.profile;
  const bureauQ = useQuery({
    queryKey: ["customer-verifications-bureau", latestAppId],
    queryFn: () => staffApi.verifications(latestAppId as number),
    enabled: latestAppId != null,
  });
  const bureau = (bureauQ.data ?? []).find((s) => s.checkType === "BUREAU");
  const bd = (bureau?.derived ?? {}) as Record<string, unknown>;

  return (
    <div className="space-y-4">
      {latestAppId != null && <CreditProfileCard applicationId={latestAppId} />}
      <Section title="Credit headline">
        <KV k="CIBIL score" v={p?.creditScore != null ? String(p.creditScore) : null} mono />
        <KV k="Star rating" v={p?.starRating != null ? `${p.starRating.toFixed(1)}★` : null} />
        <KV k="Recommendation" v={p?.recommendation} />
        <KV k="Risk category" v={p?.riskCategory} />
        <KV k="Bureau" v={p?.bureauSource} />
        <KV k="Credit brief summary" v={p?.creditBriefSummary} />
        <KV
          k="Credit brief generated"
          v={p?.creditBriefGeneratedAt ? formatDateTime(p.creditBriefGeneratedAt) : null}
        />
      </Section>
      {latestAppId != null && (
        <Section title="Bureau pull (derived)">
          <KV k="Source" v={bd.source != null ? String(bd.source) : null} />
          <KV k="No record" v={bd.noRecord != null ? String(bd.noRecord) : null} />
          <KV k="Active accounts" v={bd.activeAccounts != null ? String(bd.activeAccounts) : null} />
          <KV k="Overdue / defaults" v={bd.overdueAccounts != null ? String(bd.overdueAccounts) : null} />
          <KV k="Total balance" v={bd.totalBalance != null ? String(bd.totalBalance) : null} />
        </Section>
      )}
      {c.creditBrief?.facts && (
        <Section title="Credit facts (customer record)">
          <KV k="Total accounts" v={String(c.creditBrief.facts.totalAccounts ?? "—")} />
          <KV k="Active accounts" v={String(c.creditBrief.facts.activeAccounts ?? "—")} />
          <KV k="Closed accounts" v={String(c.creditBrief.facts.closedAccounts ?? "—")} />
          <KV k="Defaults" v={String(c.creditBrief.facts.defaults ?? "—")} />
          <KV
            k="Total balance"
            v={c.creditBrief.facts.totalBalance != null ? `₹${c.creditBrief.facts.totalBalance.toLocaleString("en-IN")}` : "—"}
          />
          <KV k="Inquiries (30d)" v={String(c.creditBrief.facts.recentInquiries30d ?? "—")} />
        </Section>
      )}
    </div>
  );
}

/**
 * The e-Aadhaar card as DigiLocker returned it, off the AADHAAR verification row's `derived`.
 * The number is masked (last 4) — the raw UID is never stored. Rows completed before the
 * backend started recording gender/address show "—" for those two.
 */
function AadhaarCard({ applicationId }: { applicationId: number }) {
  const { data, isLoading } = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
  });
  const row = data?.find((s) => s.checkType === "AADHAAR");
  if (isLoading || !row) return null;
  const d = row.derived as Record<string, string | null | undefined>;

  return (
    <Section title="Aadhaar (DigiLocker)">
      <KV k="Name on Aadhaar" v={d.fullName} />
      <KV k="Aadhaar number" v={d.maskedAadhaar} mono />
      <KV k="Date of birth" v={d.dob} />
      <KV k="Gender" v={d.gender} />
      <KV k="Address" v={d.address} />
      <KV k="State" v={d.state} />
      <KV k="PIN code" v={d.pincode} mono />
      <KV k="Status" v={row.status === "PASS" ? <Bool on /> : row.message || row.status} />
    </Section>
  );
}

// ---------------------------------------------------------------------------
// Loan applications
// ---------------------------------------------------------------------------

function LoansTab({ c, onChanged }: { c: CustomerDetail; onChanged?: () => void }) {
  const [selectedLoan, setSelectedLoan] = React.useState<LoanView | null>(null);
  const appIdFor = (loanId: number) =>
    c.applications.find((a) => a.loanId === loanId)?.id ?? null;

  return (
    <div className="space-y-4">
      <Section title={`Applications (${c.applications.length})`}>
        {c.applications.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="divide-y divide-line">
            {c.applications.map((a: ApplicationView) => (
              <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  #{a.id} · {statusLabel(a.status)}
                  {a.purpose ? <span className="text-muted"> · {a.purpose}</span> : null}
                </span>
                <div className="flex items-center gap-2">
                  <span className="font-mono text-muted">
                    {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"}
                  </span>
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
      </Section>

      <Section title={`Loans (${c.loans.length})`}>
        {c.loans.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <div className="space-y-3">
            {c.loans.map((l) => (
              <div key={l.id} className="rounded border border-line p-3">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => setSelectedLoan(l)}
                    className="font-semibold text-navy hover:underline"
                  >
                    Loan #{l.id} · {paiseToINR(l.principalPaise)}
                  </button>
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
                    {l.status}
                  </span>
                </div>
                <LoanBreakdown loan={l} />
              </div>
            ))}
          </div>
        )}
      </Section>

      <Section title={`Payments (${c.payments.length})`}>
        {c.payments.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="divide-y divide-line">
            {c.payments.map((pm) => (
              <li key={pm.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  {paiseToINR(pm.amountPaise)} · {pm.method}
                  {pm.paidOn ? <span className="text-muted"> · {formatDate(pm.paidOn)}</span> : null}
                </span>
                <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">
                  {pm.status}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {selectedLoan && (
        <LoanDetailDialog
          loan={selectedLoan}
          applicationId={appIdFor(selectedLoan.id)}
          onClose={() => setSelectedLoan(null)}
        />
      )}
    </div>
  );
}

function CancelButton({ appId, onDone }: { appId: number; onDone?: () => void }) {
  const m = useMutation({
    mutationFn: () => staffApi.cancel(appId, "Cancelled by admin from customer page"),
    onSuccess: () => onDone?.(),
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

// ---------------------------------------------------------------------------
// Call logs
// ---------------------------------------------------------------------------

function CallLogsTab({ customerId }: { customerId: number }) {
  const qc = useQueryClient();
  const [callType, setCallType] = React.useState("OUTBOUND");
  const [outcome, setOutcome] = React.useState("CONNECTED");
  const [callbackOn, setCallbackOn] = React.useState("");
  const [notes, setNotes] = React.useState("");

  const q = useQuery({
    queryKey: ["customer-call-logs", customerId],
    queryFn: () => customersApi.callLogs(customerId),
  });
  const add = useMutation({
    mutationFn: () =>
      customersApi.addCallLog(customerId, {
        callType,
        outcome,
        callbackOn: outcome === "CALLBACK" && callbackOn ? callbackOn : null,
        notes: notes.trim() || null,
      }),
    onSuccess: () => {
      setNotes("");
      setCallbackOn("");
      qc.invalidateQueries({ queryKey: ["customer-call-logs", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-activity", customerId] });
    },
  });

  const logs = q.data ?? [];
  return (
    <div className="space-y-3">
      <div className="grid gap-2 sm:grid-cols-2">
        <Select
          label="Call type"
          value={callType}
          onChange={(e) => setCallType(e.target.value)}
          options={CALL_TYPES}
          className="!mb-0"
        />
        <Select
          label="Outcome"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value)}
          options={CALL_OUTCOMES}
          className="!mb-0"
        />
      </div>
      {outcome === "CALLBACK" && (
        <label className="block text-xs text-muted">
          Callback on
          <input
            type="date"
            value={callbackOn}
            onChange={(e) => setCallbackOn(e.target.value)}
            className="mt-1 w-full rounded border border-line px-3 py-2 text-sm text-ink"
          />
        </label>
      )}
      <textarea
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        rows={3}
        placeholder="Call notes…"
        className="w-full rounded border border-line px-3 py-2 text-sm"
      />
      <button
        onClick={() => add.mutate()}
        disabled={add.isPending}
        className="btn btn-sm btn-navy disabled:opacity-50"
      >
        {add.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />} Log call
      </button>
      {add.error && <p className="text-xs text-error-700">{errMessage(add.error)}</p>}

      {q.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : logs.length === 0 ? (
        <p className="text-sm text-muted">No call logs yet.</p>
      ) : (
        <ul className="space-y-2">
          {logs.map((r) => (
            <li key={r.id} className="rounded border border-line p-2.5">
              <p className="text-sm font-semibold text-ink">
                {r.callType} · {r.outcome}
                {r.callbackOn ? ` · callback ${r.callbackOn}` : ""}
              </p>
              {r.notes && <p className="mt-1 whitespace-pre-wrap text-sm text-ink">{r.notes}</p>}
              <p className="mt-1 text-[11px] text-muted">
                {r.author ?? "staff"}
                {r.at ? ` · ${formatDateTime(r.at)}` : ""}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Audit logs
// ---------------------------------------------------------------------------

function AuditLogsTab({ customerId }: { customerId: number }) {
  const q = useQuery({
    queryKey: ["customer-activity", customerId],
    queryFn: () => customersApi.activity(customerId),
  });
  if (q.isLoading) return <p className="text-sm text-muted">Loading…</p>;
  const items = q.data ?? [];
  if (items.length === 0) return <p className="text-sm text-muted">No activity recorded yet.</p>;
  return (
    <ul className="space-y-2">
      {items.map((e: ActivityEntry, i) => (
        <li key={i} className="flex gap-3 border-b border-line pb-2 last:border-0">
          <span
            className={`mt-0.5 h-fit rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${TYPE_STYLE[e.type] ?? "bg-grey-100 text-muted"}`}
          >
            {e.type}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="font-semibold text-ink">
                {e.title}
                {e.applicationId != null ? (
                  <span className="font-normal text-muted"> · app #{e.applicationId}</span>
                ) : null}
              </span>
              <span className="text-[11px] text-muted">{e.at ? formatDateTime(e.at) : ""}</span>
            </div>
            {e.detail && <p className="break-words text-xs text-muted">{e.detail}</p>}
            {e.actor && <p className="text-[11px] text-muted">by {e.actor}</p>}
          </div>
        </li>
      ))}
    </ul>
  );
}
