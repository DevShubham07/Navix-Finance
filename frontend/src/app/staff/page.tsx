import { redirect } from "next/navigation";

/**
 * Staff console index. There is no standalone landing screen for the bare
 * `/staff` segment — the console starts at the role-aware dashboard — so hitting
 * `/staff` directly used to render Next's 404 (no `page.tsx` existed here).
 *
 * Middleware already bounces unauthenticated visitors to `/staff/login`; an
 * authenticated visitor who types `/staff` is sent straight to the dashboard.
 */
export default function StaffIndexPage() {
  redirect("/staff/dashboard");
}
