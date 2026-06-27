"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Users, Info } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { normalizeMobile } from "@/lib/utils";

const RELATIONSHIPS = ["Spouse", "Parent", "Sibling", "Child", "Other"].map((r) => ({ value: r, label: r }));
const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

export default function SignupCoApplicantPage() {
  const router = useRouter();
  const { applicant, updateApplicant, coApplicantRequired } = useBorrowerJourney();
  const [add, setAdd] = React.useState(Boolean(applicant.coApplicant) || coApplicantRequired);
  const [fullName, setFullName] = React.useState(applicant.coApplicant?.fullName ?? "");
  const [pan, setPan] = React.useState(applicant.coApplicant?.pan ?? "");
  const [mobile, setMobile] = React.useState(applicant.coApplicant?.mobile ?? "");
  const [relationship, setRelationship] = React.useState(applicant.coApplicant?.relationship ?? "Spouse");
  const [touched, setTouched] = React.useState(false);

  const fieldsOk = fullName.trim().length > 2 && PAN_RE.test(pan) && mobile.replace(/\D/g, "").length === 10;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (add) {
      if (!fieldsOk) { setTouched(true); return; }
      updateApplicant({ coApplicant: { fullName: fullName.trim(), pan, mobile, relationship } });
    } else {
      updateApplicant({ coApplicant: undefined });
    }
    router.push("/signup/salary");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <div className="mb-4 flex items-start gap-3 rounded border border-navy-tint bg-navy-tint/60 p-4 text-sm text-ink">
          <Info size={18} className="mt-0.5 flex-shrink-0 text-navy" />
          <p className="m-0">
            A co-applicant shares repayment responsibility and completes the same verification you did.
            It&apos;s <strong>required for some risk categories</strong> and can raise your limit — optional
            otherwise.
          </p>
        </div>

        <label className="checkbox mb-4">
          <input type="checkbox" checked={add} onChange={(e) => setAdd(e.target.checked)} />
          <span>Add a co-applicant to this application</span>
        </label>

        {add && (
          <div className="border-t border-grey-200 pt-4">
            <div className="mb-4 flex items-center gap-2 text-sm font-semibold text-navy">
              <Users size={16} /> Co-applicant details
            </div>
            <Input
              label="Full name (as per PAN)"
              required
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder="Co-applicant name"
              error={touched && fullName.trim().length <= 2 ? "Enter the co-applicant's name" : undefined}
            />
            <div className="field-row">
              <Input
                label="PAN"
                required
                value={pan}
                onChange={(e) => setPan(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10))}
                placeholder="ABCDE1234F"
                inputClassName="uppercase tracking-wider"
                error={touched && !PAN_RE.test(pan) ? "Valid PAN required" : undefined}
              />
              <Input
                label="Mobile"
                required
                inputMode="numeric"
                maxLength={10}
                value={mobile}
                onChange={(e) => setMobile(normalizeMobile(e.target.value))}
                placeholder="98765 43210"
                error={touched && mobile.length !== 10 ? "Enter a valid 10-digit mobile number" : undefined}
              />
            </div>
            <Select label="Relationship" value={relationship} onChange={(e) => setRelationship(e.target.value)} options={RELATIONSHIPS} />
          </div>
        )}
      </div>
      <WizardActions backHref="/signup/bureau" submit continueLabel={add ? "Add & continue" : "Continue without co-applicant"} />
      <Reassurance />
    </form>
  );
}
