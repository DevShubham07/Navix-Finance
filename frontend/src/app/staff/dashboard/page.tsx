// TODO: Role-aware overview. Show counts/queues relevant to the signed-in
// role (pending KYC, credit queue, releases awaiting authorisation, transfers
// awaiting confirmation, live DPD buckets).
// Roles: all staff (tiles filtered per role).
// Backend: GET /api/staff/dashboard (role-scoped summary).

export default function StaffDashboardPage() {
  return (
    <section>
      <h1>Dashboard</h1>
      <p>Role-aware overview of work queues and operational metrics.</p>
    </section>
  );
}
