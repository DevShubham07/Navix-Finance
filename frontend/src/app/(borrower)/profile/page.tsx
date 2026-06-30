"use client";

import * as React from "react";
import { LogOut, ShieldCheck, Save, Loader2, Lock, AlertTriangle } from "lucide-react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { InfoRow, SummarySection } from "@/components/borrower/summary";
import { KycProgress } from "@/components/borrower/kyc-progress";
import { Input } from "@/components/ui";
import { useLiveApplication, useBorrowerLogout } from "@/lib/api/live-journey";
import { borrowerApi, rupeesToPaise, ApplicationApiError, type ApplicationStatus } from "@/lib/api/applications";
import type { KycCheck, KycState } from "@/lib/domain/borrower";

/** Statuses at/after KYC clearance — all checks read as verified. */
const KYC_CLEARED: ApplicationStatus[] = [
  "KYC_APPROVED", "PRE_APPROVED", "CREDIT_EXEC_PENDING", "CREDIT_EXEC_APPROVED",
  "CREDIT_HEAD_PENDING", "CREDIT_HEAD_APPROVED", "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING",
  "DISBURSEMENT_FAILED", "DISBURSED", "ACTIVE", "OVERDUE", "DEFAULTED", "CLOSED", "WRITTEN_OFF",
];

function kycFromStatus(status: ApplicationStatus | undefined): KycState {
  let check: KycCheck = "PENDING";
  if (status === "KYC_PENDING" || status === "REVIEW_PENDING") check = "IN_PROGRESS";
  else if (status === "KYC_REJECTED") check = "FAILED";
  else if (status && KYC_CLEARED.includes(status)) check = "VERIFIED";
  return { pan: check, aadhaar: check, selfie: check, address: check, bank: check };
}

export default function ProfilePage() {
  const logout = useBorrowerLogout();
  const { appId, app } = useLiveApplication();
  const qc = useQueryClient();

  const profileQ = useQuery({
    queryKey: ["live-profile", appId],
    queryFn: () => borrowerApi.getProfile(appId as number),
    enabled: appId != null,
  });
  const p = profileQ.data;

  // Editable fields (initialised from the loaded profile).
  const [address, setAddress] = React.useState("");
  const [employer, setEmployer] = React.useState("");
  const [employmentStatus, setEmploymentStatus] = React.useState("");
  const [salary, setSalary] = React.useState("");
  const [salaryBank, setSalaryBank] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [ecName, setEcName] = React.useState("");
  const [ecPhone, setEcPhone] = React.useState("");
  const [ecRelation, setEcRelation] = React.useState("");

  React.useEffect(() => {
    if (!p) return;
    setAddress(p.address ?? "");
    setEmployer(p.employer ?? "");
    setEmploymentStatus(p.employmentStatus ?? "");
    setSalary(p.monthlySalaryPaise != null ? String(Math.round(p.monthlySalaryPaise / 100)) : "");
    setSalaryBank(p.salaryBank ?? "");
    setEmail(p.email ?? "");
    setEcName(p.emergencyContactName ?? "");
    setEcPhone(p.emergencyContactPhone ?? "");
    setEcRelation(p.emergencyContactRelation ?? "");
  }, [p]);

  const save = useMutation({
    mutationFn: () =>
      borrowerApi.editProfile(appId as number, {
        address: address.trim() || null,
        employer: employer.trim() || null,
        employmentStatus: employmentStatus.trim() || null,
        monthlySalaryPaise: salary ? rupeesToPaise(Number(salary.replace(/[^\d.]/g, ""))) : null,
        salaryBank: salaryBank.trim() || null,
        email: email.trim() || null,
        emergencyContactName: ecName.trim() || null,
        emergencyContactPhone: ecPhone.trim() || null,
        emergencyContactRelation: ecRelation.trim() || null,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["live-profile", appId] }),
  });

  if (appId == null && !profileQ.isLoading) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Your profile</h1>
          <p className="mb-4 text-muted">Complete your application to set up your profile.</p>
          <button onClick={() => logout()} className="btn btn-outline"><LogOut size={16} /> Sign out</button>
        </div>
      </div>
    );
  }

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7">
        <h1 className="mb-0">{p?.fullName || "Your profile"}</h1>
        <p className="mt-1 text-muted">{p?.email || "Verified borrower"}</p>
      </div>

      {profileQ.isLoading ? (
        <div className="h-72 animate-pulse rounded border border-line bg-white" />
      ) : (
        <div className="grid gap-4">
          {/* Identity — locked. */}
          <SummarySection title="Identity">
            <InfoRow label="Full name" value={p?.fullName} />
            <InfoRow label="PAN" value={<span className="flex items-center justify-end gap-1.5">{p?.pan} <Lock size={12} className="text-muted" /></span>} />
            <InfoRow label="Aadhaar" value={<span className="flex items-center justify-end gap-1.5">{p?.aadhaar} <Lock size={12} className="text-muted" /></span>} />
            <InfoRow label="Mobile" value={<span className="flex items-center justify-end gap-1.5">{p?.mobile} {p?.mobile && <ShieldCheck size={14} className="text-success-600" />}</span>} />
            <p className="pt-1 text-xs text-muted">Identity details are locked. Contact support to change your PAN, Aadhaar or mobile.</p>
          </SummarySection>

          {/* Editable fields. */}
          <SummarySection title="Contact & address">
            <div className="grid gap-3 sm:grid-cols-2">
              <Input label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} className="!mb-0" />
              <Input label="Address" value={address} onChange={(e) => setAddress(e.target.value)} className="!mb-0" />
            </div>
          </SummarySection>

          <SummarySection title="Employment & income">
            <div className="grid gap-3 sm:grid-cols-2">
              <Input label="Employer" value={employer} onChange={(e) => setEmployer(e.target.value)} className="!mb-0" />
              <Input label="Employment status" value={employmentStatus} onChange={(e) => setEmploymentStatus(e.target.value)} className="!mb-0" />
              <Input label="Monthly salary (₹)" inputMode="numeric" value={salary} onChange={(e) => setSalary(e.target.value.replace(/[^\d]/g, ""))} className="!mb-0" />
              <Input label="Salary bank" value={salaryBank} onChange={(e) => setSalaryBank(e.target.value)} className="!mb-0" />
            </div>
            <div className="mt-3 flex items-start gap-2 rounded border border-gold-soft bg-gold-50/60 px-3 py-2 text-xs text-ink">
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0 text-gold-dark" />
              Changing your address, salary or bank will require those checks to be re-verified before your next loan.
            </div>
          </SummarySection>

          <SummarySection title="Emergency contact">
            <div className="grid gap-3 sm:grid-cols-3">
              <Input label="Name" value={ecName} onChange={(e) => setEcName(e.target.value)} className="!mb-0" />
              <Input label="Phone" inputMode="tel" value={ecPhone} onChange={(e) => setEcPhone(e.target.value)} className="!mb-0" />
              <Input label="Relationship" value={ecRelation} onChange={(e) => setEcRelation(e.target.value)} className="!mb-0" />
            </div>
          </SummarySection>

          {save.error && (
            <p className="text-sm text-error-700">
              {save.error instanceof ApplicationApiError ? `${save.error.message} (${save.error.code})` : "Could not save your changes."}
            </p>
          )}
          {save.isSuccess && <p className="text-sm text-success-700">Profile updated.</p>}

          <div>
            <button onClick={() => save.mutate()} disabled={save.isPending || appId == null} className="btn btn-gold disabled:opacity-50">
              {save.isPending ? <Loader2 size={16} className="animate-spin" /> : <Save size={16} />} Save changes
            </button>
          </div>

          <div>
            <div className="mb-2 text-sm font-semibold text-navy">KYC status</div>
            <KycProgress kyc={kycFromStatus(app?.status)} />
          </div>
        </div>
      )}

      <button onClick={() => logout()} className="btn btn-outline mt-7">
        <LogOut size={16} /> Sign out
      </button>
    </div>
  );
}
