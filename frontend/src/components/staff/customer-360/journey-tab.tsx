"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Zap } from "lucide-react";
import { EmptyState, Skeleton } from "@/components/ui";
import { EventTimeline } from "@/components/staff/event-timeline";
import { JourneyStepper } from "@/components/staff/journey-stepper";
import { JourneyAssignee } from "@/components/staff/journey-assignee";
import { StageDetailDialog } from "@/components/staff/stage-detail-dialog";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { classifyEvent, deriveJourney, type EventKind, type JourneyStage } from "@/lib/domain/journey";
import { staffApi } from "@/lib/api/applications";
import { cn } from "@/lib/utils";

const CHIPS: { key: "all" | EventKind; label: string }[] = [
  { key: "all", label: "All" },
  { key: "lifecycle", label: "Lifecycle" },
  { key: "assignment", label: "Assignment" },
  { key: "decision", label: "Decision" },
];

export function JourneyTab({ app, applicationId }: Pick<TabCtx, "app" | "applicationId">) {
  const [openStage, setOpenStage] = React.useState<JourneyStage | null>(null);
  const [filter, setFilter] = React.useState<"all" | EventKind>("all");
  const eventsQ = useQuery({
    queryKey: ["staff-events", applicationId],
    queryFn: () => staffApi.events(applicationId as number),
    enabled: applicationId != null,
  });
  if (applicationId == null || !app) return <EmptyState title="No application to show yet." />;
  const events = eventsQ.data ?? [];
  const journey = deriveJourney(app, events);
  const activeIndex = journey.stages.reduce((acc, s, i) => (s.state !== "upcoming" ? i : acc), 0);
  const count = (k: "all" | EventKind) =>
    k === "all" ? events.length : events.filter((e) => classifyEvent(e) === k).length;
  const shown = filter === "all" ? events : events.filter((e) => classifyEvent(e) === filter);

  return (
    <div className="grid gap-4 lg:grid-cols-[2fr_3fr]">
      <div className="rounded border border-line bg-white p-4">
        {journey.fastTrack && (
          <span className="mb-3 inline-flex items-center gap-1 rounded-full bg-gold-50 px-2 py-0.5 text-xs font-semibold text-gold-dark">
            <Zap size={12} /> Fast-track
          </span>
        )}
        <JourneyAssignee app={app} />
        <JourneyStepper stages={journey.stages} activeIndex={activeIndex} onStageClick={setOpenStage} />
      </div>
      <div className="space-y-3">
        <div className="flex flex-wrap gap-2">
          {CHIPS.map((c) => (
            <button
              key={c.key}
              type="button"
              onClick={() => setFilter(c.key)}
              className={cn("cal-preset", filter === c.key && "on")}
            >
              {c.label} {count(c.key)}
            </button>
          ))}
        </div>
        {eventsQ.isLoading ? (
          <Skeleton variant="line" rows={3} />
        ) : shown.length === 0 ? (
          <EmptyState
            title={
              events.length > 0
                ? `No ${CHIPS.find((c) => c.key === filter)?.label.toLowerCase()} events for this application.`
                : "No events recorded yet."
            }
          />
        ) : (
          <EventTimeline events={shown} />
        )}
      </div>
      {openStage && (
        <StageDetailDialog
          applicationId={app.id}
          app={app}
          stage={openStage}
          stages={journey.stages}
          allEvents={events}
          open
          onClose={() => setOpenStage(null)}
          onNavigate={setOpenStage}
        />
      )}
    </div>
  );
}
