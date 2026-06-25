"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Users, X, RotateCcw, Database } from "lucide-react";
import { STAFF_ROLES, type StaffRole } from "@/lib/auth/rbac";
import { signInStaff, STAFF_ROLE_LABELS, STAFF_PERSONAS, useStaffSession } from "@/lib/mock/session";
import { useMockDb } from "@/lib/mock/store";
import { useMounted } from "@/hooks/use-mounted";

/**
 * Prototype role switcher (clickable demo only). Sign in as any role in one
 * click to exercise separation of duties: recommend as a Credit Executive,
 * switch to Credit Head to approve, then try to act again and watch the
 * maker-checker bar lock you out. Also resets the seeded console data.
 */
export function StaffRoleBar() {
  const [open, setOpen] = React.useState(false);
  const router = useRouter();
  const mounted = useMounted();
  const { session } = useStaffSession();
  const resetDemo = useMockDb((s) => s.resetDemo);

  if (!mounted) return null;

  const pick = async (role: StaffRole) => {
    // Mock session (navix_session cookie + localStorage) keeps the demo pages + middleware working.
    signInStaff(role);
    try {
      // Live session: the httpOnly navix_staff cookie the BFF reads, so backend calls actually carry
      // the chosen role (without this the switch is cosmetic — the backend keeps the previous actor).
      await fetch("/api/auth/staff/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ role }),
      });
    } catch {
      // Non-fatal: the mock console still works without the live session.
    }
    setOpen(false);
    router.refresh();
  };

  return (
    <>
      {open && <div data-staffbar className="fixed inset-0 z-[190] bg-navy/30" onClick={() => setOpen(false)} aria-hidden />}
      <div data-staffbar className="fixed bottom-4 right-4 z-[200] flex flex-col items-end gap-3">
        {open && (
          <div className="w-[min(92vw,320px)] overflow-hidden rounded-lg border border-line bg-white shadow-lg">
            <div className="flex items-center justify-between bg-navy-900 px-4 py-3 text-white">
              <span className="flex items-center gap-2 font-serif text-sm font-semibold">
                <Users size={16} className="text-gold" /> Act as role
              </span>
              <button onClick={() => setOpen(false)} aria-label="Close" className="rounded p-1 hover:bg-white/10">
                <X size={16} />
              </button>
            </div>
            <div className="max-h-[55vh] overflow-y-auto p-2">
              {STAFF_ROLES.map((role) => {
                const active = session?.role === role;
                return (
                  <button
                    key={role}
                    onClick={() => pick(role)}
                    className={`flex w-full items-center justify-between gap-3 rounded px-3 py-2 text-left text-sm transition ${
                      active ? "bg-navy-tint font-semibold text-navy" : "text-ink hover:bg-grey-100"
                    }`}
                  >
                    <span>
                      <span className="block">{STAFF_ROLE_LABELS[role]}</span>
                      <span className="block text-xs text-muted">{STAFF_PERSONAS[role].name}</span>
                    </span>
                    {active ? <span className="text-xs font-semibold text-gold-dark">current</span> : null}
                  </button>
                );
              })}
            </div>
            <button
              onClick={() => { resetDemo(); setOpen(false); router.refresh(); }}
              className="flex w-full items-center gap-2 border-t border-grey-200 px-4 py-2.5 text-sm text-muted hover:bg-grey-100 hover:text-ink"
            >
              <Database size={14} /> Reset console data
            </button>
          </div>
        )}
        <button onClick={() => setOpen((o) => !o)} className="btn btn-navy btn-sm shadow-lg" aria-label="Act as role" aria-expanded={open}>
          {open ? <RotateCcw size={15} /> : <Users size={15} />}
          <span className="hidden sm:inline">{session ? STAFF_ROLE_LABELS[session.role] : "Role"}</span>
        </button>
      </div>
    </>
  );
}
