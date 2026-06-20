// Repayment: single repayment on salary day (prepay allowed, no penalty).
// TODO: show the outstanding amount (principal + 1%/day interest to date), let
//       the borrower pay manually via UPI/bank transfer and upload proof, and
//       support early prepayment. Populate the Repayment domain type.

export default function RepayPage() {
  return (
    <section>
      <h1>Repay your loan</h1>
      <p>Pay your single repayment on salary day, or prepay anytime with no penalty.</p>
    </section>
  );
}
