"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { LayoutDashboard, Wallet } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { AccountMenu } from "@/components/app/account-menu";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { borrowerApi } from "@/lib/api/applications";
import { useBorrowerSession, canStartNewLoan } from "@/lib/api/live-journey";

const APP_NAV = [
  { label: "Dashboard", href: "/dashboard", Icon: LayoutDashboard },
  { label: "Repay", href: "/repay", Icon: Wallet },
];

/** Slim borrower app header — brand + authed nav, or sign-in/apply when out. */
export function AppHeader() {
  const pathname = usePathname();
  const { data: session } = useBorrowerSession();
  const { data: apps } = useQuery({
    queryKey: ["my-apps"],
    queryFn: borrowerApi.myApplications,
    enabled: !!session,
  });

  return (
    <header className="sticky top-0 z-40 bg-frame/85 backdrop-blur-md sm:rounded-t-[var(--r-frame)]">
      <div className="container py-2">
        <nav className="nav gap-2" aria-label="Borrower" style={{ minHeight: 64 }}>
          <Brand href={session ? "/dashboard" : "/"} tag="Borrower" className="max-sm:[&_.brand-text]:hidden" />
          {session ? (
            <div className="flex min-w-0 items-center gap-1.5 sm:gap-2">
              {APP_NAV.map(({ label, href, Icon }) => {
                const active = pathname.startsWith(href);
                return (
                  <Link
                    key={href}
                    href={href}
                    aria-current={active ? "page" : undefined}
                    className={`nav-pill max-sm:px-3 ${active ? "is-active" : ""}`}
                  >
                    <Icon size={16} /> <span className="hidden sm:inline">{label}</span>
                  </Link>
                );
              })}
              {canStartNewLoan(apps) && (
                <Link href="/reloan" className="btn btn-gold btn-sm ml-2">
                  New loan
                </Link>
              )}
              <div className="ml-1">
                <NotificationBell scope="borrower" />
              </div>
              <div className="ml-1">
                <AccountMenu />
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <Link href="/login" className="btn btn-outline btn-sm">
                Sign in
              </Link>
              <Link href="/signup/start" className="btn btn-gold btn-sm">
                Apply Now
              </Link>
            </div>
          )}
        </nav>
      </div>
    </header>
  );
}
