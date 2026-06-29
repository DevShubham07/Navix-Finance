"use client";

import * as React from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import {
  LayoutDashboard,
  ShieldCheck,
  UserCheck,
  ClipboardList,
  Banknote,
  Receipt,
  Workflow,
  PhoneCall,
  HandCoins,
  Users,
  Contact,
  Mail,
  Ban,
  CreditCard,
  Wallet,
  Files,
  LogOut,
} from "lucide-react";
import { Brand } from "@/components/site/brand";
import { NotificationBell } from "@/components/notifications/notification-bell";
import { StaffRoleBar } from "@/components/staff/staff-role-bar";
import { hasPermission, STAFF_ROLE_LABELS, type Permission } from "@/lib/auth/rbac";
import { useStaffSession, signOutStaff } from "@/lib/auth/staff-session";
import { cn } from "@/lib/utils";

const PUBLIC_STAFF = ["/staff/login", "/staff/activate"];

type NavItem = { label: string; href: string; Icon: typeof LayoutDashboard; perm?: Permission };
type NavGroup = { heading: string; items: NavItem[] };

const NAV: NavGroup[] = [
  {
    heading: "Operations",
    items: [
      { label: "Dashboard", href: "/staff/dashboard", Icon: LayoutDashboard },
      { label: "Live applications", href: "/staff/applications", Icon: Workflow },
      { label: "Customers", href: "/staff/customers", Icon: Contact, perm: "customer:view" },
      { label: "KYC Approvals", href: "/staff/kyc-approvals", Icon: ShieldCheck, perm: "kyc:approve" },
      { label: "Reborrow Reviews", href: "/staff/kyc-review", Icon: UserCheck, perm: "kyc:approve" },
      { label: "Credit Queue", href: "/staff/credit/queue", Icon: ClipboardList, perm: "loan:review" },
      { label: "Disbursement", href: "/staff/disbursement", Icon: Banknote, perm: "loan:disburse" },
      { label: "Accounting", href: "/staff/accounting", Icon: Receipt, perm: "loan:activate" },
    ],
  },
  {
    heading: "Collections",
    items: [
      { label: "DPD Buckets", href: "/staff/collections/buckets", Icon: PhoneCall, perm: "collections:interact" },
      { label: "Settlements", href: "/staff/collections/settlements", Icon: HandCoins, perm: "collections:manage" },
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
      { label: "All applications", href: "/staff/admin/all-applications", Icon: Files, perm: "staff:manage" },
      { label: "Transactions", href: "/staff/accounting/transactions", Icon: Receipt, perm: "loan:activate" },
    ],
  },
];

/** Flattened horizontal nav for the mobile strip — icon + label pills that
 *  scroll sideways, no group headings (those only make sense in the sidebar). */
function MobileNavLinks({ role, pathname }: { role: Parameters<typeof hasPermission>[0]; pathname: string }) {
  const items = NAV.flatMap((g) => g.items).filter((it) => !it.perm || hasPermission(role, it.perm));
  return (
    <>
      {items.map(({ label, href, Icon }) => {
        const active = pathname === href || pathname.startsWith(href + "/");
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

function NavLinks({ role, pathname, onNavigate }: { role: Parameters<typeof hasPermission>[0]; pathname: string; onNavigate?: () => void }) {
  return (
    <>
      {NAV.map((group) => {
        const items = group.items.filter((it) => !it.perm || hasPermission(role, it.perm));
        if (!items.length) return null;
        return (
          <div key={group.heading} className="mb-5">
            <p className="px-3 pb-2 text-[0.68rem] font-bold uppercase tracking-wider text-navix-300">{group.heading}</p>
            <ul className="space-y-0.5">
              {items.map(({ label, href, Icon }) => {
                const active = pathname === href || pathname.startsWith(href + "/");
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

  // Login / activation pages: bare, centered, ivory.
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
    // Drop all cached staff queries (queues, customers, ledgers) so the next staffer
    // who signs in on this browser never sees the previous user's cached data.
    queryClient.clear();
    router.push("/staff/login");
  };

  return (
    <div className="flex min-h-screen bg-ivory">
      {/* Sidebar */}
      <aside className="sticky top-0 hidden h-screen w-60 flex-shrink-0 flex-col bg-navy-900 lg:flex">
        <div className="border-b border-white/10 px-5 py-4">
          <Brand href="/staff/dashboard" tag="Staff Console" light />
        </div>
        <nav className="flex-1 overflow-y-auto px-3 py-5">
          <NavLinks role={session.role} pathname={pathname} />
        </nav>
      </aside>

      {/* Main column */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center justify-between gap-4 border-b border-line bg-white px-4 py-3 lg:px-6">
          <div className="lg:hidden">
            <Brand href="/staff/dashboard" tag="Staff" />
          </div>
          <div className="ml-auto flex items-center gap-2 sm:gap-3">
            <div className="hidden text-right sm:block">
              <div className="text-sm font-semibold text-ink">{session.name}</div>
              <div className="text-xs text-gold-dark">{STAFF_ROLE_LABELS[session.role]}</div>
            </div>
            <div className="grid h-9 w-9 place-items-center rounded-full bg-navy-tint font-serif font-bold text-navy">
              {session.name.split(" ").map((n) => n[0]).join("").slice(0, 2)}
            </div>
            <NotificationBell scope="staff" />
            <button
              onClick={signOut}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-2 text-sm text-muted hover:bg-grey-100 hover:text-ink"
            >
              <LogOut size={15} /> <span className="hidden sm:inline">Sign out</span>
            </button>
          </div>
        </header>

        {/* Mobile nav strip — flat, horizontally scrollable */}
        <div className="border-b border-line bg-navy-900 lg:hidden">
          <div className="flex gap-1 overflow-x-auto px-2 py-2 [-webkit-overflow-scrolling:touch] [scrollbar-width:none]">
            <MobileNavLinks role={session.role} pathname={pathname} />
          </div>
        </div>

        <main className="flex-1 p-4 lg:p-8">{children}</main>
      </div>

      <StaffRoleBar />
    </div>
  );
}
