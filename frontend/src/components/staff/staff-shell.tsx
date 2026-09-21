"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { LogOut, ChevronRight } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { GlobalSearch } from "@/components/staff/global-search";
import { STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import { collectionsApi, featureFlagsApi, type FeatureFlags } from "@/lib/api/applications";
import { useStaffSession, signOutStaff } from "@/lib/auth/staff-session";
import { clearRecent } from "@/lib/staff/search-recents";
import { cn } from "@/lib/utils";
import { NAV, navVisible, SEGMENTED_PARENT_PATHS } from "@/components/staff/staff-nav";
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

/** Flattened horizontal nav for the mobile strip — ignores `sub` (segment chips live on the page). */
function MobileNavLinks({ role, pathname, flags }: { role: StaffRole; pathname: string; flags?: FeatureFlags }) {
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

function NavLinks({ role, pathname, onNavigate, flags }: { role: StaffRole; pathname: string; onNavigate?: () => void; flags?: FeatureFlags }) {
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
    enabled: role === "COLLECTION_HEAD" || role === "COLLECTION_EXECUTIVE" || role === "ADMIN",
    refetchInterval: 30_000,
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
                          {sub.map(({ label: sl, seg, tone }) => {
                            const childActive = parentActive && currentSeg === seg;
                            return (
                              <li key={seg}>
                                <Link
                                  href={`${href}?seg=${seg}`}
                                  onClick={onNavigate}
                                  className={cn(
                                    "flex items-center gap-1.5 rounded px-2 py-1.5 text-xs transition-colors",
                                    childActive
                                      ? "bg-white/10 font-semibold text-white"
                                      : "text-navix-300 hover:bg-white/5 hover:text-white",
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
    // Drop this operator's search history before the session goes: on a shared console the next
    // person must not inherit the mobiles and PANs the last one was looking up. Done here, where
    // the id is already in hand, rather than inside `signOutStaff` — that would have cost the
    // sign-out path an extra round-trip just to re-read an id the shell already has.
    clearRecent(session.id);
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
            {/* DSA is firewalled from customer/application/loan data, so it gets no palette at all
                (the endpoint rejects it too). `global-search` is the dev-only kill switch. */}
            {session.role !== "DSA" && flags?.["global-search"] !== false && (
              <GlobalSearch role={session.role} staffId={session.id} flags={flags} />
            )}
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
