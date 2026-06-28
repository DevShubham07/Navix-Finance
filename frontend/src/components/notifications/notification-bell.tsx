"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Bell, CheckCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import type { NotificationScope, NotificationView } from "@/lib/api/applications";
import {
  useNotifications,
  useUnreadCount,
  useMarkRead,
  useMarkAllRead,
} from "@/lib/api/notifications";

/**
 * Shared in-app notification bell for both surfaces. Pass `scope` and it speaks to the
 * matching BFF namespace (cookie). Renders an icon button with an unread badge and a
 * dropdown of recent notifications; clicking one marks it read and deep-links to the
 * relevant page. Mounted only inside authenticated shells, so the polling queries are
 * always for a signed-in caller. Dropdown closes on outside-click and Escape — the same
 * idiom as the account menu.
 */
export function NotificationBell({ scope }: { scope: NotificationScope }) {
  const router = useRouter();
  const [open, setOpen] = React.useState(false);
  const ref = React.useRef<HTMLDivElement>(null);

  const unread = useUnreadCount(scope);
  const list = useNotifications(scope, { enabled: open });
  const markRead = useMarkRead(scope);
  const markAllRead = useMarkAllRead(scope);

  const count = unread.data ?? 0;
  const items = list.data ?? [];

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

  const onItem = (n: NotificationView) => {
    if (!n.read) markRead.mutate(n.id);
    const href = linkFor(scope, n);
    setOpen(false);
    if (href) router.push(href);
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={count > 0 ? `Notifications (${count} unread)` : "Notifications"}
        className={cn(
          "relative grid h-9 w-9 place-items-center rounded-full transition-colors",
          scope === "staff"
            ? "text-muted hover:bg-grey-100 hover:text-ink"
            : "text-ink hover:bg-navy-tint hover:text-navy",
        )}
      >
        <Bell size={18} />
        {count > 0 && (
          <span className="absolute -right-0.5 -top-0.5 grid min-w-[1.05rem] place-items-center rounded-full bg-error-600 px-1 text-[0.62rem] font-bold leading-[1.05rem] text-white">
            {count > 99 ? "99+" : count}
          </span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Notifications"
          className="absolute right-0 z-50 mt-2 w-80 overflow-hidden rounded-md border border-line bg-white shadow-lg sm:w-96"
        >
          <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
            <div className="text-sm font-semibold text-navy">Notifications</div>
            {count > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                disabled={markAllRead.isPending}
                className="flex items-center gap-1 text-xs font-medium text-muted transition-colors hover:text-navy disabled:opacity-50"
              >
                <CheckCheck size={13} /> Mark all read
              </button>
            )}
          </div>

          <div className="max-h-[22rem] overflow-y-auto">
            {list.isLoading ? (
              <div className="px-4 py-8 text-center text-sm text-muted">Loading…</div>
            ) : list.isError ? (
              <div className="px-4 py-8 text-center text-sm text-error-700">
                Couldn’t load notifications.
              </div>
            ) : items.length === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-muted">
                You’re all caught up.
              </div>
            ) : (
              items.map((n) => (
                <button
                  key={n.id}
                  type="button"
                  role="menuitem"
                  onClick={() => onItem(n)}
                  className={cn(
                    "flex w-full gap-2.5 border-b border-line px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-navy-tint",
                    !n.read && "bg-navy-tint/40",
                  )}
                >
                  <span
                    className={cn(
                      "mt-1.5 h-2 w-2 flex-shrink-0 rounded-full",
                      n.read ? "bg-transparent" : "bg-gold",
                    )}
                    aria-hidden
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold text-ink">{n.title}</span>
                    <span className="mt-0.5 block text-xs leading-snug text-muted line-clamp-2">
                      {n.body}
                    </span>
                    <span className="mt-1 block text-[0.68rem] text-muted/80">
                      {timeAgo(n.createdAt)}
                    </span>
                  </span>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/** Deep-link target for a notification, by scope. Conservative — falls back to a hub page. */
function linkFor(scope: NotificationScope, n: NotificationView): string | null {
  if (scope === "staff") {
    if (n.caseId) return "/staff/collections/buckets";
    if (n.applicationId) return `/staff/applications?focus=${n.applicationId}`;
    if (n.loanId) return "/staff/accounting";
    return null;
  }
  // borrower
  if (n.category === "REPAYMENT" && n.loanId) return "/repay";
  if (n.loanId) return "/dashboard";
  if (n.applicationId) return "/loan/status";
  return null;
}

/** Compact relative time, e.g. "just now", "5m", "3h", "2d", else a short date. */
function timeAgo(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (secs < 45) return "just now";
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(then).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}
