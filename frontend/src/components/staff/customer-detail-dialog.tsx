"use client";

/**
 * Unified customer detail popup — a persistent tabbed dialog that replaces the old page-navigation
 * and inline "expand" drilldowns across the staff CRM (customers, all-applications).
 *
 * It is "continued context": the parent keeps it mounted and swaps `customerId`, so the active tab
 * persists as staff click through rows. Six tabs: Basic details · Past details · Documents ·
 * All past logs · Remarks · More options. Data-dense on purpose.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, X, ExternalLink } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { formatDate, formatDateTime } from "@/lib/utils";
import { LoanBreakdown, ProjectedCostBreakdown } from "@/components/staff/loan-breakdown";
import { Section, KV, Bool, DocumentsTab, RemarksTab } from "@/components/staff/detail-parts";
import {
  customersApi,
  paiseToINR,
  statusLabel,
  type CustomerDetail,
  type ApplicationView,
  type ActivityEntry,
} from "@/lib/api/applications";

export function CustomerDetailDialog({
  customerId,
  onClose,
}: {
  customerId: number | null;
  onClose: () => void;
}) {
  const [tab, setTab] = React.useState("basic");
  const open = customerId != null;

  const detailQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open,
  });

  const c = detailQ.data;
  const latestAppId = c?.applications[0]?.id ?? null;

  const tabs: TabDef[] = [
    { key: "basic", label: "Basic details" },
    { key: "past", label: "Past details", badge: c ? c.applications.length + c.loans.length : undefined },
    { key: "documents", label: "Documents" },
    { key: "logs", label: "All past logs" },
    { key: "remarks", label: "Remarks" },
    { key: "more", label: "More options" },
  ];

  // !max-w-4xl / !w-[...]: globals.css's un-layered `.modal { max-width: 460px }` outranks
  // plain utilities in the cascade, so the width needs the important modifier (mirrors
  // stage-detail-dialog.tsx:178).
  return (
    <Dialog open={open} onClose={onClose} className="!max-w-4xl !w-[min(56rem,94vw)]">
      <div className="flex items-center justify-between gap-3 border-b border-line pb-3">
        <div>
          <h3 className="font-serif text-lg text-navy">
            {c?.profile?.fullName ?? "Customer"}{" "}
            <span className="text-sm font-normal text-muted">#{customerId}</span>
          </h3>
          {c?.profile && (
            <p className="text-xs text-muted">
              {c.profile.mobile ?? "—"} · PAN {c.profile.pan ?? "—"}
              {c.profile.riskCategory ? ` · risk ${c.profile.riskCategory}` : ""}
            </p>
          )}
        </div>
        <button onClick={onClose} className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink" aria-label="Close">
          <X size={18} />
        </button>
      </div>

      <Tabs tabs={tabs} active={tab} onChange={setTab} className="mt-2" />

      <div className="mt-3 max-h-[68vh] overflow-y-auto pr-1 text-[13px]">
        {detailQ.isLoading ? (
          <p className="flex items-center gap-2 py-8 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Loading…
          </p>
        ) : detailQ.error ? (
          <p className="py-8 text-sm text-error-700">Could not load this customer.</p>
        ) : !c ? null : (
          <>
            {tab === "basic" && <BasicTab c={c} />}
            {tab === "past" && <PastTab c={c} />}
            {tab === "documents" && latestAppId != null && (
              <DocumentsTab applicationId={latestAppId} />
            )}
            {tab === "documents" && latestAppId == null && (
              <p className="py-6 text-sm text-muted">No application to attach documents to.</p>
            )}
            {tab === "logs" && customerId != null && <LogsTab customerId={customerId} />}
            {tab === "remarks" && customerId != null && <RemarksTab customerId={customerId} />}
            {tab === "more" && <MoreTab c={c} />}
          </>
        )}
      </div>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Tab 1 — Basic details
// ---------------------------------------------------------------------------

function BasicTab({ c }: { c: CustomerDetail }) {
  const p = c.profile;
  const currentLoan = c.loans.find((l) => ["ACTIVE", "OVERDUE", "DISBURSED", "IN_COLLECTIONS", "DEFAULTED"].includes(l.status)) ?? c.loans[0] ?? null;
  const latestApp = c.applications[0] ?? null;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Section title="Identity & profile">
        <KV k="Full name" v={p?.fullName} />
        <KV k="PAN" v={p?.pan} mono />
        <KV k="Mobile" v={p?.mobile} mono />
        <KV k="Email" v={p?.email} />
        <KV k="Date of birth" v={p?.dob} />
        <KV k="Address" v={p?.address} />
        <KV k="Employer" v={p?.employer} />
        <KV k="Employment" v={p?.employmentStatus} />
        <KV k="Salary bank" v={p?.salaryBank} />
      </Section>

      <Section title="Verification & credit">
        {/* verification block */}
        <KV k="PAN verified" v={<Bool on={p?.panVerified} />} />
        <KV k="Aadhaar (DigiLocker)" v={<Bool on={p?.aadhaarVerified} />} />
        <KV k="Aadhaar linked" v={<Bool on={p?.aadhaarLinked} />} />
        <KV k="Email verified" v={<Bool on={p?.emailVerified} />} />
        <KV k="Address verified" v={<Bool on={p?.addressVerified} />} />
        <KV k="Penny drop" v={<Bool on={p?.pennyDropVerified} />} />
        <KV k="Identity match" v={p?.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null} />
        {/* credit block */}
        <KV k="CIBIL score" v={p?.creditScore != null ? String(p.creditScore) : null} mono />
        <KV k="Star rating" v={p?.starRating != null ? `${p.starRating.toFixed(1)}★` : null} />
        <KV k="Recommendation" v={p?.recommendation} />
        <KV k="Risk category" v={p?.riskCategory} />
        <KV k="Bureau" v={p?.bureauSource} />
        <KV k="Credit brief summary" v={p?.creditBriefSummary} />
        <KV k="Credit brief generated" v={p?.creditBriefGeneratedAt ? formatDateTime(p.creditBriefGeneratedAt) : null} />
      </Section>

      <Section title="Salary & eligibility">
        <KV k="Monthly salary" v={p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
        <KV k="Annual salary" v={p?.annualSalaryPaise != null ? paiseToINR(p.annualSalaryPaise) : null} />
        <KV k="Salary %" v={p?.salaryPercentage != null ? `${p.salaryPercentage}%` : null} />
        <KV k="Increment %" v={p?.incrementPercentage != null ? `${p.incrementPercentage}%` : null} />
        <KV k="Eligible limit" v={latestApp?.eligibleLimitPaise != null ? paiseToINR(latestApp.eligibleLimitPaise) : null} />
      </Section>

      <Section title="Emergency contact">
        <KV k="Name" v={p?.emergencyContactName} />
        <KV k="Phone" v={p?.emergencyContactPhone} mono />
        <KV k="Relation" v={p?.emergencyContactRelation} />
      </Section>

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

// ---------------------------------------------------------------------------
// Tab 2 — Past details (applications, loans, payments)
// ---------------------------------------------------------------------------

function PastTab({ c }: { c: CustomerDetail }) {
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
                <span className="font-mono text-muted">
                  {a.amountRequestedPaise != null ? paiseToINR(a.amountRequestedPaise) : "—"}
                </span>
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
                  <span className="font-semibold text-navy">Loan #{l.id} · {paiseToINR(l.principalPaise)}</span>
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 text-xs font-semibold text-navy">{l.status}</span>
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
                <span className="rounded-full bg-grey-100 px-2 py-0.5 text-xs font-semibold text-muted">{pm.status}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab 4 — Activity timeline
// ---------------------------------------------------------------------------

const TYPE_STYLE: Record<string, string> = {
  LIFECYCLE: "bg-navy-tint text-navy",
  PROFILE: "bg-gold-50 text-gold-dark",
  REVERIFY: "bg-warning-100 text-warning-800",
  REMARK: "bg-grey-100 text-muted",
};

function LogsTab({ customerId }: { customerId: number }) {
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
          <span className={`mt-0.5 h-fit rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${TYPE_STYLE[e.type] ?? "bg-grey-100 text-muted"}`}>
            {e.type}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="font-semibold text-ink">
                {e.title}
                {e.applicationId != null ? <span className="font-normal text-muted"> · app #{e.applicationId}</span> : null}
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

// ---------------------------------------------------------------------------
// Tab 6 — More options
// ---------------------------------------------------------------------------

function MoreTab({ c }: { c: CustomerDetail }) {
  const totalOutstanding = c.loans.reduce((s, l) => s + l.outstandingPaise, 0);
  const latestStatus = c.applications[0]?.status;
  return (
    <div className="space-y-4">
      <Section title="Summary">
        <KV k="Customer id" v={String(c.customerId)} mono />
        <KV k="Applications" v={String(c.applications.length)} />
        <KV k="Loans" v={String(c.loans.length)} />
        <KV k="Payments" v={String(c.payments.length)} />
        <KV k="Total outstanding" v={paiseToINR(totalOutstanding)} mono />
        <KV k="Latest status" v={latestStatus ? statusLabel(latestStatus) : null} />
      </Section>
      <Section title="Admin tools">
        <p className="text-sm text-muted">
          Salary/KYC correction, blocklist and lifecycle actions for this customer are available on the
          full customer page.
        </p>
        <a
          href={`/staff/customers/${c.customerId}`}
          className="mt-2 inline-flex items-center gap-1 text-sm font-semibold text-navy hover:underline"
        >
          Open full customer page <ExternalLink size={13} />
        </a>
      </Section>
    </div>
  );
}

