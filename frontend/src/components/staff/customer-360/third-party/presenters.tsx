"use client";

/**
 * Presentable cards for the Third-party logs tab. Each provider body maps a StepResult's `derived`
 * map (keys verified against ApplicationVerificationService) to cards; only keys that exist render.
 * Aadhaar: the provider `derived` only carries `maskedAadhaar`; staff see the full number, taken
 * from the customer profile (`aadhaar` prop), falling back to the masked value when none is on file.
 */

import * as React from "react";
import {
  BadgeCheck, CalendarClock, Camera, FileSignature, Home, IdCard, Link2, Mail, MapPin, Banknote,
  ShieldCheck, UserRound, Briefcase, type LucideIcon,
} from "lucide-react";
import { Badge } from "@/components/ui";
import { paiseToINR, type StepResult } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

type D = Record<string, unknown>;
export type Item = { label: string; value: React.ReactNode; mono?: boolean; key?: boolean };
type Variant = "success" | "warning" | "error" | "neutral" | "info";

const has = (v: unknown) => v != null && v !== "";
const str = (v: unknown): string | null => (has(v) ? String(v) : null);
const date = (v: unknown) => (typeof v === "string" && /^\d{4}-\d{2}-\d{2}/.test(v) ? formatDate(v) : str(v));
const pill = (text: React.ReactNode, variant: Variant = "neutral") => (
  <Badge variant={variant} size="sm">{text}</Badge>
);

export function YesNo({ v }: { v: unknown }) {
  if (v == null) return <>—</>;
  return <Badge variant={v === true ? "success" : "neutral"} size="sm">{v === true ? "Yes" : "No"}</Badge>;
}
/** A Yes/No pill row value, or null (row hidden) when the key is absent. */
const yn = (v: unknown) => (v == null ? null : <YesNo v={v} />);

function CardShell({ title, icon: Icon, children }: { title: string; icon: LucideIcon; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-3">
      <div className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-black">
        <Icon size={14} className="shrink-0" /> <span>{title}</span>
      </div>
      {children}
    </div>
  );
}

export function InfoCard({ title, icon, items }: { title: string; icon: LucideIcon; items: Item[] }) {
  const shown = items.filter((i) => has(i.value));
  if (shown.length === 0) return null;
  return (
    <CardShell title={title} icon={icon}>
      <div className="grid grid-cols-2 gap-x-5 gap-y-3">
        {shown.map((i) => (
          <div key={i.label} className="min-w-0">
            <div className={`text-[8.8px] font-semibold uppercase tracking-wide ${i.key ? "text-info-500" : "text-black"}`}>
              {i.label}
            </div>
            <div className={`break-words text-[10.4px] text-black ${i.mono ? "font-mono tabular-nums" : ""}`}>{i.value}</div>
          </div>
        ))}
      </div>
    </CardShell>
  );
}

export type StatusRow = { icon: LucideIcon; label: string; pill: React.ReactNode };
export function StatusCard({ title, rows }: { title: string; rows: StatusRow[] }) {
  const shown = rows.filter((r) => r.pill != null);
  if (shown.length === 0) return null;
  return (
    <CardShell title={title} icon={ShieldCheck}>
      <ul className="space-y-2">
        {shown.map((r) => (
          <li key={r.label} className="flex items-center justify-between gap-3 text-[10.4px] text-black">
            <span className="flex items-center gap-1.5"><r.icon size={13} className="shrink-0" />{r.label}</span>
            {r.pill}
          </li>
        ))}
      </ul>
    </CardShell>
  );
}

export function AddressCard({ title = "Address Information", items }: { title?: string; items: Item[] }) {
  return <InfoCard title={title} icon={Home} items={items} />;
}

const Grid = ({ children }: { children: React.ReactNode }) => (
  <div className="grid gap-3 md:grid-cols-2">{children}</div>
);

export const checkedAt = (s: StepResult) => (s.checkedAt ? formatDateTime(s.checkedAt) : null);
export const statusLabel = (s: StepResult) =>
  s.status === "PASS" ? "Verified" : s.status === "FAIL" ? "Failed" : s.status === "REVIEW" ? "Review" : "Pending";
export const statusVariant = (s: StepResult): Variant =>
  s.status === "PASS" ? "success" : s.status === "FAIL" ? "error" : s.status === "REVIEW" ? "warning" : "neutral";

type Body = { step: StepResult; aadhaar?: string | null };

export function PanBody({ step: s, aadhaar }: Body) {
  const d = s.derived as D;
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 rounded border border-line bg-neutral-50 px-3 py-1.5 text-[10.4px] text-black">
        <span>Registered PAN — PAN entered on this lead at onboarding</span>
        <span className="rounded bg-navy px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-white">Lead PAN</span>
      </div>
      <Grid>
        <InfoCard title="Personal Information" icon={UserRound} items={[
          { label: "Full name", value: str(d.fullName) },
          { label: "PAN number", value: str(d.panNumber), mono: true, key: true },
          { label: "Date of birth", value: date(d.dob) },
          { label: "Gender", value: str(d.gender) },
          { label: "Aadhaar number", value: str(aadhaar) ?? str(d.maskedAadhaar), mono: true, key: true },
          { label: "PAN allotted", value: date(d.panAllotmentDate) },
        ]} />
        <StatusCard title="Verification Status" rows={[
          { icon: Link2, label: "Aadhaar linked", pill: yn(d.aadhaarLinked) },
          { icon: BadgeCheck, label: "PAN status", pill: has(d.panStatus) ? pill(String(d.panStatus), /valid/i.test(String(d.panStatus)) ? "success" : "warning") : null },
          { icon: ShieldCheck, label: "Compliant", pill: yn(d.compliant) },
          { icon: CalendarClock, label: "Last updated", pill: checkedAt(s) ? pill(checkedAt(s)) : null },
        ]} />
      </Grid>
      <AddressCard items={[
        { label: "State", value: str(d.addressState) },
        { label: "PIN code", value: str(d.addressZip), mono: true },
      ]} />
    </div>
  );
}

export function UanBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <InfoCard title="Employment Record" icon={Briefcase} items={[
        { label: "UAN", value: str(d.uan) ?? str(d.uanMasked), mono: true, key: true },
        { label: "Employer (EPFO)", value: str(d.employerName) },
        { label: "Declared employer", value: str(d.declaredEmployer) },
        { label: "Joined", value: date(d.dateOfJoining) },
        { label: "Exited", value: date(d.dateOfExit) },
        { label: "Tenure", value: d.tenureMonths == null ? null : `${d.tenureMonths} months` },
        { label: "Establishment ID", value: str(d.establishmentId), mono: true },
        { label: "UAN count", value: str(d.uanCount) },
        { label: "Leave reason", value: str(d.leaveReason) },
      ]} />
      <StatusCard title="Verification Status" rows={[
        { icon: BadgeCheck, label: "Record found", pill: yn(d.found) },
        { icon: Briefcase, label: "Currently employed", pill: yn(d.employed) },
        { icon: UserRound, label: "Name match", pill: yn(d.employeeNameMatch) },
        { icon: Briefcase, label: "Employer match", pill: yn(d.employerNameMatch) },
        { icon: CalendarClock, label: "Recent PF filing", pill: yn(d.recentPfFiling) },
      ]} />
    </Grid>
  );
}

export function AadhaarBody({ step: s, aadhaar }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <InfoCard title="Personal Information" icon={IdCard} items={[
        { label: "Full name", value: str(d.fullName) },
        { label: "Aadhaar number", value: str(aadhaar) ?? str(d.maskedAadhaar), mono: true, key: true },
        { label: "Date of birth", value: date(d.dob) },
        { label: "Gender", value: str(d.gender) },
        { label: "Status", value: s.provider ? pill(`${statusLabel(s)} · ${s.provider}`, statusVariant(s) === "success" ? "success" : "neutral") : null },
        { label: "Valid signature", value: yn(d.validDsc) },
      ]} />
      <AddressCard items={[
        { label: "Address", value: str(d.address) },
        { label: "Address line", value: str(d.addressLine) },
        { label: "Landmark", value: str(d.landmark) },
        { label: "City", value: str(d.city) },
        { label: "District", value: str(d.district) },
        { label: "State", value: str(d.state) },
        { label: "PIN code", value: str(d.pincode), mono: true },
        { label: "Country", value: str(d.country) },
      ]} />
    </Grid>
  );
}

export function EmailBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <InfoCard title="Email Details" icon={Mail} items={[
        { label: "Person", value: str(d.personName) },
        { label: "Company", value: str(d.companyName) },
        { label: "Domain", value: str(d.domain), mono: true, key: true },
        { label: "Matched establishment", value: str(d.matchedEstablishment) },
        { label: "Mail server", value: str(d.mxRecord), mono: true },
        { label: "Did you mean", value: str(d.didYouMean) },
        { label: "Provider status", value: str(d.status) },
      ]} />
      <StatusCard title="Verification Status" rows={[
        { icon: BadgeCheck, label: "Deliverable", pill: yn(d.verified) },
        { icon: Mail, label: "Mail server found", pill: yn(d.mxFound) },
        { icon: Briefcase, label: "Employer match", pill: yn(d.establishmentMatched) },
        { icon: UserRound, label: "Name match", pill: yn(d.individualMatched) },
        { icon: Mail, label: "Generic address", pill: yn(d.genericEmail) },
      ]} />
    </Grid>
  );
}

export function BureauBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <InfoCard title="Credit Summary" icon={Banknote} items={[
        { label: "Active accounts", value: str(d.activeAccounts) },
        { label: "Overdue accounts", value: str(d.overdueAccounts) },
        { label: "Total balance", value: has(d.totalBalance) && Number.isFinite(Number(d.totalBalance)) ? paiseToINR(Math.round(Number(d.totalBalance) * 100)) : str(d.totalBalance) },
        { label: "Source", value: str(d.source) },
      ]} />
      <StatusCard title="Verification Status" rows={[
        { icon: BadgeCheck, label: "Record found", pill: d.noRecord == null ? null : <YesNo v={d.noRecord !== true} /> },
        { icon: ShieldCheck, label: "Identity mismatch", pill: d.identityMismatch == null ? null : <YesNo v={Boolean(d.identityMismatch)} /> },
      ]} />
    </Grid>
  );
}

export function PennyBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <InfoCard title="Bank Account" icon={Banknote} items={[
        { label: "Beneficiary name", value: str(d.beneficiaryName) },
        { label: "Account number", value: str(d.accountNumber), mono: true, key: true },
        { label: "IFSC", value: str(d.ifsc), mono: true, key: true },
        { label: "Bank", value: str(d.bank) },
        { label: "Bank RRN", value: str(d.bankRrn), mono: true },
        { label: "Reason", value: str(d.reason) },
      ]} />
      <StatusCard title="Verification Status" rows={[
        { icon: BadgeCheck, label: "Account exists", pill: yn(d.accountExists) },
        { icon: UserRound, label: "Name match", pill: d.nameMatch == null ? null : pill(`${Math.round(Number(d.nameMatch) * 100)}%`, Number(d.nameMatch) >= 0.6 ? "success" : "warning") },
      ]} />
    </Grid>
  );
}

export function SelfieBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <StatusCard title="Liveness and Face Match" rows={[
      { icon: Camera, label: "Completed", pill: yn(d.completed) },
      { icon: Camera, label: "Live person", pill: yn(d.live) },
      { icon: UserRound, label: "Face matched to Aadhaar", pill: yn(d.faceMatched) },
      { icon: BadgeCheck, label: "Match percentage", pill: d.matchPercentage == null ? null : pill(`${d.matchPercentage}%`) },
      { icon: BadgeCheck, label: "Liveness score", pill: d.livenessScore == null ? null : pill(String(d.livenessScore)) },
    ]} />
  );
}

export function AddressCheckBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <Grid>
      <AddressCard title="Resolved Address" items={[
        { label: "Address", value: str(d.address) ?? str(d.manualAddress) },
        { label: "District", value: str(d.district) },
        { label: "State", value: str(d.state) },
        { label: "PIN code", value: str(d.pincode), mono: true },
        { label: "Country", value: str(d.country) },
      ]} />
      <StatusCard title="Verification Status" rows={[
        { icon: MapPin, label: "Within India", pill: yn(d.withinIndia) },
        { icon: BadgeCheck, label: "Confidence", pill: d.confidenceScore == null ? null : pill(String(d.confidenceScore)) },
      ]} />
    </Grid>
  );
}

export function EsignBody({ step: s }: Body) {
  const d = s.derived as D;
  return (
    <StatusCard title="Aadhaar eSign" rows={[
      { icon: FileSignature, label: "Signed", pill: d.signedAt ? pill(formatDateTime(String(d.signedAt)), "success") : yn(d.completed) },
      { icon: ShieldCheck, label: "Match mode", pill: has(d.matchMode) ? pill(String(d.matchMode)) : null },
      { icon: BadgeCheck, label: "Signature reference", pill: has(d.signatureRef) ? pill(String(d.signatureRef)) : null },
    ]} />
  );
}
