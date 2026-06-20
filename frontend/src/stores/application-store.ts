import { create } from "zustand";
import type { SignupApplication, SignupStep } from "@/lib/domain/application";

/**
 * Client-side store for the in-progress borrower onboarding application.
 * TODO: persist to the BFF and hydrate on load.
 */
interface ApplicationState {
  application: SignupApplication | null;
  setApplication: (application: SignupApplication | null) => void;
  /** Advance the local step pointer. TODO: sync with backend. */
  goToStep: (step: SignupStep) => void;
  reset: () => void;
}

export const useApplicationStore = create<ApplicationState>((set) => ({
  application: null,
  setApplication: (application) => set({ application }),
  goToStep: (step) =>
    set((state) =>
      state.application
        ? { application: { ...state.application, currentStep: step } }
        : state,
    ),
  reset: () => set({ application: null }),
}));
