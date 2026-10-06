/**
 * Pure logic behind the `/staff/verifications` dashboard and its checks panel: rolling verification
 * rows up into one card per application, the triage buckets, "last checked … ago", the retry
 * in-flight guard and the provider label. Kept out of the components so it can be unit-tested.
 */

import {
  ApplicationApiError,
  VerificationRetryTimeoutError,
  type VerificationNotStarted,
  type VerificationOverviewRow,
} from "@/lib/api/applications";

/** The four application-wise buckets, in triage priority order. */
export type VerificationBucket = "failures" | "awaiting" | "passed" | "notStarted";

/**
 * Application statuses this dashboard triages — mirrors `ApplicationVerificationService.DASHBOARD_STATUSES`.
 * The files still awaiting a KYC decision, plus SANCTIONED ones still walking the offer journey: their
 * DigiLocker / selfie / address / eSign checks run after the credit decision and never block the borrower,
 * so a failure there surfaces only here — and this is where staff send the "redo this step" link from.
 * Rows for anything past disbursal (or rejected, closed…) are historical evidence, not work, and would
 * pollute the buckets forever.
 */
export const UNDECIDED_STATUSES: readonly string[] = ["DRAFT", "KYC_PENDING", "REVIEW_PENDING", "SANCTIONED"];

/**
 * The checks a borrower must clear (PASS/REVIEW) before this dashboard calls an application "all
 * checks passed" — the union of the backend's two gates: `ApplicationVerificationService.REQUIRED`
 * (PAN, EMAIL, BUREAU, SALARY, gating submit-kyc) and `REQUIRED_SANCTION` (AADHAAR, SELFIE, ADDRESS,
 * ESIGN, gating sanction). PENNY_DROP gates nothing server-side; it stays here only so a dashboard
 * card isn't marked "all checks passed" ahead of the penny-drop step later in the offer journey.
 * Needed so an application whose required checks were never RUN (no row at all) isn't mistaken for
 * "all checks passed" just because the few rows it does have are green.
 */
export const REQUIRED_CHECKS: readonly string[] = [
  "PAN", "EMAIL", "ADDRESS", "AADHAAR", "BUREAU", "SALARY", "PENNY_DROP", "SELFIE",
];

/**
 * Checks that inform the credit decision but gate nothing, and so must be kept out of the bucket
 * maths. Mirrored by the backend's `OVERVIEW_NON_GATING` — the two must agree, or the server's
 * default "needs attention" page and the cards drawn from it would disagree about the same file.
 *
 * EMPLOYMENT is the EPFO/UAN lookup. It can never return PASS for a borrower the EPFO has no record
 * of — a first job, a cash employer, a non-PF establishment all land in REVIEW legitimately — and it
 * is deliberately absent from the backend's REQUIRED set for exactly that reason. Counting it in
 * `pendingReview` would drag a large share of otherwise-clean files out of "All checks passed" and
 * into "Awaiting borrower steps", where there is no borrower step to take. It gets its own chip on
 * the card instead: visible, not gating.
 *
 * DIGILOCKER, AGREEMENT and ESIGN are the other three types the backend recognises outside
 * {@link REQUIRED_CHECKS}. Excluding them keeps the card's fraction a stable, comparable X/8.
 */
export const NON_GATING_CHECKS: readonly string[] = ["EMPLOYMENT", "DIGILOCKER", "AGREEMENT", "ESIGN"];

/** One application rolled up from its verification rows (or a server-reported "Not started" file). */
export interface VerificationAppCard {
  applicationId: number;
  customerId: number | null;
  borrowerName: string | null;
  borrowerMobile: string | null;
  total: number;
  passed: number;
  failed: number;
  pendingReview: number;
  /** EPFO/UAN outcome, shown as its own chip. Advisory — see {@link NON_GATING_CHECKS}. */
  employmentStatus: string | null;
  /** Newest `updatedAt` across ALL of the application's rows (non-gating included). */
  lastUpdate: string | null;
  bucket: VerificationBucket;
}

/**
 * One card per application. Cards for applications with rows come from `rows` (one page of the
 * server's overview); "Not started" cards come only from the server's `notStarted` list, which is
 * computed over the whole undecided queue. The page used to synthesise that bucket itself — every
 * KYC_PENDING file not on the current page — and so called a file whose checks sat on another page,
 * or that was fully cleared, "never verified".
 */
export function buildVerificationCards(
  rows: readonly VerificationOverviewRow[],
  notStarted: readonly VerificationNotStarted[],
): VerificationAppCard[] {
  // Only applications still awaiting a decision. (applicationStatus is null on rows from before the
  // field existed; treat those as undecided rather than silently hiding them.)
  const scoped = rows.filter(
    (r) => r.applicationStatus == null || UNDECIDED_STATUSES.includes(r.applicationStatus),
  );
  const byApp = new Map<number, VerificationOverviewRow[]>();
  for (const r of scoped) {
    const list = byApp.get(r.applicationId) ?? [];
    list.push(r);
    byApp.set(r.applicationId, list);
  }
  const out: VerificationAppCard[] = [];
  for (const [applicationId, checks] of byApp) {
    const gating = checks.filter((c) => !NON_GATING_CHECKS.includes(c.checkType));
    const employment = checks.find((c) => c.checkType === "EMPLOYMENT") ?? null;
    const failed = gating.filter((c) => c.status === "FAIL").length;
    const passed = gating.filter((c) => c.status === "PASS").length;
    // Required checks with no recorded row at all are still outstanding borrower work — count
    // them as pending so a barely-started application can't read as "all checks passed".
    const cleared = new Set(
      gating.filter((c) => c.status === "PASS" || c.status === "REVIEW").map((c) => c.checkType),
    );
    const recorded = new Set(gating.map((c) => c.checkType));
    const missingRequired = REQUIRED_CHECKS.filter((t) => !recorded.has(t)).length;
    const pendingReview =
      gating.filter((c) => c.status === "PENDING" || c.status === "REVIEW").length + missingRequired;
    const requiredCleared = REQUIRED_CHECKS.every((t) => cleared.has(t));
    const lastUpdate = checks.reduce<string | null>(
      (acc, c) => (c.updatedAt && (!acc || c.updatedAt > acc) ? c.updatedAt : acc),
      null,
    );
    const bucket: VerificationBucket =
      failed > 0 ? "failures" : !requiredCleared || pendingReview > 0 ? "awaiting" : "passed";
    out.push({
      applicationId,
      customerId: checks.find((c) => c.customerId != null)?.customerId ?? null,
      borrowerName: checks.find((c) => c.borrowerName != null)?.borrowerName ?? null,
      borrowerMobile: checks.find((c) => c.borrowerMobile != null)?.borrowerMobile ?? null,
      total: gating.length + missingRequired,
      employmentStatus: employment?.status ?? null,
      passed,
      failed,
      pendingReview,
      lastUpdate,
      bucket,
    });
  }

  // The server only lists files with zero rows, so these never collide with the cards above; the
  // guard is belt-and-braces against a row landing between the two halves of one response.
  for (const n of notStarted) {
    if (byApp.has(n.applicationId)) continue;
    out.push({
      applicationId: n.applicationId,
      customerId: n.customerId,
      borrowerName: n.borrowerName,
      borrowerMobile: n.borrowerMobile,
      total: 0,
      passed: 0,
      failed: 0,
      pendingReview: 0,
      employmentStatus: null,
      lastUpdate: null,
      bucket: "notStarted",
    });
  }
  return out;
}

/**
 * Cards by bucket, newest activity first within each. "Not started" cards carry no timestamp, so the
 * stable sort keeps the server's newest-first order for them.
 */
export function groupVerificationCards(
  cards: readonly VerificationAppCard[],
): Record<VerificationBucket, VerificationAppCard[]> {
  const g: Record<VerificationBucket, VerificationAppCard[]> = {
    failures: [],
    awaiting: [],
    passed: [],
    notStarted: [],
  };
  for (const c of cards) g[c.bucket].push(c);
  for (const k of Object.keys(g) as VerificationBucket[]) {
    g[k].sort((a, b) => (b.lastUpdate ?? "").localeCompare(a.lastUpdate ?? ""));
  }
  return g;
}

/**
 * "2 h ago" for the time elapsed between `iso` and `nowMs`. Elapsed time between two instants does
 * not depend on a timezone, so this needs no IST handling: "d" is whole 24-hour periods, not
 * calendar days. A future timestamp (clock skew) reads "just now"; an unparseable one, null.
 */
export function formatCheckedAgo(iso: string | null | undefined, nowMs: number): string | null {
  if (!iso) return null;
  const at = Date.parse(iso);
  if (!Number.isFinite(at) || !Number.isFinite(nowMs)) return null;
  const secs = Math.max(0, Math.floor((nowMs - at) / 1000));
  if (secs < 60) return "just now";
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} h ago`;
  return `${Math.floor(hrs / 24)} d ago`;
}

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
