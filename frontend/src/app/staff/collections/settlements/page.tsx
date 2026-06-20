// TODO: Settlements / hardship plans. Collection Officer proposes a settlement
// or hardship repayment plan; Collections Head approves or rejects it.
// Separation of duties: proposer (Officer) != approver (Head).
// Roles: Collection Officer (propose), Collections Head (approve).
// Backend: GET /api/collections/settlements ;
//   POST /api/collections/settlements (propose) ;
//   POST /api/collections/settlements/{id}/approve.

export default function CollectionsSettlementsPage() {
  return (
    <section>
      <h1>Collections · Settlements</h1>
      <p>Propose and approve settlement and hardship plans.</p>
    </section>
  );
}
