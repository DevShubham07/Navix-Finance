import {
  LayoutDashboard,
  Receipt,
  Workflow,
  HandCoins,
  Handshake,
  Users,
  Contact,
  UserX,
  Mail,
  Ban,
  Settings,
  Wallet,
  Files,
  ListChecks,
  History,
  Gauge,
  Gift,
  Phone,
  Upload,
  Briefcase,
  Banknote,
  Landmark,
  Repeat,
  FileBarChart,
} from "lucide-react";
import { can, type Permission, type StaffRole } from "@/lib/auth/rbac";
import type { FeatureFlags } from "@/lib/api/applications";
import { SEGMENTS, SEGMENT_LABEL, type CustomerSegment } from "@/lib/customers/segments";
import {
  SEGMENTS as LOAN_SEGMENTS,
  SEGMENT_LABEL as LOAN_SEGMENT_LABEL,
  SEGMENT_TONE as LOAN_SEGMENT_TONE,
  type LoanSegment,
} from "@/lib/loans/segments";
import type { BadgeVariant } from "@/components/ui/badge";
import { REPORT_CATEGORIES, type ReportCategorySeg } from "@/lib/reports/categories";

/**
 * The staff console's navigation, as data.
 *
 * <p>Lifted out of `staff-shell.tsx` so the global-search palette can build its "Pages" results from
 * the SAME array through the SAME {@link navVisible} gate the sidebar uses. Two independently
 * maintained lists would drift, and a drifted copy in a search box is an RBAC leak: it would offer a
 * staffer a page their role cannot open. This module is deliberately free of React hooks and session
 * state so it can also be unit-tested in Vitest's node environment.
 */
export type NavItem = {
  label: string;
  href: string;
  Icon: typeof LayoutDashboard;
  /** Any-of when an array. */
  perm?: Permission | Permission[];
  /** Hide this item when the named dev-controlled feature flag is off (in addition to RBAC). */
  flag?: string;
  /** Optional segment children (Customers, Loans). href for a child = `${parent.href}?seg=${seg}`.
   *  `tone` drives an optional coloured status dot (e.g. loan segments) — omitted for segments
   *  (like the customer ones) that don't carry a status colour. */
  sub?: { label: string; seg: CustomerSegment | LoanSegment | ReportCategorySeg; tone?: BadgeVariant }[];
  collectionBuckets?: boolean;
  /** Roles that never see this item even if it has no `perm` (e.g. perm-less items that would
   *  otherwise leak to a firewalled role like DSA). */
  hideFor?: StaffRole[];
  /** Per working-role label override (e.g. "Live applications" reads "My credit files" for an executive). */
  labelFor?: Partial<Record<StaffRole, string>>;
};

export type NavGroup = { heading: string; items: NavItem[] };

/** A nav item shows when its RBAC perm passes, its feature flag (if any) is not explicitly off,
 *  and the role isn't explicitly excluded via `hideFor`. */
export function navVisible(it: NavItem, realRole: StaffRole, role: StaffRole, flags?: FeatureFlags): boolean {
  if (it.hideFor?.includes(role)) return false;
  if (it.perm && ![it.perm].flat().some((p) => can(realRole, role, p))) return false;
  if (it.flag && flags?.[it.flag] === false) return false;
  return true;
}

/** Sidebar / search label for this working role. */
export function navLabel(item: NavItem, role: StaffRole): string {
  return item.labelFor?.[role] ?? item.label;
}

/** Page title for a nav href: the role's override when it has one, else `fallback`. */
export function pageTitleFor(href: string, role: StaffRole | undefined, fallback: string): string {
  const item = NAV.flatMap((g) => g.items).find((it) => it.href === href);
  return (role && item?.labelFor?.[role]) || fallback;
}

/** Customers (and its segment children) open scoped to "mine" for roles without the whole book. */
export function navHref(href: string, realRole: StaffRole, role: StaffRole): string {
  if (!href.startsWith("/staff/customers") || can(realRole, role, "customer:view:all")) return href;
  return href + (href.includes("?") ? "&" : "?") + "mine=1";
}

/**
 * Grouped by use case — what a staffer is trying to get done — rather than by department, so the
 * same headings read sensibly for every working role. Empty groups (every item gated off) are not
 * rendered, so a Telecaller sees only Overview, Customers & credit and Leads & sales.
 */
export const NAV: NavGroup[] = [
  {
    heading: "Overview",
    items: [
      { label: "Dashboard", href: "/staff/dashboard", Icon: LayoutDashboard, hideFor: ["DSA"] },
      // No `perm`: the server scopes the roster (ADMIN → company, Head → team, else self), so
      // gating the route would only hide a page that already shows the caller their own numbers.
      { label: "Staff performance", href: "/staff/performance", Icon: Gauge, hideFor: ["DSA"] },
      // No `perm`: every staffer may read their own decision history (the server scopes it).
      { label: "My decisions", href: "/staff/my-decisions", Icon: History, hideFor: ["DSA"] },
    ],
  },
  {
    heading: "Customers & credit",
    items: [
      {
        label: "Live applications",
        href: "/staff/applications",
        Icon: Workflow,
        perm: "loan:pipeline",
        labelFor: {
          CREDIT_HEAD: "Credit desk · all files",
          CREDIT_EXECUTIVE: "My credit files",
          DISBURSEMENT_HEAD: "Disbursal queue",
          ACCOUNTANT: "Repayments to verify",
          COLLECTION_HEAD: "Payment approvals",
          COLLECTION_EXECUTIVE: "My repayment book",
        },
      },
      {
        label: "Customers",
        href: "/staff/customers",
        Icon: Contact,
        perm: "customer:view",
        sub: SEGMENTS.map((seg) => ({ label: SEGMENT_LABEL[seg], seg })),
      },
      {
        label: "Unallocated customers",
        href: "/staff/customers?seg=unallocated",
        Icon: UserX,
        perm: "customer:assign",
        hideFor: ["ADMIN"],
      },
      { label: "All applications", href: "/staff/admin/all-applications", Icon: Files, perm: "staff:manage" },
      { label: "Rejections", href: "/staff/admin/rejections", Icon: Ban, perm: "staff:manage" },
      { label: "Onboarding waitlist", href: "/staff/admin/waitlist", Icon: Users, perm: "waitlist:view" },
      { label: "Blocklist", href: "/staff/admin/blocklist", Icon: Ban, perm: "staff:manage" },
    ],
  },
  {
    heading: "Leads & sales",
    items: [
      { label: "Leads", href: "/staff/leads", Icon: Phone, perm: "leads:manage" },
      { label: "Telecalling", href: "/staff/telecalling", Icon: Phone, perm: "leads:manage" },
      { label: "Leads dashboard", href: "/staff/admin/leads", Icon: Phone, perm: "staff:manage" },
      // Every staff role holds leads:import, so this is the one nav entry that shows for all of
      // them — including DSA, whose sidebar is otherwise limited to the DSA portal.
      { label: "Import leads", href: "/staff/leads/import", Icon: Upload, perm: "leads:import" },
      { label: "Referral payouts", href: "/staff/disbursement/referrals", Icon: Gift, perm: "referral:payout", flag: "referral" },
    ],
  },
  {
    heading: "Collections",
    items: [
      {
        label: "Loans",
        href: "/staff/loans",
        Icon: Landmark,
        perm: "loan:register",
        // No explicit "All" child — same convention as Customers: the parent label itself
        // (no `seg`) is the "all" view, so only the real segments get a chip.
        sub: LOAN_SEGMENTS.map((seg) => ({ label: LOAN_SEGMENT_LABEL[seg], seg, tone: LOAN_SEGMENT_TONE[seg] })),
      },
      { label: "DPD buckets (all loans)", href: "/staff/collections", Icon: HandCoins, perm: ["collections:interact", "collections:manage", "collections:overview"], collectionBuckets: true, labelFor: { COLLECTION_EXECUTIVE: "My DPD cases" } },
      // Every eMandate and NACH debit across the book. loan:register is exactly COLLECTION_HEAD + ADMIN — the
      // same pair the backend allowlists (MandateDashboardService). Not flag-gated: debits already taken stay
      // auditable with digio-debit off.
      { label: "Mandates & debits", href: "/staff/collections/mandates", Icon: Repeat, perm: "loan:register" },
      // Collection Executive can see settlements they proposed; approve stays PermissionGate-gated.
      // collections:overview lets the Admin working role read every settlement across the book
      // ("Total settlements") — read-only there; deciding still needs the Collection Head role.
      { label: "Settlements", href: "/staff/collections/settlements", Icon: Handshake, perm: ["collections:interact", "collections:manage", "collections:overview"], labelFor: { ADMIN: "Total settlements", COLLECTION_HEAD: "Settlement approvals", COLLECTION_EXECUTIVE: "My settlements" } },
      // Refunds owed is a cross-book finance view, not case-scoped, so it follows the backend's own
      // COLLECTION_HEAD/ADMIN rule rather than collections:interact.
      // Deliberately NOT flag-gated: if digio-debit is switched off during an incident, the money already
      // taken is still owed back, and this register is the only record of it.
      { label: "Refunds owed", href: "/staff/collections/refunds", Icon: HandCoins, perm: "collections:manage" },
    ],
  },
  {
    heading: "Finance & reports",
    items: [
      { label: "Transactions", href: "/staff/accounting/transactions", Icon: Receipt, perm: ["loan:activate", "staff:manage"] },
      { label: "Company expenses", href: "/staff/admin/expenses", Icon: Wallet, perm: "staff:manage" },
      // Downloadable CSV / PDF reports across the whole platform, one child per report group. staff:manage
      // keeps it to the Admin working role; the backend allows the real ADMIN role only.
      {
        label: "Reports",
        href: "/staff/admin/reports",
        Icon: FileBarChart,
        perm: "staff:manage",
        sub: REPORT_CATEGORIES.map(({ seg, label }) => ({ label, seg })),
      },
    ],
  },
  {
    heading: "DSA",
    items: [
      { label: "My leads", href: "/staff/dsa/leads", Icon: Briefcase, perm: "dsa:portal" },
      { label: "My earnings", href: "/staff/dsa/earnings", Icon: Banknote, perm: "dsa:portal" },
    ],
  },
  {
    heading: "Team & settings",
    items: [
      { label: "Staff", href: "/staff/admin/staff", Icon: Users, perm: "staff:manage" },
      { label: "DSA", href: "/staff/admin/dsa", Icon: Briefcase, perm: "staff:manage" },
      { label: "Invites", href: "/staff/admin/invites", Icon: Mail, perm: "staff:manage" },
      { label: "Provider API dashboard", href: "/staff/admin/api-dashboard", Icon: ListChecks, perm: "verification:retry" },
      { label: "Settings", href: "/staff/admin/settings", Icon: Settings, perm: "staff:manage" },
    ],
  },
];

/** Parent paths that expand into segment children (Customers, Loans, …). A plain "no seg" link that
 *  shares one of these paths (e.g. the bare parent href itself) must not stay highlighted once a
 *  `seg` child is active — derived from NAV so a new segmented entry never needs a second hardcoded
 *  path check alongside it. */
export const SEGMENTED_PARENT_PATHS = new Set(
  NAV.flatMap((g) => g.items).filter((it) => it.sub?.length).map((it) => it.href.split("?")[0]),
);

/** The groups the sidebar renders. Every working role — ADMIN included — gets the same use-case
 *  groups; RBAC and flags decide which items (and so which groups) are visible. */
export function navGroupsFor(_role: StaffRole): NavGroup[] {
  return NAV;
}
