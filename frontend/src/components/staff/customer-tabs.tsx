"use client";

/**
 * Shared customer CRM tabs — used by both the list dialog and the full detail page so they
 * cannot drift apart (same precedent as detail-parts.tsx).
 */

import * as React from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { Loader2, XCircle } from "lucide-react";
import { EmptyState, Skeleton, StatusBadge, toast } from "@/components/ui";
import {
  Banknote, Building2, CalendarClock, Copy, FileSignature, Files, Gauge, HandCoins, History, IndianRupee,
  Landmark, MapPin, MessageSquare, Plug, Repeat, Route, ShieldCheck, User, Users,
} from "lucide-react";
import { type PillTabDef } from "@/components/ui/pill-tabs";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { CustomerTab } from "@/components/staff/customer-360/customer-tab";
import { MandateTab } from "@/components/staff/customer-360/mandate-tab";
import { ReferencesTab } from "@/components/staff/customer-360/references-tab";
import { LoanTab } from "@/components/staff/customer-360/loan-tab";
import { RepaymentTab } from "@/components/staff/customer-360/repayment-tab";
import { CollectionsTab } from "@/components/staff/customer-360/collections-tab";
import { SanctionTab } from "@/components/staff/customer-360/sanction-tab";
import { DisbursalTab } from "@/components/staff/customer-360/disbursal-tab";
import { DocumentsTab as DocumentsCardsTab } from "@/components/staff/customer-360/documents-tab";
import { BankingTab } from "@/components/staff/customer-360/banking-tab";
import { JourneyTab } from "@/components/staff/customer-360/journey-tab";
import { AddressesTab } from "@/components/staff/customer-360/addresses-tab";
import { CommunicationTab } from "@/components/staff/customer-360/communication-tab";
import { DedupeTab } from "@/components/staff/customer-360/dedupe-tab";
import { FollowupsTab } from "@/components/staff/customer-360/followups-tab";
import { ThirdPartyTab } from "@/components/staff/customer-360/third-party-tab";
import { formatDate, formatDateTime } from "@/lib/utils";
import { CreditProfileCard } from "@/components/staff/credit-profile-card";
import { formatRupees } from "@/components/staff/credit/tradeline-table";
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import { PermissionGate } from "@/components/staff/live-pipeline";
import {
  NeedsManualReviewBadge,
  Section,
} from "@/components/staff/detail-parts";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import { stageOf, STAGE_LABELS } from "@/lib/domain/journey";
import {
  AUDIT_FILTER_ALL,
  auditTypeChips,
  auditTypeLabel,
  customerExposure,
  filterActivityByType,
  isoDayToLocalDate,
  resolveAuditFilter,
} from "@/lib/customers/customer-360";
import {
  customersApi,
  staffApi,
  paiseToINR,
  statusLabel,
  type CustomerDetail,
  type ApplicationView,
  type ActivityEntry,
  type ApplicationStatus,
} from "@/lib/api/applications";

/** The 19 lifecycle-ordered tabs shared by the pop-up and the full customer page. */
export const CUSTOMER_TABS: PillTabDef[] = [
  { key: "customer", label: "Customer", icon: User },
  { key: "loan", label: "Loan", icon: Landmark },
  { key: "sanction", label: "Sanction", icon: FileSignature },
  { key: "disbursal", label: "Disbursal", icon: Banknote },
  { key: "repayment", label: "Repayment", icon: IndianRupee },
  { key: "collections", label: "Collections", icon: HandCoins },
  { key: "third-party", label: "Third-party logs", icon: Plug },
  { key: "banking", label: "Banking", icon: Building2 },
  { key: "credit", label: "Credit report", icon: Gauge },
  { key: "journey", label: "Journey", icon: Route },
  { key: "references", label: "References", icon: Users },
  { key: "documents", label: "Documents", icon: Files },
  { key: "addresses", label: "Addresses", icon: MapPin },
  { key: "dedupe", label: "Dedupe", icon: Copy },
  { key: "communication", label: "Communication", icon: MessageSquare },
  { key: "activity", label: "Activity", icon: History },
  { key: "followups", label: "Follow-ups", icon: CalendarClock },
  {
    key: "mandate",
    label: "Mandate",
    icon: Repeat,
    disabled: true,
    badge: "Coming soon",
  },
  { key: "verifications", label: "Verifications", icon: ShieldCheck },
];

const CANCELLABLE: Set<ApplicationStatus> = new Set([
  "DRAFT", "KYC_PENDING", "KYC_APPROVED", "PRE_APPROVED", "REVIEW_PENDING",
  "CREDIT_EXEC_PENDING", "CREDIT_EXEC_APPROVED", "CREDIT_HEAD_PENDING", "CREDIT_HEAD_APPROVED",
  "DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING", "DISBURSEMENT_FAILED",
]);

const TYPE_STYLE: Record<string, string> = {
  LIFECYCLE: "bg-navy-tint text-navy",
  PROFILE: "bg-gold-50 text-gold-dark",
  REVERIFY: "bg-warning-100 text-warning-800",
  VERIFICATION: "bg-info-50 text-info-700",
  REFERENCE: "bg-neutral-100 text-neutral-700",
  UPLOAD: "bg-grey-200 text-navy",
  REMARK: "bg-grey-100 text-muted",
  CALL: "bg-success-50 text-success-700",
};

export function CustomerTabBody({
  tab,
  detail,
  customerId,
  applicationId: applicationIdProp,
  app: appProp,
  onChanged = noop,
  onOpenApplication,
  onTabChange,
}: { tab: string } & Partial<TabCtx> & Pick<TabCtx, "detail" | "customerId">) {
  // An explicit `null` (lead-only customer) is honoured; only an omitted prop falls back to the newest file.
  const applicationId = applicationIdProp !== undefined ? applicationIdProp : (detail.applications[0]?.id ?? null);
  const app = appProp !== undefined ? appProp : (detail.applications[0] ?? null);
  const ctx: TabCtx = { detail, customerId, applicationId, app, onChanged, onOpenApplication, onTabChange };
  const noApp = <EmptyState title="No application to show yet." />;

  const content = (() => {
    switch (tab) {
      case "customer":
        return <CustomerTab {...ctx} />;
      case "loan":
        return (
          <LoanTab
            {...ctx}
            listing={
              <ApplicationsList
                c={detail}
                onChanged={onChanged}
                onOpenApplication={onOpenApplication}
                currentApplicationId={applicationId}
              />
            }
          />
        );
      case "sanction":
        return <SanctionTab {...ctx} />;
      case "disbursal":
        return <DisbursalTab {...ctx} />;
      case "repayment":
        return <RepaymentTab {...ctx} />;
      case "collections":
        return <CollectionsTab {...ctx} />;
      case "banking":
        return <BankingTab {...ctx} />;
      case "credit":
        return <CreditTab c={detail} latestAppId={applicationId} />;
      case "journey":
        return <JourneyTab app={app} applicationId={applicationId} />;
      case "references":
        return <ReferencesTab applicationId={applicationId} />;
      case "documents":
        // Grouped mode: every application this customer ever filed, not just the newest — a
        // reborrow's prior-application uploads must stay reachable (item 4).
        return <DocumentsCardsTab customerId={customerId} />;
      case "communication":
        return <CommunicationTab customerId={customerId} loans={detail.loans} />;
      case "activity":
        return <AuditLogsTab customerId={customerId} apps={detail.applications} />;
      case "mandate":
        return <MandateTab />;
      case "verifications":
        // Every check on the file, penny drop included, with the same per-check detail and manual
        // override the application dialog offers.
        return applicationId != null ? <VerificationChecksPanel applicationId={applicationId} /> : noApp;
      case "third-party":
        return <ThirdPartyTab {...ctx} />;
      case "addresses":
        return <AddressesTab {...ctx} />;
      case "dedupe":
        return <DedupeTab {...ctx} />;
      case "followups":
        return <FollowupsTab {...ctx} />;
      default:
        return null;
    }
  })();

  // The two callers of this body (the customer list dialog and the full customer page) each build
  // their own bespoke header around <PillTabs>/<CustomerTabBody> — there's no shared header component
  // to hang a badge off. Rendering it here instead, ahead of the per-tab content above, means it
  // stays visible across every tab both callers offer without duplicating it in each caller.
  return (
    <>
      <NeedsManualReviewBadge customerId={customerId} className="mb-3" />
      {content}
    </>
  );
}

const noop = () => {};

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

// ---------------------------------------------------------------------------
// Credit
// ---------------------------------------------------------------------------

function CreditTab({ c, latestAppId }: { c: CustomerDetail; latestAppId: number | null }) {
  const p = c.profile;
  const bureauQ = useQuery({
    queryKey: ["verifications", latestAppId],
    queryFn: () => staffApi.verifications(latestAppId as number),
    enabled: latestAppId != null,
  });
  const bureau = (bureauQ.data ?? []).find((s) => s.checkType === "BUREAU");
  const bd = (bureau?.derived ?? {}) as Record<string, unknown>;
  const totalBalance = typeof bd.totalBalance === "number" ? bd.totalBalance : null;

  return (
    <div className="space-y-4">
      <div className="rounded border border-line bg-white p-5 shadow-sm">
        <CreditScoreGauge
          score={p?.creditScore ?? c.creditBrief?.creditScore ?? null}
          starRating={p?.starRating ?? c.creditBrief?.starRating ?? null}
          recommendation={p?.recommendation ?? c.creditBrief?.recommendation ?? null}
          state={c.creditBrief?.bureauState ?? "NOT_FETCHED"}
          size="md"
        />
      </div>
      {/* CreditProfileCard already carries the score/star/recommendation headline, the A/B/C facts
          (incl. total/secured/unsecured balance), the tradeline + enquiry tables and the raw provider
          dump — so the two tables that used to sit below it here just repeated those same numbers a
          second and third time. What's left below is only what neither the gauge nor the card show:
          risk category (a staff-only underwriting field, not part of the bureau brief), which bureau
          answered, when the brief was generated, and the raw pre-brief verification-row diagnostics
          (a different, earlier snapshot than the parsed `facts` — useful when the two disagree). */}
      {latestAppId != null && <CreditProfileCard applicationId={latestAppId} />}
      <Section title="Underwriting & brief metadata">
        <StaffFieldTable rows={[
          ["Risk category", p?.riskCategory, "Customer profile"],
          ["Bureau", p?.bureauSource, "Customer profile"],
          ["Credit brief generated", p?.creditBriefGeneratedAt ? formatDateTime(p.creditBriefGeneratedAt) : null, "Credit brief"],
        ]} />
      </Section>
      {latestAppId != null && (
        <Section title="Bureau pull (raw verification diagnostics)">
          <StaffFieldTable rows={[
            ["Source", str(bd.source), "Bureau provider"],
            ["No record", str(bd.noRecord), "Bureau provider"],
            ["Active accounts", str(bd.activeAccounts), "Bureau provider"],
            ["Overdue / defaults", str(bd.overdueAccounts), "Bureau provider"],
            ["Total balance", formatRupees(totalBalance), "Bureau provider"],
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
        <thead><tr><th>S.No.</th><th>Field</th><th>Value</th><th>Source</th></tr></thead>
        <tbody>
          {rows.map(([field, value, source], i) => (
            <tr key={field}>
              <td className="text-muted">{i + 1}</td>
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

// ---------------------------------------------------------------------------
// Loan applications
// ---------------------------------------------------------------------------

function ApplicationsList({
  c,
  onChanged,
  onOpenApplication,
  currentApplicationId,
}: {
  c: CustomerDetail;
  onChanged?: () => void;
  onOpenApplication?: (applicationId: number) => void;
  /** The application already on screen, if any — it is never made clickable. */
  currentApplicationId?: number | null;
}) {
  const exposure = customerExposure(c);
  const lastPaidOn = isoDayToLocalDate(exposure.lastVerifiedPaymentOn);

  return (
    <div className="space-y-4">
      {c.loans.length > 0 && (
        <p className="flex flex-wrap items-baseline gap-x-1.5 gap-y-0.5 text-xs text-muted">
          <span>
            Total principal{" "}
            <span className="font-mono text-ink">{paiseToINR(exposure.totalPrincipalPaise)}</span>
          </span>
          {exposure.totalOutstandingPaise != null && (
            <span>
              · Outstanding{" "}
              <span className="font-mono text-ink">{paiseToINR(exposure.totalOutstandingPaise)}</span>
            </span>
          )}
          {lastPaidOn && (
            <span>
              · Last verified payment <span className="text-ink">{formatDate(lastPaidOn)}</span>
            </span>
          )}
        </p>
      )}
      <Section title={`Applications (${c.applications.length})`}>
        {c.applications.length === 0 ? (
          <EmptyState title="None." className="py-4" />
        ) : (
          <ul className="divide-y divide-line">
            {c.applications.map((a: ApplicationView) => (
              <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  {/* Clickable only when a caller can host the dialog, and never for the file already
                      open — opening a nested copy of the application you are looking at is pointless. */}
                  {onOpenApplication && a.id !== currentApplicationId ? (
                    <button
                      type="button"
                      onClick={() => onOpenApplication(a.id)}
                      className="font-semibold text-navy hover:underline"
                      title={`Open application #${a.id}`}
                    >
                      #{a.id}
                    </button>
                  ) : (
                    <>#{a.id}</>
                  )}{" "}
                  · {statusLabel(a.status)}
                  {a.purpose ? <span className="text-muted"> · {a.purpose}</span> : null}
                  {/* Real assignee only — no implied Credit Head fallback outside the journey view. */}
                  <span className="block text-xs text-muted">
                    Assigned to {a.assignedExecutiveName ?? "—"}
                    {a.currentStageEnteredAt ? ` · in stage since ${formatDateTime(a.currentStageEnteredAt)}` : ""}
                  </span>
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

    </div>
  );
}

function CancelButton({ appId, onDone }: { appId: number; onDone?: () => void }) {
  const m = useMutation({
    mutationFn: () => staffApi.cancel(appId, "Cancelled by admin from customer page"),
    onSuccess: () => {
      onDone?.();
      toast.success("Application cancelled");
    },
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
// Audit logs
// ---------------------------------------------------------------------------

interface ActivityGroup {
  app: ApplicationView | null;
  entries: ActivityEntry[];
}

/**
 * Group the flat activity feed by loan application — one group per entry in `apps` (its existing
 * newest-first order preserved), plus a trailing group for entries with no `applicationId`
 * (remarks / calls / unattached profile edits). `items` already arrives newest-first from the
 * backend, and filtering preserves that order within each group.
 */
function groupActivity(items: ActivityEntry[], apps: ApplicationView[]): ActivityGroup[] {
  const groups: ActivityGroup[] = apps.map((app) => ({
    app,
    entries: items.filter((e) => e.applicationId === app.id),
  }));
  const unattached = items.filter((e) => e.applicationId == null);
  if (unattached.length > 0) {
    groups.push({ app: null, entries: unattached });
  }
  return groups;
}

function AuditLogsTab({ customerId, apps }: { customerId: number; apps: ApplicationView[] }) {
  const q = useQuery({
    queryKey: ["customer-activity", customerId],
    queryFn: () => customersApi.activity(customerId),
  });
  const [selectedType, setSelectedType] = React.useState<string>(AUDIT_FILTER_ALL);
  if (q.isLoading) {
    return (
      <div className="space-y-2">
        <Skeleton variant="row" />
        <Skeleton variant="row" />
        <Skeleton variant="row" />
      </div>
    );
  }
  const items = q.data ?? [];
  if (items.length === 0) return <EmptyState title="No activity recorded yet." />;
  // Chips come from the types actually in the loaded feed; filtering is client-side only.
  const chips = auditTypeChips(items);
  const activeType = resolveAuditFilter(chips, selectedType);
  const groups = groupActivity(filterActivityByType(items, activeType), apps);
  const chipClass = (on: boolean) =>
    `rounded-full border px-2 py-0.5 text-[9.6px] font-semibold ${
      on ? "border-navy bg-navy text-white" : "border-line bg-white text-muted hover:bg-grey-100 hover:text-ink"
    }`;
  return (
    <div className="space-y-4">
      {chips.length > 1 && (
        <div role="group" aria-label="Filter activity by type" className="flex flex-wrap gap-1.5">
          <button
            type="button"
            aria-pressed={activeType === AUDIT_FILTER_ALL}
            onClick={() => setSelectedType(AUDIT_FILTER_ALL)}
            className={chipClass(activeType === AUDIT_FILTER_ALL)}
          >
            All ({items.length})
          </button>
          {chips.map((chip) => (
            <button
              key={chip.type}
              type="button"
              aria-pressed={activeType === chip.type}
              onClick={() => setSelectedType(chip.type)}
              className={chipClass(activeType === chip.type)}
            >
              {chip.label} ({chip.count})
            </button>
          ))}
        </div>
      )}
      {groups.map((group) => (
        <Section
          key={group.app?.id ?? "unattached"}
          title={
            group.app ? (
              <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 normal-case tracking-normal">
                <span className="font-semibold text-ink">
                  #{group.app.id}
                  {group.app.amountRequestedPaise != null
                    ? ` · ${paiseToINR(group.app.amountRequestedPaise)}`
                    : ""}
                </span>
                <StatusBadge kind="application" value={group.app.status}>
                  {statusLabel(group.app.status)}
                </StatusBadge>
                {group.app.status !== "REJECTED" && group.app.status !== "CANCELLED" ? (
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 text-[10px] font-semibold text-navy">
                    {STAGE_LABELS[stageOf(group.app.status).stage]}
                  </span>
                ) : null}
                <span className="text-[10px] font-normal normal-case text-muted">
                  Assigned to {group.app.assignedExecutiveName ?? "—"}
                  {group.app.currentStageEnteredAt
                    ? ` · in stage since ${formatDateTime(group.app.currentStageEnteredAt)}`
                    : ""}
                </span>
              </span>
            ) : (
              "Not tied to an application"
            )
          }
        >
          {group.entries.length === 0 ? (
            <EmptyState
              title={
                activeType === AUDIT_FILTER_ALL
                  ? "No activity recorded for this application yet."
                  : `No ${auditTypeLabel(activeType).toLowerCase()} events for this application.`
              }
              className="py-4"
            />
          ) : (
            <ul className="space-y-2">
              {group.entries.map((e: ActivityEntry, i) => (
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
                        {group.app == null && e.applicationId != null ? (
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
          )}
        </Section>
      ))}
    </div>
  );
}
