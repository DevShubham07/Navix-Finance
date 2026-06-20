// Loan step 1: choose amount.
// TODO: let the borrower pick an amount up to their eligible limit (25% of
//       declared salary). Show live cost preview (10% processing fee + 18% GST
//       on the fee, 1%/day interest). Populate the LoanApplication domain type.

export default function LoanApplyPage() {
  return (
    <section>
      <h1>Choose your loan amount</h1>
      <p>Pick an amount up to your eligible limit. We&apos;ll show the full cost next.</p>
    </section>
  );
}
