/**
 * Pure helpers for the verification checks panel: the retry in-flight guard and the provider label.
 * Kept out of the component so they can be unit-tested.
 */

import { ApplicationApiError, VerificationRetryTimeoutError } from "@/lib/api/applications";

/**
 * Did a retry fail in a way that leaves the provider call possibly still running? True for the
 * client's own 120s give-up ({@link VerificationRetryTimeoutError}) and for an HTTP 504 — a gateway
 * (load balancer or the BFF's own time limit) that stopped waiting while the backend carried on.
 * Anything else is an answer from the server, and retrying after it is fine.
 */
export function isRetryOutcomeUnknown(error: unknown): boolean {
  if (error instanceof VerificationRetryTimeoutError) return true;
  return error instanceof ApplicationApiError && error.status === 504;
}

/**
 * The retry guard's key: application AND check type. The checks panel can be re-pointed at another
 * application without unmounting (Customer 360 follows the customer's latest application), and one
 * file's in-flight BUREAU retry must never disable — or be lifted by — another file's BUREAU row.
 */
export function retryGuardKey(applicationId: number, checkType: string): string {
  return `${applicationId}:${checkType}`;
}

/**
 * The retry in-flight guard. `guards` maps a {@link retryGuardKey} to the row timestamp
 * (`checkedAt`, i.e. the row's updatedAt) it had when the retry was sent; the guard holds while that
 * timestamp is unchanged, and lifts once the row is written again (the provider call finished).
 * A missing row is `null` on both sides, so a never-run check stays guarded until its first row.
 */
export function isRetryStillRunning(
  guards: Readonly<Record<string, string | null>>,
  key: string,
  currentCheckedAt: string | null | undefined,
): boolean {
  if (!Object.prototype.hasOwnProperty.call(guards, key)) return false;
  return (guards[key] ?? null) === (currentCheckedAt ?? null);
}

/**
 * Who answered a check, as a phrase for the checks panel: the vendor's own name for a provider
 * result ("Answered by DIGITAP"), or what produced it for the three non-vendor values the backend
 * writes (`MANUAL` staff override, `MANUAL_PROOF` uploaded proof, `SYSTEM` flag). Null when the row
 * names nobody — e.g. a provider that failed to answer, which the panel words separately.
 */
export function providerAttribution(provider: string | null | undefined): string | null {
  const p = provider?.trim();
  if (!p) return null;
  if (p === "MANUAL") return "Manual override";
  if (p === "MANUAL_PROOF") return "Manual proof upload";
  if (p === "SYSTEM") return "Raised by the system";
  return `Answered by ${p}`;
}
