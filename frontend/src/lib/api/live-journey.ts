"use client";

/**
 * Live borrower-journey adapter.
 *
 * Single seam between the *committed designed UI* (the `(borrower)/*` pages) and
 * the *real* NAVIX backend. The designed wizard keeps using the mock Zustand
 * store as multi-step form scratch; at the points that matter (submit, choose
 * amount, status, dashboard) the pages call the helpers/hooks here, which talk
 * to the Spring application state-machine through the BFF (`borrowerApi`).
 *
 * This replaces the standalone `/apply-live` page — the same proven logic, made
 * reusable so the polished journey is backed by real, persisted state.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ApplicationApiError,
  borrowerApi,
  rupeesToPaise,
  type ApplicationStatus,
  type ApplicationView,
  type ProfileInput,
} from "@/lib/api/applications";
import { eligibleLimit as eligibleLimitRupees } from "@/lib/calc/loan-math";
import type { ApplicantProfile, BorrowerStatus } from "@/lib/mock/borrower";

/** Browser-local pointer to the borrower's in-flight live application id. */
const STORAGE_KEY = "navix.live.applicationId";
const POLL_MS = 4000;

/** Statuses where the borrower can do nothing more — the journey is over (one way or another). */
const TERMINAL: ApplicationStatus[] = [
  "ACTIVE",
  "CLOSED",
  "WRITTEN_OFF",
  "DEFAULTED",
  "OVERDUE",
  "REJECTED",
  "CANCELLED",
  "KYC_REJECTED",
  "DISBURSEMENT_FAILED",
];

/** Bad terminal states (stop polling, show a declined/failed card). */
const TERMINAL_BAD: ApplicationStatus[] = [
  "KYC_REJECTED",
  "REJECTED",
  "CANCELLED",
  "DISBURSEMENT_FAILED",
];

// ---------------------------------------------------------------------------
// Session (real httpOnly navix_borrower cookie)
// ---------------------------------------------------------------------------

export interface BorrowerSession {
  id: string;
  applicantId: number;
  name: string;
  mobile: string;
}

/** Read the current live borrower session from the BFF (or null). */
export async function fetchBorrowerSession(): Promise<BorrowerSession | null> {
  const res = await fetch("/api/auth/borrower/me", { cache: "no-store", credentials: "same-origin" });
  if (!res.ok) return null;
  const json = (await res.json()) as { session: BorrowerSession | null };
  return json.session;
}

/**
 * Ensure a real `navix_borrower` cookie exists for this mobile and return the
 * session. If one is already set we keep it; otherwise we log in with the demo
 * OTP so the applicantId is stable and the BFF can thread identity to Spring.
 */
export async function ensureBorrowerSession(mobile: string, name?: string): Promise<BorrowerSession | null> {
  const existing = await fetchBorrowerSession();
  if (existing) return existing;
  const res = await fetch("/api/auth/borrower/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ mobile, otp: "123456", name }),
  });
  if (!res.ok) return null;
  return (await res.json()) as BorrowerSession;
}

/** React Query wrapper for the live borrower session. */
export function useBorrowerSession() {
  return useQuery({ queryKey: ["borrower-me"], queryFn: fetchBorrowerSession });
}

// ---------------------------------------------------------------------------
// Application-id persistence (shared across pages via localStorage)
// ---------------------------------------------------------------------------

export function readStoredAppId(): number | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : null;
}

export function writeStoredAppId(id: number | null): void {
  if (typeof window === "undefined") return;
  if (id == null) window.localStorage.removeItem(STORAGE_KEY);
  else window.localStorage.setItem(STORAGE_KEY, String(id));
}

/** Hydrate the stored app id after mount (avoids SSR/client mismatch). */
export function useStoredAppId(): [number | null, (id: number | null) => void] {
  const [appId, setAppId] = React.useState<number | null>(null);
  React.useEffect(() => {
    setAppId(readStoredAppId());
  }, []);
  const update = React.useCallback((id: number | null) => {
    setAppId(id);
    writeStoredAppId(id);
  }, []);
  return [appId, update];
}

// ---------------------------------------------------------------------------
// Live application + loan (polling)
// ---------------------------------------------------------------------------

export interface LiveApplication {
  appId: number | null;
  setAppId: (id: number | null) => void;
  app: ApplicationView | undefined;
  loan: Awaited<ReturnType<typeof borrowerApi.loan>> | undefined;
  isLoading: boolean;
  refetch: () => void;
}

/** Poll the borrower's live application (+ loan once ACTIVE). */
export function useLiveApplication(): LiveApplication {
  const [appId, setAppId] = useStoredAppId();

  const appQuery = useQuery({
    queryKey: ["live-application", appId],
    queryFn: () => borrowerApi.get(appId as number),
    enabled: appId != null,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (!status) return POLL_MS;
      if (status === "ACTIVE" || TERMINAL_BAD.includes(status)) return false;
      return POLL_MS;
    },
  });

  const app = appQuery.data;
  // The loan is minted at ACTIVE and persists through OVERDUE/CLOSED — fetch it for any of them.
  const loanId =
    app && (app.status === "ACTIVE" || app.status === "OVERDUE" || app.status === "CLOSED")
      ? app.loanId
      : null;

  const loanQuery = useQuery({
    queryKey: ["live-loan", loanId],
    queryFn: () => borrowerApi.loan(loanId as number),
    enabled: loanId != null,
  });

  return {
    appId,
    setAppId,
    app,
    loan: loanQuery.data,
    isLoading: appQuery.isLoading,
    refetch: () => appQuery.refetch(),
  };
}

/** The application's audit trail (for the live status page). */
export function useLiveEvents(appId: number | null, status?: ApplicationStatus) {
  return useQuery({
    queryKey: ["live-events", appId, status],
    queryFn: () => borrowerApi.events(appId as number),
    enabled: appId != null,
  });
}

// ---------------------------------------------------------------------------
// Status mapping — backend ApplicationStatus -> designed BorrowerStatus
// ---------------------------------------------------------------------------

/**
 * Map a backend application to the customer-facing {@link BorrowerStatus} so the
 * existing display components (LoanStatusTracker, StatusChip, LoanCard) can be
 * reused unchanged. This is a *rough* progress mapping; pages that need the
 * exact state branch on the raw {@link ApplicationView} fields directly.
 */
export function appStatusToStage(app: ApplicationView | null | undefined): BorrowerStatus {
  if (!app) return "NEW";
  switch (app.status) {
    case "DRAFT":
      return "NEW";
    case "KYC_PENDING":
      return "UNDER_REVIEW";
    case "KYC_APPROVED":
      // KYC done; "APPROVED" prompts the choose-amount step. Once an amount is
      // submitted it's in credit review — still shown as approved (the precise
      // label + audit trail on /loan/status disambiguate).
      return "APPROVED";
    case "CREDIT_EXEC_PENDING":
    case "CREDIT_EXEC_APPROVED":
    case "CREDIT_HEAD_PENDING":
      return "APPROVED";
    case "CREDIT_HEAD_APPROVED":
    case "DISBURSEMENT_PENDING":
    case "ACCOUNTANT_PENDING":
    case "DISBURSED":
      return "DISBURSING";
    case "ACTIVE":
      return "ACTIVE";
    case "OVERDUE":
      return "OVERDUE";
    case "CLOSED":
      return "REPAID";
    case "KYC_REJECTED":
    case "REJECTED":
    case "CANCELLED":
    case "DISBURSEMENT_FAILED":
      return "DECLINED";
    default:
      return "UNDER_REVIEW";
  }
}

/** True once the borrower has submitted a desired amount (entered credit review). */
export function hasApplied(app: ApplicationView | null | undefined): boolean {
  return !!app && app.amountRequestedPaise != null;
}

/** The borrower can pick an amount only when KYC-approved and not yet applied. */
export function canChooseAmount(app: ApplicationView | null | undefined): boolean {
  return !!app && app.status === "KYC_APPROVED" && app.amountRequestedPaise == null;
}

export function isTerminalBad(app: ApplicationView | null | undefined): boolean {
  return !!app && TERMINAL_BAD.includes(app.status);
}

export { TERMINAL as LIVE_TERMINAL_STATUSES };

// ---------------------------------------------------------------------------
// Profile mapping (wizard ApplicantProfile -> backend ProfileInput)
// ---------------------------------------------------------------------------

export function buildProfileInput(a: ApplicantProfile): ProfileInput {
  const address = [a.addressLine, a.city, a.pin].filter(Boolean).join(", ");
  return {
    fullName: a.fullName?.trim() || undefined,
    pan: a.pan?.trim().toUpperCase() || undefined,
    aadhaar: a.aadhaar?.replace(/\D/g, "") || undefined,
    mobile: a.mobile?.replace(/\D/g, "") || undefined,
    address: address || undefined,
    employer: a.employer?.trim() || undefined,
    employmentStatus: "SALARIED",
    monthlySalaryPaise: a.monthlySalary ? rupeesToPaise(a.monthlySalary) : undefined,
    salaryBank: a.bankName?.trim() || undefined,
  };
}

// ---------------------------------------------------------------------------
// Mutations (used by the wizard review + choose-amount pages)
// ---------------------------------------------------------------------------

/** Resolve the in-flight DRAFT/in-progress app, or create a fresh DRAFT. */
async function resolveApplication(session: BorrowerSession): Promise<ApplicationView> {
  const existing = readStoredAppId();
  if (existing != null) {
    try {
      const app = await borrowerApi.get(existing);
      // Continue an in-flight application; only a terminal one warrants a fresh start.
      if (!TERMINAL.includes(app.status)) return app;
    } catch {
      // fall through and create a new draft
    }
  }
  const created = await borrowerApi.create(session.applicantId);
  writeStoredAppId(created.id);
  return created;
}

/**
 * Submit the completed onboarding form to the real backend:
 * ensure session -> create DRAFT -> save KYC profile -> upload docs -> submit KYC.
 * Persists everything in Postgres and advances the application to KYC_PENDING.
 */
export async function submitOnboarding(
  applicant: ApplicantProfile,
  docs: Array<{ docType: string; fileName: string; contentType?: string; dataBase64: string }> = [],
): Promise<ApplicationView> {
  const mobile = applicant.mobile?.replace(/\s/g, "") || "";
  const session = await ensureBorrowerSession(mobile, applicant.fullName);
  if (!session) {
    throw new ApplicationApiError("Could not establish a borrower session — please sign in again.", "NO_SESSION", 0);
  }

  let app = await resolveApplication(session);

  const profile = buildProfileInput(applicant);
  if (Object.values(profile).some((v) => v !== undefined)) {
    await borrowerApi.saveProfile(app.id, profile);
  }

  for (const d of docs) {
    await borrowerApi.uploadDocument(app.id, d);
  }

  if (app.status === "DRAFT") {
    app = await borrowerApi.submitKyc(app.id);
  }

  writeStoredAppId(app.id);
  return app;
}

/** Submit the desired amount once KYC is approved (enters the credit queue). */
export async function applyForAmount(
  appId: number,
  params: { amountRupees: number; purpose?: string; salaryDay?: number; monthlySalary?: number },
): Promise<ApplicationView> {
  const eligibleRupees = params.monthlySalary ? eligibleLimitRupees(params.monthlySalary) : undefined;
  const day = params.salaryDay;
  return borrowerApi.apply(appId, {
    amountPaise: rupeesToPaise(params.amountRupees),
    purpose: params.purpose?.trim() || undefined,
    salaryCreditDay: day != null && day >= 1 && day <= 31 ? day : undefined,
    eligibleLimitPaise: eligibleRupees ? rupeesToPaise(eligibleRupees) : undefined,
  });
}
