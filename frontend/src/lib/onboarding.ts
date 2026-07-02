"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { useOnboardingStore } from "@/stores/application-store";
import { readStoredAppId, hydrateDraftFromProfile } from "@/lib/api/live-journey";
import { borrowerApi, type ProfileInput, type StepResult } from "@/lib/api/applications";
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
 * Shared per-step context: a hydration guard (so persisted inputs don't trip React's SSR mismatch),
 * the persisted draft + its `patch`, and the resolved in-flight application id (null until the DRAFT
 * is created at mobile-otp).
 *
 * `mounted` intentionally stays false until the draft has been **pre-filled from the server profile**
 * (`hydrateDraftFromProfile`) — every step seeds its inputs from the draft store exactly once, gated
 * on `mounted`, so the server data must be in the store *first* or a returning borrower would be shown
 * blank fields. The prefill runs once per app id (React Query, `staleTime: Infinity`) and is a no-op
 * when there is no id yet (the mobile-otp step) or the DRAFT has no saved profile.
 */
export function useOnboarding() {
  const baseMounted = useMounted();
  const draft = useOnboardingStore();
  const [appId, setAppId] = React.useState<number | null>(null);
  const [appIdResolved, setAppIdResolved] = React.useState(false);
  React.useEffect(() => {
    setAppId(readStoredAppId());
    setAppIdResolved(true);
  }, []);
  const prefill = useQuery({
    queryKey: ["onboarding-prefill", appId],
    queryFn: async () => {
      if (appId != null) await hydrateDraftFromProfile(appId);
      return true;
    },
    enabled: appIdResolved,
    staleTime: Infinity,
  });
  const mounted = baseMounted && appIdResolved && prefill.isFetched;
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

/**
 * The wizard step (`ONBOARDING_STEPS` seg) each required verification check gates. Mirrors the
 * per-step `stepRoute` map on the review page; kept here so the dashboard's resume link and the
 * wizard share one source of truth. (mobile-otp / set-password / review carry no check.)
 */
const STEP_CHECK: Record<string, string> = {
  email: "EMAIL",
  address: "ADDRESS",
  digilocker: "AADHAAR",
  pan: "PAN",
  bureau: "BUREAU",
  salary: "SALARY",
  "penny-drop": "PENNY_DROP",
  selfie: "SELFIE",
  agreement: "AGREEMENT",
};

/**
 * The first onboarding step the borrower still needs to finish, derived from the server verification
 * summary — so an abandoned application resumes at the right place (not step 1). A check counts done
 * when its status is PASS or REVIEW; a missing row means "not started". DigiLocker is done if EITHER
 * AADHAAR or DIGILOCKER passed (the callback finalises AADHAAR). Everything done → `"review"`; nothing
 * recorded yet → `"email"` (the first real step after mobile-otp).
 */
export function firstIncompleteStepSeg(summary: StepResult[]): string {
  const done = new Set(
    summary.filter((s) => s.status === "PASS" || s.status === "REVIEW").map((s) => s.checkType),
  );
  const isDone = (check: string) =>
    check === "AADHAAR" ? done.has("AADHAAR") || done.has("DIGILOCKER") : done.has(check);
  for (const { seg } of ONBOARDING_STEPS) {
    const check = STEP_CHECK[seg];
    if (check && !isDone(check)) return seg;
  }
  return "review";
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
