"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";
import { journeyApi } from "@/lib/api/applications";
import { useLiveApplication } from "@/lib/api/live-journey";

/**
 * The post-sanction offer journey (revamp.md Phase 3) — mirrors `JourneyService.OfferStep` on the
 * backend. Kept separate from `ONBOARDING_STEPS` because the two run under different application
 * statuses (DRAFT vs SANCTIONED) and share nothing but the resume mechanism.
 */
export const OFFER_STEPS: Array<{ seg: string; step: string; label: string }> = [
  { seg: "amount", step: "OFFER_AMOUNT", label: "Choose your amount" },
  { seg: "repayment-date", step: "OFFER_REPAYMENT_DATE", label: "Repayment date" },
  { seg: "digilocker", step: "OFFER_DIGILOCKER", label: "Verify with DigiLocker" },
  { seg: "references", step: "OFFER_REFERENCES", label: "Your references" },
  { seg: "summary", step: "OFFER_SUMMARY", label: "Loan summary" },
  { seg: "selfie", step: "OFFER_SELFIE", label: "Take a selfie" },
  { seg: "address", step: "OFFER_ADDRESS", label: "Confirm your address" },
  { seg: "sanction-letter", step: "OFFER_SANCTION_LETTER", label: "Sanction letter" },
  { seg: "esign", step: "OFFER_ESIGN", label: "Sign your agreement" },
  { seg: "sanctioned", step: "OFFER_SANCTIONED", label: "Sanctioned" },
  { seg: "disbursal-account", step: "OFFER_DISBURSAL_ACCOUNT", label: "Where to send the money" },
];

/** The routes this journey owns — everything else under /loan/* is outside the wizard chrome. */
export const OFFER_SEGMENTS = new Set(OFFER_STEPS.map((s) => s.seg));

/**
 * The steps the SERVER says this borrower's journey has, cached for the session.
 *
 * <p>A re-apply's journey is genuinely shorter — DigiLocker, references, the selfie and the address
 * check carried over from the last advance, so they are not screens this borrower walks (revamp.md
 * decision 45). That list lives on the backend (`JourneyService.applicableOfferSteps`), which is the
 * only thing that can know; the layout fetches it once and everything here reads it.
 *
 * <p>Module-scoped rather than context because `nextOfferRoute` is called from event handlers deep
 * inside eleven pages. It falls back to the full list, so a page rendered before the fetch lands
 * behaves exactly as it did before V47 — never skipping a step the borrower does owe.
 */
/**
 * The full list, as ONE stable array instance.
 *
 * <p>This must never be rebuilt per call. `useSyncExternalStore` compares snapshots by reference and
 * re-renders whenever they differ, so returning a freshly-mapped array from `getSnapshot` /
 * `getServerSnapshot` makes every render produce a "new" value — an infinite update loop that
 * crashed `LoanLayout` and blanked every `/loan/*` route.
 */
const ALL_SEGMENTS: readonly string[] = OFFER_STEPS.map((s) => s.seg);

let applicableSegments: string[] | null = null;

const subscribers = new Set<() => void>();

/** Record the server's step list (step NAMES, e.g. `OFFER_AMOUNT`) and wake the layout. */
export function setOfferSteps(steps: string[] | undefined) {
  if (!steps || steps.length === 0) return;
  const segs = steps
    .map((name) => OFFER_STEPS.find((s) => s.step === name)?.seg)
    .filter((s): s is string => Boolean(s));
  if (segs.length === 0 || segs.join() === applicableSegments?.join()) return;
  applicableSegments = segs;
  subscribers.forEach((fn) => fn());
}

function subscribeOfferSteps(fn: () => void) {
  subscribers.add(fn);
  return () => subscribers.delete(fn);
}

/** Always a stable reference: either the server's stored array, or the one shared constant. */
function segments(): readonly string[] {
  return applicableSegments ?? ALL_SEGMENTS;
}

/** The steps to render, in order — the server's list once known, the full one until then. */
export function useOfferSteps(): typeof OFFER_STEPS {
  // The same `segments` for both client and server snapshots — identical reference, no loop.
  const segs = React.useSyncExternalStore(subscribeOfferSteps, segments, segments);
  return React.useMemo(
    () => segs.map((seg) => OFFER_STEPS.find((s) => s.seg === seg)!).filter(Boolean),
    [segs],
  );
}

export function offerStepIndex(seg: string): number {
  return segments().indexOf(seg);
}

/** How many steps this borrower's journey has (the progress denominator). */
export function offerStepCount(): number {
  return segments().length;
}

/** Where a step goes next. The last step hands off to disbursement, so it has no successor here. */
export function nextOfferRoute(seg: string): string {
  const segs = segments();
  const next = segs[segs.indexOf(seg) + 1];
  return next ? `/loan/${next}` : "/loan/status";
}

export function prevOfferRoute(seg: string): string | undefined {
  const segs = segments();
  const i = segs.indexOf(seg);
  return i > 0 ? `/loan/${segs[i - 1]}` : undefined;
}

/**
 * The sanctioned application this journey runs against, plus a guard that bounces anyone whose
 * application isn't in the offer stage. Reuses `useLiveApplication` (the existing borrower seam) so
 * the app-id pointer still recovers on a fresh device via `GET /mine`.
 */
export function useOffer() {
  const router = useRouter();
  const { appId, app, isLoading, refetch } = useLiveApplication();

  React.useEffect(() => {
    if (isLoading || !app) return;
    if (app.status !== "SANCTIONED") router.replace("/loan/status");
  }, [isLoading, app, router]);

  return { appId, app, isLoading, refetch };
}

/**
 * Record a finished offer step server-side, then move on. As on the intake, a failure to record must
 * not block the borrower — the pointer is advisory and `JourneyService` re-derives from saved data.
 */
export async function completeOfferStep(appId: number, step: string, router: { push: (h: string) => void }, next: string) {
  await journeyApi
    .advance(appId, step)
    .then((j) => setOfferSteps(j?.steps))
    .catch(() => {});
  router.push(next);
}

/**
 * On entering the offer journey, jump to wherever the server says this borrower actually is — the
 * same cross-device resume as the intake (revamp.md C1), and the reason a borrower who abandons at
 * the selfie doesn't come back to the amount screen. Mount-only, so it never fights a Back button.
 */
export function useOfferGuard(appId: number | null) {
  const router = useRouter();
  const pathname = usePathname();
  const checked = React.useRef(false);

  React.useEffect(() => {
    if (appId == null || checked.current) return;
    checked.current = true;
    journeyApi
      .get(appId)
      .then((j) => {
        // Learn the shape of this borrower's journey before deciding where they belong in it.
        setOfferSteps(j?.steps);
        if (j.route && j.route.startsWith("/loan/") && j.route !== pathname) router.replace(j.route);
      })
      .catch(() => {});
  }, [appId, pathname, router]);
}
