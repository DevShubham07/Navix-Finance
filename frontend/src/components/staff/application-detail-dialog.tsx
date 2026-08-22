"use client";

/**
 * The single staff detail popup — every tab about a file and the person behind it, plus every
 * action the signed-in role can take on it. Replaces the old `/staff/credit/{id}` page and folds in
 * what used to be a second, separate customer dialog: a staffer opening a queue row should never
 * have to work out which of two modals holds the field they need.
 *
 * It carries the **union** of both tab sets:
 *   - application: Overview · Journey · Verifications · Documents · Past details · Audit log
 *   - customer (`c:` prefixed, from {@link CUSTOMER_TABS}): Personal · Employment · Bank ·
 *     Credit report · Loan applications · Calls & remarks · Customer activity
 * The customer half loads lazily — nothing fetches `customersApi.get` until one of those tabs is
 * actually opened, so the common case (glance at Overview, decide, close) costs no extra request.
 *
 * "Continued context": open state is derived from `applicationId != null` and the active-tab state
 * lives above the data, so it survives switching applications when the parent keeps this mounted
 * and swaps the id. The stage's maker-checker action is pinned in the header, so a reviewer never
 * has to leave the queue to act.
 *
 * The **Overview** tab is deliberately role-shaped, not a field dump: a short shared borrower
 * strip, then the one card the signed-in role actually decides on ({@link RoleFocus}) —
 * disbursement + payout gate for the release roles, the penalty-aware amount due for
 * collections, the credit headline for the credit roles, KYC progress for the approver.
 * ADMIN gets every card (oversight). Everything else stays one tab away.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { X, Loader2, Zap, Banknote, ShieldCheck, PhoneCall, Gauge, Check, ExternalLink, Pencil } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Tabs, type TabDef } from "@/components/ui/tabs";
import { InfoTooltip } from "@/components/ui/tooltip";
import { CreditBadge } from "@/components/staff/credit-badge";
import { EventTimeline } from "@/components/staff/event-timeline";
import { JourneyStepper } from "@/components/staff/journey-stepper";
import { JourneyAssignee } from "@/components/staff/journey-assignee";
import { StageDetailDialog } from "@/components/staff/stage-detail-dialog";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import { LoanHistory } from "@/components/staff/pipeline/loan-history";
import { LoanBreakdown, ProjectedCostBreakdown } from "@/components/staff/loan-breakdown";
import { Section, KV, DocumentsTab, NeedsManualReviewBadge } from "@/components/staff/detail-parts";
import { CustomerTabBody } from "@/components/staff/customer-tabs";
import { deriveJourney, type JourneyStage } from "@/lib/domain/journey";
import { hasPermission, type StaffRole } from "@/lib/auth/rbac";
import { dpdBucket, daysBetween } from "@/lib/calc/loan-math";
import { formatDate } from "@/lib/utils";
import { customerPageHref } from "@/lib/customers/customer-page";
import {
  staffApi,
  customersApi,
  statusLabel,
  paiseToINR,
  REFERENCE_RELATIONS,
  type ApplicationView,
  type ProfileView,
  type LoanView,
  type OutstandingView,
  type VerificationProgress,
  type EventView,
  type ReferenceInput,
  type CreditBriefDetail,
} from "@/lib/api/applications";
import { useStaffMe, errMessage, REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import {
  TradelineTable,
  DelinquencySummaryBlock,
  EnquiryVelocityBlock,
} from "@/components/staff/credit/tradeline-table";
import {
  AssignActions,
  CreditDecisionActions,
  DisbursementActions,
  AdminForceDisbursementAction,
  NoAccessNotice,
} from "@/components/staff/live-pipeline";

const todayISO = () => new Date().toISOString().slice(0, 10);

/** DPD bucket labels — same wording as the collections buckets page, so both surfaces agree. */
const DPD_LABEL: Record<string, string> = {
  UPCOMING: "Upcoming",
  T0_T7: "1–7 DPD",
  T8_T30: "8–30 DPD",
  T30_T60: "31–60 DPD",
  T60_T90: "61–90 DPD",
  T90_PLUS: "90+ DPD",
};

/** Which focus card(s) a role sees first on the Overview tab. */
const ROLE_FOCUS: Record<StaffRole, Array<"disbursement" | "collections" | "credit" | "kyc">> = {
  DISBURSEMENT_HEAD: ["disbursement"],
  ACCOUNTANT: ["disbursement", "collections"],
  COLLECTION_HEAD: ["collections"],
  COLLECTION_EXECUTIVE: ["collections"],
  // The credit roles absorbed KYC (V45), so they see both cards.
  CREDIT_HEAD: ["kyc", "credit"],
  CREDIT_EXECUTIVE: ["kyc", "credit"],
  // Telecaller has no lifecycle stage — no focus card.
  TELECALLER: [],
  // DSA has no lifecycle stage and never opens this dialog — no focus card.
  DSA: [],
  // Oversight roles see everything.
  ADMIN: ["kyc", "credit", "disbursement", "collections"],
};

/** The maker-checker action available for an application's current pipeline stage. */
function actionFor(app: ApplicationView): React.ReactNode {
  switch (app.status) {
    case "KYC_PENDING":
    case "KYC_APPROVED":
      // V45: a submitted intake is assigned by the Credit Head, not KYC-approved first.
      return <AssignActions app={app} />;
    case "CREDIT_EXEC_PENDING":
      return <CreditDecisionActions app={app} />;
    case "SANCTIONED":
      // ADMIN-only escape hatch — normally this stage waits on the borrower's own offer
      // journey (accept-offer), so every other role sees "Not your step" here.
      return <AdminForceDisbursementAction app={app} />;
    case "DISBURSEMENT_PENDING":
    case "DISBURSEMENT_FAILED":
      return <DisbursementActions app={app} />;
    // ACCOUNTANT_PENDING deliberately has no action: the hop was retired in V48 and V48's migration
    // moved every live row out of it. A historical row opens read-only.
    default:
      return null;
  }
}

/**
 * The customer half of the union, `c:`-prefixed so its keys can't collide with the application
 * tabs (both sets have a "documents" and an "audit"). The bodies come from `customer-tabs.tsx`,
 * shared with the full `/staff/customers/{id}` page so the two can't drift.
 *
 * `documents` is deliberately absent: the customer tab of that name just renders the *latest
 * application's* documents, which is exactly what this dialog's own Documents tab already shows.
 */
const CUSTOMER_TAB_DEFS: TabDef[] = [
  { key: "c:personal", label: "Personal" },
  { key: "c:employment", label: "Employment & salary" },
  { key: "c:bank", label: "Bank" },
  { key: "c:credit", label: "Credit report" },
  { key: "c:loans", label: "Loan applications" },
  { key: "c:calls", label: "Calls & remarks" },
  { key: "c:audit", label: "Customer activity" },
];

export interface ApplicationDetailDialogProps {
  applicationId: number | null;
  onClose: () => void;
}

export function ApplicationDetailDialog({ applicationId, onClose }: ApplicationDetailDialogProps) {
  const open = applicationId != null;
  const id = applicationId ?? 0;
  const [tab, setTab] = React.useState("basic");
  const [openStage, setOpenStage] = React.useState<JourneyStage | null>(null);
  /** Another application of the same customer, stacked on top of this one (see the render below). */
  const [nestedAppId, setNestedAppId] = React.useState<number | null>(null);

  // Close any open step popup whenever the dialog itself closes (mirrors ApplicationJourney).
  React.useEffect(() => {
    if (!open) setOpenStage(null);
  }, [open]);

  // Drop the stacked application when this dialog closes OR retargets: the parent keeps this
  // component mounted and swaps `applicationId`, so without this a stale nested dialog would
  // reappear over an unrelated file.
  React.useEffect(() => {
    setNestedAppId(null);
  }, [open, id]);

  const role = useStaffMe().data?.role;
  const canReview = role != null && REVIEW_PERMS.some((p) => hasPermission(role, p));

  const appQ = useQuery({
    queryKey: ["staff-application", id],
    queryFn: () => staffApi.get(id),
    enabled: open,
    refetchInterval: open ? 8000 : false,
  });
  const eventsQ = useQuery({
    queryKey: ["staff-events", id],
    queryFn: () => staffApi.events(id),
    enabled: open,
  });
  // get(id) is borrower-safe (no credit fields); the staff-only headline comes from the brief endpoint.
  const briefQ = useQuery({
    queryKey: ["credit-brief", id],
    queryFn: () => staffApi.creditBrief(id),
    enabled: open && appQ.data != null && appQ.data.status !== "DRAFT",
  });
  // Backs the header identity (name/mobile/PAN/risk) and the Basic details tab.
  const profileQ = useQuery({
    queryKey: ["staff-profile", id],
    queryFn: () => staffApi.getProfile(id),
    enabled: open && canReview,
    retry: false,
  });

  const app = appQ.data;
  // The customer half of the union. Lazy on purpose: a staffer who only glances at Overview and
  // decides shouldn't pay for the whole customer roll-up. Once fetched it stays cached, so
  // flipping between customer tabs costs nothing.
  const onCustomerTab = tab.startsWith("c:");
  const customerId = app?.customerId ?? null;
  const customerQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open && onCustomerTab && customerId != null,
  });
  const p = profileQ.data;
  const events = eventsQ.data ?? [];
  const journey = app ? deriveJourney(app, events) : null;
  const activeIndex = journey
    ? journey.stages.reduce((acc, s, i) => (s.state !== "upcoming" ? i : acc), 0)
    : 0;
  const action = app ? actionFor(app) : null;

  const displayName = p?.fullName ?? app?.customerName ?? (app ? `Customer #${app.customerId}` : "Application");
  const displayMobile = p?.mobile ?? app?.customerMobile ?? null;

  const tabs: TabDef[] = [
    { key: "basic", label: "Overview" },
    { key: "journey", label: "Journey" },
    { key: "verifications", label: "Verifications" },
    { key: "documents", label: "Documents" },
    { key: "past", label: "Past details" },
    { key: "audit", label: "Audit log", badge: events.length || undefined },
    ...CUSTOMER_TAB_DEFS,
  ];

  return (
    <>
      {/* !max-w / !w: globals.css's un-layered `.modal { max-width: 460px }` outranks plain
          utilities in the cascade (mirrors customer-detail-dialog.tsx). 80vw since V45 — the
          credit decision is now made off this one surface, so it carries the whole file. Type
          size is deliberately unchanged; emphasis comes from the KV weights, not scaling. */}
      <Dialog open={open} onClose={onClose} className="!max-w-[80vw] !w-[80vw]">
        <div className="border-b border-line pb-3">
          <div className="flex items-start gap-3">
            <div className="min-w-0 flex-1">
              <h3 className="font-serif text-lg text-navy">
                {displayName} <span className="text-sm font-normal text-muted">— Application #{id}</span>
              </h3>
              <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
                <span>#{app?.customerId ?? "—"}</span>
                {displayMobile && <span>· {displayMobile}</span>}
                {p?.pan && <span>· PAN {p.pan}</span>}
                {p?.riskCategory && <span>· risk {p.riskCategory}</span>}
                {app && (
                  <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">
                    {statusLabel(app.status)}
                  </span>
                )}
                {briefQ.data?.available && (
                  <CreditBadge
                    starRating={briefQ.data.starRating}
                    creditScore={briefQ.data.creditScore}
                    recommendation={briefQ.data.recommendation}
                  />
                )}
                {app?.customerId != null && <NeedsManualReviewBadge customerId={app.customerId} />}
              </div>
            </div>
            {app?.customerId != null && (
              <Link
                href={customerPageHref(app.customerId)}
                className="inline-flex shrink-0 items-center gap-1 rounded border border-line px-2 py-1 text-xs font-semibold text-navy hover:bg-grey-100"
              >
                Full customer page <ExternalLink size={13} />
              </Link>
            )}
            {/* Close stays pinned to the top-right corner regardless of how tall the action
                cluster below grows — it used to sit inline with the actions, which floated it to
                the vertical middle of the dialog once the assignment picker appeared. */}
            <button
              onClick={onClose}
              className="-mt-1 flex-shrink-0 rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
              aria-label="Close"
            >
              <X size={18} />
            </button>
          </div>
          {/* The stage's maker-checker cluster gets its own full-width row: it can carry a select,
              a text field and three buttons, which has no business competing with the title. */}
          {action && <div className="mt-3 flex flex-wrap items-end gap-2">{action}</div>}
        </div>

        <Tabs tabs={tabs} active={tab} onChange={setTab} className="mt-2" />

        <div className="mt-3 max-h-[80vh] overflow-y-auto pr-1 text-[10.4px]">
          {appQ.isLoading ? (
            <p className="flex items-center gap-2 py-8 text-sm text-muted">
              <Loader2 size={15} className="animate-spin" /> Loading…
            </p>
          ) : appQ.error ? (
            <p className="py-8 text-sm text-error-700">{errMessage(appQ.error)}</p>
          ) : !app ? (
            <p className="py-8 text-sm text-muted">Application #{id} not found.</p>
          ) : (
            <>
              {tab === "basic" &&
                (canReview ? (
                  <OverviewTab
                    app={app}
                    p={p}
                    role={role}
                    loading={profileQ.isLoading}
                    events={events}
                    creditDetail={briefQ.data?.facts?.detail ?? null}
                  />
                ) : (
                  <NoAccessNotice message="Customer details (incl. PII) aren't available to your role." />
                ))}

              {tab === "journey" && (
                <JourneyTab app={app} journey={journey} activeIndex={activeIndex} onStageClick={setOpenStage} />
              )}

              {tab === "verifications" &&
                (canReview ? (
                  <VerificationChecksPanel applicationId={id} />
                ) : (
                  <NoAccessNotice message="Verification detail isn't available to your role." />
                ))}

              {tab === "documents" &&
                (canReview ? (
                  // Grouped mode: documents from every application this customer ever filed, not
                  // just this one — a reborrow's prior-application uploads stay reachable (item 4).
                  <DocumentsTab customerId={app.customerId} />
                ) : (
                  <NoAccessNotice message="Documents aren't available to your role." />
                ))}

              {tab === "past" && <PastDetailsTab app={app} onOpen={setNestedAppId} />}

              {tab === "audit" && (
                events.length === 0 ? (
                  <p className="py-6 text-sm text-muted">No events recorded yet.</p>
                ) : (
                  <EventTimeline events={events} />
                )
              )}

              {onCustomerTab &&
                (!canReview ? (
                  <NoAccessNotice message="Customer details (incl. PII) aren't available to your role." />
                ) : customerQ.isLoading ? (
                  <p className="flex items-center gap-2 py-8 text-sm text-muted">
                    <Loader2 size={15} className="animate-spin" /> Loading…
                  </p>
                ) : customerQ.error ? (
                  <p className="py-8 text-sm text-error-700">{errMessage(customerQ.error)}</p>
                ) : customerQ.data ? (
                  <CustomerTabBody
                    tab={tab.slice(2)}
                    detail={customerQ.data}
                    customerId={app.customerId}
                    applicationId={id}
                    onChanged={() => customerQ.refetch()}
                    onOpenApplication={setNestedAppId}
                  />
                ) : null)}
            </>
          )}
        </div>
      </Dialog>

      {openStage && app && (
        <StageDetailDialog
          applicationId={id}
          app={app}
          stage={openStage}
          stages={journey?.stages ?? []}
          allEvents={events}
          open
          onClose={() => setOpenStage(null)}
          onNavigate={setOpenStage}
        />
      )}

      {/* Another of this customer's applications, opened from Past details or the Loan applications
          tab. Stacked rather than swapped so closing it returns you here, on the tab you left — and
          a reborrow's earlier Journey is otherwise unreachable, since queues are status-filtered.
          Placed outside <Dialog> in the fragment, the same structure StageDetailDialog above uses:
          Dialog portals to document.body, so the inner one lands later in DOM order and paints on
          top. Self-recursive reference, no import — this module is the one that defines it. */}
      {nestedAppId != null && (
        <ApplicationDetailDialog
          applicationId={nestedAppId}
          onClose={() => setNestedAppId(null)}
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Overview — a short borrower strip + the card THIS role decides on
// ---------------------------------------------------------------------------

function OverviewTab({
  app,
  p,
  role,
  loading,
  events,
  creditDetail,
}: {
  app: ApplicationView;
  p: ProfileView | undefined;
  role: StaffRole | undefined;
  loading: boolean;
  events: EventView[];
  creditDetail: CreditBriefDetail | null;
}) {
  if (loading && !p) {
    return <p className="py-6 text-sm text-muted">Loading…</p>;
  }
  // Unknown/loading role: fall back to the credit headline rather than an empty tab.
  const focus = (role && ROLE_FOCUS[role]) ?? ["credit"];

  return (
    <div className="space-y-4">
      <BorrowerStrip app={app} p={p} />
      <WaitTime app={app} events={events} />
      {focus.includes("kyc") && <KycFocus applicationId={app.id} p={p} />}
      {focus.includes("credit") && <CreditFocus app={app} p={p} detail={creditDetail} />}
      {focus.includes("disbursement") && <DisbursementFocus app={app} p={p} />}
      {focus.includes("collections") && <CollectionsFocus app={app} />}
      <ReferencesFocus applicationId={app.id} />
    </div>
  );
}

/**
 * How long this borrower has been waiting — total since they submitted, and how long the file has
 * sat at its current stage. Read off the event trail rather than stored, so it can never drift.
 *
 * The "pending" tag lives here too: it explains a stalled clock, so it belongs beside it rather
 * than buried in Remarks.
 */
function WaitTime({ app, events }: { app: ApplicationView; events: EventView[] }) {
  const submitted = events.find((e) => e.action === "SUBMIT_KYC") ?? events[0];
  const stageEntered = [...events].reverse().find((e) => e.toStatus === app.status);
  const since = (iso: string | undefined) => {
    if (!iso) return null;
    const hours = (Date.now() - new Date(iso).getTime()) / 3_600_000;
    if (hours < 1) return "under an hour";
    if (hours < 48) return `${Math.round(hours)} hours`;
    return `${Math.round(hours / 24)} days`;
  };

  return (
    <div className="grid gap-x-6 gap-y-1 rounded border border-line p-4 sm:grid-cols-3">
      <KV k="Waiting since submission" v={since(submitted?.at)} />
      <KV k="At this stage for" v={since(stageEntered?.at)} />
      <KV
        k="Marked pending"
        v={app.markedPendingAt ? app.pendingReason || "yes" : null}
      />
    </div>
  );
}

/**
 * The facts every role needs to know who they're looking at, at ANY stage.
 *
 * Deliberately excludes the requested amount: the borrower can only `apply` once the application
 * is KYC_APPROVED (or PRE_APPROVED) — `ApplicationFlowService.apply` throws `NOT_APPLICABLE`
 * otherwise — so `amountRequestedPaise` is null throughout KYC and would render a permanent "—"
 * that reads as "the borrower asked for nothing". The ask belongs to the credit card
 * ({@link CreditFocus}), which is where the credit roles decide on it. The eligible limit IS set
 * earlier (at salary verification), so it is safe to show at every stage.
 */
function BorrowerStrip({ app, p }: { app: ApplicationView; p: ProfileView | undefined }) {
  return (
    <div className="grid gap-x-6 gap-y-1 rounded border border-line bg-grey-50 p-4 sm:grid-cols-2 lg:grid-cols-3">
      <KV k="Name" v={p?.fullName ?? app.customerName} />
      <KV k="Mobile" v={p?.mobile ?? app.customerMobile} mono />
      <KV k="PAN" v={p?.pan} mono />
      <KV k="Employer" v={p?.employer} />
      <KV k="Monthly salary" v={p?.monthlySalaryPaise != null ? paiseToINR(p.monthlySalaryPaise) : null} />
      <KV k="Eligible limit" v={app.eligibleLimitPaise != null ? paiseToINR(app.eligibleLimitPaise) : null} />
    </div>
  );
}

/** Headline figure + caption, for the one number a role is really here for. */
function Headline({ label, value, tone = "navy", caption }: {
  label: string;
  value: string;
  tone?: "navy" | "error";
  caption?: React.ReactNode;
}) {
  return (
    <div className="mb-3 border-b border-line pb-3">
      <div className="text-xs font-semibold uppercase tracking-wide text-muted">{label}</div>
      <div className={`font-serif text-3xl font-bold ${tone === "error" ? "text-error-700" : "text-navy"}`}>
        {value}
      </div>
      {caption && <div className="mt-0.5 text-xs text-muted">{caption}</div>}
    </div>
  );
}

function FocusCard({ icon: Icon, title, children, headerRight }: {
  icon: typeof Banknote;
  title: string;
  children: React.ReactNode;
  /** Optional right-aligned action rendered beside the title (e.g. an ADMIN "Edit" button). */
  headerRight?: React.ReactNode;
}) {
  return (
    <div className="rounded border border-line bg-white p-4">
      <h4 className="mb-3 flex items-center justify-between gap-2 font-serif text-sm font-semibold text-navy">
        <span className="flex items-center gap-2">
          <Icon size={15} aria-hidden /> {title}
        </span>
        {headerRight}
      </h4>
      {children}
    </div>
  );
}

// ---- References (every role) -----------------------------------------------

const RELATION_LABEL: Record<string, string> = {
  PARENT: "Parent", SPOUSE: "Spouse", SIBLING: "Sibling", RELATIVE: "Relative",
  FRIEND: "Friend", COLLEAGUE: "Colleague", MANAGER: "Manager", NEIGHBOUR: "Neighbour",
};

/**
 * The two contacts the borrower named in the offer journey (V46). Shown to every role rather than
 * scoped to collections: credit reads them as part of the file, and collections is simply the role
 * that eventually calls them. Renders nothing before the borrower reaches that screen.
 *
 * ADMIN additionally gets an inline "Edit" affordance — the backend's `offer/references` endpoint
 * already permits ADMIN (not just the owning borrower), so a staffer can correct a mistyped name/
 * mobile/relation without the borrower having to redo the screen.
 */
function ReferencesFocus({ applicationId }: { applicationId: number }) {
  const role = useStaffMe().data?.role;
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-references", applicationId],
    queryFn: () => staffApi.references(applicationId),
    retry: false,
  });
  const rows = q.data ?? [];
  const [editing, setEditing] = React.useState(false);
  const [drafts, setDrafts] = React.useState<ReferenceInput[]>([]);

  const startEdit = () => {
    setDrafts(rows.map((r) => ({ fullName: r.fullName, mobile: r.mobile, relation: r.relation })));
    setEditing(true);
  };

  const save = useMutation({
    mutationFn: () => staffApi.saveReferences(applicationId, drafts),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-references", applicationId] });
      setEditing(false);
    },
  });

  if (rows.length === 0) return null;

  const canEdit = role === "ADMIN";

  return (
    <FocusCard
      icon={PhoneCall}
      title="References"
      headerRight={
        canEdit && !editing ? (
          <button
            type="button"
            onClick={startEdit}
            className="btn btn-sm btn-outline btn-icon"
            aria-label="Edit references"
            title="Edit references"
          >
            <Pencil size={13} />
          </button>
        ) : undefined
      }
    >
      {editing ? (
        <div className="space-y-3">
          {drafts.map((d, i) => (
            <div key={i} className="grid gap-2 rounded border border-line p-2 sm:grid-cols-3">
              <label className="field !mb-0">
                <span className="text-xs">Full name</span>
                <input
                  type="text"
                  value={d.fullName}
                  onChange={(e) =>
                    setDrafts((prev) => prev.map((x, j) => (j === i ? { ...x, fullName: e.target.value } : x)))
                  }
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                />
              </label>
              <label className="field !mb-0">
                <span className="text-xs">Mobile</span>
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={10}
                  value={d.mobile}
                  onChange={(e) =>
                    setDrafts((prev) => prev.map((x, j) => (j === i ? { ...x, mobile: e.target.value } : x)))
                  }
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                />
              </label>
              <label className="field !mb-0">
                <span className="text-xs">Relation</span>
                <select
                  value={d.relation}
                  onChange={(e) =>
                    setDrafts((prev) => prev.map((x, j) => (j === i ? { ...x, relation: e.target.value } : x)))
                  }
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                >
                  {REFERENCE_RELATIONS.map((rel) => (
                    <option key={rel} value={rel}>
                      {RELATION_LABEL[rel] ?? rel}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          ))}
          {save.error && <p className="text-xs text-error-700">{errMessage(save.error)}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setEditing(false)}
              disabled={save.isPending}
              className="btn btn-sm btn-outline"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => save.mutate()}
              disabled={save.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {save.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
              Save
            </button>
          </div>
        </div>
      ) : (
        <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
          {rows.map((r) => (
            <KV
              key={r.slot}
              k={`${RELATION_LABEL[r.relation] ?? r.relation} · ${r.fullName}`}
              v={r.mobile}
              mono
            />
          ))}
        </dl>
      )}
    </FocusCard>
  );
}

// ---- Disbursement (DISBURSEMENT_HEAD, ACCOUNTANT, ADMIN) -------------------

/**
 * What the release roles decide on: the exact amount to transfer, the account it goes to, and
 * whether that account was externally verified.
 *
 * <p>The account number and IFSC are shown in full — the Disbursement Head cannot make a transfer
 * without them, and V46 persists them for exactly that reason (revamp.md decision 16). Never log
 * them and never put them in an export.
 *
 * <p>The penny-drop line reads three ways on purpose. A borrower who kept their salary account is
 * never penny-dropped at all (decision 9), which is NOT the same as one who failed a check — and
 * the release desk is the last human who can catch a wrong account number, so the difference has to
 * be legible rather than collapsed into a green tick.
 */
function DisbursementFocus({ app, p }: { app: ApplicationView; p: ProfileView | undefined }) {
  const hasLoan = app.loanId != null;
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: hasLoan,
    retry: false,
  });
  const loan = loanQ.data;

  // Same lookup `BankTab` (customer-tabs.tsx) uses to read the penny-drop verification row's
  // `derived` — here specifically for `bankProofPending`, the marker `OfferService.confirmDisbursalAccount`
  // writes when the borrower used the cheque/passbook fallback instead of a penny drop.
  const verificationsQ = useQuery({
    queryKey: ["staff-verifications", app.id],
    queryFn: () => staffApi.verifications(app.id),
  });
  const pennyDropStep = (verificationsQ.data ?? []).find((s) => s.checkType === "PENNY_DROP");
  const bankProofPending = (pennyDropStep?.derived as Record<string, unknown> | undefined)?.bankProofPending === true;

  // The Aadhaar identity twin of the bank-proof marker above, read off the same verifications
  // query — `aadhaarManual` writes this on the AADHAAR row when DigiLocker never connected and the
  // borrower uploaded card photos instead.
  const aadhaarStep = (verificationsQ.data ?? []).find((s) => s.checkType === "AADHAAR");
  const aadhaarProofPending = (aadhaarStep?.derived as Record<string, unknown> | undefined)?.aadhaarProofPending === true;

  // Pre-disbursal the payable is the requested amount; post-disbursal it's the minted loan.
  const principal = loan?.principalPaise ?? app.amountRequestedPaise ?? null;
  const fee = loan?.processingFeePaise ?? (principal != null ? Math.round(principal * 0.1) : null);
  const gst = loan?.gstPaise ?? (fee != null ? Math.round(fee * 0.18) : null);
  const net = loan?.netDisbursedPaise ?? (principal != null && fee != null && gst != null ? principal - fee - gst : null);

  // The account the borrower confirmed on the last offer screen, falling back to the salary
  // account on file for applications that predate V46.
  const account = app.disbursalAccountNumber ?? p?.salaryAccountNumber ?? null;
  const ifsc = app.disbursalIfsc ?? p?.salaryIfsc ?? null;
  const changed = app.disbursalAccountChanged === true;
  const dropVerified = app.disbursalAccountVerified === true || p?.pennyDropVerified === true;

  return (
    <FocusCard icon={Banknote} title="Disbursement">
      <Headline
        label={loan ? "Transferred to borrower" : "Amount to transfer"}
        value={net != null ? paiseToINR(net) : "—"}
        caption={
          principal != null
            ? `${paiseToINR(principal)} principal − ${paiseToINR(fee ?? 0)} fee − ${paiseToINR(gst ?? 0)} GST`
            : "No amount requested yet"
        }
      />
      <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
        <KV k="Account number" v={account} mono />
        <KV k="IFSC" v={ifsc} mono />
        <KV k="Account holder" v={app.disbursalHolderName ?? p?.fullName} />
        <KV k="Bank" v={app.disbursalBank ?? p?.salaryBank} />
        <KV
          k="Penny drop"
          v={
            // A verified account wins outright, whichever route got it there — otherwise a file the
            // Head has just cleared would keep reporting "needs review" off the bank-proof marker.
            // Below that, the fallback is a fourth, distinct state: the borrower never ran (or
            // exhausted) a penny drop at all, and a human now stands in for it.
            dropVerified ? (
              <span className="font-semibold text-success-700">Verified</span>
            ) : bankProofPending ? (
              <span className="font-semibold text-warning-800">Bank proof uploaded — needs review</span>
            ) : changed ? (
              <span className="font-semibold text-warning-800">Not verified — needs review</span>
            ) : (
              <span className="font-semibold text-warning-800">
                Not run — borrower kept their salary account
              </span>
            )
          }
        />
        <KV
          k="Name match at bank"
          v={p?.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null}
        />
        {/* Only when there IS something to say: KV renders a placeholder for a null value, so an
            unconditional row printed a bare "Aadhaar —" on every disbursement card, including the
            overwhelming majority where DigiLocker worked and there is nothing to review. */}
        {aadhaarProofPending && (
          <KV k="Aadhaar" v={<span className="font-semibold text-warning-800">Card uploaded — needs review</span>} />
        )}
        <KV k="Transaction ref" v={loan?.disbursalTxnRef} mono />
        <KV k="Disbursed on" v={loan?.disbursedOn ? formatDate(loan.disbursedOn) : null} />
        <KV k="Repayment due" v={loan?.dueDate ? formatDate(loan.dueDate) : null} />
        <KV k="Contracted repayable (on-time)" v={loan ? paiseToINR(loan.totalRepayablePaise) : null} />
      </dl>
      {!dropVerified && (
        <p className="mt-3 rounded border border-warning-100 bg-warning-50 px-3 py-2 text-xs text-warning-800">
          {bankProofPending
            ? "Bank proof uploaded — verify the cheque/passbook against the account above before releasing."
            : changed
            ? "The penny-drop check has not passed on this account. Confirm it before releasing funds."
            : "This account was never penny-dropped — the borrower kept the salary account they typed at intake. Check the number against their payslips before releasing funds."}
        </p>
      )}
    </FocusCard>
  );
}

// ---- Collections (COLLECTION_HEAD/EXECUTIVE, ACCOUNTANT, ADMIN) ------------

/** What collections decides on: the penalty-aware amount due, shown as a full calculation. */
function CollectionsFocus({ app }: { app: ApplicationView }) {
  const hasLoan = app.loanId != null;
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: hasLoan,
    retry: false,
  });
  const outQ = useQuery({
    queryKey: ["staff-loan-out", app.loanId, todayISO()],
    queryFn: () => staffApi.outstanding(app.loanId as number, todayISO()),
    enabled: hasLoan,
    retry: false,
  });

  if (!hasLoan) {
    return (
      <FocusCard icon={PhoneCall} title="Amount due">
        <p className="text-sm text-muted">No loan has been disbursed yet — nothing is collectible.</p>
      </FocusCard>
    );
  }
  if (loanQ.isLoading || outQ.isLoading) {
    return (
      <FocusCard icon={PhoneCall} title="Amount due">
        <p className="text-sm text-muted">Loading…</p>
      </FocusCard>
    );
  }
  if (loanQ.error) {
    return (
      <FocusCard icon={PhoneCall} title="Amount due">
        <p className="text-sm text-error-700">{errMessage(loanQ.error)}</p>
      </FocusCard>
    );
  }

  const loan = loanQ.data as LoanView;
  const out = outQ.data as OutstandingView | undefined;
  // DPD is computed-on-read everywhere in this product; never stored.
  const dpd = loan.dueDate ? Math.max(0, daysBetween(new Date(loan.dueDate), new Date())) : 0;
  const overdue = dpd > 0;

  return (
    <FocusCard icon={PhoneCall} title="Amount due">
      <Headline
        label={out?.settledAmountPaise != null ? "Payable (settlement capped)" : "Net amount due today"}
        value={paiseToINR(out ? out.outstandingPaise : loan.outstandingPaise)}
        tone={overdue ? "error" : "navy"}
        caption={
          loan.dueDate
            ? overdue
              ? `${dpd} days past due · ${DPD_LABEL[dpdBucket(dpd)] ?? dpdBucket(dpd)} · due ${formatDate(loan.dueDate)}`
              : `Due ${formatDate(loan.dueDate)} — not yet overdue`
            : undefined
        }
      />

      <div className="text-xs font-semibold uppercase tracking-wide text-muted">How it adds up</div>
      {loan.disbursedOn && (
        <p className="mb-1 text-[8.8px] text-muted">
          Interest runs from disbursal on {formatDate(loan.disbursedOn)}
          {loan.dueDate ? ` and stops on the due date ${formatDate(loan.dueDate)}` : ""}; the late
          the grace day adds one normal-interest day without penalty. The 2% daily penalty starts the
          following day and is capped at 30 days.
        </p>
      )}
      <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
        <KV k="Principal" v={paiseToINR(loan.principalPaise)} mono />
        {/* Show the working, not just the figure: the day counts come from the backend breakdown,
            so "1%/day × N days" always reconciles with the rupee amount beside it (interest stops
            at the due date; penalty excludes the 1-day grace and is capped at 30 days). */}
        <KV
          k={`+ Interest accrued (1%/day × ${out?.interestDays ?? "—"} ${out?.interestDays === 1 ? "day" : "days"})`}
          v={out?.interestPaise != null ? paiseToINR(out.interestPaise) : "—"}
          mono
        />
        <KV
          k={
            out?.penaltyDays != null && out.penaltyDays > 0
              ? `+ Late penalty (2%/day × ${out.penaltyDays} ${out.penaltyDays === 1 ? "day" : "days"}, ≤30d)`
              : "+ Late penalty (2%/day, ≤30d)"
          }
          v={out?.penaltyPaise != null ? paiseToINR(out.penaltyPaise) : "—"}
          mono
        />
        <KV k="− Paid (verified)" v={out?.verifiedPaise != null ? paiseToINR(out.verifiedPaise) : "—"} mono />
        <KV
          k="= Net due"
          v={<span className="font-semibold text-navy">{paiseToINR(out ? out.outstandingPaise : loan.outstandingPaise)}</span>}
          mono
        />
        {out?.settledAmountPaise != null && (
          <KV
            k="Settlement (full & final)"
            v={<span className="font-semibold text-success-700">{paiseToINR(out.settledAmountPaise)}</span>}
            mono
          />
        )}
      </dl>

      <dl className="mt-3 grid gap-x-6 gap-y-1 border-t border-line pt-3 sm:grid-cols-2">
        <KV k="Loan status" v={loan.status} />
        <KV k="Disbursed on" v={loan.disbursedOn ? formatDate(loan.disbursedOn) : null} />
        <KV k="Net disbursed" v={paiseToINR(loan.netDisbursedPaise)} mono />
        <KV k="Contracted repayable (on-time)" v={paiseToINR(loan.totalRepayablePaise)} mono />
      </dl>

      {out?.settledAmountPaise != null && (
        <p className="mt-3 rounded border border-success-100 bg-success-50 px-3 py-2 text-xs text-success-800">
          An approved settlement caps this loan — collecting the payable above closes it in full and final.
        </p>
      )}
    </FocusCard>
  );
}

// ---- Credit (CREDIT_HEAD/EXECUTIVE, ADMIN) ---------------------------------

/**
 * What the credit roles decide on: the sanctioned ceiling against the ask.
 *
 * `eligibleLimitPaise` (25% of salary) is salary-derived GUIDANCE only, computed long before any
 * human credit decision — it must never stand in for `sanctionedAmountPaise`, the real ceiling a
 * Credit Executive/Head actually approved (`SanctionDialog`), including in the "over limit"
 * comparison. It renders here, honestly labelled as guidance, only pre-sanction (`sanctioned ==
 * null`); once a sanction exists (including a reborrow's carried-over sanction — see
 * `ApplicationFlowService.carryOverForReapply`) it is never read by this component again.
 */
function CreditFocus({
  app,
  p,
  detail,
}: {
  app: ApplicationView;
  p: ProfileView | undefined;
  detail: CreditBriefDetail | null;
}) {
  const limit = app.eligibleLimitPaise;
  const sanctioned = app.sanctionedAmountPaise;
  const asked = app.amountRequestedPaise;
  const applied = asked != null;
  const withinLimit = sanctioned != null && asked != null ? asked <= sanctioned : null;

  return (
    <FocusCard icon={Gauge} title="Credit">
      {applied ? (
        <Headline
          label="Requested"
          value={paiseToINR(asked)}
          caption={
            sanctioned != null ? (
              <>
                against a sanctioned amount of {paiseToINR(sanctioned)}
                {withinLimit === false && (
                  <span className="ml-1 font-semibold text-error-700">— over limit</span>
                )}
              </>
            ) : (
              <span className="font-semibold text-error-700">No sanctioned amount on record</span>
            )
          }
        />
      ) : sanctioned != null ? (
        <Headline
          label="Sanctioned"
          value={paiseToINR(sanctioned)}
          caption="Approved by the credit team — awaiting the borrower's offer acceptance."
        />
      ) : (
        <Headline
          label="Indicative limit"
          value={limit != null ? paiseToINR(limit) : "—"}
          caption="Salary-derived guidance. The credit team sets the sanctioned amount — nothing requested yet."
        />
      )}
      <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
        <KV k="CIBIL score" v={p?.creditScore != null ? String(p.creditScore) : null} mono />
        <KV k="Star rating" v={p?.starRating != null ? `${p.starRating.toFixed(1)}★` : null} />
        <KV k="Recommendation" v={p?.recommendation} />
        <KV k="Risk category" v={p?.riskCategory} />
        <KV k="Bureau" v={p?.bureauSource} />
        {applied && <KV k="Purpose" v={app.purpose} />}
      </dl>
      {p?.creditBriefSummary && (
        <p className="mt-3 rounded border border-line bg-grey-50 px-3 py-2 text-xs text-muted">
          {p.creditBriefSummary}
        </p>
      )}

      {/* `detail` exists only on briefs generated AFTER tradeline parsing shipped — there is
          deliberately no backfill, so every application pulled before it (and any with no bureau
          pull at all) renders exactly what this card showed before. Absent detail must never
          become an empty table or a row of zeroes: on a credit report a fabricated 0 reads as
          "clean", which is a materially different claim from "we don't know". */}
      {detail && (
        <div className="mt-4 space-y-4 border-t border-line pt-3">
          <div>
            <div className="mb-1.5 flex items-center gap-1 text-xs font-semibold uppercase tracking-wide text-muted">
              Delinquency history
              <InfoTooltip content="Aggregated from the full bureau report. A dash means the bureau data couldn't tell us — never read a dash as clean." />
            </div>
            <DelinquencySummaryBlock d={detail.delinquency} />
          </div>
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">Enquiry velocity</div>
            <EnquiryVelocityBlock v={detail.enquiryVelocity} />
          </div>
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted">Tradelines</div>
            <TradelineTable tradelines={detail.tradelines} tradelineCount={detail.tradelineCount} />
          </div>
        </div>
      )}
    </FocusCard>
  );
}

// ---- KYC (CREDIT_HEAD/EXECUTIVE, ADMIN) ------------------------------------

/**
 * Are the intake checks clear? Judged by the credit team since V45.
 *
 * <p>The headline and the per-check list must come from the SAME source. They used not to: the
 * headline counted verification rows (where a REVIEW counts as cleared) while each line rendered a
 * `customer_profile` boolean that is only set on a PASS. A file with PAN and email in REVIEW read
 * "4/4 · 100% complete" directly above five "No"s — and on older rows those booleans were never
 * backfilled at all, so checks that had genuinely PASSED also showed "No". Both halves now read the
 * verification rows, and a REVIEW says "Review" rather than being flattened to a no.
 */
function KycFocus({ applicationId, p }: { applicationId: number; p: ProfileView | undefined }) {
  const progQ = useQuery({
    queryKey: ["staff-verification-progress", applicationId],
    queryFn: () => staffApi.verificationProgress(applicationId),
    retry: false,
  });
  const checksQ = useQuery({
    queryKey: ["staff-verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId),
    retry: false,
  });
  const prog = progQ.data as VerificationProgress | undefined;

  const statusOf = React.useMemo(() => {
    const byType = new Map((checksQ.data ?? []).map((s) => [s.checkType, s.status]));
    return (...types: string[]) => types.map((t) => byType.get(t)).find(Boolean) ?? null;
  }, [checksQ.data]);

  return (
    <FocusCard icon={ShieldCheck} title="KYC & verification">
      <Headline
        label="Required checks cleared"
        value={prog ? `${prog.completed} / ${prog.required}` : "—"}
        tone={prog && prog.failed > 0 ? "error" : "navy"}
        caption={
          prog
            ? `${prog.percent}% complete${prog.failed > 0 ? ` · ${prog.failed} failed` : ""}${prog.pending > 0 ? ` · ${prog.pending} pending` : ""}`
            : undefined
        }
      />
      <dl className="grid gap-x-6 gap-y-1 sm:grid-cols-2">
        <KV k="PAN" v={<CheckState status={statusOf("PAN")} />} />
        <KV k="Aadhaar (DigiLocker)" v={<CheckState status={statusOf("AADHAAR", "DIGILOCKER")} />} />
        <KV k="Email" v={<CheckState status={statusOf("EMAIL")} />} />
        <KV k="Address" v={<CheckState status={statusOf("ADDRESS")} />} />
        {/* Penny drop legitimately never runs when the borrower keeps their salary account
            (revamp.md decision 9), so "Not run" here is not a gap to chase. */}
        <KV k="Penny drop" v={<CheckState status={statusOf("PENNY_DROP")} notRun="Not run" />} />
        <KV k="Identity match" v={p?.nameMatchScore != null ? `${Math.round(p.nameMatchScore * 100)}%` : null} />
      </dl>
      <p className="mt-2 text-[8.8px] text-muted">Per-check detail and manual overrides are on the Verifications tab.</p>
    </FocusCard>
  );
}

/** One check's real status, in the same vocabulary the Verifications tab uses. */
function CheckState({ status, notRun = "Not run" }: { status: string | null; notRun?: string }) {
  if (status === "PASS") {
    return (
      <span className="inline-flex items-center gap-0.5 text-success-700">
        <Check size={13} /> Passed
      </span>
    );
  }
  if (status === "FAIL") return <span className="font-semibold text-error-700">Failed</span>;
  if (status === "REVIEW") return <span className="text-warning-800">Review</span>;
  if (status === "PENDING") return <span className="text-muted">In progress</span>;
  return <span className="text-muted">{notRun}</span>;
}

// ---------------------------------------------------------------------------
// Journey — stepper + cost
// ---------------------------------------------------------------------------

function JourneyTab({
  app,
  journey,
  activeIndex,
  onStageClick,
}: {
  app: ApplicationView;
  journey: ReturnType<typeof deriveJourney> | null;
  activeIndex: number;
  onStageClick: (stage: JourneyStage) => void;
}) {
  return (
    <div className="space-y-4">
      <div className="rounded border border-line bg-white p-4">
        {journey?.fastTrack && (
          <span className="mb-3 inline-flex items-center gap-1 rounded-full bg-gold-50 px-2 py-0.5 text-xs font-semibold text-gold-dark">
            <Zap size={12} /> Fast-track
          </span>
        )}
        <JourneyAssignee app={app} />
        {journey ? (
          <JourneyStepper stages={journey.stages} activeIndex={activeIndex} onStageClick={onStageClick} />
        ) : (
          <p className="text-sm text-muted">Loading journey…</p>
        )}
      </div>
      <CostCard app={app} />
    </div>
  );
}

/**
 * The loan's real cost breakdown once a loan is minted (penalty/prepayment-aware
 * outstanding for a live loan), else the pre-disbursement projection. Uses the same
 * query keys/fns as the journey step popup so the figures stay consistent.
 */
function CostCard({ app }: { app: ApplicationView }) {
  const hasLoan = app.loanId != null;
  const loanQ = useQuery({
    queryKey: ["staff-loan", app.loanId],
    queryFn: () => staffApi.loan(app.loanId as number),
    enabled: hasLoan,
    retry: false,
  });
  const outQ = useQuery({
    queryKey: ["staff-loan-out", app.loanId, todayISO()],
    queryFn: () => staffApi.outstanding(app.loanId as number, todayISO()),
    enabled: hasLoan,
    retry: false,
  });
  const loan = loanQ.data;

  return (
    <div className="rounded border border-line bg-white p-4">
      <h4 className="mb-3 flex items-center gap-2 font-serif text-sm font-semibold text-navy">
        <Banknote size={15} aria-hidden /> Cost
        {(loanQ.isLoading || outQ.isLoading) && <Loader2 size={13} className="animate-spin text-muted" />}
      </h4>
      {hasLoan ? (
        loan ? (
          <>
            {loan.dueDate && <p className="mb-2 text-sm text-muted">Due {formatDate(loan.dueDate)}</p>}
            <LoanBreakdown loan={loan} outstanding={outQ.data} />
          </>
        ) : loanQ.error ? (
          <p className="text-sm text-error-700">{errMessage(loanQ.error)}</p>
        ) : (
          <p className="text-sm text-muted">Loading…</p>
        )
      ) : (
        <ProjectedCostBreakdown app={app} />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Past details — this customer's other applications/payments + loan history
// ---------------------------------------------------------------------------

function PastDetailsTab({
  app,
  onOpen,
}: {
  app: ApplicationView;
  /** Stack another of this customer's applications — the way to reach a reborrow's prior journey. */
  onOpen: (applicationId: number) => void;
}) {
  const q = useQuery({
    queryKey: ["customer-detail", app.customerId],
    queryFn: () => customersApi.get(app.customerId),
  });
  const c = q.data;
  const otherApps = (c?.applications ?? []).filter((a) => a.id !== app.id);

  return (
    <div className="space-y-4">
      <LoanHistory customerId={app.customerId} />

      <Section title={`Other applications (${otherApps.length})`}>
        {q.isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : otherApps.length === 0 ? (
          <p className="text-sm text-muted">None.</p>
        ) : (
          <ul className="divide-y divide-line">
            {otherApps.map((a) => (
              <li key={a.id} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                <span className="text-ink">
                  {/* `otherApps` already excludes the current file, so every row here is safe to open. */}
                  <button
                    type="button"
                    onClick={() => onOpen(a.id)}
                    className="font-semibold text-navy hover:underline"
                    title={`Open application #${a.id}`}
                  >
                    #{a.id}
                  </button>{" "}
                  · {statusLabel(a.status)}
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

      <Section title={`Payments (${c?.payments.length ?? 0})`}>
        {q.isLoading ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : !c || c.payments.length === 0 ? (
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
