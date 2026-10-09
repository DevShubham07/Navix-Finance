import type { DashView } from "@/lib/api/applications";
import type { StaffRole } from "@/lib/auth/rbac";

/** Strip order. The dashboard's view toggle is local to the page — it never touches the header role switcher. */
export const ALL_VIEWS: readonly DashView[] = [
  "ADMIN",
  "CREDIT_HEAD",
  "CREDIT_EXECUTIVE",
  "COLLECTION_HEAD",
  "COLLECTION_EXECUTIVE",
  "TELECALLER",
  "DISBURSEMENT_HEAD",
  "ACCOUNTANT",
];

export const VIEW_LABELS: Record<DashView, string> = {
  ADMIN: "Admin overview",
  CREDIT_HEAD: "Credit Head",
  CREDIT_EXECUTIVE: "Credit Executive",
  COLLECTION_HEAD: "Collection Head",
  COLLECTION_EXECUTIVE: "Collection Executive",
  TELECALLER: "Telecaller",
  DISBURSEMENT_HEAD: "Disbursement Head",
  ACCOUNTANT: "Accountant",
};

/**
 * Views a REAL role may toggle between. ADMIN: all eight. A Head: its own + its executive view.
 * Everyone else: only their own (the strip is hidden). DSA: none (no dashboard access at all).
 * Mirrors the backend DashboardScope — the server is the real guard.
 */
export function allowedViews(real: StaffRole): DashView[] {
  switch (real) {
    case "ADMIN":
      return [...ALL_VIEWS];
    case "CREDIT_HEAD":
      return ["CREDIT_HEAD", "CREDIT_EXECUTIVE"];
    case "COLLECTION_HEAD":
      return ["COLLECTION_HEAD", "COLLECTION_EXECUTIVE"];
    case "DSA":
      return [];
    default:
      return [real as DashView];
  }
}

/** A real ADMIN lands on Admin overview; everyone else on their working role (else their real role). */
export function defaultView(real: StaffRole, working: StaffRole): DashView | null {
  const allowed = allowedViews(real);
  if (allowed.length === 0) return null;
  if (real === "ADMIN") return "ADMIN";
  return allowed.includes(working as DashView) ? (working as DashView) : allowed[0];
}

/** The view to render for a `?view=` URL param: the param when allowed, otherwise the default. */
export function resolveView(real: StaffRole, working: StaffRole, requested: string | null | undefined): DashView | null {
  const allowed = allowedViews(real);
  if (requested && allowed.includes(requested as DashView)) return requested as DashView;
  return defaultView(real, working);
}

/** Whether the toggle strip is worth showing. */
export function showsStrip(real: StaffRole): boolean {
  return allowedViews(real).length > 1;
}
