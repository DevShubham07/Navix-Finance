// TODO: Staff invites. Admin sends an email invite (with assigned role) that
// produces a one-time activation link consumed on the /activate screen.
// Roles: Admin.
// Backend: GET /api/admin/invites ; POST /api/admin/invites { email, role }.

export default function AdminInvitesPage() {
  return (
    <section>
      <h1>Admin · Invites</h1>
      <p>Send email invites and track pending activations.</p>
    </section>
  );
}
