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
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import { Section, KV, Bool, DocumentsTab, RemarksTab } from "@/components/staff/detail-parts";
import { CustomerOwnerPicker } from "@/components/staff/customer-owner-picker";
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

const todayISO = () => new Date().toISOString().slice(0, 10);

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
  applicationId,
  onChanged,
}: {
  tab: string;
  detail: CustomerDetail;
  customerId: number;
  applicationId?: number;
  /** Fired after cancel / owner assign so parents can refetch. */
  onChanged?: () => void;
}) {
  const latestAppId = applicationId ?? detail.applications[0]?.id ?? null;

  switch (tab) {
    case "personal":
      return <PersonalTab c={detail} applicationId={latestAppId} onChanged={onChanged} />;
    case "employment":
      return <EmploymentTab c={detail} />;
    case "bank":
      return <BankTab c={detail} latestAppId={latestAppId} />;
    case "credit":
      return <CreditTab c={detail} latestAppId={latestAppId} />;
    case "documents":
      // Grouped mode: every application this customer ever filed, not just the newest — a
      // reborrow's prior-application uploads must stay reachable (item 4).
      return applicationId != null ? (
        <DocumentsTab applicationId={applicationId} />
      ) : (
        <DocumentsTab customerId={customerId} />
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

function PersonalTab({ c, applicationId, onChanged }: { c: CustomerDetail; applicationId: number | null; onChanged?: () => void }) {
  const p = c.profile;
  const currentLoan =
    c.loans.find((l) =>
      ["ACTIVE", "OVERDUE", "DISBURSED", "IN_COLLECTIONS", "DEFAULTED"].includes(l.status),
    ) ??
    c.loans[0] ??
    null;
  const latestApp = c.applications.find((app) => app.id === applicationId) ?? c.applications[0] ?? null;

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
  // Item 3b: the itemized interest/penalty/paid breakdown only renders when `outstanding` is
  // passed — without it LoanBreakdown falls back to the loan's stale cached totalRepayable.
  const outQ = useQuery({
    queryKey: ["staff-loan-out", currentLoan?.id, todayISO()],
    queryFn: () => staffApi.outstanding(currentLoan!.id, todayISO()),
    enabled: currentLoan != null,
    retry: false,
  });

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title="Identity & profile">
        <KV k="Full name" v={p?.fullName} />
        <KV k="PAN" v={p?.pan} mono />
        <KV k="Mobile" v={p?.mobile} mono />
        <KV k="Email" v={p?.email} />
        <KV k="Official (work) email" v={p?.officialEmail} />
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

      <CustomerOwnerPicker
        customerId={c.customerId}
        ownerStaffId={c.ownerStaffId}
        ownerName={c.ownerName}
        onChanged={onChanged}
      />

      <div className="md:col-span-2">
        <Section title="Loan cost calculation">
          {currentLoan ? (
            <LoanBreakdown loan={currentLoan} outstanding={outQ.data} />
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

// ---------------------------------------------------------------------------
// Employment
// ---------------------------------------------------------------------------

function EmploymentTab({ c }: { c: CustomerDetail }) {
  const p = c.profile;
  const latestApp = c.applications[0] ?? null;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title="Employment & salary (declared)">
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
      {latestApp != null && <EpfoEmploymentCard applicationId={latestApp.id} />}
    </div>
  );
}

/**
 * The EPFO/UAN counterpart to the declared block above — what Digitap's UAN Advanced lookup found,
 * so a reviewer can put the borrower's claim and the provident-fund record side by side.
 *
 * <p>Reads the EMPLOYMENT verification row's `derived` fields. The match booleans are deliberately
 * tri-state: the provider returns null when the corresponding name was never sent, and rendering that
 * as "No" would read as a contradiction that was never actually checked.
 */
function EpfoEmploymentCard({ applicationId }: { applicationId: number }) {
  const q = useQuery({
    queryKey: ["customer-verifications-employment", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    enabled: applicationId != null,
  });
  const step = (q.data ?? []).find((s) => s.checkType === "EMPLOYMENT");
  const d = (step?.derived ?? {}) as Record<string, unknown>;

  if (!step) {
    return (
      <Section title="Employment (EPFO)">
        <p className="text-sm text-muted">No EPFO employment check has been run for this application.</p>
      </Section>
    );
  }

  const tenure = d.tenureMonths;
  return (
    <Section title="Employment (EPFO)">
      <KV k="Status" v={`${step.status}${step.message ? ` — ${step.message}` : ""}`} />
      <KV k="Employer on record" v={str(d.employerName)} />
      <KV k="Currently employed" v={triState(d.employed)} />
      <KV k="Date of joining" v={str(d.dateOfJoining)} />
      <KV k="Date of exit" v={str(d.dateOfExit)} />
      <KV k="Tenure" v={typeof tenure === "number" ? `${tenure} month${tenure === 1 ? "" : "s"}` : null} />
      <KV k="Employer name match" v={triState(d.employerNameMatch)} />
      <KV k="Employee name match" v={triState(d.employeeNameMatch)} />
      <KV k="Recent PF filing" v={triState(d.recentPfFiling)} />
      <KV k="UAN" v={str(d.uanMasked)} mono />
    </Section>
  );
}

/** true → "Yes", false → "No", null/undefined → null (KV renders its own placeholder). */
function triState(v: unknown): string | null {
  if (v === true) return "Yes";
  if (v === false) return "No";
  return null;
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
  const providerKeys = [
    "accountNumber", "account", "ifsc", "bank", "nameMatch", "nameMatched",
    "providerNameMatch", "beneficiaryName", "name", "bankRrn", "accountExists",
  ];
  const hasProviderData = providerKeys.some((key) => derived[key] != null && derived[key] !== "");
  const manualNotice = derived.manualOverride
    ? `Manually overridden by ${String(derived.manualBy ?? "staff")} on ${String(derived.manualAt ?? "an unknown date")}`
    : null;
  let emptyPennyCopy: string | null = null;
  if (!hasProviderData) {
    if (manualNotice) {
      emptyPennyCopy = `${manualNotice} — no automated provider data on record.`;
    } else if (!penny) {
      emptyPennyCopy = "No penny-drop on this application — verification was carried over from a previous application.";
    } else if (derived.providerError) {
      emptyPennyCopy = String(derived.reason ?? "The provider could not complete penny-drop verification.");
    } else {
      emptyPennyCopy = "Not run — the borrower kept their salary account.";
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-xs text-muted">
        No dedicated bank-account entity — synthesized from the salary bank on the KYC profile,
        the latest penny-drop verification, and disbursal transaction refs on loans.
      </p>
      <Section title="Salary bank">
        <StaffFieldTable rows={[
          ["Bank", p?.salaryBank, "KYC profile"],
          ["Account", p?.salaryAccountNumber, "KYC profile"],
          ["IFSC", p?.salaryIfsc, "KYC profile"],
          ["Penny drop verified", <Bool key="penny" on={p?.pennyDropVerified} />, "KYC profile"],
        ]} />
      </Section>
      <Section title="Penny-drop derived">
        {pennyQ.isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : emptyPennyCopy ? (
          <p className="text-sm text-muted">{emptyPennyCopy}</p>
        ) : (
          <>
            {manualNotice && <p className="mb-2 text-xs text-muted">{manualNotice}.</p>}
            <StaffFieldTable rows={[
              ["Account", str(derived.accountNumber ?? derived.account), "Penny-drop provider"],
              ["IFSC", str(derived.ifsc), "Penny-drop provider"],
              ["Bank", str(derived.bank), "Penny-drop provider"],
              ["Name match", str(derived.nameMatch ?? derived.nameMatched), "Penny-drop provider"],
              ["Provider name match", str(derived.providerNameMatch), "Penny-drop provider"],
              ["Beneficiary name", str(derived.beneficiaryName ?? derived.name), "Penny-drop provider"],
              ["Bank RRN", str(derived.bankRrn), "Penny-drop provider"],
              ["Reason", str(derived.reason), "Penny-drop provider"],
              ["Account exists", str(derived.accountExists), "Penny-drop provider"],
            ]} />
          </>
        )}
      </Section>
      <Section title="Disbursal txn refs">
        {c.loans.length === 0 ? (
          <p className="text-sm text-muted">No loans.</p>
        ) : (
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <thead><tr><th>Loan ID</th><th>Transaction reference</th></tr></thead>
              <tbody>{c.loans.map((loan) => (
                <tr key={loan.id}><td>#{loan.id}</td><td className="font-mono">{loan.disbursalTxnRef ?? "—"}</td></tr>
              ))}</tbody>
            </table>
          </div>
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
      <div className="rounded border border-line bg-white p-5 shadow-sm">
        <CreditScoreGauge
          score={p?.creditScore ?? c.creditBrief?.creditScore ?? null}
          starRating={p?.starRating ?? c.creditBrief?.starRating ?? null}
          recommendation={p?.recommendation ?? c.creditBrief?.recommendation ?? null}
          size="md"
        />
      </div>
      {latestAppId != null && <CreditProfileCard applicationId={latestAppId} />}
      <Section title="Credit headline">
        <StaffFieldTable rows={[
          ["Bureau score", p?.creditScore != null ? String(p.creditScore) : null, "Customer profile"],
          ["Star rating", p?.starRating != null ? `${p.starRating.toFixed(1)}★` : null, "Customer profile"],
          ["Recommendation", p?.recommendation, "Customer profile"],
          ["Risk category", p?.riskCategory, "Customer profile"],
          ["Bureau", p?.bureauSource, "Customer profile"],
          ["Credit brief summary", p?.creditBriefSummary, "Credit brief"],
          ["Credit brief generated", p?.creditBriefGeneratedAt ? formatDateTime(p.creditBriefGeneratedAt) : null, "Credit brief"],
        ]} />
      </Section>
      {latestAppId != null && (
        <Section title="Bureau pull (derived)">
          <StaffFieldTable rows={[
            ["Source", str(bd.source), "Bureau provider"],
            ["No record", str(bd.noRecord), "Bureau provider"],
            ["Active accounts", str(bd.activeAccounts), "Bureau provider"],
            ["Overdue / defaults", str(bd.overdueAccounts), "Bureau provider"],
            ["Total balance", str(bd.totalBalance), "Bureau provider"],
          ]} />
        </Section>
      )}
      {c.creditBrief?.facts && (
        <Section title="Credit facts (customer record)">
          <StaffFieldTable rows={[
            ["Total accounts", String(c.creditBrief.facts.totalAccounts ?? "—"), "Credit brief"],
            ["Active accounts", String(c.creditBrief.facts.activeAccounts ?? "—"), "Credit brief"],
            ["Closed accounts", String(c.creditBrief.facts.closedAccounts ?? "—"), "Credit brief"],
            ["Defaults", String(c.creditBrief.facts.defaults ?? "—"), "Credit brief"],
            ["Total balance", c.creditBrief.facts.totalBalance != null ? `₹${c.creditBrief.facts.totalBalance.toLocaleString("en-IN")}` : "—", "Credit brief"],
            ["Inquiries (30d)", String(c.creditBrief.facts.recentInquiries30d ?? "—"), "Credit brief"],
          ]} />
        </Section>
      )}
    </div>
  );
}

function StaffFieldTable({ rows }: { rows: Array<[string, React.ReactNode, string]> }) {
  return (
    <div className="staff-table-scroll">
      <table className="staff-data-table">
        <thead><tr><th>Field</th><th>Value</th><th>Source</th></tr></thead>
        <tbody>
          {rows.map(([field, value, source]) => (
            <tr key={field}>
              <td className="font-semibold text-ink">{field}</td>
              <td>{value || "—"}</td>
              <td className="text-muted">{source}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
      <KV k="Address line" v={d.addressLine} />
      <KV k="Landmark" v={d.landmark} />
      <KV k="State" v={d.state} />
      <KV k="District" v={d.district} />
      <KV k="City" v={d.city} />
      <KV k="PIN code" v={d.pincode} mono />
      <KV k="Country" v={d.country} />
      <KV k="Aadhaar signer" v={d.dscSubject} />
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
              <LoanCard key={l.id} loan={l} onSelect={() => setSelectedLoan(l)} />
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

/** One loan row on the Loan applications tab — fetches its own outstanding so LoanBreakdown can
 *  itemize interest/penalty/paid instead of showing the stale cached total (item 3b). */
function LoanCard({ loan, onSelect }: { loan: LoanView; onSelect: () => void }) {
  const outQ = useQuery({
    queryKey: ["staff-loan-out", loan.id, todayISO()],
    queryFn: () => staffApi.outstanding(loan.id, todayISO()),
    retry: false,
  });
  return (
    <div className="rounded border border-line p-3">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <button type="button" onClick={onSelect} className="font-semibold text-navy hover:underline">
          Loan #{loan.id} · {paiseToINR(loan.principalPaise)}
        </button>
        <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">
          {loan.status}
        </span>
      </div>
      <LoanBreakdown loan={loan} outstanding={outQ.data} />
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
              <p className="mt-1 text-[8.8px] text-muted">
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
            className={`mt-0.5 h-fit rounded-full px-1.5 py-0.5 text-[8px] font-semibold ${TYPE_STYLE[e.type] ?? "bg-grey-100 text-muted"}`}
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
              <span className="text-[8.8px] text-muted">{e.at ? formatDateTime(e.at) : ""}</span>
            </div>
            {e.detail && <p className="break-words text-xs text-muted">{e.detail}</p>}
            {e.actor && <p className="text-[8.8px] text-muted">by {e.actor}</p>}
          </div>
        </li>
      ))}
    </ul>
  );
}
