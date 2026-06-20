// TODO: Account activation screen. Consume a one-time invite token (from query
// param) and let the invited staff member set their password to activate.
// Roles: any newly invited staff member (role assigned by Admin on invite).
// Backend: POST /api/staff/auth/activate { token, password }.

export default function StaffActivatePage() {
  return (
    <section>
      <h1>Activate Your Account</h1>
      <p>Use your one-time invite link to set a password and activate access.</p>
    </section>
  );
}
