// TODO: Fraud blocklist. Admin manages blocked identifiers — PAN, Aadhaar
// reference, phone, device, and bank account — checked during onboarding.
// Roles: Admin.
// Backend: GET /api/admin/blocklist ;
//   POST /api/admin/blocklist { type, value } ;
//   DELETE /api/admin/blocklist/{id}.

export default function AdminBlocklistPage() {
  return (
    <section>
      <h1>Admin · Fraud Blocklist</h1>
      <p>Manage blocked PAN, Aadhaar ref, phone, device, and bank entries.</p>
    </section>
  );
}
