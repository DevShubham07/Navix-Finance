"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
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
  Gift,
  Phone,
  LogOut,
  ChevronRight,
} from "lucide-react";
import { Brand } from "@/components/site/brand";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { hasPermission, STAFF_ROLE_LABELS, type Permission } from "@/lib/auth/rbac";
import { collectionsApi, featureFlagsApi, type FeatureFlags } from "@/lib/api/applications";
import { useStaffSession, signOutStaff } from "@/lib/auth/staff-session";
import { cn } from "@/lib/utils";
import { SEGMENTS, SEGMENT_LABEL, type CustomerSegment } from "@/lib/customers/segments";
import { COLLECTION_BUCKETS, collectionBucketCounts } from "@/lib/collection-buckets";

const PUBLIC_STAFF = ["/staff/login", "/staff/activate", "/staff/forgot-password", "/staff/reset-password"];

const SIDEBAR_WIDTH_KEY = "navix-staff-sidebar-width";
const SIDEBAR_DEFAULT_WIDTH = 240; // px — matches the old fixed `w-60`
const SIDEBAR_MIN_WIDTH = 180;
const SIDEBAR_MAX_WIDTH = 420;

/** Drag-to-resize handle for the sidebar. Persists the chosen width to localStorage
 *  and applies it via the CSS var `--sidebar-w` the <aside> reads (avoids a re-render
 *  on every mousemove pixel). Double-click resets to the default width. */
function SidebarResizer({ asideRef }: { asideRef: React.RefObject<HTMLElement | null> }) {
  const draggingRef = React.useRef(false);

  const applyWidth = React.useCallback((width: number) => {
    const clamped = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, width));
    asideRef.current?.style.setProperty("--sidebar-w", `${clamped}px`);
    return clamped;
  }, [asideRef]);

  React.useEffect(() => {
    const stored = Number(localStorage.getItem(SIDEBAR_WIDTH_KEY));
    if (stored) applyWidth(stored);
  }, [applyWidth]);

  const onPointerDown = (e: React.PointerEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";

    const onMove = (ev: PointerEvent) => {
      if (!draggingRef.current || !asideRef.current) return;
      const left = asideRef.current.getBoundingClientRect().left;
      applyWidth(ev.clientX - left);
    };
    const onUp = () => {
      draggingRef.current = false;
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      const width = asideRef.current?.getBoundingClientRect().width;
      if (width) localStorage.setItem(SIDEBAR_WIDTH_KEY, String(Math.round(width)));
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  const onDoubleClick = () => {
    applyWidth(SIDEBAR_DEFAULT_WIDTH);
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(SIDEBAR_DEFAULT_WIDTH));
  };

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      title="Drag to resize · double-click to reset"
      onPointerDown={onPointerDown}
      onDoubleClick={onDoubleClick}
      className="group absolute -right-1 top-0 hidden h-full w-2 cursor-col-resize lg:block"
    >
      <div className="mx-auto h-full w-px bg-white/10 transition-colors group-hover:bg-gold group-active:bg-gold" />
    </div>
  );
}

type NavItem = {
  label: string;
  href: string;
  Icon: typeof LayoutDashboard;
  perm?: Permission;
  /** Hide this item when the named dev-controlled feature flag is off (in addition to RBAC). */
  flag?: string;
  /** Optional segment children (Customers). href for a child = `${parent.href}?seg=${seg}`. */
  sub?: { label: string; seg: CustomerSegment }[];
  collectionBuckets?: boolean;
};
type NavGroup = { heading: string; items: NavItem[] };

/** A nav item shows when its RBAC perm passes AND its feature flag (if any) is not explicitly off. */
function navVisible(
  it: NavItem,
  role: Parameters<typeof hasPermission>[0],
  flags?: FeatureFlags,
): boolean {
  if (it.perm && !hasPermission(role, it.perm)) return false;
  if (it.flag && flags?.[it.flag] === false) return false;
  return true;
}

const NAV: NavGroup[] = [
  {
    heading: "Operations",
    items: [
      { label: "Dashboard", href: "/staff/dashboard", Icon: LayoutDashboard },
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
      { label: "My decisions", href: "/staff/my-decisions", Icon: History },
      { label: "Leads", href: "/staff/leads", Icon: Phone, perm: "leads:manage" },
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
    ],
  },
  {
    heading: "Administration",
    items: [
      { label: "Staff", href: "/staff/admin/staff", Icon: Users, perm: "staff:manage" },
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

/** Flattened horizontal nav for the mobile strip — ignores `sub` (segment chips live on the page). */
function MobileNavLinks({ role, pathname, flags }: { role: Parameters<typeof hasPermission>[0]; pathname: string; flags?: FeatureFlags }) {
  const items = NAV.flatMap((g) => g.items).filter((it) => navVisible(it, role, flags));
  return (
    <>
      {items.map(({ label, href, Icon }) => {
        const pathOnly = href.split("?")[0];
        const active = pathname === pathOnly || pathname.startsWith(pathOnly + "/");
        return (
          <Link
            key={href}
            href={href}
            className={cn(
              "flex flex-shrink-0 items-center gap-2 whitespace-nowrap rounded px-3 py-2 text-sm transition-colors",
              active
                ? "bg-white/10 font-semibold text-white"
                : "text-navix-200 hover:bg-white/5 hover:text-white",
            )}
          >
            <Icon size={16} className="flex-shrink-0" />
            {label}
          </Link>
        );
      })}
    </>
  );
}

function NavLinks({ role, pathname, onNavigate, flags }: { role: Parameters<typeof hasPermission>[0]; pathname: string; onNavigate?: () => void; flags?: FeatureFlags }) {
  const searchParams = useSearchParams();
  const currentSeg = searchParams.get("seg");
  const currentBucket = searchParams.get("bucket");
  const collectionCases = useQuery({
    queryKey: ["collection-cases"],
    queryFn: collectionsApi.listCases,
    enabled: role === "COLLECTION_HEAD" || role === "COLLECTION_EXECUTIVE" || role === "ADMIN",
    refetchInterval: 8000,
  });
  const bucketCounts = collectionBucketCounts(collectionCases.data ?? []);

  return (
    <>
      {NAV.map((group) => {
        const items = group.items.filter((it) => navVisible(it, role, flags));
        if (!items.length) return null;
        return (
          <div key={group.heading} className="mb-5">
            <p className="px-3 pb-2 text-[0.544rem] font-bold uppercase tracking-wider text-navix-300">{group.heading}</p>
            <ul className="space-y-0.5">
              {items.map((it) => {
                const { label, href, Icon, sub, collectionBuckets } = it;
                const pathOnly = href.split("?")[0];
                const hrefSeg = href.includes("?")
                  ? new URL(href, "http://local").searchParams.get("seg")
                  : null;
                const parentActive = pathname === pathOnly || pathname.startsWith(pathOnly + "/");

                if (collectionBuckets) {
                  return (
                    <li key={href}>
                      <details open={pathname === pathOnly} className="group">
                        <summary className={cn(
                          "flex cursor-pointer list-none items-center gap-3 rounded px-3 py-2 text-sm transition-colors [&::-webkit-details-marker]:hidden",
                          pathname === pathOnly ? "bg-white/10 font-semibold text-white shadow-[inset_3px_0_0_0_var(--gold)]" : "text-navix-200 hover:bg-white/5 hover:text-white",
                        )}>
                          <Icon size={17} className="flex-shrink-0" />
                          <span className="flex-1 truncate">{label}</span>
                          <ChevronRight size={14} className="opacity-60 transition-transform group-open:rotate-90" />
                        </summary>
                        <ul className="ml-4 mt-0.5 space-y-0.5 border-l border-white/10 pl-2">
                          {COLLECTION_BUCKETS.map((item) => (
                            <li key={item.bucket}>
                              <Link href={`${href}?bucket=${item.bucket}`} onClick={onNavigate} className={cn(
                                "flex items-center justify-between rounded px-2 py-1.5 text-xs transition-colors",
                                pathname === pathOnly && currentBucket === item.bucket ? "bg-white/10 font-semibold text-white" : "text-navix-300 hover:bg-white/5 hover:text-white",
                              )}>
                                <span>{item.label}</span>
                                <span className="rounded-full bg-white/10 px-1.5 py-0.5 font-mono text-[8px]">{bucketCounts[item.bucket]}</span>
                              </Link>
                            </li>
                          ))}
                        </ul>
                      </details>
                    </li>
                  );
                }

                if (sub?.length) {
                  return (
                    <li key={href}>
                      <details open={parentActive} className="group">
                        <summary
                          className={cn(
                            "flex cursor-pointer list-none items-center gap-3 rounded px-3 py-2 text-sm transition-colors [&::-webkit-details-marker]:hidden",
                            parentActive
                              ? "bg-white/10 font-semibold text-white shadow-[inset_3px_0_0_0_var(--gold)]"
                              : "text-navix-200 hover:bg-white/5 hover:text-white",
                          )}
                        >
                          <Icon size={17} className="flex-shrink-0" />
                          <Link
                            href={href}
                            onClick={onNavigate}
                            className="flex-1 truncate !text-inherit hover:!text-inherit"
                          >
                            {label}
                          </Link>
                          <ChevronRight size={14} className="flex-shrink-0 opacity-60 transition-transform group-open:rotate-90" />
                        </summary>
                        <ul className="ml-4 mt-0.5 space-y-0.5 border-l border-white/10 pl-2">
                          {sub.map(({ label: sl, seg }) => {
                            const childActive = parentActive && currentSeg === seg;
                            return (
                              <li key={seg}>
                                <Link
                                  href={`${href}?seg=${seg}`}
                                  onClick={onNavigate}
                                  className={cn(
                                    "block rounded px-2 py-1.5 text-xs transition-colors",
                                    childActive
                                      ? "bg-white/10 font-semibold text-white"
                                      : "text-navix-300 hover:bg-white/5 hover:text-white",
                                  )}
                                >
                                  {sl}
                                </Link>
                              </li>
                            );
                          })}
                        </ul>
                      </details>
                    </li>
                  );
                }

                const active = hrefSeg
                  ? pathname === pathOnly && currentSeg === hrefSeg
                  : parentActive && !(pathOnly === "/staff/customers" && currentSeg);

                return (
                  <li key={href}>
                    <Link
                      href={href}
                      onClick={onNavigate}
                      className={cn(
                        "flex items-center gap-3 rounded px-3 py-2 text-sm transition-colors",
                        active
                          ? "bg-white/10 font-semibold text-white shadow-[inset_3px_0_0_0_var(--gold)]"
                          : "text-navix-200 hover:bg-white/5 hover:text-white",
                      )}
                    >
                      <Icon size={17} className="flex-shrink-0" />
                      {label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
    </>
  );
}

export function StaffShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { session, loading } = useStaffSession();
  const isPublic = PUBLIC_STAFF.some((p) => pathname.startsWith(p));
  const asideRef = React.useRef<HTMLElement>(null);

  const { data: flags } = useQuery({
    queryKey: ["feature-flags"],
    queryFn: () => featureFlagsApi.get(),
    enabled: !isPublic && !!session,
    staleTime: 60_000,
  });

  if (isPublic) {
    return <div className="min-h-screen bg-ivory">{children}</div>;
  }

  if (loading) {
    return <div className="grid min-h-screen place-items-center bg-ivory text-muted">Loading console…</div>;
  }

  if (!session) {
    return (
      <div className="grid min-h-screen place-items-center bg-ivory">
        <div className="form-card max-w-sm text-center">
          <h2 className="font-serif text-xl text-navy">Staff sign-in required</h2>
          <p className="mt-2 text-sm text-muted">Your session has ended. Please sign in to continue.</p>
          <Link href="/staff/login" className="btn btn-navy mt-4">Go to staff sign-in</Link>
        </div>
      </div>
    );
  }

  const signOut = async () => {
    await signOutStaff();
    queryClient.clear();
    router.push("/staff/login");
  };

  return (
    <div className="flex min-h-screen bg-ivory">
      <aside
        ref={asideRef}
        style={{ width: "var(--sidebar-w, 240px)" }}
        className="sticky top-0 hidden h-screen flex-shrink-0 flex-col bg-navy-900 lg:flex"
      >
        <div className="border-b border-white/10 px-5 py-4">
          <Brand href="/staff/dashboard" tag="Staff Console" light />
        </div>
        <nav className="flex-1 overflow-y-auto px-3 py-5">
          <React.Suspense fallback={null}>
            <NavLinks role={session.role} pathname={pathname} flags={flags} />
          </React.Suspense>
        </nav>
        <SidebarResizer asideRef={asideRef} />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center justify-between gap-4 border-b border-line bg-white px-4 py-3 lg:px-6">
          <div className="lg:hidden">
            <Brand href="/staff/dashboard" tag="Staff" />
          </div>
          <div className="ml-auto flex items-center gap-2 sm:gap-3">
            <Link href="/staff/profile" className="flex items-center gap-2 rounded-full px-1 py-0.5 hover:bg-grey-100" title="My profile">
              <div className="hidden text-right sm:block">
                <div className="text-sm font-semibold text-ink">{session.name}</div>
                <div className="text-xs text-gold-dark">{STAFF_ROLE_LABELS[session.role]}</div>
              </div>
              <div className="grid h-9 w-9 place-items-center rounded-full bg-navy-tint font-serif font-bold text-navy">
                {session.name.split(" ").map((n) => n[0]).join("").slice(0, 2)}
              </div>
            </Link>
            <NotificationBell scope="staff" />
            <button
              onClick={signOut}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-2 text-sm text-muted hover:bg-grey-100 hover:text-ink"
            >
              <LogOut size={15} /> <span className="hidden sm:inline">Sign out</span>
            </button>
          </div>
        </header>

        <div className="border-b border-line bg-navy-900 lg:hidden">
          <div className="flex gap-1 overflow-x-auto px-2 py-2 [-webkit-overflow-scrolling:touch] [scrollbar-width:none]">
            <MobileNavLinks role={session.role} pathname={pathname} flags={flags} />
          </div>
        </div>

        <main className="navix-crm flex-1 p-3 lg:p-6">{children}</main>
      </div>
    </div>
  );
}
