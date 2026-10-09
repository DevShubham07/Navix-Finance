"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Briefcase, ExternalLink, IdCard, Pencil, Phone, ShieldCheck, User } from "lucide-react";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { CreditBadge } from "@/components/staff/credit-badge";
import { CustomerEditDialog } from "@/components/staff/customer-edit-dialog";
import { CustomerOwnerPicker } from "@/components/staff/customer-owner-picker";
import { LimitBasisBadge, Section } from "@/components/staff/detail-parts";
import { PermissionGate } from "@/components/staff/live-pipeline";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { customerPageHref } from "@/lib/customers/customer-page";
import { staffApi, paiseToINR } from "@/lib/api/applications";
import { displayAnnualSalaryPaise, formatDate, formatDateTime } from "@/lib/utils";

const OtpTick = () => <span className="ml-1.5 text-[9.6px] font-bold text-success-700">OTP ✓</span>;

const str = (v: unknown): string | null => (v == null || v === "" ? null : String(v));

/** §3.1 — three identity cards, the employment card, then the owner row. */
export function CustomerTab({ detail: c, customerId, app, onChanged }: TabCtx) {
  const p = c.profile;
  const latestApp = app ?? c.applications[0] ?? null;
  const [editing, setEditing] = React.useState(false);

  // Same shared key as the other tabs' verification reads — one round trip per application.
  const verQ = useQuery({
    queryKey: ["verifications", latestApp?.id],
    queryFn: () => staffApi.verifications(latestApp!.id),
    enabled: latestApp != null,
  });
  const derivedOf = (type: string) =>
    ((verQ.data ?? []).find((s) => s.checkType === type)?.derived ?? {}) as Record<string, unknown>;
  const pan = derivedOf("PAN");
  const aadhaar = derivedOf("AADHAAR");
  const epfo = derivedOf("EMPLOYMENT");

  const employerPill =
    epfo.employerNameMatch === false ? (
      <span className="rounded-full bg-warning-100 px-2 py-0.5 text-[9.6px] font-semibold normal-case tracking-normal text-warning-800">
        Employer mismatch
      </span>
    ) : epfo.uan || epfo.uanMasked || epfo.found === true ? (
      <span className="rounded-full bg-success-50 px-2 py-0.5 text-[9.6px] font-semibold normal-case tracking-normal text-success-700">
        UAN matched
      </span>
    ) : null;

  const annualPaise = p ? displayAnnualSalaryPaise(p) : null;
  const limitPaise = c.limitOverridePaise ?? latestApp?.eligibleLimitPaise ?? null;

  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-3">
        <Section title="Basic information" icon={User}>
          <FieldGrid cols={2}>
            <Field label="Customer no" keyLabel mono>#{customerId}</Field>
            <Field label="Full name" tone="navy">{p?.fullName}</Field>
            <Field label="Gender">{str(pan.gender)}</Field>
            <Field label="Date of birth">{p?.dob ? formatDate(p.dob) : null}</Field>
            <Field label="Employment type">{p?.employmentStatus}</Field>
            <Field label="Risk category">{p?.riskCategory}</Field>
            <Field label="Credit score">
              {p?.creditScore != null ? (
                <CreditBadge
                  starRating={p.starRating}
                  creditScore={p.creditScore}
                  recommendation={p.recommendation}
                  bureauSource={p.bureauSource}
                />
              ) : null}
            </Field>
          </FieldGrid>
        </Section>

        <Section
          title="Contact information"
          icon={Phone}
          action={
            <PermissionGate permission="customer:manage">
              <button
                type="button"
                onClick={() => setEditing(true)}
                className="btn btn-sm btn-outline btn-icon"
                aria-label="Edit customer details"
                title="Edit customer details"
              >
                <Pencil size={13} />
              </button>
            </PermissionGate>
          }
        >
          <FieldGrid cols={2}>
            <Field label="Personal email">{p?.email}{p?.email && p.personalEmailVerified && <OtpTick />}</Field>
            <Field label="Official email">{p?.officialEmail}{p?.officialEmail && p.officialEmailOtpVerified && <OtpTick />}</Field>
            <Field label="Mobile" mono>{p?.mobile}</Field>
            <Field label="Emergency contact">
              {p?.emergencyContactName
                ? `${p.emergencyContactName}${p.emergencyContactRelation ? ` (${p.emergencyContactRelation})` : ""}${p.emergencyContactPhone ? ` · ${p.emergencyContactPhone}` : ""}`
                : null}
            </Field>
          </FieldGrid>
        </Section>

        <Section title="Identity & address" icon={IdCard}>
          <FieldGrid cols={2}>
            <Field label="PAN" mono>{p?.pan}</Field>
            {/* Typed at intake (V75); shown in full to every staff role by product decision. */}
            <Field label="Aadhaar" mono>{p?.aadhaar}</Field>
            <Field label="Aadhaar linked">{p?.aadhaarLinked == null ? null : p.aadhaarLinked ? "✓ Yes" : "No"}</Field>
            <Field label="Full address" className="col-span-2">{p?.address}</Field>
            <Field label="City">{str(aadhaar.city)}</Field>
            <Field label="State">{str(aadhaar.state ?? pan.addressState)}</Field>
            <Field label="Country">{str(aadhaar.country)}</Field>
            <Field label="PIN" mono>{str(aadhaar.pincode ?? pan.addressZip)}</Field>
          </FieldGrid>
        </Section>
      </div>

      <Section title="Employment" icon={Briefcase} pill={employerPill}>
        <FieldGrid cols={4}>
          <Field label="Company">{p?.employer}</Field>
          <Field label="Salary day">{latestApp?.salaryCreditDay != null ? `Day ${latestApp.salaryCreditDay}` : null}</Field>
          <Field label="Last salary received">{p?.previousSalaryDate ? formatDate(p.previousSalaryDate) : null}</Field>
          <Field label="Net monthly income" tone="navy">
            {p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null}
          </Field>
          <Field label="Annual salary">{annualPaise != null ? paiseToINR(annualPaise) : null}</Field>
          <Field label="Salary %">{p?.salaryPercentage != null ? `${p.salaryPercentage}%` : null}</Field>
          <Field label="Increment %">{p?.incrementPercentage != null ? `${p.incrementPercentage}%` : null}</Field>
          <Field label="UAN" mono>{p?.uan ?? str(epfo.uan) ?? str(epfo.uanMasked)}</Field>
          {/* An ADMIN override is the customer's live ceiling and outranks the salary rule. */}
          <Field label="Eligible limit">
            {limitPaise != null ? (
              <span className="inline-flex flex-wrap items-center gap-1.5">
                {paiseToINR(limitPaise)}
                <LimitBasisBadge overridePaise={c.limitOverridePaise} className="px-1.5 py-0 text-[9.6px]" />
              </span>
            ) : null}
          </Field>
        </FieldGrid>
      </Section>

      {/* The consent trail (terms / PEP) — an auditor's two standard questions. */}
      <Section title="Compliance" icon={ShieldCheck}>
        <FieldGrid cols={3}>
          <Field label="Terms version" mono>{p?.termsVersion}</Field>
          <Field label="Terms accepted">{p?.termsAcceptedAt ? formatDateTime(p.termsAcceptedAt) : null}</Field>
          <Field label="PEP declared">{p?.pepDeclaredAt ? formatDateTime(p.pepDeclaredAt) : null}</Field>
        </FieldGrid>
      </Section>

      <CustomerOwnerPicker
        customerId={c.customerId}
        ownerStaffId={c.ownerStaffId}
        ownerName={c.ownerName}
        onChanged={onChanged}
      />

      <p className="text-sm text-muted">
        <Link
          href={customerPageHref(customerId)}
          className="inline-flex items-center gap-1 font-semibold text-navy hover:underline"
        >
          Open full customer page <ExternalLink size={13} />
        </Link>
      </p>

      {editing && (
        <CustomerEditDialog
          customerId={customerId}
          onClose={() => {
            setEditing(false);
            onChanged();
          }}
        />
      )}
    </div>
  );
}
