"use client";

import * as React from "react";
import { useOnboardingStore } from "@/stores/application-store";
import { readStoredAppId } from "@/lib/api/live-journey";
import { borrowerApi, type ProfileInput } from "@/lib/api/applications";
import { useMounted } from "@/hooks/use-mounted";

/**
 * The canonical ordered list of wizard steps (financials removed; co-applicant
 * is conditional and intentionally not in the linear order). Drives the
 * progress bar in the signup layout.
 */
export const ONBOARDING_STEPS: Array<{ seg: string; label: string }> = [
  { seg: "mobile-otp", label: "Mobile & OTP" },
  { seg: "email", label: "Email & employer" },
  { seg: "address", label: "Current address" },
  { seg: "digilocker", label: "DigiLocker KYC" },
  { seg: "pan", label: "PAN verification" },
  { seg: "bureau", label: "Credit check" },
  { seg: "salary", label: "Salary & slip" },
  { seg: "penny-drop", label: "Bank verification" },
  { seg: "selfie", label: "Selfie" },
  { seg: "agreement", label: "Agreements" },
  { seg: "review", label: "Review & submit" },
];

/** The three legal documents shown on the agreement step. */
export const AGREEMENT_DOCS: Array<{ key: string; version: string; title: string; href: string }> = [
  { key: "loan-agreement", version: "loan-agreement@1", title: "Master Loan Agreement", href: "/legal/loan-agreement.txt" },
  { key: "loan-sanction", version: "loan-sanction@1", title: "Sanction Letter", href: "/legal/loan-sanction.txt" },
  { key: "privacy-declaration", version: "privacy-declaration@1", title: "Privacy & Consent Declaration", href: "/legal/privacy-declaration.txt" },
];

export const AGREEMENT_VERSIONS = AGREEMENT_DOCS.map((d) => d.version);

/**
 * Shared per-step context: a hydration guard (so persisted inputs don't trip
 * React's SSR mismatch), the persisted draft + its `patch`, and the resolved
 * in-flight application id (null until the DRAFT is created at mobile-otp).
 */
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
 * Where to navigate after a wizard step succeeds. When the step was opened from the review page to
 * fix a single failed check (the URL carries `?return=review`), go straight back to review instead
 * of walking the rest of the wizard again — so retrying one step (e.g. DigiLocker) doesn't drag the
 * borrower through the entire flow. Otherwise advance to the given linear next step.
 */
export function nextAfterStep(defaultNext: string): string {
  if (typeof window !== "undefined") {
    const ret = new URLSearchParams(window.location.search).get("return");
    if (ret === "review") return "/signup/review";
  }
  return defaultNext;
}

/** Persist a profile slice, dropping empty/undefined fields so we never clobber stored data. */
export async function saveProfileSlice(appId: number, slice: ProfileInput): Promise<void> {
  const compact: ProfileInput = {};
  for (const [k, v] of Object.entries(slice)) {
    if (v === undefined || v === null || v === "") continue;
    (compact as Record<string, unknown>)[k] = v;
  }
  if (Object.keys(compact).length === 0) return;
  await borrowerApi.saveProfile(appId, compact);
}
