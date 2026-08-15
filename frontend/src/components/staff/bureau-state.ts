import type { BureauState } from "@/lib/api/applications";

/**
 * Single source of truth for "no record" / "not fetched" copy across every credit-score surface
 * (the application info dialog, credit profile card, both score gauges, and the two list views).
 * `"FOUND"` returns "" — callers render the rich display instead.
 */
export function bureauStateLabel(state: BureauState, variant: "long" | "short" | "caption"): string {
  if (state === "NO_RECORD") {
    return variant === "long" ? "No records found"
      : variant === "caption" ? "NO RECORDS FOUND"
      : "No record";
  }
  if (state === "NOT_FETCHED") {
    return variant === "long" ? "Record not fetched till now"
      : variant === "caption" ? "NOT FETCHED YET"
      : "Not fetched";
  }
  return "";
}
