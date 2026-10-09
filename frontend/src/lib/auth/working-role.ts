/**
 * The staffer's WORKING role (chosen in the header switcher), per staffer, in localStorage.
 *
 * Only imports rbac (types/functions) so the fetch layer can use it without a cycle. Every storage
 * access fails soft (private mode / blocked storage) — the default is the first allowed role.
 */

import { canWorkAs, workingRolesFor, STAFF_ROLES, type StaffRole } from "@/lib/auth/rbac";

export function workingRoleKey(staffId: string): string {
  return `navix-staff-working-role:${staffId}`;
}

let current: { real: StaffRole; working: StaffRole } | null = null;

export function readWorkingRole(staffId: string, real: StaffRole): StaffRole {
  let working = workingRolesFor(real)[0];
  try {
    const stored = localStorage.getItem(workingRoleKey(staffId)) as StaffRole | null;
    if (stored && STAFF_ROLES.includes(stored) && canWorkAs(real, stored)) working = stored;
  } catch {
    // ignore — see header
  }
  current = { real, working };
  return working;
}

export function writeWorkingRole(staffId: string, real: StaffRole, r: StaffRole): void {
  if (!canWorkAs(real, r)) return;
  try {
    localStorage.setItem(workingRoleKey(staffId), r);
  } catch {
    // ignore — see header
  }
  current = { real, working: r };
  // String literal on purpose: importing staff-session here would make a cycle.
  if (typeof window !== "undefined") window.dispatchEvent(new Event("navix-staff-session"));
}

export function clearWorkingRole(staffId: string): void {
  try {
    localStorage.removeItem(workingRoleKey(staffId));
  } catch {
    // ignore — see header
  }
  current = null;
}

/** The working role when it differs from the real one (sent as X-Acting-Role), else null. */
export function currentActingRole(): StaffRole | null {
  return current && current.working !== current.real ? current.working : null;
}
