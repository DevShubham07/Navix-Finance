"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Users, X, RotateCcw } from "lucide-react";
import { STAFF_ROLES, STAFF_ROLE_LABELS, type StaffRole } from "@/lib/auth/rbac";
import { STAFF_PERSONA_NAMES } from "@/lib/api/staff-personas";
import { loginStaff, useStaffSession } from "@/lib/auth/staff-session";
import { useMounted } from "@/hooks/use-mounted";

/**
 * Role switcher. Sign in as any role in one click to exercise separation of
 * duties: recommend as a Credit Executive, switch to Credit Head to approve,
 * then try to act again and watch the maker-checker bar lock you out. Each pick
 * re-authenticates against the backend (role -> seeded account), so backend
 * calls carry the chosen role's JWT.
 */
export function StaffRoleBar() {
  const [open, setOpen] = React.useState(false);
  const router = useRouter();
  const mounted = useMounted();
  const { session } = useStaffSession();

  if (!mounted) return null;

  const pick = async (role: StaffRole) => {
    // Re-authenticate: sets the httpOnly navix_staff cookie (JWT) the BFF forwards as a bearer.
    await loginStaff(role);
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
                      <span className="block text-xs text-muted">{STAFF_PERSONA_NAMES[role]}</span>
                    </span>
                    {active ? <span className="text-xs font-semibold text-gold-dark">current</span> : null}
                  </button>
                );
              })}
            </div>
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
