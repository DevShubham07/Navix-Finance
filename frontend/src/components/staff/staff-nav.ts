import {
  LayoutDashboard,
  Receipt,
  Workflow,
  HandCoins,
  Users,
  Contact,
  UserX,
  Mail,
  Ban,
  CreditCard,
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
} from "lucide-react";
import { hasPermission, type Permission, type StaffRole } from "@/lib/auth/rbac";
import type { FeatureFlags } from "@/lib/api/applications";
import { SEGMENTS, SEGMENT_LABEL, type CustomerSegment } from "@/lib/customers/segments";
import {
  SEGMENTS as LOAN_SEGMENTS,
  SEGMENT_LABEL as LOAN_SEGMENT_LABEL,
  SEGMENT_TONE as LOAN_SEGMENT_TONE,
  type LoanSegment,
} from "@/lib/loans/segments";
import type { BadgeVariant } from "@/components/ui/badge";

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
  perm?: Permission;
  /** Hide this item when the named dev-controlled feature flag is off (in addition to RBAC). */
  flag?: string;
  /** Optional segment children (Customers, Loans). href for a child = `${parent.href}?seg=${seg}`.
   *  `tone` drives an optional coloured status dot (e.g. loan segments) — omitted for segments
   *  (like the customer ones) that don't carry a status colour. */
  sub?: { label: string; seg: CustomerSegment | LoanSegment; tone?: BadgeVariant }[];
  collectionBuckets?: boolean;
  /** Roles that never see this item even if it has no `perm` (e.g. perm-less items that would
   *  otherwise leak to a firewalled role like DSA). */
  hideFor?: StaffRole[];
};

export type NavGroup = { heading: string; items: NavItem[] };

/** A nav item shows when its RBAC perm passes, its feature flag (if any) is not explicitly off,
 *  and the role isn't explicitly excluded via `hideFor`. */
export function navVisible(it: NavItem, role: StaffRole, flags?: FeatureFlags): boolean {
  if (it.hideFor?.includes(role)) return false;
  if (it.perm && !hasPermission(role, it.perm)) return false;
  if (it.flag && flags?.[it.flag] === false) return false;
  return true;
}

export const NAV: NavGroup[] = [
  {
    heading: "Operations",
    items: [
      { label: "Dashboard", href: "/staff/dashboard", Icon: LayoutDashboard, hideFor: ["DSA"] },
      { label: "Live applications", href: "/staff/applications", Icon: Workflow, perm: "loan:pipeline" },
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
      },
      { label: "Verification Dashboard", href: "/staff/verifications", Icon: ListChecks, perm: "kyc:approve" },
      // No `perm`: every staffer may read their own decision history (the server scopes it).
      { label: "My decisions", href: "/staff/my-decisions", Icon: History, hideFor: ["DSA"] },
      // No `perm`: the server scopes the roster (ADMIN → company, Head → team, else self), so
      // gating the route would only hide a page that already shows the caller their own numbers.
      { label: "Staff performance", href: "/staff/performance", Icon: Gauge, hideFor: ["DSA"] },
      { label: "Leads", href: "/staff/leads", Icon: Phone, perm: "leads:manage" },
      // Every staff role holds leads:import, so this is the one nav entry that shows for all of
      // them — including DSA, whose sidebar is otherwise limited to the DSA portal.
      { label: "Import leads", href: "/staff/leads/import", Icon: Upload, perm: "leads:import" },
      { label: "Telecalling", href: "/staff/telecalling", Icon: Phone, perm: "leads:manage" },
      { label: "Referral payouts", href: "/staff/disbursement/referrals", Icon: Gift, perm: "referral:payout", flag: "referral" },
    ],
  },
  {
    heading: "Collections",
    items: [
      { label: "DPD buckets", href: "/staff/collections", Icon: HandCoins, perm: "collections:interact", collectionBuckets: true },
      // Collection Executive can see settlements they proposed; approve stays PermissionGate-gated.
      { label: "Settlements", href: "/staff/collections/settlements", Icon: HandCoins, perm: "collections:interact" },
      {
        label: "Loans",
        href: "/staff/loans",
        Icon: Landmark,
        perm: "loan:register",
        // No explicit "All" child — same convention as Customers: the parent label itself
        // (no `seg`) is the "all" view, so only the real segments get a chip.
        sub: LOAN_SEGMENTS.map((seg) => ({ label: LOAN_SEGMENT_LABEL[seg], seg, tone: LOAN_SEGMENT_TONE[seg] })),
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
    heading: "Administration",
    items: [
      { label: "Staff", href: "/staff/admin/staff", Icon: Users, perm: "staff:manage" },
      { label: "DSA", href: "/staff/admin/dsa", Icon: Briefcase, perm: "staff:manage" },
      { label: "Invites", href: "/staff/admin/invites", Icon: Mail, perm: "staff:manage" },
      { label: "Blocklist", href: "/staff/admin/blocklist", Icon: Ban, perm: "staff:manage" },
      { label: "Payment settings", href: "/staff/admin/payment-settings", Icon: CreditCard, perm: "staff:manage" },
      { label: "Company expenses", href: "/staff/admin/expenses", Icon: Wallet, perm: "staff:manage" },
      { label: "Leads dashboard", href: "/staff/admin/leads", Icon: Phone, perm: "staff:manage" },
      { label: "All applications", href: "/staff/admin/all-applications", Icon: Files, perm: "staff:manage" },
      { label: "Rejections", href: "/staff/admin/rejections", Icon: Ban, perm: "staff:manage" },
      { label: "Provider API dashboard", href: "/staff/admin/api-dashboard", Icon: ListChecks, perm: "verification:retry" },
      { label: "Transactions", href: "/staff/accounting/transactions", Icon: Receipt, perm: "loan:activate" },
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
