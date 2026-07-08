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
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import {
  ApplicationApiError,
  borrowerApi,
  rupeesToPaise,
  type ApplicationStatus,
  type ApplicationView,
  type ProfileInput,
} from "@/lib/api/applications";
import { eligibleLimit as eligibleLimitRupees } from "@/lib/calc/loan-math";
import { useOnboardingStore } from "@/stores/application-store";
import type { CustomerProfile, BorrowerStatus } from "@/lib/domain/borrower";

/** Browser-local pointer to the borrower's in-flight live application id. */
const STORAGE_KEY = "navix.live.applicationId";
const POLL_MS = 4000;
/**
 * How often to re-list the borrower's OWN applications (`["my-apps"]`). Modest on purpose — this is a
 * cross-page shared cache (header/dashboard), not a hot path — but it MUST poll: a reborrow can advance
 * to ACTIVE faster than a single `/mine` snapshot, and without a refetch the pointer reconciliation in
 * {@link useLiveApplication} branch (b) would never see the newer app (the old "why the Pay button was
 * intermittently dead for reborrowers" bug). 15s is well inside the window before a user reaches /repay.
 */
const MINE_POLL_MS = 15_000;

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
  customerId: number;
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
 * Update the live session's display name (maps the borrower's typed name onto their
 * phone-derived session) so the header/dashboard greet them by name instead of the
 * "Borrower" default. Best-effort: never throws / never blocks onboarding.
 */
export async function updateBorrowerName(name: string): Promise<void> {
  const trimmed = name.trim();
  if (!trimmed) return;
  try {
    await fetch("/api/auth/borrower/me", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ name: trimmed }),
    });
  } catch {
    /* ignore — display name is cosmetic; the next /me poll self-heals */
  }
}

/**
 * Ensure a real `navix_borrower` cookie exists for this mobile and return the
 * session. If one is already set we keep it; otherwise we log in with the
 * supplied OTP (the backend validates it and issues the JWT) so the BFF can
 * authenticate every later call to Spring.
 */
/** Result of requesting an OTP: whether the SMS went out + (dev-echo only) the code. */
export interface OtpRequestResult {
  sent: boolean;
  ttlSeconds: number;
  devCode?: string;
}

/**
 * Ask the backend to generate + SMS an OTP to {@code mobile} (UltronSMS gateway).
 * Throws with the backend message on failure (e.g. invalid mobile).
 */
export async function requestBorrowerOtp(mobile: string): Promise<OtpRequestResult> {
  const res = await fetch("/api/auth/borrower/otp/request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ mobile }),
  });
  const env = (await res.json().catch(() => null)) as
    | (OtpRequestResult & { error?: { message?: string } | string })
    | null;
  if (!res.ok) {
    const msg =
      (env && typeof env.error === "object" ? env.error?.message : (env?.error as string)) ??
      "Could not send the OTP — please try again.";
    throw new Error(typeof msg === "string" ? msg : "Could not send the OTP.");
  }
  return { sent: env?.sent ?? false, ttlSeconds: env?.ttlSeconds ?? 0, devCode: env?.devCode };
}

/**
 * Return the live borrower session, or establish one by verifying {@code otp}. When
 * {@code otp} is omitted this only reuses an existing session (used by flows that run
 * after the mobile-otp step has already logged the borrower in).
 */
export async function ensureBorrowerSession(
  mobile: string,
  otp?: string,
  name?: string,
): Promise<BorrowerSession | null> {
  const existing = await fetchBorrowerSession();
  if (existing) return existing;
  if (!otp) return null;
  const res = await fetch("/api/auth/borrower/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ mobile, otp, name }),
  });
  if (!res.ok) return null;
  return (await res.json()) as BorrowerSession;
}

/**
 * React Query wrapper for the live borrower session.
 *
 * `staleTime: 0` so the displayed identity (name/mobile) is always re-validated
 * against the cookie on mount — it must NEVER be served stale across a
 * logout/login on the same browser (that previously leaked the prior user's
 * name to the next one). The cache is also cleared outright on every auth
 * change ({@link useBorrowerLogout}, login) as the authoritative guarantee.
 */
export function useBorrowerSession() {
  return useQuery({ queryKey: ["borrower-me"], queryFn: fetchBorrowerSession, staleTime: 0 });
}

/**
 * Wipe every borrower-scoped artifact this browser holds, so no identity or PII
 * survives a sign-out and bleeds into the next user on the same device:
 *  - the in-flight application-id pointer,
 *  - the persisted onboarding draft (name / PAN / Aadhaar / bank details) — both
 *    the in-memory zustand state and its `navix.onboarding.draft` localStorage copy,
 *  - any remaining `navix.*` client scratch (settings, last onboarding step, …).
 *
 * The in-memory React Query cache (the cached `borrower-me` session) is cleared
 * separately by {@link useBorrowerLogout}, which has access to the QueryClient.
 *
 * Exported so the login + signup entry points can also call it on a successful
 * auth — a different user can sign in WITHOUT the previous one signing out (an
 * expired cookie, or just "Welcome back"), and the previous user's persisted
 * draft/app-pointer must not carry over.
 */
export function clearBorrowerClientState(): void {
  writeStoredAppId(null);
  if (typeof window === "undefined") return;
  try {
    useOnboardingStore.getState().reset();
    useOnboardingStore.persist?.clearStorage?.();
  } catch {
    /* store may be unavailable during teardown — best-effort */
  }
  try {
    const stale: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (key && key.startsWith("navix.")) stale.push(key);
    }
    stale.forEach((key) => window.localStorage.removeItem(key));
  } catch {
    /* ignore storage access errors */
  }
}

/**
 * Sign the borrower out at the network level: clear the real `navix_borrower`
 * httpOnly cookie (so re-visiting a borrower route no longer resolves a session)
 * and drop all client-side borrower state. UI callers should prefer
 * {@link useBorrowerLogout}, which ALSO flushes the React Query cache and routes
 * to `/login` (without it the cached name lingers until a full reload).
 */
export async function logoutBorrower(): Promise<void> {
  try {
    await fetch("/api/auth/borrower/logout", { method: "POST", credentials: "same-origin" });
  } catch {
    // best-effort — the cookie is httpOnly; the logout route is the way to clear it
  }
  clearBorrowerClientState();
}

/**
 * The sign-out a component should call. Clears the cookie + all client state
 * (above) AND the in-memory React Query cache — which holds the cached
 * `borrower-me` session plus every per-user application/loan/notification query —
 * then routes to `/login`. Clearing the query cache is what makes the header and
 * account menu update immediately (no reload needed) and is what stops the
 * previous user's name from being shown to the next user on the same browser.
 */
export function useBorrowerLogout(): () => Promise<void> {
  const queryClient = useQueryClient();
  const router = useRouter();
  return React.useCallback(async () => {
    await logoutBorrower();
    queryClient.clear();
    router.replace("/login");
  }, [queryClient, router]);
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

/**
 * Pick the borrower's "current" application from their own list (newest-first): the newest one that
 * is still live or carries a loan, skipping dead-ends (cancelled/rejected). Falls back to the newest.
 */
function pickCurrentAppId(apps: ApplicationView[]): number | null {
  if (!apps.length) return null;
  const current = apps.find((a) => !TERMINAL_BAD.includes(a.status)) ?? apps[0];
  return current.id;
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

  // The pointer can only be stale while there is no pointer yet, the pointed-at application hasn't
  // resolved, or it resolved to a dead end (CLOSED / rejected / cancelled / failed) that a newer
  // reborrow could have superseded. While it points at a live in-flight/ACTIVE/OVERDUE application,
  // the one-live-loan rule means nothing newer can exist — don't poll /mine for it.
  const resolvedStatus = appQuery.data?.status;
  const pointerMayBeStale =
    appId == null ||
    resolvedStatus == null ||
    resolvedStatus === "CLOSED" ||
    TERMINAL_BAD.includes(resolvedStatus);

  // Resolve the caller's OWN applications from the ownership-scoped /mine endpoint (shared cache with
  // the header + dashboard, keyed ["my-apps"]). /mine is scoped server-side to the JWT subject, so it
  // can never surface another user's application — unlike a localStorage app-id pointer, which could
  // be stale from a previous user, OR lag behind a just-created reborrow on the same browser.
  const mineQuery = useQuery({
    queryKey: ["my-apps"],
    queryFn: () => borrowerApi.myApplications(),
    // Poll (modest) so branch (b) below still catches a reborrow that has already advanced to ACTIVE —
    // but only while the pointer can actually be stale; a settled live pointer needs no re-listing.
    // Keeps the SAME ["my-apps"] key — the shared header/dashboard cache is refreshed, never forked.
    refetchInterval: pointerMayBeStale ? MINE_POLL_MS : false,
  });
  React.useEffect(() => {
    const mine = mineQuery.data;
    if (!mine || mine.length === 0) return;
    // (a) No stored pointer (fresh login / new device): adopt the current application. Defer to a
    //     localStorage pointer that is still being hydrated this mount (useStoredAppId's mount effect
    //     hasn't run yet) — otherwise we'd briefly clobber a freshly-written id (e.g. a just-created
    //     reborrow) with a /mine pick off the stale shared cache, flashing the prior application.
    if (appId == null) {
      if (readStoredAppId() != null) return;
      const resolved = pickCurrentAppId(mine);
      if (resolved != null) setAppId(resolved);
      return;
    }
    // (b) Stale pointer: a NEWER application (e.g. a reborrow) exists than the one we point at. Move
    //     FORWARD to it (ids are monotonic) so /repay + the status page + dashboard follow the current
    //     advance rather than the prior (closed) one. We re-pick via pickCurrentAppId — the SAME
    //     "newest relevant" selection used in (a) — which skips only dead-ends (cancelled/rejected/
    //     failed) and falls back to the newest. Crucially it does NOT require the target to still be
    //     non-terminal: a reborrow can flip to ACTIVE (a TERMINAL status) faster than we reconcile, and
    //     the old "!TERMINAL" gate skipped exactly that case, stranding /repay on the prior CLOSED loan.
    //     Forward-only (target > appId) — we never switch back to an older application, so a just-created
    //     reborrow written to localStorage earlier this mount is never clobbered by the shared cache.
    const target = pickCurrentAppId(mine);
    if (target != null && target > appId) setAppId(target);
  }, [appId, mineQuery.data, setAppId]);

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
    // Loading while we resolve the pointer from /mine OR while the resolved application loads, so a
    // returning user doesn't flash the "start a new application" state before their own loan appears.
    isLoading: appQuery.isLoading || (appId == null && mineQuery.isLoading),
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
    case "PRE_APPROVED":
      // KYC done (or a pre-approved returning borrower); "APPROVED" prompts the
      // choose-amount step. Once an amount is submitted it's in credit/disbursement
      // review — still shown as approved (the audit trail on /loan/status disambiguates).
      return "APPROVED";
    case "REVIEW_PENDING":
      // Returning borrower with past delinquency, held for KYC re-review.
      return "UNDER_REVIEW";
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

/**
 * The borrower can pick an amount once approved and not yet applied — a fresh borrower after KYC
 * (KYC_APPROVED) or a returning borrower who is pre-approved (PRE_APPROVED, straight to disbursement).
 */
export function canChooseAmount(app: ApplicationView | null | undefined): boolean {
  return (
    !!app &&
    (app.status === "KYC_APPROVED" || app.status === "PRE_APPROVED") &&
    app.amountRequestedPaise == null
  );
}

export function isTerminalBad(app: ApplicationView | null | undefined): boolean {
  return !!app && TERMINAL_BAD.includes(app.status);
}

/** Statuses in which the borrower has no live or in-flight application and may start a new loan. */
const DONE_STATUSES: ApplicationStatus[] = [
  "CLOSED",
  "REJECTED",
  "CANCELLED",
  "KYC_REJECTED",
  "DISBURSEMENT_FAILED",
  "WRITTEN_OFF",
];

/**
 * One advance at a time: true only when the borrower has at least one application and EVERY one of
 * them is in a DONE status — i.e. a returning borrower whose prior advance is fully settled and who
 * has nothing in flight. Requires the list to be loaded and non-empty so the "New loan"/"Borrow
 * again" CTA never flashes while applications are still loading, nor for a brand-new borrower who has
 * no application yet (mid first-time onboarding) — only an in-between-loans borrower may start one.
 */
export function canStartNewLoan(apps: ApplicationView[] | undefined): boolean {
  return !!apps && apps.length > 0 && apps.every((a) => DONE_STATUSES.includes(a.status));
}

/**
 * Where to send a borrower whose attempt to start a *new* advance was blocked because they already
 * have one (the backend "one advance at a time" guard). Maps the guard's error code to the right
 * existing screen, or returns null when the code isn't a live-app block — so the caller can fall back
 * to its own handling (a real error message, or a default route):
 *   - `ACTIVE_APPLICATION` → an unfinished application is in flight → track it on `/loan/status`
 *   - `ACTIVE_LOAN`        → a live advance is still outstanding → repay it first on `/repay`
 */
export function routeForBlockedStart(code: string | undefined): string | null {
  if (code === "ACTIVE_APPLICATION") return "/loan/status";
  if (code === "ACTIVE_LOAN") return "/repay";
  return null;
}

export { TERMINAL as LIVE_TERMINAL_STATUSES };

// ---------------------------------------------------------------------------
// Profile mapping (wizard CustomerProfile -> backend ProfileInput)
// ---------------------------------------------------------------------------

export function buildProfileInput(a: CustomerProfile): ProfileInput {
  const address = [a.addressLine, a.city, a.pin].filter(Boolean).join(", ");
  return {
    fullName: a.fullName?.trim() || undefined,
    pan: a.pan?.trim().toUpperCase() || undefined,
    mobile: a.mobile?.replace(/\D/g, "") || undefined,
    email: a.email?.trim() || undefined,
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
      // fall through and try /mine, then create a new draft
    }
  }
  // No usable local pointer (e.g. onboarding submitted on a fresh device / cleared localStorage):
  // adopt any still-in-flight application from the ownership-scoped /mine list before creating, so we
  // resume it instead of tripping the backend's one-live-application guard (ACTIVE_APPLICATION).
  try {
    const mine = await borrowerApi.myApplications();
    const live = mine.find((a) => !TERMINAL.includes(a.status));
    if (live) {
      writeStoredAppId(live.id);
      return live;
    }
  } catch {
    // /mine unavailable — fall through to create (the server guard still applies)
  }
  const created = await borrowerApi.create(session.customerId);
  writeStoredAppId(created.id);
  return created;
}

/**
 * P5 onboarding: create the DRAFT application early (right after mobile-OTP) so every
 * later step can persist/verify against a real id. Resumes an existing DRAFT if one is
 * already stored (reload-safe); a previously-submitted app is left untouched and a fresh
 * DRAFT is started instead.
 */
export async function createOrResumeDraft(session: BorrowerSession): Promise<ApplicationView> {
  const existing = readStoredAppId();
  if (existing != null) {
    try {
      const app = await borrowerApi.get(existing);
      if (app.status === "DRAFT") return app;
    } catch {
      // fall through and try /mine, then create a fresh draft
    }
  }
  // No usable local pointer (fresh login / new device): consult the ownership-scoped /mine list
  // before creating. Adopt a resumable DRAFT so an abandoned onboarding continues cross-device; a
  // further-along application is deliberately left for the backend guard to reject (createDraft throws
  // ACTIVE_APPLICATION), which the mobile-otp callers route to /dashboard instead of minting a second app.
  try {
    const mine = await borrowerApi.myApplications();
    const draftApp = mine.find((a) => a.status === "DRAFT");
    if (draftApp) {
      writeStoredAppId(draftApp.id);
      return draftApp;
    }
  } catch {
    // /mine unavailable — fall through to create (the server guard still applies)
  }
  const created = await borrowerApi.create(session.customerId);
  writeStoredAppId(created.id);
  return created;
}

/**
 * Submit the completed onboarding form to the real backend:
 * ensure session -> create DRAFT -> save KYC profile -> upload docs -> submit KYC.
 * Persists everything in Postgres and advances the application to KYC_PENDING.
 */
export async function submitOnboarding(
  customer: CustomerProfile,
  docs: Array<{ docType: string; fileName: string; contentType?: string; dataBase64: string }> = [],
): Promise<ApplicationView> {
  const mobile = customer.mobile?.replace(/\s/g, "") || "";
  // The mobile-otp step already established the session with a real OTP; reuse it.
  const session = await ensureBorrowerSession(mobile, undefined, customer.fullName);
  if (!session) {
    throw new ApplicationApiError("Could not establish a borrower session — please sign in again.", "NO_SESSION", 0);
  }

  let app = await resolveApplication(session);

  const profile = buildProfileInput(customer);
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

/**
 * Submit the desired amount once KYC is approved (enters the credit queue). The eligible limit sent
 * to the backend (which both enforces it and stores it) is the explicit {@code eligibleLimitRupees}
 * when given — this is the application's sanctioned limit, already reduced by any current outstanding
 * for a reborrow — and only falls back to the 25%-of-salary figure for a fresh borrower.
 */
export async function applyForAmount(
  appId: number,
  params: {
    amountRupees: number;
    purpose?: string;
    salaryDay?: number;
    monthlySalary?: number;
    eligibleLimitRupees?: number;
  },
): Promise<ApplicationView> {
  const eligibleRupees =
    params.eligibleLimitRupees != null
      ? params.eligibleLimitRupees
      : params.monthlySalary
        ? eligibleLimitRupees(params.monthlySalary)
        : undefined;
  const day = params.salaryDay;
  return borrowerApi.apply(appId, {
    amountPaise: rupeesToPaise(params.amountRupees),
    purpose: params.purpose?.trim() || undefined,
    salaryCreditDay: day != null && day >= 1 && day <= 31 ? day : undefined,
    eligibleLimitPaise: eligibleRupees != null ? rupeesToPaise(eligibleRupees) : undefined,
  });
}
