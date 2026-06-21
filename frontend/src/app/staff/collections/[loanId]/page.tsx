// TODO: Collections case detail. Collection Officer logs an interaction
// (call/visit/promise-to-pay) and uploads proof for a specific overdue loan.
// Roles: Collection Officer (log + proof), Collections Head (oversight).
// Backend: GET /api/collections/{loanId} ;
//   POST /api/collections/{loanId}/interactions (with proof upload).

export default async function CollectionsCasePage({
  params,
}: {
  params: Promise<{ loanId: string }>;
}) {
  const { loanId } = await params;
  return (
    <section>
      <h1>Collections Case</h1>
      <p>Loan {loanId}: log interactions and attach proof.</p>
    </section>
  );
}
