"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { LogOut, LayoutDashboard, Wallet, User } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { useBorrowerSession, signOutBorrower } from "@/lib/mock/session";

const APP_NAV = [
  { label: "Dashboard", href: "/dashboard", Icon: LayoutDashboard },
  { label: "Repay", href: "/repay", Icon: Wallet },
  { label: "Profile", href: "/profile", Icon: User },
];

/** Slim borrower app header — brand + authed nav, or sign-in/apply when out. */
export function AppHeader() {
  const pathname = usePathname();
  const router = useRouter();
  const { session } = useBorrowerSession();

  const signOut = () => {
    signOutBorrower();
    router.push("/login");
  };

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
              <Link href="/loan/apply" className="btn btn-gold btn-sm ml-2">
                New loan
              </Link>
              <button
                onClick={signOut}
                className="ml-1 flex items-center gap-2 rounded px-3 py-2 text-sm font-medium text-muted hover:bg-grey-100 hover:text-ink"
                aria-label="Sign out"
              >
                <LogOut size={16} /> <span className="hidden sm:inline">Sign out</span>
              </button>
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
