"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import {
  Banknote,
  Briefcase,
  Calculator,
  Check,
  ChevronDown,
  ClipboardCheck,
  Headset,
  PhoneCall,
  Shield,
  ShieldCheck,
  UsersRound,
  type LucideIcon,
} from "lucide-react";
import { toast } from "@/components/ui";
import { ROLE_META, STAFF_ROLE_LABELS, workingRolesFor, type StaffRole } from "@/lib/auth/rbac";
import { writeWorkingRole } from "@/lib/auth/working-role";
import { cn } from "@/lib/utils";

export const ROLE_ICON: Record<StaffRole, LucideIcon> = {
  CREDIT_HEAD: ShieldCheck,
  CREDIT_EXECUTIVE: ClipboardCheck,
  DISBURSEMENT_HEAD: Banknote,
  ACCOUNTANT: Calculator,
  COLLECTION_HEAD: UsersRound,
  COLLECTION_EXECUTIVE: PhoneCall,
  TELECALLER: Headset,
  DSA: Briefcase,
  ADMIN: Shield,
};

const PILL = "inline-flex h-[38px] items-center gap-1.5 rounded-full border border-line bg-paper px-3.5 shadow-pill";

/** Header pill showing the working role; a menu to switch when the real role allows more than one. */
export function RoleSwitcher({ staffId, realRole, role }: { staffId: string; realRole: StaffRole; role: StaffRole }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const roles = workingRolesFor(realRole);
  const [open, setOpen] = React.useState(false);
  const wrapRef = React.useRef<HTMLDivElement>(null);
  const pillRef = React.useRef<HTMLButtonElement>(null);
  const itemRefs = React.useRef<(HTMLButtonElement | null)[]>([]);
  const Icon = ROLE_ICON[role];
  const label = STAFF_ROLE_LABELS[role];

  const focusItem = (i: number) => {
    const n = roles.length;
    itemRefs.current[((i % n) + n) % n]?.focus();
  };

  React.useEffect(() => {
    if (!open) return;
    focusItem(Math.max(0, roles.indexOf(role)));
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  if (roles.length === 1) {
    return (
      <span className={PILL}>
        <Icon size={15} className="text-navy" aria-hidden />
        <span className="hidden text-sm font-semibold text-navy sm:inline">{label}</span>
      </span>
    );
  }

  const close = () => {
    setOpen(false);
    pillRef.current?.focus();
  };

  const select = (r: StaffRole) => {
    if (r === role) return close();
    writeWorkingRole(staffId, realRole, r);
    void queryClient.invalidateQueries();
    router.push("/staff/dashboard");
    toast.success("Now working as " + STAFF_ROLE_LABELS[r]);
    setOpen(false);
  };

  // Enter/Space/click toggle natively; ArrowDown opens too.
  const onPillKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOpen(true);
    }
  };

  const onMenuKey = (e: React.KeyboardEvent) => {
    const cur = itemRefs.current.findIndex((el) => el === document.activeElement);
    if (e.key === "Escape") { e.preventDefault(); close(); }
    else if (e.key === "ArrowDown") { e.preventDefault(); focusItem(cur + 1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); focusItem(cur - 1); }
    else if (e.key === "Home") { e.preventDefault(); focusItem(0); }
    else if (e.key === "End") { e.preventDefault(); focusItem(roles.length - 1); }
    else if (e.key === "Tab") setOpen(false);
  };

  return (
    <div ref={wrapRef} className="relative">
      <button
        ref={pillRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Switch role, currently ${label}`}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onPillKey}
        className={cn(PILL, "hover:bg-grey-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy")}
      >
        <Icon size={15} className="text-navy" aria-hidden />
        <span className="hidden text-sm font-semibold text-navy sm:inline">{label}</span>
        <ChevronDown size={13} className="text-muted" aria-hidden />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="Switch role"
          onKeyDown={onMenuKey}
          className="absolute right-0 top-full z-50 mt-2 w-[280px] rounded border border-line bg-white p-1.5 shadow-md"
        >
          <p className="px-2 pb-1.5 pt-1 text-[0.544rem] font-bold uppercase tracking-wider text-muted">Switch role</p>
          {roles.map((r, i) => {
            const RIcon = ROLE_ICON[r];
            const current = r === role;
            return (
              <button
                key={r}
                ref={(el) => { itemRefs.current[i] = el; }}
                type="button"
                role="menuitemradio"
                aria-checked={current}
                onClick={() => select(r)}
                className={cn(
                  "flex w-full items-center gap-2.5 rounded px-2 py-1.5 text-left hover:bg-grey-100 focus-visible:bg-grey-100 focus-visible:outline-none",
                  current && "bg-navy-tint/60",
                )}
              >
                <span className="grid h-8 w-8 flex-shrink-0 place-items-center rounded bg-navy-tint text-navy">
                  <RIcon size={16} aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-ink">{STAFF_ROLE_LABELS[r]}</span>
                  <span className="block text-xs text-muted">{ROLE_META[r].purpose}</span>
                </span>
                {current && <Check size={14} className="flex-shrink-0 text-navy" aria-hidden />}
              </button>
            );
          })}
          <p className="mt-1 border-t border-line px-2 pt-1.5 text-[8.8px] text-muted">
            Actions are recorded with the role you worked as.
          </p>
        </div>
      )}
    </div>
  );
}
