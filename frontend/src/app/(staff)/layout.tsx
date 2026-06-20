import type { ReactNode } from "react";

// TODO: Wrap with RBAC guard — read the staff session/role and gate nav items
// per role (KYC Approver, Credit Executive, Credit Head, Disbursement Head,
// Accountant, Collections Head, Collection Officer, Admin). Redirect
// unauthenticated/customer users away from the (staff) route group.
// Backend: GET /api/staff/me (resolve current staff identity + roles).

const NAV_PLACEHOLDER: ReadonlyArray<{ label: string; href: string }> = [
  { label: "Dashboard", href: "/dashboard" },
  { label: "KYC Approvals", href: "/kyc-approvals" },
  { label: "Credit Queue", href: "/credit/queue" },
  { label: "Disbursement", href: "/disbursement" },
  { label: "Accounting", href: "/accounting" },
  { label: "Collections", href: "/collections/buckets" },
  { label: "Settlements", href: "/collections/settlements" },
  { label: "Admin · Staff", href: "/admin/staff" },
  { label: "Admin · Invites", href: "/admin/invites" },
  { label: "Admin · Blocklist", href: "/admin/blocklist" },
];

export default function StaffLayout({ children }: { children: ReactNode }) {
  return (
    <div className="staff-shell">
      <aside className="staff-shell__nav">
        {/* TODO: render only nav entries the current role is allowed to see */}
        <strong>NAVIX Staff Console</strong>
        <nav>
          <ul>
            {NAV_PLACEHOLDER.map((item) => (
              <li key={item.href}>
                <a href={item.href}>{item.label}</a>
              </li>
            ))}
          </ul>
        </nav>
      </aside>
      <main className="staff-shell__content">{children}</main>
    </div>
  );
}
