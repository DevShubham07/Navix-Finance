import type { BadgeVariant } from "@/components/ui/badge";

/** Mandate status as the console shows it (CANCELLED is derived server-side from cancelled_at). */
export const MANDATE_STATUS: Record<string, { label: string; tone: BadgeVariant }> = {
  ACTIVE: { label: "Active", tone: "success" },
  PENDING_AUTHORIZATION: { label: "Awaiting authorisation", tone: "warning" },
  AUTHORIZATION_FAILED: { label: "Authorisation failed", tone: "error" },
  EXPIRED: { label: "Expired", tone: "error" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  BANK_UNSUPPORTED: { label: "Bank not supported", tone: "warning" },
};

export const DEBIT_STATUS: Record<string, { label: string; tone: BadgeVariant }> = {
  SCHEDULED: { label: "Scheduled", tone: "warning" },
  IN_PROGRESS: { label: "In progress", tone: "info" },
  SUCCEEDED: { label: "Succeeded", tone: "success" },
  FAILED: { label: "Failed", tone: "error" },
  FAILED_TECHNICAL: { label: "Failed (technical)", tone: "error" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  SKIPPED: { label: "Skipped", tone: "neutral" },
};

/** Who presented a debit: the staffer for a manual one, the sweep for a scheduled one. */
export function presentedBy(d: { trigger: string; actorName: string | null; actorRole: string | null }): string {
  if (d.actorName) return `${d.actorName}${d.actorRole ? ` (${d.actorRole.replace(/_/g, " ").toLowerCase()})` : ""}`;
  return d.trigger === "SCHEDULED" ? "Automatic sweep" : "—";
}
