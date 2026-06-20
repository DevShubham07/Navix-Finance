// Sign-up step 4: declared monthly salary.
// TODO: capture declared salary (in-app, since Account Aggregator is out of
//       scope now). Drives the 25% eligibility limit and the salary-day due
//       date. Populate the SalaryDeclaration domain type.

export default function SignupSalaryPage() {
  return (
    <section>
      <h1>Your monthly salary</h1>
      <p>Your declared salary sets your eligible limit (up to 25%) and repayment date.</p>
    </section>
  );
}
