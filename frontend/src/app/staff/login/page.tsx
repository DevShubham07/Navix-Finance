// TODO: Staff login screen. Render a credential form (email + password) and
// post to the backend to establish a staff session.
// Roles: all staff (KYC Approver, Credit Executive, Credit Head,
// Disbursement Head, Accountant, Collections Head, Collection Officer, Admin).
// Backend: POST /api/staff/auth/login -> sets session, returns role(s).

export default function StaffLoginPage() {
  return (
    <section>
      <h1>Staff Sign In</h1>
      <p>Internal NAVIX Finance console. Authorised staff only.</p>
    </section>
  );
}
