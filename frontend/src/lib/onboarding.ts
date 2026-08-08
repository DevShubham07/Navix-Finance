"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";
import { useOnboardingStore } from "@/stores/application-store";
import { readStoredAppId } from "@/lib/api/live-journey";
import { borrowerApi, journeyApi, type ProfileInput, type ProfileView } from "@/lib/api/applications";
import { useMounted } from "@/hooks/use-mounted";
export { ONBOARDING_STEPS } from "@/lib/onboarding-config";

/**
 * The Phase-1 intake, in order — mirrors `JourneyService.Step` on the backend (revamp.md).
 * `set-password` is the one optional step: it is in the linear count but skippable.
 */
/** The T&C accepted on screen 1. Bump the version to re-prompt every borrower. */
export const TERMS_VERSION = "terms-and-conditions@1";
export const TERMS_DOC_HREF = "/legal/terms-and-conditions.txt";

/**
 * The credit-bureau consent wording. Single source of truth: the consent step renders this string
 * AND sends it to the backend, which stores it verbatim on the audit row — so what was displayed and
 * what was recorded cannot drift apart.
 */
export const BUREAU_CONSENT_TEXT =
  "I authorize DhanBoost to retrieve my credit report from a credit bureau for verification and assessment purposes.";

/** Shared per-step context: hydration guard, the persisted draft, and the in-flight application id. */
export function useOnboarding() {
  const mounted = useMounted();
  const draft = useOnboardingStore();
  const [appId, setAppId] = React.useState<number | null>(null);
  React.useEffect(() => {
    setAppId(readStoredAppId());
  }, []);
  return { mounted, draft, appId, setAppId };
}

/**
 * The saved profile for this application — every step hydrates its fields from here rather than from
 * localStorage, so a borrower resuming on another device finds what they already typed (decision 26).
 * `null` while loading or when nothing has been saved yet.
 */
export function useSavedProfile(appId: number | null): ProfileView | null {
  const [profile, setProfile] = React.useState<ProfileView | null>(null);
  React.useEffect(() => {
    if (appId == null) return;
    let live = true;
    borrowerApi
      .getProfile(appId)
      .then((p) => live && setProfile(p))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [appId]);
  return profile;
}

/** Persist a profile slice, dropping empty fields so a later slice never clobbers an earlier one. */
export async function saveProfileSlice(appId: number, slice: ProfileInput): Promise<void> {
  const compact: ProfileInput = {};
  for (const [k, v] of Object.entries(slice)) {
    if (v === undefined || v === null || v === "") continue;
    (compact as Record<string, unknown>)[k] = v;
  }
  if (Object.keys(compact).length === 0) return;
  await borrowerApi.saveProfile(appId, compact);
}

/** Record a finished step server-side, then go to `next`. Failing to record must not block the borrower. */
export async function completeStep(appId: number, step: string, router: { push: (h: string) => void }, next: string) {
  await journeyApi.advance(appId, step).catch(() => {});
  router.push(next);
}

/**
 * On entering the wizard, jump to wherever the server says this borrower actually is — that is what
 * makes a second device resume mid-flow (revamp.md C1). Deliberately mount-only: once they are in the
 * wizard the step pages drive navigation, so this never fights a Back button.
 */
export function useJourneyGuard(appId: number | null) {
  const router = useRouter();
  const pathname = usePathname();
  const checked = React.useRef(false);

  React.useEffect(() => {
    if (appId == null || checked.current) return;
    checked.current = true;
    journeyApi
      .get(appId)
      .then((j) => {
        if (j.route && j.route !== pathname) router.replace(j.route);
      })
      .catch(() => {});
  }, [appId, pathname, router]);
}
