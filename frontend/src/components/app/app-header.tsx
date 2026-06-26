"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutDashboard, Wallet } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { AccountMenu } from "@/components/app/account-menu";
import { useBorrowerSession } from "@/lib/mock/session";

const APP_NAV = [
  { label: "Dashboard", href: "/dashboard", Icon: LayoutDashboard },
  { label: "Repay", href: "/repay", Icon: Wallet },
];

/** Slim borrower app header — brand + authed nav, or sign-in/apply when out. */
export function AppHeader() {
  const pathname = usePathname();
  const { session } = useBorrowerSession();

  return (
    <header className="site-header">
      <div className="container">
        <nav className="nav" aria-label="Borrower" style={{ minHeight: 64 }}>
          <Brand href={session ? "/dashboard" : "/"} tag="Borrower" />
          {session ? (
            <div className="flex items-center gap-1">
              {APP_NAV.map(({ label, href, Icon }) => {
                const active = pathname.startsWith(href);
                return (
                  <Link
                    key={href}
                    href={href}
                    className={`flex items-center gap-2 rounded px-3 py-2 text-sm font-medium transition-colors ${
                      active ? "bg-navy-tint text-navy" : "text-ink hover:bg-navy-tint hover:text-navy"
                    }`}
                  >
                    <Icon size={16} /> <span className="hidden sm:inline">{label}</span>
                  </Link>
                );
              })}
              <Link href="/signup/pan" className="btn btn-gold btn-sm ml-2">
                New loan
              </Link>
              <div className="ml-1">
                <AccountMenu />
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <Link href="/login" className="btn btn-outline btn-sm">
                Sign in
              </Link>
              <Link href="/signup/pan" className="btn btn-gold btn-sm">
                Apply Now
              </Link>
            </div>
          )}
        </nav>
      </div>
    </header>
  );
}
