"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

/**
 * Persisted scratch for the rebuilt P5 onboarding wizard.
 *
 * Each step still verifies/persists against the backend (PUT /profile + the
 * relevant /verify/* call); this store only holds the text the borrower typed
 * so that Back-navigation and a full reload re-show their inputs. The real
 * source of truth for "which steps are done" is `GET /verify/summary`.
 *
 * (Resolves the former `application-store.ts` "persist to the BFF" TODO with a
 * lightweight localStorage-backed store; the canonical progress is read from
 * the verify summary on mount.)
 */
export interface OnboardingDraft {
  mobile: string;
  fullName: string;
  personalEmail: string;
  officialEmail: string;
  employer: string;
  pan: string;
  aadhaar: string;
  /** Declared net monthly salary, in rupees. */
  monthlySalary: number;
  /** Day-of-month the salary lands (repayment date driver). */
  salaryDay: number;
  bankName: string;
  accountNumber: string;
  ifsc: string;
  /** Resolved (geocoded) or manually typed current address. */
  address: string;
  /** Optional refer-a-friend code typed at signup (applied best-effort; never blocks onboarding). */
  referralCode: string;
  /**
   * Whether the (otherwise hidden) co-applicant step should be shown. The
   * backend does not drive this yet, so it defaults to hidden.
   */
  coApplicantRequired: boolean;
}

interface OnboardingStore extends OnboardingDraft {
  patch: (p: Partial<OnboardingDraft>) => void;
  reset: () => void;
}

const EMPTY: OnboardingDraft = {
  mobile: "",
  fullName: "",
  personalEmail: "",
  officialEmail: "",
  employer: "",
  pan: "",
  aadhaar: "",
  monthlySalary: 0,
  salaryDay: 1,
  bankName: "",
  accountNumber: "",
  ifsc: "",
  address: "",
  referralCode: "",
  coApplicantRequired: false,
};

export const useOnboardingStore = create<OnboardingStore>()(
  persist(
    (set) => ({
      ...EMPTY,
      patch: (p) => set(p),
      reset: () => set({ ...EMPTY }),
    }),
    { name: "navix.onboarding.draft", version: 1 },
  ),
);
