import * as React from "react";
import { UserCheck, ThumbsUp, ThumbsDown, Banknote, Send } from "lucide-react";
import type { ApprovalTrailEntry } from "@/lib/domain";
import { STAFF_ROLE_LABELS } from "@/lib/mock/session";
import { cn } from "@/lib/utils";

/**
 * Maker-checker audit trail timeline. Enforces visibility of separation of
 * duties: who recommended / approved / released / confirmed, when, and notes.
 */
export interface ApprovalTrailProps {
  entries: ApprovalTrailEntry[];
  className?: string;
}

const ACTION_META: Record<string, { label: string; Icon: typeof UserCheck; tone: string }> = {
  RECOMMEND: { label: "Recommended", Icon: UserCheck, tone: "bg-info-100 text-info-700" },
  APPROVE: { label: "Approved", Icon: ThumbsUp, tone: "bg-success-100 text-success-700" },
  REJECT: { label: "Rejected", Icon: ThumbsDown, tone: "bg-error-100 text-error-700" },
  RELEASE: { label: "Released", Icon: Send, tone: "bg-info-100 text-info-700" },
  DISBURSE: { label: "Transfer confirmed", Icon: Banknote, tone: "bg-success-100 text-success-700" },
};

function when(iso: string) {
  return new Date(iso).toLocaleString("en-IN", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function ApprovalTrail({ entries, className }: ApprovalTrailProps) {
  if (!entries.length) {
    return <p className={cn("text-sm text-muted", className)}>No actions recorded yet.</p>;
  }
  return (
    <ol className={cn("relative", className)} data-testid="approval-trail">
      {entries.map((entry, i) => {
        const meta = ACTION_META[entry.action] ?? ACTION_META.RECOMMEND;
        const Icon = meta.Icon;
        return (
          <li key={entry.id ?? i} className="relative flex gap-3 pb-5 last:pb-0">
            {i < entries.length - 1 && <span className="absolute left-4 top-9 h-[calc(100%-1.25rem)] w-0.5 bg-line" />}
            <span className={cn("z-10 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full", meta.tone)}>
              <Icon size={16} />
            </span>
            <div>
              <div className="text-sm">
                <strong className="text-ink">{meta.label}</strong>{" "}
                <span className="text-muted">by {entry.actorName}</span>
              </div>
              <div className="text-xs text-muted">
                {STAFF_ROLE_LABELS[entry.role]} · {when(entry.createdAt)}
              </div>
              {entry.notes ? <p className="mt-1 text-sm text-ink/90">{entry.notes}</p> : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
