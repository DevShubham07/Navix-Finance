// Loan step: review & sign documents.
// TODO: render the full cost breakdown (principal, 10% processing fee, 18% GST
//       on the fee, 1%/day interest, salary-day due date) BEFORE signing, then
//       collect e-signatures for the Loan Agreement, Sanction Letter, and KFS.
//       Populate the LoanDocuments domain type.

export default function LoanDocumentsPage() {
  return (
    <section>
      <h1>Review and sign your documents</h1>
      <p>See your full cost breakdown, then sign your Loan Agreement, Sanction Letter, and KFS.</p>
    </section>
  );
}
