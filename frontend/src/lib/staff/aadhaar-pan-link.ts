import type { StepResult } from "@/lib/api/applications";

/**
 * The Aadhaar–PAN linkage step. No separate vendor call: every PAN provider (Signzy 206AB,
 * Digitap, Fintrix pan_comprehensive) already answers the link status in the same response, and
 * the backend stores it as the PAN row's `derived.aadhaarLinked`. This lifts that answer out into
 * its own step so staff see it beside the other third-party checks. Read-only — nothing to retry
 * or override on its own; re-running PAN refreshes it.
 */
export const AADHAAR_PAN_LINK = "AADHAAR_PAN_LINK";

const text = (v: unknown): string | null => (v == null || v === "" ? null : String(v));

/**
 * Derive the linkage step from the PAN row, or null when PAN has not run or answered no link
 * status. `aadhaar` is the full number on the customer profile, when the caller has it; otherwise
 * the masked Aadhaar the PAN record carries is shown.
 */
export function aadhaarPanLinkStep(steps: readonly StepResult[], aadhaar?: string | null): StepResult | null {
  const pan = steps.find((s) => s.checkType === "PAN");
  const d = (pan?.derived ?? {}) as Record<string, unknown>;
  if (!pan || typeof d.aadhaarLinked !== "boolean") return null;
  const linked = d.aadhaarLinked;
  return {
    checkType: AADHAAR_PAN_LINK,
    status: linked ? "PASS" : "FAIL",
    message: linked ? "Aadhaar and PAN are linked" : "Aadhaar and PAN are not linked",
    derived: {
      aadhaarNumber: text(aadhaar) ?? text(d.maskedAadhaar),
      panNumber: text(d.panNumber),
      linked,
    },
    provider: pan.provider ?? null,
    providerTxnId: pan.providerTxnId ?? null,
    clientRefNum: pan.clientRefNum ?? null,
    checkedAt: pan.checkedAt ?? null,
  };
}

/** `steps` with the linkage step slotted in right after PAN (unchanged when there is none). */
export function withAadhaarPanLink(steps: readonly StepResult[], aadhaar?: string | null): StepResult[] {
  const link = aadhaarPanLinkStep(steps, aadhaar);
  if (!link || steps.some((s) => s.checkType === AADHAAR_PAN_LINK)) return [...steps];
  const at = steps.findIndex((s) => s.checkType === "PAN");
  return [...steps.slice(0, at + 1), link, ...steps.slice(at + 1)];
}
