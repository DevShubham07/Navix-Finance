"use client";

/**
 * The single staff detail popup — a shell around the 17 lifecycle tabs in `customer-tabs.tsx`
 * (shared with the full `/staff/customers/{id}` page, so the two cannot drift). Every tab body,
 * stage action included, lives in `customer-360/`; this component owns only the queries, the
 * header and the tab state.
 *
 * Open state is derived from `applicationId != null` and the tab state lives above the data, so it
 * survives the parent swapping the id. The default tab follows the file's stage (a file awaiting a
 * credit decision opens on Sanction, and so on), and the header carries an "Action pending" chip
 * that jumps to the tab holding the maker-checker action.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { X, ExternalLink } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { PillTabs } from "@/components/ui/pill-tabs";
import { CreditBadge } from "@/components/staff/credit-badge";
import { NeedsManualReviewBadge } from "@/components/staff/detail-parts";
import { CUSTOMER_TABS, CustomerTabBody } from "@/components/staff/customer-tabs";
import { stageActionTab } from "@/components/staff/customer-360/stage-actions";
import { hasPermission } from "@/lib/auth/rbac";
import { customerPageHref } from "@/lib/customers/customer-page";
import { ErrorState, Skeleton, StatusBadge } from "@/components/ui";
import { staffApi, customersApi, type ApplicationStatus } from "@/lib/api/applications";
import { useStaffMe, REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import { NoAccessNotice } from "@/components/staff/live-pipeline";

/** Stage-aware landing tab. */
function defaultTabFor(status: ApplicationStatus): string {
  switch (status) {
    case "CREDIT_EXEC_PENDING":
    case "KYC_PENDING":
    case "KYC_APPROVED":
    case "SANCTIONED":
      return "sanction";
    case "DISBURSEMENT_PENDING":
    case "DISBURSEMENT_FAILED":
      return "disbursal";
    case "ACTIVE":
    case "OVERDUE":
      return "repayment";
    default:
      return "customer";
  }
}

export interface ApplicationDetailDialogProps {
  applicationId: number | null;
  onClose: () => void;
  /** Set only on the stacked copy: it never opens a further level. */
  nested?: boolean;
}

export function ApplicationDetailDialog({ applicationId, onClose, nested = false }: ApplicationDetailDialogProps) {
  const open = applicationId != null;
  const id = applicationId ?? 0;
  const qc = useQueryClient();
  const [tab, setTab] = React.useState("customer");
  /** Another application of the same customer, stacked on top of this one (see the render below). */
  const [nestedAppId, setNestedAppId] = React.useState<number | null>(null);

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
  // get(id) is borrower-safe (no credit fields); the staff-only headline comes from the brief
  // endpoint. Same key and the same ungated call `application-info-dialog.tsx` already makes.
  const briefQ = useQuery({
    queryKey: ["credit-brief", id],
    queryFn: () => staffApi.creditBrief(id),
    enabled: open,
  });
  // Backs the header identity (name/mobile/PAN/risk).
  const profileQ = useQuery({
    queryKey: ["staff-profile", id],
    queryFn: () => staffApi.getProfile(id),
    enabled: open && canReview,
    retry: false,
  });

  const app = appQ.data;
  const customerId = app?.customerId ?? null;
  const detailQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open && customerId != null,
  });

  // The default tab is set once per file, when it first loads — not on every 8s refetch.
  const defaultedFor = React.useRef<number | null>(null);
  React.useEffect(() => {
    if (!open) defaultedFor.current = null;
  }, [open]);
  React.useEffect(() => {
    if (!app || defaultedFor.current === app.id) return;
    defaultedFor.current = app.id;
    setTab(defaultTabFor(app.status));
  }, [app]);

  // The 8s poll sees a status change that the (static) customer roll-up does not.
  const status = app?.status;
  const prevStatus = React.useRef<typeof status>(undefined);
  React.useEffect(() => {
    if (prevStatus.current && status && prevStatus.current !== status && customerId != null) {
      void qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
    }
    prevStatus.current = status;
  }, [status, customerId, qc]);

  const p = profileQ.data;
  const displayName = p?.fullName ?? app?.customerName ?? (app ? `Customer #${app.customerId}` : "Application");
  const displayMobile = p?.mobile ?? app?.customerMobile ?? null;
  const pendingTab = app ? stageActionTab(app.status) : null;

  return (
    <>
      {/* `!w` pins the width (`size` sets only the max-width, and globals.css's un-layered `.modal`
          outranks a plain `w-` utility). The panel is a flex column with the body as its single
          scroller, so the header and tab strip stay pinned. */}
      <Dialog open={open} onClose={onClose} size="xl" className="!w-[92vw] !p-6 !px-7 flex flex-col !overflow-hidden">
        <div className="shrink-0 border-b border-line pb-3">
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
                {app && <StatusBadge kind="application" value={app.status} />}
                {briefQ.data?.available && (
                  <CreditBadge
                    starRating={briefQ.data.starRating}
                    creditScore={briefQ.data.creditScore}
                    recommendation={briefQ.data.recommendation}
                    bureauSource={p?.bureauSource}
                  />
                )}
                {app?.customerId != null && <NeedsManualReviewBadge customerId={app.customerId} />}
                {pendingTab && (
                  <button
                    type="button"
                    onClick={() => setTab(pendingTab)}
                    className="rounded-full bg-warning-50 px-2 py-0.5 text-xs font-semibold text-warning-800 hover:bg-warning-100"
                  >
                    Action pending → {pendingTab === "sanction" ? "Sanction" : "Disbursal"}
                  </button>
                )}
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
            <button
              onClick={onClose}
              className="-mt-1 flex-shrink-0 rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
              aria-label="Close"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        <PillTabs tabs={CUSTOMER_TABS} active={tab} onChange={setTab} className="mt-2 shrink-0" />

        <div className="mt-3 min-h-0 flex-1 overflow-y-auto pr-1 text-[10.4px]">
          {appQ.isLoading ? (
            <Skeleton variant="line" rows={6} className="py-6" />
          ) : appQ.error ? (
            <ErrorState error={appQ.error} onRetry={() => void appQ.refetch()} />
          ) : !app ? (
            <p className="py-8 text-sm text-muted">Application #{id} not found.</p>
          ) : !canReview ? (
            <NoAccessNotice message="Customer details (incl. PII) aren't available to your role." />
          ) : detailQ.isLoading ? (
            <Skeleton variant="line" rows={6} className="py-6" />
          ) : detailQ.error ? (
            <ErrorState error={detailQ.error} onRetry={() => void detailQ.refetch()} />
          ) : detailQ.data ? (
            <CustomerTabBody
              tab={tab}
              detail={detailQ.data}
              customerId={app.customerId}
              applicationId={id}
              app={app}
              onChanged={() => void detailQ.refetch()}
              onOpenApplication={nested ? undefined : setNestedAppId}
              onTabChange={setTab}
            />
          ) : null}
        </div>
      </Dialog>

      {/* Another of this customer's applications, opened from the Loan tab. Stacked rather than
          swapped so closing it returns you here, on the tab you left. One level only: the nested
          copy gets no `onOpenApplication`. Placed outside <Dialog> in the fragment (Dialog portals
          to document.body, so the inner one paints on top). Self-recursive, no import. */}
      {nestedAppId != null && !nested && (
        <ApplicationDetailDialog applicationId={nestedAppId} onClose={() => setNestedAppId(null)} nested />
      )}
    </>
  );
}
