import type { StepResult } from "@/lib/api/applications";
import { matchesMasked } from "@/lib/customers/bre/matching";

/**
 * The Aadhaar–PAN linkage step shown beside the other third-party checks. Two sources, in order:
 *  1. The backend's own `PAN_AADHAAR_LINK` row (Signzy kycIdLinkStatus on the PAN + the Aadhaar number
 *     the borrower typed, run at submit-kyc). It is the only source that ties the PAN to *this
 *     customer's* Aadhaar, so when it exists its verdict is the step's verdict.
 *  2. The PAN row's `derived.aadhaarLinked`. Every PAN provider answers it, but it only means "this PAN
 *     is seeded with some Aadhaar" — so it is shown as confirmed only when the PAN record's masked
 *     Aadhaar matches the customer's own number. With no Aadhaar number on file (or no mask to
 *     compare) the link is unconfirmed: REVIEW, never "Success".
 * Read-only — the PAN check refreshes source 2.
 */
export const AADHAAR_PAN_LINK = "AADHAAR_PAN_LINK";

/** The backend check type of source 1 (ApplicationVerificationService.PAN_AADHAAR_LINK). */
const PAN_AADHAAR_LINK = "PAN_AADHAAR_LINK";

const text = (v: unknown): string | null => (v == null || v === "" ? null : String(v));

/**
 * Derive the linkage step, or null when neither source has answered. `aadhaar` is the full number on
 * the customer profile, when the caller has it; otherwise the masked Aadhaar a provider returned is
 * shown (but never by itself confirms the link).
 */
export function aadhaarPanLinkStep(steps: readonly StepResult[], aadhaar?: string | null): StepResult | null {
  const pan = steps.find((s) => s.checkType === "PAN");
  const d = (pan?.derived ?? {}) as Record<string, unknown>;
  const real = steps.find((s) => s.checkType === PAN_AADHAAR_LINK);

  if (real) {
    const rd = (real.derived ?? {}) as Record<string, unknown>;
    const linked = typeof rd.linked === "boolean" ? rd.linked : null;
    return {
      checkType: AADHAAR_PAN_LINK,
      status: linked === true ? "PASS" : real.status,
      message: real.message ?? null,
      derived: {
        aadhaarNumber: text(aadhaar) ?? text(rd.maskedAadhaar) ?? text(d.maskedAadhaar),
        panNumber: text(d.panNumber),
        linked,
        panReportsLinked: typeof d.aadhaarLinked === "boolean" ? d.aadhaarLinked : null,
      },
      provider: real.provider ?? null,
      providerTxnId: real.providerTxnId ?? null,
      clientRefNum: real.clientRefNum ?? null,
      checkedAt: real.checkedAt ?? null,
    };
  }

  if (!pan || typeof d.aadhaarLinked !== "boolean") return null;
  const panReportsLinked = d.aadhaarLinked;
  const ownAadhaar = text(aadhaar);
  // A "linked" flag counts only once the PAN record's Aadhaar is shown to be this customer's.
  const confirmed = panReportsLinked && matchesMasked(ownAadhaar, text(d.maskedAadhaar)) === "MATCH";
  const linked = !panReportsLinked ? false : confirmed ? true : null;
  return {
    checkType: AADHAAR_PAN_LINK,
    status: linked === true ? "PASS" : linked === false ? "FAIL" : "REVIEW",
    message: linked === true ? "Aadhaar and PAN are linked"
      : linked === false ? "Aadhaar and PAN are not linked"
      : ownAadhaar == null
        ? "PAN reports a linked Aadhaar, but no Aadhaar number is on file to confirm it is this customer's"
        : "PAN reports a linked Aadhaar, not yet confirmed against this customer's Aadhaar number",
    derived: {
      aadhaarNumber: ownAadhaar ?? text(d.maskedAadhaar),
      panNumber: text(d.panNumber),
      linked,
      panReportsLinked,
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
  return at < 0 ? [...steps, link] : [...steps.slice(0, at + 1), link, ...steps.slice(at + 1)];
}
