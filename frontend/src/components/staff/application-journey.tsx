"use client";

/**
 * Application Journey drawer (staff-only).
 *
 * A point-in-time, per-application lifecycle visualization launched from any
 * queue row (or the detail page). It collapses the backend's 20-status state
 * machine into the 6 macro-stages of {@link deriveJourney} and renders them as a
 * vertical stepper — every stage carries a state (done / active / pending /
 * branch), the latest timestamp + acting person, and is itself a `<button>` that
 * opens the per-step {@link StageDetailDialog} popup.
 *
 * Data is fetched only while the drawer is open, with NO polling (this is an
 * inspection surface, not an acting queue). It reuses the same React Query keys
 * as the live pipeline (`staff-application` / `staff-events` / `staff-profile`),
 * so a maker-checker action elsewhere refreshes the open journey too.
 *
 * RBAC: this component renders staff-only fields (customer name behind the same
 * `REVIEW_PERMS` gate as CustomerReview; credit detail inside the popup). It must
 * never be imported from the borrower route tree.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Zap, X, ArrowRight } from "lucide-react";
import {
  Drawer,
  DrawerHeader,
  DrawerTitle,
  DrawerBody,
  DrawerFooter,
} from "@/components/ui";
import { hasPermission } from "@/lib/auth/rbac";
import { staffApi, statusLabel, paiseToINR } from "@/lib/api/applications";
import { deriveJourney, type JourneyStage } from "@/lib/domain/journey";
import { useStaffMe, errMessage, REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import { JourneyStepper } from "@/components/staff/journey-stepper";
import { StageDetailDialog } from "@/components/staff/stage-detail-dialog";

export interface ApplicationJourneyProps {
  applicationId: number;
  open: boolean;
  onClose: () => void;
}

export function ApplicationJourney({ applicationId, open, onClose }: ApplicationJourneyProps) {
  const role = useStaffMe().data?.role;
  const canReview = role != null && REVIEW_PERMS.some((p) => hasPermission(role, p));

  const appQ = useQuery({
    queryKey: ["staff-application", applicationId],
    queryFn: () => staffApi.get(applicationId),
    enabled: open,
  });
  const eventsQ = useQuery({
    queryKey: ["staff-events", applicationId],
    queryFn: () => staffApi.events(applicationId),
    enabled: open,
  });
  const profileQ = useQuery({
    queryKey: ["staff-profile", applicationId],
    queryFn: () => staffApi.getProfile(applicationId),
    enabled: open && canReview,
    retry: false,
  });

  const app = appQ.data;
  const events = eventsQ.data ?? [];
  const journey = app ? deriveJourney(app, events) : null;
  const isLoading = appQ.isLoading || eventsQ.isLoading;

  const [openStage, setOpenStage] = React.useState<JourneyStage | null>(null);
  // Close any open step popup whenever the drawer itself closes.
  React.useEffect(() => {
    if (!open) setOpenStage(null);
  }, [open]);

  const titleId = `journey-title-${applicationId}`;
  const customerName =
    profileQ.data?.fullName ?? (app ? `Customer #${app.customerId}` : "Customer");

  // The current stage = the last stage that isn't still "upcoming".
  const activeIndex = journey
    ? journey.stages.reduce((acc, s, i) => (s.state !== "upcoming" ? i : acc), 0)
    : 0;

  return (
    <>
      <Drawer open={open} onClose={onClose} aria-labelledby={titleId}>
        <DrawerHeader>
          <div className="flex items-start justify-between gap-3">
            <DrawerTitle id={titleId}>Application #{applicationId}</DrawerTitle>
            <button
              onClick={onClose}
              aria-label="Close journey"
              className="-mr-1 -mt-0.5 flex-shrink-0 rounded p-1 text-muted hover:bg-grey-100 hover:text-ink"
            >
              <X size={18} />
            </button>
          </div>
          {app && (
            <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
              <span className="rounded-full bg-navy-tint px-2 py-0.5 font-semibold text-navy">
                {statusLabel(app.status)}
              </span>
              <span className="truncate">{customerName}</span>
              <span>· {paiseToINR(app.amountRequestedPaise)}</span>
              {journey?.fastTrack && (
                <span className="inline-flex items-center gap-1 rounded-full bg-gold-50 px-2 py-0.5 font-semibold text-gold-dark">
                  <Zap size={11} /> Fast-track
                </span>
              )}
            </div>
          )}
        </DrawerHeader>

        <DrawerBody>
          {isLoading ? (
            <JourneySkeleton />
          ) : appQ.error ? (
            <div className="rounded border border-error-100 bg-error-50 px-4 py-3 text-sm text-error-700">
              {errMessage(appQ.error)}
            </div>
          ) : !app || !journey ? (
            <p className="text-sm text-muted">Application #{applicationId} not found.</p>
          ) : (
            <JourneyStepper
              stages={journey.stages}
              activeIndex={activeIndex}
              onStageClick={setOpenStage}
            />
          )}
        </DrawerBody>

        <DrawerFooter>
          <Link
            href={`/staff/credit/${applicationId}`}
            onClick={onClose}
            className="btn btn-sm btn-outline"
          >
            Open full detail <ArrowRight size={14} />
          </Link>
        </DrawerFooter>
      </Drawer>

      {openStage && app && (
        <StageDetailDialog
          applicationId={applicationId}
          app={app}
          stage={openStage}
          allEvents={events}
          open
          onClose={() => setOpenStage(null)}
        />
      )}
    </>
  );
}

function JourneySkeleton() {
  return (
    <div className="space-y-5" aria-hidden>
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="flex items-start gap-4">
          <div className="h-8 w-8 flex-shrink-0 animate-pulse rounded-full bg-grey-200" />
          <div className="flex-1 space-y-2 pt-1">
            <div className="h-3 w-1/3 animate-pulse rounded bg-grey-200" />
            <div className="h-2.5 w-1/2 animate-pulse rounded bg-grey-100" />
          </div>
        </div>
      ))}
    </div>
  );
}
