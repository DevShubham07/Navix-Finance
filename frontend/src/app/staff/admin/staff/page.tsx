// TODO: Staff management. Admin lists staff accounts, assigns/revokes roles,
// and enables/disables accounts.
// Roles: Admin.
// Backend: GET /api/admin/staff ; PATCH /api/admin/staff/{id} (roles/status).

export default function AdminStaffPage() {
  return (
    <section>
      <h1>Admin · Staff</h1>
      <p>Manage staff accounts, roles, and access status.</p>
    </section>
  );
}
