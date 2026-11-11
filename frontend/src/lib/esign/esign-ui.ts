import { ApplicationApiError, type EsignDocStatus } from "@/lib/api/applications";
import { errMessage } from "@/components/staff/live-pipeline";
import type { BadgeVariant } from "@/components/ui/badge";

export const DOC_STATUS_VARIANT: Record<string, BadgeVariant> = {
  SENT: "warning",
  COMPLETED: "success",
  FAILED: "error",
  EXPIRED: "neutral",
  PENDING: "warning",
  SIGNED: "success",
  NOT_SENT: "neutral",
  SKIPPED: "neutral",
};

export type { EsignDocStatus };

/** Friendly copy for the eSign error codes; anything else falls back to the shared formatter. */
export function esignErrMessage(e: unknown): string {
  if (e instanceof ApplicationApiError) {
    switch (e.code) {
      case "ESIGN_DOC_INVALID":
        return e.message;
      case "FEATURE_DISABLED":
        return "eSign documents is switched off for this environment.";
      case "FORBIDDEN_ROLE":
        return "Only admins can send documents for eSign.";
      case "ESIGN_PROVIDER_FAILED":
        return "The eSign provider could not create the contract. Nothing was sent — please try again shortly.";
    }
  }
  return errMessage(e);
}
