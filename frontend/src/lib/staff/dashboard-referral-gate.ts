import type { FeatureFlags } from "@/lib/api/applications";

/**
 * Whether the dashboard should count pending referral payouts (Disbursement Head's queue).
 *
 * - `true`  — the `referral` flag is on, or absent from the response (the flag is a kill switch:
 *   only an explicit `false` turns the feature off), or the flag read itself failed. Failing open
 *   matches the old in-queue fetch, which treated an unreadable flag set as `{}`.
 * - `false` — the flag is explicitly off; the payouts endpoint is not called at all.
 * - `undefined` — not known yet (flags still loading). The caller waits: firing the payouts call
 *   before the flag is known would ask the backend for a feature that may be switched off.
 *
 * Data already in hand wins over a later failed refetch, so a transient flags error never flips
 * a known-off kill switch back on.
 */
export function referralPayoutsGate(flags: FeatureFlags | undefined, flagsFailed: boolean): boolean | undefined {
  if (flags) return flags.referral !== false;
  return flagsFailed ? true : undefined;
}
