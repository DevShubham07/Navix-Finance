// TODO: Credit queue. Credit Head assigns applications to a Credit Executive;
// Credit Executive sees applications assigned to them for review.
// Separation of duties: reviewer (Executive) != final approver (Head).
// Roles: Credit Head (assign), Credit Executive (review).
// Backend: GET /api/credit/queue ; POST /api/credit/{applicationId}/assign.

export default function CreditQueuePage() {
  return (
    <section>
      <h1>Credit Queue</h1>
      <p>Assign and pick up applications for credit review.</p>
    </section>
  );
}
