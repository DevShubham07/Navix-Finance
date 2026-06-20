// Loan step 3: disbursement status tracker.
// TODO: poll backend for loan status (approved -> disbursement released by
//       Disbursement Head -> transfer confirmed by Accountant -> active) and
//       render a status tracker. Populate the LoanStatus domain type.

export default function LoanStatusPage() {
  return (
    <section>
      <h1>Your money is on the way</h1>
      <p>Track your disbursement here. We&apos;ll let you know once your loan is active.</p>
    </section>
  );
}
