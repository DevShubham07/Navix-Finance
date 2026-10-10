"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LogOut, ChevronRight } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { GlobalSearch } from "@/components/staff/global-search";
import { Toaster } from "@/components/ui/toast";
import { can, STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import { collectionsApi, featureFlagsApi, type FeatureFlags } from "@/lib/api/applications";
import { useStaffSession, signOutStaff } from "@/lib/auth/staff-session";
import { RoleSwitcher } from "@/components/staff/role-switcher";
import { clearWorkingRole } from "@/lib/auth/working-role";
import { clearRecent } from "@/lib/staff/search-recents";
import { cn } from "@/lib/utils";
import { UserChip } from "@/components/kit/nav";
import { NAV, navHref, navVisible, SEGMENTED_PARENT_PATHS } from "@/components/staff/staff-nav";
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
      <div className="mx-auto h-full w-px bg-transparent transition-colors group-hover:bg-gold group-active:bg-gold" />
    </div>
  );
}

/** Flattened horizontal nav for the mobile strip — ignores `sub` (segment chips live on the page). */
function MobileNavLinks({ realRole, role, pathname, flags }: { realRole: StaffRole; role: StaffRole; pathname: string; flags?: FeatureFlags }) {
  const items = NAV.flatMap((g) => g.items).filter((it) => navVisible(it, realRole, role, flags));
  return (
    <>
      {items.map(({ label, href: rawHref, Icon }) => {
        const href = navHref(rawHref, realRole, role);
        const pathOnly = href.split("?")[0];
        const active = pathname === pathOnly || pathname.startsWith(pathOnly + "/");
        return (
          <Link
            key={href}
            href={href}
            className={cn(
              "flex flex-shrink-0 items-center gap-2 whitespace-nowrap rounded-full px-3.5 py-2 text-sm transition-colors",
              active
                ? "bg-navy font-medium text-white shadow-sm"
                : "bg-paper text-slate shadow-pill hover:text-ink",
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

function NavLinks({ realRole, role, pathname, onNavigate, flags }: { realRole: StaffRole; role: StaffRole; pathname: string; onNavigate?: () => void; flags?: FeatureFlags }) {
  const searchParams = useSearchParams();
  const currentSeg = searchParams.get("seg");
  const currentBucket = searchParams.get("bucket");
  // The same worklist the bucket pages render, so a sidebar badge can never disagree with the page
  // it links to (it used to count cases, while the page now counts loans).
  const collectionCases = useQuery({
    // 30s, not 8s: this badge rides along on EVERY staff page for the collection roles + ADMIN, and
    // all it renders is six bucket integers. The collections page itself observes the same
    // `["collections-worklist"]` key at its own faster interval while it is open, and React Query
    // takes the shortest interval across observers — so the register stays live where it matters
    // and only the ambient background cost drops.
    queryKey: ["collections-worklist"],
    queryFn: collectionsApi.worklist,
    enabled: can(realRole, role, "collections:manage") || can(realRole, role, "collections:interact"),
    refetchInterval: 30_000,
  });
  const bucketCounts = collectionBucketCounts(collectionCases.data ?? []);

  return (
    <>
      {NAV.map((group) => {
        const items = group.items.filter((it) => navVisible(it, realRole, role, flags));
        if (!items.length) return null;
        return (
          <div key={group.heading} className="mb-5">
            <p className="px-3 pb-2 text-[0.544rem] font-semibold uppercase tracking-[.14em] text-muted">{group.heading}</p>
            <ul className="space-y-0.5">
              {items.map((it) => {
                const { label, Icon, sub, collectionBuckets } = it;
                const href = navHref(it.href, realRole, role);
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
                          "flex cursor-pointer list-none items-center gap-3 rounded-full px-3.5 py-2 text-sm transition-all [&::-webkit-details-marker]:hidden",
                          pathname === pathOnly ? "bg-navy font-medium text-white shadow-sm" : "text-slate hover:bg-paper hover:text-ink hover:shadow-pill",
                        )}>
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
                        <ul className="ml-5 mt-1 space-y-0.5 border-l border-line pl-2">
                          {COLLECTION_BUCKETS.map((item) => (
                            <li key={item.bucket}>
                              <Link href={`${href}?bucket=${item.bucket}`} onClick={onNavigate} className={cn(
                                "flex items-center justify-between rounded-full px-2.5 py-1.5 text-xs transition-colors",
                                pathname === pathOnly && currentBucket === item.bucket ? "bg-paper font-medium text-ink shadow-pill" : "text-muted hover:bg-paper hover:text-ink",
                              )}>
                                <span>{item.label}</span>
                                <span className="rounded-full bg-grey-200 px-1.5 py-0.5 font-mono text-[8px] text-slate">{bucketCounts[item.bucket]}</span>
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
                            "flex cursor-pointer list-none items-center gap-3 rounded-full px-3.5 py-2 text-sm transition-all [&::-webkit-details-marker]:hidden",
                            parentActive
                              ? "bg-navy font-medium text-white shadow-sm"
                              : "text-slate hover:bg-paper hover:text-ink hover:shadow-pill",
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
                        <ul className="ml-5 mt-1 space-y-0.5 border-l border-line pl-2">
                          {sub.map(({ label: sl, seg, tone }) => {
                            const childActive = parentActive && currentSeg === seg;
                            return (
                              <li key={seg}>
                                <Link
                                  href={`${href}?seg=${seg}`}
                                  onClick={onNavigate}
                                  className={cn(
                                    "flex items-center gap-1.5 rounded-full px-2.5 py-1.5 text-xs transition-colors",
                                    childActive
                                      ? "bg-paper font-medium text-ink shadow-pill"
                                      : "text-muted hover:bg-paper hover:text-ink",
                                  )}
                                >
                                  {/* Status dot only for segments carrying a success/error tone (e.g.
                                      loan Active/Overdue) — Closed/All and Customers' segments stay undotted. */}
                                  {(tone === "success" || tone === "error") && (
                                    <span
                                      aria-hidden
                                      className={cn(
                                        "h-1.5 w-1.5 flex-shrink-0 rounded-full",
                                        tone === "success" ? "bg-success-500" : "bg-error-500",
                                      )}
                                    />
                                  )}
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

                // A plain link whose own href carries no `seg` (e.g. "Unallocated customers") must
                // NOT stay highlighted merely because it shares `pathOnly` with a segmented parent
                // (Customers, Loans, …) that has an active `seg` child — that child owns the
                // highlight alone. Driven by `SEGMENTED_PARENT_PATHS`, not a hardcoded path string.
                const active = hrefSeg
                  ? pathname === pathOnly && currentSeg === hrefSeg
                  : parentActive && !(SEGMENTED_PARENT_PATHS.has(pathOnly) && currentSeg);

                return (
                  <li key={href}>
                    <Link
                      href={href}
                      onClick={onNavigate}
                      className={cn(
                        "flex items-center gap-3 rounded-full px-3.5 py-2 text-sm transition-all",
                        active
                          ? "bg-navy font-medium text-white shadow-sm"
                          : "text-slate hover:bg-paper hover:text-ink hover:shadow-pill",
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

  // Portalled dialogs/drawers/search live outside `.navix-crm`; this body flag lets the black-text rule reach them.
  const authed = !isPublic && !loading && !!session;
  React.useEffect(() => {
    if (!authed) return;
    document.body.classList.add("staff-console");
    return () => document.body.classList.remove("staff-console");
  }, [authed]);

  if (isPublic) {
    return <div className="app-backdrop">{children}</div>;
  }

  if (loading) {
    return <div className="app-backdrop grid place-items-center text-muted">Loading console…</div>;
  }

  if (!session) {
    return (
      <div className="app-backdrop grid place-items-center">
        <div className="form-card max-w-sm text-center">
          <h1 className="text-3xl">Staff sign-in required</h1>
          <p className="mt-2 text-sm text-muted">Your session has ended. Please sign in to continue.</p>
          <Link href="/staff/login" className="btn btn-navy mt-4">Go to staff sign-in</Link>
        </div>
      </div>
    );
  }

  const signOut = async () => {
    // Drop this operator's search history before the session goes: on a shared console the next
    // person must not inherit the mobiles and PANs the last one was looking up. Done here, where
    // the id is already in hand, rather than inside `signOutStaff` — that would have cost the
    // sign-out path an extra round-trip just to re-read an id the shell already has.
    clearRecent(session.id);
    clearWorkingRole(session.id);
    await signOutStaff();
    queryClient.clear();
    router.push("/staff/login");
  };

  return (
    <div className="app-backdrop">
      <div className="app-frame flex">
        <aside
          ref={asideRef}
          style={{ width: "var(--sidebar-w, 240px)" }}
          className="sticky top-[14px] hidden h-[calc(100dvh-28px)] flex-shrink-0 flex-col border-r border-line lg:flex"
        >
          <div className="px-5 pb-3 pt-5">
            <Brand href="/staff/dashboard" tag="Staff Console" />
          </div>
          <nav className="flex-1 overflow-y-auto px-3 py-4 [scrollbar-width:thin]">
            <React.Suspense fallback={null}>
              <NavLinks realRole={session.realRole} role={session.role} pathname={pathname} flags={flags} />
            </React.Suspense>
          </nav>
          <SidebarResizer asideRef={asideRef} />
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="staff-header sticky top-0 z-30 flex items-center justify-between gap-2 bg-frame/85 px-4 py-3 backdrop-blur-md sm:rounded-tr-[var(--r-frame)] lg:px-7 lg:pt-4">
            <div className="lg:hidden">
              <Brand href="/staff/dashboard" tag="Staff" className="max-sm:[&_.brand-text]:hidden" />
            </div>
            <div className="ml-auto flex min-w-0 items-center gap-1.5 sm:gap-3">
              {/* DSA is firewalled from customer/application/loan data, so it gets no palette at all
                  (the endpoint rejects it too). `global-search` is the dev-only kill switch. */}
              {session.role !== "DSA" && flags?.["global-search"] !== false && (
                <GlobalSearch realRole={session.realRole} role={session.role} staffId={session.id} flags={flags} />
              )}
              <NotificationBell scope="staff" />
              <RoleSwitcher staffId={session.id} realRole={session.realRole} role={session.role} />
              <Link
                href="/staff/profile"
                title={`${session.name} · ${STAFF_ROLE_LABELS[session.realRole]}`}
                aria-label={`${session.name} · ${STAFF_ROLE_LABELS[session.realRole]}`}
                className="rounded-full"
              >
                <UserChip name={session.name} role={STAFF_ROLE_LABELS[session.realRole]} />
              </Link>
              <button
                onClick={signOut}
                title="Sign out"
                aria-label="Sign out"
                className="icon-pill text-muted hover:text-ink"
              >
                <LogOut size={16} />
              </button>
            </div>
          </header>

          <div className="lg:hidden">
            <div className="flex gap-1 overflow-x-auto px-2 py-2 [-webkit-overflow-scrolling:touch] [scrollbar-width:none]">
              <MobileNavLinks realRole={session.realRole} role={session.role} pathname={pathname} flags={flags} />
            </div>
          </div>

          <main className="navix-crm flex-1 px-3 pb-6 pt-1 lg:px-7 lg:pb-8">{children}</main>
        </div>
      </div>
      {/* The one mount point for `toast.*` across the console. Without it every toast call is a
          silent no-op — the store queues the message and nothing renders it. It portals to
          document.body itself, so its position in this tree does not matter. */}
      <Toaster />
    </div>
  );
}
