"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  User,
  ChevronDown,
  LayoutDashboard,
  FileText,
  ArrowLeftRight,
  Wallet,
  PlusCircle,
  LifeBuoy,
  HelpCircle,
  Settings,
  LogOut,
} from "lucide-react";
import { borrowerApi } from "@/lib/api/applications";
import { useBorrowerSession, useBorrowerLogout, canStartNewLoan } from "@/lib/api/live-journey";

type Item = { label: string; href: string; Icon: typeof User };

/** Grouped account links (dividers between groups), matching the borrower nav styling. */
const GROUPS: Item[][] = [
  [
    { label: "Dashboard", href: "/dashboard", Icon: LayoutDashboard },
    { label: "Profile details", href: "/profile", Icon: User },
  ],
  [
    { label: "Past loans", href: "/loans", Icon: FileText },
    { label: "Past transactions", href: "/transactions", Icon: ArrowLeftRight },
  ],
  [
    { label: "Repay / prepay", href: "/repay", Icon: Wallet },
    { label: "Borrow again", href: "/reloan", Icon: PlusCircle },
  ],
  [
    { label: "Support", href: "/support", Icon: LifeBuoy },
    { label: "Help & FAQ", href: "/support#faq", Icon: HelpCircle },
    { label: "Account settings", href: "/settings", Icon: Settings },
  ],
];

/**
 * Top-right borrower account menu: an avatar button that opens a dropdown of the
 * account links plus Sign out. Closes on outside-click and Escape. The borrower
 * name comes from the live (`navix_borrower`) session, falling back to "Account".
 */
export function AccountMenu() {
  const logout = useBorrowerLogout();
  const { data: session } = useBorrowerSession();
  const { data: apps } = useQuery({
    queryKey: ["my-apps"],
    queryFn: borrowerApi.myApplications,
    enabled: !!session,
  });
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef<HTMLDivElement>(null);

  const name = session?.name?.trim() || "Account";
  const firstName = name.split(" ")[0] || "Account";

  // One advance at a time: hide "Borrow again" while a loan/application is live or in flight.
  const allowReborrow = canStartNewLoan(apps);
  const groups = React.useMemo(
    () =>
      GROUPS.map((group) =>
        allowReborrow ? group : group.filter((item) => item.href !== "/reloan"),
      ).filter((group) => group.length > 0),
    [allowReborrow],
  );

  React.useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("mousedown", onClick);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onClick);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const signOut = async () => {
    setOpen(false);
    await logout();
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
        className="flex items-center gap-2 rounded px-2 py-1.5 text-sm font-medium text-ink transition-colors hover:bg-navy-tint hover:text-navy"
      >
        <span className="grid h-8 w-8 place-items-center rounded-full bg-navy text-white">
          <User size={16} />
        </span>
        <span className="hidden max-w-[8rem] truncate sm:inline">{firstName}</span>
        <ChevronDown size={15} className={open ? "rotate-180 transition" : "transition"} />
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Account"
          className="absolute right-0 z-50 mt-2 w-60 overflow-hidden rounded-md border border-line bg-white py-1 shadow-lg"
        >
          <div className="border-b border-line px-4 py-2.5">
            <div className="truncate text-sm font-semibold text-navy">{name}</div>
            {session?.mobile && <div className="truncate text-xs text-muted">{session.mobile}</div>}
          </div>

          {groups.map((group, gi) => (
            <div key={gi} className={gi > 0 ? "border-t border-line py-1" : "py-1"}>
              {group.map(({ label, href, Icon }) => (
                <Link
                  key={href}
                  href={href}
                  role="menuitem"
                  onClick={() => setOpen(false)}
                  className="flex items-center gap-2.5 px-4 py-2 text-sm text-ink transition-colors hover:bg-navy-tint hover:text-navy"
                >
                  <Icon size={15} className="text-muted" /> {label}
                </Link>
              ))}
            </div>
          ))}

          <div className="border-t border-line py-1">
            <button
              type="button"
              role="menuitem"
              onClick={signOut}
              className="flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm text-error-700 transition-colors hover:bg-error-50"
            >
              <LogOut size={15} /> Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
