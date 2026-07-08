import * as React from "react";
import {
  FilePlus,
  Repeat,
  Send,
  UserCheck,
  ShieldCheck,
  ShieldX,
  ThumbsUp,
  ThumbsDown,
  Banknote,
  BadgeCheck,
  CircleDollarSign,
  RotateCcw,
  Ban,
  CheckCircle2,
  XCircle,
  Zap,
  Route,
  Circle,
  type LucideIcon,
} from "lucide-react";
import type { EventView } from "@/lib/api/applications";
import { cn, formatDateTime } from "@/lib/utils";

/**
 * Event trail entry — the shared shape rendered by {@link EventTimeline}.
 *
 * `actorName` is declared locally (optional) because the backend/API type may
 * gain it in a parallel change; this renderer degrades to `actorRole` /
 * `"system"` when it is absent.
 */
export type TimelineEvent = EventView & { actorName?: string | null };

export interface EventTimelineProps {
  events: TimelineEvent[];
  className?: string;
  /** Tighter spacing/typography for inline use inside a row or popup. */
  dense?: boolean;
}

type ActionMeta = { label: string; Icon: LucideIcon; tone: string };

const TONE_INFO = "bg-info-100 text-info-700";
const TONE_OK = "bg-success-100 text-success-700";
const TONE_BAD = "bg-error-100 text-error-700";
const TONE_WARN = "bg-warning-100 text-warning-700";
const TONE_NEUTRAL = "bg-grey-100 text-muted";

/**
 * One entry per action literal emitted by `ApplicationFlowService.transition(...)`
 * / `logEvent(...)`. Enumerated from the backend source; unknown actions fall
 * back to {@link DEFAULT_META}.
 */
const ACTION_META: Record<string, ActionMeta> = {
  CREATE: { label: "Application created", Icon: FilePlus, tone: TONE_NEUTRAL },
  REBORROW: { label: "Reborrow started", Icon: Repeat, tone: TONE_INFO },
  SUBMIT_KYC: { label: "KYC submitted", Icon: Send, tone: TONE_INFO },
  KYC_APPROVE: { label: "KYC approved", Icon: ShieldCheck, tone: TONE_OK },
  KYC_REJECT: { label: "KYC rejected", Icon: ShieldX, tone: TONE_BAD },
  REVIEW_APPROVE: { label: "Reborrow review cleared", Icon: ShieldCheck, tone: TONE_OK },
  REVIEW_REJECT: { label: "Reborrow review rejected", Icon: ShieldX, tone: TONE_BAD },
  APPLY: { label: "Amount requested", Icon: CircleDollarSign, tone: TONE_INFO },
  APPLY_FAST_TRACK: { label: "Fast-tracked to disbursement", Icon: Zap, tone: TONE_INFO },
  ASSIGN: { label: "Assigned to executive", Icon: UserCheck, tone: TONE_INFO },
  EXEC_APPROVE: { label: "Recommended", Icon: ThumbsUp, tone: TONE_OK },
  EXEC_REJECT: { label: "Rejected by executive", Icon: ThumbsDown, tone: TONE_BAD },
  AUTO_ROUTE: { label: "Auto-routed", Icon: Route, tone: TONE_NEUTRAL },
  HEAD_APPROVE: { label: "Approved", Icon: BadgeCheck, tone: TONE_OK },
  HEAD_REJECT: { label: "Rejected by head", Icon: ThumbsDown, tone: TONE_BAD },
  DISB_ACCEPT: { label: "Accepted for disbursal", Icon: Send, tone: TONE_INFO },
  DISB_REJECT: { label: "Disbursement rejected", Icon: XCircle, tone: TONE_BAD },
  VALIDATE_FAIL: { label: "Transfer failed", Icon: XCircle, tone: TONE_WARN },
  VALIDATE_SUCCESS: { label: "Transfer confirmed", Icon: Banknote, tone: TONE_OK },
  ACTIVATE: { label: "Loan activated", Icon: CheckCircle2, tone: TONE_OK },
  RETRY: { label: "Disbursement retried", Icon: RotateCcw, tone: TONE_WARN },
  CANCEL: { label: "Cancelled", Icon: Ban, tone: TONE_BAD },
  REPAID: { label: "Loan closed (repaid)", Icon: CheckCircle2, tone: TONE_OK },
};

const DEFAULT_META: ActionMeta = { label: "Updated", Icon: Circle, tone: TONE_NEUTRAL };

function metaFor(action: string | null): ActionMeta {
  if (!action) return DEFAULT_META;
  return ACTION_META[action] ?? { ...DEFAULT_META, label: humanize(action) };
}

/** "DISB_ACCEPT" -> "Disb accept" (fallback label for unmapped actions). */
function humanize(action: string): string {
  const s = action.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function actorLabel(e: TimelineEvent): string {
  return e.actorName ?? e.actorRole ?? "system";
}

/**
 * The single shared audit-trail renderer for an application's `EventView[]`.
 * Vertical timeline (icon dots + connecting line) in the app's navy/gold/
 * semantic tokens. Replaces the two divergent renderers (live-pipeline
 * `EventsTrail`, loan-detail-dialog `EventLi`).
 */
export function EventTimeline({ events, className, dense }: EventTimelineProps) {
  if (events.length === 0) {
    return <p className={cn("text-sm text-muted", className)}>No events recorded yet.</p>;
  }
  return (
    <ol className={cn("relative", className)} data-testid="event-timeline">
      {events.map((e, i) => {
        const meta = metaFor(e.action);
        const Icon = meta.Icon;
        const isLast = i === events.length - 1;
        return (
          <li
            key={e.id ?? `${e.action ?? "event"}-${i}`}
            className={cn("relative flex gap-3", dense ? "pb-3 last:pb-0" : "pb-5 last:pb-0")}
          >
            {!isLast && (
              <span
                className={cn(
                  "absolute w-0.5 bg-line",
                  dense
                    ? "left-3 top-7 h-[calc(100%-1rem)]"
                    : "left-4 top-9 h-[calc(100%-1.25rem)]",
                )}
              />
            )}
            <span
              className={cn(
                "z-10 flex flex-shrink-0 items-center justify-center rounded-full",
                dense ? "h-6 w-6" : "h-8 w-8",
                meta.tone,
              )}
            >
              <Icon size={dense ? 13 : 16} />
            </span>
            <div className="min-w-0">
              <div className={cn("text-ink", dense ? "text-xs" : "text-sm")}>
                <strong>{meta.label}</strong>
                {e.fromStatus || e.toStatus ? (
                  <span className="text-muted">
                    {" "}
                    {e.fromStatus ? `${e.fromStatus} → ` : ""}
                    {e.toStatus ?? ""}
                  </span>
                ) : null}
              </div>
              <div className={cn("text-muted", dense ? "text-[11px]" : "text-xs")}>
                by {actorLabel(e)}
                {e.actorName && e.actorRole ? ` · ${e.actorRole}` : ""} · {formatDateTime(e.at)}
              </div>
              {e.notes ? (
                <p className={cn("mt-1 text-ink/90", dense ? "text-xs" : "text-sm")}>{e.notes}</p>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
