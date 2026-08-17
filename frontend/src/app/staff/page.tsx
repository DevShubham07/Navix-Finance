import { redirect } from "next/navigation";
import { getStaffSession } from "@/lib/api/bff-session";

/**
 * Staff console index. There is no standalone landing screen for the bare
 * `/staff` segment — the console starts at the role-aware dashboard — so hitting
 * `/staff` directly used to render Next's 404 (no `page.tsx` existed here).
 *
 * Middleware already bounces unauthenticated visitors to `/staff/login`; an
 * authenticated visitor who types `/staff` is sent to their role's landing page.
 * A DSA has no dashboard permission at all (firewalled portal role — see
 * lib/auth/rbac.ts), so it would otherwise render a bare "no access" dashboard;
 * send it straight to the DSA's own leads queue instead.
 */
export default async function StaffIndexPage() {
  const session = await getStaffSession();
  if (session?.role === "DSA") {
    redirect("/staff/dsa/leads");
  }
  redirect("/staff/dashboard");
}
